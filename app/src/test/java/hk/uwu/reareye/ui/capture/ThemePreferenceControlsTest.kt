package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.HookPrefsEditor
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.config.EnumSingleSelectConfigInput
import hk.uwu.reareye.ui.components.config.flattenMoreSourceItems
import hk.uwu.reareye.ui.config.*
import hk.uwu.reareye.ui.theme.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the actual single Settings entry and its production persistence path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemePreferenceControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val backing = GuideMemoryPrefs()
    private var failCommit = false
    private var commitGate: CountDownLatch? = null
    private val commits = AtomicInteger()
    private var savedOnMain = false
    private val store = object : HookPrefs by backing {
        override fun edit(): HookPrefsEditor {
            val editor = backing.edit()
            return object : HookPrefsEditor by editor {
                override fun putInt(key: String, value: Int): HookPrefsEditor {
                    editor.putInt(key, value)
                    return this
                }
                override fun remove(key: String): HookPrefsEditor {
                    editor.remove(key)
                    return this
                }
                override fun commit(): Boolean {
                    commits.incrementAndGet()
                    savedOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                    commitGate?.let { check(it.await(10, TimeUnit.SECONDS)) }
                    return if (failCommit) false else editor.commit()
                }
            }
        }
    }
    private val prefs = PrefsManager(store)
    private var activity: ActivityController<ComponentActivity>? = null
    private var mode by mutableStateOf(AppThemeMode.MIUIX_LIGHT)
    private var style by mutableStateOf(AppMochiThemeStyle.default)
    private var renderedBackend = MixUiBackend.Miuix
    private var callbacks = 0

    private fun open() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            mode = AppThemePreferences.readMode(store)
            style = AppMochiThemeStyle.fromValue(prefs.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, 0))
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                AppTheme(themeMode = mode, mochiStyle = style.style) {
                    val backend = LocalMixUiBackend.current
                    SideEffect { renderedBackend = backend }
                    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                        Column {
                            val item = flattenMoreSourceItems(REAREyeConfig).single { it.key == ConfigKeys.MODULE_THEME_MODE }
                            val type = item.type as ConfigType.EnumSingleSelect
                            EnumSingleSelectConfigInput(item, type.defaultValue, type.options, prefs) {
                                callbacks++
                                mode = AppThemePreferences.readMode(store)
                                style = AppMochiThemeStyle.fromValue(prefs.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, 0))
                            }
                        }
                    }
                }
            }
        }
        frames()
    }
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun choose(tag: String, text: String) {
        compose.onNode(hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text))) and
            hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).performTouchInput { click() }
        frames()
    }
    private fun edit() { compose.onNodeWithTag("theme-style-entry").performClick(); frames() }
    private fun save() {
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick()
        frames()
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val previous = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = true
        try { compose.waitUntil(timeoutMillis = 10_000, condition = condition) }
        finally { compose.mainClock.autoAdvance = previous }
    }
    @After fun close() {
        commitGate?.countDown()
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test fun previewCancelSaveAndRecreationUseOneProductionEntry() {
        backing.edit().putInt(ConfigKeys.MODULE_THEME_MODE, 0).commit()
        open()
        assertFalse(flattenMoreSourceItems(REAREyeConfig).any { it.key == ConfigKeys.MODULE_MOCHI_THEME_STYLE })
        edit()
        choose("theme-style-tabs", "Mochi")
        choose("theme-palette-tabs", "Nothing")
        choose("theme-brightness-tabs", label(R.string.theme_editor_dark))
        assertEquals(MixUiBackend.Miuix, renderedBackend)
        assertEquals(0, prefs.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).performClick(); frames()
        assertEquals(0, commits.get())
        assertEquals(0, callbacks)
        edit()
        choose("theme-style-tabs", "Mochi")
        choose("theme-palette-tabs", "iOS")
        choose("theme-brightness-tabs", label(R.string.theme_editor_dark))
        save()
        awaitCondition { callbacks == 1 }; frames()
        assertEquals(1, commits.get())
        assertFalse(savedOnMain)
        assertEquals(8, prefs.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
        assertEquals(1, prefs.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, -1))
        assertEquals(MixUiBackend.Mochi, renderedBackend)
        compose.onNodeWithTag("theme-style-sheet").assertDoesNotExist()
        open()
        assertEquals(AppThemeMode.MOCHI_DARK, mode)
        assertEquals(AppMochiThemeStyle.Ios, style)
    }

    @Test fun navigationStyleCancelSaveReopenAndBackendSwitchRemainTransactional() {
        backing.edit().putInt(ConfigKeys.MODULE_THEME_MODE, 0).commit()
        open(); edit()
        choose("theme-navigation-tabs", "HyperOS")
        assertEquals(AppNavigationBarStyle.IOS, AppThemePreferences.readNavigationStyle(store))
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).performClick(); frames()
        assertEquals(0, commits.get())
        edit()
        save(); awaitCondition { callbacks == 1 }; frames()
        assertEquals(AppNavigationBarStyle.IOS, AppThemePreferences.readNavigationStyle(store))
        edit()
        choose("theme-navigation-tabs", "HyperOS")
        choose("theme-style-tabs", "Mochi")
        compose.onNodeWithTag("theme-navigation-tabs").assertDoesNotExist()
        choose("theme-style-tabs", "MIUIX")
        save(); awaitCondition { callbacks == 2 }; frames()
        assertEquals(AppNavigationBarStyle.HyperOS, AppThemePreferences.readNavigationStyle(store))
        open(); edit(); save(); awaitCondition { callbacks == 3 }; frames()
        assertEquals(AppNavigationBarStyle.HyperOS, AppThemePreferences.readNavigationStyle(store))
        assertEquals(3, commits.get())
    }

    @Test fun failedSaveRetainsDraftAndBlocksDuplicateCommit() {
        backing.edit().putInt(ConfigKeys.MODULE_THEME_MODE, 0).commit()
        open(); edit()
        choose("theme-palette-tabs", "Monet")
        choose("theme-navigation-tabs", "HyperOS")
        failCommit = true
        commitGate = CountDownLatch(1)
        save()
        awaitCondition { commits.get() == 1 }
        // The save icon is replaced with progress, and close cannot dismiss in flight.
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).assertHasNoClickAction()
        assertEquals(0, callbacks)
        commitGate!!.countDown()
        awaitCondition { compose.onAllNodesWithTag("theme-save-error").fetchSemanticsNodes().isNotEmpty() }
        frames()
        assertEquals(0, prefs.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
        assertEquals(1, commits.get())
        compose.onNodeWithTag("theme-style-sheet").assertExists()
        failCommit = false; commitGate = null
        save(); awaitCondition { callbacks == 1 }; frames()
        assertEquals(4, prefs.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
        assertEquals(AppNavigationBarStyle.HyperOS, AppThemePreferences.readNavigationStyle(store))
        assertEquals(2, commits.get())
    }

    @Test fun mochiBackDuringSaveRecoversNativeSheetAndKeepsFailureDraft() {
        backing.edit().putInt(ConfigKeys.MODULE_THEME_MODE, 0).commit()
        open(); edit(); choose("theme-style-tabs", "Mochi")
        choose("theme-palette-tabs", "Nothing")
        failCommit = true
        commitGate = CountDownLatch(1)
        save(); awaitCondition { commits.get() == 1 }
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
        compose.onNodeWithTag("theme-style-sheet").assertExists()
        commitGate!!.countDown()
        awaitCondition { compose.onAllNodesWithTag("theme-save-error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, callbacks)
        assertEquals(MixUiBackend.Miuix, renderedBackend)
        failCommit = false; commitGate = null
        save(); awaitCondition { callbacks == 1 }; frames()
        assertEquals(2, prefs.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, -1))
        assertEquals(7, prefs.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
    }

    @Test fun saveCompletionReachesRecreatedActivityOwner() {
        backing.edit().putInt(ConfigKeys.MODULE_THEME_MODE, 0).commit()
        var displayedMode = -1
        var displayedStyle = -1
        fun openOwner() {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    val revision by AppThemePreferences.changes.collectAsState()
                    var ownerMode by remember { mutableStateOf(AppThemePreferences.readMode(store)) }
                    var ownerStyle by remember { mutableStateOf(AppMochiThemeStyle.fromValue(
                        store.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, 0))) }
                    LaunchedEffect(revision) {
                        ownerMode = AppThemePreferences.readMode(store)
                        ownerStyle = AppMochiThemeStyle.fromValue(store.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, 0))
                    }
                    SideEffect { displayedMode = ownerMode.value; displayedStyle = ownerStyle.value }
                    AppTheme(themeMode = ownerMode, mochiStyle = ownerStyle.style) {
                        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                            val item = flattenMoreSourceItems(REAREyeConfig).single { it.key == ConfigKeys.MODULE_THEME_MODE }
                            val type = item.type as ConfigType.EnumSingleSelect
                            EnumSingleSelectConfigInput(item, type.defaultValue, type.options, prefs) {
                                // This callback belongs only to this Activity's composition.
                                ownerMode = AppThemePreferences.readMode(store)
                                ownerStyle = AppMochiThemeStyle.fromValue(store.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, 0))
                            }
                        }
                    }
                }
            }
            frames()
        }
        openOwner(); edit()
        choose("theme-style-tabs", "Mochi")
        choose("theme-palette-tabs", "Nothing")
        choose("theme-brightness-tabs", label(R.string.theme_editor_dark))
        commitGate = CountDownLatch(1)
        save(); awaitCondition { commits.get() == 1 }
        openOwner()
        assertEquals(0, displayedMode)
        commitGate!!.countDown()
        awaitCondition { displayedMode == 8 && displayedStyle == 2 }
        frames()
        assertEquals(1, commits.get())
        commitGate = null
        edit(); save()
        awaitCondition { commits.get() == 2 }
        frames()
        assertEquals(8, store.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
        assertEquals(2, store.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, -1))
    }

    @Test fun backCancelsMochiDraftWithoutApplyingToApp() {
        backing.edit().putInt(ConfigKeys.MODULE_THEME_MODE, 0).commit()
        open(); edit(); choose("theme-style-tabs", "Mochi")
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
        compose.onNodeWithTag("theme-style-sheet").assertDoesNotExist()
        assertEquals(0, commits.get())
        assertEquals(MixUiBackend.Miuix, renderedBackend)
    }
}
