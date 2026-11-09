package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.background
import org.junit.Before
import org.junit.Assume.assumeTrue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.reareye.ui.components.config.MoreSearchBottomSheet
import hk.uwu.reareye.ui.components.config.buildMoreCategories
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.REAREyeConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Real production search UI/index. Callback state follows ConfigScreen, without its repositories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoreSearchBottomSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val categories = buildMoreCategories(REAREyeConfig)
    private val category = categories.first { it.group.id == "apps_runtime" }
    private val item = category.category.children.filterIsInstance<ConfigItem>()
        .first { it.key == ConfigKeys.ALLOW_EXTERNAL_DISPLAY_LAUNCH }
    private val session = mutableStateOf(false)
    private val query = mutableStateOf("")
    private val pending = mutableStateOf<ConfigCategory?>(null)
    private var navigated: ConfigCategory? = null
    private var dismissRequests = 0
    private var dismissFinished = 0

    @Before fun requireCapture() { assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled")) }

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun frames() { repeat(18) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun text(id: Int) = activity!!.get().getString(id)
    private fun editor() = compose.onNode(hasSetTextAction())
    private fun result(title: String) = compose.onNode(
        hasText(title) and hasClickAction() and hasSetTextAction().not(),
    )
    private fun open(backend: MixUiBackend, glass: Boolean, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            session.value = true; query.value = ""; pending.value = null
            navigated = null; dismissRequests = 0; dismissFinished = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    val content: @Composable () -> Unit = {
                        Box(Modifier.fillMaxSize().background(hk.uwu.mixui.theme.MixTheme.colorScheme.background)) {
                            MoreSearchBottomSheet(
                                show = session.value && pending.value == null,
                                categories = categories,
                                query = query.value,
                                onQueryChange = { query.value = it },
                                onDismissRequest = {
                                    dismissRequests++
                                    session.value = false
                                    query.value = ""
                                },
                                onDismissFinished = {
                                    dismissFinished++
                                    pending.value?.let {
                                        navigated = it
                                        pending.value = null
                                        // ConfigScreen's Category route keeps the sheet hidden.
                                        session.value = false
                                    }
                                },
                                onResultSelected = { pending.value = it },
                            )
                        }
                    }
                    if (glass) GlassBottomSheetScene(content) else content()
                }
            }
        }
        frames()
        assertEquals("Initial hidden state must not complete a dismissal", 0, dismissFinished)
        if (backend == MixUiBackend.Mochi && glass) {
            compose.onNodeWithTag("mochi-glass-sheet").assertIsDisplayed()
            assertFalse("Mochi scene must use the new in-scene host, not a dialog fallback",
                ShadowDialog.getLatestDialog()?.isShowing == true)
        }
    }

    private fun capture(name: String) {
        val folder = File(System.getProperty("ui.capture.output"), "more-search-sheet").apply { mkdirs() }
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
        val bitmap = if (dialog != null) compose.runOnUiThread { captureDialogWindowBitmap(dialog) }
            else compose.onNode(isRoot() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun productionFilteringSelectionAndNativeCompletionPreserveDraft() {
        for (backend in MixUiBackend.entries) for (glass in listOf(false, true)) for (dark in listOf(false, true)) {
            open(backend, glass, dark)
            val title = text(item.titleRes)
            editor().performTextReplacement("no-matching-setting-74293")
            frames()
            result(title).assertDoesNotExist()
            editor().performTextReplacement(title)
            frames()
            result(title).assertIsDisplayed()
            compose.onNodeWithText(text(category.group.titleRes)).assertIsDisplayed()
            capture("${backend.name}-${if (glass) "glass" else "ordinary"}-${if (dark) "dark" else "light"}")
            result(title).performClick()
            // A request is not a completion: route must still be pending this frame.
            compose.runOnIdle {
                assertSame(category.category, pending.value)
                assertNull(navigated)
                assertEquals(0, dismissFinished)
            }
            frames()
            compose.runOnIdle {
                assertSame(category.category, navigated)
                assertEquals(1, dismissFinished)
                assertEquals("Programmatic hide must not discard the search session", 0, dismissRequests)
                assertEquals(title, query.value)
                session.value = true
            }
            frames()
            editor().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(title),
            ))
            // Clear through the actual editable input; empty query has no results.
            editor().performTextReplacement("")
            frames()
            result(title).assertDoesNotExist()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun nativeBackDiscardsDraftAndReopeningDoesNotNavigate() {
        for (backend in MixUiBackend.entries) for (glass in listOf(false, true)) {
            open(backend, glass, dark = false)
            editor().performTextReplacement(text(item.titleRes))
            frames()
            // No focused IME: exercise the native sheet Back handler, not an IME dismissal.
            compose.runOnUiThread {
                activity!!.get().currentFocus?.clearFocus()
                val dialog = ShadowDialog.getLatestDialog()
                if (dialog?.isShowing == true) dialog.onBackPressed()
                else activity!!.get().onBackPressedDispatcher.onBackPressed()
            }
            frames()
            compose.runOnIdle {
                assertEquals(1, dismissRequests)
                assertEquals(1, dismissFinished)
                assertEquals("", query.value)
                assertNull(navigated)
                session.value = true
            }
            frames()
            editor().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(""),
            ))
            result(text(item.titleRes)).assertDoesNotExist()
        }
    }

    @Test fun interruptedHideDoesNotCompleteOrDiscardNewSession() {
        for (backend in MixUiBackend.entries) {
            open(backend, glass = true, dark = false)
            editor().performTextReplacement(text(item.titleRes))
            frames()
            compose.runOnUiThread { session.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread { session.value = true }
            frames()
            editor().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(text(item.titleRes)),
            ))
            assertEquals(0, dismissFinished)
            assertEquals(0, dismissRequests)
            assertNull(navigated)
        }
    }
    @Test fun interruptedSelectionCannotNavigateAfterReopening() {
        open(MixUiBackend.Mochi, glass = true, dark = false)
        editor().performTextReplacement(text(item.titleRes)); frames()
        result(text(item.titleRes)).performClick()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnUiThread {
            assertSame(category.category, pending.value)
            // A newer search supersedes the selected route before exit completes.
            pending.value = null
            session.value = true
            query.value = "new-session-query"
        }
        frames()
        compose.onNodeWithTag("mochi-glass-sheet").assertIsDisplayed()
        editor().assertTextContains("new-session-query")
        assertNull(navigated)
        assertEquals(0, dismissFinished)
        assertEquals(0, dismissRequests)
    }

    @Config(qualifiers = "en-rUS-w400dp-h480dp-mdpi")
    @Test fun shortScreenSyntheticImeKeepsSearchAndLastResultReachable() {
        open(MixUiBackend.Mochi, glass = true, dark = false)
        editor().performTextReplacement(text(category.group.titleRes)); frames()
        val list = compose.onNode(hasScrollToIndexAction())
        val sheet = compose.onNodeWithTag("mochi-glass-sheet")
        val sheetBottom = sheet.fetchSemanticsNode().boundsInRoot.bottom
        val originalHeight = list.fetchSemanticsNode().boundsInRoot.height
        val rootBottom = compose.onNode(isRoot() and hasAnyDescendant(hasSetTextAction()),
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
        fun ime(pixels: Int) {
            compose.runOnUiThread {
                val insets = androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),
                        androidx.core.graphics.Insets.of(0, 0, 0, pixels))
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), pixels > 0)
                    .build()
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView, insets)
            }
            frames()
        }
        ime(220)
        editor().assertIsDisplayed()
        assertEquals("IME must not shrink the glass material", sheetBottom,
            sheet.fetchSemanticsNode().boundsInRoot.bottom, 1f)
        val bounds = list.fetchSemanticsNode().boundsInRoot
        assertTrue("IME fixture must actually reduce the results viewport", bounds.height < originalHeight)
        assertTrue("Results remain above the keyboard", bounds.bottom <= rootBottom - 220 + 1f)
        assertTrue("Results retain a usable viewport", bounds.height > 40f)
        val last = category.category.children.filterIsInstance<ConfigItem>().last()
        compose.mainClock.autoAdvance = true
        try { list.performScrollToNode(hasText(text(last.titleRes))) }
        finally { compose.mainClock.autoAdvance = false }
        frames()
        result(text(last.titleRes)).assertIsDisplayed()
        capture("Mochi-short-screen-synthetic-ime")
        ime(0)
        assertEquals("Removing IME must restore the viewport", originalHeight,
            list.fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.mainClock.autoAdvance = true
        try { list.performScrollToNode(hasText(text(last.titleRes))) }
        finally { compose.mainClock.autoAdvance = false }
        result(text(last.titleRes)).performClick(); frames()
        assertSame(category.category, navigated)
        assertEquals(1, dismissFinished)
        assertEquals(0, dismissRequests)
    }

}
