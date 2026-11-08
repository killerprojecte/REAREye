// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotion
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.basic.LiquidMenu
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.MochiItemLayout
import ui.feice.mochi.basic.MochiStyle
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.liquid
import ui.feice.mochi.basic.parts.MochiGlyphs
import ui.feice.mochi.effect.glow.MochiGlowDefaults
import ui.feice.mochi.effect.glow.MochiGlowSpec
import ui.feice.mochi.effect.glow.drawGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.shape.continuityFor
import ui.feice.mochi.theme.MochiTheme

/** 按着选项时高亮块缩到多少 */
private const val ItemPressScale = 0.96f

/** 按着选项时高亮块朝手指最多偏多远 */
private val ItemFollow = 3.dp

/**
 * 选择列表的菜单项（下拉菜单和长按菜单共用，视觉和手感完全一致）：
 * - 滑动高亮块：前缘快、后缘慢，滑动时被拉长、横向收窄一点
 * - 按着选项：高亮块缩到 0.96、朝手指偏一点，选项自己亮起高光（比面板的光跟得快）
 * - Material 布局有独立滑动的选中块；iOS 布局对勾在最左
 * - 打开时逐项错开出现；列表能滚动时只组合视口里的几项
 *
 * @param hover 手指此刻在第几项上（-1 = 不在）
 * @param pointer 手指的屏幕坐标（没按着时 null）
 * @param contentOrigin 列表左上角（不含滚动）的屏幕坐标，用来把手指换算到列表里
 * @param effects 内容显影进度（错开出现用）
 * @param scroll 列表滚动量（px）
 * @param window 最多同时组合几项
 */
@Composable
internal fun MochiList(
    items: List<MochiItem>,
    selectedIndex: Int,
    style: MochiStyle,
    px: MochiPx,
    motion: MochiMotion,
    upward: Boolean,
    hover: () -> Int,
    pointer: () -> Offset?,
    contentOrigin: () -> Offset,
    effects: () -> Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scroll: () -> Float = { 0f },
    window: Int = items.size,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    // 玻璃菜单和 iOS 布局一样：对勾在左、选中不铺底色
    val ios = style.itemLayout == MochiItemLayout.Ios || style.liquid
    val itemH = px.itemHeight
    val hlPathCache = remember { G2PathCache() }
    val currentHover by rememberUpdatedState(hover)
    val currentPointer by rememberUpdatedState(pointer)
    var width by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // 高亮块：前缘快、后缘慢 → 滑动时被拉长；拉长时横向收窄一点
    val hlTop = remember { Animatable(0f) }
    val hlBot = remember { Animatable(itemH) }
    val hlAlpha = remember { Animatable(0f) }

    // 选项自己的光：比面板的光跟得快（面板的光慢半拍追过来，两处不同步），只照亮按着的那一项和它的描边。
    // 按着选项时高亮块轻微缩小，并朝手指方向偏一点（跟手形变）
    val itemGlowSpec = remember(style.glowColor) { MochiGlowSpec(style.glowColor, followResponse = 0.16f, followDamping = 0.8f) }
    val itemGlow = rememberMochiGlowState(itemGlowSpec, pointer = { currentPointer() ?: Offset.Zero })
    val hlPress = remember { Animatable(1f) }
    LaunchedEffect(px) {
        snapshotFlow { currentPointer() != null && currentHover() >= 0 }.distinctUntilChanged().collect { on ->
            if (on) {
                launch { itemGlow.press(radius = MochiGlowDefaults.radius(Size(width, itemH), density)) }
                launch { hlPress.animateTo(ItemPressScale, MochiMotions.responseSpring(0.22f, 0.62f)) }
            } else {
                itemGlow.markReleased()
                launch { itemGlow.release() }
                launch { hlPress.animateTo(1f, MochiMotions.responseSpring(0.36f, 0.6f)) }
            }
        }
    }
    LaunchedEffect(px) {
        var prev = -1
        snapshotFlow { currentHover() }.collect { i ->
            val p = prev
            prev = i
            if (i < 0) {
                launch { hlAlpha.animateTo(0f, MochiMotions.responseSpring(0.18f, 1f)) }
                return@collect
            }
            val top = i * itemH
            val bot = top + itemH
            if (p < 0 || hlAlpha.value < 0.05f) {
                hlTop.snapTo(top)
                hlBot.snapTo(bot)
            } else {
                val fast = MochiMotions.responseSpring(0.19f, 0.78f)
                val slow = MochiMotions.responseSpring(0.32f, 0.86f)
                launch { hlTop.animateTo(top, if (i < p) fast else slow) }
                launch { hlBot.animateTo(bot, if (i > p) fast else slow) }
            }
            launch { hlAlpha.animateTo(1f, MochiMotions.responseSpring(0.12f, 1f)) }
        }
    }

    val innerRadius = (style.panelCornerRadius - style.panelPadding).coerceAtLeast(4.dp)
    val innerRadiusPx = with(density) { innerRadius.toPx() }
    val itemShape = remember(innerRadius, style.g2Smoothing) {
        ContinuousRoundedRectangle(innerRadius, continuityFor(style.g2Smoothing))
    }
    val indicatorY by animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0) * itemH,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f), // M3E fast spatial
        label = "indicator",
    )
    val dir = if (upward) 1f else -1f

    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat() }
            .drawBehind {
                val a = hlAlpha.value.coerceIn(0f, 1f)
                if (a <= 0.01f) return@drawBehind
                val top = min(hlTop.value, hlBot.value) - scroll()
                val h = abs(hlBot.value - hlTop.value)
                val s = ((h - itemH) / itemH).coerceIn(0f, 1f)
                val inset = s * 6.dp.toPx()
                // 按着：缩小一点、朝手指（选项的光心）偏一点。半径同比例缩，和面板圆角保持同心
                val k = hlPress.value
                val pressed = ((1f - k) / (1f - ItemPressScale)).coerceIn(0f, 1f)
                val light = itemGlow.center - contentOrigin()
                val base = Rect(inset, top, size.width - inset, top + h)
                val follow = ItemFollow.toPx() * pressed
                val shift = Offset(
                    follow * tanh((light.x - base.center.x) / (base.width / 2f).coerceAtLeast(1f)),
                    follow * tanh((light.y - base.center.y) / (base.height / 2f).coerceAtLeast(1f)),
                )
                val half = Offset(base.width * k / 2f, base.height * k / 2f)
                val r = Rect(base.center - half, base.center + half).translate(shift)
                val path = hlPathCache.get(r, (innerRadiusPx + s * 6.dp.toPx()) * k, style.g2Smoothing)
                drawPath(path, style.highlightColor, alpha = a)
                drawGlow(itemGlow, Outline.Generic(path), light, intensityScale = a, base = style.highlightColor)
            },
    ) {
        // 选中块（Material 布局）：独立一层，用 M3E fast spatial 弹簧滑动
        if (!ios && selectedIndex in items.indices) {
            Box(
                Modifier
                    .offset { IntOffset(0, (indicatorY - scroll()).roundToInt()) }
                    .fillMaxWidth()
                    .height(style.itemHeight)
                    .background(style.selectedColor, itemShape),
            )
        }
        // 只组合视口里（加上橡皮筋多露出的）那几项
        val first by remember(itemH) { derivedStateOf { (scroll().coerceAtLeast(0f) / itemH).toInt() } }
        val from = first.coerceIn(0, max(0, items.size - 1))
        val until = min(items.size, from + window)
        Column(
            Modifier
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset { IntOffset(0, (from * itemH - scroll()).roundToInt()) },
        ) {
            val radio = selectedIndex >= 0
            val icons = items.any { it.icon != null }
            for (i in from until until) {
                key(i) {
                    val item = items[i]
                    val selected = i == selectedIndex
                    val base = if (item.destructive) style.destructiveColor else style.onPanelColor
                    val color by animateColorAsState(if (selected && !ios) style.onSelectedColor else base, label = "itemColor")
                    val row = Modifier
                        // 不错开出现的曲线不给每一行单独开 layer
                        .then(
                            if (motion.stagger > 0f) {
                                Modifier.graphicsLayer {
                                    val t = staggered(effects(), i, items.size, motion.stagger)
                                    alpha = t
                                    translationY = (1f - t) * dir * 8.dp.toPx()
                                }
                            } else {
                                Modifier
                            },
                        )
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            onClick {
                                onSelect(i)
                                true
                            }
                        }
                        .fillMaxWidth()
                        .height(style.itemHeight)
                    if (style.liquid) {
                        LiquidRow(item, color, selected, radio, icons, row)
                    } else {
                        Row(
                            row.padding(start = if (ios) 12.dp else 16.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (ios) {
                                // 勾在最左，单选菜单里每行都留位置，文字才对齐
                                if (radio) {
                                    Box(Modifier.size(20.dp)) { if (selected) MochiGlyphs.Check(color) }
                                    Spacer(Modifier.width(10.dp))
                                }
                                ItemLabel(item.label, color, Modifier.weight(1f))
                                if (item.icon != null) {
                                    Spacer(Modifier.width(10.dp))
                                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { item.icon(color) }
                                }
                            } else {
                                if (item.icon != null) {
                                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { item.icon(color) }
                                    Spacer(Modifier.width(12.dp))
                                }
                                ItemLabel(item.label, color, Modifier.weight(1f))
                                if (selected) MochiGlyphs.Check(color)
                            }
                        }
                    }
                }
            }
        }
        overlay()
    }
}

/**
 * 玻璃菜单的一行（iOS 27 _UIContextMenuLargeCellLayout / LargeLeadingImageCellLayout 的约束，数值见 [LiquidMenu]）：
 * 左边距 l = 28，单选菜单再加 15 给对勾；对勾中心在 l / 2 + 6；有图标的菜单图标在文字前，中心 l + 40 / 2 − 8，文字从 l + 4 + 40 − 8 开始。
 * iOS 的行占满菜单宽，Mochi 的列表整体缩进 [LiquidMenu.Inset]，这里都减掉它
 */
@Composable
private fun LiquidRow(item: MochiItem, color: Color, selected: Boolean, radio: Boolean, icons: Boolean, modifier: Modifier = Modifier) {
    val m = LiquidMenu
    val l = m.Padding + if (radio) m.SelectionIndent else 0.dp
    Box(modifier.padding(end = m.Padding - m.Inset), contentAlignment = Alignment.CenterStart) {
        if (selected) {
            Box(Modifier.offset(x = l / 2 + m.CheckOffset - m.Inset - CheckSize / 2).size(CheckSize)) { MochiGlyphs.Check(color) }
        }
        if (item.icon != null) {
            Box(
                Modifier.offset(x = l + m.ImageBox / 2 + m.ImageOffset - m.Inset - IconSize / 2).size(IconSize),
                contentAlignment = Alignment.Center,
            ) { item.icon(color) }
        }
        val start = if (icons) l + m.ImageGap + m.ImageBox + m.ImageOffset else l
        ItemLabel(item.label, color, Modifier.padding(start = start - m.Inset))
    }
}

private val CheckSize = 20.dp
private val IconSize = 22.dp

@Composable
private fun ItemLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        style = MochiTheme.textStyles.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        minFontScale = 0.85f,
        modifier = modifier,
    )
}
