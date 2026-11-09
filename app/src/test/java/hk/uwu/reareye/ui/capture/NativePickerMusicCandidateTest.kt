package hk.uwu.reareye.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.config.*
import hk.uwu.reareye.ui.screen.ConfigNodePage
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

/** Production Category/Favorites/Lyrics list, with in-memory preferences and no manager IO.
 * This tests page callbacks, not ConfigScreen's outer route stack or manager tab lifetime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativePickerMusicCandidateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var favoriteIds by mutableStateOf(emptySet<String>())
    private val opened = mutableListOf<String>()
    private var backs = 0
    private lateinit var pagePrefs: PrefsManager
    private val changed = mutableListOf<String>()
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun open(
        backend: MixUiBackend,
        nodes: List<ConfigNode>,
        favorites: Boolean = false,
        dark: Boolean = false,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        pageTitle: String = if (favorites) "Fixture Favorites" else "Fixture Category",
        mochiStyle: hk.uwu.mixui.MixMochiStyle = hk.uwu.mixui.MixMochiStyle.Default,
        preparePreferences: (PrefsManager) -> Unit = {},
    ) {
        compose.mainClock.autoAdvance = false
        opened.clear(); backs = 0; favoriteIds = emptySet()
        val prefs = PrefsManager(GuideMemoryPrefs())
        preparePreferences(prefs)
        pagePrefs = prefs; changed.clear()
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                NativeTitleTestEnvironment(direction, fontScale) {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, mochiStyle = mochiStyle) {
                    Box(Modifier.fillMaxSize()) {
                        ConfigNodePage(
                            title = pageTitle,
                            nodes = nodes,
                            prefsManager = prefs,
                            bottomObstruction = 96.dp,
                            onBack = { backs++ },
                            onOpenCategory = { opened += "category:${it.key}" },
                            onOpenAppList = { opened += "apps:${it.key}" },
                            onOpenManager = { opened += "manager:${it.key}" },
                            onPreferenceChanged = { changed += it.key },
                            favoriteNodeIds = favoriteIds,
                            resolveFavoriteNodeId = { it.key },
                            onToggleFavorite = { node ->
                                node.key?.let { id -> favoriteIds = if (id in favoriteIds) favoriteIds - id else favoriteIds + id }
                            },
                            emptyStateRes = if (favorites) R.string.config_favorites_empty else null,
                        )
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                            .testTag("config-bottom-overlay"))
                    }
                }
                }
            }
        }
        frames()
    }
    private fun capture(name: String) {
        val folder = File(System.getProperty("ui.capture.output"), "native-picker-music-candidate").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { index ->
            val bitmap = roots[index].captureToImage().asAndroidBitmap()
            val suffix = if (index == 0) "" else "-root$index"
            File(folder, "$name$suffix.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    private fun windowCapture(name: String) {
        val output = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"), "native-picker-music-candidate")
        check(output.mkdirs() || output.isDirectory)
        val image = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(output, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun assertFullDescription(id: Int) {
        val text = label(id)
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        node.performScrollTo(); frames()
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        val details = layouts.joinToString("\n") { result ->
            "size=${result.size}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
                "constraints=${result.layoutInput.constraints}, maxLines=${result.layoutInput.maxLines}, " +
                "fontScale=${result.layoutInput.density.fontScale}, lines=${result.lineCount}, " +
                "overflowWidth=${result.didOverflowWidth}, overflowHeight=${result.didOverflowHeight}, " +
                "ellipsized=${(0 until result.lineCount).map { result.isLineEllipsized(it) }}, " +
                "lastEnd=${if (result.lineCount == 0) -1 else result.getLineEnd(result.lineCount - 1)}, textLength=${text.length}"
        }
        println("NATIVE-PICKER-DESCRIPTION text=$text $details")
        // Always retain the actual scrolled frame, including a failing description.
        windowCapture("description-$id-font-${layouts.first().layoutInput.density.fontScale}-check")
        assertFalse("Description must remain complete: $text\n$details", layouts.any { it.hasVisualOverflow })
        layouts.forEach { result ->
            assertEquals(text, result.layoutInput.text.text)
            assertEquals("Every character must be laid out: $details", text.length,
                result.getLineEnd(result.lineCount - 1))
        }
        compose.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(1)
    }
    @Test fun captureActualMusicPickerAndDescriptions() {
        val category = hk.uwu.reareye.ui.components.config.buildMoreCategories(REAREyeConfig)
            .single { it.group.id == "music_lyrics" }.category
        for ((style, dark) in listOf(hk.uwu.mixui.MixMochiStyle.Default to false, hk.uwu.mixui.MixMochiStyle.Ios to true)) {
            open(MixUiBackend.Mochi, category.children, dark = dark, mochiStyle = style,
                pageTitle = "音乐与歌词", preparePreferences = { it.putBoolean(ConfigKeys.HOOK_MUSIC_CONTROLS_WHITELIST, true) })
            val prefix = "${style.name}-${if (dark) "dark" else "light"}"
            windowCapture("$prefix-initial")
            assertFullDescription(R.string.lyric_display_mode_desc)
            windowCapture("$prefix-multi-closed-description")
            compose.onNodeWithText(label(R.string.lyric_display_mode), useUnmergedTree = true).performTouchInput { click() }; frames()
            compose.onNodeWithText(label(R.string.lyric_display_mode_translation)).assertIsDisplayed()
            windowCapture("$prefix-multi-open")
            compose.onNodeWithText(label(R.string.lyric_display_mode_translation)).performClick(); frames()
            assertEquals("One option click delivers one persistence callback", 1, changed.size)
            windowCapture("$prefix-multi-changed")
            compose.onNodeWithText(label(R.string.lyric_display_mode), useUnmergedTree = true).performTouchInput { click() }; frames()
            compose.onNodeWithText(label(R.string.lyric_display_mode_translation)).assertIsDisplayed()
            windowCapture("$prefix-multi-reopened")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
            assertEquals(1, changed.size)
            assertFullDescription(R.string.lyric_provider_desc)
            windowCapture("$prefix-single-closed-description")
            compose.onNodeWithText(label(R.string.lyric_provider_lyricon), useUnmergedTree = true).performTouchInput { click() }; frames()
            compose.onNodeWithText(label(R.string.lyric_provider_superlyric)).assertIsDisplayed()
            windowCapture("$prefix-single-open")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
            assertEquals(1, changed.size)
            assertEquals(0, backs)
        }
    }
    @Test fun actualMusicDescriptionsStayCompleteAtLargeFont() {
        val category = hk.uwu.reareye.ui.components.config.buildMoreCategories(REAREyeConfig)
            .single { it.group.id == "music_lyrics" }.category
        open(MixUiBackend.Mochi, category.children, fontScale = 1.5f, pageTitle = "音乐与歌词")
        assertFullDescription(R.string.lyric_display_mode_desc)
        windowCapture("Default-light-large-font-description")
        assertTrue(changed.isEmpty())
    }
    @Test fun productionSingleAndMaskMenusPersistInBothBackendsAndThemes() {
        val single = ConfigItem("capture_single", R.string.config_tab_cards,
            type = ConfigType.EnumSingleSelect(1, listOf(
                ConfigType.EnumOption(R.string.config_tab_components, 1),
                ConfigType.EnumOption(R.string.config_tab_wallpapers, 2))))
        val mask = ConfigItem("capture_mask", R.string.config_tab_more,
            type = ConfigType.MaskMultiSelect(1, listOf(
                ConfigType.MaskOption(R.string.config_tab_components, 1),
                ConfigType.MaskOption(R.string.config_tab_wallpapers, 2))))
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, listOf(single), dark = dark)
            compose.onNodeWithText(label(R.string.config_tab_cards)).performClick(); frames()
            capture("dropdown-${backend.name}-$dark-single-open")
            compose.onNodeWithText(label(R.string.config_tab_wallpapers)).performClick(); frames()
            assertEquals(2, pagePrefs.getInt("capture_single", -1))
            assertEquals(listOf("capture_single"), changed)
            open(backend, listOf(mask), dark = dark)
            compose.onNodeWithText(label(R.string.config_tab_more)).performClick(); frames()
            capture("dropdown-${backend.name}-$dark-multiple-open")
            compose.onNodeWithText(label(R.string.config_tab_wallpapers)).performClick(); frames()
            assertEquals(3, pagePrefs.getInt("capture_mask", -1))
            assertEquals(listOf("capture_mask"), changed)
            compose.onNodeWithText(label(R.string.config_tab_more)).performClick(); frames()
            compose.onNodeWithText(label(R.string.config_tab_wallpapers)).assertIsOn()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
            assertEquals(3, pagePrefs.getInt("capture_mask", -1))
            assertEquals(1, changed.size)
        }
    }
}
