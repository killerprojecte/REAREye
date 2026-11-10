package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.script.ScriptProject
import hk.uwu.reareye.script.ScriptProjectKind
import hk.uwu.reareye.ui.components.script.ScriptList
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual ScriptList with inert callbacks. Never opens files or executes Lua. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptListNativeTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val events = mutableListOf<String>()
    private fun settle() { repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun open(backend: MixUiBackend, dark: Boolean, projects: List<ScriptProject>, fontScale: Float = 1f) {
        compose.mainClock.autoAdvance = false
        events.clear()
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                NativeTitleTestEnvironment(androidx.compose.ui.unit.LayoutDirection.Ltr, fontScale) {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    ScriptList(projects, 80.dp, onBack = { events += "back" },
                        onRefresh = { events += "refresh" }, onImport = { events += "import" },
                        onExport = { events += "export:${it.id}" }, onOpen = { events += "open:${it.id}" },
                        onRename = { events += "rename:${it.id}" }, onDelete = { events += "delete:${it.id}" },
                        onNew = { events += "new" }, onNewProject = { events += "project" })
                }
                }
            }
        }
        settle()
    }
    private fun capture(name: String) {
        val folder = File(System.getProperty("ui.capture.output"), "script-native-list").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    @Test fun actualEmptyListKeepsEveryToolbarAction() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, emptyList())
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
            compose.onNodeWithText(label(R.string.script_empty)).assertIsDisplayed()
            capture("${backend.name}-${if (dark) "dark" else "light"}-empty")
            listOf(R.string.script_new, R.string.script_new_project, R.string.script_import,
                R.string.script_refresh, R.string.scene_route_navigate_back).forEach { id ->
                compose.onNodeWithContentDescription(label(id)).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            assertEquals(listOf("new", "project", "import", "refresh", "back"), events)
        }
    }
    @Test fun actualProjectRowsKeepCallbacksAndLongListReachability() {
        val projects = List(32) { index -> ScriptProject("id-$index", "Script $index", File("/fixture"),
            File("/fixture/script-$index.lua"), ScriptProjectKind.STANDALONE,
            description = "A script description that wraps across the native row.") }
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, projects)
            compose.onNodeWithText("Script 0").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onAllNodesWithContentDescription(label(R.string.script_edit))[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onAllNodesWithContentDescription(label(R.string.script_rename))[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onAllNodesWithContentDescription(label(R.string.script_export))[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onAllNodesWithText(label(R.string.script_delete))[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals("$backend dark=$dark", listOf("open:id-0", "open:id-0", "rename:id-0", "export:id-0", "delete:id-0"), events)
            events.clear()
            compose.onAllNodesWithContentDescription(label(R.string.script_rename))[0].performTouchInput { click() }
            settle()
            assertEquals(listOf("rename:id-0"), events)
            capture("${backend.name}-${if (dark) "dark" else "light"}-projects")
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
            compose.onNode(hasScrollAction()).performScrollToKey(projects.last().mainFile.absolutePath)
            settle()
            compose.onNodeWithText("Script 31").assertIsDisplayed()
            capture("${backend.name}-${if (dark) "dark" else "light"}-last")
        }
    }
    @Test fun productionScriptCompactTitleNeverOverlapsFourActions() {
        val projects = List(24) { index -> ScriptProject("id-$index", "Script $index", File("/fixture"),
            File("/fixture/script-$index.lua"), ScriptProjectKind.STANDALONE) }
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            open(MixUiBackend.Mochi, dark, projects, fontScale)
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; settle()
            val title = compose.nativeTitleGeometry(label(R.string.script_management_title))
                .first { it.layout.layoutInput.style.fontSize.value == 17f }
            val actions = listOf(R.string.script_new, R.string.script_new_project, R.string.script_import, R.string.script_refresh)
            actions.forEach { id ->
                val action = compose.onNodeWithContentDescription(label(id)).fetchSemanticsNode().boundsInRoot
                assertTrue("Compact title overlaps action ${label(id)}", !title.bounds.overlaps(action))
                compose.onNodeWithContentDescription(label(id)).performTouchInput { click() }; settle()
            }
            assertEquals(listOf("new", "project", "import", "refresh"), events)
            capture("Mochi-$dark-$fontScale-bounded-compact-title")
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
    fun denseShrunkProductionScriptHeaderKeepsDisjointActions() {
        productionScriptCompactTitleNeverOverlapsFourActions()
    }

    @Test fun productionShortAndLongListsRespondToDragFlingAndEdgeReversal() {
        for (backend in MixUiBackend.entries) for (count in listOf(1, 40)) {
            val projects = List(count) { index -> ScriptProject("id-$index", "Script $index", File("/fixture"),
                File("/fixture/script-$index.lua"), ScriptProjectKind.STANDALONE) }
            open(backend, false, projects)
            val scroller = compose.onNode(hasScrollAction())
            fun position() = scroller.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            val start = position()
            // Slow pointer drag and high-velocity fling both use production content.
            scroller.performTouchInput { swipeUp(durationMillis = 1000) }; settle()
            if (count > 1) assertTrue("$backend long list must move upward", position() > start)
            scroller.performTouchInput { swipeUp(durationMillis = 120) }; settle()
            capture("$backend-$count-after-fling")
            // Reach a real end, pull beyond it, then reverse. Elastic feedback must
            // settle without retaining a translated list or swallowing the next drag.
            scroller.performScrollToKey(projects.last().mainFile.absolutePath); settle()
            scroller.performTouchInput { swipeUp(durationMillis = 120) }; settle()
            val last = compose.onNodeWithText("Script ${count - 1}")
            last.assertIsDisplayed()
            val bottomPosition = position()
            // The list extends behind its pinned bar. Start on visible content,
            // not swipeDown's default 10% point underneath that overlay.
            scroller.performTouchInput {
                swipe(start = center, end = androidx.compose.ui.geometry.Offset(center.x, height * 0.9f), durationMillis = 250)
            }; settle()
            if (count > 1) assertTrue("$backend must reverse after bottom edge: before=$bottomPosition after=${position()}", position() < bottomPosition)
            // ScrollToKey is only setup for the top-edge pointer test, not its oracle.
            scroller.performScrollToKey(projects.first().mainFile.absolutePath); settle()
            repeat(2) { scroller.performTouchInput { swipeDown(durationMillis = 120) }; settle() }
            val first = compose.onNodeWithText("Script 0")
            first.assertIsDisplayed()
            val topBounds = first.fetchSemanticsNode().boundsInRoot
            scroller.performTouchInput { swipeDown(durationMillis = 250) }; settle()
            val settledBounds = first.fetchSemanticsNode().boundsInRoot
            assertEquals("$backend top overscroll must spring back", topBounds.top, settledBounds.top, 1f)
            capture("$backend-$count-top-edge-restored")
        }
    }

    @Test fun progressiveSamplingFollowsLazyRowsInsteadOfRetainingFirstCard() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().enableEdgeToEdge()
                activity!!.get().setContent {
                    MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        hk.uwu.mixui.components.LargeTitleLazyScaffold(title = "Sampling") {
                            items(12, key = { it }) { index ->
                                androidx.compose.foundation.layout.Box(
                                    androidx.compose.ui.Modifier
                                        .fillMaxWidth()
                                        .height(400.dp)
                                        .background(if (index % 2 == 0) androidx.compose.ui.graphics.Color(0xFFDD2233) else androidx.compose.ui.graphics.Color(0xFF2244DD)),
                                )
                            }
                        }
                    }
                }
            }
            settle()
            val list = compose.onNode(hasScrollAction())
            list.performTouchInput { swipeUp(durationMillis = 1000) }; settle()
            fun imageAt(index: Int): Bitmap {
                // Mochi inserts its native large title as lazy item zero.
                list.performScrollToIndex(index + if (backend == MixUiBackend.Mochi) 1 else 0)
                settle()
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 180f * compose.density.density) }
                settle()
                return compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            }
            if (backend == MixUiBackend.Miuix) {
                val root = generateSequence(compose.onNode(hasScrollAction())
                    .fetchSemanticsNode().layoutInfo) { it.parentInfo }.last()
                compose.runOnIdle {
                    val band = samplingDescendants(root).firstOrNull { layout ->
                        layout.getModifierInfo().any { info ->
                            val element = info.modifier
                            element.javaClass.name == "top.yukonga.miuix.kmp.blur.DrawBackdropElement" &&
                                element.javaClass.getDeclaredField("progressiveGradient")
                                    .apply { isAccessible = true }.get(element) != null &&
                                element.javaClass.getDeclaredField("enabled")
                                    .apply { isAccessible = true }.getBoolean(element)
                        }
                    }
                    assertTrue("Must mount native progressive blur, not just a transparent bar", band != null)
                }
            }
            val red = imageAt(2)
            val blue = imageAt(3)
            val again = imageAt(2)
            val folder = File(System.getProperty("ui.capture.output"), "progressive-live-sampling-${compose.density.density}").apply { mkdirs() }
            listOf("red" to red, "blue" to blue, "red-return" to again).forEach { (name, bitmap) ->
                File(folder, "$backend-$dark-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            fun channel(bitmap: Bitmap, shift: Int): Double {
                var sum = 0L; var count = 0
                for (y in (28 * compose.density.density).toInt() until (44 * compose.density.density).toInt())
                    for (x in (20 * compose.density.density).toInt() until (100 * compose.density.density).toInt()) {
                    sum += (bitmap.getPixel(x, y) shr shift) and 255; count++
                }
                return sum.toDouble() / count
            }
            val redBias = channel(red, 16) - channel(red, 0)
            val blueBias = channel(blue, 0) - channel(blue, 16)
            println("LIVE_PROGRESSIVE $backend dark=$dark red=$redBias blue=$blueBias")
            assertTrue("Red row must reach header: $backend $dark $redBias", redBias > 20)
            assertTrue("Blue row must replace red header sample: $backend $dark $blueBias", blueBias > 20)
            assertTrue("Returning must replace blue with red", channel(again,16) - channel(again,0) > 20)
        }
    }

    @Test fun collapsedShortPageStillShowsProgressiveMaterial() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Miuix, MixColorSchemeMode.Light) {
                    hk.uwu.mixui.components.LargeTitleColumnScaffold(title = "Short page") {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier
                            .fillMaxWidth().height(100.dp).testTag("short-page-body").background(androidx.compose.ui.graphics.Color.Red))
                    }
                }
            }
        }
        settle()
        val initialTop = compose.onNodeWithTag("short-page-body").fetchSemanticsNode().boundsInRoot.top
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 600) }
        settle()
        val collapsedTop = compose.onNodeWithTag("short-page-body").fetchSemanticsNode().boundsInRoot.top
        val displacement = initialTop - collapsedTop
        assertTrue("The native header must actually collapse", displacement > 0f)
        val root = generateSequence(compose.onNode(hasScrollAction())
            .fetchSemanticsNode().layoutInfo) { it.parentInfo }.last()
        fun assertAlpha(expected: Float) = compose.runOnIdle {
            val band = samplingDescendants(root).first { layout ->
                layout.getModifierInfo().any { info ->
                    val e = info.modifier
                    e.javaClass.name == "top.yukonga.miuix.kmp.blur.DrawBackdropElement" &&
                        e.javaClass.getDeclaredField("progressiveGradient").apply { isAccessible = true }.get(e) != null
                }
            }
            val layer = band.getModifierInfo().map { it.modifier }.first {
                it.javaClass.name == "androidx.compose.ui.graphics.BlockGraphicsLayerElement"
            }
            @Suppress("UNCHECKED_CAST")
            val block = layer.javaClass.getDeclaredField("block").apply { isAccessible = true }
                .get(layer) as (androidx.compose.ui.graphics.GraphicsLayerScope) -> Unit
            val scope = Class.forName("androidx.compose.ui.graphics.ReusableGraphicsLayerScope")
                .getDeclaredConstructor().newInstance() as androidx.compose.ui.graphics.GraphicsLayerScope
            block(scope)
            assertEquals("Progressive alpha must follow the real header displacement even without body scrolling",
                expected, scope.alpha, 0.01f)
        }
        assertAlpha((displacement / (48f * compose.density.density)).coerceIn(0f, 1f))
        repeat(2) {
            compose.onNode(hasScrollAction()).performTouchInput {
                swipe(start = center, end = androidx.compose.ui.geometry.Offset(center.x, height * 0.9f), durationMillis = 600)
            }; settle()
            assertAlpha(0f)
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 600) }; settle()
            assertAlpha((displacement / (48f * compose.density.density)).coerceIn(0f, 1f))
        }
    }

    @Test fun actualScriptCardsReplaceTheHeaderSampleWhileDragging() {
        val projects = List(24) { index -> ScriptProject("id-$index",
            if (index < 12) "MMMMMMMMMMMMMMMM" else "iiiiiiiiiiiiiiii",
            File("/fixture"), File("/fixture/script-$index.lua"), ScriptProjectKind.STANDALONE,
            description = if (index < 12) "MMMMMMMMMMMMMMMM" else "iiiiiiiiiiiiiiii") }
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, projects)
            val list = compose.onNode(hasScrollAction())
            list.performTouchInput { swipeUp(durationMillis = 1000) }; settle()
            fun sample(index: Int, name: String): Bitmap {
                list.performScrollToKey(projects[index].mainFile.absolutePath)
                settle()
                // Use actual pointer motion, not ScrollBy, for the sampled frames.
                list.performTouchInput { swipe(start = androidx.compose.ui.geometry.Offset(center.x, 500f),
                    end = androidx.compose.ui.geometry.Offset(center.x, 380f), durationMillis = 800) }
                settle()
                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                    .copy(Bitmap.Config.ARGB_8888, false)
                val folder = File(System.getProperty("ui.capture.output"), "script-native-list").apply { mkdirs() }
                File(folder, "$backend-$dark-live-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                return bitmap
            }
            val first = sample(2, "wide-text")
            val later = sample(14, "narrow-text")
            var difference = 0L; var count = 0
            for (y in 48 until 70) for (x in 70 until 190) {
                val a = first.getPixel(x,y); val b = later.getPixel(x,y)
                for (shift in listOf(0,8,16)) {
                    difference += kotlin.math.abs(((a shr shift) and 255) - ((b shr shift) and 255)); count++
                }
            }
            val delta = difference.toDouble() / count
            println("SCRIPT_CARD_SAMPLE $backend dark=$dark delta=$delta")
            assertTrue("Actual script cards must replace the old header sample: $delta", delta > 0.5)
        }
    }

    @Test fun progressiveSamplingTracksPlacementWithoutRecomposingContent() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().enableEdgeToEdge()
                activity!!.get().setContent {
                    MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        hk.uwu.mixui.components.LargeTitleColumnScaffold(title = "Placement") {
                            // One long, unchanged drawing. Scrolling only changes placement,
                            // never the content composition or its recorded drawing commands.
                            androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxWidth().height(5000.dp)) {
                                repeat(13) { index ->
                                    drawRect(if (index % 2 == 0) androidx.compose.ui.graphics.Color(0xFFDD2233)
                                        else androidx.compose.ui.graphics.Color(0xFF2244DD),
                                        topLeft = androidx.compose.ui.geometry.Offset(0f, index * 400.dp.toPx()),
                                        size = androidx.compose.ui.geometry.Size(size.width, 400.dp.toPx()))
                                }
                            }
                        }
                    }
                }
            }
            settle()
            val list = compose.onNode(hasScrollAction())
            list.performTouchInput { swipeUp(durationMillis = 1000) }; settle()
            fun captureShift(): Bitmap = compose.onRoot(useUnmergedTree = true).captureToImage()
                .asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            val first = captureShift()
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f * compose.density.density) }; settle()
            val next = captureShift()
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -400f * compose.density.density) }; settle()
            val returned = captureShift()
            fun bias(bitmap: Bitmap): Double {
                var sum = 0L; var count = 0
                for (y in (28 * compose.density.density).toInt() until (44 * compose.density.density).toInt())
                    for (x in (20 * compose.density.density).toInt() until (100 * compose.density.density).toInt()) {
                    val pixel = bitmap.getPixel(x,y)
                    sum += ((pixel shr 16) and 255) - (pixel and 255); count++
                }
                return sum.toDouble() / count
            }
            val a=bias(first); val b=bias(next); val c=bias(returned)
            println("PLACEMENT_SAMPLE $backend dark=$dark first=$a next=$b returned=$c")
            assertTrue("Scrolling unchanged content by one stripe must change the header sample", a*b < 0)
            assertEquals("Reverse must restore the same stripe", a,c,8.0)
            val folder=File(System.getProperty("ui.capture.output"),"progressive-placement-${compose.density.density}").apply { mkdirs() }
            listOf("first" to first,"next" to next,"return" to returned).forEach { (name,bitmap) ->
                File(folder,"$backend-$dark-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w400dp-h850dp-xxhdpi")
    fun denseScreenKeepsLazyAndPlacementSamplingLive() {
        progressiveSamplingFollowsLazyRowsInsteadOfRetainingFirstCard()
        progressiveSamplingTracksPlacementWithoutRecomposingContent()
    }

    private fun samplingDescendants(node: LayoutInfo): Sequence<LayoutInfo> = sequence {
        yield(node)
        val method = node.javaClass.methods.first { it.name.startsWith("getChildren$") && it.parameterCount == 0 }
        @Suppress("UNCHECKED_CAST")
        val children = method.invoke(node) as List<LayoutInfo>
        children.forEach { yieldAll(samplingDescendants(it)) }
    }

}
