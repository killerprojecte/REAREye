package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import org.junit.Assert.assertEquals
import org.junit.Test

class AppEmbedLaunchLayoutTest {
    @Test
    fun movingCardCannotLaunchUntilItsFinalGeometrySettles() {
        val gate = AppEmbedLaunchLayout<Int>()
        assertEquals(100L, gate.remainingMillis(400, 0))
        assertEquals(100L, gate.remainingMillis(200, 50))
        assertEquals(100L, gate.remainingMillis(0, 100))
        assertEquals(1L, gate.remainingMillis(0, 199))
        assertEquals(0L, gate.remainingMillis(0, 200))
        assertEquals(0L, gate.remainingMillis(0, 300))
    }

    @Test
    fun returningCardDoesNotReusePreviousActivationsStability() {
        val gate = AppEmbedLaunchLayout<Int>()
        gate.remainingMillis(0, 0)
        assertEquals(0L, gate.remainingMillis(0, 100))
        gate.reset()
        assertEquals(100L, gate.remainingMillis(0, 200))
    }
}
