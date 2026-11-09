package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.ui.components.config.RearCardCreateDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.GraphicsMode

/** Production dialog and editable controls; inert callbacks instead of device repositories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearCardSuggestionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val show = mutableStateOf(true)
    private val business = mutableStateOf("")
    private val priority = mutableStateOf("500")
    private val catalog = mutableStateOf(listOf(
        RearBusinessConfig("alpha", "fixture.alpha", "clock.alpha", "/fixture/alpha", defaultPriority = 123),
        RearBusinessConfig("beta", "fixture.beta", "clock.beta", "/fixture/beta", defaultPriority = 456)))
    private var confirms = 0
    private var dismisses = 0
    @Before fun enabled() { assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled")) }
    @After fun close() {
        compose.runOnUiThread { activity?.close() }
        compose.mainClock.autoAdvance = true
    }
    private fun frames() { repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        // performScrollTo schedules native scrolling; manual capture time would
        // otherwise leave its repeat-until-visible loop at the same position.
        compose.mainClock.autoAdvance = true
        return try { performScrollTo() } finally { compose.mainClock.autoAdvance = false }
    }
    private fun editor() = compose.onNode(
        hasSetTextAction() and (hasTestTag("rear-card-business-input") or
            hasAnyAncestor(hasTestTag("rear-card-business-input"))), useUnmergedTree = true)
    private fun option(value: String) = compose.onNode(hasText(value) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun open(backend: MixUiBackend, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    RearCardCreateDialog(show.value, 0, {}, catalog.value,
                        "fixture", {}, "fixture.target", {}, business.value, { business.value = it },
                        priority.value, { priority.value = it }, false, {}, false, {},
                        "fixture app", {}, 0, {}, { confirms++ }, {},
                        { dismisses++; show.value = false })
                }
            }
        }
        frames()
    }
    private fun capture(backend: MixUiBackend, dark: Boolean, step: String) {
        val folder = File(System.getProperty("ui.capture.output"), "card-suggestions").apply { mkdirs() }
        val name = "${backend.name.lowercase()}-${if (dark) "dark" else "light"}-$step"
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val frame = compose.runOnUiThread { captureDialogWindowBitmap(dialog) }
        File(folder, "$name-dialog.png").outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        // Separate native popup window, deliberately not presented as a full page.
        if (step == "filter") {
            val popup = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            File(folder, "$name-popup.png").outputStream().use { check(popup.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    private fun exercise(backend: MixUiBackend, dark: Boolean) {
        open(backend, dark)
        assertEquals("Opening must not confirm", 0, confirms)
        assertEquals("Opening must not dismiss", 0, dismisses)
        compose.onNode(hasText(label(R.string.rear_widget_confirm)) and hasClickAction()).assertIsNotEnabled()
        editor().reveal().performClick(); frames()
        editor().performTextReplacement("clock"); frames()
        option("clock.alpha").assertIsDisplayed()
        option("clock.beta").assertIsDisplayed()
        capture(backend, dark, "filter")
        @Suppress("DEPRECATION")
        compose.runOnUiThread { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
        frames()
        option("clock.alpha").assertDoesNotExist()
        assertEquals(0, dismisses)
        editor().performTextReplacement("clock."); frames()
        option("clock.alpha").assertIsDisplayed()
        // An open popup must not steal editor focus or freeze dynamic filtering.
        editor().performTextReplacement("beta"); frames()
        option("clock.alpha").assertDoesNotExist()
        option("clock.beta").assertIsDisplayed()
        editor().performTextReplacement("unmatched"); frames()
        option("clock.beta").assertDoesNotExist()
        editor().performTextReplacement("beta"); frames()
        compose.runOnUiThread { catalog.value = catalog.value +
            RearBusinessConfig("gamma", "fixture.gamma", "beta.gamma", "/fixture/gamma", defaultPriority = 789) }
        frames()
        option("beta.gamma").assertIsDisplayed()
        option("clock.beta").performClick(); frames()
        assertEquals("clock.beta", business.value)
        assertEquals("456", priority.value)
        capture(backend, dark, "selected")
        option("clock.beta").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.rear_widget_confirm)).reveal().performClick()
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
        compose.onNodeWithText(label(R.string.rear_widget_cancel)).reveal().performClick(); frames()
        assertEquals(1, dismisses)
        editor().assertDoesNotExist()
    }
    @Test(timeout = 60_000) fun miuixLight() = exercise(MixUiBackend.Miuix, false)
    @Test(timeout = 60_000) fun miuixDark() = exercise(MixUiBackend.Miuix, true)
    @Test(timeout = 60_000) fun mochiLight() = exercise(MixUiBackend.Mochi, false)
    @Test(timeout = 60_000) fun mochiDark() = exercise(MixUiBackend.Mochi, true)
}
