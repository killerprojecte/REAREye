// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.parts.ControlSmoothing
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.utils.rememberMochiHaptics

/**
 * 强度调整条，照 Codex 桌面版的思考强度滑条（`ModelPickerPowerSliderImpl`）做：24dp 的胶囊轨道，28dp 白色圆拇指上下各凸出 2dp，
 * 填充段跟着拇指走。适合推理强度、效果强度、通透度这类「越往右越猛」的量。
 *
 * - 无级（[stops] = 0）或分档（[stops] ≥ 2，每档一个小圆点，和 Codex 一样）
 * - 越往右越炫：值超过 [MochiIntensitySliderDefaults.HighFrom] 后，Codex 最高档的蓝紫流动渐变和漂移的星点逐渐浮现，
 *   到 1 时最浓，刻度同时淡出；拖到最右端时从拇指爆出一圈粒子。Codex 只在最高一档出现这些，这里按值渐进
 * - 处在高值时渐变和星点一直缓慢流动（照 Codex）；值低于 HighFrom、关闭、离开界面或系统关掉动画时不刷新
 * - [detent]（无级时，默认 0.5，传 null 关掉）：画一个刻度点，拖过时轻震并轻轻吸住，方便回到默认值；拖到两头、换档也各震一下
 * - 点哪里就滑到哪里；按下时拇指放大到 32/28
 *
 * @param value 当前值，0..1；分档时是 档序号 / (stops − 1)
 * @param stops 0 = 无级；≥ 2 = 分成这么多档（含两端）
 * @param startIcon / endIcon 两端的小图标（比如「透明」「色调」），参数是建议的图标颜色
 */
@Composable
public fun MochiIntensitySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stops: Int = 0,
    detent: Float? = 0.5f,
    colors: MochiIntensitySliderColors = MochiIntensitySliderDefaults.colors(),
    startIcon: (@Composable (Color) -> Unit)? = null,
    endIcon: (@Composable (Color) -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MochiIntensitySliderDefaults.IconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        startIcon?.invoke(colors.icon)
        IntensityTrack(value, onValueChange, enabled, stops.takeIf { it >= 2 } ?: 0, detent, colors, Modifier.weight(1f))
        endIcon?.invoke(colors.icon)
    }
}

@Composable
private fun IntensityTrack(
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    stops: Int,
    detent: Float?,
    colors: MochiIntensitySliderColors,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMochiHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 系统关掉了动画：和 Codex 的 reduced motion 一样，渐变停在第 0 帧，不爆散、不揭开
    val reduce = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val change by rememberUpdatedState(onValueChange)
    val pos = remember { Animatable(snap(value, stops)) }
    val thumbScale = remember { Animatable(1f) }
    var dragging by remember { mutableStateOf(false) }
    val fx = remember { HighEffects() }
    // 每帧换一个值，绘制读它重画；不在高值时不变
    var frame by remember { mutableLongStateOf(0L) }
    val last = remember { floatArrayOf(value) }

    LaunchedEffect(value) {
        val v = value.coerceIn(0f, 1f)
        if (!reduce) {
            // 刚越过 HighFrom 往上：蓝紫渐变从右往左揭开；刚到顶：爆一圈粒子
            if (last[0] <= MochiIntensitySliderDefaults.HighFrom && v > MochiIntensitySliderDefaults.HighFrom) fx.revealAt = fx.time
            if (last[0] < Top && v >= Top) fx.burstAt = fx.time
        }
        last[0] = v
        if (!dragging) pos.animateTo(snap(v, stops), tween(300, easing = MotionEasing))
    }
    // 高值时持续流动；回到 HighFrom 以下、爆散也放完就停
    LaunchedEffect(enabled, reduce) {
        if (!enabled || reduce) return@LaunchedEffect
        while (true) {
            snapshotFlow { high(pos.value) > 0f }.first { it }
            var prev = 0L
            while (high(pos.value) > 0f || fx.bursting()) {
                withFrameNanos { now ->
                    fx.step(if (prev == 0L) 0f else ((now - prev) / 1e9f).coerceAtMost(0.1f))
                    prev = now
                    frame = now
                }
            }
        }
    }

    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) HighFieldShader() else null }
    val trackCache = remember { G2PathCache() }
    val borderCache = remember { G2PathCache() }
    val outside = remember { Path() }
    val layerPaint = remember { androidx.compose.ui.graphics.Paint() }
    val thumbPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    Box(
        modifier
            .height(MochiIntensitySliderDefaults.Height)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f, steps = if (stops >= 2) stops - 2 else 0)
                if (enabled) {
                    setProgress { target ->
                        change(snap(target.coerceIn(0f, 1f), stops))
                        true
                    }
                }
            }
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(stops, detent) {
                        val side = Side.toPx()
                        val inset = MochiIntensitySliderDefaults.ThumbInset.toPx()
                        fun fraction(x: Float): Float {
                            val travel = (size.width - side * 2f - inset * 2f).coerceAtLeast(1f)
                            return ((x - side - inset) / travel).coerceIn(0f, 1f)
                        }

                        fun pick(x: Float): Float {
                            val f = snap(fraction(x), stops)
                            // 无级时默认值附近轻轻吸住
                            return if (stops == 0 && detent != null && abs(f - detent) < DetentPull) detent else f
                        }

                        fun move(f: Float) {
                            // 分档时每换一档滑 0.15s（Codex 拖动时的时长）；无级时跟手
                            scope.launch { if (stops >= 2) pos.animateTo(f, tween(150, easing = MotionEasing)) else pos.snapTo(f) }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            dragging = true
                            scope.launch { thumbScale.animateTo(PressedScale, PressSpring) }
                            var prev = pick(down.position.x)
                            if (stops >= 2) {
                                scope.launch { pos.animateTo(prev, tween(150, easing = MotionEasing)) }
                            } else {
                                scope.launch { pos.animateTo(prev, tween(300, easing = MotionEasing)) }
                            }
                            change(prev)
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                val f = pick(c.position.x)
                                if (f != prev) {
                                    val crossed = stops == 0 && detent != null && (prev - detent) * (f - detent) <= 0f
                                    if (stops >= 2 || crossed || f == 0f || f == 1f) haptics.tick()
                                    prev = f
                                    move(f)
                                    change(f)
                                }
                                c.consume()
                            }
                            dragging = false
                            scope.launch { thumbScale.animateTo(1f, ReleaseSpring) }
                        }
                    }
                },
            )
            .drawBehind {
                @Suppress("UNUSED_EXPRESSION")
                frame
                val alpha = if (enabled) 1f else MochiDefaults.DisabledAlpha
                val side = Side.toPx()
                val inset = MochiIntensitySliderDefaults.ThumbInset.toPx()
                val trackH = MochiIntensitySliderDefaults.TrackHeight.toPx()
                val left = side
                val w = size.width - side * 2f
                val top = (size.height - trackH) / 2f
                val cy = size.height / 2f
                val p = pos.value.coerceIn(0f, 1f)
                val cx = left + inset + (w - inset * 2f) * p
                val h = high(p)

                // 轨道：文字色 10% 的连续曲率胶囊（和库里其他控件同一种 G2 圆角）+ 0.5dp 内描边
                val trackRect = Rect(left, top, left + w, top + trackH)
                val trackPath = trackCache.get(trackRect, trackH / 2f, ControlSmoothing)
                drawPath(trackPath, colors.track, alpha = alpha)
                val hair = 0.5f * density
                drawPath(
                    borderCache.get(trackRect.deflate(hair / 2f), trackH / 2f - hair / 2f, ControlSmoothing),
                    colors.trackBorder,
                    style = Stroke(hair),
                    alpha = alpha,
                )

                // 填充段（Codex 的 Range）：整条轨道那么宽的一块，右边对齐到拇指中心；两端那一档里收拢到 0 / 满格
                val rangeRight = left + w * p + edgeShift(p, stops) * inset / ThumbInsetCss
                val rangeLeft = rangeRight - w
                if (rangeRight > left) {
                    // 按轨道形状裁：clipPath 没有抗锯齿，圆头会出锯齿。改成在只有轨道大小的一层里画，
                    // 再用抗锯齿的「轨道外面」那块把多出来的擦掉
                    val canvas = drawContext.canvas
                    canvas.saveLayer(trackRect, layerPaint)
                    clipBox(left, top, rangeRight, top + trackH) {
                        drawRect(colors.fill, Offset(rangeLeft, top), Size(w, trackH), alpha = alpha)
                        if (h > 0f) {
                            // 高值：蓝紫流动渐变 + 星点，都在填充段这块里面
                            drawHighField(shader, colors, rangeLeft, top, w, trackH, h * alpha, fx)
                            fx.drawStars(this, rangeLeft, top, w, trackH, h * alpha)
                        }
                    }
                    outside.rewind()
                    outside.fillType = PathFillType.EvenOdd
                    outside.addRect(trackRect.inflate(2f))
                    outside.addPath(trackPath)
                    drawPath(outside, Color.Black, blendMode = BlendMode.DstOut)
                    canvas.restore()
                }

                // 刻度：分档时每档一个；无级时只在 detent 画一个。高值时淡出、缩到 0.75
                val tickAlpha = (1f - h) * alpha
                if (tickAlpha > 0.01f) {
                    val r = MochiIntensitySliderDefaults.TickSize.toPx() / 2f * (1f - 0.25f * h)
                    fun tick(at: Float) {
                        val x = left + inset + (w - inset * 2f) * at
                        drawCircle(if (at <= p + 1e-4f) colors.tickOn else colors.tickOff, r, Offset(x, cy), alpha = tickAlpha)
                    }
                    if (stops >= 2) {
                        for (i in 0 until stops) tick(i / (stops - 1f))
                    } else if (detent != null) {
                        tick(detent)
                    }
                }

                // 到顶时的爆散：在拇指下面，可以飞出轨道
                fx.drawBurst(this, cx, cy, colors)

                // 拇指：白圆 + 0.5dp 描边 + 2dp 的淡阴影（0 0 2px 黑 10%）
                val radius = MochiIntensitySliderDefaults.ThumbSize.toPx() / 2f * thumbScale.value
                drawIntoCanvas { canvas ->
                    thumbPaint.color = colors.thumb.copy(alpha = colors.thumb.alpha * alpha).toArgb()
                    thumbPaint.setShadowLayer(2f * density, 0f, 0f, Color.Black.copy(alpha = 0.1f * alpha).toArgb())
                    canvas.nativeCanvas.drawCircle(cx, cy, radius, thumbPaint)
                }
                drawCircle(colors.thumbBorder, radius - hair / 2f, Offset(cx, cy), style = Stroke(hair), alpha = alpha)
            },
    )
}

/** 高值效果的强度：HighFrom 以下是 0，到 1 时满 */
private fun high(v: Float): Float {
    val t = ((v - MochiIntensitySliderDefaults.HighFrom) / (1f - MochiIntensitySliderDefaults.HighFrom)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun snap(v: Float, stops: Int): Float {
    val c = v.coerceIn(0f, 1f)
    return if (stops >= 2) (c * (stops - 1)).roundToInt() / (stops - 1f) else c
}

/**
 * 填充段右边离「值 × 轨道宽」多远（CSS px，按 13 的内收算）：中间对齐拇指中心，两端那一档里线性收拢，
 * 0 时没有填充、1 时填满（Codex 的 `st()`）。无级时按 10% 当一档。
 */
private fun edgeShift(p: Float, stops: Int): Float {
    val n = if (stops >= 2) 1f / (stops - 1) else 0.1f
    val ct = ThumbInsetCss - p * 2f * ThumbInsetCss
    return ct * min(min(p / n, (1f - p) / n), 1f)
}

private fun DrawScope.clipBox(l: Float, t: Float, r: Float, b: Float, block: DrawScope.() -> Unit) = drawContext.canvas.let { c ->
    c.save()
    c.clipRect(l, t, r, b)
    block()
    c.restore()
}

/** 蓝紫流动渐变；Android 12 及以下没有 RuntimeShader，只画静止的横向渐变 */
private fun DrawScope.drawHighField(
    shader: HighFieldShader?,
    colors: MochiIntensitySliderColors,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    alpha: Float,
    fx: HighEffects,
) {
    if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        shader.update(x, y, w, h, fx.time, alpha, fx.reveal(), 120f * density, colors)
        drawRect(shader.brush, Offset(x, y), Size(w, h))
    } else {
        val a = lerp(colors.highBlue, colors.highPurple, 0.4f)
        val c = lerp(colors.highPurple, colors.highBlue, 0.28f)
        drawRect(
            Brush.horizontalGradient(0f to a, 0.55f to colors.highPurple, 1f to c, startX = x, endX = x + w),
            Offset(x, y),
            Size(w, h),
            alpha = alpha,
        )
    }
}

/** 高值效果的时钟、星点和爆散（Codex 的 TrackParticles / Burst）。只在动画循环里前进 */
private class HighEffects {
    /** 秒；只在高值、循环在跑时前进 */
    var time = 0f
        private set
    var revealAt = -1f
    var burstAt = -1f

    private val base = FloatArray(StarCount) { (4f + hash(it, 14) * 92f).roundToInt() / 100f }
    private val opacity = FloatArray(StarCount) { 0.4f + hash(it, 11) * 0.6f }
    private val scale = FloatArray(StarCount) { 0.5f + hash(it, 12) * 0.45f }
    private val fromX = FloatArray(StarCount)
    private val toX = FloatArray(StarCount)
    private val fromY = FloatArray(StarCount)
    private val toY = FloatArray(StarCount)
    private val start = FloatArray(StarCount)
    private val dur = FloatArray(StarCount) { 1f }
    private val next = FloatArray(StarCount)
    private val rnd = Random(11)

    init {
        // 初始位置按序号定死（Codex 的 V()：同一个哈希依次取三个数）
        for (i in 0 until StarCount) {
            val ox = ((hash(i, 1) - 0.5f) * 8f).roundToInt().toFloat()
            val oy = (12f + (hash(i, 2) - 0.5f) * 14f).roundToInt().toFloat()
            fromX[i] = ox
            toX[i] = ox
            fromY[i] = oy
            toY[i] = oy
        }
    }

    fun step(dt: Float) {
        time = (time + dt) % 3600f
        // 每颗星到点就换一个新目标：横向偏 ±4、纵向 12±7，过渡时长 = 1.6s ×（0.8..2.0）
        for (i in 0 until StarCount) {
            if (time < next[i] && time >= start[i]) continue
            fromX[i] = lerpF(fromX[i], toX[i], progress(i))
            fromY[i] = lerpF(fromY[i], toY[i], progress(i))
            val a = rnd.nextFloat()
            val b = rnd.nextFloat()
            dur[i] = StarPeriod * (0.8f + rnd.nextFloat() * 1.2f)
            toX[i] = ((a - 0.5f) * 8f).roundToInt().toFloat()
            toY[i] = (12f + (b - 0.5f) * 14f).roundToInt().toFloat()
            start[i] = time
            next[i] = time + dur[i]
        }
    }

    private fun progress(i: Int): Float = StarEasing.transform(((time - start[i]) / dur[i]).coerceIn(0f, 1f))

    fun bursting(): Boolean = burstAt >= 0f && time - burstAt < BurstDuration + 0.02f

    /** 揭开遮罩的位置：1 = 只露出最右边，0 = 全露出（2s，CSS 默认 ease） */
    fun reveal(): Float {
        if (revealAt < 0f) return 0f
        val t = ((time - revealAt) / RevealDuration).coerceIn(0f, 1f)
        return 1f - RevealEasing.transform(t)
    }

    fun drawStars(scope: DrawScope, x: Float, y: Float, w: Float, h: Float, alpha: Float) = with(scope) {
        val dp = density
        val bandTop = y + (h - 24f * dp) / 2f
        for (i in 0 until StarCount) {
            // 越往右亮起的星越多：第 i 颗在 alpha > i/14 之后才淡入
            val k = ((alpha * StarCount) - i).coerceIn(0f, 1f) * opacity[i]
            if (k <= 0.01f) continue
            val u = progress(i)
            val cx = x + w * base[i] + lerpF(fromX[i], toX[i], u) * dp
            val cy = bandTop + lerpF(fromY[i], toY[i], u) * dp
            val r = 1.5f * dp * scale[i]
            // box-shadow 0 0 5px #ffffff57
            drawCircle(Color.White, r + 2.5f * dp * scale[i], Offset(cx, cy), alpha = 0.34f * 0.35f * k)
            drawCircle(Color(0xB8FFFFFF), r, Offset(cx, cy), alpha = k)
        }
    }

    fun drawBurst(scope: DrawScope, cx: Float, cy: Float, colors: MochiIntensitySliderColors) = with(scope) {
        if (!bursting()) return@with
        val dp = density
        for (i in 0 until BurstCount) {
            val t = ((time - burstAt - BurstDelay[i]) / BurstDuration).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) continue
            val o: Float
            val s: Float
            val m: Float
            if (t < 0.22f) {
                val e = BurstEasing.transform(t / 0.22f)
                o = e
                s = lerpF(0.25f, 1.28f, e)
                m = 0f
            } else {
                val e = BurstEasing.transform((t - 0.22f) / 0.78f)
                o = 1f - e
                s = lerpF(1.28f, 0.55f, e)
                m = e
            }
            // :nth-child(4n+3) 紫 82% + 蓝，:nth-child(4n+1)（第一颗除外）紫 68% + 蓝，其余纯紫
            val c = when {
                i % 4 == 2 -> lerp(colors.highPurple, colors.highBlue, 0.18f)
                i % 4 == 0 && i > 0 -> lerp(colors.highPurple, colors.highBlue, 0.32f)
                else -> colors.highPurple
            }
            drawCircle(c, 2.5f * dp * s, Offset(cx + BurstX[i] * dp * m, cy + BurstY[i] * dp * m), alpha = o)
        }
    }

    private companion object {
        const val StarCount = 14
        const val StarPeriod = 1.6f
        const val BurstCount = 16
        const val BurstDuration = 0.62f
        const val RevealDuration = 2f
        val BurstX = floatArrayOf(-3f, 15f, 30f, 34f, 26f, 12f, -6f, -22f, -32f, -32f, -21f, 7f, 24f, 20f, -9f, -25f)
        val BurstY = floatArrayOf(-34f, -29f, -19f, -2f, 20f, 31f, 34f, 26f, 9f, -10f, -26f, -24f, -9f, 10f, 21f, -5f)
        val BurstDelay = floatArrayOf(0f, 0.008f, 0f, 0.012f, 0f, 0.010f, 0f, 0.014f, 0f, 0.008f, 0f, 0.006f, 0f, 0.010f, 0f, 0.012f)
    }
}

/** Codex 的 `y(e, t)`：fract(sin((e+1)·12.9898 + t·78.233)·43758.5453)，用 double 算，和 JS 一致 */
private fun hash(e: Int, t: Int): Float {
    val n = sin((e + 1) * 12.9898 + t * 78.233) * 43758.5453
    return (n - floor(n)).toFloat()
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Codex 的高值渐变着色器（逐行移植），再按 mix-blend-mode: luminosity 叠到横向的蓝紫渐变上 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class HighFieldShader {
    private val shader = RuntimeShader(HIGH_FIELD_AGSL)
    val brush = ShaderBrush(shader)

    fun update(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        time: Float,
        alpha: Float,
        reveal: Float,
        feather: Float,
        colors: MochiIntensitySliderColors,
    ) {
        shader.setFloatUniform("uOrigin", x, y)
        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uAlpha", alpha)
        shader.setFloatUniform("uReveal", reveal)
        shader.setFloatUniform("uFeather", feather)
        shader.setFloatUniform("uBlue", colors.highBlue.red, colors.highBlue.green, colors.highBlue.blue)
        shader.setFloatUniform("uPurple", colors.highPurple.red, colors.highPurple.green, colors.highPurple.blue)
    }
}

/** CSS 里拇指内收的 13px，edgeShift 按它算，再换成 dp */
private const val ThumbInsetCss = 13f

/** 拇指在 0 时左边比轨道多出 1dp，留出这点空 */
private val Side = androidx.compose.ui.unit.Dp(1f)

private const val Top = 0.999f

/** 离默认值多近时吸住（占全程的比例） */
private const val DetentPull = 0.025f

/** 按下时拇指 28 → 32 */
private const val PressedScale = 32f / 28f

/** Codex：cubic-bezier(.23, 1, .32, 1) */
private val MotionEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val StarEasing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)
private val BurstEasing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private val RevealEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

// framer-motion 弹簧：按下 stiffness 420 / damping 38，松开 220 / 26（mass 1），换成阻尼比
private val PressSpring = spring<Float>(dampingRatio = 38f / (2f * kotlin.math.sqrt(420f)), stiffness = 420f)
private val ReleaseSpring = spring<Float>(dampingRatio = 26f / (2f * kotlin.math.sqrt(220f)), stiffness = 220f)

private const val HIGH_FIELD_AGSL = """
uniform float2 uOrigin;
uniform float2 uSize;
uniform float uTime;
uniform float uAlpha;
uniform float uReveal;
uniform float uFeather;
uniform float3 uBlue;
uniform float3 uPurple;

const float3 COLOR_1 = float3(0.592, 0.388, 0.945);
const float3 COLOR_2 = float3(0.831, 0.710, 0.953);
const float3 COLOR_3 = float3(0.286, 0.000, 0.404);
const float3 COLOR_4 = float3(0.145, 0.055, 0.478);
const float3 COLOR_5 = float3(0.592, 0.000, 0.996);
const float3 COLOR_6 = float3(0.780, 0.459, 0.914);
const float3 COLOR_7 = float3(0.725, 0.576, 1.000);
const float3 COLOR_8 = float3(0.400, 0.212, 0.820);
const float3 COLOR_9 = float3(0.882, 0.690, 1.000);
const float3 COLOR_10 = float3(0.498, 0.345, 0.957);
const float3 COLOR_11 = float3(0.659, 0.275, 0.910);
const float3 COLOR_12 = float3(0.212, 0.063, 0.400);

float grain(float2 uv) {
    float2 grainUv = uv * uSize * 0.5;
    return fract(sin(dot(grainUv + uTime, float2(12.9898, 78.233))) * 43758.5453) * 2.0 - 1.0;
}

float fieldWeight(float2 point, float2 center) {
    return exp(-dot(point - center, point - center) * 12.0);
}

float3 fieldColor(float2 rawUv) {
    const float speed = 1.25;
    float2 uv = float2(rawUv.x, 0.40 + rawUv.y * 0.18);
    float2 s = float2(1.55, 1.0);
    float2 point = uv * s;
    float t = uTime * speed;
    float2 c1 = float2(0.18 + sin(t * 0.42) * 0.18, 0.36 + cos(t * 0.50) * 0.42) * s;
    float2 c2 = float2(0.34 + cos(t * 0.62) * 0.24, 0.62 + sin(t * 0.47) * 0.38) * s;
    float2 c3 = float2(0.52 + sin(t * 0.38) * 0.28, 0.30 + cos(t * 0.58) * 0.36) * s;
    float2 c4 = float2(0.70 + cos(t * 0.54) * 0.24, 0.68 + sin(t * 0.41) * 0.36) * s;
    float2 c5 = float2(0.88 + sin(t * 0.74) * 0.16, 0.36 + cos(t * 0.64) * 0.40) * s;
    float2 c6 = float2(0.12 + cos(t * 0.48) * 0.20, 0.72 + sin(t * 0.70) * 0.30) * s;
    float2 c7 = float2(0.30 + sin(t * 0.58) * 0.22, 0.44 + cos(t * 0.52) * 0.42) * s;
    float2 c8 = float2(0.46 + cos(t * 0.68) * 0.26, 0.72 + sin(t * 0.56) * 0.32) * s;
    float2 c9 = float2(0.60 + sin(t * 0.44) * 0.28, 0.26 + cos(t * 0.60) * 0.38) * s;
    float2 c10 = float2(0.76 + cos(t * 0.50) * 0.22, 0.54 + sin(t * 0.66) * 0.40) * s;
    float2 c11 = float2(0.92 + sin(t * 0.70) * 0.15, 0.66 + cos(t * 0.46) * 0.30) * s;
    float2 c12 = float2(0.06 + cos(t * 0.40) * 0.14, 0.32 + sin(t * 0.60) * 0.40) * s;
    float w1 = fieldWeight(point, c1) * (0.7 + 0.3 * sin(uTime * 0.91));
    float w2 = fieldWeight(point, c2) * (0.7 + 0.3 * cos(uTime * 1.07));
    float w3 = fieldWeight(point, c3) * (0.7 + 0.3 * sin(uTime * 0.76));
    float w4 = fieldWeight(point, c4) * (0.7 + 0.3 * cos(uTime * 1.18));
    float w5 = fieldWeight(point, c5) * (0.7 + 0.3 * sin(uTime * 1.03));
    float w6 = fieldWeight(point, c6) * (0.7 + 0.3 * cos(uTime * 0.83));
    float w7 = fieldWeight(point, c7) * (0.7 + 0.3 * sin(uTime * 1.24));
    float w8 = fieldWeight(point, c8) * (0.7 + 0.3 * cos(uTime * 0.96));
    float w9 = fieldWeight(point, c9) * (0.7 + 0.3 * sin(uTime * 1.11));
    float w10 = fieldWeight(point, c10) * (0.7 + 0.3 * cos(uTime * 0.72));
    float w11 = fieldWeight(point, c11) * (0.7 + 0.3 * sin(uTime * 1.29));
    float w12 = fieldWeight(point, c12) * (0.7 + 0.3 * cos(uTime * 0.88));
    float total = max(w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8 + w9 + w10 + w11 + w12, 0.0001);
    float3 color = (COLOR_1 * w1 + COLOR_2 * w2 + COLOR_3 * w3 + COLOR_4 * w4 + COLOR_5 * w5 + COLOR_6 * w6 +
        COLOR_7 * w7 + COLOR_8 * w8 + COLOR_9 * w9 + COLOR_10 * w10 + COLOR_11 * w11 + COLOR_12 * w12) / total;
    color = mix(COLOR_4, color, 0.96);
    return pow(clamp(color, float3(0.0), float3(1.0)), float3(0.9));
}

// W3C 合成规范里 luminosity 混合用的 Lum / ClipColor / SetLum
float lum(float3 c) { return dot(c, float3(0.3, 0.59, 0.11)); }

float3 clipColor(float3 c) {
    float l = lum(c);
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = l + (c - l) * l / (l - n);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l);
    return c;
}

float3 setLum(float3 c, float l) { return clipColor(c + (l - lum(c))); }

half4 main(float2 fragCoord) {
    float2 local = fragCoord - uOrigin;
    // WebGL 的 vUv 原点在左下
    float2 uv = float2(local.x / uSize.x, 1.0 - local.y / uSize.y);
    float3 color = fieldColor(uv);
    color += grain(uv) * 0.012;
    color.r += sin(uTime * 0.5) * 0.02;
    color.g += cos(uTime * 0.7) * 0.02;
    color.b += sin(uTime * 0.6) * 0.02;
    color = pow(max(color, float3(0.0)), float3(0.92));

    // 底下那条横向渐变：蓝 60% + 紫 → 紫（55%）→ 紫 72% + 蓝
    float x = clamp(uv.x, 0.0, 1.0);
    float3 s0 = mix(uPurple, uBlue, 0.6);
    float3 s2 = mix(uBlue, uPurple, 0.72);
    float3 base = x < 0.55 ? mix(s0, uPurple, x / 0.55) : mix(uPurple, s2, (x - 0.55) / 0.45);
    float3 outC = setLum(base, lum(color));

    // 揭开：遮罩位置从右往左扫，前沿 uFeather 宽的羽化
    float edge = uReveal * uSize.x;
    float m = clamp((local.x - (edge - uFeather)) / uFeather, 0.0, 1.0);
    float a = uAlpha * m;
    return half4(half3(outC * a), half(a));
}
"""
