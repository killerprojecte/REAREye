// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic.menu

import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotion
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiOpenMode
import ui.feice.mochi.basic.MochiStyle
import ui.feice.mochi.effect.elastic.MochiRubberBandState
import ui.feice.mochi.effect.elastic.jellyCrossScale
import ui.feice.mochi.effect.elastic.jellyStretch
import ui.feice.mochi.effect.elastic.rememberMochiRubberBandState
import ui.feice.mochi.effect.elastic.rubberStretched
import ui.feice.mochi.effect.glow.MochiGlowDefaults
import ui.feice.mochi.effect.glow.MochiGlowSpec
import ui.feice.mochi.effect.glow.MochiGlowState
import ui.feice.mochi.effect.glow.mochiGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.effect.metaball.MochiBlob
import ui.feice.mochi.effect.metaball.MochiMetaballColors
import ui.feice.mochi.effect.metaball.MochiMetaballFrame
import ui.feice.mochi.effect.metaball.MochiMetaballShadow
import ui.feice.mochi.effect.metaball.drawMochiMetaball
import ui.feice.mochi.effect.metaball.proximityFuse
import ui.feice.mochi.effect.metaball.rememberMochiMetaballRenderer
import ui.feice.mochi.gesture.MochiCallbacks
import ui.feice.mochi.gesture.MochiController
import ui.feice.mochi.gesture.mochiButtonGesture
import ui.feice.mochi.overlay.InWindowOverlay
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.shape.G2RectClipShape
import ui.feice.mochi.shape.addG2RoundRect
import ui.feice.mochi.shape.continuityFor
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.MochiHaptics
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.smoothstep
import ui.feice.mochi.utils.windowOriginOnScreen

// ───────────────────────── 几何 ─────────────────────────

/** 预先换算好的像素值 */
internal class MochiPx(s: MochiStyle, d: Density) {
    val buttonRadius = with(d) { s.buttonCornerRadius.toPx() }
    val panelRadius = with(d) { s.panelCornerRadius.toPx() }
    val goo = with(d) { s.fuseRadius.toPx() }
    val gap = with(d) { s.gap.toPx() }
    val menuWidth = with(d) { s.menuWidth.toPx() }
    val itemHeight = with(d) { s.itemHeight.toPx() }
    val panelPadding = with(d) { s.panelPadding.toPx() }
    val margin = with(d) { s.screenMargin.toPx() }
    val rubberMax = with(d) { s.rubberMax.toPx() }
    val blendWidth = with(d) { 10.dp.toPx() }
    val gel = s.gel
    val squash = s.squash
    val align = s.align
}

internal fun squashScale(p: Float, squash: Float): Float {
    val c = p.coerceIn(0f, 1f)
    return 1f + squash * 4f * c * (1f - c)
}

/** 菜单项错开出现 */
internal fun staggered(e: Float, index: Int, count: Int, stagger: Float): Float {
    val span = 1f + stagger * (count - 1).coerceAtLeast(0)
    return (e * span - index * stagger).coerceIn(0f, 1f)
}

/** 往按钮方向（展开的反方向）拉得少一点，免得面板压到按钮上 */
internal fun towardButtonResist(pull: Offset, upward: Boolean): Offset {
    val towardButton = if (upward) pull.y > 0f else pull.y < 0f
    return if (towardButton) Offset(pull.x, pull.y * 0.4f) else pull
}

/** 一帧的几何：给融球渲染器的 [metaball]，以及克隆按钮的缩放 [scale] */
internal class DropdownFrame(
    val button: Rect,
    val buttonRadius: Float,
    val panel: Rect,
    val panelRadius: Float,
    val goo: Float,
    val blend: Float,
    val scale: Float,
    /** 包裹模式下 0→1，按钮颜色被面板色吞掉 */
    val cover: Float,
) {
    val metaball: MochiMetaballFrame = MochiMetaballFrame(MochiBlob(button, buttonRadius), MochiBlob(panel, panelRadius), goo, blend, cover)

    /** 面板竖着平移 [dy]（iOS 菜单的 kick）；包裹模式按钮就是面板，一起走 */
    fun panelShifted(dx: Float, dy: Float, cover: Boolean): DropdownFrame {
        val p = panel.translate(dx, dy)
        return DropdownFrame(if (cover) p else button, buttonRadius, p, panelRadius, goo, blend, scale, this.cover)
    }
}

/** 面板最终位置；空间不够自动反向开。包裹模式下面板从按钮所在位置铺开 */
internal fun computeTarget(
    button: Rect,
    container: Size,
    itemCount: Int,
    mode: MochiOpenMode,
    px: MochiPx,
    matchAnchorWidth: Boolean = true,
    safeTop: Float = 0f,
    safeBottom: Float = 0f,
    headerPx: Float = 0f,
): Pair<Rect, Boolean> {
    // 锚点是按钮时面板至少和按钮一样宽；锚点是大卡片（长按预览）时按菜单自己的宽度
    val w = if (matchAnchorWidth) max(px.menuWidth, button.width) else px.menuWidth
    val wanted = itemCount * px.itemHeight + px.panelPadding * 2f + headerPx
    val cover = mode == MochiOpenMode.Cover
    // 长列表最多伸到状态栏下面一点、导航栏上面一点（safeTop / safeBottom 是系统栏占的高度）
    val floor = container.height - safeBottom - px.margin
    val ceil = safeTop + px.margin
    val below = if (cover) floor - button.top else floor - button.bottom - px.gap
    val above = if (cover) button.bottom - ceil else button.top - px.gap - ceil
    val upward = below < wanted && above > below
    val h = min(wanted, max(if (upward) above else below, px.itemHeight + px.panelPadding * 2f + headerPx))
    var left = when (px.align) {
        MochiAlign.Start -> button.left
        MochiAlign.Center -> button.center.x - w / 2f
        MochiAlign.End -> button.right - w
    }
    left = left.coerceIn(px.margin, max(px.margin, container.width - px.margin - w))
    val top = when {
        cover && upward -> button.bottom - h
        cover -> button.top
        upward -> button.top - px.gap - h
        else -> button.bottom + px.gap
    }
    return Rect(left, top, left + w, top + h) to upward
}

/**
 * 核心编排。p：展开进度（弹簧），velocity：它的速度（1/s）。
 * - 面板四条边一起从按钮插值到目标
 * - 果冻：按速度沿展开方向拉长、横向收窄（体积近似守恒），锚定在靠按钮那条边；回弹时速度反向就自然变成压扁
 * - 融球半径：开头从 0 淡入（否则 smin 会让重合的形状凭空胖一圈），后半段收回 0 → 颈部被「掐断」
 * - pull：手指拖出面板时被橡皮筋拉住（effect.elastic 的 rubberStretched）
 * - 融球颈：展开途中长出再掐断；展开后被拉回按钮附近时按距离重新连上（effect.metaball 的 proximityFuse）
 * - elastic = 0（系统关动画）时，上面这些弹性效果全关
 * - liquid（开了液态玻璃的菜单，照 iOS 的液态形变）：位置、宽高按同一个进度直接插值
 *   （iOS 这几条通道是同一组弹簧），没有果冻；包裹模式不先横后纵；圆角从胶囊过渡到面板圆角
 */
internal fun computeFrame(
    p: Float,
    velocity: Float,
    button: Rect,
    target: Rect,
    upward: Boolean,
    px: MochiPx,
    pressScale: Float,
    pull: Offset,
    cover: Boolean,
    elastic: Float,
    liquid: Boolean = false,
): DropdownFrame {
    val pg = p.coerceAtLeast(0f)
    val clamped = pg.coerceAtMost(1f)

    // 按压放大只属于「还是按钮」的阶段：展开越多越弱，按住滑选时原按钮处不会鼓出一块
    val pressEff = 1f + (pressScale - 1f) * (1f - clamped)
    val sc = (if (cover) 1f else 1f + (squashScale(pg, px.squash) - 1f) * elastic) * pressEff
    val bw = button.width * sc
    val bh = button.height * sc
    var btn = Rect(button.center.x - bw / 2f, button.center.y - bh / 2f, button.center.x + bw / 2f, button.center.y + bh / 2f)

    // 包裹：横向先走、纵向后走。按钮先横着撑成胶囊，再顺势往下「流」成面板，
    // 角点走的是一条弧线而不是直线；过冲反过来主要落在纵向（横向收、纵向多弹一点）
    val hp = if (cover && !liquid) pg + 0.6f * pg * (1f - pg) else pg
    val vp = if (cover && !liquid) pg - 0.45f * pg * (1f - pg) * (1f - 0.5f * pg) else pg
    var l = min(lerp(button.left, target.left, hp), lerp(button.right, target.right, hp))
    var r = max(lerp(button.left, target.left, hp), lerp(button.right, target.right, hp))
    var t = min(lerp(button.top, target.top, vp), lerp(button.bottom, target.bottom, vp))
    var b = max(lerp(button.top, target.top, vp), lerp(button.bottom, target.bottom, vp))
    if (cover && pressEff != 1f) {
        // 包裹模式没有单独的按钮形状，按压直接作用在这块（还很小的）面板上
        val cx = button.center.x
        val cy = button.center.y
        l = cx + (l - cx) * pressEff
        r = cx + (r - cx) * pressEff
        t = cy + (t - cy) * pressEff
        b = cy + (b - cy) * pressEff
    }

    // 果冻形变（包裹模式弱一些，不然圆按钮会先被拉成竖条）
    val s = if (liquid) 0f else jellyStretch(velocity) * px.gel * elastic * (if (cover) 0.55f else 1f)
    val h = (b - t) * (1f + s)
    val w = (r - l) / (1f + s * 0.6f)
    if (upward) t = b - h else b = t + h
    when (px.align) {
        MochiAlign.End -> l = r - w

        MochiAlign.Center -> {
            val c = (l + r) / 2f
            l = c - w / 2f
            r = c + w / 2f
        }

        MochiAlign.Start -> r = l + w
    }

    // 橡皮筋
    val panel = Rect(l, t, r, b).rubberStretched(Offset(pull.x * clamped * elastic, pull.y * clamped * elastic))

    val btnR = min(px.buttonRadius, button.height / 2f)
    // liquid：圆角从「当前大小的胶囊」（短边一半）按进度变成面板圆角，长大途中一直是圆滚滚的胶囊 / 椭圆。
    // 近似：iOS 录屏里中途是胶囊；原生按什么插值圆角没拆出来
    val panR = if (liquid) {
        lerp(min(panel.width, panel.height) / 2f, px.panelRadius, clamped)
    } else {
        lerp(btnR, px.panelRadius, if (cover) smoothstep(0f, 0.85f, clamped) else clamped)
    }
    // 融球：展开过程中开头淡入、后半段掐断；展开后面板被拉回按钮附近（两块的间距小于静止间距）时，
    // 颈部再按距离长出来，像两滴水靠近时连上，拉开又断开
    val opening = smoothstep(0f, 0.15f, pg) * (1f - smoothstep(0.5f, 0.95f, pg))
    val near = proximityFuse(btn, panel, px.gap) * smoothstep(0.85f, 1f, pg)
    val goo = if (cover) 0f else px.goo * max(opening, near) * elastic
    val blend = smoothstep(0f, 0.35f, pg)
    val coverAmt = if (cover) smoothstep(0.05f, 0.4f, pg) else 0f
    // 包裹模式里按钮和面板是同一块东西：按钮形状直接等于面板，并集就只剩面板
    if (cover) btn = panel
    return DropdownFrame(btn, if (cover) panR else btnR * sc, panel, panR, goo, blend, sc, coverAmt)
}

// ───────────────────────── 杂项 ─────────────────────────

internal fun Offset.round() = IntOffset(x.roundToInt(), y.roundToInt())
