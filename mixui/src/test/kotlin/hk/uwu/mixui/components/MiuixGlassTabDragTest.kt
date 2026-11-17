package hk.uwu.mixui.components

import org.junit.Assert.*
import org.junit.Test

class MiuixGlassTabDragTest {
    // Native 352dp track: 3dp inset on both edges, 8dp gaps, 80.5dp slots.
    private val geometry = MiuixGlassTabGeometry(4, 346f, 8f)
    private fun drag(selected: Int = 0, rtl: Boolean = false) = MiuixGlassTabDrag(
        geometry, selected, geometry.offsetFor(selected), touchSlop = 8f, rtl = rtl,
    )

    @Test fun geometryRetainsNativeInsetsSlotsAndClampedTargets() {
        assertEquals(80.5f, geometry.slotWidth, 0f)
        assertEquals(265.5f, geometry.maxOffset, 0f)
        assertEquals(0, geometry.target(-400f))
        assertEquals(3, geometry.target(1000f))
        assertEquals(0f, geometry.offsetFor(-1), 0f)
        assertEquals(265.5f, geometry.offsetFor(99), 0f)
    }

    @Test fun horizontalPreviewIsContinuousAndFinishCommitsOnlyOnce() {
        val session = drag()
        session.move(6f, 1f)
        assertFalse(session.isDragging)
        assertNull(session.previewOffset)
        session.move(34f, 1f)
        assertEquals(32f, session.previewOffset!!, 0f)
        session.move(13f, 0f)
        assertEquals(45f, session.previewOffset!!, 0f)
        assertEquals(1, session.finish())
        assertNull(session.finish())
    }

    @Test fun crossingSeveralTabsDoesNotProduceIntermediateSelections() {
        val session = drag()
        for (delta in listOf(50f, 90f, 100f)) {
            session.move(delta, 0f)
            assertTrue(session.isDragging)
        }
        assertEquals(3, session.finish())
        assertNull(session.finish())
    }

    @Test fun cancelDiscardsPreviewAndCannotCommit() {
        val session = drag()
        session.move(180f, 0f)
        assertTrue(session.isDragging)
        session.cancel()
        assertNull(session.previewOffset)
        assertNull(session.finish())
    }

    @Test fun verticalAndDiagonalWinnerNeverBecomeHorizontalLater() {
        for ((x, y) in listOf(3f to 20f, 20f to 20f)) {
            val session = drag()
            session.move(x, y)
            assertTrue(session.isRejected)
            session.move(240f, 0f)
            assertFalse(session.isDragging)
            assertNull(session.finish())
        }
    }

    @Test fun subSlopAndReturnToSelectedDoNotSubmit() {
        val tap = drag()
        tap.move(8f, 3f)
        assertNull(tap.finish())
        val returned = drag(1)
        returned.move(120f, 0f)
        returned.move(-112f, 0f)
        assertNull(returned.finish())
    }

    @Test fun rtlMirrorsPhysicalMovementButKeepsLogicalSelection() {
        val session = drag(rtl = true)
        session.move(-98f, 1f)
        assertEquals(90f, session.previewOffset!!, 0f)
        assertEquals(1, session.finish())
        val reverse = drag(selected = 3, rtl = true)
        reverse.move(1000f, 0f)
        assertEquals(0f, reverse.previewOffset!!, 0f)
        assertEquals(0, reverse.finish())
    }

    @Test fun boundariesClampAndImmediatelyFollowReversal() {
        val session = drag()
        session.move(1000f, 0f)
        assertEquals(geometry.maxOffset, session.previewOffset!!, 0f)
        session.move(-10f, 0f)
        assertEquals(geometry.maxOffset - 10f, session.previewOffset!!, 0f)
        session.move(-1000f, 0f)
        assertEquals(0f, session.previewOffset!!, 0f)
        assertNull(session.finish())
    }

    @Test fun emptySingleAndUnmeasuredTracksCannotDrag() {
        for (value in listOf(
            MiuixGlassTabGeometry(0, 346f, 8f),
            MiuixGlassTabGeometry(1, 346f, 8f),
            MiuixGlassTabGeometry(4, 0f, 8f),
            MiuixGlassTabGeometry(4, 10f, 8f),
        )) {
            assertFalse(value.canDrag)
            val session = MiuixGlassTabDrag(value, 0, 0f, 8f, false)
            session.move(1000f, 0f)
            assertNull(session.previewOffset)
            assertNull(session.finish())
        }
        assertNull(MiuixGlassTabGeometry(0, 346f, 8f).target(0f))
    }

    @Test fun newSessionUsesExternalSelectionAndChangedGeometry() {
        val old = drag()
        old.move(100f, 0f)
        old.cancel()
        assertNull(old.finish())
        val resized = MiuixGlassTabGeometry(3, 500f, 8f)
        val replacement = MiuixGlassTabDrag(resized, 2, resized.offsetFor(2), 8f, false)
        replacement.move(-180f, 0f)
        assertEquals(1, replacement.finish())
    }
}
