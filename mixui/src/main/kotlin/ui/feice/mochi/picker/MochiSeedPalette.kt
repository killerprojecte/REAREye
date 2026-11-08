// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.picker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiSheetDetent
import ui.feice.mochi.basic.rememberMochiSheetState
import ui.feice.mochi.effect.elastic.mochiPressDeform
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.PaletteStyle
import ui.feice.mochi.theme.generateColorScheme
import ui.feice.mochi.utils.rememberMochiHaptics

/** 莫奈色板的默认值 */
public object MochiSeedPaletteDefaults {
    /** 色块直径（≥ 44dp 的触控区域） */
    public val SwatchSize: Dp = 52.dp

    /** 色块之间最小的横向间距 */
    public val MinGap: Dp = 12.dp

    /** 一组挑过的种子色：色相均匀铺开，生成的配色明度、彩度都舒服 */
    public val Seeds: ImmutableList<Color> = persistentListOf(
        Color(0xFF2B6A5A),
        Color(0xFF3C7D8C),
        Color(0xFF4A6FA5),
        Color(0xFF6750A4),
        Color(0xFFB0507A),
        Color(0xFFD97757),
        Color(0xFFC08A2B),
        Color(0xFF5E8C31),
        Color(0xFF8C5A3C),
        Color(0xFF6B6B7B),
    )
}

/**
 * 莫奈色板（Android 的「壁纸与风格」取色）：每个色块是种子色生成的配色里的主色、辅助色、第三色
 * （上半圆主色，下面两块四分之一圆），看得出整套配色的感觉，而不只是一个颜色。
 *
 * 选中的色块往里缩出一圈缝，外面亮起主色描边环（和框式头像同一个语言）；切换时轻震一下，主题配色由
 * [ui.feice.mochi.theme.MochiTheme] 平滑过渡。最后一个是自定义：彩虹色块，点开 [MochiColorPickerSheet]
 * 选任意种子色，拖动时色块实时预览，关掉面板才应用到主题，不会边拖边整页重算。
 *
 * @param selected 当前种子色；不在 [seeds] 里时视为自定义色；null = 都没选（比如正在跟随壁纸取色）
 * @param style 色块预览用的取色算法，应和主题一致
 */
@Composable
public fun MochiSeedPalette(
    selected: Color?,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
    seeds: ImmutableList<Color> = MochiSeedPaletteDefaults.Seeds,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    custom: Boolean = true,
    swatchSize: Dp = MochiSeedPaletteDefaults.SwatchSize,
) {
    val haptics = rememberMochiHaptics()
    val sheet = rememberMochiSheetState(listOf(MochiSheetDetent.Medium, MochiSheetDetent.Large))
    // 自定义色：外面选中了不在色板里的颜色时就是它；面板里拖动时先只改这里
    var draft by remember { mutableStateOf(selected?.takeIf { it !in seeds }) }
    val pick: (Color) -> Unit = {
        if (it != selected) {
            haptics.tick()
            onSelect(it)
        }
    }
    val count = seeds.size + if (custom) 1 else 0
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val minGap = MochiSeedPaletteDefaults.MinGap
        val columns = ((maxWidth + minGap) / (swatchSize + minGap)).toInt().coerceIn(1, count)
        // 每行等距铺满，最后一行和上面的列对齐（不居中）
        val gap = if (columns > 1) (maxWidth - swatchSize * columns) / (columns - 1) else 0.dp
        Column(verticalArrangement = Arrangement.spacedBy(min(gap, 16.dp))) {
            (0 until count).chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { i ->
                        if (i < seeds.size) {
                            val seed = seeds[i]
                            MochiSeedSwatch(seed, selected == seed, { pick(seed) }, Modifier.size(swatchSize), style = style)
                        } else {
                            val c = draft
                            MochiSeedSwatch(
                                seed = c,
                                selected = c != null && selected == c,
                                onClick = { sheet.show() },
                                modifier = Modifier.size(swatchSize),
                                style = style,
                                rainbow = true,
                            )
                        }
                    }
                }
            }
        }
    }
    if (custom) {
        MochiColorPickerSheet(
            state = sheet,
            color = draft ?: selected ?: seeds.first(),
            onColorChange = { draft = it.copy(alpha = 1f) },
            title = "自定义主题色",
            supportsAlpha = false,
            onDismiss = { draft?.let(pick) },
        )
    }
}

/**
 * 一个莫奈色块。[seed] 为 null 时画成彩虹圆（自定义入口）；[rainbow] 时外环用彩虹色。
 */
@Composable
public fun MochiSeedSwatch(
    seed: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    rainbow: Boolean = false,
) {
    // 预览用深色配色的 80 色阶：三个颜色亮度相近，彩度柔和，在深浅底上都好看
    val tones = remember(seed, style) {
        seed?.let { generateColorScheme(it, isDark = true, style = style, contrastLevel = 0.0) }
            ?.let { Triple(it.primary, it.secondary, it.tertiary) }
    }
    val ring = MochiTheme.colorScheme.primary
    val sel by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 520f), label = "swatch")
    Canvas(
        modifier
            .mochiPressDeform()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics {
                role = Role.Button
                this.selected = selected
                contentDescription = if (seed == null) "自定义颜色" else "主题色"
            },
    ) {
        val r = size.minDimension / 2f
        val stroke = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        // 选中：色块缩小留出一圈缝，外环淡入；外环和色块同心
        val inner = r - (stroke + gap) * sel
        if (rainbow) {
            drawCircle(Brush.sweepGradient(RainbowStops), radius = r - stroke / 2f, style = Stroke(stroke))
        } else if (sel > 0f) {
            drawCircle(ring.copy(alpha = sel.coerceIn(0f, 1f)), radius = r - stroke / 2f, style = Stroke(stroke))
        }
        val disc = if (rainbow) minOf(inner, r - stroke - gap) else inner
        if (tones == null) {
            drawCircle(Brush.sweepGradient(RainbowStops), radius = disc)
            drawPlus(disc * 0.42f, Color.White)
        } else {
            drawMonet(disc, tones.first, tones.second, tones.third)
        }
    }
}

/** 上半圆主色，左下、右下两个四分之一圆是辅助色和第三色 */
private fun DrawScope.drawMonet(radius: Float, top: Color, left: Color, right: Color) {
    val c = center
    val tl = Offset(c.x - radius, c.y - radius)
    val s = Size(radius * 2f, radius * 2f)
    drawArc(top, 180f, 180f, useCenter = true, topLeft = tl, size = s)
    drawArc(left, 90f, 90f, useCenter = true, topLeft = tl, size = s)
    drawArc(right, 0f, 90f, useCenter = true, topLeft = tl, size = s)
}

private fun DrawScope.drawPlus(half: Float, color: Color) {
    val w = 2.5.dp.toPx()
    drawLine(color, Offset(center.x - half, center.y), Offset(center.x + half, center.y), w, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - half), Offset(center.x, center.y + half), w, StrokeCap.Round)
}
