package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.theme.MixTheme
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.presetpack.PresetPackContract
import hk.uwu.reareye.repository.presetpack.PresetPackValidator
import hk.uwu.reareye.service.PresetPackDownloadService
import hk.uwu.reareye.ui.components.PresetPackDialog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Actual PresetPackDialog and repository local reads over an isolated filesDir. No check/download,
 * import, permission, apply, remove, or service action is invoked. Long synthetic local metadata
 * makes the real body scroll on a 480dp viewport. This is not a busy-task or full-app/device test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetPackGlassDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private var activity: ActivityController<ComponentActivity>? = null
    private var show by mutableStateOf(true)
    private var dismisses = 0
    private var applied = 0
    private var pageBacks = 0
    private var pageTaps = 0
    private val installedVersion = "fixture-installed-" + (1..5).joinToString("-") { "local-metadata-$it" }
    private val stagedVersion = "fixture-staged-" + (1..5).joinToString("-") { "long-version-$it" }

    @Before fun requireCapture() {
        assertTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
        assertFalse("No download may already be running in this isolated fixture", PresetPackDownloadService.state.value.running)
        assertNull(PresetPackDownloadService.state.value.release)
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun awaitLocalData() {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText("当前版本：$installedVersion").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("待应用版本：$stagedVersion").fetchSemanticsNodes().isNotEmpty()
        }
        settle()
    }
    private fun capture(backend: MixUiBackend, dark: Boolean, state: String) {
        val directory = File(System.getProperty("ui.capture.output"), "preset-pack-dialog").apply { mkdirs() }
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(directory, "${backend.name.lowercase()}-${if (dark) "dark" else "light"}-480dp-$state.png")
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun exercise(backend: MixUiBackend, dark: Boolean) {
        val files = temporary.newFolder("files")
        val packRoot = File(files, "preset-pack").apply { mkdirs() }
        val active = File(packRoot, "active.rpp")
        val staged = File(packRoot, "staging.rpp")
        writeLocalPack(active, installedVersion)
        writeLocalPack(staged, stagedVersion)
        assertEquals(installedVersion, PresetPackValidator.validate(active).packVersion)
        assertEquals(stagedVersion, PresetPackValidator.validate(staged).packVersion)
        val activeBefore = active.readBytes()
        val stagedBefore = staged.readBytes()
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            // Real getPrefsManager expects the module package. Its constructor is inert here;
            // readInstalled/stagedFile use only this explicitly isolated application filesDir.
            val isolatedContext = object : ContextWrapper(activity!!.get()) {
                override fun getApplicationContext(): Context = this
                override fun getPackageName(): String = "hk.uwu.reareye"
                override fun getFilesDir(): File = files
            }
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    CompositionLocalProvider(LocalContext provides isolatedContext) {
                        Scaffold {
                            GlassBottomSheetScene {
                                BackHandler { pageBacks++ }
                                Box(Modifier.fillMaxSize().background(MixTheme.colorScheme.surface)
                                    .pointerInput(Unit) { detectTapGestures { pageTaps++ } }) {
                                    Text("Preset pack fixture page")
                                }
                                PresetPackDialog(show = show,
                                    onDismissRequest = { dismisses++; show = false },
                                    onApplied = { applied++ })
                            }
                        }
                    }
                }
            }
        }
        awaitLocalData()
        val title = activity!!.get().getString(R.string.preset_pack_title)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("检查 GitHub").assertExists()
        compose.onNodeWithText("手动导入").assertExists()
        compose.onNodeWithText("应用并覆盖当前版本").assertExists()
        val scroll = compose.onNode(hasScrollAction(), useUnmergedTree = true)
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Real long body must overflow the short viewport", before.maxValue() > 0f)
        val initialScroll = before.value()
        capture(backend, dark, "open")
        // performScrollTo loops until semantics ScrollBy moves the target, but that async
        // animation cannot advance under our manual clock. Exercise real pointer scrolling
        // instead, with a bounded number of drags and explicit clock advancement between them.
        var swipes = 0
        while (swipes < 6 && (!compose.onNodeWithText("删除").isDisplayed() ||
                    !compose.onNodeWithText("重新校验").isDisplayed())) {
            scroll.performTouchInput {
                swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 400L)
            }
            swipes++
            settle()
        }
        assertTrue("The real short-screen body must require pointer scrolling", swipes > 0)
        compose.onNodeWithText("删除").assertIsDisplayed()
        compose.onNodeWithText("重新校验").assertIsDisplayed()
        val after = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The production body really scrolled", after.value() > initialScroll + 1f)
        assertEquals(0, applied)
        capture(backend, dark, "scrolled-bottom")

        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(1, dismisses)
        assertEquals(0, pageBacks)
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText("Preset pack fixture page").assertIsDisplayed()
        compose.runOnUiThread { show = true }
        awaitLocalData()
        compose.onNodeWithText(title).assertIsDisplayed()
        capture(backend, dark, "reopened")
        compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        settle()
        assertEquals("Outside closes the actual idle dialog", 2, dismisses)
        assertEquals(0, pageBacks)
        assertEquals(0, pageTaps)
        assertEquals(0, applied)
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText("Preset pack fixture page").assertIsDisplayed()
        assertFalse(PresetPackDownloadService.state.value.running)
        assertNull(PresetPackDownloadService.state.value.release)
        assertArrayEquals("Read-only UI must not replace the installed fixture", activeBefore, active.readBytes())
        assertArrayEquals("Read-only UI must not consume the staged fixture", stagedBefore, staged.readBytes())
        assertEquals(setOf("active.rpp", "staging.rpp"), packRoot.listFiles()!!.map { it.name }.toSet())
    }

    private fun writeLocalPack(destination: File, version: String) {
        val payload = "Inert local capture fixture. No executable resource.".toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val entry = JSONObject().put("path", "fixture.txt").put("archivePath", "payload/fixture.txt")
            .put("kind", "data").put("size", payload.size).put("compressedSize", payload.size)
            .put("sha256", checksum).put("compression", "store").put("required", true)
        val manifest = JSONObject().put("format", "rpp").put("formatVersion", PresetPackContract.FORMAT_VERSION)
            .put("packId", PresetPackContract.PACK_ID).put("packVersion", version)
            .put("generatedAt", "2000-01-01T00:00:00Z").put("sourceRoot", "fixture")
            .put("payloadRoot", "payload").put("minModuleVersion", "0.0.0")
            .put("supportedPackages", JSONArray()).put("entryCount", 1)
            .put("uncompressedBytes", payload.size).put("compressedPayloadBytes", payload.size)
            .put("entries", JSONArray().put(entry))
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(PresetPackContract.MANIFEST_NAME))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload/fixture.txt").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                crc = CRC32().apply { update(payload) }.value
            })
            zip.write(payload)
            zip.closeEntry()
        }
    }

    @Test fun miuixLightShort() = exercise(MixUiBackend.Miuix, false)
    @Test fun miuixDarkShort() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiLightShort() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiDarkShort() = exercise(MixUiBackend.Mochi, true)
}
