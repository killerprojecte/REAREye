// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ui.feice.mochi.anim.MochiMotion
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.basic.menu.AnchorTracker
import ui.feice.mochi.basic.menu.Overlay
import ui.feice.mochi.basic.menu.rememberMochiEngine
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.windowOriginOnScreen

/**
 * 长按预览（iOS 的 context menu，不做模糊和玻璃）。菜单用的是和 [MochiDropdown] 完全同一个引擎（[rememberMochiEngine]）：
 * 面板从卡片里带融球颈、果冻长出来，滑出面板被橡皮筋拉住，面板按压发光、两层投影，长列表能滚、贴边自动滚，
 * 滑过选项轻震、选中项高光——下拉菜单演示页里有的，这里全都有。
 *
 * - 按住时内容慢慢缩小一点（告诉你「再按一会儿会发生什么」），松手或滑动就恢复，交给内容自己的点击 / 外面的滚动
 * - 按满时长：轻震，背景变暗，菜单从卡片里长出来；下面放不下就往上开，都放不下就变矮、可以滚
 * - 不松手直接滑到菜单项上松手 = 选中；松手在别处菜单保持打开，点外面或按返回关闭
 *
 * 内容会在浮层里再组合一次（预览用），所以里面不要放有内部状态的东西。
 * @param shape 内容的外形
 * @param cornerRadius 内容的圆角（浮层里卡片的投影、和面板之间的融球颈按它算），和 [shape] 一致
 * @param colors / behavior / physics / motion 和 [MochiDropdown] 的同名参数一样
 */
// 内容故意组合两次：原位一份、浮层里预览一份（见上面的说明）
@Suppress("ktlint:compose:content-slot-reused")
@Composable
public fun MochiContextMenu(
    items: List<MochiItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MochiTheme.shapes.extraLarge,
    cornerRadius: Dp = 28.dp,
    colors: MochiDropdownColors = MochiDropdownDefaults.dropdownColors(),
    behavior: MochiDropdownBehavior = MochiDropdownDefaults.behavior(),
    physics: MochiDropdownPhysics = MochiDropdownDefaults.physics(),
    motion: MochiMotion = MochiTheme.motionScheme.menu,
    menuWidth: Dp = MochiDropdownDefaults.MenuWidth,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    // 卡片位置平时不进可观察状态（转场里每帧都变），菜单打开期间才跟踪
    val anchor = remember { AnchorTracker() }
    var expanded by remember { mutableStateOf(false) }
    var screenW by remember { mutableFloatStateOf(0f) }
    // 卡片在屏幕右半边就让菜单靠右对齐
    val alignEnd = screenW > 0f && anchor.observed().center.x > screenW / 2f
    val liquid = liquidMenu()
    val style = remember(colors, behavior, physics, menuWidth, alignEnd, cornerRadius, liquid) {
        mochiStyle(
            // 卡片就是「按钮」：它自己画内容，按钮底色透明；投影、融球颈按卡片的圆角算
            colors.copy(buttonColor = Color.Transparent),
            behavior.copy(openMode = MochiOpenMode.Detach, align = if (alignEnd) MochiAlign.End else MochiAlign.Start),
            physics,
            menuWidth,
        ).copy(buttonCornerRadius = cornerRadius).let { if (liquid) it.liquid(cover = false) else it }
    }
    val menuMotion = liquidMotion(liquid, motion)
    val engine = rememberMochiEngine(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        onItemClick = onItemClick,
        itemCount = items.size,
        style = style,
        motion = menuMotion,
        dismissDelayMillis = behavior.dismissDelayMillis,
        anchorSize = { anchor.rect.size },
    )
    val scope = rememberCoroutineScope()
    val hold = remember { Animatable(1f) }
    val currentItems by rememberUpdatedState(items)

    Box(
        modifier
            .onGloballyPositioned {
                anchor.update(Rect(it.positionInWindow() + view.windowOriginOnScreen(), it.size.toSize()))
                screenW = view.rootView.width.toFloat()
            }
            .graphicsLayer {
                // 菜单打开后卡片由浮层接手画（原地藏起来）；按住时慢慢缩小一点
                alpha = if (engine.overlayReady) 0f else 1f
                scaleX = hold.value
                scaleY = hold.value
            }
            .semantics {
                onLongClick(label = "更多操作") {
                    if (enabled && currentItems.isNotEmpty()) engine.callbacks.open(true)
                    true
                }
            }
            .then(
                if (enabled && items.isNotEmpty()) {
                    Modifier.pointerInput(items.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (engine.callbacks.isExpanded()) return@awaitEachGesture
                            val slop = viewConfiguration.touchSlop
                            var shrink: Job? = scope.launch {
                                hold.animateTo(HoldScale, tween(viewConfiguration.longPressTimeoutMillis.toInt()))
                            }
                            // 按满时长才算长按；中途松手或滑动都算放弃（交给内容自己的点击 / 外面的滚动）
                            val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    val c = e.changes.firstOrNull { it.id == down.id }
                                    val gone = c == null || !c.pressed || c.isConsumed
                                    if (gone || (c.position - down.position).getDistance() > slop) break
                                }
                            }
                            shrink?.cancel()
                            shrink = null
                            if (cancelled != null) {
                                scope.launch { hold.animateTo(1f, MochiMotions.responseSpring(0.3f, 0.7f)) }
                                return@awaitEachGesture
                            }
                            // 打开：浮层里的卡片从按住时缩小的大小接着弹回（不跳）
                            scope.launch {
                                engine.press.snapTo(hold.value)
                                hold.snapTo(1f)
                                engine.press.animateTo(1f, MochiMotions.responseSpring(0.38f, 0.62f))
                            }
                            engine.callbacks.open(true)
                            val origin = anchor.rect.topLeft
                            val controller = engine.controller
                            var moved = false
                            // 打开后这一下手势归菜单（和下拉菜单按住滑选同一套）：Initial 阶段就吃掉，内容收不到松手
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                for (ch in e.changes) ch.consume()
                                val p = origin + c.position
                                controller.touchOnScreen = p
                                if (!moved && (c.position - down.position).getDistance() > slop) {
                                    moved = true
                                    controller.autoScroll = true
                                }
                                if (moved) controller.track(p)
                                if (!c.pressed) break
                            }
                            val h = controller.hover
                            controller.autoScroll = false
                            controller.pointer = null
                            if (moved && h >= 0) engine.callbacks.select(h) else controller.hover = -1
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) { content() }

    anchor.tracking = expanded || engine.overlayVisible
    engine.Overlay(
        anchorOnScreen = anchor.observed(),
        trigger = MochiTrigger.LongPress,
        items = items,
        selectedIndex = -1,
        motion = menuMotion,
        anchorShape = shape,
        anchorPadding = PaddingValues(0.dp),
        scrim = MochiDefaults.scrim(),
        matchAnchorWidth = false,
    ) { content() }
}

/** 按住时内容缩到多少 */
private const val HoldScale = 0.95f
