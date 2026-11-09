package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.HookPrefsEditor
import hk.uwu.reareye.repository.bounds.CustomBoundsCompatAppConfig
import hk.uwu.reareye.repository.bounds.CustomBoundsCompatConfigCodec
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.config.CustomBoundsCompatManagerScreen
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real page and real import parser. Only the external document picker is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomBoundsImportGlassDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val files = mutableListOf<File>()
    private var nextUri: Uri? = null
    private var pageBacks = 0
    private lateinit var prefs: PrefsManager
    private var baseline = ""
    private var preferenceWrites = 0

    @Before fun enabled() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        files.forEach { it.delete() }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun host(backend: String, dark: Boolean) {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, requireNotNull(nextUri))
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val memory = GuideMemoryPrefs()
        prefs = PrefsManager(object : HookPrefs by memory {
            override fun edit(): HookPrefsEditor {
                val editor = memory.edit()
                return object : HookPrefsEditor {
                    override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                    override fun putStringSet(key: String, value: Set<String>?) = apply { editor.putStringSet(key, value) }
                    override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
                    override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                    override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }
                    override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                    override fun remove(key: String) = apply { editor.remove(key) }
                    override fun clear() = apply { editor.clear() }
                    override fun apply() { preferenceWrites++; editor.apply() }
                    override fun commit(): Boolean { preferenceWrites++; return editor.commit() }
                }
            }
        })
        prefs.putStringSet(ConfigKeys.CUSTOM_BOUNDS_COMPAT_APPS, setOf("test.bounds.existing"))
        baseline = CustomBoundsCompatConfigCodec.encode(listOf(CustomBoundsCompatAppConfig("test.bounds.existing")))
        prefs.putString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA, baseline)
        pageBacks = 0
        preferenceWrites = 0
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        BackHandler { pageBacks++ }
                        CustomBoundsCompatManagerScreen(prefs) { pageBacks++ }
                    }
                }
            }
        }
        settle()
    }
    private fun importFile(raw: String, tag: String, accessibility: Boolean = false) {
        val file = File.createTempFile("bounds-import-glass-", ".txt").also {
            it.writeText(raw); files += it
        }
        nextUri = Uri.fromFile(file)
        val button = compose.onNodeWithContentDescription(activity!!.get().getString(R.string.script_import))
        if (accessibility) button.performSemanticsAction(SemanticsActions.OnClick) { it() }
        else button.performClick()
        // Import parsing runs on Dispatchers.IO; wait for the actual production result.
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithTag("$tag-message").fetchSemanticsNodes().isNotEmpty()
        }
        settle()
        compose.onNodeWithTag("$tag-message").assertIsDisplayed()
    }
    private fun unchanged() {
        assertEquals("Notices must not write preferences", 0, preferenceWrites)
        assertEquals(baseline, prefs.getString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA))
        assertEquals(setOf("test.bounds.existing"), prefs.getStringSet(ConfigKeys.CUSTOM_BOUNDS_COMPAT_APPS))
    }
    private fun closed(tag: String) {
        settle()
        compose.onNodeWithTag("$tag-confirm").assertDoesNotExist()
        compose.onNodeWithTag("bounds-manager-list").assertIsDisplayed()
        unchanged()
    }
    private fun capture(backend: String, dark: Boolean, tag: String) {
        val dir = File(System.getProperty("ui.capture.output"), "bounds-import-glass").apply { mkdirs() }
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(dir, "$backend-${if (dark) "dark" else "light"}-$tag.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    @Test fun realImportNoticesKeepDismissalAndNeverMutateRules() {
        val cases = listOf(
            "bounds-import-failed" to "This is not a bounds rule file.",
            "bounds-import-rejected" to CustomBoundsCompatConfigCodec.encodeRuleFile(
                CustomBoundsCompatAppConfig("test.bounds.definitely.not.installed")),
        )
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            host(backend, dark)
            for ((tag, raw) in cases) {
                importFile(raw, tag)
                capture(backend, dark, tag)
                val oldAction = compose.onNodeWithTag("$tag-confirm")
                    .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                compose.runOnUiThread { oldAction(); oldAction() }
                closed(tag)
                importFile(raw, tag)
                // Cancellation must be delivered while the old input root still
                // exists. Fully dismissing a native window disposes its dispatcher;
                // sending its UP to a reopened root would be an invalid test gesture.
                // Exercise a real cancelled press, then close/reopen the same notice.
                val oldId = compose.onNodeWithTag("$tag-confirm").fetchSemanticsNode().id
                compose.onNodeWithTag("$tag-confirm").performTouchInput {
                    down(center)
                    advanceEventTime(32)
                    cancel()
                }
                settle()
                compose.onNodeWithTag("$tag-confirm").assertIsDisplayed()
                unchanged()
                compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
                closed(tag)
                // Reopen through the actual import action and parser. No old
                // pointer or disposed semantics action is reused in the new root.
                importFile(raw, tag, accessibility = true)
                org.junit.Assert.assertNotEquals(oldId, compose.onNodeWithTag("$tag-confirm").fetchSemanticsNode().id)
                compose.onNodeWithTag("$tag-confirm").assertIsDisplayed()
                unchanged()
                compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
                closed(tag)
                assertEquals(0, pageBacks)
                importFile(raw, tag)
                compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("$tag-confirm")), useUnmergedTree = true)
                    .performTouchInput { click(Offset(2f, 2f)) }
                closed(tag)
                importFile(raw, tag)
                // A fresh complete gesture must still activate the reopened button.
                compose.onNodeWithTag("$tag-confirm").performTouchInput { click(center) }
                closed(tag)
            }
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, pageBacks)
        }
    }
}
