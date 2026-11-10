package hk.uwu.reareye.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderPreset
import hk.uwu.reareye.agent.data.SettingsSnapshot
import hk.uwu.reareye.ui.capture.captureWindowBitmap
import hk.uwu.reareye.ui.screen.MarySettingsScreen
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real settings page, persisted local snapshot, pointer scrolling and native button materials.
 * Native graphics captures supplement material inspection; they are not device GPU evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarySettingsTopBarLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val controls = listOf(
        "mary-settings-back" to R.string.scene_route_navigate_back,
        "mary-settings-presets" to R.string.mary_provider_preset,
        "mary-settings-save" to R.string.mary_settings_save,
    )

    @Before fun requireResources() {
        assumeTrue("Run with -PmaryUiResources=true", java.lang.Boolean.getBoolean("mary.ui.resources"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    @Test fun miuixSettingsButtonsKeepNativeGlassAndActionsAcrossScroll() = exercise(MixUiBackend.Miuix, false)
    @Test fun mochiSettingsButtonsKeepNativeGlassAndActionsAcrossScroll() = exercise(MixUiBackend.Mochi, false)
    @Test fun miuixDarkSettingsButtonsKeepNativeGlassAndActionsAcrossScroll() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiDarkSettingsButtonsKeepNativeGlassAndActionsAcrossScroll() = exercise(MixUiBackend.Mochi, true)

    private fun exercise(backend: MixUiBackend, dark: Boolean) {
        var backs = 0
        val snapshot = Json.encodeToString(SettingsSnapshot.serializer(), SettingsSnapshot(AgentConfig()))
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().getSharedPreferences("reareye_agent_secrets", 0).edit()
                .clear().putString("settings_snapshot_v1", snapshot).commit()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light,
                    mochiStyle = MixMochiStyle.Ios) {
                    MarySettingsScreen(onBack = { backs++ })
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithTag("mary-model-card-${R.string.mary_settings_provider}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        frames()
        assertControls()
        assertNativeButtons(backend, scrolled = false)
        capture("$backend-$dark-top")
        val initial = offset()
        repeat(3) { drag(up = true) }
        assertTrue("Actual settings content must scroll", offset() > initial)
        assertControls()
        assertNativeButtons(backend, scrolled = true)
        capture("$backend-$dark-scrolled")
        // The native toolbar's preset anchor must still reopen after selecting a draft.
        repeat(2) {
            node("mary-settings-presets").performClick(); frames()
            compose.onNodeWithText(ProviderPreset.entries.first().label).assertIsDisplayed().performClick()
            frames()
            compose.onNodeWithText(ProviderPreset.entries.first().label).assertDoesNotExist()
        }
        val prefs = activity!!.get().getSharedPreferences("reareye_agent_secrets", 0)
        assertEquals("Preset selection must remain an unsaved draft", snapshot,
            prefs.getString("settings_snapshot_v1", null))
        node("mary-settings-save").performClick()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            prefs.getString("settings_snapshot_v1", null) != snapshot
        }
        frames()
        assertControls()
        repeat(30) { if (offset() > 0f) drag(up = false) }
        repeat(2) { drag(up = false) } // Restore the large-title bar as well as the column.
        assertEquals("Pointer scrolling must restore the settings page top", 0f, offset(), 0f)
        assertControls()
        assertNativeButtons(backend, scrolled = false)
        capture("$backend-$dark-returned")
        node("mary-settings-back").performClick()
        assertEquals(1, backs)
    }

    private fun assertControls() {
        controls.forEach { (tag, label) ->
            node(tag).assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                .assertContentDescriptionEquals(activity!!.get().getString(label))
        }
        val back = node(controls[0].first).getUnclippedBoundsInRoot()
        val presets = node(controls[1].first).getUnclippedBoundsInRoot()
        val save = node(controls[2].first).getUnclippedBoundsInRoot()
        assertTrue(back.right <= presets.left)
        assertTrue(presets.right <= save.left)
        assertEquals((presets.top.value + presets.bottom.value) / 2f, (save.top.value + save.bottom.value) / 2f, 1f)
    }

    private fun assertNativeButtons(backend: MixUiBackend, scrolled: Boolean) {
        controls.forEach { (tag, _) ->
            // Native MIUIX renders material and icon in sibling layout nodes. Limit
            // inspection to this button's subtree, excluding the page/header surface.
            val info = node(tag).fetchSemanticsNode().layoutInfo
            compose.runOnIdle {
                val expected = if (backend == MixUiBackend.Miuix)
                    "top.yukonga.miuix.kmp.blur.DrawBackdropElement"
                else "ui.feice.mochi.effect.glass.engine.GlassElement"
                val elements = layoutDescendants(info)
                    .flatMap { it.getModifierInfo().asSequence() }.map { it.modifier }
                    .filter { it.javaClass.name == expected }.toList()
                if (backend == MixUiBackend.Miuix) {
                    val enabled = elements.any { element ->
                        element.javaClass.getDeclaredField("enabled").apply { isAccessible = true }
                            .getBoolean(element)
                    }
                    assertEquals("$tag must follow the native settings bar's material visibility", scrolled, enabled)
                } else {
                    // BoundedMochiTitleBar selects the native clear surface at rest,
                    // matching Home and Configuration, without fading the icon.
                    assertEquals("$tag must follow the native Mochi bar visibility", scrolled, elements.isNotEmpty())
                }
            }
        }
    }

    private fun layoutDescendants(node: LayoutInfo): Sequence<LayoutInfo> = sequence {
        yield(node)
        val method = node.javaClass.methods.first {
            it.name.startsWith("getChildren$") && it.parameterCount == 0
        }
        @Suppress("UNCHECKED_CAST")
        val children = method.invoke(node) as List<LayoutInfo>
        children.forEach { yieldAll(layoutDescendants(it)) }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun scroll() = compose.onNode(hasScrollAction())
    private fun offset() = scroll().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun drag(up: Boolean) {
        val bounds = scroll().fetchSemanticsNode().boundsInRoot
        val toolbar = node("mary-settings-back").fetchSemanticsNode().boundsInRoot
        val top = maxOf(bounds.top + 16f, toolbar.bottom + 24f) - bounds.top
        val bottom = bounds.height - 32f
        scroll().performTouchInput {
            swipe(Offset(width * 0.1f, if (up) bottom else top),
                Offset(width * 0.1f, if (up) top else bottom), 600)
        }
        frames()
    }
    private fun frames() { repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun capture(name: String) {
        val directory = File(System.getProperty("ui.capture.output", "build/ui-captures"),
            "mary-settings-topbar").apply { mkdirs() }
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
