package hk.uwu.reareye.agent.tools

import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleCodec
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReareyeToolEnvironmentSafetyTest {
    @Test
    fun lockedComponentAndDependentCardsCannotBeMigrated() {
        assertFalse(canRenameComponent(false, emptyList()))
        assertFalse(canRenameComponent(false, listOf(true)))
        assertFalse(canRenameComponent(true, listOf(true, false)))
        assertTrue(canRenameComponent(true, listOf(true, true)))
        assertTrue(canRenameComponent(true, emptyList()))
    }

    @Test
    fun qualifiedScriptIdDisambiguatesIdenticalLocalNames() {
        val kinds = listOf("STANDALONE", "PROJECT", "COMPONENT")
        assertEquals(3, kinds.count { scriptIdMatches("demo", it, "demo") })
        for (kind in kinds) {
            val qualified = qualifiedScriptId(kind, "demo")
            assertEquals(listOf(kind), kinds.filter { scriptIdMatches(qualified, it, "demo") })
        }
        assertTrue(kinds.none { scriptIdMatches("UNKNOWN:demo", it, "demo") })
    }

    @Test
    fun scriptingReferenceIsEnglishAndIncludesDocumentedCapabilitiesAndLimits() {
        val spec =
            ReareyeToolEnvironment(RuntimeEnvironment.getApplication()).scriptingModelReference()
        assertFalse(Regex("[\\p{IsHan}]").containsMatchIn(spec))
        for (required in listOf(
            "context.root:exec", "context.http:request", "context:readText",
            "86400000", "262144", "120000", "bytecode", "runtime descriptor validation"
        )) {
            assertTrue(required, spec.contains(required))
        }
    }

    @Test
    fun allEditingCapabilitiesAreUniqueAndDispatchedRatherThanMerelyAdvertised() = runBlocking {
        val environment = ReareyeToolEnvironment(RuntimeEnvironment.getApplication())
        val tools = environment.availableTools()
        assertEquals(tools.size, tools.map { it.name }.distinct().size)
        val sourceEdits = listOf("update_script", "update_component")
        val edits = listOf(
            "rename_script",
            "update_card_config",
            "update_wallpaper_config",
            "update_component_settings",
            "update_card_order",
            "update_scene_route"
        )
        val reads = listOf(
            "read_script", "list_script_files", "read_component", "get_card_config",
            "get_wallpaper_config", "get_component_settings"
        )
        for (name in edits + reads + sourceEdits + "create_script") {
            val spec = tools.single { it.name == name }
            if (name in sourceEdits) assertEquals(name, ToolRisk.DESTRUCTIVE, spec.risk)
            if (name in edits) assertEquals(name, ToolRisk.MUTATING, spec.risk)
            if (name in reads) assertEquals(name, ToolRisk.READ_ONLY, spec.risk)
            // No required input/service: every implemented route must reject safely, not fall
            // through the registry to "Unknown tool" or write defaults over real configuration.
            val result = environment.execute(name, JsonObject(emptyMap()))
            assertTrue(name, result.isError)
            assertFalse(name, result.content.contains("Unknown tool"))
        }
    }

    @Test
    fun wallpaperIntervalEditsPreserveOrderAndOmittedDelay() {
        val original =
            listOf(RearWallpaperScheduleEntry(7, 900L), RearWallpaperScheduleEntry(8, 1200L))
        val updated = updatedMaryWallpaperSchedule(original, 7, true, 1500L)
        assertEquals(listOf(7, 8), updated.map { it.wallpaperId })
        assertEquals(listOf(1500L, 1200L), updated.map { it.delayMs })
        assertEquals(original, updatedMaryWallpaperSchedule(original, 7, true, null))
        assertEquals(listOf(original[1]), updatedMaryWallpaperSchedule(original, 7, false, null))
        assertEquals(
            listOf(7, 8, 9),
            updatedMaryWallpaperSchedule(original, 9, true, null).map { it.wallpaperId })
        assertEquals(
            RearWallpaperScheduleCodec.MIN_DELAY_MS,
            updatedMaryWallpaperSchedule(original, 7, true, -1L).first().delayMs
        )
    }

    @Test
    fun renameSchemaHasNoLockedOverrideAndDangerousToolsRequireConfirmation() {
        val tools = ReareyeToolEnvironment(RuntimeEnvironment.getApplication()).availableTools()
        val rename = tools.single { it.name == "rename_component" }
        assertTrue(rename.params.none { it.name == "confirm_locked_edit" })
        for (name in listOf(
            "delete_card", "delete_app_card", "delete_component", "delete_script",
            "delete_scene_route", "delete_wallpaper", "create_script", "create_html_widget"
        )) {
            assertEquals(name, ToolRisk.DESTRUCTIVE, tools.single { it.name == name }.risk)
        }
        for (tool in tools) {
            assertFalse(tool.name, Regex("[\\p{IsHan}]").containsMatchIn(tool.description))
            assertTrue(tool.params.all { !Regex("[\\p{IsHan}]").containsMatchIn(it.description) })
        }
    }
}
