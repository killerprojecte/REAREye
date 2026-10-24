package hk.uwu.reareye.agent.tools

import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearCardConfig
import hk.uwu.reareye.repository.rearwidget.RearCardOrderSetting
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearWidgetOneConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetSceneRouteConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MaryConfigEditingToolsTest {
    private val v = MaryConfigEditingValidation
    private fun obj(raw: String) = v.objectJson(raw)
    private val schema = obj(
        """{"items":[
        {"tagName":"Text","name":"title","editable":true,"minLength":2,"maxLength":8,"valueList":[]},
        {"tagName":"Text","name":"preset","editable":false,"minLength":0,"maxLength":8,"valueList":["one","two"]},
        {"tagName":"SeekBar","name":"size","from":1,"to":8},
        {"tagName":"OnOff","name":"enabled"},
        {"tagName":"ImagePick","name":"photo","options":[{"value":"existing.png"}]},
        {"tagName":"DropDown","name":"mode","items":[{"value":"safe"}]},
        {"tagName":"CustomEditLink","name":"link"}
    ]}"""
    )

    @Test
    fun mergePreservesUnknownBucketsAndUnchangedValues() {
        val current =
            obj("""{"unknown":{"keep":[1,2]},"textConfig":{"legacy":"unchanged","title":"before"}}""")
        val result = v.merge(
            schema,
            current,
            obj("""{"title":"after","size":5,"enabled":true,"photo":"existing.png"}""")
        )
        assertEquals(current["unknown"], result["unknown"])
        assertEquals(JsonPrimitive("unchanged"), (result["textConfig"] as JsonObject)["legacy"])
        assertEquals(JsonPrimitive(5), (result["seekBarSaveConfig"] as JsonObject)["size"])
        assertEquals(JsonPrimitive(true), (result["onOffConfig"] as JsonObject)["enabled"])
        assertEquals(JsonPrimitive("before"), (current["textConfig"] as JsonObject)["title"])
    }

    @Test
    fun invalidChangesFailBeforePersistenceCallback() {
        for (raw in listOf(
            "{}", "{\"unknown\":1}", "{\"title\":1}", "{\"title\":\"x\"}",
            "{\"title\":\"way too long\"}", "{\"preset\":\"free\"}", "{\"size\":9}",
            "{\"size\":\"5\"}", "{\"size\":1.5}", "{\"enabled\":\"true\"}",
            "{\"enabled\":1}", "{\"photo\":\"/sdcard/secret.png\"}", "{\"mode\":\"bad\"}",
            "{\"link\":\"anything\"}", "{\"title\":null}"
        )) {
            var writes = 0
            try {
                v.merge(schema, obj("{}"), obj(raw)); writes++
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(raw, 0, writes)
        }
    }

    @Test
    fun schemasAdvertiseApprovalAndExactArrayContracts() {
        assertEquals(8, MaryConfigEditingTools.SPECS.size)
        MaryConfigEditingTools.SPECS.forEach { spec ->
            assertEquals(
                if (spec.name.startsWith("get_")) ToolRisk.READ_ONLY else ToolRisk.MUTATING,
                spec.risk
            )
            assertTrue(spec.params.none { it.name == "confirm_locked_edit" })
        }
        val spec = MaryConfigEditingTools.SPECS.single { it.name == "update_card_order" }
        assertTrue(spec.params.all { it.type == ToolParam.ParamType.STRING_ARRAY })
        assertFalse(spec.params.single { it.name == "automatic_ids" }.required)
        val readonly = v.constraints(schema).last() as JsonObject
        assertEquals(JsonPrimitive(false), readonly["mary_writable"])
    }

    @Test
    fun disconnectedExecuteRejectsEveryMutationAndUnknownFallsThrough() = runBlocking {
        val tools = MaryConfigEditingTools(RuntimeEnvironment.getApplication())
        assertNull(tools.execute("not_a_tool", obj("{}")))
        for (spec in MaryConfigEditingTools.SPECS.filter { it.risk == ToolRisk.MUTATING }) {
            val result = tools.execute(spec.name, obj("{}"))!!
            assertTrue(spec.name, result.isError)
            assertEquals(JsonPrimitive("REMOTE_UNAVAILABLE"), obj(result.content)["code"])
        }
    }

    private fun card(id: String, priority: Int = 500, unlocked: Boolean = true) =
        RearCardConfig(id, id, "pkg", "business", priority = priority, renameable = unlocked)

    private fun business(name: String, unlocked: Boolean = true) =
        RearBusinessConfig(name, "pkg", name, "", renameable = unlocked)

    @Test
    fun orderPreservesManualValuesAndUsesRepositoryAutomaticSequence() {
        val cards = listOf(card("a", 901), card("b", 42), card("c", 20))
        val settings = mapOf(
            "a" to RearCardOrderSetting(false, 0),
            "b" to RearCardOrderSetting(true, 1),
            "c" to RearCardOrderSetting(true, 2)
        )
        val (next, meta) = v.order(cards, settings, listOf("c", "a", "b"), null)
        assertEquals(901, next.single { it.id == "a" }.priority)
        assertEquals(600, next.single { it.id == "b" }.priority)
        assertEquals(601, next.single { it.id == "c" }.priority)
        assertFalse(meta.getValue("a").automatic)
        val (manual, manualMeta) = v.order(cards, settings, listOf("c", "b", "a"), emptyList())
        assertEquals(
            cards.associate { it.id to it.priority },
            manual.associate { it.id to it.priority })
        assertTrue(manualMeta.values.none { it.automatic })
    }

    @Test
    fun orderRejectsPartialDuplicateUnknownAndLockedChanges() {
        val cards = listOf(card("a"), card("b"))
        fails { v.order(cards, emptyMap(), listOf("a"), null) }
        fails { v.order(cards, emptyMap(), listOf("a", "a"), null) }
        fails { v.order(cards, emptyMap(), listOf("a", "x"), null) }
        fails { v.order(cards, emptyMap(), listOf("a", "b"), listOf("x")) }
        fails { v.order(listOf(card("a", unlocked = false)), emptyMap(), listOf("a"), null) }
    }

    @Test
    fun routeUsesSharedIdNormalizesAliasesAndPreservesProvenance() {
        val route = RearWidgetSceneRouteConfig(
            "old",
            "pkg",
            "phone",
            "a",
            downloadedFromStore = true,
            storeWidgetId = "store"
        )
        val next = v.route(
            listOf(route),
            listOf(business("a"), business("b")),
            "old",
            "new.pkg",
            "taxi",
            "b"
        ).single()
        assertEquals(RearWidgetConfigCodec.newSceneRouteId("new.pkg", "taxi"), next.id)
        assertEquals("carHailing", next.scene)
        assertEquals("store", next.storeWidgetId)
        assertTrue(next.downloadedFromStore)
    }

    @Test
    fun routeRejectsConflictsMissingComponentsInvalidRegexAndLockedSource() {
        val routes = listOf(
            RearWidgetSceneRouteConfig("old", "pkg", "phone", "a"),
            RearWidgetSceneRouteConfig("other", "pkg", "taxi", "b")
        )
        val components = listOf(business("a"), business("b"))
        fails { v.route(routes, components, "old", null, "carHailing", null) }
        fails { v.route(routes, components, "old", null, "regex:[", null) }
        fails { v.route(routes, components, "old", null, "regex:", null) }
        fails { v.route(routes, components, "old", null, null, "missing") }
        fails {
            v.route(
                routes,
                listOf(business("a", false), business("b")),
                "old",
                null,
                null,
                "b"
            )
        }
    }

    @Test
    fun readbackMismatchCannotBecomeSuccess() {
        try {
            v.verify(false); fail("Must reject mismatched readback")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.startsWith("READBACK_MISMATCH"))
        }
        v.verify(true)
    }

    private val compositeSchema = obj(
        """{"items":[
        {"tagName":"ColorGroup","name":"colors","items":[{"name":"fg","values":["#FFFFFF"]},{"name":"bg","values":["#000000"]}]},
        {"tagName":"SetDate","name":"date","repeatName":"repeat","repeatValue":2},
        {"tagName":"CustomColor","name":"tint","values":["#FFFFFF"],"index":3},
        {"tagName":"MultiImageSelect","name":"images","single":true,"items":[{},{}]},
        {"tagName":"Intent","name":"intent","returnValue":["label","count","ok"],"valueType":["string","int","boolean"]},
        {"tagName":"AnimatVar","name":"anim","x":0,"y":0,"scaleX":1,"scaleY":1,"rotation":0,"displayScale":1},
        {"tagName":"BackgroudMode","name":"bg","value":0,"resType":0,"uri":"safe.png","videoUri":"","foregroundUri":"","uriName":"image","videoName":"video","foregroundName":"front"},
        {"tagName":"MultiModeImageList","name":"list","size":1,"items":[{"value":0,"resType":0,"uri":"safe.png","videoUri":"","foregroundUri":""}]}
    ]}"""
    )

    @Test
    fun compositeValuesFollowUiFormatAndPreserveUnknownData() {
        val current =
            obj("""{"colorGroupConfig":{"name":"colors","index":0,"legacy":7,"selectColors":{"fg":"#FFFFFF","bg":"#000000","old":"keep"}},"dateSetConfig":{"name":"date","date":0,"repeatName":"keep","repeatValue":9},"intentSaveConfig":{"intent":{"name":"intent","returnValueString":{"old":"keep"},"returnValueNumber":{}}}}""")
        val updates =
            obj("""{"colors":{"fg":"#112233"},"date":"2026-10-16","tint":"#FF123456","images":[0,1],"intent":{"label":"hello","count":3,"ok":true},"anim":{"x":3.5},"bg":{"value":2,"uri":"safe.png"},"list":{"size":1,"itemList":[{"value":4}]}}""")
        val next = v.merge(compositeSchema, current, updates)
        val group = next["colorGroupConfig"] as JsonObject
        assertEquals(JsonPrimitive(7), group["legacy"])
        assertEquals(JsonPrimitive("keep"), (group["selectColors"] as JsonObject)["old"])
        assertEquals(JsonPrimitive(9), (next["dateSetConfig"] as JsonObject)["repeatValue"])
        assertEquals(JsonPrimitive(3), (next["customColorSaveConfig"] as JsonObject)["index"])
        val intent = com.google.gson.Gson()
            .fromJson(next.toString(), RearWidgetOneConfig::class.java).intentSaveConfig!!.getValue(
            "intent"
        )
        assertEquals("keep", intent.returnValueString["old"])
        assertEquals(1.0, intent.returnValueNumber["ok"]!!, 0.0)
        assertEquals(JsonPrimitive(1.0), (next["animationSaveConfig"] as JsonObject)["scaleX"])
        assertEquals(
            JsonPrimitive("image"),
            (next["backgroundModeSaveConfig"] as JsonObject)["uriName"]
        )
    }

    @Test
    fun compositeInvalidTypesAndResourceInjectionAreRejected() {
        for (raw in listOf(
            """{"colors":{"unknown":"#FFFFFF"}}""",
            """{"colors":{"fg":"bad"}}""",
            """{"date":"2026-02-30"}""",
            """{"date":123}""",
            """{"tint":"bad"}""",
            """{"images":[1,1]}""",
            """{"images":[1]}""",
            """{"images":[0,0.5]}""",
            """{"intent":{"unknown":1}}""",
            """{"intent":{"count":1.5}}""",
            """{"intent":{"ok":"true"}}""",
            """{"anim":{"name":"inject"}}""",
            """{"anim":{"x":"3"}}""",
            """{"bg":{"uri":"/sdcard/private.png"}}""",
            """{"bg":{"uriName":"inject"}}""",
            """{"list":{"size":-1}}""",
            """{"list":{"itemList":[]}}""",
            """{"list":{"itemList":[{"uri":"arbitrary.png"}]}}"""
        )) {
            fails { v.merge(compositeSchema, obj("{}"), obj(raw)) }
        }
    }

    @Test
    fun flatIntentUpdatesAreVisibleThroughOfficialOneConfigAdapter() {
        val current =
            obj("""{"intentSaveConfig":{"name":"intent","legacy":{"keep":true},"returnValueString":{"label":"before","old":"preserve"},"returnValueNumber":{"count":1.0}}}""")
        val next = v.merge(
            compositeSchema,
            current,
            obj("""{"intent":{"label":"after","count":9,"ok":true}}""")
        )
        val bucket = next["intentSaveConfig"] as JsonObject
        assertEquals(JsonPrimitive("intent"), bucket["name"])
        assertFalse(bucket.containsKey("intent"))
        assertEquals((current["intentSaveConfig"] as JsonObject)["legacy"], bucket["legacy"])
        val gson = com.google.gson.Gson()
        val decoded = gson.fromJson(next.toString(), RearWidgetOneConfig::class.java)
        val actual = decoded.intentSaveConfig!!.getValue("intent")
        assertEquals("after", actual.returnValueString["label"])
        assertEquals("preserve", actual.returnValueString["old"])
        assertEquals(9.0, actual.returnValueNumber["count"]!!, 0.0)
        assertEquals(1.0, actual.returnValueNumber["ok"]!!, 0.0)
        val roundTrip = gson.fromJson(gson.toJson(decoded), RearWidgetOneConfig::class.java)
        assertEquals(actual, roundTrip.intentSaveConfig!!.getValue("intent"))
    }

    @Test
    fun addingSecondIntentConvertsFlatStorageToOfficialMultiEntryEncoding() {
        val items = compositeSchema["items"] as JsonArray
        val twoIntents =
            JsonObject(compositeSchema + ("items" to JsonArray(items + obj("""{"tagName":"Intent","name":"second","returnValue":["value"],"valueType":["string"]}"""))))
        val current =
            obj("""{"intentSaveConfig":{"name":"intent","legacy":42,"returnValueString":{"label":"before"},"returnValueNumber":{"count":2.0}}}""")
        val next = v.merge(
            twoIntents,
            current,
            obj("""{"second":{"value":"second-after"},"intent":{"label":"first-after"}}""")
        )
        val storage = next["intentSaveConfig"] as JsonObject
        assertFalse(storage.containsKey("name"))
        assertEquals(JsonPrimitive(42), (storage["intent"] as JsonObject)["legacy"])
        val gson = com.google.gson.Gson()
        val decoded = gson.fromJson(next.toString(), RearWidgetOneConfig::class.java)
        assertEquals(setOf("intent", "second"), decoded.intentSaveConfig!!.keys)
        assertEquals(
            "first-after",
            decoded.intentSaveConfig!!.getValue("intent").returnValueString["label"]
        )
        assertEquals(
            2.0,
            decoded.intentSaveConfig!!.getValue("intent").returnValueNumber["count"]!!,
            0.0
        )
        assertEquals(
            "second-after",
            decoded.intentSaveConfig!!.getValue("second").returnValueString["value"]
        )
        val encoded = gson.toJson(decoded)
        val decodedAgain = gson.fromJson(encoded, RearWidgetOneConfig::class.java)
        assertEquals(decoded.intentSaveConfig, decodedAgain.intentSaveConfig)
    }

    private fun fails(action: () -> Unit) {
        try {
            action(); fail("Expected validation failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
