package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Context
import android.os.Bundle
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.components.config.RearAppCardManagementContent
import hk.uwu.reareye.repository.rearwidget.RearAppCardRepository
import hk.uwu.reareye.widgetapi.*
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.ui.components.config.RearAppCardRenameScene
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production rename form/scene. Repository effects are observed at the callback boundary.
 * Pixel assertions use native Robolectric rendering, not physical-device/IME proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearAppCardRenameGlassTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val visible = mutableStateOf(false)
    private val draft = mutableStateOf("Original name")
    private val body = mutableStateOf(Color(0xFF202020))
    private var submits = 0
    private var saved = "Original name"
    private var reject = false

    @Before fun enabled() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close() }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(20) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun host(backend: String, dark: Boolean) {
        compose.runOnUiThread {
            activity?.close()
            visible.value = false; draft.value = "Original name"; saved = "Original name"
            body.value = Color(0xFF202020); submits = 0; reject = false
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    RearAppCardRenameScene(visible.value, draft.value, { draft.value = it },
                        { visible.value = false }, {
                            submits++
                            if (!reject) { saved = draft.value; visible.value = false }
                        }) {
                        Box(Modifier.fillMaxSize().background(body.value).testTag("rename-page")) {
                            Button(onClick = { draft.value = saved; visible.value = true },
                                modifier = Modifier.testTag("rename-open")) { Text("Rename") }
                        }
                    }
                }
            }
        }
        settle()
    }
    // The native Mochi tag belongs to its decoration container, while the actual
    // editable child owns SetText/RequestFocus. MIUIX can put both on one node.
    private fun editor() = compose.onNode(
        hasSetTextAction() and (hasTestTag("app-card-rename-title") or
            hasAnyAncestor(hasTestTag("app-card-rename-title"))), useUnmergedTree = true,
    )
    private fun replaceTitle(value: String) {
        editor().performTextReplacement(value)
        // autoAdvance is disabled for capture stability. Flush the native field's
        // onValueChange and the sibling overlay's enabled state before interacting.
        settle()
        editor().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }
    private fun confirm() = compose.onNode(hasText(activity!!.get().getString(R.string.rear_widget_confirm)) and
        hasAnyAncestor(hasTestTag("app-card-rename-actions")))
    private fun cancel() = compose.onNode(hasText(activity!!.get().getString(R.string.rear_widget_cancel)) and
        hasAnyAncestor(hasTestTag("app-card-rename-actions")))
    private fun open() { compose.onNodeWithTag("rename-open").performClick(); settle() }
    private fun closed() {
        settle()
        confirm().assertDoesNotExist()
        compose.onNodeWithTag("rename-page").assertIsDisplayed()
    }
    @Test fun realRenameFormPreservesValidationCancelFailureAndReopen() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            host(backend, dark); open()
            val cancel = cancel().fetchSemanticsNode().boundsInRoot
            val confirm = confirm().fetchSemanticsNode().boundsInRoot
            assertEquals(cancel.top, confirm.top, 1f)
            assertTrue("Cancel and Confirm must share one row", cancel.right <= confirm.left)
            replaceTitle("")
            confirm().assertIsNotEnabled()
            confirm().performTouchInput { click(center) }
            settle()
            assertEquals("Blank titles must not invoke submit", 0, submits)
            confirm().assertIsDisplayed()
            replaceTitle("Discard me")
            cancel().performClick(); closed()
            assertEquals(0, submits); assertEquals("Original name", saved)
            open()
            editor().assertTextContains("Original name")
            replaceTitle("Renamed")
            reject = true
            confirm().performClick(); settle()
            confirm().assertIsDisplayed()
            assertEquals("Original name", saved)
            reject = false
            confirm().performClick(); closed()
            assertEquals(2, submits); assertEquals("Renamed", saved)
            open()
            editor().assertTextContains("Renamed")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; closed()
            open()
            compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("app-card-rename-actions")), useUnmergedTree = true)
                .performTouchInput { click(Offset(2f, 2f)) }
            closed()
            assertEquals(2, submits)
        }
    }

    @Test fun realManagementListOpensAndRenamesThroughRepository() {
        val clientField = RearAppCardRepository::class.java.getDeclaredField("remoteClient").apply { isAccessible = true }
        val oldClient = clientField.get(RearAppCardRepository)
        try {
            for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
                var renamed = "Calendar agenda"
                var writes = 0
                val fakeClient = object : RearAppApiClient() {
                    override fun bind(context: Context, onConnected: (() -> Unit)?): Boolean {
                        onConnected?.invoke(); return true
                    }
                    override fun getCatalog(): Bundle = Bundle().apply {
                        putParcelableArrayList(RearAppApiContract.BundleKeys.ITEMS, ArrayList(
                            listOf(renamed, "Weather forecast", "Train journey", "Daily steps", "Music player", "Delivery status")
                                .mapIndexed { index, title -> RearAppCardInfo("app-$index", title,
                                    null, "", null, true, true, true, false, 0).toBundle() }))
                    }
                    override fun renameAppCard(appId: String, title: String): Bundle {
                        assertEquals("app-0", appId)
                        renamed = title; writes++
                        return RearAppOperationResult(success = true).toBundle()
                    }
                }
                clientField.set(RearAppCardRepository, fakeClient)
                compose.runOnUiThread {
                    activity?.close()
                    activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                    activity!!.get().setContent {
                        CaptureTheme(backend, dark) {
                            RearAppCardManagementContent(prefsManager = PrefsManager(GuideMemoryPrefs()))
                        }
                    }
                }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeBy(100)
                    compose.onAllNodesWithText("Calendar agenda").fetchSemanticsNodes().isNotEmpty()
                }
                settle()
                compose.onNodeWithText("Calendar agenda").performClick(); settle()
                capture("$backend-${if (dark) "dark" else "light"}-real-list")
                replaceTitle("Discard this")
                cancel().performClick(); settle()
                assertEquals(0, writes)
                compose.onNodeWithText("Calendar agenda").performClick(); settle()
                editor().assertTextContains("Calendar agenda")
                replaceTitle("Trip agenda")
                confirm().performClick()
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeBy(100)
                    compose.onAllNodesWithText("Trip agenda").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithTag("app-card-rename-actions").fetchSemanticsNodes().isEmpty()
                }
                settle()
                assertEquals(1, writes)
                compose.onNodeWithText("Trip agenda").assertIsDisplayed()
                capture("$backend-${if (dark) "dark" else "light"}-real-list-saved")
            }
        } finally {
            compose.runOnUiThread { activity?.close(); activity = null }
            clientField.set(RearAppCardRepository, oldClient)
        }
    }

    private fun capture(name: String): Bitmap {
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        val dir = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"), "app-card-rename")
        check(dir.mkdirs() || dir.isDirectory)
        File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }
    @Test fun renameGlassSamplesBodyWithoutForegroundFeedback() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            host(backend, dark); open()
            val field = compose.onNodeWithTag("app-card-rename-title").fetchSemanticsNode()
            val actions = compose.onNodeWithTag("app-card-rename-actions").fetchSemanticsNode()
            val origin = IntArray(2)
            compose.runOnUiThread { activity!!.get().window.decorView.getLocationOnScreen(origin) }
            // Empty native material in the gap between solid field and action row.
            val left = (actions.positionOnScreen.x - origin[0] + 20).roundToInt()
            val right = left + actions.size.width - 40
            val top = (field.positionOnScreen.y - origin[1] + field.size.height + 2).roundToInt()
            val bottom = (actions.positionOnScreen.y - origin[1] - 2).roundToInt()
            assertTrue("Need an unobstructed glass sample", right > left && bottom > top)
            fun difference(a: Bitmap, b: Bitmap): Double {
                var sum = 0L; var count = 0L
                for (y in top until bottom) for (x in left until right) {
                    val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                    for (shift in listOf(0, 8, 16)) {
                        sum += abs(((p shr shift) and 255) - ((q shr shift) and 255)); count++
                    }
                }
                return sum.toDouble() / count
            }
            val prefix = "$backend-${if (dark) "dark" else "light"}"
            val initial = capture("$prefix-initial")
            compose.runOnIdle { draft.value = "MMMMMMMMMMMMMMMMMMMMMMMM" }; settle()
            val edited = capture("$prefix-edited")
            compose.runOnIdle { body.value = Color(0xFF90D0F0) }; settle()
            val changed = capture("$prefix-body-changed")
            settle()
            val stable = capture("$prefix-stable")
            val foreground = difference(initial, edited)
            val response = difference(edited, changed)
            val drift = difference(changed, stable)
            println("RENAME_SAMPLING $prefix foreground=$foreground body=$response drift=$drift")
            assertTrue("Foreground must not enter its own backdrop: $foreground", foreground < 1.5)
            assertTrue("Glass must respond to the actual list body: $response", response > 0.5)
            assertTrue("Settled glass must not feed back: $drift", drift < 1.5)
        }
    }
}
