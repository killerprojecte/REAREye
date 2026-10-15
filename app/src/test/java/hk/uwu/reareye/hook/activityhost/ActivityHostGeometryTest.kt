package hk.uwu.reareye.hook.activityhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityHostGeometryTest {
    private val outer = ActivityHostRect(100, 200, 500, 500)
    private fun resolve(bounds: List<Float>? = null) =
        ActivityHostGeometry.resolve(outer, outer, 2f, 2f, 24f, bounds)

    @Test
    fun guttersUseHostDensityAndBoundsUseMamlScale() {
        val layout = resolve(listOf(10f, 20f, 100f, 120f))!!
        assertEquals(ActivityHostRect(168, 240, 348, 440), layout.taskBounds)
        assertEquals(layout.taskBounds, layout.visibleBounds)
        assertEquals(ActivityHostRect(68, 40, 248, 240), layout.surfaceBounds)
        assertEquals(ActivityHostRect(100, 200, 148, 500), layout.leftNavigationBounds)
        assertEquals(ActivityHostRect(452, 200, 500, 500), layout.rightNavigationBounds)
        assertEquals(ActivityHostRect(148, 200, 452, 500), resolve()!!.taskBounds)
    }

    @Test
    fun zeroGutterUsesCompleteSafeAreaAndEmptyNavigationBounds() {
        val safe = ActivityHostRect(296, 0, 976, 596)
        val layout = ActivityHostGeometry.resolve(safe, safe, 1f, 450f / 160f, 0f)!!
        assertEquals(safe, layout.taskBounds)
        assertEquals(safe, layout.visibleBounds)
        assertEquals(ActivityHostRect(0, 0, 680, 596), layout.surfaceBounds)
        assertEquals(ActivityHostRect(296, 0, 296, 596), layout.leftNavigationBounds)
        assertEquals(ActivityHostRect(976, 0, 976, 596), layout.rightNavigationBounds)
        assertEquals(
            safe, ActivityHostGeometry.resolve(
                safe, safe, 1f, 2f, 0f, listOf(0f, 0f, 680f, 596f)
            )!!.taskBounds
        )
        assertNull(ActivityHostGeometry.resolve(safe, safe, 1f, 2f, 0f, listOf(0f, 0f, 681f, 596f)))
    }

    @Test
    fun clippingDoesNotShrinkTaskOrChangeLocalCoordinates() {
        val layout = ActivityHostGeometry.resolve(
            outer, ActivityHostRect(180, 250, 300, 400),
            2f, 2f, 24f
        )!!
        assertEquals(ActivityHostRect(148, 200, 452, 500), layout.taskBounds)
        assertEquals(ActivityHostRect(180, 250, 300, 400), layout.visibleBounds)
        assertEquals(ActivityHostRect(48, 0, 352, 300), layout.surfaceBounds)
    }

    @Test
    fun negativeScreenOffsetsRemainValid() {
        val area = ActivityHostRect(-500, -300, -100, 0)
        val layout = ActivityHostGeometry.resolve(area, area, 1f, 1f, 24f)!!
        assertEquals(ActivityHostRect(-476, -300, -124, 0), layout.taskBounds)
        assertEquals(ActivityHostRect(24, 0, 376, 300), layout.surfaceBounds)
    }

    @Test
    fun subpixelBoundsRoundOutwardButNeverEscapeTheParent() {
        val layout = resolve(listOf(0.25f, 0.25f, 1.25f, 1.25f))!!
        assertEquals(ActivityHostRect(148, 200, 151, 203), layout.taskBounds)
        assertNull(resolve(listOf(-0.01f, 0f, 1f, 1f)))
        assertNull(resolve(listOf(0f, 0f, 152.01f, 20f)))
        assertNotNull(resolve(listOf(0f, 0f, 152f, 150f)))
        assertNull(resolve(listOf(0f, 0f, 152f, 150.01f)))
    }

    @Test
    fun invalidAndTinyBoundsAreRejected() {
        listOf(
            emptyList(), listOf(0f, 0f, 1f), listOf(0f, 0f, 1f, 1f, 2f),
            listOf(0f, 0f, Float.NaN, 10f), listOf(0f, 0f, Float.POSITIVE_INFINITY, 10f),
            listOf(10f, 0f, 5f, 10f), listOf(0f, 0f, 0f, 10f),
            listOf(0f, 0f, 0.1f, 0.1f)
        ).forEach { assertNull(resolve(it)) }
    }

    @Test
    fun invalidScaleDensityAndGutterAreRejected() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertNull(ActivityHostGeometry.resolve(outer, outer, it, 1f, 24f))
            assertNull(ActivityHostGeometry.resolve(outer, outer, 1f, it, 24f))
            if (it != 0f) assertNull(ActivityHostGeometry.resolve(outer, outer, 1f, 1f, it))
        }
        assertNull(ActivityHostGeometry.resolve(outer, outer, 1f, 1f, 200f))
        assertNull(ActivityHostGeometry.resolve(outer, outer, 1f, Float.MAX_VALUE, Float.MAX_VALUE))
        assertEquals(
            1L, ActivityHostGeometry.resolve(outer, outer, 1f, 1f, 0.1f)!!
                .leftNavigationBounds.width
        )
    }

    @Test
    fun invisibleTouchingAndDegenerateRectsAreRejected() {
        listOf(
            ActivityHostRect(500, 200, 600, 500), ActivityHostRect(0, 0, 100, 100),
            ActivityHostRect(200, 200, 200, 300), ActivityHostRect(300, 200, 200, 300)
        )
            .forEach { assertNull(ActivityHostGeometry.resolve(outer, it, 1f, 1f, 24f)) }
        assertNull(ActivityHostGeometry.resolve(ActivityHostRect(0, 0, 49, 1), outer, 1f, 1f, 24f))
    }

    @Test
    fun integerExtremesCannotOverflowOrWrap() {
        val oversized = ActivityHostRect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 300)
        assertEquals(4294967295L, oversized.width)
        assertNull(ActivityHostGeometry.resolve(oversized, oversized, 1f, 1f, 24f))
        val edge =
            ActivityHostRect(Int.MIN_VALUE, Int.MAX_VALUE - 300, Int.MIN_VALUE + 400, Int.MAX_VALUE)
        val layout = ActivityHostGeometry.resolve(edge, edge, 1f, 1f, 24f)!!
        assertEquals(ActivityHostRect(24, 0, 376, 300), layout.surfaceBounds)
        assertNull(
            ActivityHostGeometry.resolve(
                edge, edge, Float.MAX_VALUE, 1f, 24f,
                listOf(0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE)
            )
        )
    }
}
