package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.components.MiuixScrollBackdropProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draw subscription coverage only, NOT hardware RenderEffect/GPU certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixBackdropInvalidationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun motionInvalidatesSourceAndConsumerAfterAlphaAndGeometryStopChanging() {
        for (live in listOf(false, true)) {
            val motion = mutableFloatStateOf(0f)
            var sourceDraws = 0
            var consumerDraws = 0
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                compose.runOnUiThread {
                    controller.get().setContent {
                        MiuixScrollBackdropProbe(motion, live, { sourceDraws++ }, { consumerDraws++ })
                    }
                }
                compose.waitForIdle()
                compose.onRoot().captureToImage()
                println("initial live=$live source=$sourceDraws consumer=$consumerDraws")
                // Fixed-geometry draw coverage; the real-page tests cover gesture wiring.
                for (offset in listOf(-240f, -480f, -48f, -96f, -24f, 0f)) {
                    val previousSource = sourceDraws
                    val previousConsumer = consumerDraws
                    compose.runOnIdle { motion.floatValue = offset }
                    compose.waitForIdle()
                    compose.onRoot().captureToImage()
                    println("offset=$offset live=$live source=$sourceDraws consumer=$consumerDraws previous=$previousSource/$previousConsumer")
                    if (live) {
                        assertTrue("source draw must follow $offset", sourceDraws > previousSource)
                        assertTrue("consumer draw must follow $offset", consumerDraws > previousConsumer)
                    } else {
                        assertEquals("native baseline has no motion subscription", previousSource, sourceDraws)
                        assertEquals("native baseline has no motion subscription", previousConsumer, consumerDraws)
                    }
                }
            } finally { compose.runOnUiThread { controller.close() }; compose.waitForIdle() }
        }
    }
}
