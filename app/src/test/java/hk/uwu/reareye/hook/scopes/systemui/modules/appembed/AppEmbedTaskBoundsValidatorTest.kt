package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.content.Intent
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppEmbedTaskBoundsValidatorTest {
    @Test
    fun acceptsReportedNegativeAndFullyOffscreenPhysicalBounds() {
        AppEmbedTaskBoundsValidator.requireValid(976, 596, Rect(0, -552, 976, 44))
        AppEmbedTaskBoundsValidator.requireValid(976, 596, Rect(-2_000, -1_500, -1_024, -904))
        AppEmbedTaskBoundsValidator.requireValid(976, 596, Rect(2_000, 1_500, 2_976, 2_096))
    }

    @Test
    fun resizeKeepsTheSameValidationWithoutConstrainingOrigin() {
        AppEmbedTaskBoundsValidator.requireValid(640, 360, Rect(-320, 700, 320, 1_060))
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedTaskBoundsValidator.requireValid(641, 360, Rect(-320, 700, 320, 1_060))
        }
    }

    @Test
    fun rejectsEmptyOrOverflowedRectDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedTaskBoundsValidator.requireValid(1, 1, Rect(0, 0, 0, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedTaskBoundsValidator.requireValid(
                Int.MAX_VALUE,
                1,
                Rect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 1),
            )
        }
    }

    @Test
    fun collidingRequestCodesStillHaveDistinctPendingIntentFilterIdentity() {
        val firstLedger = AppEmbedCapabilitySessionLedger(capabilityNamespace = 1L)
        val secondLedger = AppEmbedCapabilitySessionLedger(capabilityNamespace = 2L)
        firstLedger.issueSessionId()
        val firstId = firstLedger.issueSessionId()
        val secondId = secondLedger.issueSessionId()
        val baseIntent = Intent("hk.uwu.reareye.TEST")

        assertEquals(
            AppEmbedPendingIntentIdentity.requestCode(firstId),
            AppEmbedPendingIntentIdentity.requestCode(secondId),
        )
        val firstIntent = AppEmbedPendingIntentIdentity.launchIntent(baseIntent, firstId)
        val secondIntent = AppEmbedPendingIntentIdentity.launchIntent(baseIntent, secondId)
        assertFalse(firstIntent.filterEquals(secondIntent))
        assertFalse(firstIntent === baseIntent)
    }
}
