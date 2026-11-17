package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.SearchBar
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.MaryApprovalModePreference
import hk.uwu.reareye.ui.components.MaryComposer
import hk.uwu.reareye.ui.components.config.AppListSelectorScreen
import hk.uwu.reareye.ui.components.config.RearCardCreateDialog
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.PrefsManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Production pages/composables, with only inert preference and installed-package fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalGlassPopupPagesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun tagged(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun text(value: String) = compose.onNodeWithText(value)
    private fun option(id: Int) = compose.onNode(hasText(label(id)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
    private fun open(backend: MixUiBackend, dark: Boolean = false,
                     setup: (ComponentActivity) -> Unit = {}, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            setup(activity!!.get())
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, content = content)
            }
        }
        frames()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build())
        }
        frames()
    }
    private fun awaitText(value: String) {
        compose.waitUntil(10_000) {
            // IO catalog loading resumes into frame-clock delays; tick while waiting.
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        frames()
    }
    private fun awaitAbsent(value: String) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }
    private fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
    }
    @Suppress("DEPRECATION")
    private fun dialogBack() {
        compose.runOnUiThread { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
        frames()
    }
    private fun outside() {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots[roots.fetchSemanticsNodes().lastIndex].performTouchInput { click(Offset(4f, 4f)) }
        frames()
    }
    private fun capture(name: String) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "final-glass-popup-pages").apply { mkdirs() }
        val windowBitmap = compose.runOnUiThread {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            if (dialog?.isShowing == true) captureDialogWindowBitmap(dialog)
            else captureWindowBitmap(activity!!.get().window)
        }
        File(folder, "$name-window.png").outputStream().use {
            check(windowBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { index ->
            val bitmap = roots[index].captureToImage().asAndroidBitmap()
            File(folder, "$name-root$index.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    private fun installApps(host: ComponentActivity, longList: Boolean = false) {
        val packages = listOf(
            Triple("z.fixture.alpha", "Alpha fixture", false),
            Triple("a.fixture.zulu", "Zulu fixture", false),
            Triple("m.fixture.system", "System fixture", true),
        ) + if (longList) List(40) { Triple("fixture.extra$it", "Extra fixture %02d".format(it), false) } else emptyList()
        packages.forEach { (pkg, title, system) ->
            shadowOf(host.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    enabled = true
                    nonLocalizedLabel = title
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
            })
        }
    }
    private fun toolbar(id: Int) {
        compose.onNodeWithContentDescription(label(id)).performClick()
        frames()
    }
    private fun assertBefore(first: String, second: String) {
        assertTrue("$first must precede $second", text(first).fetchSemanticsNode().boundsInRoot.top <
            text(second).fetchSemanticsNode().boundsInRoot.top)
    }

    private fun searchOutput() = File(
        System.getProperty("ui.capture.output", "build/reports/ui-capture"),
        "glass-interaction-refinement/SEARCH",
    ).apply { check(mkdirs() || isDirectory) }

    /** Actual native window plus independent node capture; never synthesize a composite. */
    private fun captureSearch(name: String): Bitmap {
        val folder = searchOutput()
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(folder, "$name-window.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val root = compose.onNode(isRoot() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        File(folder, "$name-root.png").outputStream().use {
            check(root.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }

    private fun searchPixelDelta(a: Bitmap, b: Bitmap, bounds: Rect): Double {
        // Empty, central-height/right-hand material: excludes the search icon, label and capsule edge.
        val left = ceil(bounds.left + bounds.width * .65f).toInt()
        val right = floor(bounds.left + bounds.width * .80f).toInt()
        val top = ceil(bounds.top + bounds.height * .35f).toInt()
        val bottom = floor(bounds.top + bounds.height * .65f).toInt()
        assertTrue("Need a nonempty, in-window search material ROI: $bounds",
            right > left && bottom > top && left >= 0 && top >= 0 && right <= a.width && bottom <= a.height)
        var total = 0L
        var count = 0L
        for (y in top until bottom) for (x in left until right) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            for (shift in listOf(0, 8, 16)) {
                total += abs(((p shr shift) and 255) - ((q shr shift) and 255))
                count++
            }
        }
        return total.toDouble() / count
    }

    /** Controlled optical contract, NOT a real application page or physical-device shader proof. */
    @Test(timeout = 90_000) fun controlledMiuixPinnedSearchRespondsToPageButNotHeaderAcrossThemesAndPlacements() {
        val failures = mutableListOf<String>()
        for (dark in listOf(false, true)) for (inherit in listOf(true, false)) {
            val page = mutableStateOf(Color(0xFF153CB3))
            val header = mutableStateOf(Color.Red)
            open(MixUiBackend.Miuix, dark) {
                val list = rememberLazyListState(initialFirstVisibleItemIndex = 4)
                Box(Modifier.fillMaxSize()) {
                    GlassTopBarScaffold(
                        title = "Controlled search backdrop",
                        isContentScrolled = list.canScrollBackward,
                        contentListState = list,
                        inheritMiuixBarMaterial = inherit,
                        pinnedHeader = {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                Box(Modifier.fillMaxWidth().height(8.dp).background(header.value)
                                    .testTag("controlled-search-header-witness"))
                                SearchBar("", "", Modifier.testTag("controlled-glass-search"), {})
                            }
                        },
                    ) {
                        // Full, fixed viewport behind the header; search and witness are NOT its source.
                        Box(Modifier.fillMaxSize().background(page.value)) {
                            LazyColumn(Modifier.fillMaxSize(), state = list) {
                                items(40) { Box(Modifier.fillMaxWidth().height(64.dp)) }
                            }
                            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(64.dp)
                                .testTag("controlled-search-page-witness"))
                        }
                    }
                    // Sibling outside the Scaffold local: genuine native surfaceContainerHigh negative control.
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 80.dp)) {
                        SearchBar("", "", Modifier.testTag("controlled-solid-search"), {})
                    }
                }
            }
            val name = "controlled-miuix-${if (dark) "dark" else "light"}-${if (inherit) "joined" else "detached"}"
            val glassBounds = tagged("controlled-glass-search").fetchSemanticsNode().boundsInWindow
            val solidBounds = tagged("controlled-solid-search").fetchSemanticsNode().boundsInWindow
            val pageBounds = tagged("controlled-search-page-witness").fetchSemanticsNode().boundsInWindow
            val headerBounds = tagged("controlled-search-header-witness").fetchSemanticsNode().boundsInWindow
            val first = captureSearch("$name-blue")
            frames()
            val stable = captureSearch("$name-stable")
            compose.runOnIdle { header.value = Color.Cyan }; frames()
            val changedHeader = captureSearch("$name-header-only")
            compose.runOnIdle { page.value = Color(0xFFE07824) }; frames()
            val changedPage = captureSearch("$name-orange")
            assertEquals("Backdrop updates must not move the native input", glassBounds,
                tagged("controlled-glass-search").fetchSemanticsNode().boundsInWindow)
            val drift = searchPixelDelta(first, stable, glassBounds)
            val foregroundWitness = searchPixelDelta(stable, changedHeader, headerBounds)
            val feedback = searchPixelDelta(stable, changedHeader, glassBounds)
            val direct = searchPixelDelta(changedHeader, changedPage, pageBounds)
            val solid = searchPixelDelta(changedHeader, changedPage, solidBounds)
            val glass = searchPixelDelta(changedHeader, changedPage, glassBounds)
            val report = "$name glass=$glass solid=$solid page=$direct drift=$drift headerWitness=$foregroundWitness headerFeedback=$feedback bounds=$glassBounds"
            println("SEARCH_GLASS_SOURCE $report")
            File(searchOutput(), "$name-measurement.txt").writeText(report + "\n")
            if (direct <= 30) failures += "$name: page fixture did not change: $direct"
            if (foregroundWitness <= 30) failures += "$name: header fixture did not change: $foregroundWitness"
            if (drift > 1) failures += "$name: unstable material baseline: $drift"
            if (feedback > 1) failures += "$name: header entered search's own backdrop: $feedback"
            if (solid > 1) failures += "$name: native solid negative control changed with page: $solid"
            if (glass <= 2) failures += "$name: search remains opaque or backdrop is not rendered: $glass"
            if (glass >= direct - 1) failures += "$name: search looks like raw transparency, not native material: $glass/$direct"
            listOf(first, stable, changedHeader, changedPage).forEach { it.recycle() }
        }
        assertTrue("Native Robolectric optical contract failed; do not claim device shader support:\n" +
            failures.joinToString("\n"), failures.isEmpty())
    }

    private fun awaitMeasuredSearchPosition(name: String, state: () -> LazyListState?, reached: (LazyListState) -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeBy(100)
                // Frame time drives recomposition/animation, not Android measure/layout.
                compose.waitForIdle()
                state()?.let(reached) == true
            }
        } catch (timeout: ComposeTimeoutException) {
            val list = state()
            throw AssertionError("$name: measured items=${list?.layoutInfo?.totalItemsCount}, " +
                "position=${list?.firstVisibleItemIndex}:${list?.firstVisibleItemScrollOffset}, " +
                "scrolling=${list?.isScrollInProgress}, canScrollBack=${list?.canScrollBackward}", timeout)
        }
    }

    @Test(timeout = 180_000) fun coldLoadedProgrammaticSearchGlassAndReturnToTopUseRealPixelsAcrossHosts() {
        for (dark in listOf(false, true)) for (placement in listOf("joined", "detached", "large-lazy")) {
            val page = mutableStateOf(Color(0xFF153CB3))
            val loaded = mutableStateOf(false)
            var actualList: LazyListState? = null
            var scope: CoroutineScope? = null
            open(MixUiBackend.Miuix, dark) {
                val list = rememberLazyListState()
                val coroutineScope = rememberCoroutineScope()
                SideEffect { actualList = list; scope = coroutineScope }
                LaunchedEffect(loaded.value) {
                    if (loaded.value) {
                        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 12 }
                        list.animateScrollToItem(12)
                    }
                }
                val search: @Composable () -> Unit = {
                    SearchBar("", "", Modifier.testTag("programmatic-search"), {})
                }
                if (placement == "large-lazy") {
                    hk.uwu.mixui.components.LargeTitleLazyScaffold(
                        title = "Programmatic search glass", listState = list, searchBar = search,
                    ) {
                        items(if (loaded.value) 40 else 0) {
                            Box(Modifier.fillMaxWidth().height(64.dp).background(page.value))
                        }
                    }
                } else {
                    GlassTopBarScaffold(
                        title = "Programmatic search glass", isContentScrolled = list.canScrollBackward,
                        contentListState = list, inheritMiuixBarMaterial = placement == "joined",
                        pinnedHeader = { Box(Modifier.padding(12.dp)) { search() } },
                    ) { padding ->
                        LazyColumn(Modifier.fillMaxSize().background(page.value), state = list, contentPadding = padding) {
                            items(if (loaded.value) 40 else 0) { Box(Modifier.fillMaxWidth().height(64.dp)) }
                        }
                    }
                }
            }
            compose.runOnIdle { loaded.value = true }
            awaitMeasuredSearchPosition("$placement dark=$dark cold-load jump", { actualList }) {
                it.firstVisibleItemIndex == 12 && !it.isScrollInProgress
            }
            frames()
            val name = "programmatic-${if (dark) "dark" else "light"}-$placement"
            val bounds = tagged("programmatic-search").fetchSemanticsNode().boundsInWindow
            val blue = captureSearch("$name-cold-jump-blue-no-touch")
            compose.runOnIdle { page.value = Color(0xFFE07824) }; frames()
            val orange = captureSearch("$name-cold-jump-orange-no-touch")
            val delta = searchPixelDelta(blue, orange, bounds)
            assertTrue("Loaded animateScrollToItem must raise real sampled material without a native gesture: $name delta=$delta", delta > 2)
            compose.runOnIdle { scope!!.launch { actualList!!.animateScrollToItem(0) } }
            awaitMeasuredSearchPosition("$placement dark=$dark programmatic top", { actualList }) {
                it.firstVisibleItemIndex == 0 && it.firstVisibleItemScrollOffset == 0 && !it.isScrollInProgress
            }
            frames()
            val topBounds = tagged("programmatic-search").fetchSemanticsNode().boundsInWindow
            val topOrange = captureSearch("$name-top-orange-no-touch")
            compose.runOnIdle { page.value = Color(0xFF153CB3) }; frames()
            val topBlue = captureSearch("$name-top-blue-no-touch")
            assertTrue("Return-to-top must remove glass sampling and stale native offset: $name",
                searchPixelDelta(topOrange, topBlue, topBounds) <= 1)
            File(searchOutput(), "$name-measurement.txt").writeText(
                "Cold-load animated jump at index 12, no body gesture; sampled RGB delta=$delta.\n" +
                    "Programmatic return-to-top is solid again; all dimensions remain vendor native.\n")
            listOf(blue, orange, topOrange, topBlue).forEach { it.recycle() }
        }
    }

    @Test(timeout = 120_000) fun expandedSearchStaysSolidAndFocusCannotRaiseShadowAcrossAllHosts() {
        for (dark in listOf(false, true)) for (placement in listOf("joined", "detached", "large-lazy")) {
            val page = mutableStateOf(Color(0xFF153CB3))
            val query = mutableStateOf("")
            open(MixUiBackend.Miuix, dark) {
                val list = rememberLazyListState()
                val search: @Composable () -> Unit = {
                    SearchBar(query.value, "", Modifier.testTag("resting-search"), { query.value = it })
                }
                if (placement == "large-lazy") {
                    hk.uwu.mixui.components.LargeTitleLazyScaffold(
                        title = "Resting native search", listState = list, searchBar = search,
                    ) {
                        items(40) { Box(Modifier.fillMaxWidth().height(64.dp).background(page.value)) }
                    }
                } else {
                    GlassTopBarScaffold(
                        title = "Resting native search", isContentScrolled = false,
                        contentListState = list, inheritMiuixBarMaterial = placement == "joined",
                        pinnedHeader = { Box(Modifier.padding(12.dp)) { search() } },
                    ) { padding ->
                        LazyColumn(Modifier.fillMaxSize().background(page.value), state = list,
                            contentPadding = padding) {
                            items(40) { Box(Modifier.fillMaxWidth().height(64.dp)) }
                        }
                    }
                }
            }
            val name = "resting-${if (dark) "dark" else "light"}-$placement"
            val bounds = tagged("resting-search").fetchSemanticsNode().boundsInWindow
            val initial = captureSearch("$name-idle")
            compose.onNode(hasSetTextAction()).performClick(); frames()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            val focused = captureSearch("$name-focused")
            assertEquals("Focus must not move/resize the native input", bounds,
                tagged("resting-search").fetchSemanticsNode().boundsInWindow)
            // Below the capsule, excluding all editable content and cursor pixels.
            var focusDelta = 0L
            var samples = 0
            val left = ceil(bounds.left + bounds.width * .25f).toInt()
            val right = floor(bounds.left + bounds.width * .75f).toInt()
            for (y in ceil(bounds.bottom + 2).toInt() until ceil(bounds.bottom + 8).toInt()) {
                for (x in left until right) {
                    val a = initial.getPixel(x, y)
                    val b = focused.getPixel(x, y)
                    for (shift in listOf(0, 8, 16)) {
                        focusDelta += abs(((a shr shift) and 255) - ((b shr shift) and 255)); samples++
                    }
                }
            }
            assertTrue("Focus alone must not create an exterior shadow: $name", focusDelta.toDouble() / samples <= 1)
            compose.runOnIdle { page.value = Color(0xFFE07824) }; frames()
            val changedPage = captureSearch("$name-page-changed-focused")
            assertTrue("Expanded search keeps the native solid fill, not transparent/blurred self-feedback: $name",
                searchPixelDelta(focused, changedPage, bounds) <= 1)
            compose.onNode(hasSetTextAction()).performTextReplacement("query"); frames()
            compose.onNodeWithContentDescription("Search Cleanup", useUnmergedTree = true).performClick(); frames()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            captureSearch("$name-cleared-focused").recycle()
            listOf(initial, focused, changedPage).forEach { it.recycle() }
        }
    }

    @Test(timeout = 60_000) fun actualMiuixAppPageSearchInputClearFocusBackAndMenusRemainUsable() {
        for (dark in listOf(false, true)) {
            val prefs = PrefsManager(GuideMemoryPrefs())
            var cancels = 0
            open(MixUiBackend.Miuix, dark, setup = { installApps(it) }) {
                AppListSelectorScreen(ConfigItem("fixture.glass.search", R.string.search_apps, type = ConfigType.AppList()),
                    prefs, onCancel = { cancels++ }, onSave = {})
            }
            awaitText("Alpha fixture")
            val name = "actual-app-list-miuix-${if (dark) "dark" else "light"}"
            captureSearch("$name-idle").recycle()
            val input = compose.onNode(hasSetTextAction())
            input.performClick(); frames()
            input.assertIsFocused().performTextInput("Alpha fixture")
            frames(); awaitAbsent("Zulu fixture")
            // The query text also says Alpha; assert the actual row's package, not a duplicate label.
            text("z.fixture.alpha").assertIsDisplayed()
            input.assertTextContains("Alpha fixture")
            captureSearch("$name-filtered-focused").recycle()
            back()
            input.assertIsNotFocused().assertTextContains("Alpha fixture")
            assertEquals("First Back only dismisses search focus", 0, cancels)
            // Clear via the real editable control, not fixture state or the selection-clear menu.
            input.performTextClearance(); frames(); awaitText("Zulu fixture")
            input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("")))
            text("Alpha fixture").assertIsDisplayed()
            captureSearch("$name-cleared").recycle()
            // Explicitly refocus after clearing, then exercise the real page Back handler again.
            input.performClick(); frames()
            input.assertIsFocused()
            back(); input.assertIsNotFocused()
            assertEquals(0, cancels)
            toolbar(R.string.app_list_sort)
            option(R.string.app_list_sort_by_name).assertIsSelected()
            captureSearch("$name-sort-menu").recycle()
            back()
            option(R.string.app_list_sort_by_name).assertDoesNotExist()
            toolbar(R.string.app_list_filter)
            option(R.string.show_system_apps).assertIsNotSelected().performClick()
            frames(); awaitText("System fixture")
            captureSearch("$name-system-apps").recycle()
            tagged("app-list-back").performClick()
            assertEquals(1, cancels)
        }
    }

    @Test(timeout = 60_000) fun actualAppPageSortFilterClearSaveSearchAndDismissalStayConnected() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            val prefs = PrefsManager(GuideMemoryPrefs())
            val config = ConfigItem("fixture.app.selection", R.string.search_apps, type = ConfigType.AppList())
            var saves = 0
            var cancels = 0
            open(backend, dark, setup = { installApps(it) }) {
                AppListSelectorScreen(config, prefs, onCancel = { cancels++ }, onSave = { saves++ })
            }
            awaitText("Alpha fixture")
            assertBefore("Alpha fixture", "Zulu fixture")
            val sortButton = compose.onNodeWithContentDescription(label(R.string.app_list_sort))
            assertTrue(sortButton.fetchSemanticsNode().boundsInRoot.top >= 24f)
            toolbar(R.string.app_list_sort)
            option(R.string.app_list_sort_by_name).assertIsSelected()
            option(R.string.app_list_sort_reverse).assertIsNotSelected()
            capture("${backend.name}-$dark-app-sort")
            back()
            option(R.string.app_list_sort_by_name).assertDoesNotExist()
            assertEquals(0, cancels)
            toolbar(R.string.app_list_sort)
            option(R.string.app_list_sort_by_package).performClick()
            frames()
            option(R.string.app_list_sort_by_package).assertDoesNotExist()
            assertBefore("Zulu fixture", "Alpha fixture")
            toolbar(R.string.app_list_sort)
            option(R.string.app_list_sort_by_package).assertIsSelected()
            option(R.string.app_list_sort_reverse).performClick()
            frames()
            assertBefore("Alpha fixture", "Zulu fixture")
            toolbar(R.string.app_list_filter)
            option(R.string.show_system_apps).assertIsNotSelected().performClick()
            frames()
            text("System fixture").assertIsDisplayed()
            toolbar(R.string.app_list_filter)
            option(R.string.show_system_apps).assertIsSelected()
            capture("${backend.name}-$dark-app-filter")
            outside()
            option(R.string.show_system_apps).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextReplacement("Alpha fixture")
            frames()
            awaitAbsent("Zulu fixture")
            text("Zulu fixture").assertDoesNotExist()
            compose.onNode(hasClickAction() and hasAnyDescendant(hasText("Alpha fixture")) and
                hasAnyAncestor(hasTestTag("app-list-results")), useUnmergedTree = true).performClick()
            tagged("app-list-save").performClick()
            assertEquals(setOf("z.fixture.alpha"), prefs.getStringSet(config.key))
            assertEquals(1, saves)
            toolbar(R.string.app_list_more_actions)
            text(label(R.string.selection_clear)).performClick()
            frames()
            tagged("app-list-save").performClick()
            assertEquals(emptySet<String>(), prefs.getStringSet(config.key))
            assertEquals(2, saves)
            compose.onNode(hasSetTextAction()).performTextReplacement("missing fixture")
            frames()
            text(label(R.string.app_list_empty_result)).assertIsDisplayed()
            tagged("app-list-back").performClick()
            assertEquals(1, cancels)
        }
    }

    @Test(timeout = 60_000) fun actualAppPageLongListClearsSaveButtonAndBottomSystemInset() {
        for (backend in MixUiBackend.entries) {
            val prefs = PrefsManager(GuideMemoryPrefs())
            open(backend, setup = { installApps(it, longList = true) }) {
                AppListSelectorScreen(ConfigItem("fixture.long", R.string.search_apps, type = ConfigType.AppList()),
                    prefs, onCancel = {}, onSave = {})
            }
            awaitText("Alpha fixture")
            tagged("app-list-results").performScrollToKey("a.fixture.zulu")
            tagged("app-list-results").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10000f) }
            frames()
            text("a.fixture.zulu").assertIsDisplayed()
            assertTrue(text("a.fixture.zulu").fetchSemanticsNode().boundsInRoot.bottom <
                tagged("app-list-save").fetchSemanticsNode().boundsInRoot.top)
            capture("${backend.name}-app-bottom")
        }
    }

    @Test(timeout = 60_000) fun actualMaryComposerAndSettingsShareCompleteApprovalMenu() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            val mode = mutableStateOf(ApprovalMode.ASK)
            val locked = mutableStateOf(false)
            val events = mutableListOf<ApprovalMode>()
            open(backend, dark) {
                GlassPreferenceScene {
                    Box(Modifier.fillMaxSize().padding(top = 40.dp)) {
                        MaryApprovalModePreference(mode.value, { mode.value = it; events += it }, enabled = !locked.value)
                        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 80.dp)) {
                            MaryComposer("draft", {}, running = false, locked = locked.value, configuring = false,
                                provider = ProviderConfig(model = "fixture"), onReasoningSelected = {},
                                onSend = {}, onStop = {}, approvalMode = mode.value,
                                onApprovalModeSelected = { mode.value = it; events += it })
                        }
                    }
                }
            }
            ApprovalMode.entries.forEach { next ->
                tagged("mary-approval-mode").performClick()
                frames()
                tagged("mary-approval-option-${mode.value.name}").assertIsSelected()
                ApprovalMode.entries.forEach { entry ->
                    tagged("mary-approval-option-icon-${entry.name}").assertIsDisplayed()
                    val title = tagged("mary-approval-option-title-${entry.name}")
                    val summary = tagged("mary-approval-option-summary-${entry.name}")
                    title.assertIsDisplayed(); summary.assertIsDisplayed()
                    assertTrue(title.fetchSemanticsNode().boundsInRoot.bottom <= summary.fetchSemanticsNode().boundsInRoot.top)
                }
                capture("${backend.name}-$dark-approval-${next.name}")
                tagged("mary-approval-option-${next.name}").performClick()
                frames()
                assertEquals(next, mode.value)
                tagged("mary-approval-options").assertDoesNotExist()
                tagged("mary-approval-current-${next.name}").assertIsDisplayed()
            }
            tagged("mary-settings-approval-mode").performClick(); frames()
            tagged("mary-approval-option-ALLOW_ALL").assertIsSelected()
            back()
            tagged("mary-approval-options").assertDoesNotExist()
            tagged("mary-settings-approval-mode").performClick(); frames()
            tagged("mary-approval-option-ASK").performClick(); frames()
            assertEquals(ApprovalMode.ASK, mode.value)
            tagged("mary-approval-mode").performClick(); frames()
            outside()
            tagged("mary-approval-options").assertDoesNotExist()
            tagged("mary-settings-approval-mode").performClick(); frames()
            compose.runOnUiThread { locked.value = true }; frames()
            tagged("mary-approval-options").assertDoesNotExist()
            tagged("mary-settings-approval-mode").assertIsNotEnabled()
            compose.runOnUiThread { locked.value = false }; frames()
            tagged("mary-approval-options").assertDoesNotExist()
            assertEquals(ApprovalMode.entries.toList() + ApprovalMode.ASK, events)
        }
    }

}
