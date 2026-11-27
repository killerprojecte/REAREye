package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearstore.RearStoreAuthor
import hk.uwu.reareye.repository.rearstore.RearStoreListItem
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.screen.RearStoreRootContent
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
import java.io.File

/** Actual store page with offline catalog injection. No network, install or real preference writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearStoreNativeTopBarTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val openedIds = mutableListOf<String>()
    private var backCalls = 0

    // Same production-store fixture as UiCaptureActivity / RealPageCaptureTest.
    private val catalog = listOf(
        RearStoreListItem(
            id = "fixture-clock", name = "Fixture Aurora Clock",
            description = "A readable clock for the rear display.",
            author = RearStoreAuthor(name = "Fixture Studio"),
            type = "card", updatedAt = "2026-01-02T00:00:00Z", stargazersCount = 42,
        ),
        RearStoreListItem(
            id = "fixture-wallpaper", name = "Fixture Mountain Wallpaper",
            description = "A quiet landscape for the rear display.",
            author = RearStoreAuthor(name = "Fixture Studio"),
            type = "wallpaper", updatedAt = "2026-01-01T00:00:00Z", stargazersCount = 17,
        ),
    )

    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)
    private fun sortOption(id: Int) = compose.onNode(hasText(label(id)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
    private fun awaitText(value: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        frames()
    }
    private fun scroll(delta: Float) {
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        frames()
    }
    private fun open(
        backend: MixUiBackend,
        dark: Boolean = false,
        bottomInset: Int = 24,
        items: List<RearStoreListItem>? = catalog,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
    ) {
        compose.mainClock.autoAdvance = false
        openedIds.clear()
        backCalls = 0
        val prefs = PrefsManager(GuideMemoryPrefs())
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                NativeTitleTestEnvironment(direction, fontScale) {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    Box(Modifier.fillMaxSize()) {
                        RearStoreRootContent(
                            bottomInnerPadding = 96.dp,
                            prefsManager = prefs,
                            installedWidgets = emptyMap(),
                            onOpenDetail = { openedIds += it },
                            onBack = { backCalls++ },
                            loadItems = { items },
                        )
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                            .testTag("store-test-bottom-overlay"))
                    }
                }
                }
            }
        }
        frames()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomInset))
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomInset))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build())
        }
        frames()
        when {
            items == null -> awaitText(label(R.string.rear_store_load_failed))
            items.isEmpty() -> awaitText(label(R.string.rear_store_search_empty))
            else -> awaitText(items.first().name)
        }
    }
    private fun capture(name: String) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "rear-store-native-topbar").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { index ->
            val bitmap = roots[index].captureToImage().asAndroidBitmap()
            val suffix = if (index == 0) "" else "-root$index"
            File(folder, "$name$suffix.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
    private fun openSort() {
        compose.onNodeWithContentDescription(label(R.string.app_list_sort)).performClick()
        frames()
        sortOption(R.string.rear_store_sort_by_stars).assertIsDisplayed()
    }
    private fun assertBefore(first: String, second: String) {
        assertTrue("$first must precede $second in the rendered store list",
            text(first).fetchSemanticsNode().boundsInRoot.top < text(second).fetchSemanticsNode().boundsInRoot.top)
    }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test fun actualStoreHasNativeTitleAndSafeToolbarInBothColorModes() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark)
            text(catalog.first().name).assertIsDisplayed()
            val sort = compose.onNodeWithContentDescription(label(R.string.app_list_sort))
            sort.assertIsDisplayed()
            assertTrue("Toolbar must clear the status inset", sort.fetchSemanticsNode().boundsInRoot.top >= 24f)
            if (backend == MixUiBackend.Mochi) {
                val titles = compose.onAllNodesWithText(label(R.string.store_navigation), useUnmergedTree = true)
                val layouts = titles.fetchSemanticsNodes().indices.flatMap { index ->
                    val result = mutableListOf<TextLayoutResult>()
                    titles[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
                    result
                }
                assertTrue("Store must retain the shared 32sp normal large-title contract", layouts.any {
                    it.layoutInput.style.fontSize == 32.sp && it.layoutInput.style.fontWeight == FontWeight.Normal
                })
            }
            capture("${backend.name}-${if (dark) "dark" else "light"}-initial")
        }
    }

    @Test fun actualLongCatalogReachesLastCardAboveNavigationOverlay() {
        val longCatalog = List(40) { index -> catalog.first().copy(
            id = "long-$index", name = "Fixture item %02d".format(index),
            description = "Description %02d".format(index),
        ) }
        for (backend in MixUiBackend.entries) for (inset in listOf(24, 48)) {
            open(backend, bottomInset = inset, items = longCatalog)
            compose.onNode(hasScrollAction()).performScrollToKey("long-39")
            frames()
            scroll(10000f)
            text(longCatalog.last().name).assertIsDisplayed()
            val lastDescription = text(longCatalog.last().description)
            lastDescription.assertIsDisplayed()
            val overlayTop = compose.onNodeWithTag("store-test-bottom-overlay").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Final card description must clear the bottom overlay", lastDescription.fetchSemanticsNode().boundsInRoot.bottom < overlayTop - 16f)
            capture("${backend.name}-navigation-$inset-bottom")
            scroll(-100000f)
            text(longCatalog.first().name).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.app_list_sort)).assertIsDisplayed()
        }
    }

    @Test fun actualSearchSortMenuAndNavigationCallbacksSurviveMigration() {
        // Reverse star order only, so the default updated-at order and stars order differ.
        val items = listOf(catalog[0], catalog[1].copy(stargazersCount = 99))
        for (backend in MixUiBackend.entries) {
            open(backend, items = items)
            assertBefore(items[0].name, items[1].name)
            openSort()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            frames()
            sortOption(R.string.rear_store_sort_by_stars).assertDoesNotExist()
            assertEquals("Dismissing the sort popup must not navigate away", 0, backCalls)
            openSort()
            sortOption(R.string.rear_store_prioritize_updates).assertIsSelected()
            sortOption(R.string.rear_store_sort_by_updated_at).assertIsSelected()
            capture("${backend.name}-sort-open")
            sortOption(R.string.rear_store_prioritize_updates).performClick()
            frames()
            sortOption(R.string.rear_store_sort_by_stars).assertIsDisplayed()
            sortOption(R.string.rear_store_prioritize_updates).assertIsNotSelected()
            sortOption(R.string.rear_store_sort_by_stars).performClick()
            frames()
            sortOption(R.string.rear_store_sort_by_stars).assertDoesNotExist()
            assertBefore(items[1].name, items[0].name)
            openSort()
            sortOption(R.string.rear_store_sort_by_stars).assertIsSelected()
            sortOption(R.string.rear_store_sort_by_updated_at).assertIsNotSelected()
            sortOption(R.string.rear_store_sort_by_name).performClick()
            frames()
            assertBefore(items[0].name, items[1].name)

            compose.onNode(hasSetTextAction()).performTextReplacement("Mountain")
            frames()
            awaitText(items[1].name)
            text(items[0].name).assertDoesNotExist()
            compose.onNode(hasClickAction() and hasAnyDescendant(hasText(items[1].name)), useUnmergedTree = true)
                .performClick()
            assertEquals(listOf("fixture-wallpaper"), openedIds)
            compose.onNode(hasSetTextAction()).performTextReplacement("no-such-widget")
            frames()
            awaitText(label(R.string.rear_store_search_empty))
            text(label(R.string.rear_store_search_empty)).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextClearance()
            frames()
            awaitText(items[0].name)
            compose.onNode(hasSetTextAction()).performImeAction()
            frames()
            text(items[0].name).assertIsDisplayed()

            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            assertEquals(1, backCalls)
            capture("${backend.name}-search-cleared")
        }
    }

    @Test fun actualTouchCollapseKeepsToolbarAndLastItemSafeWithCutoutAndIme() {
        val items = List(24) { index -> catalog.first().copy(
            id = "touch-$index", name = "Touch item %02d".format(index),
            description = "Touch description %02d".format(index),
        ) }
        for (backend in MixUiBackend.entries) {
            open(backend, items = items)
            fun applyInsets(imePx: Int) {
                compose.runOnUiThread {
                    ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                        WindowInsetsCompat.Builder()
                            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                            .setDisplayCutout(DisplayCutoutCompat(
                                android.graphics.Rect(48, 0, 0, 0),
                                listOf(android.graphics.Rect(0, 0, 48, 100)),
                            ))
                            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(48, 0, 0, 0))
                            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                            .setVisible(WindowInsetsCompat.Type.systemBars(), true)
                            .setVisible(WindowInsetsCompat.Type.displayCutout(), true)
                            .setVisible(WindowInsetsCompat.Type.ime(), imePx > 0)
                            .build())
                }
                frames()
            }
            fun assertToolbarSafe() {
                for (id in listOf(R.string.scene_route_navigate_back, R.string.app_list_sort)) {
                    val control = compose.onNodeWithContentDescription(label(id))
                    control.assertIsDisplayed()
                    val bounds = control.fetchSemanticsNode().boundsInRoot
                    assertTrue("Toolbar control must clear the 48px cutout and status bar: $backend/$bounds",
                        bounds.left >= 48f && bounds.top >= 24f)
                }
            }
            applyInsets(imePx = 0)
            assertToolbarSafe()
            val expandedSearchTop = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top
            assertTrue("Real first card must clear the cutout",
                text(items.first().name).fetchSemanticsNode().boundsInRoot.left >= 48f)
            // Touch gestures, unlike ScrollBy/ScrollToKey, exercise native nested-scroll
            // collapse and Mochi's title-header handoff/snap behavior.
            repeat(2) {
                compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
                frames()
            }
            assertToolbarSafe()
            val compactTitles = compose.onAllNodesWithText(label(R.string.store_navigation), useUnmergedTree = true)
                .fetchSemanticsNodes().filter { node ->
                    val bounds = node.boundsInRoot
                    bounds.height > 0f && bounds.top >= 24f && bounds.bottom < 120f
                }
            assertTrue("After touch scrolling a compact title must remain in the toolbar", compactTitles.isNotEmpty())
            if (backend == MixUiBackend.Miuix) {
                val search = compose.onNode(hasSetTextAction())
                search.assertIsDisplayed()
                assertTrue("MIUIX pinned search must move up as its large bar collapses",
                    search.fetchSemanticsNode().boundsInRoot.top < expandedSearchTop - 8f)
            } else {
                assertFalse("Mochi search must scroll away with the native large-title header",
                    compose.onNode(hasSetTextAction()).isDisplayed())
            }
            capture("${backend.name}-touch-collapsed-cutout")

            // Inject a real window IME inset, without relying on a device keyboard
            // process in Robolectric. The host must resize while keeping popup ownership.
            applyInsets(imePx = 240)
            compose.onNode(hasScrollAction()).performScrollToKey("touch-23")
            frames()
            compose.onNode(hasScrollAction()).performTouchInput {
                swipeUp(startY = height * 0.6f, endY = height * 0.2f)
            }
            frames()
            val last = text(items.last().description)
            last.assertIsDisplayed()
            val lastBounds = last.fetchSemanticsNode().boundsInRoot
            val rootBottom = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("Last item must be reachable above the 240px keyboard: $backend/$lastBounds",
                lastBounds.bottom <= rootBottom - 240f)
            assertTrue("Last item must retain horizontal cutout protection", lastBounds.left >= 48f)
            assertToolbarSafe()
            capture("${backend.name}-touch-bottom-ime-cutout")

            applyInsets(imePx = 0)
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
            frames()
            val overlayTop = compose.onNodeWithTag("store-test-bottom-overlay").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Dismissing IME must restore bottom-overlay clearance",
                text(items.last().description).fetchSemanticsNode().boundsInRoot.bottom < overlayTop)
            assertToolbarSafe()
        }
    }

    @Test fun actualCatalogFailureAndEmptyStatesKeepToolbarUsable() {
        for (backend in MixUiBackend.entries) for (items in listOf(null, emptyList<RearStoreListItem>())) {
            open(backend, items = items)
            text(label(if (items == null) R.string.rear_store_load_failed else R.string.rear_store_search_empty))
                .assertIsDisplayed()
            openSort()
            sortOption(R.string.rear_store_sort_by_updated_at).performClick()
            frames()
            sortOption(R.string.rear_store_sort_by_updated_at).assertDoesNotExist()
            assertTrue(openedIds.isEmpty())
        }
    }
    @Test fun productionStoreTitleInsetMatchesMiuixWithoutMovingSearchOrActions() {
        val failures = mutableListOf<String>()
        val items = List(24) { index -> catalog.first().copy(id = "alignment-$index", name = "Catalog item $index") }
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            for (backend in MixUiBackend.entries) {
                open(backend, dark = dark, items = items, fontScale = fontScale)
                val titleText = label(R.string.store_navigation)
                val key = "alignment-${backend.name}-$dark-$fontScale"
                val expanded = compose.nativeTitleGeometry(titleText)
                writeNativeTitleGeometry("rear-store-native-topbar", "$key-expanded", expanded)
                capture("$key-expanded")
                val title = expanded.maxBy { it.layout.layoutInput.style.fontSize.value }
                val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val inset = title.startInset(root, LayoutDirection.Ltr)
                if (kotlin.math.abs(inset - 26f) > 1f) failures += "$key title first-glyph inset=$inset, expected 26px"
                if (backend == MixUiBackend.Mochi) {
                    val search = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
                    // The editable field is inside the production search card:
                    // 16dp native body + 9dp row padding + 18dp search icon + 8dp gap.
                    assertEquals("Search content stays at its native position", 51f, search.left - root.left, 1.5f)
                    assertEquals("Search trailing padding stays native", 28f, root.right - search.right, 1.5f)
                }
                repeat(2) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; frames() }
                val collapsed = compose.nativeTitleGeometry(titleText)
                writeNativeTitleGeometry("rear-store-native-topbar", "$key-collapsed", collapsed)
                capture("$key-collapsed")
                assertTrue("$key retains native compact title", collapsed.any {
                    it.layout.layoutInput.style.fontSize.value < 28f && it.bounds.height > 0f &&
                        it.bounds.top >= 24f && it.bounds.bottom < 150f
                })
                compose.onNodeWithContentDescription(label(R.string.app_list_sort)).assertIsDisplayed()
                compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
                assertEquals(1, backCalls)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun glassSearchKeepsFilteringAndBackLocalInBothThemes() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark)
            val mode = "${backend.name}-${if (dark) "dark" else "light"}"
            repeat(2) {
                compose.onNode(hasSetTextAction()).performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("Mountain")
                frames()
                awaitText(catalog[1].name)
                text(catalog[0].name).assertDoesNotExist()
                capture("$mode-glass-search-filtered")
                compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
                frames()
                assertEquals("Back dismisses search focus, not the store", 0, backCalls)
                compose.onNode(hasSetTextAction()).assertIsNotFocused()
                text(catalog[1].name).assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextReplacement("no-such-widget")
                frames()
                awaitText(label(R.string.rear_store_search_empty))
                capture("$mode-glass-search-empty")
                compose.onNode(hasSetTextAction()).performTextClearance()
                frames()
                awaitText(catalog[0].name)
                compose.onNode(hasSetTextAction()).performImeAction()
                frames()
                compose.onNode(hasSetTextAction()).assertIsNotFocused()
            }
            capture("$mode-glass-search-restored")
        }
    }

    @Test fun mochiGlassSearchRemainsInNativeScrollAndFocusTree() {
        for (dark in listOf(false, true)) {
            val items = List(30) { index -> catalog.first().copy(id = "portal-$index", name = "Portal item $index") }
            open(MixUiBackend.Mochi, dark, items = items)
            val field = compose.onNode(hasSetTextAction())
            field.performClick()
            frames()
            field.assertIsFocused()
            val center = field.fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput {
                // Drag the native search icon, not the editable text's selection
                // gesture region. The header must remain a list descendant.
                swipe(androidx.compose.ui.geometry.Offset(24f, center.y),
                    androidx.compose.ui.geometry.Offset(24f, 32f), 200)
            }
            frames()
            assertFalse("A swipe starting inside native search must collapse its header", field.isDisplayed())
            capture("Mochi-${if (dark) "dark" else "light"}-glass-search-swipe-collapsed")
            compose.onNode(hasScrollAction()).performScrollToKey("portal-29")
            frames()
            // Compose may retain a focused lazy item offscreen. It must not paint there.
            assertFalse(field.isDisplayed())
            scroll(-100000f)
            field.assertIsDisplayed()
            field.performTextReplacement("Portal item 29")
            frames()
            awaitText(items.last().name)
            text(items.first().name).assertDoesNotExist()
            capture("Mochi-${if (dark) "dark" else "light"}-glass-search-returned")
        }
    }

    @Test fun captureActualItemsBehindPinnedSearchAndMochiHeader() {
        val items = List(24) { index -> catalog[index % catalog.size].copy(
            id = "sampling-$index", name = "Layer sample %02d".format(index),
            updatedAt = "2026-01-02T00:00:00Z",
        ) }
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, items = items)
            repeat(2) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; frames() }
            compose.onNode(hasScrollAction()).performScrollToKey("sampling-8")
            frames()
            val target = text(items[8].name)
            // MIUIX pins search. Mochi scrolls search away with its native header,
            // so only its TopBar can truthfully show passing list items underneath.
            val targetCenter = if (backend == MixUiBackend.Miuix) {
                compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.center.y
            } else 54f
            scroll(target.fetchSemanticsNode().boundsInRoot.center.y - targetCenter)
            val itemY = target.fetchSemanticsNode().boundsInRoot.center.y
            assertEquals("Actual item title must occupy the sampled header band", targetCenter, itemY, 2f)
            if (backend == MixUiBackend.Miuix) compose.onNode(hasSetTextAction()).assertIsDisplayed()
            else assertFalse("Mochi's native search is scrolled away, not pinned over items",
                compose.onNode(hasSetTextAction()).isDisplayed())
            capture("${backend.name}-${if (dark) "dark" else "light"}-item-under-${if (backend == MixUiBackend.Miuix) "search" else "topbar"}")
        }
    }

    @Test fun miuixNativeSearchShadowKeepsGeometryAndSampling() {
        val items = List(24) { index -> catalog[index % catalog.size].copy(
            id = "shadow-$index", name = "Layer sample %02d".format(index),
            updatedAt = "2026-01-02T00:00:00Z",
        ) }
        for (dark in listOf(false, true)) {
            open(MixUiBackend.Miuix, dark, items = items)
            val mode = if (dark) "dark" else "light"
            capture("Miuix-$mode-after-shadow-initial")
            // Native captureToImage may invalidate recorded backdrop layers.
            // Each material comparison therefore starts with a fresh host.
            open(MixUiBackend.Miuix, dark, items = items)
            repeat(2) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; frames() }
            compose.onNode(hasScrollAction()).performScrollToKey("shadow-8")
            frames()
            val target = text(items[8].name)
            val field = compose.onNode(hasSetTextAction())
            val fieldBounds = field.fetchSemanticsNode().boundsInRoot
            scroll(target.fetchSemanticsNode().boundsInRoot.center.y - fieldBounds.center.y)
            assertEquals(fieldBounds.center.y, target.fetchSemanticsNode().boundsInRoot.center.y, 2f)
            assertEquals("Shadow must not change search geometry", fieldBounds, field.fetchSemanticsNode().boundsInRoot)
            capture("Miuix-$mode-after-shadow-scrolled")
        }
    }

}
