// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.isSpecified

/**
 * 字体大写字母的墨迹高度与文字行框的关系。用于符号尺寸，不作为多语言文字的边缘留白。
 * 列表中的箭头、对勾等属于整行，按整行居中，不能用标题的 cap-band 决定其竖向位置。
 * @property capHeight 当前字体大写字母的实际墨迹高度（px）
 * @property shift 大写字母那一段的中心相对文字行框中心偏了多少（px，正 = 偏下），不是列表行级图标的位移
 */
internal class CapBand(val capHeight: Float, val shift: Float)

/** Updated by BasicText during measurement, then read by its enclosing layout in the same pass. */
internal class TextBlockMetrics {
    private var result: TextLayoutResult? = null

    fun update(layout: TextLayoutResult) {
        result = layout
    }

    // Line boxes include resolved fallback-font ascent/descent. Unlike per-word ink bounds,
    // they stay stable when letters change between uppercase, lowercase and descenders.
    fun edgeCenters(height: Int): Pair<Float, Float> {
        val r = result ?: return height / 2f to height / 2f
        if (r.lineCount == 0) return height / 2f to height / 2f
        fun center(line: Int) = ((r.getLineTop(line) + r.getLineBottom(line)) / 2f).coerceIn(0f, height.toFloat())
        return center(0) to center(r.lineCount - 1)
    }
}

@Composable
internal fun rememberCapBand(style: TextStyle): CapBand {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    return remember(style, density, resolver) {
        val r = measurer.measure("H", style)
        val em = with(density) { if (style.fontSize.isSpecified) style.fontSize.toPx() else 16f * density.density }
        // 用这个字体里「H」实际的墨迹上下边：不同字体的大写字母在行框里高低不一样，按比例估会偏
        val typeface = resolver.resolve(
            style.fontFamily,
            style.fontWeight ?: FontWeight.Normal,
            style.fontStyle ?: FontStyle.Normal,
            style.fontSynthesis ?: FontSynthesis.All,
        ).value as? Typeface
        val ink = AndroidRect()
        Paint().apply {
            this.typeface = typeface
            textSize = em
        }.getTextBounds("H", 0, 1, ink)
        val cap = if (ink.height() > 0) -ink.top.toFloat() else em * 0.7f
        CapBand(cap, r.firstBaseline - cap / 2f - r.size.height / 2f)
    }
}

/**
 * 大号粗体文字的光学左对齐：字形自带左侧空白（约 0.04em），字号越大越明显，看起来比上面的图标、下面的卡片往里缩一点。
 * 往左挪掉这一点，和别的元素的左边在视觉上对齐（只改图层，不影响布局）。
 */
public fun Modifier.mochiOpticalLeading(style: TextStyle): Modifier = graphicsLayer {
    if (style.fontSize.isSpecified) translationX = -style.fontSize.toPx() * LeadingBearingEm
}

private const val LeadingBearingEm = 0.04f
