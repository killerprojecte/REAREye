package hk.uwu.reareye.ui.components.navigation

import android.app.Application
import android.content.Context
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.HookPrefsEditor
import hk.uwu.reareye.hook.support.XposedRemoteHookPrefs
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.MainActivity
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.theme.AppNavigationBarStyle
import hk.uwu.reareye.ui.theme.AppThemeMode
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/**
 * Launches the actual MainActivity, actual ConfigScreen and actual MaryScreen. Only the
 * external libxposed preference service is replaced with isolated in-memory settings.
 * No synthetic page, simulated registration, or test-owned route setter participates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class,
    qualifiers = "en-rUS-w400dp-h869dp-xxhdpi",
    instrumentedPackages = ["hk.uwu.reareye.hook.support.XposedRemoteHookPrefs"],
    shadows = [MainActivityNavigationInteractionTest.RemotePrefsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityNavigationInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<MainActivity>? = null

    @Before fun requireUiProfile() {
        assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    @After fun close() {
        compose.runOnUiThread { activity?.close() }
        compose.mainClock.autoAdvance = true
    }

    private fun frames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
        }
    }

    private fun launch(style: AppNavigationBarStyle, theme: AppThemeMode) {
        RemotePrefsShadow.backing = GuideMemoryPrefs().apply {
            edit().putInt(ConfigKeys.MODULE_THEME_MODE, theme.value)
                .putInt(ConfigKeys.MODULE_NAVIGATION_BAR_STYLE, style.value).apply()
        }
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("reareye_oobe", Context.MODE_PRIVATE).edit()
            .putBoolean("completed", true).putBoolean("feature_guide_completed", true).commit()
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        }
        // The production startup reads preference readiness on Dispatchers.IO.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.mainClock.advanceTimeBy(16)
            compose.onAllNodes(navMatcher(R.string.configuration_navigation)).fetchSemanticsNodes().isNotEmpty()
        }
        frames(60)
    }

    private fun navMatcher(label: Int): SemanticsMatcher {
        val text = activity!!.get().getString(label)
        return hasClickAction() and (hasText(text) or hasContentDescription(text))
    }

    private fun scene() = compose.onNode(isRoot() and hasAnyDescendant(navMatcher(R.string.home_navigation)))

    private fun point(label: Int): Offset = compose.onNode(navMatcher(label)).fetchSemanticsNode().boundsInRoot.center

    private fun tap(label: Int) {
        val position = point(label)
        scene().performTouchInput { click(position) }
        frames(45)
    }

    private fun drag(from: Offset, to: Offset, holdMs: Long, steps: Int, stepMs: Long) {
        val root = scene()
        root.performTouchInput { down(from) }
        if (holdMs > 0) {
            frames((holdMs / 16).toInt())
            root.performTouchInput { advanceEventTime(holdMs) }
        }
        repeat(steps) { i ->
            root.performTouchInput { moveTo(from + (to - from) * ((i + 1f) / steps), delayMillis = stepMs) }
            frames(maxOf(1, (stepMs / 16).toInt()))
        }
        root.performTouchInput { up() }
    }

    @Test fun realConfigToMaryIosDoesNotRebound() = exercise(AppNavigationBarStyle.IOS, AppThemeMode.MIUIX_LIGHT)
    @Test fun realConfigToMaryHyperOsDoesNotRebound() = exercise(AppNavigationBarStyle.HyperOS, AppThemeMode.MIUIX_LIGHT)
    @Test fun realConfigToMaryMochiDoesNotRebound() = exercise(AppNavigationBarStyle.HyperOS, AppThemeMode.MOCHI_LIGHT)

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl-w400dp-h869dp-xxhdpi")
    fun realConfigToMaryRtlDoesNotRebound() {
        for ((style, theme) in listOf(
            AppNavigationBarStyle.IOS to AppThemeMode.MIUIX_LIGHT,
            AppNavigationBarStyle.HyperOS to AppThemeMode.MIUIX_LIGHT,
            AppNavigationBarStyle.HyperOS to AppThemeMode.MOCHI_LIGHT,
        )) exercise(style, theme, rtl = true)
    }

    private fun exercise(style: AppNavigationBarStyle, theme: AppThemeMode, rtl: Boolean = false) {
        launch(style, theme)
        // Fresh entry, re-entry after real Mary registration/disposal, slow and fast movements,
        // plus the long-press quick menu. Every page switch is delivered as real pointer input.
        for ((holdMs, steps, stepMs) in listOf(
            Triple(0L, 24, 32L), Triple(0L, 4, 16L), Triple(720L, 24, 32L),
            Triple(720L, 4, 16L), Triple(0L, 24, 32L),
        )) {
            tap(R.string.configuration_navigation)
            compose.onNodeWithTag("mary-open-sidebar").assertDoesNotExist()
            val config = point(R.string.configuration_navigation)
            val mary = point(R.string.mary_title)
            assertTrue("Actual Config/Mary hit target order rtl=$rtl", if (rtl) config.x < mary.x else config.x > mary.x)
            drag(config, mary, holdMs, steps, stepMs)
            frames(24)
            compose.onNodeWithTag("mary-open-sidebar").assertIsDisplayed()
            // A later callback or a page-entry modifier restart must not restore Configuration.
            frames(120)
            compose.onNodeWithTag("mary-open-sidebar").assertIsDisplayed()
            // Leave an active real Mary page via a held drag, including its root draggable.
            drag(point(R.string.mary_title), point(R.string.home_navigation), 720, 12, 16)
            frames(120)
            compose.onNodeWithTag("mary-open-sidebar").assertDoesNotExist()
        }
    }

    @Test fun realConfigToMaryIosCurvedPathDiagnostics() =
        exerciseCurvedPaths(AppNavigationBarStyle.IOS, AppThemeMode.MIUIX_LIGHT)
    @Test fun realConfigToMaryHyperOsCurvedPathDiagnostics() =
        exerciseCurvedPaths(AppNavigationBarStyle.HyperOS, AppThemeMode.MIUIX_LIGHT)
    @Test fun realConfigToMaryMochiCurvedPathDiagnostics() =
        exerciseCurvedPaths(AppNavigationBarStyle.HyperOS, AppThemeMode.MOCHI_LIGHT)

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl-w400dp-h869dp-xxhdpi")
    fun realConfigToMaryRtlCurvedPathDiagnostics() {
        for ((style, theme) in listOf(
            AppNavigationBarStyle.IOS to AppThemeMode.MIUIX_LIGHT,
            AppNavigationBarStyle.HyperOS to AppThemeMode.MIUIX_LIGHT,
            AppNavigationBarStyle.HyperOS to AppThemeMode.MOCHI_LIGHT,
        )) exerciseCurvedPaths(style, theme, rtl = true)
    }

    private data class PointerPath(
        val name: String,
        val down: Offset,
        val moves: List<Offset>,
        val holdMs: Long = 0,
        val expectMary: Boolean = true,
        val initialTab: Int = R.string.configuration_navigation,
    )

    /**
     * Deliberately small counterexamples, not a claim about unrecorded user coordinates:
     *  - the same in-bounds curve with/without a hold isolates the gate's first-direction lock;
     *  - small edge excursions may continue or re-enter, before and after a held-menu handoff;
     *  - exits beyond the continuation slop must remain cancelled after returning to Mary.
     */
    private fun exerciseCurvedPaths(style: AppNavigationBarStyle, theme: AppThemeMode, rtl: Boolean = false) {
        launch(style, theme)
        tap(R.string.configuration_navigation)
        val config = point(R.string.configuration_navigation)
        val mary = point(R.string.mary_title)
        assertTrue("Config→Mary actual physical order rtl=$rtl", if (rtl) config.x < mary.x else config.x > mary.x)
        val towardMarySign = if (rtl) 1f else -1f
        val px = activity!!.get().resources.displayMetrics.density
        val slop = ViewConfiguration.get(activity!!.get()).scaledTouchSlop.toFloat()
        // This semantic action belongs to NavigationBar's actual outer gesture adapter,
        // rather than an icon, scaled pressed indicator, or its window-inset container.
        val actionLabel = activity!!.get().getString(R.string.navigation_quick_available_title)
        val adapter = compose.onNode(SemanticsMatcher("navigation gesture adapter") {
            it.config.getOrNull(SemanticsActions.CustomActions)?.any { action -> action.label == actionLabel } == true
        }, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val overflow = if (theme == AppThemeMode.MIUIX_LIGHT && style == AppNavigationBarStyle.IOS) 7f * px else 0f
        val continuation = Rect(adapter.left - slop, adapter.top - overflow - slop,
            adapter.right + slop, adapter.bottom + overflow + slop)
        val towardMary = (mary - config)
        fun line(from: Offset, to: Offset, count: Int = 12): List<Offset> =
            (1..count).map { from + (to - from) * (it.toFloat() / count) }
        // Use the measured platform slop, not an assumed dp value. The entire lead stays inside.
        val initialVertical = config + Offset(towardMarySign * 0.55f * slop, 1.1f * slop)
        val horizontalArc = listOf(
            config + Offset(towardMarySign * 1.5f * slop, 0.55f * slop),
            config + towardMary * 0.5f + Offset(0f, 1.1f * slop),
        ) + line(config + towardMary * 0.5f + Offset(0f, 1.1f * slop), mary)
        val verticalArc = listOf(initialVertical) + line(initialVertical, mary)
        val lowConfig = Offset(config.x, adapter.bottom - 2f * px)
        val lowMary = Offset(mary.x, adapter.bottom - px)
        val edgeLine = line(lowConfig, lowMary)
        val paths = listOf(
            PointerPath("horizontal-first-small-arc", config, horizontalArc),
            PointerPath("vertical-first-small-arc", config, verticalArc),
            PointerPath("same-vertical-arc-after-hold", config, verticalArc, holdMs = 720),
            // Begin on Home, so an accidental Config click is externally observable.
            PointerPath("vertical-only-does-not-click-Config", config,
                listOf(config + Offset(0f, 1.1f * slop)),
                expectMary = false, initialTab = R.string.home_navigation),
            PointerPath("vertical-return-does-not-click-Config", config,
                listOf(initialVertical, config),
                expectMary = false, initialTab = R.string.home_navigation),
            PointerPath("held-lower-edge-inside", lowConfig, edgeLine, holdMs = 720),
            PointerPath("held-lower-edge-1px-out-after-Mary", lowConfig,
                edgeLine + listOf(Offset(mary.x, adapter.bottom + overflow + 1f), lowMary),
                holdMs = 720),
        ) + buildList {
            for (hold in listOf(0L, 720L)) {
                // Leave the vertical edge before entering Mary's cell. The held variant must
                // relinquish the actual quick menu despite the small initial overshoot.
                for ((edge, positions) in listOf(
                    // IOS menu ends 12dp above the bar. At platform slop 16dp,
                    // overflow + half-slop is 15dp and actually enters that menu.
                    // Keep this navigation positive in the verified 11dp gap; the
                    // production-popup suite separately requires menu ownership at 13dp.
                    "upper" to Triple(adapter.top + 2f * px, adapter.top -
                        (if (hold > 0 && overflow > 0) minOf(overflow + 0.5f * slop, 11f * px)
                         else overflow + 0.5f * slop),
                        adapter.top - overflow - 1.25f * slop),
                    "lower" to Triple(adapter.bottom - 2f * px, adapter.bottom + overflow + 0.5f * slop,
                        adapter.bottom + overflow + 1.25f * slop),
                )) {
                    val (innerY, smallY, largeY) = positions
                    val from = Offset(config.x, innerY)
                    val near = Offset(config.x, smallY)
                    val end = Offset(mary.x, smallY)
                    add(PointerPath("$edge-small-exit-reenter-hold-$hold", from,
                        listOf(near, from) + line(from, Offset(mary.x, innerY)), holdMs = hold))
                    add(PointerPath("$edge-small-exit-continue-release-hold-$hold", from,
                        listOf(near) + line(near, end), holdMs = hold))
                    // First become Drag, then leave. Otherwise Secondary legitimately owns
                    // an upward departure and the test would exercise a menu, not cancellation.
                    add(PointerPath("$edge-large-exit-return-hold-$hold", from,
                        line(from, mary) + listOf(Offset(mary.x, largeY), mary),
                        holdMs = hold, expectMary = false))
                }
                for ((edge, smallX, largeX) in listOf(
                    Triple("left", adapter.left - 0.5f * slop, adapter.left - 1.25f * slop),
                    Triple("right", adapter.right + 0.5f * slop, adapter.right + 1.25f * slop),
                )) {
                    add(PointerPath("$edge-small-exit-return-hold-$hold", config,
                        line(config, mary) + listOf(Offset(smallX, mary.y), mary), holdMs = hold))
                    add(PointerPath("$edge-large-exit-return-hold-$hold", config,
                        line(config, mary) + listOf(Offset(largeX, mary.y), mary),
                        holdMs = hold, expectMary = false))
                }
            }
        }
        val report = mutableListOf(
            "Actual MainActivity; style=$style theme=$theme rtl=$rtl; density=$px touchSlopPx=$slop",
            "Actual gesture-adapter bounds=$adapter; pressed overflowPx=$overflow; continuation=$continuation",
            "Actual Config center=$config; Mary center=$mary",
            "This is a constructed input diagnostic, not evidence of the user's exact path.",
        )
        val failures = mutableListOf<String>()
        fun page(): String = when {
            compose.onAllNodesWithTag("mary-open-sidebar").fetchSemanticsNodes().isNotEmpty() -> "mary"
            compose.onAllNodesWithText(activity!!.get().getString(R.string.config_tab_components)).fetchSemanticsNodes().isNotEmpty() -> "config"
            compose.onAllNodesWithTag("home-quick-actions").fetchSemanticsNodes().isNotEmpty() -> "home"
            else -> "other"
        }
        for (path in paths) {
            tap(path.initialTab)
            val initialPage = if (path.initialTab == R.string.home_navigation) "home" else "config"
            check(page() == initialPage) { "Diagnostic did not begin on the real $initialPage page" }
            check(adapter.contains(path.down)) { "Continuation test must begin inside the actual bar: ${path.name}" }
            if (path.expectMary) {
                check(path.moves.all { continuation.contains(it) }) {
                    "An intended tolerated path left the continuation bounds: ${path.name}"
                }
            }
            if ("small-exit" in path.name) check(path.moves.any {
                it.x < adapter.left || it.x > adapter.right ||
                    it.y < adapter.top - overflow || it.y > adapter.bottom + overflow
            }) { "Small-exit case did not leave the old continuation bounds: ${path.name}" }
            if ("large-exit" in path.name) check(path.moves.any { !continuation.contains(it) }) {
                "Large-exit case did not exceed the new continuation bounds: ${path.name}"
            }
            val root = scene()
            root.performTouchInput { down(path.down) }
            if (path.holdMs > 0) {
                frames((path.holdMs / 16).toInt())
                root.performTouchInput { advanceEventTime(path.holdMs) }
                compose.onNodeWithText(activity!!.get().getString(R.string.navigation_quick_component_manager)).assertExists()
            }
            for (move in path.moves) {
                root.performTouchInput { moveTo(move, delayMillis = 16) }
                frames(1)
            }
            val beforeUp = page()
            root.performTouchInput { up() }
            frames(24)
            val afterRelease = page()
            frames(120)
            val afterSettle = page()
            val expected = if (path.expectMary) "mary" else initialPage
            val menuVisible = compose.onAllNodesWithText(activity!!.get().getString(R.string.navigation_quick_component_manager))
                .fetchSemanticsNodes().isNotEmpty()
            val sidebarVisible = compose.onAllNodesWithTag("mary-sidebar-overlay").fetchSemanticsNodes().isNotEmpty()
            val passed = beforeUp == initialPage && afterRelease == expected && afterSettle == expected &&
                !menuVisible && !sidebarVisible
            val result = "${path.name}: expected=$expected beforeUp=$beforeUp after384ms=$afterRelease after2304ms=$afterSettle menu=$menuVisible sidebar=$sidebarVisible pass=$passed"
            report += result
            report += "  initialPage=$initialPage; holdMs=${path.holdMs}; down=${path.down}; moves=${path.moves}"
            if (!passed) failures += result
        }
        val output = File(System.getProperty("ui.capture.output"), "main-activity-navigation-paths")
        output.mkdirs()
        val evidence = File(output, "${theme.name}-${style.name}-rtl-$rtl.txt")
        evidence.writeText(report.joinToString("\n") + "\n")
        assertTrue("Constructed pointer-path failures; evidence=${evidence.absolutePath}\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test fun realMaryNavigationDoesNotOpenSidebarAcrossRenderers() = exerciseMaryOwnership(rtl = false)

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl-w400dp-h869dp-xxhdpi")
    fun realMaryNavigationDoesNotOpenSidebarInRtlAcrossRenderers() = exerciseMaryOwnership(rtl = true)

    /** Separates native navigation from the real Mary's full-page opening recognizers. */
    private fun exerciseMaryOwnership(rtl: Boolean) {
        val failures = mutableListOf<String>()
        for ((style, theme) in listOf(
            AppNavigationBarStyle.IOS to AppThemeMode.MIUIX_LIGHT,
            AppNavigationBarStyle.HyperOS to AppThemeMode.MIUIX_LIGHT,
            AppNavigationBarStyle.HyperOS to AppThemeMode.MOCHI_LIGHT,
        )) {
            launch(style, theme)
            tap(R.string.mary_title)
            compose.onNodeWithTag("mary-open-sidebar").assertIsDisplayed()
            val mary = point(R.string.mary_title)
            val config = point(R.string.configuration_navigation)
            assertTrue("Mary→Config actual physical order rtl=$rtl", if (rtl) config.x < mary.x else config.x > mary.x)
            val px = activity!!.get().resources.displayMetrics.density
            val actionLabel = activity!!.get().getString(R.string.navigation_quick_available_title)
            val adapter = compose.onNode(SemanticsMatcher("navigation gesture adapter") {
                it.config.getOrNull(SemanticsActions.CustomActions)?.any { action -> action.label == actionLabel } == true
            }, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val report = mutableListOf("Actual MainActivity / real MaryScreen; $theme $style rtl=$rtl",
                "Adapter=$adapter; Mary=$mary; Config=$config")
            fun sidebarVisible() = compose.onAllNodesWithTag("mary-sidebar-overlay").fetchSemanticsNodes().isNotEmpty()
            fun maryVisible() = compose.onAllNodesWithTag("mary-open-sidebar").fetchSemanticsNodes().isNotEmpty()
            fun configVisible() = compose.onAllNodesWithText(activity!!.get().getString(R.string.config_tab_components)).fetchSemanticsNodes().isNotEmpty()
            fun record(name: String, expected: String, passed: Boolean) {
                val line = "$name: expected=$expected Mary=${maryVisible()} Config=${configVisible()} sidebar=${sidebarVisible()} pass=$passed"
                report += line
                if (!passed) failures += "$theme $style rtl=$rtl $line"
            }
            fun closeSidebar() {
                if (sidebarVisible()) {
                    compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
                    frames(48)
                    check(!sidebarVisible()) { "Real sidebar Back dismissal failed; cannot continue safely" }
                }
            }
            fun prepareMary() {
                closeSidebar()
                tap(R.string.mary_title)
                compose.onNodeWithTag("mary-open-sidebar").assertIsDisplayed()
            }
            // Control: the exact production page opener remains functional away from the bar.
            val bounds = scene().fetchSemanticsNode().boundsInRoot
            val fromBody = Offset(bounds.left + bounds.width * (if (rtl) 0.7f else 0.3f), bounds.top + bounds.height * 0.4f)
            val toBody = fromBody + Offset(bounds.width * (if (rtl) -0.35f else 0.35f), 0f)
            drag(fromBody, toBody, holdMs = 0, steps = 12, stepMs = 16)
            frames(48)
            record("content-opens-sidebar", "Mary sidebar", sidebarVisible() && maryVisible() && !configVisible())
            closeSidebar()
            // Center and both straight inner edges are all real bar hits, not page gestures.
            for ((name, y) in listOf("center" to mary.y, "upper-inner-edge" to adapter.top + 2f * px,
                "lower-inner-edge" to adapter.bottom - 2f * px)) {
                prepareMary()
                drag(Offset(mary.x, y), Offset(config.x, y), holdMs = 0, steps = 12, stepMs = 16)
                frames(24)
                val initiallySelected = configVisible() && !sidebarVisible()
                frames(120)
                record("navigation-$name", "Config, no sidebar", initiallySelected && configVisible() && !sidebarVisible() && !maryVisible())
            }
            // Exercise the opposite route with the real Mary's opening recognizer mounted.
            // The small excursion must navigate; a real departure must not recover navigation or open the sidebar.
            val slop = ViewConfiguration.get(activity!!.get()).scaledTouchSlop.toFloat()
            val overflow = if (theme == AppThemeMode.MIUIX_LIGHT && style == AppNavigationBarStyle.IOS) 7f * px else 0f
            for ((edge, sign) in listOf("upper" to -1f, "lower" to 1f)) for (factor in listOf(0.5f, 1.25f)) {
                prepareMary()
                val edgeY = if (sign < 0) adapter.top else adapter.bottom
                val innerY = edgeY - sign * 2f * px
                val from = Offset(mary.x, innerY)
                val target = Offset(config.x, innerY)
                val root = scene()
                root.performTouchInput { down(from) }
                repeat(12) { step ->
                    root.performTouchInput { moveTo(from + (target - from) * ((step + 1) / 12f), delayMillis = 16) }
                    frames(1)
                }
                root.performTouchInput { moveTo(Offset(config.x, edgeY + sign * (overflow + factor * slop)), delayMillis = 16) }
                frames(1)
                root.performTouchInput { moveTo(target, delayMillis = 16) }
                frames(1)
                val deferred = maryVisible() && !configVisible() && !sidebarVisible()
                root.performTouchInput { up() }
                frames(24)
                fun expectedPage() = if (factor < 1f) configVisible() && !maryVisible() else maryVisible() && !configVisible()
                val initiallyCorrect = expectedPage() && !sidebarVisible()
                frames(120)
                record("navigation-$edge-exit-$factor-slop", if (factor < 1f) "Config, no sidebar" else "Mary, no sidebar",
                    deferred && initiallyCorrect && expectedPage() && !sidebarVisible())
            }
            // Same center path, but after a hold: exposes the existing direction/timeout priority.
            prepareMary()
            drag(mary, config, holdMs = 720, steps = 12, stepMs = 16)
            frames(144)
            record("navigation-center-held", "Config, no sidebar", configVisible() && !sidebarVisible() && !maryVisible())
            prepareMary()
            val root = scene()
            root.performTouchInput { down(mary) }
            repeat(8) { i ->
                root.performTouchInput { moveTo(mary + (config - mary) * ((i + 1f) / 8), delayMillis = 16) }
                frames(1)
            }
            root.performTouchInput { cancel() }
            frames(144)
            record("navigation-system-cancel", "Mary, no sidebar", maryVisible() && !configVisible() && !sidebarVisible())
            closeSidebar()
            val output = File(System.getProperty("ui.capture.output"), "main-activity-mary-navigation").apply { mkdirs() }
            File(output, "${theme.name}-${style.name}-rtl-$rtl.txt").writeText(report.joinToString("\n") + "\n")
        }
        assertTrue("Mary/navigation ownership failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    /** No network, binder, root service or real user preferences are used by this suite. */
    @Implements(value = XposedRemoteHookPrefs::class, isInAndroidSdk = false)
    class RemotePrefsShadow {
        companion object { @JvmField var backing: HookPrefs = GuideMemoryPrefs() }
        @Implementation fun isRemoteReady(): Boolean = true
        @Implementation fun getString(key: String, defaultValue: String): String = backing.getString(key, defaultValue)
        @Implementation fun getStringSet(key: String, defaultValue: Set<String>): Set<String> = backing.getStringSet(key, defaultValue)
        @Implementation fun getInt(key: String, defaultValue: Int): Int = backing.getInt(key, defaultValue)
        @Implementation fun getLong(key: String, defaultValue: Long): Long = backing.getLong(key, defaultValue)
        @Implementation fun getFloat(key: String, defaultValue: Float): Float = backing.getFloat(key, defaultValue)
        @Implementation fun getBoolean(key: String, defaultValue: Boolean): Boolean = backing.getBoolean(key, defaultValue)
        @Implementation fun contains(key: String): Boolean = backing.contains(key)
        @Implementation fun all(): Map<String, Any?> = backing.all()
        @Implementation fun edit(): HookPrefsEditor = backing.edit()
    }
}
