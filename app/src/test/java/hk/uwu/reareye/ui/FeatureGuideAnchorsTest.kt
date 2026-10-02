package hk.uwu.reareye.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class FeatureGuideAnchorsTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null

    @After
    fun tearDown() {
        compose.runOnUiThread { activity?.close() }
    }

    @Test
    fun anchorTracksActualSizeAndParentPadding() {
        val anchors = FeatureGuideAnchors()
        val size = mutableStateOf(40.dp)
        val padding = mutableStateOf(12.dp)
        var density = 1f
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent {
                density = LocalDensity.current.density
                CompositionLocalProvider(LocalFeatureGuideAnchors provides anchors) {
                    Box(Modifier.padding(padding.value)) {
                        Box(Modifier
                            .size(size.value)
                            .featureGuideAnchor("target"))
                    }
                }
            }
        }
        compose.runOnIdle {
            val rect = anchors.coordinates.getValue("target").boundsInRoot()
            assertEquals(40f * density, rect.width, 1f)
            assertEquals(12f * density, rect.left, 1f)
            size.value = 96.dp
            padding.value = 24.dp
        }
        compose.runOnIdle {
            val rect = anchors.coordinates.getValue("target").boundsInRoot()
            assertEquals(96f * density, rect.width, 1f)
            assertEquals(24f * density, rect.left, 1f)
        }
    }

    @Test
    fun anchorRekeysWithoutRelayoutAndIsRemovedOnDisposal() {
        val anchors = FeatureGuideAnchors()
        val key = mutableStateOf("cards")
        val visible = mutableStateOf(true)
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent {
                CompositionLocalProvider(LocalFeatureGuideAnchors provides anchors) {
                    if (visible.value) Box(Modifier
                        .size(40.dp)
                        .featureGuideAnchor(key.value))
                }
            }
        }
        compose.runOnIdle {
            assertNotNull(anchors.coordinates["cards"])
            key.value = "wallpapers"
        }
        compose.runOnIdle {
            assertFalse(anchors.coordinates.containsKey("cards"))
            assertNotNull(anchors.coordinates["wallpapers"])
            visible.value = false
        }
        compose.runOnIdle { assertEquals(0, anchors.coordinates.size) }
    }
}
