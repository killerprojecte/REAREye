// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // 只在滚筒组内部用，告诉列不画自己的胶囊

package ui.feice.mochi.picker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.Text
import ui.feice.mochi.shape.drawG2RoundRect
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.rememberMochiHaptics

/** 滚筒的颜色 */
@Immutable
public data class MochiWheelPickerColors(
    /** 文字 */
    val content: Color,
    /** 不可选的项（比如 2 月的 30、31 日） */
    val disabledContent: Color,
    /** 中间那条选中胶囊 */
    val selection: Color,
)

/** 滚筒的默认值 */
public object MochiWheelPickerDefaults {
    /** 每一格的高度 */
    public val ItemHeight: Dp = 36.dp

    /** 可见的格数（奇数，中间一格是选中项） */
    public const val VisibleCount: Int = 5

    /** 选中胶囊左右离滚筒边的距离 */
    public val SelectionInset: Dp = 0.dp

    /** 默认颜色：文字取 onSurface，胶囊取 [MochiDefaults.fill] */
    @Composable
    public fun colors(
        content: Color = MochiTheme.colorScheme.onSurface,
        disabledContent: Color = MochiTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        selection: Color = MochiDefaults.fill(),
    ): MochiWheelPickerColors = remember(content, disabledContent, selection) {
        MochiWheelPickerColors(content, disabledContent, selection)
    }

    /** 滚筒文字：比正文大一号（iOS 的 UIPickerView 用 22pt 左右） */
    @Composable
    public fun textStyle(): TextStyle = MochiTheme.textStyles.body.copy(fontSize = 21.sp, lineHeight = 26.sp)
}

/** 放在 [MochiWheelPickerGroup] 里的滚筒不画自己的胶囊，由组统一画一条 */
private val LocalWheelGrouped = staticCompositionLocalOf { false }

/**
 * 通用滚筒选择器（iOS 的 UIPickerView 单列）：
 * - 每一项摆在圆柱上，越往上下越扁、越淡；中间一条胶囊标出选中项
 * - 拖动 1:1 跟手，松手按惯性滚动并吸附到最近一格；首末项外拉有橡皮筋（[cyclic] 时无限循环）
 * - 每滚过一格轻震一下（iOS 的 selection feedback）；点上下的项直接滚过去
 * - 停稳后才回调 [onSelectedIndexChange]；外部改了 [selectedIndex] 用动画滚过去，不瞬跳
 *
 * @param disabled 返回 true 的项变灰，仍可滚到（调用方在回调里把值改回合法项，滚筒会自己滚回去，比如 2 月 31 日）
 * @param label 固定在选中行右边的单位（比如「小时」），不跟着滚
 * @param alignment 文字在列里的对齐
 */
@Composable
public fun MochiWheelPicker(
    items: ImmutableList<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    cyclic: Boolean = false,
    visibleCount: Int = MochiWheelPickerDefaults.VisibleCount,
    itemHeight: Dp = MochiWheelPickerDefaults.ItemHeight,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    label: String? = null,
    disabled: (Int) -> Boolean = { false },
    colors: MochiWheelPickerColors = MochiWheelPickerDefaults.colors(),
    textStyle: TextStyle = MochiWheelPickerDefaults.textStyle(),
) {
    val grouped = LocalWheelGrouped.current
    val wheel: @Composable (Modifier, Alignment.Horizontal) -> Unit = { m, a ->
        WheelColumn(items, selectedIndex, onSelectedIndexChange, cyclic, visibleCount, itemHeight, a, disabled, colors, textStyle, m)
    }
    val height = itemHeight * visibleCount
    Box(
        modifier
            .height(height)
            .then(if (grouped) Modifier else Modifier.drawSelection(colors.selection, itemHeight)),
    ) {
        if (label == null) {
            wheel(Modifier.fillMaxWidth(), alignment)
        } else {
            Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
                wheel(Modifier.weight(1f), Alignment.End)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MochiTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold), color = colors.content, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/**
 * 多列滚筒（iOS 的 UIPickerView 多列，时间 / 日期选择器就是这样拼的）：里面的 [MochiWheelPicker] 共用一条选中胶囊，
 * 每列宽度由调用方用 `Modifier.weight` 分。各列的格高和格数要一样。
 */
@Composable
public fun MochiWheelPickerGroup(
    modifier: Modifier = Modifier,
    itemHeight: Dp = MochiWheelPickerDefaults.ItemHeight,
    colors: MochiWheelPickerColors = MochiWheelPickerDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier.drawSelection(colors.selection, itemHeight)) {
        CompositionLocalProvider(LocalWheelGrouped provides true) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

private fun Modifier.drawSelection(color: Color, itemHeight: Dp): Modifier = drawWithCache {
    val h = itemHeight.toPx()
    val inset = MochiWheelPickerDefaults.SelectionInset.toPx()
    val scratch = Path()
    val rect = Rect(inset, (size.height - h) / 2f, size.width - inset, (size.height + h) / 2f)
    onDrawBehind { drawG2RoundRect(scratch, rect, h / 2f * SelectionCornerFraction, color) }
}

@Composable
private fun WheelColumn(
    items: ImmutableList<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    cyclic: Boolean,
    visibleCount: Int,
    itemHeight: Dp,
    alignment: Alignment.Horizontal,
    disabled: (Int) -> Boolean,
    colors: MochiWheelPickerColors,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val itemPx = with(density) { itemHeight.toPx() }
    val count = items.size
    val heightPx = itemPx * visibleCount
    val radius = WheelGeometry.radius(heightPx)
    val offset = remember { Animatable(WheelGeometry.offsetFor(selectedIndex.coerceAtLeast(0), 0f, itemPx, count, cyclic)) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val onChange by rememberUpdatedState(onSelectedIndexChange)
    val currentSelected by rememberUpdatedState(selectedIndex)

    fun settledIndex(): Int {
        val c = WheelGeometry.centerIndex(offset.value, itemPx)
        return if (cyclic) WheelGeometry.wrap(c, count) else c.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    suspend fun settle(velocity: Float) {
        val decay = splineBasedDecay<Float>(density)
        val target = decay.calculateTargetValue(offset.value, velocity)
        val snapped = WheelGeometry.snap(target, itemPx, count, cyclic)
        offset.animateTo(snapped, spring(dampingRatio = 1f, stiffness = SettleStiffness), initialVelocity = velocity)
        val i = settledIndex()
        if (i != currentSelected) onChange(i)
    }

    // 每滚过一格轻震
    LaunchedEffect(itemPx) {
        snapshotFlow { WheelGeometry.centerIndex(offset.value, itemPx) }.drop(1).collect { haptics.tick() }
    }
    // 外部改了选中项：滚过去
    LaunchedEffect(selectedIndex, count, cyclic, itemPx) {
        if (dragging || count == 0) return@LaunchedEffect
        if (settledIndex() == selectedIndex && !offset.isRunning) return@LaunchedEffect
        val target = WheelGeometry.offsetFor(selectedIndex.coerceIn(0, count - 1), offset.value, itemPx, count, cyclic)
        offset.animateTo(target, spring(dampingRatio = 1f, stiffness = SettleStiffness))
    }

    val center by remember(itemPx) { derivedStateOf { WheelGeometry.centerIndex(offset.value, itemPx) } }
    val span = visibleCount / 2 + 2
    Box(
        modifier
            .height(itemHeight * visibleCount)
            .clipToBounds()
            .semantics { contentDescription = items.getOrNull(selectedIndex) ?: "" }
            .pointerInput(count, cyclic, itemPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val wasMoving = offset.isRunning
                    scope.launch { offset.stop() }
                    dragging = true
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var raw = offset.value
                    val max = WheelGeometry.maxOffset(count, itemPx)
                    fun apply(dy: Float) {
                        raw -= dy
                        val v = if (cyclic) raw else WheelGeometry.rubberBand(raw, 0f, max, heightPx / 2f)
                        scope.launch { offset.snapTo(v) }
                    }
                    val start = awaitVerticalTouchSlopOrCancellation(down.id) { ch, over ->
                        ch.consume()
                        apply(over)
                    }
                    if (start == null) {
                        dragging = false
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        val tapped = up != null && up.changedToUpIgnoreConsumed() && !up.isConsumed && !wasMoving
                        scope.launch {
                            if (tapped) {
                                val d = WheelGeometry.tapDelta(down.position.y - size.height / 2f, radius, itemPx)
                                val c = WheelGeometry.centerIndex(offset.value, itemPx)
                                val target = c + d
                                val ok = cyclic || target in 0 until count
                                if (d != 0 && ok) {
                                    offset.animateTo(target * itemPx, spring(dampingRatio = 1f, stiffness = SettleStiffness))
                                }
                            }
                            settle(0f)
                        }
                        return@awaitEachGesture
                    }
                    verticalDrag(start.id) { ch ->
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        apply(ch.positionChange().y)
                        ch.consume()
                    }
                    dragging = false
                    val v = -tracker.calculateVelocity().y
                    scope.launch { settle(v) }
                }
            },
    ) {
        for (idx in center - span..center + span) {
            if (!cyclic && idx !in 0 until count) continue
            if (count == 0) break
            val item = WheelGeometry.wrap(idx, count)
            key(idx) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            val t = WheelGeometry.transform(idx * itemPx - offset.value, radius)
                            if (!t.visible) {
                                alpha = 0f
                            } else {
                                translationY = t.y
                                rotationX = t.rotationX
                                scaleX = t.scale
                                scaleY = t.scale
                                alpha = t.alpha
                                cameraDistance = 6f * density.density * 72f
                            }
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = when (alignment) {
                        Alignment.Start -> Alignment.CenterStart
                        Alignment.End -> Alignment.CenterEnd
                        else -> Alignment.Center
                    },
                ) {
                    Text(
                        items[item],
                        style = textStyle,
                        color = if (disabled(item)) colors.disabledContent else colors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/** 吸附弹簧：临界阻尼，不过冲（iOS 滚筒停下时不回弹） */
private const val SettleStiffness = 180f

/** 选中胶囊的圆角占半高的比例：1 = 胶囊 */
private const val SelectionCornerFraction = 1f
