// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.shape

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import ui.feice.mochi.theme.TextBlockMetrics

// Layout alignment lines survive transparent Box/Row/Column wrappers. Parents read them in the
// same measure pass, without onGloballyPositioned callbacks or a second animation chasing bounds.
internal val ConcentricTop = HorizontalAlignmentLine(::min)
internal val ConcentricBottom = HorizontalAlignmentLine(::max)
internal val ConcentricStart = VerticalAlignmentLine(::min)
internal val ConcentricEnd = VerticalAlignmentLine(::max)

/** Corner centers of a rounded surface; null radius means the measured capsule radius. */
internal fun Modifier.mochiConcentricOutline(radius: Dp? = null): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val r = (radius?.toPx() ?: (min(p.width, p.height) / 2f)).coerceIn(0f, min(p.width, p.height) / 2f)
    layout(
        p.width,
        p.height,
        mapOf(
            ConcentricTop to r.roundToInt(),
            ConcentricBottom to (p.height - r).roundToInt(),
            ConcentricStart to r.roundToInt(),
            ConcentricEnd to (p.width - r).roundToInt(),
        ),
    ) { p.place(0, 0) }
}

/** A narrow optical glyph uses its ink center, rather than pretending it is a rounded tile. */
internal fun Modifier.mochiConcentricPoint(shiftY: Float = 0f): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val center = (p.height / 2f + shiftY).roundToInt()
    layout(
        p.width,
        p.height,
        mapOf(
            ConcentricTop to center,
            ConcentricBottom to center,
            ConcentricStart to (p.width / 2f).roundToInt(),
            ConcentricEnd to (p.width / 2f).roundToInt(),
        ),
    ) { p.place(0, 0) }
}

/** Text has typographic edge guides, independent of rounded-element corner centers. */
internal fun Modifier.mochiTextBlockGuides(metrics: TextBlockMetrics): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val (top, bottom) = metrics.edgeCenters(p.height)
    layout(
        p.width,
        p.height,
        mapOf(
            ConcentricTop to top.roundToInt(),
            ConcentricBottom to bottom.roundToInt(),
        ),
    ) { p.place(0, 0) }
}
