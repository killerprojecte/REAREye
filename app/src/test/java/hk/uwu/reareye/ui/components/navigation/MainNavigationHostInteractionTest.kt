package hk.uwu.reareye.ui.components.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.components.*
import hk.uwu.mixui.motion.ArtVisibilityMotion
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.featureGuideAnchor
import hk.uwu.reareye.ui.screen.LocalMarySidebarPageGestureHost
import hk.uwu.reareye.ui.screen.MarySidebarPageGestureHost
import hk.uwu.reareye.ui.screen.MarySidebarGestureState
import hk.uwu.reareye.ui.screen.RegisterMarySidebarPageGestures
import hk.uwu.reareye.ui.screen.MarySidebarPagePreview
import hk.uwu.reareye.ui.screen.marySidebarBackground
import hk.uwu.reareye.ui.screen.marySidebarPageGesture
import hk.uwu.reareye.ui.theme.AppNavigationBarStyle
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * MainActivity's navigation ancestry, actual AppNavigationBar and route-driven page transition.
 * Not an end-to-end MainActivity launch: page bodies are synthetic, but the active Mary page
 * registers the production sidebar gesture owner and the bar uses real quick actions.
 * xxhdpi is a synthetic density probe, not an inference about the user's device configuration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h869dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainNavigationHostInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var route by mutableStateOf("home")
    private val selected = mutableListOf<String>()
    private val quickSelected = mutableListOf<NavigationQuickTarget>()
    private var redraw by mutableIntStateOf(0)
    private var sidebarOpened = false

    @Before fun requireUiProfile() {
        assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close() }
        compose.mainClock.autoAdvance = true
    }
    private fun frames(count: Int = 24) {
        repeat(count) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
    }
    private fun launch(style: AppNavigationBarStyle, rtl: Boolean, backend: MixUiBackend = MixUiBackend.Miuix) {
        compose.runOnUiThread {
            activity?.close()
            route = "home"; selected.clear(); quickSelected.clear(); redraw = 0; sidebarOpened = false
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                AppTheme(themeMode = if (backend == MixUiBackend.Mochi) AppThemeMode.MOCHI_LIGHT else AppThemeMode.MIUIX_LIGHT) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        val backdrop = rememberNavigationBackdrop()
                        val density = LocalDensity.current
                        var stableBottomInset by remember { mutableStateOf(0.dp) }
                        val sidebar = remember { MarySidebarPageGestureHost() }
                        CompositionLocalProvider(LocalMarySidebarPageGestureHost provides sidebar) {
                            Scaffold { _ ->
                                GlassBottomSheetScene {
                                    Box(Modifier.fillMaxSize().testTag("main-host")
                                        .graphicsLayer { clip = true }) {
                                        Box(Modifier.fillMaxSize()
                                            .marySidebarPageGesture(sidebar, enabled = route == "mary")
                                            .marySidebarBackground(sidebar).navigationBackdrop(backdrop)) {
                                            AnimatedContent(route, contentKey = { it }, transitionSpec = {
                                                (fadeIn(tween(210, delayMillis = 50)) + slideInHorizontally(tween(280)) { it / 3 }) togetherWith
                                                    (fadeOut(tween(180)) + slideOutHorizontally(tween(280)) { -it / 3 })
                                            }) { page ->
                                                Box(Modifier.fillMaxSize().padding(bottom = stableBottomInset)
                                                    .background(if (page == "home") Color(0xFFCEDEE8) else Color(0xFFF8D5C3))) {
                                                    if (page == "mary") {
                                                        val gestures = remember { MarySidebarGestureState() }
                                                        RegisterMarySidebarPageGestures(sidebar, gestures, enabled = !sidebarOpened,
                                                            beforeOpen = {}, onOpen = { sidebarOpened = true })
                                                    }
                                                    Text("$page $redraw", Modifier.testTag("page-$page"))
                                                }
                                            }
                                        }
                                        Box(Modifier.align(Alignment.BottomCenter).marySidebarBackground(sidebar)) {
                                            ArtVisibilityMotion(
                                                visible = true,
                                                modifier = Modifier.align(Alignment.BottomCenter).featureGuideAnchor("bottom_navigation")
                                                    .onGloballyPositioned { stableBottomInset = with(density) { it.size.height.toDp() } },
                                                enterAlphaDurationMillis = 260, enterTransformDurationMillis = 380,
                                                exitAlphaDurationMillis = 180, exitTransformDurationMillis = 240,
                                                hiddenEnterScale = 1f, hiddenExitScale = 1f, slideDivisor = 3,
                                                hiddenOffsetFallback = 28.dp,
                                            ) {
                                                AppNavigationBar(
                                                    modifier = Modifier.testTag("main-navigation"),
                                                    currentScreen = route,
                                                    navigationBarMode = style.mode(backend),
                                                    navigationBarStyle = style,
                                                    backdrop = backdrop,
                                                    shadowVisibilityProgress = if (redraw % 2 == 0) 1f else 0.99f,
                                                    onScreenSelected = { selected += it; route = it },
                                                    onQuickActionSelected = { quickSelected += it },
                                                )
                                            }
                                        }
                                        MarySidebarPagePreview(sidebar, enabled = route == "mary")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        frames(60)
    }
    private fun point(label: Int): Offset = compose.onNode(hasClickAction() and
        (hasText(activity!!.get().getString(label)) or hasContentDescription(activity!!.get().getString(label))))
        .fetchSemanticsNode().boundsInRoot.center

    private fun drag(from: Offset, to: Offset, interleaveFrames: Boolean, hold: Boolean = false) {
        val scene = compose.onNodeWithTag("main-host")
        scene.performTouchInput { down(from) }
        if (hold) {
            repeat(45) {
                compose.runOnIdle { redraw++ }
                frames(1)
            }
            scene.performTouchInput { advanceEventTime(720) }
        }
        repeat(12) { step ->
            scene.performTouchInput { moveTo(from + (to - from) * ((step + 1) / 12f), delayMillis = 16) }
            if (interleaveFrames) frames(1)
        }
        scene.performTouchInput { up() }
        frames(48)
    }

    @Test fun mainHierarchyAtHighDensityCommitsBothDirectionsWithoutDelayedSnapBack() = exerciseHost()

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h869dp-mdpi")
    fun mainHierarchyAtBaselineDensityCommitsBothDirectionsWithoutDelayedSnapBack() = exerciseHost()

    @Test fun heldDragsSurviveActivePagesAndMidGestureRecomposition() {
        for (rtl in listOf(false, true)) for ((backend, style) in listOf(
            MixUiBackend.Miuix to AppNavigationBarStyle.IOS,
            MixUiBackend.Miuix to AppNavigationBarStyle.HyperOS,
            MixUiBackend.Mochi to AppNavigationBarStyle.HyperOS,
        )) {
            launch(style, rtl, backend)
            val home = point(R.string.home_navigation)
            val config = point(R.string.configuration_navigation)
            val mary = point(R.string.mary_title)
            val about = point(R.string.about_navigation)
            repeat(3) {
                drag(home, config, interleaveFrames = true, hold = true)
                compose.runOnIdle { assertEquals("$backend $style rtl=$rtl Home→Config hold iteration=$it", "config", route) }
                // A quick Config→Mary drag must select; a deliberate Config hold belongs to
                // the quick menu and is checked separately below rather than disabling it.
                drag(config, mary, interleaveFrames = true)
                compose.runOnIdle { assertEquals("$backend $style rtl=$rtl Config→Mary iteration=$it", "mary", route) }
                drag(mary, home, interleaveFrames = true, hold = true)
                compose.runOnIdle {
                    assertEquals("$backend $style rtl=$rtl Mary→Home hold iteration=$it", "home", route)
                    assertFalse("Held navigation must not open Mary's sidebar", sidebarOpened)
                }
            }
            val beforeCancel = selected.size
            compose.onNodeWithTag("main-host").performTouchInput { down(home); moveTo(about); cancel() }
            frames(60)
            compose.runOnIdle { assertEquals("home", route); assertEquals(beforeCancel, selected.size) }
            drag(home, about, interleaveFrames = true, hold = true)
            compose.runOnIdle { assertEquals("about", route) }
        }
    }

    /** Diagnostic: IOS's pressed indicator is 78dp high, versus the 64dp layout.
     * This tests a release 3dp beyond layout but 4dp inside that visible pressed indicator.
     * It is not evidence of the user's exact finger coordinates.
     */
    @Test fun heldIosReleaseInsideExpandedIndicatorCommits() {
        launch(AppNavigationBarStyle.IOS, rtl = false)
        val home = point(R.string.home_navigation)
        val config = point(R.string.configuration_navigation)
        val px = activity!!.get().resources.displayMetrics.density
        val scene = compose.onNodeWithTag("main-host")
        scene.performTouchInput { down(home + Offset(0f, 24f * px)) }
        frames(45)
        scene.performTouchInput { advanceEventTime(720) }
        repeat(12) { step ->
            scene.performTouchInput {
                moveTo(home + (config - home) * ((step + 1) / 12f) + Offset(0f, 24f * px), delayMillis = 16)
            }
            frames(1)
        }
        // Native preview has reached Config. Finger lifts within its expanded pressed shape.
        scene.performTouchInput { moveTo(config + Offset(0f, 35f * px), delayMillis = 16); up() }
        frames(90)
        compose.runOnIdle { assertEquals("Expanded IOS indicator release", "config", route) }
    }

    @Test fun heldIosExpandedEdgeAndHardCancellationStayDistinct() {
        for (rtl in listOf(false, true)) for (sign in listOf(-1f, 1f)) {
            launch(AppNavigationBarStyle.IOS, rtl)
            val home = point(R.string.home_navigation)
            val config = point(R.string.configuration_navigation)
            val about = point(R.string.about_navigation)
            val px = activity!!.get().resources.displayMetrics.density
            val scene = compose.onNodeWithTag("main-host")
            fun beginHeldDrag() {
                scene.performTouchInput { down(home + Offset(0f, sign * 24f * px)) }
                frames(45)
                scene.performTouchInput { advanceEventTime(720) }
                // Enter the expanded edge diagonally with unambiguous horizontal intent.
                scene.performTouchInput {
                    moveTo(home + (config - home) * 0.25f + Offset(0f, sign * 35f * px), delayMillis = 32)
                }
                frames(1)
                repeat(6) { step ->
                    scene.performTouchInput {
                        moveTo(home + (config - home) * (0.25f + 0.75f * (step + 1) / 6f) + Offset(0f, sign * 35f * px), delayMillis = 16)
                    }
                    frames(1)
                }
            }
            beginHeldDrag()
            scene.performTouchInput { up() }
            frames(90)
            compose.runOnIdle { assertEquals("rtl=$rtl edge=$sign sustained expanded drag", listOf("config"), selected); route = "home"; selected.clear() }
            frames(60)
            for (cancelKind in listOf("system", "multiple", "far")) {
                beginHeldDrag()
                when (cancelKind) {
                    "system" -> scene.performTouchInput { cancel() }
                    "multiple" -> scene.performTouchInput { down(1, about); up(1); up(0) }
                    "far" -> scene.performTouchInput {
                        moveTo(config + Offset(0f, sign * 100f * px), delayMillis = 16)
                        moveTo(config + Offset(0f, sign * 35f * px), delayMillis = 16)
                        up()
                    }
                }
                frames(90)
                compose.runOnIdle { assertEquals("$cancelKind rtl=$rtl edge=$sign", "home", route); assertTrue(selected.isEmpty()) }
            }
            // A rejected stream must not poison a subsequent real selection.
            drag(home, config, interleaveFrames = true, hold = true)
            compose.runOnIdle { assertEquals(listOf("config"), selected) }
        }
    }

    @Test fun heldConfigToMaryIosLeavesRealQuickMenuForNavigation() =
        exerciseHeldConfigToMary(MixUiBackend.Miuix, AppNavigationBarStyle.IOS)
    @Test fun heldConfigToMaryHyperOsLeavesRealQuickMenuForNavigation() =
        exerciseHeldConfigToMary(MixUiBackend.Miuix, AppNavigationBarStyle.HyperOS)
    @Test fun heldConfigToMaryMochiLeavesRealQuickMenuForNavigation() =
        exerciseHeldConfigToMary(MixUiBackend.Mochi, AppNavigationBarStyle.HyperOS)

    private fun exerciseHeldConfigToMary(backend: MixUiBackend, style: AppNavigationBarStyle) {
        for (rtl in listOf(false, true)) {
            launch(style, rtl, backend)
            val config = point(R.string.configuration_navigation)
            val mary = point(R.string.mary_title)
            val scene = compose.onNodeWithTag("main-host")
            scene.performTouchInput { down(config); up() }
            frames(60)
            compose.runOnIdle { assertEquals("config", route); selected.clear() }
            scene.performTouchInput { down(config) }
            frames(45)
            scene.performTouchInput { advanceEventTime(720) }
            // Assert the actual AppNavigationBar popup opened, not merely a mock callback.
            val quickLabel = activity!!.get().getString(R.string.navigation_quick_component_manager)
            compose.onNodeWithText(quickLabel).assertExists()
            repeat(12) { step ->
                scene.performTouchInput { moveTo(config + (mary - config) * ((step + 1) / 12f), delayMillis = 16) }
                frames(1)
            }
            scene.performTouchInput { up() }
            frames(90)
            compose.runOnIdle {
                assertEquals("$backend $style rtl=$rtl Config hold→Mary", "mary", route)
                assertEquals(listOf("mary"), selected)
            }
            compose.onNodeWithText(quickLabel).assertDoesNotExist()
        }
    }

    @Test fun heldConfigExpandedIosEdgesReturnToNavigation() {
        for (rtl in listOf(false, true)) for (sign in listOf(-1f, 1f)) {
            launch(AppNavigationBarStyle.IOS, rtl)
            val config = point(R.string.configuration_navigation)
            val mary = point(R.string.mary_title)
            val px = activity!!.get().resources.displayMetrics.density
            val scene = compose.onNodeWithTag("main-host")
            scene.performTouchInput { down(config); up() }
            frames(60)
            compose.runOnIdle { assertEquals("config", route); selected.clear() }
            scene.performTouchInput { down(config + Offset(0f, sign * 24f * px)) }
            frames(45)
            scene.performTouchInput { advanceEventTime(720) }
            val quickLabel = activity!!.get().getString(R.string.navigation_quick_component_manager)
            compose.onNodeWithText(quickLabel).assertExists()
            // Enter the visible pressed lens before crossing the item boundary.
            scene.performTouchInput {
                moveTo(config + (mary - config) * 0.15f + Offset(0f, sign * 35f * px), delayMillis = 32)
            }
            frames(1)
            repeat(12) { step ->
                scene.performTouchInput {
                    moveTo(config + (mary - config) * (0.15f + 0.85f * (step + 1) / 12f) + Offset(0f, sign * 35f * px), delayMillis = 16)
                }
                frames(1)
            }
            scene.performTouchInput { up() }
            frames(90)
            compose.runOnIdle {
                assertEquals("held Config IOS edge sign=$sign rtl=$rtl", "mary", route)
                assertEquals(listOf("mary"), selected)
            }
            compose.onNodeWithText(quickLabel).assertDoesNotExist()
        }
    }

    @Test fun heldIosMenuBottomEdgeOwnsOverlapWithNavigationSlop() {
        for (rtl in listOf(false, true)) {
            launch(AppNavigationBarStyle.IOS, rtl)
            val config = point(R.string.configuration_navigation)
            val mary = point(R.string.mary_title)
            val px = activity!!.get().resources.displayMetrics.density
            val scene = compose.onNodeWithTag("main-host")
            val menuLabel = activity!!.get().getString(R.string.navigation_quick_available_title)
            val cardLabel = activity!!.get().getString(R.string.navigation_quick_card_manager)
            val adapter = compose.onNode(SemanticsMatcher("navigation gesture adapter") {
                it.config.getOrNull(SemanticsActions.CustomActions)?.any { action -> action.label == menuLabel } == true
            }, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            for (start in listOf("center", "upper-edge")) for (path in listOf("menu-edge-release", "menu-then-return", "gap-then-Mary")) {
                scene.performTouchInput { click(config) }
                frames(60)
                compose.runOnIdle { assertEquals("config", route); selected.clear(); quickSelected.clear() }
                val startPoint = if (start == "center") config else Offset(config.x, adapter.top + 2f * px)
                scene.performTouchInput { down(startPoint) }
                frames(45)
                scene.performTouchInput { advanceEventTime(720) }
                val card = compose.onNode(hasClickAction() and hasText(cardLabel)).fetchSemanticsNode()
                val cardOrigin = card.positionOnScreen - scene.fetchSemanticsNode().positionOnScreen
                val actualTarget = Rect(cardOrigin.x, cardOrigin.y,
                    cardOrigin.x + card.size.width, cardOrigin.y + card.size.height)
                // The real 176x40dp target ends 12dp above the bar (10dp gap + 2dp padding).
                // This overlaps IOS's 7dp pressed overflow + 8dp continuation slop.
                assertEquals("Actual menu bottom rtl=$rtl", adapter.top - 12f * px, actualTarget.bottom, 1.1f)
                val y = adapter.top - (if (path == "gap-then-Mary") 11f else 13f) * px
                val enter = Offset(config.x, y)
                val crossTab = Offset(config.x + (mary.x - config.x) * 0.75f, y)
                if (path == "gap-then-Mary") {
                    assertFalse("11dp is the gap, not a menu hit", actualTarget.contains(enter))
                } else {
                    assertTrue("13dp must enter the actual target", actualTarget.contains(enter))
                    assertTrue("The sideways 0.75-tab move remains on the target", actualTarget.contains(crossTab))
                }
                scene.performTouchInput { moveTo(enter, delayMillis = 16) }
                frames(1)
                scene.performTouchInput { moveTo(crossTab, delayMillis = 16) }
                frames(1)
                when (path) {
                    "menu-then-return" -> scene.performTouchInput { moveTo(mary, delayMillis = 16) }
                    "gap-then-Mary" -> scene.performTouchInput { moveTo(Offset(mary.x, y), delayMillis = 16) }
                }
                frames(1)
                compose.runOnIdle { assertEquals("No route before release", "config", route); assertTrue(selected.isEmpty()) }
                scene.performTouchInput { up() }
                fun verify() = compose.runOnIdle {
                    val context = "$start $path rtl=$rtl"
                    assertEquals(context, if (path == "gap-then-Mary") "mary" else "config", route)
                    assertEquals(context, if (path == "gap-then-Mary") listOf("mary") else emptyList<String>(), selected)
                    assertEquals(context,
                        if (path == "menu-edge-release") listOf(NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.CARD))
                        else emptyList<NavigationQuickTarget>(), quickSelected)
                    assertFalse(context, sidebarOpened)
                }
                frames(24)
                verify()
                frames(120)
                verify()
                compose.onNodeWithText(cardLabel).assertDoesNotExist()
            }
        }
    }

    @Test fun heldConfigQuickMenuKeepsUpwardSelectionAndHardCancellation() {
        for (rtl in listOf(false, true)) for ((backend, style) in listOf(
            MixUiBackend.Miuix to AppNavigationBarStyle.IOS,
            MixUiBackend.Miuix to AppNavigationBarStyle.HyperOS,
            MixUiBackend.Mochi to AppNavigationBarStyle.HyperOS,
        )) {
            launch(style, rtl, backend)
            val config = point(R.string.configuration_navigation)
            val mary = point(R.string.mary_title)
            val home = point(R.string.home_navigation)
            val scene = compose.onNodeWithTag("main-host")
            val quickLabel = activity!!.get().getString(R.string.navigation_quick_component_manager)
            scene.performTouchInput { down(config); up() }
            frames(60)
            compose.runOnIdle { selected.clear() }
            fun holdMenu() {
                scene.performTouchInput { down(config) }
                frames(45)
                scene.performTouchInput { advanceEventTime(720) }
                compose.onNodeWithText(quickLabel).assertExists()
            }
            holdMenu()
            val menu = compose.onNodeWithText(quickLabel).fetchSemanticsNode()
            val host = scene.fetchSemanticsNode()
            val menuCenter = menu.positionOnScreen + Offset(menu.size.width / 2f, menu.size.height / 2f) - host.positionOnScreen
            scene.performTouchInput { moveTo(menuCenter, delayMillis = 64) }
            frames(8)
            scene.performTouchInput { up() }
            frames(60)
            compose.runOnIdle { assertEquals("$backend $style rtl=$rtl upward quick selection", 1, quickSelected.size); assertTrue(selected.isEmpty()); assertEquals("config", route) }
            compose.onNodeWithText(quickLabel).assertDoesNotExist()
            for (kind in listOf("system", "multiple", "return-from-menu")) {
                holdMenu()
                when (kind) {
                    "system" -> scene.performTouchInput { cancel() }
                    "multiple" -> scene.performTouchInput { down(1, home); up(1); up(0) }
                    "return-from-menu" -> scene.performTouchInput {
                        moveTo(menuCenter, delayMillis = 64)
                        moveTo(mary, delayMillis = 64)
                        up()
                    }
                }
                frames(60)
                compose.runOnIdle {
                    assertEquals("$kind $backend $style rtl=$rtl", "config", route)
                    assertTrue(selected.isEmpty()); assertEquals(1, quickSelected.size)
                }
                compose.onNodeWithText(quickLabel).assertDoesNotExist()
            }
        }
    }

    private fun exerciseHost() {
        for (rtl in listOf(false, true)) for (style in listOf(AppNavigationBarStyle.IOS, AppNavigationBarStyle.HyperOS)) {
            launch(style, rtl)
            val home = point(R.string.home_navigation)
            val about = point(R.string.about_navigation)
            // Both paced real-time-like motion and coalesced test dispatch, using the actual
            // four-cell app bar. Assert settled route, not just visual indicator position.
            drag(home, about, interleaveFrames = true)
            compose.runOnIdle { assertEquals("$style rtl=$rtl first drag", listOf("about"), selected) }
            drag(about, home, interleaveFrames = false)
            compose.runOnIdle { assertEquals("$style rtl=$rtl reverse drag", listOf("about", "home"), selected) }
            frames(120)
            compose.runOnIdle { assertEquals("home", route); assertEquals(2, selected.size) }
            // Off-center but still within the 64dp capsule, without conflating outside-cancel
            // policy with a valid in-bounds drag. Insets and root offsets come from real bounds.
            val yDelta = (point(R.string.home_navigation).y -
                compose.onNodeWithTag("main-navigation").fetchSemanticsNode().boundsInRoot.top) * 0.5f
            drag(home - Offset(0f, yDelta), about - Offset(0f, yDelta), interleaveFrames = true)
            compose.runOnIdle { assertEquals(listOf("about", "home", "about"), selected); assertEquals("about", route) }
        }
    }
}
