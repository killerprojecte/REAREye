package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.navigation.NavigationQuickAction
import hk.uwu.reareye.ui.components.navigation.NavigationQuickActionEditor
import hk.uwu.reareye.ui.components.navigation.NavigationQuickTarget
import hk.uwu.reareye.ui.config.ConfigType
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

/** Real editor/host/pointer integration, not device gesture or predictive Back coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationQuickEditorSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var fullStyleFolder: File? = null
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var selected by mutableStateOf(listOf("a", "b"))
    private val saved = mutableListOf<List<String>>()
    private var dismissals = 0
    private val actions = listOf("a", "b", "c").map {
        NavigationQuickAction(it, "Action $it", Icons.Rounded.Settings,
            NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.BUSINESS))
    }
    @Before fun resources() { assumeTrue(java.lang.Boolean.getBoolean("mary.ui.resources")) }
    @After fun close() { compose.runOnUiThread { activity?.close() } }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun action(label: Int) = compose.onNodeWithContentDescription(activity!!.get().getString(label))
    private val actionLabels = listOf(R.string.navigation_quick_cancel, R.string.navigation_quick_save)
    private fun settle() { compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle() }
    private fun open(backend: MixUiBackend, dark: Boolean,
        colorMode: MixColorSchemeMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light,
        mochiStyle: MixMochiStyle = MixMochiStyle.Default,
        actionOptions: List<NavigationQuickAction> = actions) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, colorMode, mochiStyle) {
                    Scaffold {
                        GlassBottomSheetScene {
                            Box(Modifier.fillMaxSize().background(Color(0xFF246899)))
                            NavigationQuickActionEditor(visible, selected, actionOptions,
                                { dismissals++; visible = false }, {
                                    saved += it
                                    selected = it
                                    visible = false
                                })
                        }
                    }
                }
            }
        }
        settle()
    }
    private fun capture(name: String) {
        val directory = (fullStyleFolder ?: File(System.getProperty("ui.capture.output"), "navigation-quick-editor")).apply { mkdirs() }
        compose.runOnUiThread {
            val bitmap = captureWindowBitmap(activity!!.get().window)
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    private fun reopen() { compose.runOnIdle { visible = true }; settle() }
    private fun save() { action(R.string.navigation_quick_save).assertIsDisplayed().performClick(); settle() }
    private fun drag(cancelGesture: Boolean = false) {
        val first = node("quick-editor-drag-a")
        val from = first.fetchSemanticsNode().boundsInRoot
        val to = node("quick-editor-drag-b").fetchSemanticsNode().boundsInRoot
        first.performTouchInput {
            down(center)
            moveTo(Offset(centerX, centerY + to.center.y - from.center.y + 12f), 500)
            if (cancelGesture) cancel() else up()
        }
        settle()
    }
    private fun exercise(backend: MixUiBackend, dark: Boolean,
        colorMode: MixColorSchemeMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light,
        mochiStyle: MixMochiStyle = MixMochiStyle.Default) {
        open(backend, dark, colorMode, mochiStyle)
        val sheet = node(if (backend == MixUiBackend.Mochi) "mochi-glass-sheet" else "mixui-native-glass-sheet")
        val restingTop = sheet.fetchSemanticsNode().boundsInRoot.top
        capture("${backend.name}-${if (dark) "dark" else "light"}")
        drag(cancelGesture = true)
        assertEquals("Cancelled reorder cannot dismiss", 0, dismissals)
        save()
        assertEquals(listOf(listOf("a", "b")), saved)
        reopen()
        drag()
        assertEquals("Reorder never transfers to sheet drag", restingTop,
            sheet.fetchSemanticsNode().boundsInRoot.top, 1f)
        action(R.string.navigation_quick_cancel).assertIsDisplayed().performClick()
        settle()
        assertEquals(1, saved.size)
        reopen()
        save()
        assertEquals("Cancel discards draft order", listOf("a", "b"), saved.last())
        reopen()
        drag()
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
        node("quick-editor-body").assertDoesNotExist()
        reopen()
        save()
        assertEquals("Back discards draft order", listOf("a", "b"), saved.last())
        reopen()
        drag()
        save()
        assertEquals("Save commits reordered draft once", listOf("b", "a"), saved.last())
        assertEquals(4, saved.size)
        reopen()
        save()
        assertEquals("Reopen loads committed order", listOf("b", "a"), saved.last())
        assertEquals(5, saved.size)
    }
    @Test fun renderFullStyleQuickEditorMatrix() = majorSheetStyleMatrix(
        directory = "full-style-quick-editor", language = "en", fixture = "NavigationQuickActionEditor production sheet over solid blue fixture background; three synthetic actions; not a full application page",
        close = { compose.runOnUiThread { activity?.close(); activity = null } },
    ) { _, _, backend, dark, colors, style, folder ->
        compose.runOnIdle { visible = true; selected = listOf("a", "b"); saved.clear(); dismissals = 0 }
        fullStyleFolder = folder
        try { exercise(backend, dark, colors, style) } finally { fullStyleFolder = null }
    }

    private fun assertHeaderActions() {
        val bodyTop = node("quick-editor-body").fetchSemanticsNode().boundsInRoot.top
        actionLabels.forEach { label ->
            val control = action(label)
            control.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                .assert(hasAnyAncestor(hasTestTag("quick-editor-body")).not())
            assertTrue("Native header action must be above, not inside, the scroll body",
                control.fetchSemanticsNode().boundsInRoot.bottom <= bodyTop + 1f)
        }
    }

    private fun assertMiuixActionMaterial(scrolled: Boolean) {
        val expected = if (scrolled) "mixui-sheet-glass-action" else "mixui-sheet-plain-action"
        actionLabels.forEach { label ->
            // Caller and mixui testTag modifiers share one semantics node; the caller
            // tag may win the merge. Inspect the actual native modifier, never add a
            // synthetic material tag to the editor just to satisfy this assertion.
            val info = action(label).fetchSemanticsNode().layoutInfo
            compose.runOnIdle {
                val tags = info.getModifierInfo().mapNotNull { entry ->
                    val element = entry.modifier
                    if (element.javaClass.name != "androidx.compose.ui.platform.TestTagElement") null
                    else element.javaClass.getDeclaredField("tag").apply { isAccessible = true }
                        .get(element) as String
                }
                assertTrue("Native action must expose real $expected; found $tags", expected in tags)
            }
        }
    }

    private fun fixedHeaderActions(backend: MixUiBackend, dark: Boolean) {
        val longActions = actions + (1..24).map { index ->
            NavigationQuickAction("extra-$index", "Extra action $index", Icons.Rounded.Settings,
                NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.BUSINESS))
        }
        open(backend, dark, actionOptions = longActions)
        val sheetTag = if (backend == MixUiBackend.Mochi) "mochi-glass-sheet" else "mixui-native-glass-sheet"
        node(sheetTag).assertIsDisplayed()
        assertHeaderActions()
        if (backend == MixUiBackend.Miuix) assertMiuixActionMaterial(scrolled = false)
        val before = actionLabels.map { action(it).fetchSemanticsNode().boundsInRoot }
        val body = node("quick-editor-body")
        val range = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Long editor body must actually overflow", range.maxValue() > 0f)
        assertEquals(0f, range.value(), 0f)
        val folder = File(requireNotNull(System.getProperty("ui.capture.output")),
            "sheet-glass-controls-3a980f7c/QUICK-EDITOR")
        val prefix = "Robolectric-${backend.name}-${if (dark) "dark" else "light"}"
        fullStyleFolder = folder
        try {
            capture("$prefix-at-top")
            body.performTouchInput { swipeUp() }
            settle()
            assertTrue("Body must move independently of header", range.value() > 0f)
            assertHeaderActions()
            actionLabels.forEachIndexed { index, label ->
                val after = action(label).fetchSemanticsNode().boundsInRoot
                assertEquals("Header action x is fixed while body scrolls", before[index].left, after.left, 1f)
                assertEquals("Header action y is fixed while body scrolls", before[index].top, after.top, 1f)
                assertEquals(before[index].bottom, after.bottom, 1f)
            }
            if (backend == MixUiBackend.Miuix) assertMiuixActionMaterial(scrolled = true)
            else compose.onAllNodesWithTag("mixui-sheet-glass-action", useUnmergedTree = true).assertCountEquals(0)
            capture("$prefix-scrolled")
            body.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, -10000f)) }
            settle()
            assertEquals(0f, range.value(), 0f)
            if (backend == MixUiBackend.Miuix) assertMiuixActionMaterial(scrolled = false)
            assertHeaderActions()
            capture("$prefix-restored")
            val manifest = org.json.JSONObject()
                .put("capture", "Native Robolectric production component fixture; not a physical-device screenshot")
                .put("backend", backend.name).put("dark", dark)
                .put("fixture", "Quick editor native fixed header over a long scroll body; synthetic actions")
                .put("images", org.json.JSONArray(listOf("$prefix-at-top.png", "$prefix-scrolled.png", "$prefix-restored.png")))
            File(folder, "$prefix-manifest.json").writeText(manifest.toString(2) + "\n")
        } finally {
            fullStyleFolder = null
        }
        save()
        assertEquals("Header save still commits once", listOf(listOf("a", "b")), saved)
    }

    @Test @Config(qualifiers = "en-w400dp-h400dp-mdpi")
    fun miuixLightFixedHeaderActionsFollowRealScroll() = fixedHeaderActions(MixUiBackend.Miuix, false)
    @Test @Config(qualifiers = "en-w400dp-h400dp-mdpi")
    fun miuixDarkFixedHeaderActionsFollowRealScroll() = fixedHeaderActions(MixUiBackend.Miuix, true)
    @Test @Config(qualifiers = "en-w400dp-h400dp-mdpi")
    fun mochiFixedHeaderActionsStayNative() = fixedHeaderActions(MixUiBackend.Mochi, false)

    private fun headerSaveDoesNotCommitDuringExit(backend: MixUiBackend) {
        open(backend, false)
        action(R.string.navigation_quick_save).performTouchInput { down(center); cancel() }
        settle()
        assertTrue("Canceled native action pointer must not save", saved.isEmpty())
        assertTrue(visible)

        fun assertExitingActions(expectedSaves: Int) {
            // Raw accessibility activation first updates the caller, then the
            // portaled sheet entry. Flush both while retaining the exit animation.
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            assertFalse(visible)
            actionLabels.forEach { label ->
                // MIUIX removes OnClick entirely when disabled; Mochi retains
                // a rejecting semantics action. Both must expose Disabled.
                val click = action(label).assertIsNotEnabled().fetchSemanticsNode()
                    .config.getOrNull(SemanticsActions.OnClick)?.action
                if (click != null) compose.runOnUiThread {
                    assertFalse("Disabled exit action must reject semantics activation", click())
                }
            }
            assertEquals("Disabled exiting header cannot commit twice", expectedSaves, saved.size)
            assertEquals("Disabled exiting close cannot dismiss again", 0, dismissals)
        }

        // Mochi's native pressed animation runs until release. Freeze before
        // DOWN; waiting for automatic idle while holding it can never complete.
        compose.mainClock.autoAdvance = false
        var pointerHeld = false
        try {
            action(R.string.navigation_quick_save).performTouchInput { down(center) }
            pointerHeld = true
            compose.mainClock.advanceTimeBy(1_000)
            compose.runOnUiThread {
                assertTrue("Held native action pointer must not save", saved.isEmpty())
                assertTrue(visible)
            }
            action(R.string.navigation_quick_save).performTouchInput { up() }
            pointerHeld = false
            assertExitingActions(expectedSaves = 1)
            assertEquals(listOf(listOf("a", "b")), saved)
        } finally {
            if (pointerHeld) action(R.string.navigation_quick_save).performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
            settle()
        }
        node("quick-editor-body").assertDoesNotExist()
        reopen()

        // Capture while attached, before the first click. Both callbacks run in
        // one UI turn, before submitted/show can recompose or replace the node.
        val saveClick = requireNotNull(action(R.string.navigation_quick_save)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { saveClick(); saveClick() }
            assertEquals("Same-frame native Save activation commits once", 2, saved.size)
            assertExitingActions(expectedSaves = 2)
        } finally {
            compose.mainClock.autoAdvance = true
            settle()
        }
        node("quick-editor-body").assertDoesNotExist()
        reopen()
        save()
        assertEquals("Reopened editor can save exactly once again", 3, saved.size)
    }

    @Test fun miuixHeaderSaveIsDisabledDuringExit() = headerSaveDoesNotCommitDuringExit(MixUiBackend.Miuix)
    @Test fun mochiHeaderSaveIsDisabledDuringExit() = headerSaveDoesNotCommitDuringExit(MixUiBackend.Mochi)

    @Test fun miuixLight() = exercise(MixUiBackend.Miuix, false)
    @Test fun miuixDark() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiLight() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiDark() = exercise(MixUiBackend.Mochi, true)

    private fun shortInsets(backend: MixUiBackend) {
        open(backend, false)
        fun applyInset(bottom: Int) {
            compose.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), bottom > 0).build()
                ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView, insets)
            }
            settle()
        }
        applyInset(0)
        assertHeaderActions()
        val before = node("quick-editor-body").fetchSemanticsNode().boundsInRoot.bottom
        applyInset(48)
        assertHeaderActions()
        val after = node("quick-editor-body").fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("Bottom inset is reserved once, not caller plus host", 48f, before - after, 2f)
        capture("${backend.name}-short-navigation-inset")
        save()
        assertEquals(listOf(listOf("a", "b")), saved)
    }
    @Test @Config(qualifiers = "w400dp-h400dp-mdpi")
    fun miuixShortViewportInsets() = shortInsets(MixUiBackend.Miuix)
    @Test @Config(qualifiers = "w400dp-h400dp-mdpi")
    fun mochiShortViewportInsets() = shortInsets(MixUiBackend.Mochi)
}

/** Shared only by these three opt-in component-fixture galleries. */
internal fun majorSheetStyleMatrix(
    directory: String,
    language: String,
    fixture: String,
    close: () -> Unit,
    render: (String, String, MixUiBackend, Boolean, MixColorSchemeMode, MixMochiStyle, File) -> Unit,
) {
    assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
    val requested = System.getProperty("ui.capture.variants", "").trim()
    assumeTrue("Opt in with ui.capture.variants", requested.isNotEmpty())
    fun selection(raw: String, allowed: Set<String>): List<String> {
        val values = raw.split(',').map(String::trim)
        require(values.all { it in allowed } && values.distinct().size == values.size) {
            "Invalid selection '$raw'; allowed: $allowed"
        }
        return values
    }
    val variants = selection(requested,
        setOf("miuix-default", "miuix-monet", "mochi-default", "mochi-ios", "mochi-nothing"))
    val modes = selection(System.getProperty("ui.capture.modes", "light,dark"),
        setOf("light", "dark", "system-day", "system-night"))
    val output = requireNotNull(System.getProperty("ui.capture.output"))
    val originalQualifiers = org.robolectric.RuntimeEnvironment.getQualifiers()
    try {
        for (variant in variants) for (mode in modes) {
            close()
            val systemNight = mode == "system-night"
            val dark = mode == "dark" || systemNight
            val system = mode.startsWith("system-")
            org.robolectric.RuntimeEnvironment.setQualifiers(
                "$language-w400dp-h800dp-${if (systemNight) "night" else "notnight"}-mdpi")
            val backend = if (variant.startsWith("mochi-")) MixUiBackend.Mochi else MixUiBackend.Miuix
            val colors = if (variant == "miuix-monet") {
                if (system) MixColorSchemeMode.MonetSystem else if (dark) MixColorSchemeMode.MonetDark else MixColorSchemeMode.MonetLight
            } else {
                if (system) MixColorSchemeMode.System else if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light
            }
            val style = when (variant) {
                "mochi-ios" -> MixMochiStyle.Ios
                "mochi-nothing" -> MixMochiStyle.Nothing
                else -> MixMochiStyle.Default
            }
            val folder = File(output, "$directory/$variant/$mode").apply { check(mkdirs() || isDirectory) }
            fun clearTarget() {
                folder.listFiles()?.filter { it.extension == "png" || it.name == "manifest.json" }
                    ?.forEach { check(it.delete()) { "Cannot remove stale capture: $it" } }
            }
            clearTarget()
            try {
                render(variant, mode, backend, dark, colors, style, folder)
                val actualNight = org.robolectric.RuntimeEnvironment.getApplication().resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                check(actualNight == systemNight) { "System night configuration did not match $mode" }
                val images = folder.listFiles().orEmpty().filter { it.extension == "png" }.sortedBy { it.name }
                check(images.isNotEmpty() && images.all { it.length() > 0 }) { "Missing component fixture captures" }
                val manifest = org.json.JSONObject()
                    .put("variant", variant).put("mode", mode).put("backend", backend.name)
                    .put("colorMode", colors.name).put("mochiStyle", style.name)
                    .put("systemDark", actualNight).put("requestedDark", dark)
                    .put("qualifiers", org.robolectric.RuntimeEnvironment.getQualifiers())
                    .put("fixture", fixture)
                    .put("capture", "Native Robolectric production component fixture, not device/full-page capture")
                    .put("images", org.json.JSONArray(images.map { it.name }))
                File(folder, "manifest.json").writeText(manifest.toString(2) + "\n")
            } catch (failure: Throwable) {
                // Do not let a failed interaction leave partial images looking like a successful run.
                clearTarget()
                throw failure
            }
        }
    } finally {
        try { close() } finally { org.robolectric.RuntimeEnvironment.setQualifiers(originalQualifiers) }
    }
}
