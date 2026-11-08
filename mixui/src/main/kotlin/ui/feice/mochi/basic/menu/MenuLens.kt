// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic.menu

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions

/**
 * iOS 玻璃菜单开合时的透镜（AnimationKit LiquidMorphAnimation，iOS 26.0 24A435 逆向）。
 *
 * 开合一开始，形变里的内容经过一层透镜：
 * - 透镜强度 [lens]：0 秒起以 dampingRatio 1、response 0.2 升到 1，展开 0.12 秒 / 收起 0.1 秒后
 *   以 dampingRatio 1、response 0.6（展开）/ 0.4（收起）落回 0（时间线上的「Lens on」「Lens off」）。
 * - 透镜的 SDF 位移：高 75pt × 强度 × min(短边 / 250pt, 1)，位移量同值（effect.height、displacementMap.inputAmount）。
 * - 内容的模糊和放大 [blur]：0 秒起以 blurInSpring（1.0 / 0.3）走到模糊 4、放大 s，延迟同上之后以 blurOutSpring（0.8 / 0.45）
 *   回到不模糊、不放大。s 按展开后的高度 h 在 blurOutMaxScale 1.65（h ≤ 215pt）到 blurOutMinScale 1.2（h ≥ 520pt）之间线性取。
 * - kick [kick]：弹出的那块（菜单）竖着平移 −80pt × f × (2u − 1)，0 秒起以 dampingRatio 1、response 0.2 踢出去，0.1 秒后回位。
 *   u = clamp((按钮中心 y − 菜单顶) / 菜单高, 0, 1)，f = clamp((菜单高 − 60pt) / 140pt, 0.5, 1)：按钮在菜单上方时菜单先往下沉再收回。
 *   来源 [source] 反方向踢 40pt × f × (2u − 1)，0.15 秒后回位。来源是菜单脱出来的那个按钮组（[MochiBarGroupDropdownItem]），
 *   单独的按钮本身就变成了菜单，没有留下的来源。
 * - 两块玻璃的融合 [smooth]：0 秒起以 dampingRatio 1、response 0.1 把玻璃组的光滑并集半径升到 20pt（Smooth on），
 *   0.1 秒后以 dampingRatio 1、response 0.6 落回 0（Smooth off）：菜单从按钮组里拉出来时两块连成一片，随后断开。
 *
 * 近似（没逆出来的部分）：
 * - 展开 / 收起对应哪条分支是按「面积小的一方 → 面积大的一方」推断的（原生按两端面积比较选分支）；
 * - 短边按当前面板取（原生是另一组弹簧从起点短边动画到终点短边）；
 * - 位移的剖面沿用玻璃内折射的 `1 - sqrt(t(2 - t))`（CASDFGlassDisplacementEffect 的剖面没拆）；
 * - 模糊 4 按 sigma 处理（CAFilter 半径和 sigma 的换算没闭合）；
 * - 原生透镜作用在形变两端的 portal 上，这里只作用在菜单内容上，玻璃本身不加折射；
 * - kick 回位用的弹簧取 MorphSpec 的 kick 通道（0.8 / 0.35），回位那一步调用的弹簧没逐条对上；来源踢出去也按 1 / 0.2 算；
 * - 光滑并集的核按二次多项式（iq 的 smin）算，CASDF 用的核没拆；按钮组按圆角矩形（半径半高）算，不是连续曲率胶囊。
 */
internal class MenuLens {
    val lens = Animatable(0f)
    val blur = Animatable(0f)
    val kick = Animatable(0f)
    val source = Animatable(0f)
    val smooth = Animatable(0f)

    /**
     * 按开合走一遍时间线；再开 / 再关时打断上一遍，从当前值接着走。
     * [speed] 是 LiquidMorphAnimation settings 里的 speed：所有弹簧的 response 和延迟都除以它（默认 1；settings 里是 0.7，但实机放慢后拖沓，没用上）
     */
    suspend fun run(open: Boolean, speed: Float = 1f) = coroutineScope {
        fun spring(s: LensSpring) = MochiMotions.responseSpring(s.response / speed, s.damping)
        fun wait(ms: Long) = (ms / speed).toLong()
        launch { lens.animateTo(1f, spring(LensOn)) }
        launch { blur.animateTo(1f, spring(BlurInSpring)) }
        launch { kick.animateTo(1f, spring(KickOn)) }
        launch { source.animateTo(1f, spring(KickOn)) }
        launch { smooth.animateTo(1f, spring(SmoothOn)) }
        launch {
            delay(wait(150L))
            source.animateTo(0f, spring(KickOff))
        }
        delay(wait(100L))
        launch { kick.animateTo(0f, spring(KickOff)) }
        launch { smooth.animateTo(0f, spring(SmoothOff)) }
        if (open) delay(wait(20L))
        launch { lens.animateTo(0f, spring(if (open) LensOffGrow else LensOffShrink)) }
        launch { blur.animateTo(0f, spring(BlurOutSpring)) }
    }

    /** kick 的竖向平移（px）；[button] 是来源按钮，[target] 是展开后的面板 */
    fun kickOffset(density: Density, button: Rect, target: Rect): Float {
        val k = kick.value
        if (k == 0f || target.height <= 0f) return 0f
        val u = ((button.center.y - target.top) / target.height).coerceIn(0f, 1f)
        val f = ((with(density) { target.height.toDp().value } - 60f) / 140f).coerceIn(0.5f, 1f)
        return with(density) { (-80).dp.toPx() } * f * (2f * u - 1f) * k
    }

    /** 来源（按钮组）的竖向平移（px），和 [kickOffset] 反方向、幅度一半 */
    fun sourceOffset(density: Density, button: Rect, target: Rect): Float {
        val k = source.value
        if (k == 0f || target.height <= 0f) return 0f
        val u = ((button.center.y - target.top) / target.height).coerceIn(0f, 1f)
        val f = ((with(density) { target.height.toDp().value } - 60f) / 140f).coerceIn(0.5f, 1f)
        return with(density) { 40.dp.toPx() } * f * (2f * u - 1f) * k
    }

    /** 两块玻璃光滑并集的半径（px） */
    fun smoothRadius(density: Density): Float = with(density) { 20.dp.toPx() } * smooth.value.coerceAtLeast(0f)

    /** 内容的放大倍数；[targetHeight] 是展开后面板的高度（px） */
    fun scale(density: Density, targetHeight: Float): Float {
        val h = with(density) { targetHeight.toDp().value }
        val t = ((h - 215f) / 305f).coerceIn(0f, 1f)
        val s = 1.65f + (1.2f - 1.65f) * t
        return 1f + (s - 1f) * blur.value.coerceAtLeast(0f)
    }

    /**
     * 内容图层的 RenderEffect：先位移、再模糊。[panel] 是图层坐标里的当前面板（已经按 [scale] 换算回放大前），
     * [radius] 是它的圆角；位移按 1 / [scale] 缩小，放大后屏幕上还是原生的量
     */
    fun effect(density: Density, panel: Rect, radius: Float, scale: Float): RenderEffect? {
        val l = lens.value.coerceAtLeast(0f)
        val b = blur.value.coerceAtLeast(0f)
        val blurEffect = blurEffect(with(density) { (BlurSigma * b).toPx() })
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return blurEffect
        val k = min(min(panel.width, panel.height) * scale / with(density) { 250.dp.toPx() }, 1f)
        val height = with(density) { 75.dp.toPx() } * l * k / scale
        if (height < 0.5f) return blurEffect
        val lensEffect = displacement(panel, radius / scale, height)
        return if (blurEffect == null) lensEffect else chain(blurEffect, lensEffect)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun displacement(panel: Rect, radius: Float, height: Float): RenderEffect {
        val s = shader ?: RuntimeShader(DISPLACEMENT_AGSL).also { shader = it }
        s.setFloatUniform("rect", panel.left, panel.top, panel.right, panel.bottom)
        s.setFloatUniform("radius", radius.coerceAtMost(min(panel.width, panel.height) / 2f))
        s.setFloatUniform("height", height)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    }

    private var shader: RuntimeShader? = null
}

/** response / dampingRatio，按 speed 换算后才变成弹簧（见 [MenuLens.run]） */
private class LensSpring(val response: Float, val damping: Float)

private val LensOn = LensSpring(0.2f, 1f)
private val LensOffGrow = LensSpring(0.6f, 1f)
private val LensOffShrink = LensSpring(0.4f, 1f)
private val BlurInSpring = LensSpring(0.3f, 1f)
private val BlurOutSpring = LensSpring(0.45f, 0.8f)
private val BlurSigma = 4.dp
private val KickOn = LensSpring(0.2f, 1f)
private val KickOff = LensSpring(0.35f, 0.8f)
private val SmoothOn = LensSpring(0.1f, 1f)
private val SmoothOff = LensSpring(0.6f, 1f)

/** sigma 换成 RenderEffect 的半径（Skia：sigma = 0.57735·r + 0.5），Android 12 以下不模糊 */
private fun blurEffect(sigma: Float): RenderEffect? {
    if (sigma <= 0.5f || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val r = (sigma - 0.5f) / 0.57735f
    return androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Decal)
}

@RequiresApi(Build.VERSION_CODES.S)
private fun chain(outer: RenderEffect, inner: RenderEffect): RenderEffect =
    android.graphics.RenderEffect.createChainEffect(outer.asAndroidRenderEffect(), inner.asAndroidRenderEffect())
        .asComposeRenderEffect()

/** 圆角矩形 SDF 的位移：离边 height 以内往里取样，边上偏移 height，按 1 - sqrt(t(2 - t)) 变小 */
private const val DISPLACEMENT_AGSL = """
uniform shader content;
uniform float4 rect;
uniform float radius;
uniform float height;

float sdf(float2 p) {
    float2 c = (rect.xy + rect.zw) * 0.5;
    float2 h = (rect.zw - rect.xy) * 0.5;
    float2 q = abs(p - c) - h + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

half4 main(float2 p) {
    float depth = -sdf(p);
    if (depth <= 0.0 || depth >= height) return content.eval(p);
    float2 g = float2(sdf(p + float2(0.5, 0.0)) - sdf(p - float2(0.5, 0.0)), sdf(p + float2(0.0, 0.5)) - sdf(p - float2(0.0, 0.5)));
    float n = length(g);
    if (n < 1e-4) return content.eval(p);
    float t = depth / height;
    float off = height * (1.0 - sqrt(t * (2.0 - t)));
    return content.eval(p - g / n * off);
}
"""
