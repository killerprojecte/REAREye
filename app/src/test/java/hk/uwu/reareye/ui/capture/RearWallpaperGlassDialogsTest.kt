package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.ActivityOptionsCompat
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperRepository
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.config.RearWallpaperManagerScreen
import hk.uwu.reareye.ui.components.config.RearWallpaperManagerState
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.widgetapi.RearWallpaperApiClient
import hk.uwu.reareye.widgetapi.RearWallpaperApiContract.BundleKeys as Keys
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleCodec
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleEntry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual manager, forms, repository, memory preferences, and URI packaging.
 * Only wallpaper IPC and the system document picker are fake. No device service,
 * user file, network, upload, or real permission grant is involved. Native JVM
 * pixels do not certify physical-device optics, system-picker grants, or IME. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearWallpaperGlassDialogsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val clientField = RearWallpaperRepository::class.java.getDeclaredField("remoteClient").apply { isAccessible = true }
    private var originalClient: Any? = null
    private var installedClient = false
    private lateinit var fake: WallpaperClient
    private lateinit var state: RearWallpaperManagerState
    private lateinit var prefs: PrefsManager
    private val files = mutableListOf<File>()
    private var nextUri: Uri? = null
    private var pickerTypes = emptyList<String>()
    private var pickerCalls = 0
    private var pageBacks = 0

    private class WallpaperClient(private val previewPngs: List<ByteArray>) : RearWallpaperApiClient() {
        @Volatile var title = "Mountain wallpaper"
        @Volatile var reject = false
        @Volatile var deleted = false
        @Volatile var edits = 0
        @Volatile var deletes = 0
        @Volatile var imports = 0
        @Volatile var syncs = 0
        @Volatile var options = Bundle()
        @Volatile var preview: String? = null
        @Volatile var importedEntries: Map<String, String> = emptyMap()
        override fun isConnected() = true
        override fun bind(context: Context, onConnected: (() -> Unit)?): Boolean { onConnected?.invoke(); return true }
        override fun getCatalog() = Bundle().apply {
            putParcelableArrayList(Keys.ITEMS, ArrayList((1..12).filterNot { it == 1 && deleted }.map { id ->
                Bundle().apply {
                    putInt(Keys.WALLPAPER_ID, id)
                    putString(Keys.NAME, if (id == 1) title else "Wallpaper $id")
                    putString(Keys.TITLE, "Fixture category")
                    putString(Keys.DESCRIPTION, "Fixture description")
                    putString(Keys.AUTHOR, "Fixture author")
                    putString(Keys.DESIGNER, "Fixture designer")
                    putString(Keys.RES_SUB_TYPE, "fixture")
                    putBoolean(Keys.CAN_EDIT_METADATA, id != 2)
                    putBoolean(Keys.CAN_DELETE, id != 2)
                    putBoolean(Keys.THIRD_PARTIES, true)
                    putBoolean(Keys.PREVIEW_AVAILABLE, true)
                    putString(Keys.PREVIEW_SIGNATURE, "native-dialog-fixture-$id")
                }
            }))
        }
        // The IPC fixture returns bytes, as a device service would. Native image
        // initialization belongs to the Robolectric UI thread, not this IO callback.
        override fun getPreview(wallpaperId: Int): ByteArray {
            return previewPngs[wallpaperId % 2].copyOf()
        }
        private fun result() = Bundle().apply {
            putBoolean(Keys.SUCCESS, !reject)
            if (reject) putString(Keys.ERROR, "fixture failure")
        }
        override fun updateWallpaperMetadata(wallpaperId: Int, previewUri: String?, options: Bundle): Bundle {
            check(wallpaperId == 1)
            this.options = Bundle(options); preview = previewUri
            if (!reject) title = options.getString(Keys.META_TITLE_FALLBACK)!!
            edits++
            return result()
        }
        override fun deleteWallpaper(wallpaperId: Int): Bundle {
            check(wallpaperId == 1)
            if (!reject) deleted = true
            deletes++
            return result()
        }
        override fun syncSchedule(enabled: Boolean, scheduleData: String): Boolean {
            syncs++
            return !reject
        }
        override fun importWallpaperPackage(packageFd: ParcelFileDescriptor, displayNameHint: String,
            previewUri: String?, options: Bundle): Bundle {
            this.options = Bundle(options); preview = previewUri
            importedEntries = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(packageFd.fileDescriptor)).use { input ->
                ZipInputStream(input).use { zip -> buildMap {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        put(entry.name, zip.readBytes().decodeToString())
                    }
                } }
            }
            imports++
            return result()
        }
        override fun switchWallpaper(wallpaperId: Int): Boolean = error("Unexpected apply")
        override fun generateWallpaperPreview(wallpaperId: Int): Bundle = error("Unexpected preview generation")
    }

    @Before fun enabled() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        originalClient = clientField.get(RearWallpaperRepository)
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        if (installedClient) clientField.set(RearWallpaperRepository, originalClient)
        files.forEach { it.delete() }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(20) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); condition() }
        settle()
    }
    private fun host(backend: String, dark: Boolean) {
        compose.runOnUiThread { activity?.close(); activity = null }
        val previewPngs = compose.runOnUiThread {
            // Prime native bitmap/PNG JNI before loadCatalog launches Dispatchers.IO.
            // Both the fixture and the production preview decoder remain NATIVE.
            listOf(0xFF2362D4.toInt(), 0xFFF1B62B.toInt()).map { color ->
                val image = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
                val bytes = try {
                    image.eraseColor(color)
                    // Initialize native NIO bitmap access before Activity rendering
                    // and IO decoding, and verify the transferred pixels are restored.
                    val pixels = java.nio.ByteBuffer.allocate(image.byteCount)
                    image.copyPixelsToBuffer(pixels)
                    image.eraseColor(0)
                    assertEquals(0, image.getPixel(80, 50))
                    pixels.rewind()
                    image.copyPixelsFromBuffer(pixels)
                    assertEquals(color, image.getPixel(80, 50))
                    ByteArrayOutputStream().use { out ->
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, out))
                        out.toByteArray()
                    }
                } finally {
                    image.recycle()
                }
                val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try {
                    assertEquals(160, decoded.width)
                    assertEquals(100, decoded.height)
                    assertEquals(color, decoded.getPixel(80, 50))
                } finally {
                    decoded.recycle()
                }
                bytes
            }
        }
        fake = WallpaperClient(previewPngs)
        clientField.set(RearWallpaperRepository, fake); installedClient = true
        prefs = PrefsManager(GuideMemoryPrefs())
        RearWallpaperRepository.saveSchedule(prefs, listOf(RearWallpaperScheduleEntry(1, 60_000)))
        RearWallpaperRepository.setScheduleEnabled(prefs, true)
        state = RearWallpaperManagerState()
        pageBacks = 0; pickerCalls = 0; nextUri = null; pickerTypes = emptyList()
        ShadowToast.reset()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                pickerCalls++
                pickerTypes = (input as Array<*>).map { it as String }
                dispatchResult(requestCode, nextUri)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        BackHandler { pageBacks++ }
                        RearWallpaperManagerScreen(prefsManager = prefs, state = state, onBack = { pageBacks++ })
                    }
                }
            }
        }
        await { compose.onAllNodesWithTag("wallpaper-card-1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, fake.edits + fake.imports + fake.deletes + fake.syncs)
    }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun open(kind: String) {
        val node = when (kind) {
            "import" -> compose.onNodeWithContentDescription(label(R.string.rear_wallpaper_import))
            "metadata" -> compose.onNode(hasContentDescription(label(R.string.rear_wallpaper_edit_metadata)) and
                hasAnyAncestor(hasTestTag("wallpaper-card-1")))
            "interval" -> compose.onNode(hasText(label(R.string.rear_wallpaper_edit_interval_short)) and
                hasAnyAncestor(hasTestTag("wallpaper-card-1")))
            "delete" -> compose.onNode(hasText(label(R.string.rear_widget_action_delete)) and
                hasAnyAncestor(hasTestTag("wallpaper-card-1")))
            else -> error(kind)
        }
        node.performClick(); settle()
        compose.onNodeWithTag("wallpaper-$kind-dialog").assertIsDisplayed()
    }
    private fun reveal(tag: String) {
        val wasAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = true
        try {
            compose.onNodeWithTag(tag).performScrollTo()
        } finally {
            compose.mainClock.autoAdvance = wasAutoAdvance
        }
        settle()
    }
    private fun action(kind: String, confirm: Boolean): SemanticsNodeInteraction {
        if (kind == "import" || kind == "metadata") {
            reveal("wallpaper-$kind-actions")
        }
        val text = if (!confirm) R.string.rear_widget_cancel else if (kind == "delete") R.string.rear_widget_action_delete else R.string.rear_widget_confirm
        return compose.onNode(hasText(label(text)) and hasAnyAncestor(hasTestTag("wallpaper-$kind-actions")))
    }
    private fun horizontalActions(kind: String) {
        val cancel = action(kind, false).fetchSemanticsNode().boundsInRoot
        val confirm = action(kind, true).fetchSemanticsNode().boundsInRoot
        assertEquals("Same action row", cancel.top, confirm.top, 1f)
        assertTrue("Cancel precedes Confirm/Delete", cancel.right <= confirm.left)
    }
    private fun editor(tag: String) = compose.onNode(hasSetTextAction() and
        (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
    private fun replace(tag: String, text: String) {
        reveal(tag)
        editor(tag).performTextReplacement(text); settle()
    }
    private fun closed(kind: String) {
        settle()
        compose.onNodeWithTag("wallpaper-$kind-dialog").assertDoesNotExist()
        compose.onNodeWithTag("RearWallpaperManagerScreen-list").assertIsDisplayed()
    }
    private fun back(kind: String) {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        closed(kind); assertEquals("Dialog Back must not navigate", 0, pageBacks)
    }
    private fun pick(tag: String, uri: Uri?, expectedTypes: List<String>) {
        nextUri = uri
        reveal(tag)
        compose.onNode(hasText(label(R.string.rear_wallpaper_select_file)) and hasAnyAncestor(hasTestTag(tag))).performClick()
        settle(); assertEquals(expectedTypes, pickerTypes)
    }
    private val packageTypes = listOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
    private val metadataTypes = listOf("application/json", "application/octet-stream", "*/*")
    private val previewTypes = listOf("image/*", "application/octet-stream", "*/*")
    private fun fixtureFile(suffix: String, bytes: ByteArray): Uri {
        val file = File.createTempFile("wallpaper-dialog-", suffix).also { it.writeBytes(bytes); files += it }
        return Uri.fromFile(file)
    }
    private fun capture(name: String): Bitmap {
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        val dir = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"), "wallpaper-glass").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }

    @Test fun realManagerMetadataAndDeletePreserveCancelFailureSaveReopenAndPermissions() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            host(backend, dark)
            val prefix = "$backend-${if (dark) "dark" else "light"}"
            // Inspect an actually composed read-only card, not a vacuous absent item.
            compose.onNodeWithTag("RearWallpaperManagerScreen-list")
                .performScrollToNode(hasTestTag("wallpaper-card-2")); settle()
            compose.onNodeWithTag("wallpaper-card-2").assertIsDisplayed()
            // Read-only source still has no metadata/delete capability, regardless of new presentation.
            compose.onNode(hasContentDescription(label(R.string.rear_wallpaper_edit_metadata)) and
                hasAnyAncestor(hasTestTag("wallpaper-card-2"))).assertDoesNotExist()
            compose.onNode(hasText(label(R.string.rear_widget_action_delete)) and
                hasAnyAncestor(hasTestTag("wallpaper-card-2"))).assertDoesNotExist()
            compose.onNodeWithTag("RearWallpaperManagerScreen-list")
                .performScrollToNode(hasTestTag("wallpaper-card-1")); settle()
            open("metadata"); capture("$prefix-metadata-top")
            replace("wallpaper-title-fallback", "")
            replace("wallpaper-title-zh", "")
            action("metadata", true).assertIsNotEnabled()
            horizontalActions("metadata"); capture("$prefix-metadata-actions")
            action("metadata", false).performClick(); closed("metadata")
            assertEquals(0, fake.edits)
            open("metadata")
            editor("wallpaper-title-fallback").assertTextContains("Mountain wallpaper")
            replace("wallpaper-title-fallback", "Discard me")
            back("metadata")
            open("metadata")
            editor("wallpaper-title-fallback").assertTextContains("Mountain wallpaper")
            replace("wallpaper-title-fallback", "Changed title")
            fake.reject = true
            action("metadata", true).performClick(); closed("metadata")
            await { fake.edits == 1 && ShadowToast.getTextOfLatestToast()?.contains("fixture failure") == true }
            assertEquals("Mountain wallpaper", fake.title)
            open("metadata")
            editor("wallpaper-title-fallback").assertTextContains("Mountain wallpaper")
            replace("wallpaper-title-fallback", "Changed title")
            fake.reject = false
            action("metadata", true).performClick(); closed("metadata")
            await { fake.edits == 2 && compose.onAllNodesWithText("Changed title").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Fixture author", fake.options.getString(Keys.META_AUTHOR))
            assertTrue(fake.options.getBoolean(Keys.META_THIRD_PARTIES))
            assertNull(fake.preview)
            open("metadata")
            editor("wallpaper-title-fallback").assertTextContains("Changed title")
            back("metadata")
            open("delete"); horizontalActions("delete"); capture("$prefix-delete")
            action("delete", false).performClick(); closed("delete")
            assertEquals(0, fake.deletes)
            open("delete"); back("delete")
            open("delete")
            fake.reject = true
            action("delete", true).performClick(); closed("delete")
            await { fake.deletes == 1 && ShadowToast.getTextOfLatestToast()?.contains("fixture failure") == true }
            assertFalse(fake.deleted)
            compose.onNodeWithTag("wallpaper-card-1").assertIsDisplayed()
            open("delete")
            fake.reject = false
            action("delete", true).performClick(); closed("delete")
            await { fake.deletes == 2 && compose.onAllNodesWithTag("wallpaper-card-1").fetchSemanticsNodes().isEmpty() }
            assertTrue(RearWallpaperRepository.loadSchedule(prefs).isEmpty())
            assertFalse(RearWallpaperRepository.isScheduleEnabled(prefs))
            capture("$prefix-after-delete")
        }
    }

    @Test fun realImportUsesUnchangedPickerContractsAndRepositoryPayloads() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            host(backend, dark)
            val prefix = "$backend-${if (dark) "dark" else "light"}"
            val source = fixtureFile(".zip", ByteArrayOutputStream().use { out ->
                ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("fixture.txt")); zip.write("fixture payload".toByteArray()); zip.closeEntry() }
                out.toByteArray()
            })
            val metadata = "{\"titles\":{\"fallback\":\"Fixture metadata\"}}"
            val metadataUri = fixtureFile(".json", metadata.toByteArray())
            val previewUri = fixtureFile(".png", fake.getPreview(1))
            open("import"); capture("$prefix-import-top")
            horizontalActions("import")
            action("import", true).assertIsNotEnabled()
            action("import", false).performClick(); closed("import")
            open("import")
            pick("wallpaper-package-file", null, packageTypes)
            action("import", true).assertIsNotEnabled()
            pick("wallpaper-package-file", source, packageTypes)
            // Cancelling the package picker must retain the already selected package.
            pick("wallpaper-package-file", null, packageTypes)
            action("import", true).assertIsEnabled()
            pick("wallpaper-metadata-file", metadataUri, metadataTypes)
            pick("wallpaper-preview-file", previewUri, previewTypes)
            compose.onNode(hasText(label(R.string.rear_wallpaper_clear_file)) and hasAnyAncestor(hasTestTag("wallpaper-preview-file"))).performClick()
            settle()
            compose.onNode(hasText(label(R.string.rear_wallpaper_clear_file)) and hasAnyAncestor(hasTestTag("wallpaper-preview-file"))).assertDoesNotExist()
            pick("wallpaper-preview-file", previewUri, previewTypes)
            replace("wallpaper-title-fallback", "Imported fixture")
            action("import", true).performClick(); closed("import")
            await { fake.imports == 1 }
            assertEquals("Imported fixture", fake.options.getString(Keys.META_TITLE_FALLBACK))
            assertEquals(previewUri.toString(), fake.preview)
            assertEquals("fixture payload", fake.importedEntries["fixture.txt"])
            assertEquals(metadata, fake.importedEntries["metadata.mrm"])
            open("import")
            editor("wallpaper-title-fallback").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            action("import", true).assertIsNotEnabled()
            back("import")
            open("import")
            pick("wallpaper-package-file", source, packageTypes)
            fake.reject = true
            action("import", true).performClick(); closed("import")
            await { fake.imports == 2 && ShadowToast.getTextOfLatestToast()?.contains("fixture failure") == true }
            open("import")
            action("import", true).assertIsNotEnabled()
            capture("$prefix-import-reset-actions")
            back("import")
            // Metadata preview replacement uses the fourth picker and the same IPC argument.
            fake.reject = false
            open("metadata")
            pick("wallpaper-preview-file", previewUri, previewTypes)
            action("metadata", true).performClick(); closed("metadata")
            await { fake.edits == 1 }
            assertEquals(previewUri.toString(), fake.preview)
            assertEquals(0, fake.deletes)
        }
    }

    @Test fun realIntervalPreservesMinimumOverflowFilteringCancelAndScheduleWrites() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            host(backend, dark); open("interval")
            horizontalActions("interval")
            capture("$backend-${if (dark) "dark" else "light"}-interval")
            for (invalid in listOf("", "99", "999999999999999999999999999")) {
                replace("wallpaper-interval-input", invalid)
                action("interval", true).assertIsNotEnabled()
                assertEquals(0, fake.syncs)
            }
            replace("wallpaper-interval-input", "1a2b3")
            editor("wallpaper-interval-input").assertTextContains("123")
            action("interval", false).performClick(); closed("interval")
            assertEquals(60_000L, RearWallpaperRepository.loadSchedule(prefs).single().delayMs)
            open("interval")
            editor("wallpaper-interval-input").assertTextContains("60000")
            replace("wallpaper-interval-input", "100")
            action("interval", true).performClick(); closed("interval")
            await { fake.syncs == 1 }
            assertEquals(RearWallpaperScheduleCodec.MIN_DELAY_MS, RearWallpaperRepository.loadSchedule(prefs).single().delayMs)
            open("interval")
            editor("wallpaper-interval-input").assertTextContains("100")
            back("interval")
            open("interval")
            compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("wallpaper-interval-actions")), useUnmergedTree = true)
                .performTouchInput { click(Offset(2f, 2f)) }
            closed("interval")
            assertEquals(1, fake.syncs)
            assertEquals(0, fake.edits + fake.imports + fake.deletes)
        }
    }

    private fun assertNoWallpaperWrites(phase: String) {
        assertEquals("$phase: edits/imports/deletes/syncs", listOf(0, 0, 0, 0),
            listOf(fake.edits, fake.imports, fake.deletes, fake.syncs))
    }

    @Test fun actualWallpaperBodyRespondsWithoutDialogForegroundFeedback() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            for (kind in listOf("import", "metadata", "interval", "delete")) {
                host(backend, dark); open(kind)
                val prefix = "$backend-${if (dark) "dark" else "light"}-$kind-sampling"
                val above: String
                val below: String
                if (kind == "import" || kind == "metadata") {
                    replace("wallpaper-title-fallback", "AAAA")
                    reveal("wallpaper-title-zh")
                    above = "wallpaper-title-fallback"; below = "wallpaper-title-zh"
                } else {
                    // Match focus state before/after the foreground-only mutation.
                    if (kind == "interval") replace("wallpaper-interval-input", "60000")
                    above = if (kind == "interval") "wallpaper-interval-input" else "wallpaper-delete-message"
                    below = "wallpaper-$kind-actions"
                }
                val a = compose.onNodeWithTag(above).fetchSemanticsNode()
                val b = compose.onNodeWithTag(below).fetchSemanticsNode()
                val origin = IntArray(2)
                compose.runOnUiThread { activity!!.get().window.decorView.getLocationOnScreen(origin) }
                val left = (b.positionOnScreen.x - origin[0] + 20).roundToInt()
                val right = left + b.size.width - 40
                val top = (a.positionOnScreen.y - origin[1] + a.size.height + 2).roundToInt()
                val bottom = (b.positionOnScreen.y - origin[1] - 2).roundToInt()
                assertTrue("Unobstructed native glass gap required", right > left && bottom > top)
                val bounds = compose.onNodeWithTag("wallpaper-$kind-dialog").fetchSemanticsNode().boundsInRoot
                val initial = capture("$prefix-initial")
                when (kind) {
                    "import", "metadata" -> editor("wallpaper-title-fallback").performTextReplacement("MMMMMMMMMMMMMMMMMMMMMMMM")
                    "interval" -> editor("wallpaper-interval-input").performTextReplacement("777777")
                    // Delete has no editable foreground. This case checks idle material stability.
                }
                settle()
                val edited = capture("$prefix-foreground")
                assertNoWallpaperWrites("$prefix before body mutation")
                // Change only the actual manager's data-backed list, not its theme,
                // dialog target/draft, scrim, material, window, or a synthetic backdrop.
                compose.runOnUiThread {
                    state.wallpapers.clear(); state.schedule.clear(); state.currentWallpaperId.value = null
                }
                settle()
                assertNoWallpaperWrites("$prefix after body mutation")
                assertEquals(bounds, compose.onNodeWithTag("wallpaper-$kind-dialog").fetchSemanticsNode().boundsInRoot)
                val bodyChanged = capture("$prefix-body-empty")
                settle()
                val stable = capture("$prefix-stable")
                fun difference(x: Bitmap, y: Bitmap): Double {
                    assertTrue(left >= 0 && top >= 0 && right <= x.width && bottom <= x.height)
                    var total = 0L; var count = 0L
                    for (py in top until bottom) for (px in left until right) {
                        val p = x.getPixel(px, py); val q = y.getPixel(px, py)
                        for (shift in listOf(0, 8, 16)) { total += abs(((p shr shift) and 255) - ((q shr shift) and 255)); count++ }
                    }
                    return total.toDouble() / count
                }
                val foreground = difference(initial, edited)
                val response = difference(edited, bodyChanged)
                val drift = difference(bodyChanged, stable)
                println("WALLPAPER_GLASS $prefix roi=$left,$top,$right,$bottom foreground=$foreground body=$response drift=$drift")
                assertTrue("Foreground must not enter its source: $foreground", foreground < 1.5)
                assertTrue("Actual list must affect glass: $response", response > 0.5)
                assertTrue("No settled feedback drift: $drift", drift < 1.5)
                assertNoWallpaperWrites("$prefix before dismissal")
                back(kind)
                assertNoWallpaperWrites("$prefix after dismissal")
            }
        }
    }
}
