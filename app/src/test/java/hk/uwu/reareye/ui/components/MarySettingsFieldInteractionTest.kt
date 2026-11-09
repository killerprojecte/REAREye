package hk.uwu.reareye.ui.components

import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.reareye.ui.capture.captureWindowBitmap
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.components.LargeTitleColumnScaffold
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.Card
import hk.uwu.reareye.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Offline field transactions, using real controls and native in-window form hosts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarySettingsFieldInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var value by mutableStateOf("")
    private val writes = mutableListOf<String>()
    private var label = R.string.mary_provider_endpoint

    @Before fun explicitUiRun() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    private fun frames() {
        repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }

    private fun host(backend: MixUiBackend, initial: String, password: Boolean = false, dark: Boolean = false, rtl: Boolean = false) {
        compose.runOnUiThread {
            activity?.close()
            value = initial
            writes.clear()
            label = if (password) R.string.mary_provider_api_key else R.string.mary_provider_endpoint
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend = backend, colorMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    CompositionLocalProvider(LocalLayoutDirection provides
                        if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    LargeTitleColumnScaffold(title = "Mary settings") {
                        Card {
                            MarySettingsField(value, { value = it; writes += it }, label, password)
                        }
                    }
                    }
                }
            }
        }
        frames()
    }

    private fun open() {
        compose.onNodeWithText(activity!!.get().getString(label)).performClick()
        frames()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File(System.getProperty("ui.capture.output"), "mary-settings-field")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onAllNodes(isRoot()).filter(hasAnyDescendant(hasSetTextAction()))
            .onFirst().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun button(resource: Int) {
        compose.onNodeWithText(activity!!.get().getString(resource)).performClick()
        frames()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun cancelDiscardsAndReopeningCopiesTheLatestParentValue() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, "https://offline.example.invalid/v1", dark = dark)
            open()
            screenshot("${backend.name}-${if (dark) "dark" else "light"}")
            compose.onNode(hasSetTextAction()).performTextReplacement("not saved")
            compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
            button(R.string.rear_widget_cancel)
            compose.runOnIdle {
                assertEquals("https://offline.example.invalid/v1", value)
                assertEquals(emptyList<String>(), writes)
                value = "https://updated.example.invalid/v2"
            }
            frames()
            open()
            compose.onNode(hasSetTextAction()).assertTextContains("https://updated.example.invalid/v2")
            compose.onNode(hasSetTextAction()).performTextReplacement("https://saved.example.invalid/v3")
            button(R.string.rear_widget_confirm)
            compose.runOnIdle { assertEquals(listOf("https://saved.example.invalid/v3"), writes) }
            open()
            compose.onNode(hasSetTextAction()).assertTextContains("https://saved.example.invalid/v3")
            button(R.string.rear_widget_cancel)
            compose.runOnIdle { assertEquals(1, writes.size) }
        }
    }

    @Test fun secretSummaryStaysMaskedAndClearingRequiresConfirm() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, "fake-offline-secret", password = true, dark = dark)
            compose.onNodeWithText("••••••", substring = true).assertIsDisplayed()
            compose.onNodeWithText("fake-offline-secret", substring = true).assertDoesNotExist()
            open()
            compose.onNode(hasSetTextAction()).performTextClearance()
            button(R.string.rear_widget_cancel)
            compose.runOnIdle { assertEquals("fake-offline-secret", value); assertEquals(0, writes.size) }
            open()
            compose.onNode(hasSetTextAction()).performTextClearance()
            button(R.string.rear_widget_confirm)
            compose.runOnIdle { assertEquals("", value); assertEquals(listOf(""), writes) }
        }
    }
    @Test fun repeatedSubmitOldCallbacksAndNativeDismissalDoNotChangeNewDrafts() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, "original", dark = dark)
            open()
            compose.onNode(hasSetTextAction()).performTextReplacement("first")
            val confirm = compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_confirm))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnUiThread { confirm(); confirm() }
            frames()
            assertEquals(listOf("first"), writes)
            open()
            compose.onNode(hasSetTextAction()).performTextReplacement("second")
            // Vendor overlays may detach or retain their exiting native node.
            // Only the known detached-node rejection is allowed; retained actions
            // must still leave the new draft and committed value unchanged below.
            compose.runOnUiThread {
                val failure = runCatching { confirm() }.exceptionOrNull()
                assertTrue("Unexpected failure invoking retired native action: $failure",
                    failure == null || (failure is IllegalStateException &&
                        failure.message.orEmpty().contains("not currently attached")))
            }
            frames()
            compose.onNode(hasSetTextAction()).assertTextContains("second")
            assertEquals(listOf("first"), writes)
            compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_confirm))
                .performTouchInput { down(center); advanceEventTime(32); cancel() }
            frames()
            assertEquals(listOf("first"), writes)
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            frames()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            open()
            compose.onNode(hasSetTextAction()).assertTextContains("first")
            compose.onNode(isRoot() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true)
                .performTouchInput { click(Offset(2f, 2f)) }
            frames()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            open()
            button(R.string.rear_widget_cancel)
            assertEquals(listOf("first"), writes)
        }
    }

    /** Real field/form, controlled page source. Native-renderer evidence, not device shader QA. */
    @Test fun fieldGlassRespondsToPageButDoesNotSampleItsOwnEditor() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            var body by mutableStateOf(Color(0xFF202020))
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                label = R.string.mary_provider_endpoint
                activity!!.get().setContent {
                    MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        GlassPreferenceScene {
                            Box(Modifier.fillMaxSize().background(body)) {
                                MarySettingsField("short", {}, label)
                            }
                        }
                    }
                }
            }
            frames(); open()
            val target = compose.onNodeWithTag("mary-field-dialog-$label", useUnmergedTree = true)
            fun shot(name: String): Bitmap {
                val window = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
                val dir = File(System.getProperty("ui.capture.output"), "mary-settings-field").apply { mkdirs() }
                File(dir, "${backend.name}-$dark-$name-window.png").outputStream().use {
                    check(window.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val bounds = target.fetchSemanticsNode().boundsInWindow
                return Bitmap.createBitmap(window, bounds.left.roundToInt(), bounds.top.roundToInt(),
                    bounds.width.roundToInt(), bounds.height.roundToInt())
            }
            val before = shot("initial")
            compose.onNode(hasSetTextAction()).performTextReplacement("MMMMMMMMMMMMMMMMMMMMMMMM")
            frames()
            val edited = shot("edited")
            compose.runOnIdle { body = Color(0xFF80C0F0) }
            frames()
            val changed = shot("body-changed")
            fun difference(a: Bitmap, b: Bitmap): Double {
                var sum = 0L; var count = 0L
                // Blank left interior margin, outside input/button fills and corner clipping.
                for (y in 48 until a.height - 48) for (x in 8 until 16) {
                    val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                    for (shift in listOf(0, 8, 16)) {
                        sum += abs(((p shr shift) and 255) - ((q shr shift) and 255)); count++
                    }
                }
                return sum.toDouble() / count
            }
            val self = difference(before, edited)
            val source = difference(edited, changed)
            println("MARY_FIELD_SAMPLING backend=$backend dark=$dark self=$self source=$source")
            assertTrue("Editor must not contaminate its own glass: $self", self < 1.5)
            assertTrue("Glass must sample the page: $source", source > 2.0)
        }
    }

    @Test fun actualSettingsPageEditsEndpointAndBudgetWithoutPersistingBeforeSave() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            val snapshot = kotlinx.serialization.json.Json.encodeToString(
                hk.uwu.reareye.agent.data.SettingsSnapshot.serializer(),
                hk.uwu.reareye.agent.data.SettingsSnapshot(hk.uwu.reareye.agent.AgentConfig()))
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().getSharedPreferences(
                    "reareye_agent_secrets", 0)
                    .edit().clear().putString(
                        "settings_snapshot_v1", snapshot).commit()
                activity!!.get().setContent {
                    MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        hk.uwu.reareye.ui.screen.MarySettingsScreen(onBack = {})
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeBy(100)
                compose.onAllNodesWithTag("mary-model-card-${R.string.mary_settings_provider}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            frames()
            for ((field, replacement) in listOf(
                R.string.mary_provider_endpoint to "https://offline.example.invalid/v1",
                R.string.mary_behaviour_turn_budget to "240",
            )) {
                val title = activity!!.get().getString(field)
                // Native animated scroll needs clock progress; leaving the test
                // clock paused deadlocks performScrollTo before the editor opens.
                compose.mainClock.autoAdvance = true
                compose.onNodeWithText(title).performScrollTo()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText(title).performClick()
                frames()
                val dialog = "mary-field-dialog-$field"
                compose.onNodeWithTag(dialog, useUnmergedTree = true).assertIsDisplayed()
                val actualPage = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
                val output = File(System.getProperty("ui.capture.output"), "mary-settings-field").apply { mkdirs() }
                File(output, "actual-settings-${backend.name}-$dark-field-$field.png").outputStream().use {
                    check(actualPage.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(dialog)), useUnmergedTree = true)
                    .performTextReplacement(replacement)
                compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_confirm)).performClick()
                frames()
                compose.onNodeWithTag(dialog, useUnmergedTree = true).assertDoesNotExist()
                // Native animated scroll needs clock progress; leaving the test
                // clock paused deadlocks performScrollTo before the editor opens.
                compose.mainClock.autoAdvance = true
                compose.onNodeWithText(title).performScrollTo()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText(title).performClick()
                frames()
                compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(dialog)), useUnmergedTree = true)
                    .assertTextContains(replacement)
                compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_cancel)).performClick()
                frames()
            }
            assertEquals(snapshot, activity!!.get().getSharedPreferences(
                "reareye_agent_secrets", 0)
                .getString("settings_snapshot_v1", null))
        }
    }

    @Test fun fieldActionsStaySideBySideInLogicalOrderForBothDirections() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true))
            for (rtl in listOf(false, true)) {
                host(backend, "original", dark = dark, rtl = rtl)
                open()
                val cancel = compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_cancel))
                val confirm = compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_confirm))
                cancel.assertIsDisplayed(); confirm.assertIsDisplayed()
                val cancelBounds = cancel.fetchSemanticsNode().boundsInWindow
                val confirmBounds = confirm.fetchSemanticsNode().boundsInWindow
                assertEquals("Actions must share one row", cancelBounds.center.y, confirmBounds.center.y, 1f)
                assertTrue("Cancel must precede confirm in layout direction",
                    if (rtl) confirmBounds.right <= cancelBounds.left else cancelBounds.right <= confirmBounds.left)
                assertTrue("Labels must have nonempty visible bounds", cancelBounds.width > 0 && confirmBounds.width > 0)
                screenshot("${backend.name}-$dark-${if (rtl) "rtl" else "ltr"}-actions")
                compose.onNode(hasSetTextAction()).performTextReplacement("cancelled")
                cancel.performClick(); frames()
                assertEquals(emptyList<String>(), writes)
                open()
                compose.onNode(hasSetTextAction()).assertTextContains("original")
                compose.onNode(hasSetTextAction()).performTextReplacement("confirmed")
                button(R.string.rear_widget_confirm)
                assertEquals(listOf("confirmed"), writes)
            }
    }

}
