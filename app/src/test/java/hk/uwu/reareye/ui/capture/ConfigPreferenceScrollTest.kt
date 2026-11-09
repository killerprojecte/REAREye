package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.*
import hk.uwu.mixui.components.*
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.config.EnumSingleSelectConfigInput
import hk.uwu.reareye.ui.components.config.MaskMultiSelectConfigInput
import hk.uwu.reareye.ui.config.*
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

/** Real configuration inputs and persistence, not a substitute menu or semantic-only click. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigPreferenceScrollTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var scroll: ScrollState
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test fun productionMusicPreferencesScrollWithoutOpeningOrWritingAndTapStillSelects() {
        for (backend in MixUiBackend.entries) for (multiple in listOf(false, true)) for (dark in listOf(false, true)) {
            val prefs = PrefsManager(GuideMemoryPrefs())
            var writes = 0
            val key = if (multiple) ConfigKeys.LYRIC_DISPLAY_MODE else ConfigKeys.LYRIC_PROVIDER
            val title = if (multiple) R.string.lyric_display_mode else R.string.lyric_provider
            val description = if (multiple) R.string.lyric_display_mode_desc else R.string.lyric_provider_desc
            val first = if (multiple) R.string.lyric_display_mode_original else R.string.lyric_provider_lyricon
            val second = if (multiple) R.string.lyric_display_mode_translation else R.string.lyric_provider_superlyric
            val item = ConfigItem(key, title, descriptionRes = description,
                type = ConfigType.EnumSingleSelect(0, emptyList()))
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        GlassPreferenceScene {
                            scroll = rememberScrollState()
                            Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("music-list")) {
                                Spacer(Modifier.height(160.dp))
                                Card {
                                    if (multiple) MaskMultiSelectConfigInput(item, 1,
                                        listOf(ConfigType.MaskOption(first, 1), ConfigType.MaskOption(second, 2)),
                                        prefs, onPreferenceChanged = { writes++ })
                                    else EnumSingleSelectConfigInput(item, 0,
                                        listOf(ConfigType.EnumOption(first, 0), ConfigType.EnumOption(second, 1)),
                                        prefs, onPreferenceChanged = { writes++ })
                                }
                                Spacer(Modifier.height(1600.dp))
                            }
                        }
                    }
                }
            }
            frames()
            val row = compose.onNodeWithText(label(title))
            fun assertNoPopupOrWrites() {
                compose.onNodeWithText(label(second)).assertDoesNotExist()
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                    .assertCountEquals(0)
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
                    .assertCountEquals(0)
                assertEquals("$backend/$multiple must not persist on drag/cancel", 0, writes)
                assertEquals(if (multiple) 1 else 0, prefs.getInt(key, if (multiple) 1 else 0))
            }
            val folder = File(System.getProperty("ui.capture.output"), "music-preference-scroll-fix").apply { mkdirs() }
            fun capture(suffix: String) {
                val bitmap = compose.onNodeWithTag("music-list").captureToImage().asAndroidBitmap()
                File(folder, "${backend.name}-${if (dark) "dark" else "light"}-${if (multiple) "multiple" else "single"}-$suffix.png")
                    .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            capture("initial")
            row.performTouchInput { down(center) }
            frames()
            assertNoPopupOrWrites() // catches native press-trigger before a drag even starts
            row.performTouchInput { cancel() }
            frames()
            assertNoPopupOrWrites()
            row.performTouchInput {
                swipe(center, Offset(center.x, center.y - 120f), durationMillis = 400)
            }
            frames()
            assertNoPopupOrWrites()
            assertTrue("$backend/$multiple must let the parent scroll", scroll.value > 0)
            row.performScrollTo()
            frames()
            row.performTouchInput { click() }
            frames()
            val option = compose.onNodeWithText(label(second))
            option.assertIsDisplayed().performTouchInput { click() }
            frames()
            assertEquals(1, writes)
            assertEquals(if (multiple) 3 else 1, prefs.getInt(key, -1))
            row.performTouchInput { click() }
            frames()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            frames()
            assertEquals("Back must not select again", 1, writes)
        }
    }
}
