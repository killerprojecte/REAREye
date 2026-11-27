package hk.uwu.mixui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuixPageVisualOffsetTest {
    @Test fun firstItemUsesExactPixelsInsteadOfBooleanSentinel() {
        for (offset in listOf(0, 1, 8, 24, 48)) {
            val distance = miuixListScrollDistance(0, offset, 80f)
            assertEquals(offset.toFloat(), distance, 0f)
            assertEquals(-offset.toFloat(), miuixPageVisualOffset(distance, 0f, 0f), 0f)
        }
    }

    @Test fun recycledItemsUseMeasuredSpanForDeepProgrammaticJumps() {
        val distance = miuixListScrollDistance(12, 7, 88f)
        assertEquals(1063f, distance, 0f)
        assertTrue(miuixPageVisualOffset(distance, 0f, 0f) < -48f)
    }

    @Test fun shortListCollapseAndElasticReturnUseNativeDisplacement() {
        assertEquals(-24f, miuixPageVisualOffset(0f, -24f, 0f), 0f)
        assertEquals(-12f, miuixPageVisualOffset(0f, -24f, 12f), 0f)
        assertEquals(8f, miuixPageVisualOffset(0f, 0f, 8f), 0f)
        assertEquals(0f, miuixPageVisualOffset(0f, 0f, 0f), 0f)
    }

    @Test fun coldLoadAndAnimatedPositionRestoreOnlyOnceAfterCompletion() {
        val gate = MiuixPagePositionRestoreGate()
        assertFalse(gate.observe(0, 0, false, false, false, false))
        assertFalse(gate.observe(0, 0, false, true, false, false))
        assertFalse(gate.observe(3, 20, true, true, false, true))
        assertFalse(gate.observe(12, 0, true, true, false, true))
        assertTrue(gate.observe(12, 0, false, true, false, true))
        repeat(20) { assertFalse(gate.observe(12, 0, false, true, false, true)) }
        assertTrue(gate.observe(0, 0, false, true, false, false))
        assertFalse(gate.observe(0, 0, false, true, false, false))
    }

    @Test fun shortNativeGesturesAndIdleFramesNeverForceTitleExpansion() {
        val gate = MiuixPagePositionRestoreGate()
        assertFalse(gate.observe(0, 0, false, true, false, false))
        assertFalse(gate.observe(0, 0, false, true, true, false))
        assertFalse(gate.observe(0, 0, false, true, false, false))
        repeat(20) { assertFalse(gate.observe(0, 0, false, true, false, false)) }
        assertFalse(gate.observe(2, 8, true, true, true, true))
        assertFalse(gate.observe(2, 8, false, true, false, true))
    }

    @Test fun selectedRestoredListDefersTitleCorrectionUntilAnimationStops() {
        val gate = MiuixPagePositionRestoreGate()
        assertFalse(gate.observe(8, 0, true, true, false, true))
        assertTrue(gate.observe(8, 0, false, true, false, true))
        assertFalse(gate.observe(8, 0, false, true, false, true))
        assertTrue(MiuixPagePositionRestoreGate().observe(8, 0, false, true, false, true))
    }

    @Test fun returningToTopCannotRetainPreviousListsNativeContentOffset() {
        val scrolled = miuixPageVisualOffset(miuixListScrollDistance(8, 0, 80f), -60f, 0f)
        assertTrue(scrolled < -48f)
        assertEquals(0f, miuixPageVisualOffset(miuixListScrollDistance(0, 0, 80f), 0f, 0f), 0f)
    }
}
