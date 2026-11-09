package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.HookPrefsEditor
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.bounds.CustomBoundsCompatAppConfig
import hk.uwu.reareye.repository.bounds.CustomBoundsCompatConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.config.BusinessExtraConfigManagerScreen
import hk.uwu.reareye.ui.components.config.BusinessManagerScreen
import hk.uwu.reareye.ui.components.config.ConfigDashboardAction
import hk.uwu.reareye.ui.components.config.CustomBoundsCompatManagerScreen
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import java.io.File
import kotlin.math.abs
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual production pages and codecs with isolated preferences, not device/API integration.
 * Only the external document picker and installed-package catalog are fixtures.
 * Window captures are actual-page evidence; no synthetic sampling claim is made here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemainingManagerGlassDialogsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var prefs: PrefsManager
    private lateinit var memory: CountingPrefs
    private class CountingPrefs(private val delegate: HookPrefs = GuideMemoryPrefs()) : HookPrefs by delegate {
        val writes = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val deletedRemoteFiles: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        // This fixture has no remote blobs. Preserve idempotent delete semantics,
        // record exact requested names, and keep unsupported writes as failures.
        override fun deleteRemoteFile(name: String): Boolean {
            hk.uwu.reareye.hook.support.RemoteFileName.requireValid(name)
            deletedRemoteFiles += name
            return true
        }
        override fun edit(): HookPrefsEditor {
            val editor = delegate.edit()
            val keys = mutableSetOf<String>()
            return object : HookPrefsEditor {
                override fun putString(key: String, value: String?) = apply { keys += key; editor.putString(key, value) }
                override fun putStringSet(key: String, value: Set<String>?) = apply { keys += key; editor.putStringSet(key, value) }
                override fun putInt(key: String, value: Int) = apply { keys += key; editor.putInt(key, value) }
                override fun putLong(key: String, value: Long) = apply { keys += key; editor.putLong(key, value) }
                override fun putFloat(key: String, value: Float) = apply { keys += key; editor.putFloat(key, value) }
                override fun putBoolean(key: String, value: Boolean) = apply { keys += key; editor.putBoolean(key, value) }
                override fun remove(key: String) = apply { keys += key; editor.remove(key) }
                override fun clear() = apply { keys += delegate.all().keys; editor.clear() }
                private fun record() { keys.forEach { writes.merge(it, 1) { old, increment -> old + increment } } }
                override fun apply() { editor.apply(); record() }
                override fun commit(): Boolean = editor.commit().also { if (it) record() }
            }
        }
    }
    private var backs = 0
    private var nextUri: Uri? = null
    private val files = mutableListOf<File>()
    private val action = mutableStateOf<ConfigDashboardAction?>(null)

    @Before fun enabled() {
        Assume.assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        files.forEach { it.delete() }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); condition() }
        settle()
    }
    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun host(backend: String, dark: Boolean, content: @Composable () -> Unit) {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) { dispatchResult(requestCode, requireNotNull(nextUri)) }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.runOnUiThread {
            activity?.close()
            backs = 0
            action.value = null
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            shadowOf(activity!!.get().packageManager).installPackage(PackageInfo().apply {
                packageName = "test.bounds.existing"
            })
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        BackHandler { backs++ }
                        content()
                    }
                }
            }
        }
        settle()
    }
    private fun label(res: Int) = activity!!.get().getString(res)
    private fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; settle()
    }
    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        val original = compose.mainClock.autoAdvance
        try {
            compose.mainClock.autoAdvance = true
            performScrollTo()
        } finally {
            compose.mainClock.autoAdvance = original
        }
        settle()
        return this
    }
    private fun button(tag: String) = compose.onNodeWithTag(tag).reveal()
    private fun edit(tag: String, value: String) {
        compose.onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true).reveal().performTextReplacement(value)
        settle()
    }
    private fun horizontalActions(tag: String) {
        val cancel = button("$tag-cancel").fetchSemanticsNode().boundsInRoot
        val confirm = button("$tag-confirm").fetchSemanticsNode().boundsInRoot
        assertTrue("Cancel and confirm must be horizontal", abs(cancel.center.y - confirm.center.y) < 2f)
        assertTrue("Cancel must precede confirm in LTR", cancel.right <= confirm.left)
        assertTrue("Both actions must retain a touch target", cancel.width >= 48f && confirm.width >= 48f)
    }
    private fun capture(backend: String, dark: Boolean, name: String) {
        val folder = File(System.getProperty("ui.capture.output"),
            "remaining-manager-glass/actual-pages/$backend/${if (dark) "dark" else "light"}").apply { mkdirs() }
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun closed(tag: String) { settle(); compose.onNodeWithTag(tag).assertDoesNotExist(); assertEquals(0, backs) }
    private fun cancelBackAndOutside(tag: String, open: () -> Unit, unchanged: () -> Unit) {
        open()
        button("$tag-cancel").performTouchInput { down(center); advanceEventTime(32); cancel() }
        settle(); compose.onNodeWithTag(tag).assertExists(); unchanged()
        button("$tag-cancel").performClick(); closed(tag); unchanged()
        open(); back(); closed(tag); unchanged()
        open()
        compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
            .performTouchInput { click(Offset(2f, 2f)) }
        closed(tag); unchanged()
    }

    @Test fun businessCreateAndEditKeepValidationPersistenceAndEmbeddedHost() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) for (embedded in listOf(false, true)) {
            memory = CountingPrefs()
            prefs = PrefsManager(memory)
            val original = RearBusinessConfig("fixture-business", "com.xiaomi.subscreencenter", "fixture_widget", "/fixture/template.json")
            val locked = original.copy(id = "locked-business", business = "locked_widget", renameable = false)
            prefs.putString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA, RearWidgetConfigCodec.encodeBusinesses(listOf(original, locked)))
            host(backend, dark) {
                BusinessManagerScreen(prefs, onBack = { backs++ }, embedded = embedded,
                    actionRequest = action.value, onActionHandled = { action.value = null })
            }
            await { compose.onAllNodesWithText("fixture_widget").fetchSemanticsNodes().isNotEmpty() }
            val baseline = prefs.getString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA)
            val create = {
                compose.runOnUiThread { action.value = ConfigDashboardAction.ADD_COMPONENT }
                await { present("business-form") }
            }
            cancelBackAndOutside("business-form", create) {
                assertEquals(baseline, prefs.getString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA))
            }
            create()
            button("business-form-confirm").performClick(); settle()
            compose.onNodeWithTag("business-form").assertExists()
            assertEquals(baseline, prefs.getString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA))
            edit("business-form-name", "locked_widget"); edit("business-form-path", "/fixture/new.json")
            button("business-form-confirm").performClick(); settle()
            compose.onNodeWithTag("business-form").assertExists() // Locked-name collision cannot be saved.
            edit("business-form-name", "new_widget")
            horizontalActions("business-form")
            capture(backend, dark, "business-create-$embedded")
            val submit = button("business-form-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val writesBefore = memory.writes[ConfigKeys.REAR_WIDGET_BUSINESS_DATA] ?: 0
            compose.runOnUiThread { submit(); submit() }
            closed("business-form")
            await { RearWidgetManagerRepository.loadBusinesses(prefs).any { it.business == "new_widget" } &&
                (memory.writes[ConfigKeys.REAR_WIDGET_BUSINESS_DATA] ?: 0) >= writesBefore + 1 }
            assertEquals(writesBefore + 1, memory.writes[ConfigKeys.REAR_WIDGET_BUSINESS_DATA])
            assertEquals(1, RearWidgetManagerRepository.loadBusinesses(prefs).count { it.business == "new_widget" })
            assertEquals(locked, RearWidgetManagerRepository.loadBusinesses(prefs).first { it.id == locked.id })
            compose.onNodeWithText("fixture_widget").reveal().performClick(); settle()
            edit("business-form-name", "renamed_widget")
            capture(backend, dark, "business-edit-$embedded")
            button("business-form-confirm").performClick(); closed("business-form")
            await { RearWidgetManagerRepository.loadBusinesses(prefs).any { it.id == original.id && it.business == "renamed_widget" } }
            assertEquals(listOf(RearWidgetConfigCodec.businessBlobRemoteFileName(original.business)), memory.deletedRemoteFiles)
        }
    }

    @Test fun businessExtraAddKeepsValidationChildNavigationAndDismissal() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            memory = CountingPrefs()
            prefs = PrefsManager(memory)
            host(backend, dark) { BusinessExtraConfigManagerScreen(prefs, onBack = { backs++ }) }
            val open = {
                await { compose.onNodeWithContentDescription(label(R.string.rear_widget_business_extra_add))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }
                compose.onNodeWithContentDescription(label(R.string.rear_widget_business_extra_add)).performClick()
                await { present("business-extra-form") }
            }
            cancelBackAndOutside("business-extra-form", open) {
                assertTrue(RearBusinessExtraConfigRepository.getAllConfigs(prefs).isEmpty())
            }
            open(); button("business-extra-form-confirm").performClick(); settle()
            compose.onNodeWithTag("business-extra-form").assertExists()
            assertTrue(RearBusinessExtraConfigRepository.getAllConfigs(prefs).isEmpty())
            edit("business-extra-form-name", "new.extra")
            horizontalActions("business-extra-form"); capture(backend, dark, "business-extra-create")
            button("business-extra-form-confirm").performClick(); settle()
            await { RearBusinessExtraConfigRepository.getAllConfigs(prefs).any { it.business == "new.extra" } }
            compose.onNodeWithText(label(R.string.rear_widget_business_hide_time_tip)).assertExists()
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick(); settle()
            assertEquals(0, backs)
            compose.onNodeWithText("new.extra").assertExists()
            open(); edit("business-extra-form-name", "new.extra")
            button("business-extra-form-confirm").performClick(); settle()
            assertEquals(1, RearBusinessExtraConfigRepository.getAllConfigs(prefs).size)
            back(); assertEquals(0, backs)
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            assertEquals(1, backs)
        }
    }

    @Test fun boundsEditAndOverwriteKeepValidationAndDestructiveConfirmation() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            memory = CountingPrefs()
            prefs = PrefsManager(memory)
            val original = CustomBoundsCompatAppConfig("test.bounds.existing")
            prefs.putStringSet(ConfigKeys.CUSTOM_BOUNDS_COMPAT_APPS, setOf(original.packageName))
            prefs.putString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA, CustomBoundsCompatConfigCodec.encode(listOf(original)))
            host(backend, dark) { CustomBoundsCompatManagerScreen(prefs, onBack = { backs++ }) }
            await { compose.onAllNodesWithText(original.packageName).fetchSemanticsNodes().isNotEmpty() }
            val baseline = prefs.getString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA)
            val openEdit = {
                compose.onNodeWithText(original.packageName).reveal().performClick()
                await { present("bounds-edit-form") }
            }
            cancelBackAndOutside("bounds-edit-form", openEdit) {
                assertEquals(baseline, prefs.getString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA))
            }
            openEdit(); edit("bounds-edit-form-scale", "-1")
            button("bounds-edit-form-confirm").performClick(); settle()
            compose.onNodeWithTag("bounds-edit-form").assertExists()
            assertEquals(baseline, prefs.getString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA))
            edit("bounds-edit-form-scale", "0.85")
            horizontalActions("bounds-edit-form"); capture(backend, dark, "bounds-edit")
            button("bounds-edit-form-confirm").performClick(); closed("bounds-edit-form")
            val saved = CustomBoundsCompatConfigCodec.parse(prefs.getString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA)).single()
            assertEquals(0.85f, saved.scale, 0.0001f)
            val editedBaseline = prefs.getString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA)
            val imported = saved.copy(scale = 0.65f)
            val file = File.createTempFile("bounds-overwrite-", ".txt").also {
                it.writeText(CustomBoundsCompatConfigCodec.encodeRuleFile(imported)); files += it
            }
            nextUri = Uri.fromFile(file)
            val openOverwrite = {
                compose.onNodeWithContentDescription(label(R.string.script_import)).performClick()
                await { present("bounds-overwrite-form") }
            }
            cancelBackAndOutside("bounds-overwrite-form", openOverwrite) {
                assertEquals(editedBaseline, prefs.getString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA))
            }
            openOverwrite(); horizontalActions("bounds-overwrite-form"); capture(backend, dark, "bounds-overwrite")
            val submit = button("bounds-overwrite-form-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val writesBefore = memory.writes[ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA] ?: 0
            compose.runOnUiThread { submit(); submit() }
            closed("bounds-overwrite-form")
            assertEquals(writesBefore + 1, memory.writes[ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA])
            assertEquals(imported, CustomBoundsCompatConfigCodec.parse(prefs.getString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA)).single())
            back(); assertEquals(1, backs)
        }
    }
}
