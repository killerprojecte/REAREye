// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.effect.elastic.mochiBoundedPressDeform
import ui.feice.mochi.effect.elastic.rubberBand
import ui.feice.mochi.effect.glow.mochiPressGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.shape.ConcentricBottom
import ui.feice.mochi.shape.ConcentricTop
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.rememberMochiHaptics

/**
 * 左滑露出的一个操作。
 * @param removesRow 点它（或滑到底）后这一行收起消失，收起后才调 [onClick]
 */
@Immutable
public class MochiSwipeAction(
    public val label: String,
    public val color: Color,
    public val onClick: () -> Unit,
    public val contentColor: Color = Color.White,
    public val removesRow: Boolean = false,
    public val icon: (@Composable (tint: Color) -> Unit)? = null,
)

/**
 * 能左右滑出操作按钮的一行（iOS 列表的 swipe actions，iOS 26 的样子：一颗颗分开的胶囊按钮）：
 * - 左滑跟手，露出右侧的操作 [actions]；右滑露出左侧的操作 [leadingActions]（「标为已读」「置顶」那种）
 * - 按钮随露出的宽度从小圆点长成整圆、再横向拉成胶囊，文字跟着淡入；高度上下留白，不贴着行的边
 * - 松手按位置和速度吸附到「打开」或「收回」
 * - 继续滑过 [fullSwipeFraction]：最靠边的那个操作拉满整行（标题贴着手指那一侧），鼓一下并轻震；这时松手就直接执行它
 *   （它是删除的话，整行接着手速滑出去、高度收起，下面的行顶上来）
 * - 点某个操作：它先鼓一下、震一下，再执行
 * - 没有操作的那一侧被橡皮筋拉住；打开时点一下这一行就收回；一次只开一行
 *
 * @param actions 右侧的操作，从右往左排，第一个是左滑到底时执行的那个
 * @param leadingActions 左侧的操作，从左往右排，第一个是右滑到底时执行的那个
 * @param actionWidth 每个操作占的宽度（含按钮之间的间距）
 * @param dividerInset 放在 MochiListSection 里时，这一行下面分隔线的起点
 */
@Composable
public fun MochiSwipeRow(
    actions: List<MochiSwipeAction>,
    modifier: Modifier = Modifier,
    leadingActions: List<MochiSwipeAction> = emptyList(),
    fullSwipe: Boolean = true,
    fullSwipeFraction: Float = 0.6f,
    dividerInset: Dp = MochiListDefaults.RowPadding,
    actionWidth: Dp = 76.dp,
    background: Color = MochiListDefaults.colors().container,
    hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cardClip = LocalListCardClip.current
    // 内容往左（负）/ 往右（正）挪了多少 px
    val offset = remember { Animatable(0f) }
    // 0..1：滑过阈值后第一个按钮拉满整行的程度
    val armT = remember { Animatable(0f) }
    // 1 → 0：删除时高度收起
    val collapse = remember { Animatable(1f) }
    val itemInfo = remember(dividerInset) { ListItemInfo(dividerInset, heightScale = { collapse.value }) }
    // 触发反馈：那颗按钮轻轻鼓一下（0 → 1 → 0）；pulsed 记是哪一颗（右侧第 i 个 = −(i + 1)，左侧 = i + 1）
    val pulse = remember { Animatable(0f) }
    var pulsed by remember { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    // 这一行「要停在打开」：开始收回的那一刻就是 false（动画还在走）。接点按的那层只看它，收回途中的点按直接给行本身
    var opened by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMochiHaptics(hapticProfile)
    val currentActions by rememberUpdatedState(actions)
    val currentLeading by rememberUpdatedState(leadingActions)
    val slotPx = with(density) { actionWidth.toPx() }
    val openPx = slotPx * actions.size
    val leadPx = slotPx * leadingActions.size
    val settle = MochiMotions.responseSpring(0.36f, 0.86f)

    // 一次只开一行：开始拖这一行时，让之前打开的那行收回
    val closeRef = remember { arrayOfNulls<() -> Unit>(1) }
    val closeSelf: () -> Unit = remember { { closeRef[0]?.invoke() } }

    fun close() {
        opened = false
        scope.launch { offset.animateTo(0f, settle) }
        scope.launch { armT.animateTo(0f, settle) }
        armed = false
        SwipeRowGroup.closed(closeSelf)
    }
    closeRef[0] = ::close
    DisposableEffect(Unit) { onDispose { SwipeRowGroup.closed(closeSelf) } }

    fun pop(key: Int) {
        pulsed = key
        scope.launch {
            pulse.snapTo(0f)
            pulse.animateTo(1f, MochiMotions.responseSpring(0.12f, 0.9f))
            pulse.animateTo(0f, MochiMotions.responseSpring(0.3f, 0.7f))
        }
    }

    /**
     * @param side 操作在哪一侧：-1 = 右侧（左滑露出），1 = 左侧（右滑露出）
     * @param velocity 松手时的速度（滑到底执行时），整行接着这个速度滑出去，不会顿一下
     */
    fun perform(action: MochiSwipeAction, side: Int, velocity: Float = 0f) {
        if (removing) return
        haptics.confirm()
        val list = if (side < 0) currentActions else currentLeading
        pop(side * (list.indexOf(action) + 1))
        if (!action.removesRow) {
            // 先鼓一下，看得见「点到了」，再收回
            scope.launch {
                delay(120)
                close()
                action.onClick()
            }
            return
        }
        removing = true
        opened = false
        scope.launch {
            cardClip?.let { it.heightAnimations++ }
            try {
                // 整行接着手速滑出去（第一个按钮跟着拉满），滑到大半时高度开始收起，收完才通知删除
                launch { armT.animateTo(1f, settle) }
                val slide = launch {
                    offset.animateTo(side * width.toFloat(), MochiMotions.responseSpring(0.28f, 1f), initialVelocity = velocity)
                }
                delay(140)
                collapse.animateTo(0f, MochiMotions.responseSpring(0.32f, 1f))
                slide.join()
                action.onClick()
            } finally {
                cardClip?.let { it.heightAnimations-- }
            }
        }
    }

    Layout(
        modifier = modifier
            .layoutId(itemInfo)
            .onSizeChanged { width = it.width }
            // 首末补白是这一行的可见区域；删除时也只裁到这一完整区域，不侵入相邻行。
            .drawWithContent {
                clipRect(top = -itemInfo.extraTop, bottom = size.height + itemInfo.extraBottom) {
                    this@drawWithContent.drawContent()
                }
            }
            .semantics {
                customActions = currentLeading.map { a ->
                    CustomAccessibilityAction(a.label) {
                        perform(a, 1)
                        true
                    }
                } + currentActions.map { a ->
                    CustomAccessibilityAction(a.label) {
                        perform(a, -1)
                        true
                    }
                }
            },
        content = {
            val pulseOf = { key: Int -> if (pulsed == key) pulse.value else 0f }
            SwipeActions(
                actions,
                slotPx,
                revealed = { (-offset.value).coerceAtLeast(0f) },
                armT = { if (offset.value < 0f) armT.value else 0f },
                pulse = { pulseOf(-(it + 1)) },
                heightScale = { collapse.value },
                itemInfo = itemInfo,
                fromEnd = true,
            ) { perform(it, -1) }
            SwipeActions(
                leadingActions,
                slotPx,
                revealed = { offset.value.coerceAtLeast(0f) },
                armT = { if (offset.value > 0f) armT.value else 0f },
                pulse = { pulseOf(it + 1) },
                heightScale = { collapse.value },
                itemInfo = itemInfo,
                fromEnd = false,
            ) { perform(it, 1) }

            // 平时（没滑开）不画底色：分组卡片自己有底色和圆角，行再盖一块不透明的直角底色，会把首末行的圆角盖成直角。
            // 滑开时才画，这时分组卡片按圆角裁切（openCount 和这里是同一次重组里变的），底色也跟着是圆角
            val open by remember { derivedStateOf { offset.value != 0f } }
            if (open) {
                DisposableEffect(Unit) {
                    SwipeRowGroup.openCount++
                    onDispose { SwipeRowGroup.openCount-- }
                }
            }
            Box(
                Modifier
                    .graphicsLayer { translationX = offset.value }
                    .drawBehind {
                        if (open) {
                            drawRect(
                                background,
                                Offset(0f, -itemInfo.extraTop),
                                Size(size.width, size.height * collapse.value.coerceIn(0f, 1f) + itemInfo.extraTop + itemInfo.extraBottom),
                            )
                        }
                    }
                    .pointerInput(openPx, leadPx, fullSwipe, fullSwipeFraction) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (removing) return@awaitEachGesture
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            val start = offset.value
                            val wasOpened = opened
                            var raw = start
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, over ->
                                // 越过阈值的这一下也算进速度：短短一甩总共就几个采样，少了它松手速度会算小
                                tracker.addPosition(c.uptimeMillis, c.position)
                                c.consume()
                                raw += over
                            }
                            if (drag == null) {
                                // 打开时轻点这一行 = 收回（不传给行本身）
                                if (wasOpened) close()
                                return@awaitEachGesture
                            }
                            SwipeRowGroup.opened(closeSelf)
                            opened = true
                            fun apply(r: Float) {
                                val w = width.toFloat().coerceAtLeast(1f)
                                val shown = when {
                                    r > 0f && leadPx == 0f -> rubberBand(r, w, 24.dp.toPx())
                                    r < 0f && openPx == 0f -> -rubberBand(-r, w, 24.dp.toPx())
                                    !fullSwipe && r < -openPx -> -openPx + rubberBand(r + openPx, w, 32.dp.toPx())
                                    !fullSwipe && r > leadPx -> leadPx + rubberBand(r - leadPx, w, 32.dp.toPx())
                                    else -> r.coerceIn(-w, w)
                                }
                                scope.launch { offset.snapTo(shown) }
                                val hasSide = if (shown < 0f) openPx > 0f else leadPx > 0f
                                val nowArmed = fullSwipe && hasSide && abs(shown) > w * fullSwipeFraction
                                if (nowArmed != armed) {
                                    armed = nowArmed
                                    haptics.threshold()
                                    if (nowArmed) pop(if (shown < 0f) -1 else 1)
                                    scope.launch { armT.animateTo(if (nowArmed) 1f else 0f, MochiMotions.responseSpring(0.25f, 0.8f)) }
                                }
                            }
                            apply(raw)
                            horizontalDrag(drag.id) { c ->
                                tracker.addPosition(c.uptimeMillis, c.position)
                                raw += c.position.x - c.previousPosition.x
                                apply(raw)
                                c.consume()
                            }
                            val v = tracker.calculateVelocity().x
                            val side = if (offset.value < 0f) -1 else 1
                            val first = (if (side < 0) currentActions else currentLeading).firstOrNull()
                            if (armed && first != null) {
                                perform(first, side, v)
                                return@awaitEachGesture
                            }
                            // 甩得够快就按方向开合（往外甩打开、往回甩收起，滑了多远不管）；慢慢松手才按位置 + 速度投影判断。
                            // 不能从一侧直接甩到另一侧
                            val fling = SwipeFlingVelocity.toPx()
                            val projected = offset.value + v * 0.15f
                            val toward = when {
                                abs(v) >= fling -> if (offset.value < 0f) v < 0f else v > 0f
                                offset.value < 0f -> projected < -openPx / 2f
                                else -> projected > leadPx / 2f
                            }
                            when {
                                openPx > 0f && offset.value < 0f && toward ->
                                    scope.launch { offset.animateTo(-openPx, settle, initialVelocity = v) }

                                leadPx > 0f && offset.value > 0f && toward ->
                                    scope.launch { offset.animateTo(leadPx, settle, initialVelocity = v) }

                                else -> close()
                            }
                        }
                    },
            ) {
                CompositionLocalProvider(LocalListRowGeometry provides itemInfo, content = content)
                // 打开时盖一层接住轻点（收回），不让下面的行收到点击；开始收回的那一刻就撤掉
                if (opened) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(Unit) { detectTapGestures { close() } },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        // Actions never determine row height or publish text anchors. Explicit body guides keep
        // the card's measurement from laying out actions before it has resolved edge padding.
        val body = measurables.last().measure(constraints.copy(minHeight = 0))
        val rowWidth = constraints.constrainWidth(body.width)
        itemInfo.fullHeight = body.height
        val rowHeight = (body.height * collapse.value.coerceIn(0f, 1f)).roundToInt()
        val guides = listOf<AlignmentLine>(ConcentricTop, ConcentricBottom).mapNotNull { line ->
            body[line].takeIf { it != AlignmentLine.Unspecified }?.let { line to it }
        }.toMap()
        val layers = measurables.dropLast(1).map { it.measure(Constraints.fixed(rowWidth, body.height)) }
        layout(rowWidth, rowHeight, guides) {
            layers.forEach { it.place(0, 0) }
            body.place(0, 0)
        }
    }
}

/** 松手时甩得比这快就按方向开合：短短一甩也能打开（只走了一小段，按位置投影会弹回去） */
private val SwipeFlingVelocity = 250.dp

/** 按钮之间、按钮和行边的间距 */
private val ActionGap = 8.dp

/** 胶囊上、下与外侧边界使用相同内缩。 */
private val ActionInset = 8.dp

/**
 * 一侧的操作按钮；[fromEnd] = 从右边排起（右侧操作），否则从左边排起。
 *
 * 几何（每帧按露出的宽度 R 算，距离从这一侧的边量起）：
 * - n 个按钮平分 R，每个占一格；按钮宽 = 格宽 − 间距，高 = min(按钮高, 宽)：
 *   露出很少时是一颗小圆点，长到按钮高之前一直是正圆，之后横向拉成胶囊
 * - 滑过阈值（armT → 1）：第一个按钮的格子拉满整个 R，其余的格子收成 0（缩小、淡出到第一个下面）
 */
@Composable
private fun SwipeActions(
    actions: List<MochiSwipeAction>,
    slotPx: Float,
    revealed: () -> Float,
    armT: () -> Float,
    pulse: (Int) -> Float,
    heightScale: () -> Float,
    itemInfo: ListItemInfo,
    fromEnd: Boolean,
    onPerform: (MochiSwipeAction) -> Unit,
) {
    if (actions.isEmpty()) return
    val density = LocalDensity.current
    val gap = with(density) { ActionGap.toPx() }
    val inset = with(density) { ActionInset.toPx() }
    val natural = slotPx - gap
    val n = actions.size
    val dimensions = remember { FloatArray(2) }
    val shape = MochiCapsule

    fun visualHeight(): Float = dimensions[1] * heightScale().coerceIn(0f, 1f) + itemInfo.extraTop + itemInfo.extraBottom

    fun frame(i: Int): Rect = swipeActionRect(
        dimensions[0], visualHeight(), revealed(), n, i, armT(), inset, gap, fromEnd, slotPx,
    ).translate(Offset(0f, -itemInfo.extraTop))

    Layout(
        content = {
            actions.forEachIndexed { i, action ->
                SwipeActionButton(
                    action,
                    // 文字：随按钮长大淡入、放大；第一个拉满整行时贴着手指那一侧
                    labelAlpha = {
                        val w = frame(i).width
                        ((w - natural * 0.55f) / (natural * 0.4f)).coerceIn(0f, 1f)
                    },
                    labelBias = { if (i == 0) armT().coerceIn(0f, 1f) * (if (fromEnd) -1f else 1f) else 0f },
                    natural = natural,
                    pulse = { pulse(i) },
                    shape = shape,
                    bounds = {
                        val f = frame(i)
                        val h = visualHeight()
                        val guard = min(inset / 2f, h / 2f)
                        Rect(
                            max(guard, f.left - gap / 2f) - f.left,
                            -itemInfo.extraTop + guard - f.top,
                            min(dimensions[0] - guard, f.right + gap / 2f) - f.left,
                            h - itemInfo.extraTop - guard - f.top,
                        )
                    },
                ) { onPerform(action) }
            }
        },
    ) { measurables, c ->
        val rowW = c.maxWidth
        dimensions[0] = rowW.toFloat()
        dimensions[1] = c.maxHeight.toFloat()
        layout(rowW, c.maxHeight) {
            // The card resolves first/last padding after measuring the body. Measure buttons
            // here, with those final insets, so their size and position update in the same pass.
            val frames = measurables.indices.map { frame(it) }
            val placeables = measurables.mapIndexed { i, m ->
                val f = frames[i]
                m.measure(Constraints.fixed(f.width.roundToInt().coerceAtLeast(0), f.height.roundToInt().coerceAtLeast(0)))
            }
            // 后面的先放：第一个（滑到底时拉满的那个）画在最上面
            for (i in placeables.indices.reversed()) {
                val p = placeables[i]
                val f = frames[i]
                p.place(f.left.roundToInt(), f.top.roundToInt())
            }
        }
    }
}

@Composable
private fun SwipeActionButton(
    action: MochiSwipeAction,
    labelAlpha: () -> Float,
    labelBias: () -> Float,
    natural: Float,
    pulse: () -> Float,
    shape: androidx.compose.ui.graphics.Shape,
    bounds: () -> Rect,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .mochiBoundedPressDeform(
                scale = 1.02f,
                follow = 2.dp,
                extraScale = { 1f + 0.03f * pulse().coerceIn(0f, 1f) },
                bounds = bounds,
            )
            .background(action.color, shape)
            .mochiPressGlow(rememberMochiGlowState(), shape)
            .clip(shape)
            .pointerInput(action) { detectTapGestures { onClick() } },
    ) {
        // 文字区固定是静止时按钮的宽度：按钮窄的时候被裁掉一部分（同时淡出），拉宽时按 bias 从居中滑到贴边
        Column(
            Modifier
                .layout { m, c ->
                    val w = natural.roundToInt()
                    val p = m.measure(Constraints(maxWidth = w, maxHeight = max(c.maxHeight, 1)))
                    layout(c.maxWidth, c.maxHeight) {
                        val free = c.maxWidth - w
                        val x = free / 2f * (1f + labelBias())
                        p.place(x.roundToInt() + (w - p.width) / 2, (c.maxHeight - p.height) / 2)
                    }
                }
                .wrapContentSize()
                .graphicsLayer {
                    val f = labelAlpha()
                    alpha = f
                    scaleX = 0.8f + 0.2f * f
                    scaleY = 0.8f + 0.2f * f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            action.icon?.invoke(action.contentColor)
            // 标题变了（比如「已读」↔「未读」）模糊替换，不瞬切
            MochiBlurText(
                action.label,
                color = action.contentColor,
                style = MochiTheme.textStyles.labelLarge,
                maxLines = 1,
                contentAlignment = Alignment.Center,
            )
        }
    }
}
