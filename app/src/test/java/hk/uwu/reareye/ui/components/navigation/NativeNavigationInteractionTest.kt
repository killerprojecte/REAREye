package hk.uwu.reareye.ui.components.navigation

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.*
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Real native components and real pointer injection, not a mocked navigation renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeNavigationInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var currentRtl by mutableStateOf(false)
    private var densityScale by mutableStateOf(1f)
    private var fontScale by mutableStateOf(1f)
    private var showNavigation by mutableStateOf(true)
    private var selected by mutableStateOf("home")
    private var currentNavigationStyle by mutableStateOf(NavigationBarStyle.HyperOS)
    private var currentMode by mutableStateOf(NavigationBarMode.Normal)
    private val selections = mutableListOf<String>()
    private val secondary = mutableListOf<String>()
    private val ends = mutableListOf<Boolean>()
    private val edits = mutableListOf<String>()

    @Before fun requireExplicitUiRun() {
        Assume.assumeTrue("Use resource-enabled UI profile and -Dui.capture.enabled=true",
            java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() {
        repeat(24) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }
    private fun host(backend: MixUiBackend, mode: NavigationBarMode, rtl: Boolean, app: Boolean = false, quickActions: List<String> = emptyList(), style: MixMochiStyle = MixMochiStyle.Default, dark: Boolean = false, navigationStyle: NavigationBarStyle = NavigationBarStyle.HyperOS) {
        compose.runOnUiThread {
            activity?.close()
            currentRtl = rtl
            densityScale = 1f
            fontScale = 1f
            showNavigation = true
            currentMode = mode
            currentNavigationStyle = navigationStyle
            selected = "home"; selections.clear(); secondary.clear(); ends.clear(); edits.clear()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend = backend, colorMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, mochiStyle = style) {
                    val originalDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (currentRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalDensity provides Density(originalDensity.density * densityScale, originalDensity.fontScale * fontScale),
                    ) {
                        val backdrop = rememberNavigationBackdrop()
                        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                            Box(Modifier.fillMaxSize().testTag("scene")) {
                                Column(Modifier.fillMaxSize().navigationBackdrop(backdrop)) {
                                    repeat(20) { row ->
                                        Box(Modifier.fillMaxWidth().height(40.dp).background(
                                            if (row % 2 == 0) Color(0xFFCEDEE8) else Color(0xFFF8D5C3)))
                                    }
                                }
                                if (app) {
                                    AppNavigationBar(
                                        modifier = Modifier.align(Alignment.BottomCenter).testTag("navigation"),
                                        currentScreen = selected,
                                        navigationBarMode = ModuleNavigationBarMode.FLOATING_GLASS,
                                        backdrop = backdrop,
                                        // Empty configuration must still offer direct accessible editing.
                                        quickActionIds = quickActions,
                                        onScreenSelected = { selections += it; selected = it },
                                    )
                                } else if (showNavigation) NavigationBar(
                                    modifier = Modifier.align(Alignment.BottomCenter).testTag("navigation"),
                                    items = listOf(
                                        NavigationItem("home", "Home", Icons.Rounded.Cottage),
                                        NavigationItem("config", "Config", Icons.Rounded.Settings,
                                            secondaryActionLabel = "Open Config actions", editActionLabel = "Edit Config actions"),
                                        NavigationItem("about", "About", Icons.Rounded.Info),
                                    ),
                                    selectedKey = selected, onSelect = { selections += it; selected = it },
                                    backdrop = backdrop, mode = currentMode, style = currentNavigationStyle,
                                    onSecondaryAction = { key, _ -> secondary += key },
                                    onSecondaryEnd = { ends += it },
                                    onEditAction = { edits += it },
                                )
                            }
                        }
                    }
                }
            }
        }
        settle()
    }
    private fun cell(label: String) = compose.onNode(hasClickAction() and
        (hasText(label) or hasContentDescription(label)))
    private fun scene() = compose.onNodeWithTag("scene")
    private fun point(label: String) = cell(label).fetchSemanticsNode().boundsInRoot.center
    private fun picture(name: String, node: SemanticsNodeInteraction = compose.onNodeWithTag("navigation")): Bitmap {
        val bitmap = node.captureToImage().asAndroidBitmap()
        val output = System.getProperty("ui.capture.output")
        require(!output.isNullOrBlank()) { "Set ui.capture.output to preserve navigation evidence" }
        val folder = File(output, "native-navigation-v2").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }
    private fun assertRestored(before: Bitmap, after: Bitmap) {
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        val a = IntArray(before.width * before.height); val b = IntArray(a.size)
        before.getPixels(a, 0, before.width, 0, 0, before.width, before.height)
        after.getPixels(b, 0, after.width, 0, 0, after.width, after.height)
        val changed = a.indices.count { i ->
            listOf(0, 8, 16).any { shift -> abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255)) > 24 }
        }
        assertTrue("Native indicator/press state did not visually restore: $changed/${a.size} pixels",
            changed.toDouble() / a.size < 0.015)
    }

    @Test fun horizontalPointerDragCommitsBothDirectionsAndCancelsSafely() {
        for (backend in MixUiBackend.entries) for (rtl in listOf(false, true)) {
            val styles = if (backend == MixUiBackend.Miuix) NavigationBarStyle.entries else listOf(NavigationBarStyle.IOS)
            for (style in styles) {
                host(backend, NavigationBarMode.FloatingGlass, rtl, navigationStyle = style)
                val name = "drag-${backend.name}-${style.name}-$rtl"
                val home = point("Home"); val about = point("About")
                fun drag(from: Offset, to: Offset) {
                    scene().performTouchInput { down(from) }
                    for (step in 1..8) {
                        scene().performTouchInput { moveTo(from + (to - from) * (step / 8f), delayMillis = 16) }
                        compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
                        picture("$name-frame-${selections.size}-$step")
                    }
                    scene().performTouchInput { up() }; settle()
                }
                drag(home, about)
                picture("$name-settled-about")
                compose.runOnIdle { assertEquals(listOf("about"), selections); assertEquals("about", selected); assertTrue(secondary.isEmpty()) }
                drag(about, home)
                picture("$name-settled-home")
                compose.runOnIdle { assertEquals(listOf("about", "home"), selections); assertEquals("home", selected) }
                val before = picture("$name-before-cancel")
                scene().performTouchInput { down(home); moveTo(about, delayMillis = 64); cancel() }; settle()
                compose.runOnIdle { assertEquals(2, selections.size); assertEquals("home", selected) }
                assertRestored(before, picture("$name-after-cancel"))
                scene().performTouchInput { down(home); moveTo(about, delayMillis = 64) }
                compose.runOnIdle { currentMode = NavigationBarMode.Floating }
                settle(); scene().performTouchInput { up() }; settle()
                compose.runOnIdle { assertEquals(2, selections.size); assertEquals("home", selected) }
                picture("$name-after-mode-change")
                if (backend == MixUiBackend.Miuix) {
                    val start = point("Home"); val finish = point("About")
                    scene().performTouchInput { down(start); moveTo(finish, delayMillis = 64) }
                    compose.runOnIdle { currentNavigationStyle = if (style == NavigationBarStyle.IOS) NavigationBarStyle.HyperOS else NavigationBarStyle.IOS }
                    settle(); scene().performTouchInput { up() }; settle()
                    compose.runOnIdle { assertEquals(2, selections.size); assertEquals("home", selected) }
                    picture("$name-after-style-change")
                }
                val tap = point("About")
                scene().performTouchInput { down(tap); up() }; settle()
                compose.runOnIdle { assertEquals(3, selections.size); assertEquals("about", selected) }
                val hold = point("Config")
                scene().performTouchInput { down(hold) }
                compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
                scene().performTouchInput { up() }; settle()
                compose.runOnIdle { assertEquals(3, selections.size); assertEquals(listOf("config"), secondary) }
            }
        }
    }

    @Test fun shortTapLongPressCancelAndIndicatorRestoreAcrossNativeModes() {
        for (backend in MixUiBackend.entries) for (mode in NavigationBarMode.entries) for (rtl in listOf(false, true)) {
            host(backend, mode, rtl)
            val name = "${backend.name}-${mode.name}-${if (rtl) "rtl" else "ltr"}"
            val before = picture("$name-before")
            val config = point("Config")
            scene().performTouchInput { down(config) }
            compose.runOnIdle { assertTrue("Pointer-down must not route", selections.isEmpty()) }
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            compose.runOnIdle { assertEquals(listOf("config"), secondary); assertTrue(selections.isEmpty()) }
            scene().performTouchInput { up() }
            settle()
            compose.runOnIdle { assertEquals("home", selected); assertEquals(listOf(false), ends) }
            assertRestored(before, picture("$name-after-hold"))

            // Before the hold threshold, leave the bar then return. A queued native Down
            // callback must remain cancelled even after returning to the original cell.
            scene().performTouchInput {
                down(config); moveTo(config - Offset(0f, 100f)); moveTo(config); up()
            }
            settle()
            compose.runOnIdle { assertTrue(selections.isEmpty()); assertEquals("home", selected) }
            assertRestored(before, picture("$name-after-drag-cancel"))

            // Cancellation before the hold must suppress the buffered native press.
            scene().performTouchInput { down(config); cancel() }
            settle()
            compose.runOnIdle {
                assertTrue(selections.isEmpty()); assertEquals("home", selected)
                assertEquals(listOf(false), ends)
            }
            assertRestored(before, picture("$name-before-hold-pointer-cancel"))

            scene().performTouchInput { down(config) }
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            scene().performTouchInput { moveTo(config - Offset(0f, 80f)); cancel() }
            settle()
            compose.runOnIdle { assertTrue(selections.isEmpty()); assertEquals("home", selected) }
            compose.runOnIdle { assertEquals(listOf(false, true), ends) }
            assertRestored(before, picture("$name-after-pointer-cancel"))

            // Tap every physical native cell in both RTL and LTR, not just the middle cell.
            for ((label, key) in listOf("About" to "about", "Home" to "home", "Config" to "config")) {
                val target = point(label)
                val count = selections.size
                scene().performTouchInput { down(target) }
                compose.runOnIdle { assertEquals(count, selections.size) }
                scene().performTouchInput { up() }; settle()
                compose.runOnIdle { assertEquals(count + 1, selections.size); assertEquals(key, selected) }
            }
            picture("$name-selected-config")
        }
    }

    @Test fun changingModeDuringPressCannotDispatchOldNativeGesture() {
        for (backend in MixUiBackend.entries) {
            host(backend, NavigationBarMode.Normal, rtl = false)
            val originalConfig = point("Config")
            scene().performTouchInput { down(originalConfig) }
            compose.runOnIdle { assertTrue("$backend down selections: $selections", selections.isEmpty()); currentMode = NavigationBarMode.Floating }
            settle()
            compose.runOnIdle { assertTrue("$backend switched-before-up selections: $selections", selections.isEmpty()) }
            scene().performTouchInput { up() }
            settle()
            compose.runOnIdle { assertTrue("$backend stale selections: $selections", selections.isEmpty()); assertEquals("home", selected) }
            val config = point("Config")
            scene().performTouchInput { down(config); up() }
            settle()
            compose.runOnIdle { assertEquals(listOf("config"), selections) }
        }
    }

    @Test fun removingPressedBarCannotDispatchRetiredNativeGesture() {
        for (backend in MixUiBackend.entries) {
            host(backend, NavigationBarMode.FloatingGlass, false)
            val config = point("Config")
            scene().performTouchInput { down(config) }
            compose.runOnIdle { showNavigation = false }
            settle()
            compose.runOnIdle { assertTrue("$backend removed-before-up: $selections", selections.isEmpty()) }
            scene().performTouchInput { up() }; settle()
            compose.runOnIdle { assertTrue(selections.isEmpty()); showNavigation = true }
            settle()
            val restored = point("Config")
            scene().performTouchInput { down(restored) }
            compose.runOnIdle { assertTrue(selections.isEmpty()) }
            scene().performTouchInput { up() }; settle()
            compose.runOnIdle { assertEquals(listOf("config"), selections) }
        }
    }

    @Test fun geometryChangesDuringPressRetireOnlyTheOldGesture() {
        for (backend in MixUiBackend.entries) for (mode in listOf(NavigationBarMode.Normal, NavigationBarMode.FloatingGlass)) for (change in listOf("direction", "density", "fontScale")) {
            host(backend, mode, false)
            val oldConfig = point("Config")
            scene().performTouchInput { down(oldConfig) }
            compose.runOnIdle {
                assertTrue(selections.isEmpty())
                when (change) {
                    "density" -> densityScale = 1.25f
                    "fontScale" -> fontScale = 1.25f
                    else -> currentRtl = true
                }
            }
            settle()
            compose.runOnIdle { assertTrue("$backend geometry-change callback: $selections", selections.isEmpty()) }
            scene().performTouchInput { up() }; settle()
            compose.runOnIdle { assertTrue(selections.isEmpty()); assertEquals("home", selected) }
            // A retired gate accidentally reused after restart silently disables all taps.
            // Check every physical cell after the change, including both RTL edge cells.
            for ((label, key) in listOf("About" to "about", "Home" to "home", "Config" to "config")) {
                val target = point(label)
                val count = selections.size
                scene().performTouchInput { down(target); up() }; settle()
                compose.runOnIdle { assertEquals(count + 1, selections.size); assertEquals(key, selected) }
            }
        }
    }

    @Test fun nativeResetRejectsOldCallbacksButAllowsNewTapsAndAccessibility() {
        for (backend in MixUiBackend.entries) for (mode in NavigationBarMode.entries) {
            host(backend, mode, false)
            val home = picture("$backend-$mode-reset-home")
            val config = point("Config")
            scene().performTouchInput { down(config); cancel() }; settle()
            compose.runOnIdle { assertTrue(selections.isEmpty()); assertEquals("home", selected) }
            assertRestored(home, picture("$backend-$mode-reset-restored"))
            // Each rejected gesture rebuilds nativeEpoch. Neither an old release callback
            // nor an over-broad identity guard may leak a selection or disable its successor.
            for (key in listOf("config", "home", "config")) {
                val target = point(if (key == "home") "Home" else "Config")
                val count = selections.size
                scene().performTouchInput { down(target); up() }; settle()
                compose.runOnIdle { assertEquals(count + 1, selections.size); assertEquals(key, selected) }
            }
            val count = selections.size
            cell("About").performClick(); settle()
            compose.runOnIdle { assertEquals(count + 1, selections.size); assertEquals("about", selected) }
        }
    }

    @Test fun accessibleAppQuickMenuBackKeepsRoute() {
        for (backend in MixUiBackend.entries) {
            host(backend, NavigationBarMode.FloatingGlass, false, app = true,
                quickActions = listOf(NavigationQuickActionComponentManagerId))
            invokeCustomAction("Available Quick Actions")
            settle()
            compose.onNode(isPopup()).assertExists()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            settle()
            compose.onNode(isPopup()).assertDoesNotExist()
            compose.runOnIdle { assertTrue(selections.isEmpty()); assertEquals("home", selected) }
        }
    }

    @Test fun mochiAppearancesUseNativeGlassAndKeepSelection() {
        for (style in MixMochiStyle.entries) for (dark in listOf(false, true)) {
            host(MixUiBackend.Mochi, NavigationBarMode.FloatingGlass, false, style = style, dark = dark)
            val config = point("Config")
            scene().performTouchInput { down(config); up() }
            settle()
            compose.runOnIdle { assertEquals(listOf("config"), selections); assertEquals("config", selected) }
            val name = "Mochi-${style.name}-${if (dark) "dark" else "light"}-native-glass"
            val quick = picture(name)
            for (label in listOf("Home", "Config")) {
                val target = point(label)
                scene().performTouchInput { down(target) }
                compose.runOnIdle { }
                scene().performTouchInput { up() }; settle()
            }
            assertRestored(quick, picture("$name-after-ordinary-taps"))
        }
    }

    @Test fun captureNativeSelectionMotion() {
        for (backend in MixUiBackend.entries) {
            host(backend, NavigationBarMode.FloatingGlass, false)
            val started = compose.mainClock.currentTime
            val timestamps = mutableListOf<String>()
            var frame = 0
            fun captureFrame() {
                picture("${backend.name}-motion-${frame.toString().padStart(3, '0')}")
                timestamps += "$frame,${compose.mainClock.currentTime - started}"
                frame++
            }
            captureFrame()
            val target = point("Config")
            scene().performTouchInput { down(target) }
            repeat(5) {
                compose.mainClock.advanceTimeBy(40, ignoreFrameDuration = true)
                compose.waitForIdle()
                captureFrame()
            }
            scene().performTouchInput { up() }
            repeat(20) {
                compose.mainClock.advanceTimeBy(40, ignoreFrameDuration = true)
                compose.waitForIdle()
                captureFrame()
            }
            compose.runOnIdle {
                assertEquals(listOf("config"), selections)
                assertEquals("config", selected)
            }
            File(System.getProperty("ui.capture.output"), "native-navigation-v2/${backend.name}-motion-timestamps.csv")
                .writeText("frame,compose_clock_ms\n" + timestamps.joinToString("\n") + "\n")
        }
    }

    private fun invokeCustomAction(label: String) {
        val actions = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { assertTrue(actions.single { it.label == label }.action()) }
    }

    @Test fun accessibilityMenuAndEditHaveDistinctDirectActions() {
        for (backend in MixUiBackend.entries) for (rtl in listOf(false, true)) {
            host(backend, NavigationBarMode.FloatingGlass, rtl)
            invokeCustomAction("Edit Config actions")
            compose.runOnIdle { assertEquals(listOf("config"), edits); assertTrue(secondary.isEmpty()); assertTrue(selections.isEmpty()) }
            invokeCustomAction("Open Config actions")
            compose.runOnIdle { assertEquals(listOf("config"), secondary); assertTrue(selections.isEmpty()) }

            // Exercise the app's real editor, not only the generic callback, with an empty list.
            host(backend, NavigationBarMode.FloatingGlass, rtl, app = true)
            invokeCustomAction("Edit Quick Actions")
            settle()
            compose.onNodeWithText("Edit Quick Actions").assertIsDisplayed()
            compose.runOnIdle { assertTrue(selections.isEmpty()); assertEquals("home", selected) }
            picture("${backend.name}-${rtl}-accessible-editor", compose.onNode(
                isRoot() and hasAnyDescendant(hasText("Edit Quick Actions")), useUnmergedTree = true))
            compose.onNodeWithText("Cancel").performClick(); settle()
            compose.onNodeWithText("Edit Quick Actions").assertDoesNotExist()
        }
    }
}
