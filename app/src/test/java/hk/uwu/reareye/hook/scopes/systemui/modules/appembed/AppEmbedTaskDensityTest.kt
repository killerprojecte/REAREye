package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppEmbedTaskDensityTest {
    @Test
    fun physicalBoundsConvertToConsistentTaskResourceDimensions() {
        assertEquals(
            AppEmbedTaskDensity(
                densityDpi = 320,
                screenWidthDp = 500,
                screenHeightDp = 900,
                smallestScreenWidthDp = 500,
            ),
            AppEmbedTaskDensityCalculator.calculate(Rect(20, 40, 1020, 1840), 320),
        )
    }

    @Test
    fun conversionRoundsToNearestDpAndNeverReturnsZero() {
        assertEquals(
            411,
            AppEmbedTaskDensityCalculator.calculate(Rect(0, 0, 1080, 1), 420).screenWidthDp
        )
        assertEquals(
            1,
            AppEmbedTaskDensityCalculator.calculate(Rect(0, 0, 1, 1080), 640).screenWidthDp
        )
    }

    @Test
    fun invalidBoundsOrDensityFailFast() {
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedTaskDensityCalculator.calculate(Rect(), 320)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedTaskDensityCalculator.calculate(Rect(0, 0, 100, 100), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedTaskDensityCalculator.calculate(
                Rect(0, 0, Int.MAX_VALUE, 1),
                densityDpi = 1,
            )
        }
    }

    @Test
    fun registryUsesControllerIdentityAndBinderStyleTokenEquality() {
        val registry = AppEmbedTaskDensityRegistry()
        val controller = Any()
        val firstToken = EqualToken(7)
        val parcelledToken = EqualToken(7)

        val binding = registry.register(controller, taskId = 81, firstToken, densityDpi = 280)

        assertSame(binding, registry.find(parcelledToken))
        registry.remove(controller)
        assertNull(registry.find(firstToken))
    }

    @Test
    fun registryRejectsControllerTokenOrTaskIdentityChanges() {
        val registry = AppEmbedTaskDensityRegistry()
        val controller = Any()
        val token = EqualToken(1)
        registry.register(controller, taskId = 91, token, densityDpi = 300)

        assertThrows(IllegalStateException::class.java) {
            registry.register(controller, taskId = 91, EqualToken(2), densityDpi = 300)
        }
        assertThrows(IllegalStateException::class.java) {
            registry.register(Any(), taskId = 91, EqualToken(1), densityDpi = 300)
        }
    }

    private data class EqualToken(val id: Int)
}
