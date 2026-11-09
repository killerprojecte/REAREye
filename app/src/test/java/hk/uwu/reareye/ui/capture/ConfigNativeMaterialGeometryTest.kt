package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.reareye.R
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.GlassTabs
import hk.uwu.mixui.components.TopBarIconButton
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.ui.FeatureGuideAnchors
import hk.uwu.reareye.ui.LocalFeatureGuideAnchors
import hk.uwu.reareye.ui.components.config.ConfigDashboard
import hk.uwu.reareye.ui.components.config.buildMoreCategories
import hk.uwu.reareye.ui.config.REAREyeConfig
import org.junit.After
import org.junit.Assert.*
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

/** Actual Config More geometry, native gestures and guide registry. Runs one explicit geometry mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigNativeMaterialGeometryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var guide = FeatureGuideAnchors()
    private var touchSlop = 0f
    private var morePosition = 0 to 0
    private var storeClicks = 0
    private var searchClicks = 0
    private val mode get() = System.getProperty("ui.capture.geometry.mode", "light").also {
        require(it in listOf("light", "dark")) { "One mode per invocation" }
    }
    private data class Geometry(val guide: Rect, val track: Rect, val store: Rect, val search: Rect)
    private fun text(id: Int) = activity!!.get().getString(id)
    private fun list() = compose.onNodeWithTag("config-more-list")
    private fun track() = compose.onNodeWithTag(if (Build.VERSION.SDK_INT >= 33) "glass-tabs-miuix" else "tabs-miuix", useUnmergedTree = true)
    private fun root() = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("config-final-root")), useUnmergedTree = true)
    private fun bounds(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInWindow
    private fun settle() { repeat(16) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun settleList() {
        var previous = morePosition; var stable = 0
        repeat(100) {
            compose.mainClock.advanceTimeBy(50); compose.waitForIdle()
            stable = if (previous == morePosition) stable + 1 else 0
            previous = morePosition
            if (stable >= 12) { settle(); return }
        }
        fail("More list still moving after 5s")
    }
    @Before fun requireCapture() {
        assertTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun open(rtl: Boolean = false, fontScale: Float = 1f) {
        compose.runOnUiThread {
            activity?.close(); guide = FeatureGuideAnchors(); morePosition = 0 to 0
            storeClicks = 0; searchClicks = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                CaptureTheme("miuix", mode == "dark") {
                    val configuration = LocalViewConfiguration.current
                    SideEffect { touchSlop = configuration.touchSlop }
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalFeatureGuideAnchors provides guide,
                        LocalDensity provides Density(density.density, fontScale),
                        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        Box(Modifier.fillMaxSize().testTag("config-final-root")) {
                            ConfigDashboard(
                                moreCategories = buildMoreCategories(REAREyeConfig),
                                contentPadding = PaddingValues(0.dp), selectedTabIndex = 3,
                                onMoreScrollChanged = { i, o -> morePosition = i to o },
                                onOpenCategory = {}, onOpenScriptManagement = {}, onMoreSearchRequested = { searchClicks++ },
                                onOpenStore = { storeClicks++ }, onOpenFavoriteCategory = {}, favoriteNodeCount = 0,
                            )
                        }
                    }
                }
            }
        }
        settle()
        track().assertIsDisplayed()
    }
    private fun geometry(stage: String): Geometry {
        val header = guide.coordinates["config_header_3"]?.takeIf { it.isAttached }?.boundsInWindow()
        assertNotNull("Real config_header_3 guide must remain registered", header)
        val g = Geometry(header!!, bounds(track()),
            bounds(compose.onNodeWithContentDescription(text(R.string.store_navigation))),
            bounds(compose.onNodeWithContentDescription(text(R.string.config_more_search))))
        val directory = File(System.getProperty("ui.capture.output"), "config-final-geometry/$mode").apply { mkdirs() }
        File(directory, "$stage.txt").writeText("$g\nmorePosition=$morePosition\ntouchSlop=$touchSlop\n")
        val bitmap = root().captureToImage().asAndroidBitmap()
        File(directory, "$stage.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        for ((name, b) in listOf("track" to g.track, "store" to g.store, "search" to g.search)) {
            assertTrue("Guide encloses $name at $stage", g.guide.left <= b.left && g.guide.top <= b.top &&
                g.guide.right >= b.right && g.guide.bottom >= b.bottom)
        }
        assertEquals("Keep native joined-track height", 40f, g.track.height, 1f)
        assertEquals("Keep native toolbar footprint", 44f, g.store.width, 1f)
        assertEquals(44f, g.store.height, 1f)
        assertEquals("More guide retains its original bottom padding", 8f, g.guide.bottom - g.track.bottom, 1f)
        return g
    }
    private fun restoreTop() {
        list().performScrollToIndex(0); settleList()
        repeat(3) {
            val l = bounds(list()); val t = bounds(track())
            val startY = maxOf(l.height * 0.4f, t.bottom - l.top + 24f)
            assertTrue(startY < l.height * 0.9f)
            list().performTouchInput {
                swipe(Offset(center.x, startY), Offset(center.x, height * 0.9f), durationMillis = 350)
            }
            settleList()
        }
        assertEquals(0 to 0, morePosition)
    }
    @Test fun realGuideCoversExpandedPartialAndCollapsedNativeHeader() {
        open()
        val expanded = geometry("portrait-expanded")
        list().performTouchInput { swipeUp() }; settleList()
        assertTrue(morePosition.first > 0 || morePosition.second > 0)
        val collapsed = geometry("portrait-collapsed")
        assertTrue("Native bar really collapsed", expanded.guide.height > collapsed.guide.height)
        restoreTop()
        val restored = geometry("portrait-restored")
        assertEquals(expanded.guide.height, restored.guide.height, 1f)
        assertEquals(expanded.track.top, restored.track.top, 1f)
        val range = expanded.guide.height - collapsed.guide.height
        val l = bounds(list())
        val start = Offset(l.width / 2f, maxOf(l.height * 0.65f, restored.track.bottom - l.top + 32f))
        // Keep the pointer down: a completed gesture can snap to an endpoint and
        // cannot prove fractional native collapse. No scroll state is overwritten.
        list().performTouchInput { down(start); moveBy(Offset(0f, -(touchSlop + range / 2f))) }
        try {
            compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
            val partial = geometry("portrait-held-partial")
            assertTrue("Held gesture remains between native endpoints", partial.guide.height > collapsed.guide.height &&
                partial.guide.height < expanded.guide.height)
            assertEquals(expanded.track.left, partial.track.left, 1f)
            assertEquals(expanded.track.width, partial.track.width, 1f)
        } finally {
            list().performTouchInput { up() }
        }
        settleList(); restoreTop()
        assertEquals(expanded.guide.height, geometry("portrait-after-partial-return").guide.height, 1f)
        mochiIgnoresMiuixMaterialPolicy()
    }
    private fun mochiIgnoresMiuixMaterialPolicy() {
        var original: IntArray? = null
        var originalBounds: Rect? = null
        for (inherit in listOf(true, false)) {
            var clicks = 0
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    CaptureTheme("mochi", mode == "dark") {
                        Box(Modifier.fillMaxSize().testTag("config-final-root")) {
                            GlassTopBarScaffold(title = "Mochi negative control", isContentScrolled = true,
                                inheritMiuixBarMaterial = inherit,
                                navigationIcon = { TopBarIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("negative-button")) { Text("B") } },
                                pinnedHeader = { GlassTabs(listOf("One", "Two"), 0, {}, Modifier.fillMaxWidth().padding(12.dp)) },
                            ) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawRect(Color(0xFF2165D6))
                                    drawRect(Color(0xFFE73C42), Offset.Zero, Size(size.width / 2, size.height))
                                }
                            }
                        }
                    }
                }
            }
            settle()
            compose.onNodeWithTag("negative-button").performTouchInput { click() }; settle()
            assertEquals(1, clicks)
            val currentBounds = bounds(compose.onNodeWithTag("glass-tabs-mochi"))
            val image = root().captureToImage().asAndroidBitmap()
            val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            val folder = File(System.getProperty("ui.capture.output"), "config-final-geometry/$mode").apply { mkdirs() }
            File(folder, "mochi-inherit-$inherit.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            if (inherit) { original = pixels; originalBounds = currentBounds } else {
                assertEquals("MIUIX policy cannot move Mochi", originalBounds, currentBounds)
                assertArrayEquals("MIUIX policy cannot alter Mochi pixels", original!!, pixels)
            }
        }
    }
    private fun injectInsets(left: Int, right: Int, top: Int) {
        compose.runOnUiThread {
            val builder = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), top > 0)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, right, 0))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, right, 0))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), right > 0)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(left, 0, 0, 0))
            builder.setDisplayCutout(if (left == 0) null else DisplayCutoutCompat(
                android.graphics.Rect(left, 0, 0, 0), listOf(android.graphics.Rect(0, 120, left, 240))))
            ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView, builder.build())
        }
        settle()
    }
    @Test
    @Config(qualifiers = "en-rUS-w800dp-h400dp-land-mdpi")
    fun landscapeCutoutAndNavigationInsetsAreAppliedOnce() {
        open(rtl = true, fontScale = 1.3f); injectInsets(left = 0, right = 0, top = 0)
        val baseline = geometry("landscape-zero-insets")
        injectInsets(left = 36, right = 48, top = 24)
        val inset = geometry("landscape-cutout-navigation")
        assertEquals("One left cutout inset on the detached track", baseline.track.left + 36f, inset.track.left, 1f)
        assertEquals("One right navigation inset on the detached track", baseline.track.right - 48f, inset.track.right, 1f)
        // Native navigation/actions exchange sides in RTL; physical insets do not.
        assertEquals(baseline.store.right - 48f, inset.store.right, 1f)
        assertEquals(baseline.search.left + 36f, inset.search.left, 1f)
        assertEquals("Status inset belongs to native bar only", baseline.track.top + 24f, inset.track.top, 1f)
        list().performTouchInput { swipeUp() }; settleList()
        val collapsed = geometry("landscape-inset-collapsed")
        assertEquals(inset.track.left, collapsed.track.left, 1f)
        assertEquals(inset.track.width, collapsed.track.width, 1f)
        restoreTop()
        assertEquals(inset.guide.height, geometry("landscape-inset-restored").guide.height, 1f)
        injectInsets(left = 0, right = 0, top = 0)
        assertEquals(baseline.track, geometry("landscape-insets-cleared").track)
    }
    @Test fun detachedHeaderBlankSpaceDoesNotActivateBody() {
        val bodyClicks = androidx.compose.runtime.mutableStateOf(0)
        val selected = androidx.compose.runtime.mutableStateOf(0)
        compose.runOnUiThread {
            activity?.close(); guide = FeatureGuideAnchors(); storeClicks = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme("miuix", mode == "dark") {
                    CompositionLocalProvider(LocalFeatureGuideAnchors provides guide) {
                        Box(Modifier.fillMaxSize().testTag("config-final-root")) {
                            // The production Dashboard owns header layout/hit testing. A real,
                            // full-height native Button in each test body makes click-through
                            // observable even in the track's outer 12dp padding. No direct
                            // invocation of callbacks or semantics performClick is used.
                            val body: @androidx.compose.runtime.Composable () -> Unit = {
                                hk.uwu.mixui.components.Button(
                                    onClick = { bodyClicks.value++ }, modifier = Modifier.fillMaxSize(),
                                ) { Text("Body pointer counter: ${bodyClicks.value}") }
                            }
                            ConfigDashboard(
                                moreCategories = buildMoreCategories(REAREyeConfig),
                                contentPadding = PaddingValues(0.dp), selectedTabIndex = selected.value,
                                onSelectedTabIndexChange = { selected.value = it },
                                onOpenCategory = {}, onOpenScriptManagement = {}, onMoreSearchRequested = {},
                                onOpenStore = { storeClicks++ }, onOpenFavoriteCategory = {}, favoriteNodeCount = 0,
                                cardContent = { _, _, _, _, _ -> body() },
                                componentContent = { _, _, _, _, _ -> body() },
                            )
                        }
                    }
                }
            }
        }
        settle()
        fun pointerTap(pointInWindow: Offset) {
            val origin = bounds(root()).topLeft
            root().performTouchInput { click(pointInWindow - origin) }
            settle()
        }
        val window = bounds(root())
        val exposedBody = Offset(window.center.x, window.top + window.height * 0.75f)
        pointerTap(exposedBody)
        assertEquals("The real underlying body must respond before testing occlusion", 1, bodyClicks.value)
        for (expectedSelection in listOf(0, 1)) {
            if (expectedSelection == 1) {
                compose.onNodeWithText(text(R.string.config_tab_components)).performTouchInput { click() }; settle()
                assertEquals("Native option selection remains functional", 1, selected.value)
            }
            val t = bounds(track())
            val cells = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected) and
                    hasAnyAncestor(hasTestTag("glass-tabs-miuix")), useUnmergedTree = true,
            ).fetchSemanticsNodes().map { it.boundsInWindow }.sortedBy { it.left }
            assertEquals(4, cells.size)
            assertTrue("A genuine native inter-segment gap is required", cells[0].right < cells[1].left)
            val emptyPoints = listOf(
                "left-padding" to Offset((window.left + t.left) / 2f, t.center.y),
                "right-padding" to Offset((t.right + window.right) / 2f, t.center.y),
                "segment-gap" to Offset((cells[0].right + cells[1].left) / 2f, t.center.y),
            )
            for ((name, point) in emptyPoints) {
                pointerTap(point)
                assertEquals("$name must not activate the body below the real header", 1, bodyClicks.value)
                assertEquals("$name must not select another native option", expectedSelection, selected.value)
            }
        }
        compose.onNodeWithContentDescription(text(R.string.store_navigation)).performTouchInput { click() }; settle()
        assertEquals("Header shield must not consume its child button", 1, storeClicks)
        pointerTap(exposedBody)
        assertEquals("Shield must end at the header, not block the exposed body", 2, bodyClicks.value)
        compose.onNodeWithText(text(R.string.config_tab_cards)).performTouchInput { click() }; settle()
        assertEquals("Native return selection remains functional", 0, selected.value)
    }

}
