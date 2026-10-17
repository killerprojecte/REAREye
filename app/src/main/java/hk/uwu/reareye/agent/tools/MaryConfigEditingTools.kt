package hk.uwu.reareye.agent.tools

import android.content.Context
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperRepository
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigFields
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.widgetapi.RearWidgetTemplateConfigState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal class MaryConfigEditingTools(context: Context) {
    private val context = context.applicationContext
    private val prefs get() = context.getPrefsManager()
    private val validation = MaryConfigEditingValidation

    suspend fun execute(name: String, args: JsonObject): ToolResult? {
        val spec = SPECS.singleOrNull { it.name == name } ?: return null
        return withContext(Dispatchers.IO) {
            operationMutex.withLock {
                try {
                    check(isRemoteReady()) { "REMOTE_UNAVAILABLE: connect module service before reading or writing" }
                    require(args.keys.all { key -> spec.params.any { it.name == key } }) { "Unknown argument" }
                    spec.params.filter { it.required }
                        .forEach { require(it.name in args) { "Missing ${it.name}" } }
                    val result = when (name) {
                        "get_card_config", "update_card_config" -> card(
                            args,
                            name.startsWith("update")
                        )

                        "get_wallpaper_config", "update_wallpaper_config" -> wallpaper(
                            args,
                            name.startsWith("update")
                        )

                        "get_component_settings", "update_component_settings" -> component(
                            args,
                            name.startsWith("update")
                        )

                        "update_card_order" -> order(args)
                        "update_scene_route" -> route(args)
                        else -> error("Unreachable")
                    }
                    val content = result.toString()
                    check(content.length <= RESULT_LIMIT) { "RESULT_TOO_LARGE: configuration exceeds safe complete-result limit; no truncated schema returned" }
                    ToolResult.ok(content)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    ToolResult.error(buildJsonObject {
                        put(
                            "code", when {
                                message.startsWith("REMOTE_UNAVAILABLE") -> "REMOTE_UNAVAILABLE"
                                message.startsWith("READBACK_MISMATCH") -> "READBACK_MISMATCH"
                                message.startsWith("RESULT_TOO_LARGE") -> "RESULT_TOO_LARGE"
                                message.startsWith("CONCURRENT_MODIFICATION") -> "CONCURRENT_MODIFICATION"
                                e is IllegalArgumentException -> "INVALID_ARGUMENT"
                                else -> "CONFIG_OPERATION_FAILED"
                            }
                        )
                        put("message", message.take(1500))
                        put("success", false)
                    }.toString())
                }
            }
        }
    }

    private fun text(args: JsonObject, key: String) = validation.string(args[key]).also {
        require(it.isNotBlank()) { "$key must not be blank" }
    }

    private fun optional(args: JsonObject, key: String) = if (key in args) text(args, key) else null
    private fun values(args: JsonObject) = validation.objectJson(text(args, "values_json")).also {
        require(it.toString().length <= 65536) { "values_json too large" }
    }

    private fun isRemoteReady(): Boolean = try {
        prefs.isRemoteReady()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun ready() {
        check(isRemoteReady()) { "REMOTE_UNAVAILABLE: connection lost" }
    }

    private fun unchanged(condition: Boolean) {
        check(condition) { "CONCURRENT_MODIFICATION: configuration changed during validation; refresh and retry. A multi-step order write may have partially applied" }
    }

    private fun stateResult(state: RearWidgetTemplateConfigState): JsonObject {
        val schema = validation.objectJson(state.templateSchemaJson)
        return buildJsonObject {
            put("schema", schema)
            put("fields", validation.constraints(schema))
            put("one_config", validation.objectJson(state.oneConfigJson))
            put(
                "values_contract",
                "values_json is a JSON-encoded flat object {fieldName: typedValue}; fields with mary_writable=false cannot be updated. Existing unknown data is preserved. Images accept only schema options. No raw storage buckets."
            )
        }
    }

    private fun merged(
        state: RearWidgetTemplateConfigState,
        args: JsonObject,
        rawCurrent: String? = null
    ): JsonObject {
        // Retain unknown root/bucket values from persisted JSON even if the remote decoder drops them.
        val effective = validation.objectJson(state.oneConfigJson)
        val original = rawCurrent?.takeIf { it.isNotBlank() }?.let(validation::objectJson)
        val current = if (original == null) effective else JsonObject(effective + original)
        return validation.merge(
            validation.objectJson(state.templateSchemaJson),
            current,
            values(args)
        ).also {
            require(it.toString().length <= RESULT_LIMIT - 2048) { "Merged config exceeds safe result limit; nothing saved" }
        }
    }

    private fun card(args: JsonObject, write: Boolean): JsonObject {
        val id = text(args, "card_id")
        val cards = RearWidgetManagerRepository.loadCards(prefs)
        val card =
            cards.singleOrNull { it.id == id } ?: throw IllegalArgumentException("Card not found")
        val businesses = RearWidgetManagerRepository.loadBusinesses(prefs)
        val orderSettings = RearWidgetManagerRepository.loadCardOrderSettings(prefs)
        val business = businesses.singleOrNull { it.business == card.business }
            ?: throw IllegalArgumentException("Component not found")
        if (write) require(card.renameable && business.renameable) { "Locked card/component" }
        val state = RearWidgetManagerRepository.resolveTemplateConfigState(
            context,
            card.business,
            business.filePath,
            card.oneConfigJson
        )
            ?: error("Template config unavailable")
        if (!write) return JsonObject(stateResult(state) + buildJsonObject {
            put("card_id", id)
            put("business", card.business)
            put("writable", card.renameable && business.renameable)
            put("template_config_available", true)
            put("persisted_one_config", card.oneConfigJson?.let(validation::objectJson) ?: JsonNull)
            put(
                "edit_restrictions",
                "Both card and component must be unlocked; per-field mary_writable alone does not grant object-level edit permission."
            )
        })
        val merged = merged(state, args, card.oneConfigJson)
        val next = cards.map { if (it.id == id) it.copy(oneConfigJson = merged.toString()) else it }
        ready()
        unchanged(
            RearWidgetManagerRepository.loadCards(prefs) == cards &&
                    RearWidgetManagerRepository.loadBusinesses(prefs) == businesses &&
                    RearWidgetManagerRepository.loadCardOrderSettings(prefs) == orderSettings
        )
        RearWidgetManagerRepository.saveCards(context, prefs, next)
        ready()
        val actual = RearWidgetManagerRepository.loadCards(prefs)
        validation.verify(actual.associateBy { it.id } == next.associateBy { it.id })
        return buildJsonObject {
            put("success", true); put("card_id", id); put(
            "one_config",
            merged
        )
        }
    }

    private suspend fun wallpaper(args: JsonObject, write: Boolean): JsonObject {
        val p = args["wallpaper_id"] as? JsonPrimitive
        val id = p?.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException("wallpaper_id must be integer")
        val before = RearWallpaperRepository.resolveTemplateConfigState(context, id, null) ?: error(
            "Wallpaper/template not found"
        )
        if (!write) return JsonObject(stateResult(before) + buildJsonObject {
            put("wallpaper_id", id)
            put("template_config_available", true)
            put("writable", JsonNull)
            put(
                "edit_restrictions",
                "Write permission is not exposed by template-state reads. The remote save API is authoritative: runtime wallpapers require a valid template; existing wallpapers require an existing writable config path. No arbitrary paths/imports or config reset are supported. A readable schema does not guarantee write permission."
            )
        })
        val merged = merged(before, args)
        ready()
        val fresh = RearWallpaperRepository.resolveTemplateConfigState(context, id, null)
        unchanged(
            fresh != null && validation.objectJson(fresh.oneConfigJson) == validation.objectJson(
                before.oneConfigJson
            ) &&
                    validation.objectJson(fresh.templateSchemaJson) == validation.objectJson(before.templateSchemaJson)
        )
        ready()
        val saved = RearWallpaperRepository.saveTemplateConfig(context, id, merged.toString())
        check(saved.success) { saved.error ?: "Wallpaper save rejected" }
        ready()
        val after = RearWallpaperRepository.resolveTemplateConfigState(context, id, null)
        validation.verify(after != null && validation.objectJson(after.oneConfigJson) == merged)
        return buildJsonObject {
            put("success", true); put("wallpaper_id", id); put(
            "one_config",
            merged
        )
        }
    }

    private fun component(args: JsonObject, write: Boolean): JsonObject {
        val business = text(args, "business")
        val businesses = RearWidgetManagerRepository.loadBusinesses(prefs)
        val component = businesses.singleOrNull { it.business == business }
            ?: throw IllegalArgumentException("Component not found")
        val allConfigs = RearBusinessExtraConfigRepository.getAllConfigs(prefs)
        var config = RearBusinessExtraConfigRepository.getConfigForBusiness(prefs, business)
        if (write) {
            require(component.renameable) { "Locked component" }
            val p = args["hide_time_tip"] as? JsonPrimitive
            require(p != null && !p.isString && p.booleanOrNull != null) { "hide_time_tip must be boolean" }
            val expected =
                config.withBoolean(RearBusinessExtraConfigFields.HIDE_TIME_TIP, p.boolean)
            ready()
            unchanged(
                RearWidgetManagerRepository.loadBusinesses(prefs) == businesses &&
                        RearBusinessExtraConfigRepository.getAllConfigs(prefs) == allConfigs &&
                        RearBusinessExtraConfigRepository.getConfigForBusiness(
                            prefs,
                            business
                        ) == config
            )
            RearBusinessExtraConfigRepository.saveConfigForBusiness(prefs, business, expected)
            ready()
            config = RearBusinessExtraConfigRepository.getConfigForBusiness(prefs, business)
            validation.verify(
                config.values == expected.values &&
                    RearBusinessExtraConfigRepository.getAllConfigs(prefs)
                        .associate { it.business to it.config } ==
                    (allConfigs.associate { it.business to it.config } + (business to expected)))
        }
        return buildJsonObject {
            put("business", business); put("hide_time_tip", !config.getShowTimeTipOrDefault())
            put("writable", component.renameable)
            put(
                "constraints",
                "Only hide_time_tip:boolean is supported; all other settings preserved"
            )
            if (write) put("success", true)
        }
    }

    private fun order(args: JsonObject): JsonObject {
        val cards = RearWidgetManagerRepository.loadCards(prefs)
        val settings = RearWidgetManagerRepository.loadCardOrderSettings(prefs)
        val (next, nextSettings) = validation.order(
            cards, settings, validation.strings(args["card_ids"]),
            if ("automatic_ids" in args) validation.strings(args["automatic_ids"]) else null
        )
        ready()
        unchanged(
            RearWidgetManagerRepository.loadCards(prefs) == cards &&
                    RearWidgetManagerRepository.loadCardOrderSettings(prefs) == settings
        )
        RearWidgetManagerRepository.saveCardOrderSettings(prefs, nextSettings)
        ready()
        unchanged(
            RearWidgetManagerRepository.loadCards(prefs)
                .associateBy { it.id } == cards.associateBy { it.id } &&
                    RearWidgetManagerRepository.loadCardOrderSettings(prefs) == nextSettings)
        RearWidgetManagerRepository.saveCards(context, prefs, next)
        ready()
        validation.verify(
            RearWidgetManagerRepository.loadCardOrderSettings(prefs) == nextSettings &&
                    RearWidgetManagerRepository.loadCards(prefs) == next
        )
        return buildJsonObject {
            put("success", true)
            put("effective_card_ids", JsonArray(next.map { JsonPrimitive(it.id) }))
            put(
                "automatic_ids",
                JsonArray(next.filter { nextSettings[it.id]?.automatic == true }
                    .map { JsonPrimitive(it.id) })
            )
            put("priorities", JsonObject(next.associate { it.id to JsonPrimitive(it.priority) }))
        }
    }

    private fun route(args: JsonObject): JsonObject {
        require(
            listOf(
                "package_name",
                "scene",
                "business"
            ).any { it in args }) { "At least one route change is required" }
        val routes = RearWidgetManagerRepository.loadSceneRoutes(prefs)
        val businesses = RearWidgetManagerRepository.loadBusinesses(prefs)
        val next = validation.route(
            routes, businesses, text(args, "id"),
            optional(args, "package_name"), optional(args, "scene"), optional(args, "business")
        )
        ready()
        unchanged(
            RearWidgetManagerRepository.loadSceneRoutes(prefs) == routes &&
                    RearWidgetManagerRepository.loadBusinesses(prefs) == businesses
        )
        RearWidgetManagerRepository.saveSceneRoutes(context, prefs, next)
        ready()
        validation.verify(
            RearWidgetManagerRepository.loadSceneRoutes(prefs)
                .associateBy { it.id } == next.associateBy { it.id })
        return buildJsonObject {
            put("success", true); put(
            "id",
            next[routes.indexOfFirst { it.id == text(args, "id") }].id
        )
        }
    }

    companion object {
        private const val RESULT_LIMIT = 65536
        private val operationMutex = Mutex()
        private fun param(
            name: String,
            description: String,
            type: ToolParam.ParamType = ToolParam.ParamType.STRING,
            required: Boolean = true
        ) =
            ToolParam(name, description, type, required)

        private val patch = param(
            "values_json",
            "JSON-encoded flat object keyed by field name. Read get_*_config first for complete schema and mary_writable constraints; typed values, not raw OneConfig buckets."
        )
        val SPECS: List<ToolSpec> = buildList {
            for ((entity, id) in listOf(
                "card" to param("card_id", "Existing card UUID"),
                "wallpaper" to param(
                    "wallpaper_id",
                    "Existing wallpaper ID",
                    ToolParam.ParamType.INTEGER
                )
            )) {
                add(
                    ToolSpec(
                        "get_${entity}_config",
                        "Read complete template schema, constraints and current OneConfig. Complex fields marked mary_writable=false are read-only. No truncation; oversized configs return an error.",
                        listOf(id),
                        ToolRisk.READ_ONLY,
                        RESULT_LIMIT
                    )
                )
                add(
                    ToolSpec(
                        "update_${entity}_config",
                        "Merge validated schema field values; reject unknown/read-only fields and arbitrary image paths. Locked cards/components cannot be written. Remote readiness and complete readback required.",
                        listOf(id, patch),
                        ToolRisk.MUTATING,
                        RESULT_LIMIT
                    )
                )
            }
            add(
                ToolSpec(
                    "get_component_settings",
                    "Read component hide_time_tip boolean and editability.",
                    listOf(param("business", "Existing component business")),
                    ToolRisk.READ_ONLY
                )
            )
            add(
                ToolSpec(
                    "update_component_settings",
                    "Update only hide_time_tip, preserve other settings; reject locked components; verify readback.",
                    listOf(
                        param("business", "Existing component business"),
                        param(
                            "hide_time_tip",
                            "Whether to hide the time tip",
                            ToolParam.ParamType.BOOLEAN
                        )
                    ),
                    ToolRisk.MUTATING
                )
            )
            add(
                ToolSpec(
                    "update_card_order",
                    "card_ids must contain ALL current cards exactly once. Optional automatic_ids is the complete automatic-mode subset; omitted preserves modes (missing metadata follows repository automatic default). Manual priorities never change; automatic priorities use existing repository rules. Effective priority-first order may differ from requested positions; returned effective_card_ids is authoritative. Locked card order/mode changes rejected.",
                    listOf(
                        param(
                            "card_ids",
                            "Exact full ordered card ID list",
                            ToolParam.ParamType.STRING_ARRAY
                        ),
                        param(
                            "automatic_ids",
                            "Complete automatic subset; omit to preserve modes",
                            ToolParam.ParamType.STRING_ARRAY,
                            false
                        )
                    ),
                    ToolRisk.MUTATING,
                    RESULT_LIMIT
                )
            )
            add(
                ToolSpec(
                    "update_scene_route",
                    "Replace an existing route once, preserving provenance; validate regex, existing unlocked source/target components and package/scene conflicts; ID regenerated with shared codec.",
                    listOf(
                        param("id", "Original route ID"),
                        param("package_name", "New package pattern", required = false),
                        param("scene", "New scene pattern", required = false),
                        param("business", "New target component business", required = false)
                    ),
                    ToolRisk.MUTATING
                )
            )
        }
    }
}
