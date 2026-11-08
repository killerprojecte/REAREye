// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import ui.feice.mochi.basic.MochiMeasuredText
import ui.feice.mochi.effect.content.MochiBlurCrossfade
import ui.feice.mochi.shape.mochiTextBlockGuides
import ui.feice.mochi.theme.TextBlockMetrics

/** Keep real text layout guides attached through row wrappers and content transitions. */
@Composable
internal fun ListText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val metrics = remember { TextBlockMetrics() }
    val onLayout = remember(metrics) { metrics::update }
    MochiMeasuredText(
        text,
        modifier.mochiTextBlockGuides(metrics),
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = onLayout,
    )
}

@Composable
internal fun ListBlurText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    MochiBlurCrossfade(text, modifier, contentAlignment = Alignment.CenterEnd) {
        ListText(it, color = color, style = style)
    }
}
