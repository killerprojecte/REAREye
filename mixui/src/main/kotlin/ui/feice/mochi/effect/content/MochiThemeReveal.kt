// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import ui.feice.mochi.navigation.MochiScreenCorners
import ui.feice.mochi.navigation.rememberMochiScreenCorners
import ui.feice.mochi.theme.MochiGraphics

/**
 * 主题揭开转场的状态：[reveal] 先拍下当前画面，再执行改主题，新主题从手指处（或给定的点）揭开。
 * 用 [rememberMochiThemeRevealState] 创建，交给 [MochiThemeRevealHost]。
 */
@Stable
public class MochiThemeRevealState internal constructor(private val scope: CoroutineScope, private val animate: Boolean) {
    internal var view: View? = null
    internal var bounds: Rect = Rect.Zero
    internal var lastPress: Offset? = null
    internal var corners: MochiScreenCorners = MochiScreenCorners.None

    internal var frozen by mutableStateOf<ImageBitmap?>(null)
    internal var origin by mutableStateOf(Offset.Zero)
    internal var fromScale by mutableStateOf(StartScale)

    /** 这一次是模糊交叉淡化（[crossfade]），不是揭开 */
    internal var crossfading by mutableStateOf(false)
    internal val progress = Animatable(1f)

    // 每次点一下 +1；拍照回来时已经有更新的点击，就把自己的改动交给最新的那次一起做
    private var generation = 0
    private val pending = mutableListOf<() -> Unit>()
    private var running: Job? = null

    /**
     * 正在揭开：这期间改的配色应当一步到位（[ui.feice.mochi.theme.animateMochiColorScheme] 的 animate = false），
     * 否则洞里露出来的是还在插值的半旧颜色。连续切换时一直为 true，直到最后一次揭完。
     */
    public var isRevealing: Boolean by mutableStateOf(false)
        private set

    /**
     * 拍下当前画面 → 执行 [change] → 从 [origin]（默认是最近一次按下的位置）揭开新主题。
     *
     * - 拍照、换主题、开始动画分在不同的帧：盖上截图一帧、换主题（整页重新排版的重活）一帧、之后才开始计时，
     *   重活那一帧不会让画面「抽一下」，动画也不会因为长帧直接跳过开头；
     * - 揭开途中再切：上一次的揭开先接着走，等新的截图（屏幕上一半旧一半新的样子）回来才无缝接上，不会先跳到揭完。
     */
    public fun reveal(origin: Offset? = null, change: () -> Unit) {
        start(origin, fade = false, change)
    }

    /**
     * 内容模糊转场：拍下当前画面 → 执行 [change] → 盖在上面的旧画面一边变糊一边淡出，底下的新画面同时从模糊里清晰起来，原位交叉，不扩散。
     * 用于换整套风格（字体、图标都变）但不想要「从手指扩散」的地方。颜色的过渡由这层交叉淡化完成：
     * 期间 [isRevealing] 为 true，配色一步到位，不再逐帧插值（插值会让整棵树每帧重组、转场卡顿）。
     */
    public fun crossfade(change: () -> Unit) {
        start(null, fade = true, change)
    }

    private fun start(origin: Offset?, fade: Boolean, change: () -> Unit) {
        val view = view
        val b = bounds
        if (!animate || (!fade && !MochiGraphics.reveal) || view == null || b.width < 1f || b.height < 1f ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        ) {
            flush()
            change()
            return
        }
        val from = origin ?: lastPress ?: Offset(b.width / 2f, b.height / 2f)
        val gen = ++generation
        pending += change
        scope.launch {
            val shot = captureWindow(view, b)
            if (gen != generation) return@launch
            running?.cancel()
            running = coroutineContext[Job]
            if (shot == null) {
                frozen = null
                isRevealing = false
                flush()
                return@launch
            }
            val chained = frozen != null
            crossfading = fade
            // 两种转场期间配色都一步到位：新画面盖在截图下面，不需要再插值；
            // 插值时整棵树每帧都要重组，正是模糊转场「顿一下」的来源
            isRevealing = true
            frozen = shot
            this@MochiThemeRevealState.origin = from
            fromScale = if (chained) ChainScale else StartScale
            progress.snapTo(0f)
            withFrameNanos { }
            flush()
            withFrameNanos { }
            withFrameNanos { }
            try {
                val millis = if (fade) {
                    FadeMillis
                } else if (chained) {
                    ChainMillis
                } else {
                    RevealMillis
                }
                progress.animateTo(1f, tween(millis, easing = if (fade) FadeEasing else RevealEasing))
            } finally {
                if (gen == generation) {
                    frozen = null
                    isRevealing = false
                }
            }
        }
    }

    private fun flush() {
        val changes = pending.toList()
        pending.clear()
        changes.forEach { it() }
    }
}

/**
 * 用 PixelCopy 拷屏幕上这块区域真正显示出来的像素。不从图层离屏生成：嵌套的硬件图层、模糊、
 * 硬件位图在离屏录制里不可靠，拷屏幕像素最稳。拷完转成硬件位图（只上传一次、不占 Java 堆）。
 * API 26 以下或拷贝失败返回 null（调用方直接切换）。
 */
private suspend fun captureWindow(view: View, bounds: Rect): ImageBitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val window = view.context.findActivity()?.window ?: return null
    val src = android.graphics.Rect(
        bounds.left.roundToInt(),
        bounds.top.roundToInt(),
        bounds.right.roundToInt(),
        bounds.bottom.roundToInt(),
    )
    if (src.width() <= 0 || src.height() <= 0) return null
    val bitmap = runCatching { Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
    val ok = suspendCancellableCoroutine { cont ->
        try {
            PixelCopy.request(
                window,
                src,
                bitmap,
                { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: IllegalArgumentException) {
            if (cont.isActive) cont.resume(false)
        }
    }
    if (!ok) return null
    val hardware = runCatching { bitmap.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
    if (hardware != null) bitmap.recycle()
    return (hardware ?: bitmap).asImageBitmap()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 换配色的统一入口：有揭开状态就从 [origin] 揭开（见 [MochiThemeRevealState.reveal]），
 * 没有（状态是 null，比如预览、测试里没接 [MochiThemeRevealHost]）就直接改，颜色照常插值过去
 */
public fun MochiThemeRevealState?.revealOrChange(origin: Offset? = null, change: () -> Unit) {
    if (this == null) change() else reveal(origin, change)
}

/**
 * 换明暗：深浅真的变了（[fromDark] ≠ [toDark]）才揭开；没变（比如系统是浅色时在「浅色」和「自动」之间切、或者只换了取色方式）
 * 就直接改，颜色自己插值过去，不硬上揭开动画。深浅用 [ui.feice.mochi.theme.ColorSchemeMode.isDark] 算
 */
public fun MochiThemeRevealState?.revealIfShadeChanges(fromDark: Boolean, toDark: Boolean, origin: Offset? = null, change: () -> Unit) {
    if (fromDark != toDark) revealOrChange(origin, change) else change()
}

/** 换整套风格（字体、图标、外观都变）：有状态就原位模糊交叉（[MochiThemeRevealState.crossfade]），没有就直接改 */
public fun MochiThemeRevealState?.crossfadeOrChange(change: () -> Unit) {
    if (this == null) change() else crossfade(change)
}

/** 创建揭开转场的状态。系统关掉动画时 [MochiThemeRevealState.reveal] 直接执行改动。 */
@Composable
public fun rememberMochiThemeRevealState(): MochiThemeRevealState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(scope) {
        val animate = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        MochiThemeRevealState(scope, animate)
    }
}

/**
 * 整套换装（换主题、换明暗）的转场：像 iOS / Telegram 切深浅色那样，新主题从手指处揭开。
 *
 * - 字体、图标、控件配色整套换掉的切换没法插值，就用拍照 + 揭开：旧画面盖在上面，中间挖一个越来越大的洞；
 * - 洞一开始是圆，碰到屏幕边就贴着边铺开，四个角收成屏幕自己的圆角：最后一刻洞的形状就是屏幕，
 *   不会剩几块弧形的旧画面卡在屏幕圆角上；
 * - 洞口羽化，越到后面越清晰，带一圈很淡的光；底下的新画面从 0.97 放大回 1，像从下面浮上来；
 * - 种子色这种连续参数不要用它，交给配色插值（[ui.feice.mochi.theme.animateMochiColorScheme]）。
 *
 * 包在整个 App 外面（主题外面也行：它只拍画面，不读主题）。拍照用 PixelCopy，需要 Android 8.0 以上，更低直接切换。
 */
@Composable
public fun MochiThemeRevealHost(state: MochiThemeRevealState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    state.view = LocalView.current
    state.corners = rememberMochiScreenCorners()
    val layerPaint = remember { Paint() }
    val holePaint = remember { Paint().apply { blendMode = BlendMode.DstOut } }
    val glowPaint = remember { Paint().apply { style = PaintingStyle.Stroke } }
    val hole = remember { Path() }
    Box(
        modifier
            .onGloballyPositioned { state.bounds = it.boundsInWindow() }
            .pointerInput(state) {
                // 只记住按下的位置，不消费：揭开的中心默认就在手指那里
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) e.changes.firstOrNull()?.let { state.lastPress = it.position }
                    }
                }
            }
            // 模糊交叉淡化：底下的新画面从模糊里清晰起来（半径逐帧连续，不用分级缓存，免得一级一级跳）
            .graphicsLayer {
                val r = if (state.frozen != null && state.crossfading) {
                    (1f - state.progress.value) * FadeInBlur.toPx() * MochiGraphics.blurScale
                } else {
                    0f
                }
                renderEffect = if (MochiGraphics.blur && r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
            }
            .drawWithContent {
                val shot = state.frozen
                if (shot == null) {
                    drawContent()
                    return@drawWithContent
                }
                if (state.crossfading) {
                    drawContent()
                    return@drawWithContent
                }
                val p = state.progress.value
                val s = state.fromScale + (1f - state.fromScale) * p
                scale(s, s) { this@drawWithContent.drawContent() }

                val o = state.origin
                val w = size.width
                val h = size.height
                val c = state.corners
                val dTl = hypot(o.x, o.y)
                val dTr = hypot(w - o.x, o.y)
                val dBr = hypot(w - o.x, h - o.y)
                val dBl = hypot(o.x, h - o.y)
                val far = max(max(dTl, dTr), max(dBr, dBl))
                val r = far * p
                // 圆的外接方框，碰到屏幕边就贴边
                val rect = Rect(max(0f, o.x - r), max(0f, o.y - r), min(w, o.x + r), min(h, o.y + r))

                // 每个角：洞还没够到那个屏幕角时是圆（半径 r），越接近越收成屏幕的圆角
                fun corner(d: Float, screen: Float): CornerRadius {
                    val k = smooth((r - d * 0.55f) / (d * 0.45f + 1f))
                    val v = (r + (screen - r) * k).coerceIn(0f, min(rect.width, rect.height) / 2f)
                    return CornerRadius(v)
                }
                hole.reset()
                if (rect.width > 0f && rect.height > 0f) {
                    hole.addRoundRect(
                        RoundRect(
                            rect,
                            topLeft = corner(dTl, c.topLeft),
                            topRight = corner(dTr, c.topRight),
                            bottomRight = corner(dBr, c.bottomRight),
                            bottomLeft = corner(dBl, c.bottomLeft),
                        ),
                    )
                }
                // 羽化随进度收掉：最后一刻边缘是清楚的，正好就是屏幕的轮廓
                // 图形档位「中」：清楚的硬边，没有光晕
                val soft = MochiGraphics.softReveal
                val feather = if (soft) 20.dp.toPx() * (1f - p) else 0f
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(0f, 0f, w, h), layerPaint)
                    drawImage(shot)
                    if (!hole.isEmpty) {
                        holePaint.asFrameworkPaint().maskFilter =
                            if (feather > 0.5f) BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL) else null
                        canvas.drawPath(hole, holePaint)
                    }
                    canvas.restore()
                    // 洞口一圈淡光，揭到后面慢慢淡掉
                    val glow = 0.16f * (1f - p)
                    if (!hole.isEmpty && glow > 0.005f && feather > 1f) {
                        glowPaint.color = Color.White.copy(alpha = glow)
                        glowPaint.strokeWidth = feather
                        glowPaint.asFrameworkPaint().maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
                        canvas.drawPath(hole, glowPaint)
                    }
                }
            },
    ) {
        content()
        // 模糊交叉淡化：旧画面盖在上面，变糊、淡出
        val shot = state.frozen
        if (shot != null && state.crossfading) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val p = state.progress.value
                        alpha = 1f - p
                        // 半径逐帧连续变化，不用按 1.5 倍分级缓存的模糊，否则会一级一级地跳（之前「顿一下」的来源）
                        val r = p * FadeOutBlur.toPx() * MochiGraphics.blurScale
                        renderEffect = if (MochiGraphics.blur && r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                    },
            ) { drawImage(shot) }
        }
    }
}

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val RevealMillis = 640
private const val FadeMillis = 500
private val FadeInBlur = 12.dp
private val FadeOutBlur = 18.dp

// 模糊交叉淡化：和配色插值（420ms FastOutSlowIn）差不多的节奏
private val FadeEasing = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)
private const val ChainMillis = 500
private const val StartScale = 0.97f
private const val ChainScale = 0.99f

// 起步快、收尾很长：先冲出去，最后贴着屏幕轮廓慢慢收
private val RevealEasing = CubicBezierEasing(0.3f, 0f, 0.12f, 1f)
