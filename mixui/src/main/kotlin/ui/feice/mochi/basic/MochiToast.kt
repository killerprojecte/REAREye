// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.content.MochiBlurCrossfade
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.effect.elastic.mochiPressDeform
import ui.feice.mochi.effect.elastic.rubberBand
import ui.feice.mochi.effect.glow.MochiGlowSpec
import ui.feice.mochi.effect.glow.mochiPressGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.effect.shadow.drawMochiShadow
import ui.feice.mochi.overlay.InWindowOverlay
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.rememberMochiHaptics
import ui.feice.mochi.utils.smoothstep

/** 一条提示 */
@Immutable
public class MochiToastData internal constructor(
    public val title: String,
    public val subtitle: String?,
    public val icon: (@Composable (tint: Color) -> Unit)?,
    public val durationMillis: Long,
    public val onClick: (() -> Unit)?,
    internal val id: Long,
)

/** 提示的状态：在任何地方 [show]，由 [MochiToastHost] 显示 */
@Stable
public class MochiToastState internal constructor() {
    internal var current: MochiToastData? by mutableStateOf(null)
    private var seq = 0L

    /**
     * 显示一条提示。正在显示时会被新的替换（胶囊连续地变成新的大小，不会先收起再出来）。
     * @param icon 图标变化时自动模糊替换，出现和移除也有过渡。依赖状态时应捕获调用时的不可变值，保留旧图标供淡出。
     * @param onClick 点提示时执行（执行后提示收起）；null = 点了只收起
     */
    public fun show(
        title: String,
        subtitle: String? = null,
        icon: (@Composable (tint: Color) -> Unit)? = null,
        durationMillis: Long = 2200,
        onClick: (() -> Unit)? = null,
    ) {
        current = MochiToastData(title, subtitle, icon, durationMillis, onClick, ++seq)
    }

    /** 收起当前提示 */
    public fun dismiss() {
        current = null
    }
}

@Composable
public fun rememberMochiToastState(): MochiToastState = remember { MochiToastState() }

/** 提示配色 */
@Immutable
public data class MochiToastColors(val container: Color, val content: Color, val secondary: Color, val glow: Color)

public object MochiToastDefaults {
    /** 收起时（「岛」）的大小 */
    public val IslandWidth: Dp = 120.dp
    public val IslandHeight: Dp = 36.dp

    @Composable
    public fun colors(
        container: Color = MochiTheme.colorScheme.inverseSurface,
        content: Color = toastContentColor(container, MochiTheme.colorScheme.inverseOnSurface),
        secondary: Color = content.copy(alpha = 0.86f),
        glow: Color = Color.White.copy(alpha = 0.22f),
    ): MochiToastColors = remember(container, content, secondary, glow) { MochiToastColors(container, content, secondary, glow) }
}

/**
 * iOS 风格的顶部提示（像灵动岛长出来的胶囊，不做玻璃）。在页面根上放一个，用 [MochiToastState.show] 弹出：
 * - 从屏幕顶部中间的小「岛」柔和展开并落位，内容提前显影；停留 durationMillis 后轻收、上移并淡出
 * - 新提示替换旧的时，胶囊连续地变成新的大小
 * - 内容替换独立于整条提示的进出场：先测量新旧内容，胶囊和内部占位共用同一帧尺寸，文字和图标沿用模糊替换
 * - 按住：放大、发光、跟手（和按钮同一套），按住期间不会自动收起；往上甩收起，往下拉被橡皮筋拉住
 * - 出现时轻震一下
 *
 * 浮层只有胶囊本身接触摸，别处的点击照常落到页面上。
 */
@Composable
public fun MochiToastHost(state: MochiToastState, modifier: Modifier = Modifier, colors: MochiToastColors = MochiToastDefaults.colors()) {
    var shown by remember { mutableStateOf<MochiToastData?>(null) }
    val presence = remember { Animatable(0f) }
    val departure = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var held by remember { mutableStateOf(false) }
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val dark = MochiTheme.colorScheme.isDark
    val shadowColor = MochiTheme.colorScheme.shadow
    val shadow = MochiTheme.shadows.lifted

    LaunchedEffect(state.current) {
        val t = state.current
        if (t != null) {
            shown = t
            haptics.tick()
            coroutineScope {
                launch { drag.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = 0.5f)) }
                launch { departure.animateTo(0f, tween(160)) }
                launch { presence.animateTo(1f, MochiMotions.responseSpring(0.4f, 0.9f)) }
            }
            // 停留：按住时不计时
            var left = t.durationMillis
            while (left > 0) {
                if (held) {
                    snapshotFlow { held }.first { !it }
                    left = left.coerceAtLeast(800)
                }
                delay(100)
                left -= 100
            }
            if (state.current?.id == t.id) state.current = null
        } else if (shown != null) {
            // 收起独立于展开：不把完整文字挤回小岛，不留一颗空胶囊等待弹簧尾巴。
            departure.animateTo(1f, tween(190, easing = FastOutLinearInEasing))
            shown = null
            presence.snapTo(0f)
            departure.snapTo(0f)
            drag.snapTo(0f)
        }
    }

    val toast = shown ?: return
    InWindowOverlay(leaving = { state.current?.id != toast.id }) {
        DisposableEffect(toast.id) { onDispose { held = false } }
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val glow = rememberMochiGlowState(remember(colors.glow) { MochiGlowSpec(colors.glow) })
        val islandW = with(density) { MochiToastDefaults.IslandWidth.toPx() }
        val islandH = with(density) { MochiToastDefaults.IslandHeight.toPx() }
        Box(
            modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 6.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Layout(
                content = {
                    // 图标和胶囊同心：图标框是和内容一样高的正方形，离左端和离上下都是 10dp，
                    // 图标看起来的那一团就落在胶囊圆头往里缩 10dp 的那条同心线上（灵动岛的做法）
                    Row(
                        Modifier
                            .widthIn(max = 360.dp)
                            .height(IntrinsicSize.Min)
                            .padding(end = 20.dp, top = 10.dp, bottom = 10.dp)
                            .graphicsLayer { alpha = smoothstep(0.12f, 0.6f, presence.value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 任意图标插槽用共享内容变换；旧 lambda 保留到淡出完成，不跟新图标瞬切。
                        MochiBlurCrossfade(
                            toast.icon,
                            modifier = Modifier.fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) { icon ->
                            if (icon != null) {
                                Box(
                                    Modifier
                                        .padding(start = 10.dp, end = 12.dp)
                                        .fillMaxHeight()
                                        .defaultMinSize(minHeight = 28.dp)
                                        .aspectRatio(1f, matchHeightConstraintsFirst = true),
                                    contentAlignment = Alignment.Center,
                                ) { icon(colors.content) }
                            } else {
                                // 无图标时的左留白也纳入同一个内容尺寸变换，不能随新提示瞬切。
                                Box(Modifier.width(20.dp))
                            }
                        }
                        Column {
                            // 同一个提示原地更新内容时模糊替换
                            MochiBlurText(
                                toast.title,
                                style = MochiTheme.textStyles.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.content,
                                maxLines = 1,
                            )
                            MochiBlurCrossfade(toast.subtitle, contentAlignment = Alignment.CenterStart) { subtitle ->
                                if (subtitle != null) {
                                    Text(subtitle, style = MochiTheme.textStyles.bodySmall, color = colors.secondary, maxLines = 1)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    // 形变包住整张表面及其裁切，不能让外面的静止胶囊裁掉里面放大、偏移的内容。
                    .mochiPressDeform(scale = 1.04f, follow = 4.dp)
                    .graphicsLayer {
                        val p = presence.value.coerceIn(0f, 1f)
                        val exit = departure.value.coerceIn(0f, 1f)
                        val scale = lerp(0.98f, 1f, p) * (1f - 0.035f * exit)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        translationY = drag.value - 10.dp.toPx() * (1f - p) - 18.dp.toPx() * exit
                        alpha = smoothstep(0f, 0.2f, p) * (1f - exit)
                    }
                    // 柔和投影（和菜单同一套），不用系统按光源算的阴影：那个底边一道实影，又硬又重。画在裁切外面
                    .drawBehind {
                        drawMochiShadow(size.toRect(), size.minDimension / 2f, shadow.resolve(this, dark), shadowColor)
                    }
                    .clip(MochiCapsule)
                    .background(colors.container)
                    .mochiPressGlow(glow, MochiCapsule)
                    .pointerInput(toast.id) {
                        val dismissPx = 24.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            held = true
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var dy = 0f
                            var moved = false
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                tracker.addPosition(c.uptimeMillis, c.position)
                                dy += c.position.y - c.previousPosition.y
                                if (!moved && kotlin.math.abs(dy) > viewConfiguration.touchSlop) moved = true
                                // 往上跟手，往下被橡皮筋拉住
                                val shownDy = if (dy < 0f) dy else rubberBand(dy, size.height.coerceAtLeast(1) * 3f, 14.dp.toPx())
                                scope.launch { drag.snapTo(shownDy) }
                                c.consume()
                                if (!c.pressed) break
                            }
                            held = false
                            val v = tracker.calculateVelocity().y
                            if (moved && (dy < -dismissPx || v < -600f)) {
                                state.dismiss()
                            } else if (!moved) {
                                toast.onClick?.invoke()
                                state.dismiss()
                            } else {
                                scope.launch { drag.animateTo(0f, MochiMotions.responseSpring(0.36f, 0.62f), initialVelocity = v) }
                            }
                        }
                    },
            ) { ms, c ->
                val p = ms.first().measure(Constraints(maxWidth = c.maxWidth))
                // 内部模糊变换先测量每份新旧内容，再按同一进度给出当前占位尺寸。
                // 外壳直接使用这一帧的尺寸；不能把中间尺寸当新目标再跑一次弹簧，造成内外错拍。
                val k = presence.value.coerceAtLeast(0f)
                val w = lerp(islandW, p.width.toFloat(), k).roundToInt().coerceAtLeast(1)
                val h = lerp(islandH, p.height.toFloat(), k).roundToInt().coerceAtLeast(1)
                layout(w, h) { p.place((w - p.width) / 2, (h - p.height) / 2) }
            }
        }
    }
}
