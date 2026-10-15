package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MamlActivityCardBridgeTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val loader = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.miui.maml.ScreenElementRoot" -> CardFixtureRoot::class.java
            "com.miui.maml.component.MamlView" -> CardFixtureView::class.java
            "com.miui.maml.util.ConfigFile" -> CardFixtureConfig::class.java
            "com.miui.maml.elements.ButtonScreenElement" -> CardFixtureGesture::class.java
            "com.miui.maml.widget.edit.WidgetEditSave" -> CardFixtureEdit::class.java
            else -> super.loadClass(name)
        }
    }
    private lateinit var original: ActivityHostSelectionStore.Persistence
    private lateinit var bridge: MamlActivityCardBridge
    private val roots = mutableListOf<CardFixtureRoot>()
    private val records = mutableMapOf<String, String>()
    private lateinit var file: File

    @Before
    fun setUp() {
        original = ActivityHostSelectionStore.persistence
        // Bridge/ABI tests isolate persistence; real AtomicFile failure cases have separate tests.
        ActivityHostSelectionStore.persistence = object : ActivityHostSelectionStore.Persistence {
            override fun read(file: File): String? = records[file.path]
            override fun write(file: File, content: String) {
                records[file.path] = content
            }

            override fun delete(file: File) {
                records.remove(file.path)
            }
        }
        file = File(temporary.newFolder("reareye-maml-cards-v1"), "a".repeat(64) + ".xml")
        bridge = MamlActivityCardBridge(loader)
        val app = ApplicationInfo().apply { packageName = "example"; enabled = true }
        val info = ActivityInfo().apply {
            packageName = "example"; name = "example.Main"; enabled = true; exported = true
            applicationInfo = app
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "example"; applicationInfo = app; activities = arrayOf(info)
        })
    }

    @After
    fun tearDown() {
        roots.forEach { bridge.detachDeleted(it) }
        ActivityHostSelectionStore.persistence = original
    }

    private fun root() =
        CardFixtureRoot(context).apply { picker = false; activityPicker = true }.also(roots::add)

    private fun command(
        root: CardFixtureRoot, instance: String, op: String, sequence: Int,
        pkg: String = "", component: String = "", dpi: Int = 0
    ) {
        root.variables.put("ra_action", "$instance|$op|$pkg|$component|$dpi|$sequence")
        bridge.action(root, file)
    }

    @Test
    fun saveCreatesPermitAndRestoreOrResetNeverReplaysIt() {
        val root = root()
        val instance = bridge.initialize(root, file)
        assertEquals(1.0, root.variables.getDouble("ra_ready"), 0.0)
        assertNull(root.mConfig)
        command(root, instance, "save", 1, "example", "example/example.Main", 320)
        assertEquals(1.0, root.variables.getDouble("ra_selected"), 0.0)
        val token = root.variables.get("ra_launchRequest") as String
        val permit = MamlActivityCardBridge.getIssuedLaunch(
            root,
            instance,
            "example/example.Main",
            320,
            token
        )
        assertNotNull(permit)
        assertTrue(permit!!.claim(instance, "example/example.Main", 320, token))
        bridge.initialize(root, file)
        assertEquals("", root.variables.get("ra_launchRequest"))
        assertFalse(permit.isValid())
        assertEquals(1.0, root.variables.getDouble("ra_actionSequence"), 0.0)
        command(root, instance, "reset", 2)
        assertEquals(0.0, root.variables.getDouble("ra_selected"), 0.0)
        command(root, instance, "load", 3, "example")
        assertEquals(320.0, root.variables.getDouble("ra_savedDpi"), 0.0)
        assertEquals(1.0, root.variables.getDouble("ra_savedFound"), 0.0)
        assertEquals("example/example.Main", root.variables.get("ra_savedComponent"))
        assertEquals("example.Main", root.variables.get("ra_savedActivityClass"))
        assertEquals("", root.variables.get("ra_launchRequest"))
        command(root, instance, "load", 4, "missing")
        assertEquals("", root.variables.get("ra_savedActivityClass"))
        command(root, instance, "load", 5, "example")
        command(root, instance, "reset", 6)
        assertEquals("", root.variables.get("ra_savedActivityClass"))
    }

    @Test
    fun hideDuringBlockedSaveCannotTurnOldClickIntoNewPermit() {
        val root = root()
        val instance = bridge.initialize(root, file)
        val backend = ActivityHostBackend()
        backend.onInit(); backend.onResume(); backend.onAttachmentChanged(true)
        backend.onVisibilityChanged(true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val persistence = ActivityHostSelectionStore.persistence
        ActivityHostSelectionStore.persistence =
            object : ActivityHostSelectionStore.Persistence by persistence {
                override fun write(file: File, content: String) {
                    entered.countDown()
                    check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    persistence.write(file, content)
                }
            }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        root.variables.put("ra_action", "$instance|save|example|example/example.Main|320|1")
        try {
            val saved = executor.submit { bridge.action(root, file) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            backend.onVisibilityChanged(false)
            proceed.countDown()
            saved.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val token = root.variables.get("ra_launchRequest") as String
            val permit = MamlActivityCardBridge.getIssuedLaunch(
                root,
                instance,
                "example/example.Main",
                320,
                token
            )!!
            backend.onVisibilityChanged(true)
            backend.onInputsChanged(
                MamlBackendInputs(
                    "test", "activity", activityHost =
                        ActivityHostInputs(
                            "example/example.Main",
                            320,
                            instance = instance,
                            userRequest = token,
                            launchPermit = permit
                        )
                )
            )
            assertFalse(permit.isValid())
            assertEquals(320.0, root.variables.getDouble("ra_dpi"), 0.0)
        } finally {
            proceed.countDown()
            executor.shutdownNow()
            ActivityHostSelectionStore.persistence = persistence
            backend.onFinish()
        }
    }

    @Test
    fun deletionRevokesPermitAndAnOldRootCannotResave() {
        val root = root()
        val instance = bridge.initialize(root, file)
        command(root, instance, "save", 1, "example", "example/example.Main", 240)
        val token = root.variables.get("ra_launchRequest") as String
        val permit = MamlActivityCardBridge.getIssuedLaunch(
            root,
            instance,
            "example/example.Main",
            240,
            token
        )!!
        bridge.delete(file)
        assertFalse(permit.isValid())
        assertEquals(0.0, root.variables.getDouble("ra_ready"), 0.0)
        command(root, instance, "save", 2, "example", "example/example.Main", 320)
        assertTrue(records.isEmpty())
        val reopened = root()
        assertNotEquals(instance, bridge.initialize(reopened, file))
        assertEquals(0.0, reopened.variables.getDouble("ra_selected"), 0.0)
    }

    @Test
    fun staleActionCannotReplaceSavedDpiAndWidgetsKeepTheirCapability() {
        val root = root()
        val instance = bridge.initialize(root, file)
        command(root, instance, "save", 2, "example", "example/example.Main", 240)
        command(root, instance, "save", 1, "example", "example/example.Main", 320)
        assertEquals(240.0, root.variables.getDouble("ra_dpi"), 0.0)
        val adapter = MamlCardHostBridge(loader)
        assertTrue(adapter.enabled(root))
        root.picker = true
        assertFalse(adapter.enabled(root))
        root.activityPicker = false
        assertTrue(adapter.enabled(root))
    }

    @Test
    fun revokedLaunchReturnsPickerWithoutErasingSavedSelection() {
        val root = root()
        val instance = bridge.initialize(root, file)
        command(root, instance, "save", 1, "example", "example/example.Main", 320)
        val request = root.variables.get("ra_launchRequest") as String
        val permit = MamlActivityCardBridge.getIssuedLaunch(
            root,
            instance,
            "example/example.Main",
            320,
            request
        )!!
        permit.revoke()
        assertNull(
            MamlActivityCardBridge.getIssuedLaunch(
                root,
                instance,
                "example/example.Main",
                320,
                request
            )
        )
        assertEquals("", root.variables.get("ra_launchRequest"))
        assertEquals(1.0, root.variables.getDouble("ra_selected"), 0.0)
        assertEquals("example/example.Main", root.variables.get("ra_component"))
        command(root, instance, "load", 2, "example")
        assertEquals("example/example.Main", root.variables.get("ra_savedComponent"))
    }

    @Test
    fun activityPickerHitExcludesNavigationAndDoesNotRequireWidgetGesture() {
        val root = root().apply { gesture = null; scale = 0.5f }
        root.variables.put("ra_ready", 1.0); root.variables.put("ra_selected", 0.0)
        root.variables.put("displayReady", 1.0)
        root.variables.put("pickerX", 100.0); root.variables.put("pickerY", 20.0)
        root.variables.put("pickerW", 200.0); root.variables.put("pickerH", 100.0)
        val adapter = MamlCardHostBridge(loader)
        assertTrue(adapter.pickerHit(root, 60f, 20f))
        assertFalse(adapter.pickerHit(root, 40f, 20f))
        root.variables.put("ra_selected", 1.0)
        root.variables.put("ra_launchRequest", "")
        assertTrue(adapter.pickerHit(root, 60f, 20f)) // restored selection can be reselected
        root.variables.put("ra_launchRequest", "user-authorized-request")
        assertFalse(adapter.pickerHit(root, 60f, 20f)) // live app owns content input
    }
}
