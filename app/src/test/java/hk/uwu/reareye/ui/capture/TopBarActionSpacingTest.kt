package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.*
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.script.ScriptList
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

/** Layout and input contracts only. Robolectric cannot validate device GPU glass/shadows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopBarActionSpacingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val events = mutableListOf<Int>()
    private val title = "A deliberately very long toolbar title that must not cover its actions"
    private enum class Host { Glass, Lazy, Column, Compact }

    @After fun close() { compose.runOnUiThread { activity?.close(); activity = null } }
    private fun settle() { repeat(3) { compose.mainClock.advanceTimeBy(150); compose.waitForIdle() } }

    private fun open(width: Int, dark: Boolean, direction: LayoutDirection, content: @Composable () -> Unit) {
        events.clear()
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                NativeTitleTestEnvironment(direction, 1.3f) {
                    MixUiHost(MixUiBackend.Miuix, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        Box(Modifier.width(width.dp).fillMaxHeight().testTag("spacing-host")) { content() }
                    }
                }
            }
        }
        settle()
    }

    @Composable private fun Fixture(host: Host, count: Int) {
        val compact = host == Host.Compact
        val navigation: @Composable () -> Unit = {
            if (compact) CompactTopBarIconButton({ events += -1 }, Modifier.testTag("spacing-nav")) { Text("N") }
            else TopBarIconButton({ events += -1 }, Modifier.testTag("spacing-nav")) { Text("N") }
        }
        val actions: @Composable RowScope.() -> Unit = {
            repeat(count) { index ->
                if (compact) CompactTopBarIconButton({ events += index }, Modifier.testTag("spacing-action-$index")) { Text("$index") }
                else TopBarIconButton({ events += index }, Modifier.testTag("spacing-action-$index")) { Text("$index") }
            }
        }
        when (host) {
            Host.Glass -> GlassTopBarScaffold(title, isContentScrolled = true,
                navigationIcon = navigation, actions = actions) { padding ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                    items(30) { Text("Body $it", Modifier.fillMaxWidth().height(64.dp)) }
                }
            }
            Host.Lazy -> LargeTitleLazyScaffold(title, navigationIcon = navigation, actions = actions) {
                items(30) { Text("Body $it", Modifier.fillMaxWidth().height(64.dp)) }
            }
            Host.Column -> LargeTitleColumnScaffold(title, navigationIcon = navigation, actions = actions) {
                repeat(30) { Text("Body $it", Modifier.fillMaxWidth().height(64.dp)) }
            }
            Host.Compact -> CompactGlassTopBarScene(isContentScrolled = true) { body ->
                Box(Modifier.fillMaxSize().then(body))
                CompactGlassTopBar(title, navigationIcon = navigation, actions = actions)
            }
        }
    }

    private fun assertActions(
        nodes: List<SemanticsNodeInteraction>, diameter: Float, direction: LayoutDirection,
        titleText: String = title, checkExpandedTitle: Boolean = false,
    ) {
        val outer = compose.onNodeWithTag("spacing-host").fetchSemanticsNode().boundsInRoot
        val bounds = nodes.map { node ->
            node.assertIsDisplayed().assertHasClickAction()
            node.fetchSemanticsNode().boundsInRoot.also {
                // mdpi fixture: actual clickable layout bounds, not expanded minimum touch targets.
                assertEquals("Button width changed", diameter, it.width, 0.5f)
                assertEquals("Button height changed", diameter, it.height, 0.5f)
                assertTrue("Action escaped narrow host: $it / $outer", it.left >= outer.left && it.right <= outer.right)
            }
        }
        bounds.zipWithNext().forEach { (first, second) ->
            val gap = if (direction == LayoutDirection.Ltr) second.left - first.right else first.left - second.right
            assertEquals("Adjacent clickable surfaces must have 8dp gap", 8f, gap, 0.5f)
            assertTrue("Action rectangles overlap", !first.overlaps(second))
        }
        assertTitleDoesNotOverlap(bounds, titleText, checkExpandedTitle)
    }

    private fun assertTitleDoesNotOverlap(actions: List<Rect>, titleText: String, checkExpanded: Boolean) {
        val titles = compose.nativeTitleGeometry(titleText)
        val compact = titles.filter { it.layout.layoutInput.style.fontSize.value < 28f }
        assertTrue("Native compact-title geometry must be present", compact.isNotEmpty())
        // Native collapsing bars retain the full-width large-title semantics even at alpha=0.
        // Check the bounded compact title, plus the expanded title only before any scrolling.
        val checked = if (checkExpanded) titles else compact
        checked.forEach { geometry ->
            val bounds = geometry.bounds
            if (bounds.width > 0 && bounds.height > 0) actions.forEach {
                assertTrue("Title overlaps an action: $bounds / $it", !bounds.overlaps(it))
            }
        }
    }

    private fun capture(host: Host, dark: Boolean) {
        val folder = File(System.getProperty("ui.capture.output", "build/ui-captures"),
            "topbar-action-spacing").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val name = "${host.name}-320dp-four-actions-ltr-${if (dark) "dark" else "light"}"
        File(folder, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun exercise(host: Host) {
        for (width in listOf(320, 400)) for (dark in listOf(false, true))
            for (direction in LayoutDirection.entries) for (count in listOf(2, 4)) {
                open(width, dark, direction) { Fixture(host, count) }
                val nodes = (0 until count).map { compose.onNodeWithTag("spacing-action-$it") }
                assertActions(nodes, if (host == Host.Compact) 48f else 44f, direction,
                    checkExpandedTitle = host != Host.Compact)
                if (host != Host.Compact) {
                    compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
                    settle()
                    assertActions(nodes, 44f, direction)
                }
                if (width == 320 && count == 4 && direction == LayoutDirection.Ltr) capture(host, dark)
                nodes.forEach { it.performTouchInput { click() } }
                compose.onNodeWithTag("spacing-nav").performTouchInput { click() }
                compose.runOnIdle { assertEquals((0 until count).toList() + -1, events) }
            }
    }

    @Test fun glassScaffoldSeparatesTwoAndFourActions() = exercise(Host.Glass)
    @Test fun lazyScaffoldSeparatesTwoAndFourActions() = exercise(Host.Lazy)
    @Test fun columnScaffoldSeparatesTwoAndFourActions() = exercise(Host.Column)
    @Test fun compactBarSeparatesTwoAndFourActions() = exercise(Host.Compact)

    @Test fun productionScriptListKeepsFourSpacedClickableActions() {
        val labels = listOf(R.string.script_new, R.string.script_new_project, R.string.script_import, R.string.script_refresh)
        for (width in listOf(320, 400)) for (dark in listOf(false, true)) for (direction in LayoutDirection.entries) {
            open(width, dark, direction) {
                ScriptList(emptyList(), 80.dp, onBack = { events += -1 },
                    onRefresh = { events += 3 }, onImport = { events += 2 },
                    onExport = {}, onOpen = {}, onRename = {}, onDelete = {},
                    onNew = { events += 0 }, onNewProject = { events += 1 })
            }
            val nodes = labels.map { compose.onNodeWithContentDescription(activity!!.get().getString(it)) }
            assertActions(nodes, 44f, direction,
                titleText = activity!!.get().getString(R.string.script_management_title), checkExpandedTitle = true)
            nodes.forEach { it.performTouchInput { click() } }
            compose.runOnIdle { assertEquals(listOf(0, 1, 2, 3), events) }
        }
    }
}
