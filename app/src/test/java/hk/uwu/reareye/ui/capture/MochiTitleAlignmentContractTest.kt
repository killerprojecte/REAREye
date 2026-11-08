package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.LargeTitleColumnScaffold
import hk.uwu.mixui.components.LargeTitleLazyScaffold
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ui.feice.mochi.theme.MochiTheme

/** Public native-scaffold comparisons, including the deliberately unchanged RTL boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalFoundationApi::class)
class MochiTitleAlignmentContractTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val observed = mutableMapOf<String, TextStyle>()

    @Composable private fun Probe(key: String) {
        val style = MochiTheme.textStyles.largeTitle
        SideEffect { observed[key] = style }
        Box(Modifier.height(24.dp))
    }

    private fun open(adapter: Boolean, lazy: Boolean, title: String, direction: LayoutDirection, fontScale: Float) {
        observed.clear()
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                NativeTitleTestEnvironment(direction, fontScale) {
                    MixUiHost(MixUiBackend.Mochi, MixColorSchemeMode.Light) {
                        val styles = MochiTheme.textStyles
                        MochiTheme(
                            colors = MochiTheme.colorScheme,
                            textStyles = styles.copy(largeTitle = styles.largeTitle.copy(
                                letterSpacing = 0.25.sp,
                                textDirection = TextDirection.Content,
                            )),
                            systemBarAppearance = false,
                        ) {
                            if (lazy) {
                                if (adapter) {
                                    LargeTitleLazyScaffold(title, navigationIcon = { Probe("navigation") },
                                        actions = { Probe("actions") }, searchBar = { Probe("search") }) {
                                        item { Probe("item") }
                                        items(2) { Probe("items-$it") }
                                        stickyHeader { Probe("sticky") }
                                    }
                                } else {
                                    ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold(title,
                                        navigationIcon = { Probe("navigation") }, actions = { Probe("actions") },
                                        searchBar = { Probe("search") }) {
                                        item { Probe("item") }
                                        items(2) { Probe("items-$it") }
                                        stickyHeader { Probe("sticky") }
                                    }
                                }
                            } else if (adapter) {
                                LargeTitleColumnScaffold(title, navigationIcon = { Probe("navigation") },
                                    actions = { Probe("actions") }) { Probe("body") }
                            } else {
                                ui.feice.mochi.navigation.MochiLargeTitleScaffold(title,
                                    navigationIcon = { Probe("navigation") }, actions = { Probe("actions") }) { Probe("body") }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun close() = compose.runOnUiThread { activity?.close(); activity = null }

    @Test fun suppliedSlotsKeepTheUnmodifiedCallerTypography() {
        for (lazy in listOf(false, true)) {
            open(false, lazy, "设置 Settings", LayoutDirection.Ltr, 1.3f)
            val native = observed.toMap()
            open(true, lazy, "设置 Settings", LayoutDirection.Ltr, 1.3f)
            assertTrue(native.isNotEmpty())
            assertEquals("Every native caller slot, including lazy items/sticky headers, keeps its original style", native, observed)
            val expanded = compose.nativeTitleGeometry("设置 Settings").maxBy { it.layout.layoutInput.style.fontSize.value }
            assertEquals(TextDirection.Content, expanded.layout.layoutInput.style.textDirection)
            assertEquals(0.25.sp, expanded.layout.layoutInput.style.letterSpacing)
            assertTrue(expanded.layout.layoutInput.style.textIndent != null)
        }
    }

    @Test fun rtlUiAndRtlLeadingMixedTextRetainNativeParagraphsWithoutForcingDirection() {
        val cases = listOf(
            LayoutDirection.Rtl to "REAREye العربية",
            LayoutDirection.Rtl to "العربية REAREye",
            LayoutDirection.Ltr to "العربية REAREye",
        )
        for (lazy in listOf(false, true)) for ((direction, title) in cases) {
            open(false, lazy, title, direction, 1.3f)
            val native = compose.nativeTitleGeometry(title).maxBy { it.layout.layoutInput.style.fontSize.value }
            open(true, lazy, title, direction, 1.3f)
            val adapted = compose.nativeTitleGeometry(title).maxBy { it.layout.layoutInput.style.fontSize.value }
            assertEquals(native.layout.layoutInput.style, adapted.layout.layoutInput.style)
            assertEquals(native.layout.getParagraphDirection(0), adapted.layout.getParagraphDirection(0))
            assertEquals(native.paragraphLeft, adapted.paragraphLeft, 0.5f)
            assertEquals(native.paragraphRight, adapted.paragraphRight, 0.5f)
            for (index in title.indices) {
                assertEquals(native.layout.getBoundingBox(index), adapted.layout.getBoundingBox(index))
            }
        }
    }
}
