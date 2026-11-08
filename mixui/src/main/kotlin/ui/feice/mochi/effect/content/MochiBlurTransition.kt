// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.anim.MochiSprings
import ui.feice.mochi.basic.Text
import ui.feice.mochi.theme.LocalTextStyle
import ui.feice.mochi.theme.MochiGraphics

/**
 * 内容换掉时的「模糊替换」参数（iOS 的 blur replace / numericText 那一类内容过渡）：
 * 旧内容一边变糊、缩小、淡出，新内容从糊、小、透明里清晰起来，两者叠在原位交叉进行。
 *
 * @param blur 完全隐去时的模糊半径。模糊要 Android 12（API 31）以上，更低的系统只有淡入淡出和缩放
 * @param scale 完全隐去时的缩放
 * @param slide 完全隐去时沿 direction 方向的位移，按内容自己的高度算（计数器的数字滚动用它）
 * @param enter 新内容出现的动画（不该过冲，不然透明度和模糊会冲过头）
 * @param exit 旧内容消失的动画，比出现快一点：旧的先让开，新的再清晰起来
 * @param overflow 内容画出自己边界的距离。包着玻璃（外阴影画在玻璃外面）时传 [ui.feice.mochi.effect.glass.mochiGlassOverflow]：
 *   模糊和半透明的图层按这一圈往外留边，过渡中阴影不会被切成直角矩形。默认 0（文字、图标）
 */
@Immutable
public data class MochiBlurTransition(
    val blur: Dp = 8.dp,
    val scale: Float = 0.9f,
    val slide: Float = 0f,
    val enter: AnimationSpec<Float> = ContentSpring,
    val exit: AnimationSpec<Float> = ContentSpring,
    val overflow: Dp = 0.dp,
)

/** 内容变换统一的弹簧：SwiftUI 的 snappy，300ms、回弹 0.15（利落，收尾带一点点弹，不拖） */
internal val ContentSpring: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.001f)

/**
 * 计数器新数字的弹簧：阻尼比 0.58，越过终点约 12%（滚动距离的 12%，约字高的 7%）再回来，约 450ms 停稳。
 * 透明度和模糊照样截断在 1，回弹只体现在位移上。
 */
internal val CounterSpring: AnimationSpec<Float> = spring(dampingRatio = 0.58f, stiffness = 380f, visibilityThreshold = 0.001f)

/** 内置的几种模糊替换 */
public object MochiBlurTransitions {
    /** 文字、图标换成别的：原位交叉，轻微缩放 */
    public val Replace: MochiBlurTransition = MochiBlurTransition()

    /** 出现 / 消失：从更小、更糊里长出来 */
    public val Appear: MochiBlurTransition = MochiBlurTransition(blur = 10.dp, scale = 0.8f)

    /**
     * 计数器的一位数字：沿变化方向滚过去（变大往上、变小往下），带一点模糊，不缩放。
     * 新数字滚到位时冲过头一点再回来（iOS numericText 的回弹），旧数字利落地走掉不回弹。
     */
    public val Counter: MochiBlurTransition = MochiBlurTransition(
        blur = 5.dp,
        scale = 1f,
        slide = 0.55f,
        enter = CounterSpring,
        exit = ContentSpring,
    )
}

/** 叠在原位的一份内容：p = 1 完全显出，0 完全隐去 */
private class BlurSlot<T>(val key: T, initial: Float) {
    val p = Animatable(initial)

    /** 隐去时往哪边偏：+1 往下、−1 往上（乘上内容高度和 slide） */
    var sign by mutableFloatStateOf(0f)
    var job: Job? = null
}

/**
 * 内容换掉时做模糊替换：[targetState] 一变，旧内容变糊淡出、新内容从糊里清晰起来，叠在原位交叉进行；
 * 途中又换回来时，那份内容从当前的样子接着变清晰，不重新开始。
 *
 * 占的大小在新旧内容之间按进度平滑过渡（文字变长变短时，后面的东西被平滑推开，不跳）。
 * 内容是空的（什么都不画）时大小为 0，所以出现 / 消失也是它：见 [MochiBlurVisibility]。
 *
 * @param direction 内容往哪边走：+1 = 往上（新的从下面来、旧的往上走，数字变大），−1 = 往下，0 = 原位。
 *   只有 [MochiBlurTransition.slide] 不为 0 时才有位移
 * @param contentAlignment 新旧内容大小不同时，各自在占位里怎么对齐
 */
@Composable
public fun <T> MochiBlurCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    transition: MochiBlurTransition = MochiBlurTransitions.Replace,
    direction: Int = 0,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (T) -> Unit,
) {
    val slots = remember { mutableStateListOf(BlurSlot(targetState, 1f)) }
    val scope = rememberCoroutineScope()
    val dir by rememberUpdatedState(direction)
    val spec by rememberUpdatedState(transition)
    LaunchedEffect(targetState) {
        val d = dir.coerceIn(-1, 1).toFloat()
        for (s in slots.toList()) {
            if (s.key == targetState) continue
            // 旧内容往 direction 那边走掉（往上 = 负的 y）
            s.sign = -d
            s.job?.cancel()
            s.job = scope.launch {
                // 淡到看不见就拿掉（不等弹簧那段很长的尾巴）：少一个图层、少一次离屏模糊
                s.p.animateTo(0f, spec.exit) { if (value < 0.01f) slots.remove(s) }
                slots.remove(s)
            }
        }
        val s = slots.firstOrNull { it.key == targetState } ?: BlurSlot(targetState, 0f).also {
            // 新内容从反方向来
            it.sign = d
            slots.add(it)
        }
        s.job?.cancel()
        s.job = scope.launch { s.p.animateTo(1f, spec.enter) }
    }

    Layout(
        content = {
            for (s in slots) {
                key(s) {
                    BlurLayer(s, transition) { content(s.key) }
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val w: Int
        val h: Int
        if (placeables.all { it.width == placeables[0].width && it.height == placeables[0].height }) {
            // 新旧一样大（数字换数字、同宽的字）：不读进度，过渡期间不会每帧重新排版（连带外面一层层的父布局）
            w = placeables.firstOrNull()?.width ?: 0
            h = placeables.firstOrNull()?.height ?: 0
        } else {
            // 占位大小 = 各份内容的大小按显出程度加权平均
            var sw = 0f
            var sh = 0f
            var sp = 0f
            placeables.forEachIndexed { i, pl ->
                val p = slots.getOrNull(i)?.p?.value?.coerceIn(0f, 1f) ?: 0f
                sw += pl.width * p
                sh += pl.height * p
                sp += p
            }
            w = if (sp > 0.001f) (sw / sp).roundToInt() else 0
            h = if (sp > 0.001f) (sh / sp).roundToInt() else 0
        }
        // 出现 / 消失时另一份是空的（大小 0），加权平均自然从 0 长出来 / 缩回 0
        val lw = w.coerceIn(constraints.minWidth, constraints.maxWidth)
        val lh = h.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(lw, lh) {
            placeables.forEach { pl ->
                val o = contentAlignment.align(IntSize(pl.width, pl.height), IntSize(lw, lh), layoutDirection)
                pl.place(o)
            }
        }
    }
}

/**
 * 模糊按「每档 1.5 倍」分档复用同一个 BlurEffect 实例。
 *
 * HWUI 给带 RenderEffect 的图层缓存了「模糊后的画面」：内容没变、效果对象也没换，下一帧直接复用，
 * 只有平移 / 缩放 / 透明度在变也不用重新模糊。半径每帧都变（以前按 0.5px 一档）就等于每帧对每个模糊图层
 * 重做一遍离屏模糊，RenderThread 的 issue 和 GPU 都会炸。按 1.5 倍分档后，一次过渡每层只重算 8 次左右；
 * 档与档之间配合透明度一起变，看不出台阶。半径 < 0.75px 返回 null（不模糊、不走离屏）。只在主线程用
 */
internal fun mochiCachedBlur(blur: Float): BlurEffect? {
    // 图形档位：超高档半径 ×1.5（系统不支持时不模糊）
    if (!MochiGraphics.blur) return null
    val radius = blur * MochiGraphics.blurScale
    if (radius < 0.75f) return null
    val level = (ln(radius) / LnStep).roundToInt().coerceIn(0, MaxBlurLevel)
    return BlurCache[level] ?: run {
        val r = StepRatio.pow(level)
        BlurEffect(r, r, TileMode.Decal).also { BlurCache[level] = it }
    }
}

internal const val StepRatio = 1.5f
private val LnStep = ln(StepRatio)

/** 1.5^10 ≈ 58px，更大的按它算 */
internal const val MaxBlurLevel = 10

private val BlurCache = arrayOfNulls<BlurEffect>(MaxBlurLevel + 1)

/** [mochiCachedBlur] 给出的模糊效果对应的半径（px）；不是它给的按最大一档算 */
internal fun mochiCachedBlurRadius(e: BlurEffect): Float {
    val i = BlurCache.indexOfFirst { it === e }
    return StepRatio.pow(if (i >= 0) i else MaxBlurLevel)
}

/**
 * 模糊和隐去程度成正比：过渡到一半时正好是一半的模糊，整段过渡都看得出在糊开 / 聚拢。
 * （以前为了少几次离屏模糊按 hidden^1.5 走，一半时只剩 35%，看起来模糊很淡、过渡像是一闪就完了；
 * 现在模糊分档缓存、大半径降采样，按线性走也不贵。）几乎看不见（< 2%）时不模糊，画了也看不出来
 */
private fun <T> layerBlur(s: BlurSlot<T>, transition: MochiBlurTransition): Dp {
    val p = s.p.value.coerceIn(0f, 1f)
    val hidden = 1f - p
    return if (p * p * (3f - 2f * p) < 0.02f) 0.dp else transition.blur * hidden
}

@Composable
private fun <T> BlurLayer(s: BlurSlot<T>, transition: MochiBlurTransition, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier.graphicsLayer {
            val p = s.p.value.coerceIn(0f, 1f)
            val hidden = 1f - p
            // 透明度走 smoothstep：淡到一半时已经糊开了，不会看到两份清楚的字叠在一起
            val a = p * p * (3f - 2f * p)
            mochiBlurAlpha(a, layerBlur(s, transition))
            // 内容画出边界（玻璃阴影）时，半透明也不开按自身范围的离屏层：那一层会把阴影切成直角，透明度直接乘到每一笔上
            if (transition.overflow > 0.dp && a < 1f) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            // 缩放和位移用没截断的进度：snappy 弹簧收尾那一点点回弹在这里看得到（透明度、模糊照样截断）
            val raw = s.p.value
            val k = transition.scale + (1f - transition.scale) * raw
            scaleX = k
            scaleY = k
            translationY = s.sign * (1f - raw) * transition.slide * size.height
        }.mochiBlur(overflow = transition.overflow) { layerBlur(s, transition) },
    ) { measurables, constraints ->
        val pls = measurables.map { it.measure(constraints) }
        val w = pls.maxOfOrNull { it.width } ?: 0
        val h = pls.maxOfOrNull { it.height } ?: 0
        layout(w, h) { pls.forEach { it.place(0, 0) } }
    }
}

/**
 * 出现 / 消失做模糊淡入淡出：从更小、更糊、透明里清晰起来，消失时原路退回。
 * 占的大小跟着进度从 0 长到内容大小（旁边的东西被平滑推开）。
 */
@Composable
public fun MochiBlurVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    transition: MochiBlurTransition = MochiBlurTransitions.Appear,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    MochiBlurCrossfade(visible, modifier, transition, contentAlignment = contentAlignment) { if (it) content() }
}

/**
 * 文字换掉时做模糊替换（[MochiBlurCrossfade] 包了一层 [Text]）。同样的文字不会重新播放。
 * 想整段文字消失 / 出现，用 [MochiBlurVisibility] 包住它；数字用 [MochiCounterText]。
 */
@Composable
public fun MochiBlurText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    minFontScale: Float = 1f,
    transition: MochiBlurTransition = MochiBlurTransitions.Replace,
    contentAlignment: Alignment = Alignment.CenterStart,
) {
    MochiBlurCrossfade(text, modifier, transition, contentAlignment = contentAlignment) {
        Text(it, color = color, style = style, maxLines = maxLines, overflow = overflow, minFontScale = minFontScale)
    }
}
