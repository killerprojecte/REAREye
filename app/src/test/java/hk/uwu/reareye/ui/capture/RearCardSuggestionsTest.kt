package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.Card
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import org.robolectric.annotation.GraphicsMode

/** Production editor sheet and editable controls; inert callbacks instead of device repositories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearCardSuggestionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val show = mutableStateOf(true)
    private val business = mutableStateOf("")
    private val mode = mutableStateOf(0)
    private val selectedBinding = mutableStateOf(0)
    private var bindingChanges = 0
    private val priority = mutableStateOf("500")
    private val pageColor = mutableStateOf(Color(0xFF19518C))
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
    private fun open(backend: MixUiBackend, dark: Boolean, controlledSource: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    GlassPreferenceScene(overlayContent = {
                    RearCardCreateDialog(show.value, mode.value, { mode.value = it }, catalog.value,
                        "fixture", {}, "fixture.target", {}, business.value, { business.value = it },
                        priority.value, { priority.value = it }, false, {}, false, {},
                        "fixture app", {}, selectedBinding.value,
                        { selectedBinding.value = it; bindingChanges++ }, { confirms++ }, {},
                        { dismisses++; show.value = false })
                    }) {
                        Column(Modifier.fillMaxSize().background(pageColor.value)) {
                            if (!controlledSource) {
                                repeat(8) { index -> Card { Text("Existing card ${index + 1}: clock widget") } }
                            }
                        }
                    }
                }
            }
        }
        frames()
    }
    private fun capture(backend: MixUiBackend, dark: Boolean, step: String) {
        val folder = File(System.getProperty("ui.capture.output"), "glass-interaction-refinement/SUGGESTIONS").apply { mkdirs() }
        val name = "${backend.name.lowercase()}-${if (dark) "dark" else "light"}-$step"
        val frame = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(folder, "$name-window.png").outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        // Same-window glass overlay crop; the full production component fixture is above.
        if (step == "filter") {
            val popup = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            File(folder, "$name-popup.png").outputStream().use { check(popup.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    private fun geometry(backend: MixUiBackend, dark: Boolean, phase: String, screenshot: Boolean = false) {
        runCatching {
            System.err.println("SUGGESTIONS_GEOMETRY backend=$backend dark=$dark phase=$phase " +
                "show=${show.value} dismisses=$dismisses confirms=$confirms business=${business.value}")
            val probes = listOf("roots" to isRoot(), "popup" to isPopup(), "editors" to hasSetTextAction(),
                "options" to SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)) +
                listOf("rear-card-create-form", "mochi-glass-sheet-host", "mochi-glass-sheet",
                    "mixui-native-glass-sheet-host", "mixui-native-glass-sheet").map { it to hasTestTag(it) }
            for ((name, matcher) in probes) {
                val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
                System.err.println("SUGGESTIONS_GEOMETRY $name count=${nodes.size}")
                nodes.forEach { node -> System.err.println("SUGGESTIONS_GEOMETRY $name id=${node.id} " +
                    "clipped=${node.boundsInRoot} root=${node.positionInRoot} screen=${node.positionOnScreen} size=${node.size} " +
                    "scroll=${node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)} " +
                    "focused=${node.config.getOrNull(SemanticsProperties.Focused)}") }
            }
            if (screenshot) capture(backend, dark, "failure-$phase")
        }.onFailure { System.err.println("SUGGESTIONS_GEOMETRY diagnostic failed: $it") }
    }
    private fun inspectFailure(backend: MixUiBackend, dark: Boolean, block: () -> Unit) {
        try { block() } catch (failure: AssertionError) {
            geometry(backend, dark, "assertion", screenshot = true)
            throw failure
        }
    }
    private fun awaitEditorClosed() {
        // Retain the controls until the spring actually finishes, not a fixed
        // capture-frame budget. The assertion still requires complete disposal.
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodes(hasSetTextAction() and (hasTestTag("rear-card-business-input") or
                hasAnyAncestor(hasTestTag("rear-card-business-input"))), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        editor().assertDoesNotExist()
    }
    private fun exercise(backend: MixUiBackend, dark: Boolean) = inspectFailure(backend, dark) {
        open(backend, dark)
        assertEquals("Opening must not confirm", 0, confirms)
        assertEquals("Opening must not dismiss", 0, dismisses)
        compose.onNodeWithContentDescription(label(R.string.rear_widget_confirm)).assertIsNotEnabled()
        compose.onNodeWithTag("rear-card-create-form").assert(hasScrollAction())
        compose.onNode(isDialog()).assertDoesNotExist()
        editor().reveal().performClick(); frames()
        editor().performTextReplacement("clock"); frames()
        option("clock.alpha").assertIsDisplayed()
        option("clock.beta").assertIsDisplayed()
        editor().assertIsFocused()
        val form = compose.onNodeWithTag("rear-card-create-form").fetchSemanticsNode()
        val popup = compose.onNode(isPopup()).fetchSemanticsNode()
        val anchor = compose.onNodeWithTag("suggestion-menu-anchor").fetchSemanticsNode()
        assertEquals("Glass card must match its input width", anchor.size.width, popup.size.width)
        assertEquals("Glass card follows the input start edge", anchor.positionOnScreen.x, popup.positionOnScreen.x, 1f)
        assertTrue("Sufficient room opens below the field with a small gap",
            popup.positionOnScreen.y >= anchor.positionOnScreen.y + anchor.size.height + 3f)
        compose.onNodeWithTag(if (backend == MixUiBackend.Miuix) "glass-suggestion-miuix" else "glass-suggestion-mochi")
            .assertIsDisplayed()
        assertTrue("Suggestions stay inside the visible form", popup.positionOnScreen.x >= form.positionOnScreen.x &&
            popup.positionOnScreen.y >= form.positionOnScreen.y &&
            popup.positionOnScreen.x + popup.size.width <= form.positionOnScreen.x + form.size.width &&
            popup.positionOnScreen.y + popup.size.height <= form.positionOnScreen.y + form.size.height)
        capture(backend, dark, "filter")
        geometry(backend, dark, "before-popup-back")
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
        geometry(backend, dark, "after-popup-back")
        option("clock.alpha").assertDoesNotExist()
        assertEquals(0, dismisses)
        editor().performTextReplacement("clock."); frames()
        geometry(backend, dark, "after-popup-reopen")
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
        compose.onNodeWithContentDescription(label(R.string.rear_widget_confirm))
            .assertIsDisplayed().performClick()
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
        compose.onNodeWithContentDescription(label(R.string.rear_widget_cancel))
            .assertIsDisplayed().performClick(); frames()
        assertEquals(1, dismisses)
        awaitEditorClosed()
    }
    @Test fun narrowerRtlSuggestionsAlignToThePhysicalRightEdgeInTheSameWindow() {
        for (backend in listOf(MixUiBackend.Miuix, MixUiBackend.Mochi)) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().enableEdgeToEdge()
                activity!!.get().setContent {
                    MixUiHost(backend, MixColorSchemeMode.Light) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                            GlassPreferenceScene(overlayContent = {
                                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()
                                    .padding(top = 100.dp, start = 40.dp, end = 100.dp)) {
                                    hk.uwu.mixui.components.SuggestionMenu(true, listOf("clock.alpha"), -1, {}, {}, width = 140.dp) {
                                        androidx.compose.foundation.layout.Box(Modifier.width(220.dp).height(56.dp))
                                    }
                                }
                            }) { Column(Modifier.fillMaxSize().background(Color.Blue)) {} }
                        }
                    }
                }
            }
            frames()
            val anchor = compose.onNodeWithTag("suggestion-menu-anchor").fetchSemanticsNode()
            val popup = compose.onNode(isPopup()).fetchSemanticsNode()
            assertEquals("$backend RTL uses physical, not mirrored, coordinates", anchor.boundsInRoot.right, popup.boundsInRoot.right, 1f)
            assertEquals(140, popup.size.width)
            assertTrue(popup.boundsInRoot.top > anchor.boundsInRoot.bottom)
        }
    }

    @Test fun glassSuggestionsSampleThePageButNeverTheirOwnEditorText() {
        fun difference(a: Bitmap, b: Bitmap, quietStripOnly: Boolean = true): Double {
            assertEquals(a.width, b.width); assertEquals(a.height, b.height)
            var total = 0L
            var samples = 0
            for (y in 24 until (a.height - 16).coerceAtLeast(24)) {
                val columns = if (quietStripOnly) (a.width - 14).coerceAtLeast(0) until (a.width - 8).coerceAtLeast(0)
                    else 8 until (a.width - 8).coerceAtLeast(8)
                for (x in columns) {
                    val first = a.getPixel(x, y); val second = b.getPixel(x, y)
                    total += abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) +
                        abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) +
                        abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))
                    samples += 3
                }
            }
            assertTrue("Need a text-free material sample", samples > 0)
            return total.toDouble() / samples
        }
        for (backend in listOf(MixUiBackend.Miuix, MixUiBackend.Mochi)) for (dark in listOf(false, true)) {
            compose.runOnUiThread {
                activity?.close(); activity = null
                show.value = true; business.value = ""; priority.value = "500"; pageColor.value = Color(0xFF19518C)
                catalog.value = listOf(
                    RearBusinessConfig("alpha", "fixture.alpha", "clock.alpha", "/fixture/alpha"),
                    RearBusinessConfig("beta", "fixture.beta", "clock.beta", "/fixture/beta"),
                    RearBusinessConfig("gamma", "fixture.gamma", "clock.gamma", "/fixture/gamma"))
            }
            open(backend, dark, controlledSource = true)
            editor().reveal().performClick()
            editor().performTextReplacement("clock"); frames()
            val blue = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            compose.runOnUiThread { pageColor.value = Color(0xFFC74035) }; frames()
            val red = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            assertTrue("$backend dark=$dark suggestions must sample the real external material", difference(blue, red) > 0.1)
            val popupBounds = compose.onNode(isPopup()).fetchSemanticsNode().boundsInRoot
            val priorityBounds = compose.onNodeWithTag("rear-card-create-priority", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Foreground probe must lie behind the popup", popupBounds.overlaps(priorityBounds))
            compose.runOnUiThread { priority.value = "1234567890".repeat(10) }; frames()
            val changedForeground = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            assertTrue("$backend suggestions must exclude the overlapped Sheet foreground",
                difference(red, changedForeground, quietStripOnly = false) < 0.1)
            editor().performTextReplacement("cloc"); frames()
            val changedEditor = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            assertTrue("$backend suggestions must not record their editor", difference(red, changedEditor) < 0.1)
            editor().assertIsFocused()
            assertEquals(0, confirms); assertEquals(0, dismisses)
            capture(backend, dark, "external-source")
        }
    }

    @Test fun imeLimitsSuggestionsWithoutTakingFocusOrMovingThemOffTheEditor() {
        for (backend in listOf(MixUiBackend.Miuix, MixUiBackend.Mochi)) {
            compose.runOnUiThread {
                activity?.close(); activity = null
                show.value = true; business.value = ""
                catalog.value = (1..12).map { index ->
                    RearBusinessConfig("clock.$index", "fixture.clock.$index", "clock.$index", "/fixture/$index")
                }
            }
            open(backend, false)
            editor().reveal().performClick()
            editor().performTextReplacement("clock"); frames()
            compose.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 320))
                    .setVisible(WindowInsetsCompat.Type.ime(), true).build()
                ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView, insets)
            }
            frames()
            geometry(backend, false, "synthetic-ime-bounds", screenshot = true)
            val popup = compose.onNode(isPopup()).fetchSemanticsNode()
            val anchor = compose.onNodeWithTag("suggestion-menu-anchor").fetchSemanticsNode()
            assertEquals(anchor.size.width, popup.size.width)
            assertTrue("$backend popup must remain above the synthetic IME", popup.boundsInRoot.bottom <= 480f)
            assertTrue("$backend popup must not cover the editor", popup.boundsInRoot.top >= anchor.boundsInRoot.bottom ||
                popup.boundsInRoot.bottom <= anchor.boundsInRoot.top)
            editor().assertIsFocused()
            capture(backend, false, "synthetic-ime")
            assertEquals(0, confirms); assertEquals(0, dismisses)
        }
    }

    @Test fun explicitCancelAndHeaderDragStillDismissWithOpenSuggestions() {
        for (backend in listOf(MixUiBackend.Miuix, MixUiBackend.Mochi)) for (dark in listOf(false, true)) {
            for (drag in listOf(false, true)) {
                compose.runOnUiThread {
                    activity?.close()
                    show.value = true; business.value = ""; priority.value = "500"
                    dismisses = 0; confirms = 0
                }
                open(backend, dark)
                editor().reveal().performClick()
                editor().performTextReplacement("clock"); frames()
                option("clock.alpha").assertIsDisplayed()
                if (drag) {
                    val tag = if (backend == MixUiBackend.Miuix) "mixui-native-glass-sheet" else "mochi-glass-sheet"
                    compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
                        swipe(androidx.compose.ui.geometry.Offset(centerX, 8f),
                            androidx.compose.ui.geometry.Offset(centerX, height - 8f), durationMillis = 120)
                    }
                    frames()
                } else compose.onNodeWithContentDescription(label(R.string.rear_widget_cancel))
                    .performTouchInput { click() }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeBy(100)
                    !show.value
                }
                awaitEditorClosed()
                assertEquals("$backend dark=$dark drag=$drag must dismiss the form once", 1, dismisses)
                assertEquals(0, confirms)
            }
        }
    }
    @Test fun continuousLargeCatalogInputKeepsTheOverlayAndListIdentity() {
        for (backend in listOf(MixUiBackend.Miuix, MixUiBackend.Mochi)) {
            compose.runOnUiThread {
                activity?.close(); activity = null
                show.value = true; business.value = ""; mode.value = 0
                catalog.value = (0 until 2_000).map { index ->
                    val name = "widget.${index.toString().padStart(5, '0')}"
                    RearBusinessConfig("id-$index", "fixture.$index", name, "/fixture/$index", defaultPriority = index)
                }
            }
            open(backend, false)
            editor().reveal().performClick()
            editor().performTextReplacement("w"); frames()
            val panelId = compose.onNode(isPopup()).fetchSemanticsNode().id
            val listId = compose.onNodeWithTag("glass-suggestion-options").fetchSemanticsNode().id
            for (query in listOf("w", "widget", "widget.0", "widget.00", "widget.0004", "widget.00042")) {
                editor().performTextReplacement(query); frames()
                assertEquals("$backend query=$query must keep the portal", panelId, compose.onNode(isPopup()).fetchSemanticsNode().id)
                assertEquals("$backend query=$query must keep its list", listId,
                    compose.onNodeWithTag("glass-suggestion-options").fetchSemanticsNode().id)
                editor().assertIsFocused()
            }
            option("widget.00042").assertIsDisplayed().performClick(); frames()
            assertEquals("widget.00042", business.value)
            assertEquals("42", priority.value)
            compose.onNode(isPopup()).assertDoesNotExist()
        }
    }

    @Test fun bindingMenuUsesSheetGlassAndPreservesDuplicateLabelIndices() {
        for (backend in listOf(MixUiBackend.Miuix, MixUiBackend.Mochi)) for (dark in listOf(false, true)) {
            compose.runOnUiThread {
                activity?.close(); activity = null
                show.value = true; mode.value = 1; selectedBinding.value = 0; bindingChanges = 0
                pageColor.value = Color(0xFF19518C)
                catalog.value = listOf("zulu", "alpha", "alpha").mapIndexed { index, name ->
                    RearBusinessConfig("id-$index", "fixture.$index", name, "/fixture/$index")
                }
            }
            open(backend, dark, controlledSource = true)
            compose.onNodeWithTag("rear-card-business-selector").reveal().performClick(); frames()
            compose.onNodeWithTag(if (backend == MixUiBackend.Miuix) "glass-suggestion-miuix" else "glass-suggestion-mochi")
                .assertIsDisplayed()
            option("zulu").assertIsSelected()
            val blue = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            compose.runOnUiThread { pageColor.value = Color(0xFFC74035) }; frames()
            val red = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            var difference = 0L
            var samples = 0
            for (y in 24 until red.height - 16) for (x in red.width - 14 until red.width - 8) {
                val a = blue.getPixel(x, y); val b = red.getPixel(x, y)
                difference += abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                    abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                samples += 2
            }
            assertTrue("$backend dark=$dark must sample the actual sheet source", samples > 0 && difference.toDouble() / samples > 0.1)
            capture(backend, dark, "binding-menu")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
            compose.onNode(isPopup()).assertDoesNotExist()
            assertEquals(0, dismisses)
            compose.onNodeWithTag("rear-card-business-selector").performClick(); frames()
            compose.onAllNodes(hasText("alpha") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                .assertCountEquals(2)[1].performClick(); frames()
            assertEquals("Binding must keep the caller's original unsorted index", 2, selectedBinding.value)
            assertEquals(1, bindingChanges)
            compose.onNode(isPopup()).assertDoesNotExist()
            assertEquals(0, dismisses)
        }
    }

    @Test(timeout = 60_000) fun miuixLight() = exercise(MixUiBackend.Miuix, false)
    @Test(timeout = 60_000) fun miuixDark() = exercise(MixUiBackend.Miuix, true)
    @Test(timeout = 60_000) fun mochiLight() = exercise(MixUiBackend.Mochi, false)
    @Test(timeout = 60_000) fun mochiDark() = exercise(MixUiBackend.Mochi, true)
}
