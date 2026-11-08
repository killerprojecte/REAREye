// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass.engine

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.kyant.capsule.path.PathSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import ui.feice.mochi.shape.MochiContinuity
import ui.feice.mochi.theme.MochiGlassRecipe
import ui.feice.mochi.utils.lerp

/** Exterior-only shadow: the same G2 distance and softness as the glass finishing pass, without a backdrop input. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun glassOuterShadowShader(): RuntimeShader = RuntimeShader(
    GLASS_COMMON_AGSL + """
uniform float2 uInset;
uniform float4 uShadow;
uniform float uShadowOpacity;
half4 main(float2 coord) {
    float2 p = coord - uInset - (uSize * 0.5 + uShift);
    float outside = clamp(sdShape(p) + 0.5, 0.0, 1.0);
    if (outside <= 0.0) return half4(0.0);
    float ds = sdShape(p - uShadow.xy) + uShadow.z;
    float a = clamp(uShadowOpacity * softStep(0.7071 * ds / max(uShadow.w, 0.3)), 0.0, 1.0) * outside;
    // ±0.5/255 抖动：很淡的大范围渐变量化成 8bit 会出色带
    float n = fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    return half4(0.0, 0.0, 0.0, half(a > 0.0 ? clamp(a + n / 255.0, 0.0, 1.0) : 0.0));
}
""",
)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun configureGlassOuterShadow(
    shader: RuntimeShader,
    density: androidx.compose.ui.unit.Density,
    recipe: MochiGlassRecipe,
    rect: Rect,
    alpha: Float,
): Unit = with(density) {
    shader.shapeUniforms(rect.size, min(rect.width, rect.height) / 2f, null)
    shader.setFloatUniform("uInset", rect.left, rect.top)
    shader.setFloatUniform(
        "uShadow",
        recipe.shadowOffsetX.toPx(),
        recipe.shadowOffsetY.toPx(),
        recipe.shadowSpread.toPx(),
        recipe.shadowSoftness.toPx(),
    )
    shader.setFloatUniform("uShadowOpacity", (recipe.shadowOpacity * alpha).coerceIn(0f, 1f))
}

/** 收尾的着色器（每块玻璃自己留一个） */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun glassFinishShader(): RuntimeShader = RuntimeShader(FAST_GLASS_FINISH_AGSL)

/** Validate the existing sampled contour before enabling the capsule shortcut. */
internal fun capsuleArc(width: Float, height: Float, points: FloatArray): FloatArray {
    val radius = min(width, height) / 2f
    val cx = width / 2f - radius
    val cy = height / 2f - radius
    val step = (PI / 26.0).toFloat()
    val circular = abs(width - height) < 0.0001f && radius > 0f && (1..14).all { i ->
        val x = points[i * 2] - cx
        val y = points[i * 2 + 1] - cy
        abs(hypot(x, y) - radius) <= max(0.001f, radius * 0.001f) &&
            abs(atan2(y, x) - (14 - i) * step) <= step / 4f
    }
    return floatArrayOf(cx, cy, radius, if (circular) 1f else 0f)
}

/**
 * 合成层的效果：玻璃尺寸 [glassSize]、在这一层里的位置 [inset]，面色在右边 [atlas] 处。
 * 长度按 [density] 换算（全分辨率）
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun glassFinishEffect(
    density: androidx.compose.ui.unit.Density,
    shader: RuntimeShader,
    recipe: MochiGlassRecipe,
    glassSize: Size,
    inset: Offset,
    cornerRadius: Float,
    atlas: Float,
    merge: GlassMerge? = null,
): RenderEffect? = with(density) {
    if (glassSize.width <= 0f || glassSize.height <= 0f) return null
    val r = recipe
    val shape = merge?.shape?.size ?: glassSize
    shader.apply {
        setFloatUniform("uPad", 0f)
        val corner = shapeUniforms(glassSize, cornerRadius, merge)
        setFloatUniform("uArc", capsuleArc(shape.width, shape.height, corner.first))
        setFloatUniform("uAtlas", atlas)
        setFloatUniform("uInset", inset.x, inset.y)
        setFloatUniform("uShadow", r.shadowOffsetX.toPx(), r.shadowOffsetY.toPx(), r.shadowSpread.toPx(), r.shadowSoftness.toPx())
        setFloatUniform("uShadowOpacity", r.shadowOpacity)
        val m = r.faceMatrix
        setFloatUniform("uFace0", m[0], m[1], m[2], m[3])
        setFloatUniform("uFace1", m[4], m[5], m[6], m[7])
        setFloatUniform("uFace2", m[8], m[9], m[10], m[11])
        edge(r, density)
        rim(r, density, shape)
    }
    RenderEffect.createRuntimeShaderEffect(shader, "content")
}

/**
 * 选中块（iOS 的 UITabSelectionView）：背后的画面先高斯模糊 [blurSigma]（px），再过 3×4 矩阵 [m]；
 * 强度 [amount]（0 原样，1 完全过矩阵、完全模糊）。Android 12 以下不做
 */
internal fun BackdropEffectScope.selection(m: List<Float>, amount: Float, blurSigma: Float = 0f) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    blurRadius(blurSigma * amount).let { if (it > 0f) blur(it) }
    fun v(i: Int, identity: Float) = lerp(identity, m[i], amount)
    val cm = floatArrayOf(
        v(0, 1f), v(1, 0f), v(2, 0f), 0f, v(3, 0f) * 255f,
        v(4, 0f), v(5, 1f), v(6, 0f), 0f, v(7, 0f) * 255f,
        v(8, 0f), v(9, 0f), v(10, 1f), 0f, v(11, 0f) * 255f,
        0f, 0f, 0f, 1f, 0f,
    )
    effect(RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(cm))).asComposeRenderEffect())
}

/**
 * 找一个颜色 y，使「强度 [amount] 的选中块矩阵」作用在 y 上正好得到 [target]（选中块会把它下面的选中色图标也过一遍矩阵，
 * 先用这个抵消）。矩阵把某个通道压到 0 以下时那个通道取 0
 */
internal fun List<Float>.preimage(target: androidx.compose.ui.graphics.Color, amount: Float): androidx.compose.ui.graphics.Color {
    // M = I + amount·(m − I) 的 3×3 部分和偏移
    val a = Array(3) { r -> FloatArray(3) { c -> lerp(if (r == c) 1f else 0f, this[r * 4 + c], amount) } }
    val o = FloatArray(3) { r -> lerp(0f, this[r * 4 + 3], amount) }
    val t = floatArrayOf(target.red, target.green, target.blue)
    val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
        a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
    if (abs(det) < 1e-6f) return target
    fun inv(r: Int, c: Int): Float {
        val r1 = (c + 1) % 3
        val r2 = (c + 2) % 3
        val c1 = (r + 1) % 3
        val c2 = (r + 2) % 3
        return (a[r1][c1] * a[r2][c2] - a[r1][c2] * a[r2][c1]) / det
    }
    val v = FloatArray(3) { r -> (0..2).sumOf { c -> (inv(r, c) * (t[c] - o[c])).toDouble() }.toFloat() }
    return androidx.compose.ui.graphics.Color(v[0].coerceIn(0f, 1f), v[1].coerceIn(0f, 1f), v[2].coerceIn(0f, 1f), target.alpha)
}

/** 3×4 颜色矩阵作用在 [c] 上（逐通道截到 0..1） */
internal fun List<Float>.applyTo(c: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    fun row(i: Int) = (this[i] * c.red + this[i + 1] * c.green + this[i + 2] * c.blue + this[i + 3]).coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.Color(row(0), row(4), row(8), c.alpha)
}

/** 两种配方之间插值（浅色、深色玻璃切换时用） */
internal fun lerp(a: MochiGlassRecipe, b: MochiGlassRecipe, t: Float): MochiGlassRecipe {
    if (t <= 0f) return a
    if (t >= 1f) return b
    fun l(x: List<Float>, y: List<Float>) = x.indices.map { lerp(x[it], y[it], t) }
    fun ln(x: List<Float>?, y: List<Float>?) = if (x != null && y != null) {
        l(x, y)
    } else if (t < 0.5f) {
        x
    } else {
        y
    }
    return MochiGlassRecipe(
        blur = lerp(a.blur, b.blur, t),
        refraction = lerp(a.refraction, b.refraction, t),
        refractionHeight = lerp(a.refractionHeight, b.refractionHeight, t),
        outerRefraction = lerp(a.outerRefraction, b.outerRefraction, t),
        outerRefractionHeight = lerp(a.outerRefractionHeight, b.outerRefractionHeight, t),
        outerRefractionStart = lerp(a.outerRefractionStart, b.outerRefractionStart, t),
        outerRefractionEnd = lerp(a.outerRefractionEnd, b.outerRefractionEnd, t),
        outerRefractionOpacity = lerp(a.outerRefractionOpacity, b.outerRefractionOpacity, t),
        blurEdge = lerp(a.blurEdge, b.blurEdge, t),
        blurEdgeStart = lerp(a.blurEdgeStart, b.blurEdgeStart, t),
        blurEdgeEnd = lerp(a.blurEdgeEnd, b.blurEdgeEnd, t),
        fillBlur = lerp(a.fillBlur, b.fillBlur, t),
        fillDarken = lerp(a.fillDarken, b.fillDarken, t),
        fillLighten = lerp(a.fillLighten, b.fillLighten, t),
        fillNormal = lerp(a.fillNormal, b.fillNormal, t),
        faceMatrix = l(a.faceMatrix, b.faceMatrix),
        bleed = lerp(a.bleed, b.bleed, t),
        bleedHeight = lerp(a.bleedHeight, b.bleedHeight, t),
        bleedStart = lerp(a.bleedStart, b.bleedStart, t),
        bleedEnd = lerp(a.bleedEnd, b.bleedEnd, t),
        bleedOpacity = lerp(a.bleedOpacity, b.bleedOpacity, t),
        bleedLumaScale = lerp(a.bleedLumaScale, b.bleedLumaScale, t),
        bleedLumaOffset = lerp(a.bleedLumaOffset, b.bleedLumaOffset, t),
        bleedMatrix = l(a.bleedMatrix, b.bleedMatrix),
        bleedBlur = lerp(a.bleedBlur, b.bleedBlur, t),
        ringOffsetX = lerp(a.ringOffsetX, b.ringOffsetX, t),
        ringOffsetY = lerp(a.ringOffsetY, b.ringOffsetY, t),
        ringWidth = lerp(a.ringWidth, b.ringWidth, t),
        ringRadius = lerp(a.ringRadius, b.ringRadius, t),
        ringOpacity = lerp(a.ringOpacity, b.ringOpacity, t),
        ringMask = lerp(a.ringMask, b.ringMask, t),
        shadowOffsetX = lerp(a.shadowOffsetX, b.shadowOffsetX, t),
        shadowOffsetY = lerp(a.shadowOffsetY, b.shadowOffsetY, t),
        shadowSpread = lerp(a.shadowSpread, b.shadowSpread, t),
        shadowSoftness = lerp(a.shadowSoftness, b.shadowSoftness, t),
        shadowOpacity = lerp(a.shadowOpacity, b.shadowOpacity, t),
        highlightAngle = lerp(a.highlightAngle, b.highlightAngle, t),
        highlightHeight = lerp(a.highlightHeight, b.highlightHeight, t),
        highlightSpread = lerp(a.highlightSpread, b.highlightSpread, t),
        highlightAmount = lerp(a.highlightAmount, b.highlightAmount, t),
        highlightOffset = lerp(a.highlightOffset, b.highlightOffset, t),
        highlightBias = lerp(a.highlightBias, b.highlightBias, t),
        edgeLightKey = lerp(a.edgeLightKey, b.edgeLightKey, t),
        edgeLightFill = lerp(a.edgeLightFill, b.edgeLightFill, t),
        edgeLightAngle = lerp(a.edgeLightAngle, b.edgeLightAngle, t),
        edgeLightHeight = lerp(a.edgeLightHeight, b.edgeLightHeight, t),
        edgeLightSpread = lerp(a.edgeLightSpread, b.edgeLightSpread, t),
        edgeLightSpreadBySize = if (t < 0.5f) a.edgeLightSpreadBySize else b.edgeLightSpreadBySize,
        edgeLightCurvature = lerp(a.edgeLightCurvature, b.edgeLightCurvature, t),
        edgeLightAmount = lerp(a.edgeLightAmount, b.edgeLightAmount, t),
        edgeLightDiffuseHeight = lerp(a.edgeLightDiffuseHeight, b.edgeLightDiffuseHeight, t),
        edgeLightDiffuseOpacity = lerp(a.edgeLightDiffuseOpacity, b.edgeLightDiffuseOpacity, t),
        edgeLightDiffuseBlur = lerp(a.edgeLightDiffuseBlur, b.edgeLightDiffuseBlur, t),
        edgeLightDiffuseOffset = lerp(a.edgeLightDiffuseOffset, b.edgeLightDiffuseOffset, t),
        edgeLightMatrix = if (a.edgeLightMatrix == null && b.edgeLightMatrix == null) {
            null
        } else {
            l(a.edgeLightMatrix ?: a.highlightMatrix, b.edgeLightMatrix ?: b.highlightMatrix)
        },
        edgeLightGain = lerp(a.edgeLightGain, b.edgeLightGain, t),
        edgeLightSide = lerp(a.edgeLightSide, b.edgeLightSide, t),
        highlightMatrix = l(a.highlightMatrix, b.highlightMatrix),
        aberration = lerp(a.aberration, b.aberration, t),
        aberrationAngle = lerp(a.aberrationAngle, b.aberrationAngle, t),
        aberrationEdge = lerp(a.aberrationEdge, b.aberrationEdge, t),
        contentMatrix = ln(a.contentMatrix, b.contentMatrix),
        accentMatrix = ln(a.accentMatrix, b.accentMatrix),
        selectionMatrix = ln(a.selectionMatrix, b.selectionMatrix),
    )
}

/**
 * Android 12 及以下没有着色器：玻璃只有模糊（Android 12），更低什么都不做。Android 13+ 走 [GlassNode]
 */
internal fun BackdropEffectScope.legacyGlass(recipe: MochiGlassRecipe) {
    blur(blurRadius(max(recipe.fillBlur.toPx(), recipe.blur.toPx())))
}

/**
 * 面色的计划：小图层四周外扩 [padding] px，[lanes] 路横着排；[blur] 挂在这几路上（两遍模糊，不用模糊时为 null），
 * [face] 挂在读它们的那一层上。两段不能串成一条效果：系统只按最外层要输出的范围（第一路）去算里面几层，
 * 面色读别的几路时读到的是没算过的地方
 */
internal class GlassFacePlan(val padding: Int, val lanes: Int, val blur: RenderEffect?, val face: RenderEffect)

/** 面色要的几个着色器（每块玻璃一份，换参数时重设 uniform、重建效果） */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassFaceShaders {
    val face = RuntimeShader(GLASS_FACE_ATLAS_AGSL)
    val mask = RuntimeShader(GLASS_MASK_AGSL)
    val blurX = RuntimeShader(GLASS_BLUR_AGSL)
    val blurY = RuntimeShader(GLASS_BLUR_AGSL)
}

/**
 * 面色（降采样的小图层里算，长度按 [density] 换算）：玻璃 [glassSize]、圆角 [cornerRadius]，小图层的缓冲 [bufferWidth] × [bufferHeight]。
 * 小图层里横着排几路同一块背景：主模糊、模糊填充（有填充时）、边光的环境色（有边光时，先挖空玻璃）、贴边的模糊（模糊随离边变时），
 * 两遍模糊之后面色着色器读这几路：折射、按离边距离混两份模糊、填充、环境色、面色矩阵
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun glassFacePlan(
    density: androidx.compose.ui.unit.Density,
    r: MochiGlassRecipe,
    glassSize: Size,
    bufferWidth: Int,
    bufferHeight: Int,
    shaders: GlassFaceShaders,
    cornerRadius: Float = glassSize.minDimension / 2f,
    merge: GlassMerge? = null,
): GlassFacePlan = with(density) {
    val p = ceil(
        maxOf(
            r.fillBlur.toPx() * 2f,
            abs(r.outerRefraction.toPx()) + 2f,
            abs(r.aberration.toPx()) + 2f,
            r.edgeLightDiffuseOffset.toPx() + r.edgeLightDiffuseBlur.toPx() * 2f + 2f,
        ),
    ).toInt()
    val laneW = bufferWidth + p * 2
    val laneH = bufferHeight + p * 2
    fun RuntimeShader.common() {
        setFloatUniform("uPad", p.toFloat())
        shapeUniforms(glassSize, cornerRadius, merge)
    }
    val lit = r.edgeLightKey > 0f || r.edgeLightFill > 0f
    val fillActive = r.fillNormal > 0f || r.fillDarken > 0f || r.fillLighten > 0f
    val edgeActive = r.blurEdge.value >= 0f
    val sigmas = ArrayList<Float>(4)
    sigmas += r.blur.toPx()
    if (fillActive) sigmas += r.fillBlur.toPx()
    val lightLane = sigmas.size
    if (lit) sigmas += r.edgeLightDiffuseBlur.toPx()
    val edgeLane = sigmas.size
    if (edgeActive) sigmas += r.blurEdge.toPx()
    shaders.face.apply {
        common()
        setFloatUniform("uAtlas", laneW.toFloat())
        setFloatUniform("uAmount", r.refraction.toPx())
        setFloatUniform("uInvHeight", 1f / max(r.refractionHeight.toPx(), 0.5f))
        val o = r.outerRefractionStart.toPx()
        setFloatUniform(
            "uOuter",
            r.outerRefraction.toPx(),
            1f / max(r.outerRefractionHeight.toPx(), 0.5f),
            o,
            max(
                r.outerRefractionEnd.toPx(),
                o + 0.01f,
            ),
        )
        setFloatUniform("uOuterOpacity", r.outerRefractionOpacity)
        val ab = Math.toRadians(r.aberrationAngle.toDouble())
        setFloatUniform("uAberr", r.aberration.toPx(), 1f / max(r.aberrationEdge.toPx(), 0.5f), cos(ab).toFloat(), sin(ab).toFloat())
        val keep = 1f - r.fillNormal
        val light = r.fillLighten >= r.fillDarken
        setFloatUniform(
            "uMix",
            keep * (1f - max(r.fillDarken, r.fillLighten)),
            keep * abs(r.fillLighten - r.fillDarken),
            keep * min(r.fillDarken, r.fillLighten) + r.fillNormal,
            if (light) 1f else 0f,
        )
        setFloatUniform("uFillActive", if (fillActive) 1f else 0f)
        setFloatUniform("uLanes", 1f, lightLane.toFloat(), edgeLane.toFloat())
        val blurStart = r.blurEdgeStart.toPx()
        setFloatUniform("uBlurCurve", blurStart, max(r.blurEdgeEnd.toPx(), blurStart + 0.01f), if (edgeActive) 1f else 0f)
        setFloatUniform("uLit", if (lit) 1f else 0f)
        val h = max(r.edgeLightHeight.toPx(), 0.01f)
        setFloatUniform(
            "uAmbient",
            h * r.edgeLightDiffuseHeight,
            r.edgeLightDiffuseOpacity,
            r.edgeLightDiffuseOffset.toPx(),
            r.edgeLightSide,
        )
        val angle = Math.toRadians(r.edgeLightAngle.toDouble())
        setFloatUniform("uAmbientDir", sin(angle).toFloat(), -cos(angle).toFloat())
        setFloatUniform("uAmbientEdge", h)
        val m = r.faceMatrix
        setFloatUniform("uFace0", m[0], m[1], m[2], m[3])
        setFloatUniform("uFace1", m[4], m[5], m[6], m[7])
        setFloatUniform("uFace2", m[8], m[9], m[10], m[11])
    }
    val face = RenderEffect.createRuntimeShaderEffect(shaders.face, "content")
    // 小于 0.5 的 sigma 系统模糊也是原样（见 [blurRadius]）
    if (sigmas.none { it > 0.5f } && !lit) return GlassFacePlan(p, sigmas.size, null, face)
    fun RuntimeShader.blur(x: Float, y: Float): RenderEffect {
        setFloatUniform("uDir", x, y)
        setFloatUniform("uLane", laneW.toFloat(), laneH.toFloat())
        setFloatUniform(
            "uSigma",
            sigmas.getOrElse(0) {
                0f
            },
            sigmas.getOrElse(1) { 0f },
            sigmas.getOrElse(2) { 0f },
            sigmas.getOrElse(3) { 0f },
        )
        return RenderEffect.createRuntimeShaderEffect(this, "content")
    }
    var blurred = shaders.blurX.blur(1f, 0f)
    if (lit) {
        val mask = shaders.mask.apply {
            common()
            setFloatUniform("uLane", laneW.toFloat(), laneH.toFloat())
            setFloatUniform("uMaskLane", lightLane.toFloat())
        }
        blurred = RenderEffect.createChainEffect(blurred, RenderEffect.createRuntimeShaderEffect(mask, "content"))
    }
    blurred = RenderEffect.createChainEffect(shaders.blurY.blur(0f, 1f), blurred)
    GlassFacePlan(p, sigmas.size, blurred, face)
}

/** 边光的 uniform（只有玻璃里面的收尾着色器有） */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.rim(r: MochiGlassRecipe, density: androidx.compose.ui.unit.Density, size: Size) = with(density) {
    val a = Math.toRadians(r.edgeLightAngle.toDouble())
    setFloatUniform("uKf", sin(a).toFloat(), -cos(a).toFloat(), r.edgeLightKey, r.edgeLightFill)
    val spread = if (r.edgeLightSpreadBySize) edgeLightSpreadFor(sqrt(size.width * size.height) / density.density) else r.edgeLightSpread
    setFloatUniform("uKfShape", r.edgeLightHeight.toPx(), spread, r.edgeLightCurvature, r.edgeLightAmount)
    setFloatUniform("uKfGain", r.edgeLightGain)
    val m = r.edgeLightMatrix ?: r.highlightMatrix
    setFloatUniform("uKfM0", m[0], m[1], m[2], m[3])
    setFloatUniform("uKfM1", m[4], m[5], m[6], m[7])
    setFloatUniform("uKfM2", m[8], m[9], m[10], m[11])
    setFloatUniform("uKfSide", r.edgeLightSide)
}

/**
 * 光照范围随玻璃大小变（iOS 26.5 模拟器读出来的 keySpread）：300×62 胶囊、360×72 卡片 90°，170×170 是 97.9°，360×180 是 117.8°。
 * 按 √面积（dp）在这几个点之间线性插值、两头截住，返回 cos 阈值；中间的形状是插值，近似
 */
internal fun edgeLightSpreadFor(sqrtArea: Float): Float {
    val deg = when {
        sqrtArea <= 161f -> 90f
        sqrtArea <= 170f -> 90f + (97.93f - 90f) * (sqrtArea - 161f) / 9f
        sqrtArea <= 254.6f -> 97.93f + (117.76f - 97.93f) * (sqrtArea - 170f) / 84.6f
        else -> 117.76f
    }
    return cos(Math.toRadians(deg.toDouble())).toFloat()
}

/** 黑环和定向高光的 uniform（玻璃里面和外轮廓层共用） */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.edge(r: MochiGlassRecipe, density: androidx.compose.ui.unit.Density) = with(density) {
    setFloatUniform("uRing", r.ringWidth.toPx(), r.ringRadius.toPx(), r.ringOpacity, r.ringMask)
    setFloatUniform("uRingOffset", r.ringOffsetX.toPx(), r.ringOffsetY.toPx())
    val a = Math.toRadians(r.highlightAngle.toDouble())
    setFloatUniform("uHlDir", sin(a).toFloat(), -cos(a).toFloat())
    val height = r.highlightHeight.toPx()
    val offset = r.highlightOffset.toPx()
    val amount = if (r.highlightAmount > 0f) 1f / r.highlightAmount - 2f else 0f
    setFloatUniform("uHl", height, r.highlightSpread, amount, offset)
    setFloatUniform("uHlBias", r.highlightBias)
    val hm = r.highlightMatrix
    setFloatUniform("uHlM0", hm[0], hm[1], hm[2], hm[3])
    setFloatUniform("uHlM1", hm[4], hm[5], hm[6], hm[7])
    setFloatUniform("uHlM2", hm[8], hm[9], hm[10], hm[11])
    // 原生：偏移为负、且高度 + 偏移 ≈ 0 时走细描边分支
    setFloatUniform("uHlStroke", if (offset < 0f && abs(height + offset) < 0.001f) 1f else 0f)
}

/**
 * 配方里的模糊是高斯 sigma；RenderEffect 的参数是「半径」，系统按 sigma = 0.57735·r + 0.5 换算（Skia 的 convertRadiusToSigma），
 * 这里反过来算
 */
private fun blurRadius(sigma: Float): Float = if (sigma <= 0.5f) 0f else (sigma - 0.5f) / 0.57735f

/** 外轮廓层要比玻璃往外多画多少：外阴影、黑环、细描边都在这个范围里 */
internal fun MochiGlassRecipe.outlineReach(): Dp {
    val shadow = if (shadowOpacity > 0f) {
        maxOf(abs(shadowOffsetX.value), abs(shadowOffsetY.value)).dp - shadowSpread + shadowSoftness * 3
    } else {
        0.dp
    }
    val ring = if (ringOpacity > 0f && ringMask < 1f) maxOf(abs(ringOffsetX.value), abs(ringOffsetY.value)).dp + ringRadius * 3 else 0.dp
    return maxOf(shadow, ring, 2.dp)
}

private const val CORNER_POINTS = 16
private val cornerCache = object : LinkedHashMap<Triple<Float, Float, Float>, Pair<FloatArray, FloatArray>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Float, Float, Float>, Pair<FloatArray, FloatArray>>?) = size > 8
}

/** 一块玻璃最多和几块别的融在一起（着色器里的数组长度） */
internal const val GlassMergeMax = 6

/** 融进来的一块：圆角矩形 [rect]（玻璃节点里的 px）、圆角 [radius]，和前面已经并起来的形状做光滑并集的半径 [smooth]（0 = 直接取并集） */
internal data class GlassMergePartner(val rect: Rect, val radius: Float, val smooth: Float)

/**
 * 几块玻璃融成一块（iOS 菜单从按钮组里脱出来时两块之间的 Smooth on；按钮组里按下的按钮和邻居）。坐标都是玻璃节点里的 px：
 * 节点按所有块的外包框排版，[shape] 是主形状（圆角另给），[partners] 是其余几块（最多 [GlassMergeMax] 块），按顺序并进来
 */
internal data class GlassMerge(val shape: Rect, val partners: List<GlassMergePartner>) {
    /** 两块：主形状和另一块 [partner]（圆角 [partnerRadius]），[smooth] 是光滑并集的半径 */
    constructor(shape: Rect, partner: Rect, partnerRadius: Float, smooth: Float) :
        this(shape, listOf(GlassMergePartner(partner, partnerRadius, smooth)))

    /** 整体缩放 [s] 倍（降采样的小图层用） */
    fun scaled(s: Float): GlassMerge = GlassMerge(
        shape.times(s),
        partners.map {
            GlassMergePartner(
                it.rect.times(s),
                it.radius * s,
                it.smooth * s,
            )
        },
    )

    private fun Rect.times(s: Float) = Rect(left * s, top * s, right * s, bottom * s)
}

/**
 * 形状的几个 uniform：节点尺寸 [glassSize]、主形状的轮廓（圆角 [radius]），主形状偏开的量和融进来的几块（[merge] 为 null 时都是 0）。
 * 返回主形状的轮廓点
 */
internal fun RuntimeShader.shapeUniforms(glassSize: Size, radius: Float, merge: GlassMerge?): Pair<FloatArray, FloatArray> {
    val shape = merge?.shape?.size ?: glassSize
    setFloatUniform("uSize", glassSize.width, glassSize.height)
    val corner = cornerPoints(shape.width, shape.height, min(radius, min(shape.width, shape.height) / 2f))
    setFloatUniform("uCorner", corner.first)
    setFloatUniform("uCornerN", corner.second)
    val rects = FloatArray(GlassMergeMax * 4)
    val params = FloatArray(GlassMergeMax * 2)
    if (merge == null) {
        setFloatUniform("uShift", 0f, 0f)
    } else {
        val c = merge.shape.center
        setFloatUniform("uShift", c.x - glassSize.width / 2f, c.y - glassSize.height / 2f)
        merge.partners.take(GlassMergeMax).forEachIndexed { i, p ->
            val pc = p.rect.center
            rects[i * 4] = pc.x - c.x
            rects[i * 4 + 1] = pc.y - c.y
            rects[i * 4 + 2] = p.rect.width / 2f
            rects[i * 4 + 3] = p.rect.height / 2f
            params[i * 2] = p.radius
            params[i * 2 + 1] = p.smooth
        }
    }
    setFloatUniform("uMergeRect", rects)
    setFloatUniform("uMerge", params)
    return corner
}

/**
 * 形状的一个角，给着色器用：Kyant 连续圆角（[MochiContinuity]，和 MochiCapsule / MochiRoundedRectangle 同一条曲线）
 * 在右下四分之一里的轮廓，从下边中点 (0, h/2) 到右边中点 (w/2, 0)，按弧长等距取 [CORNER_POINTS] 个点，坐标以中心为原点；
 * 另给每个点的外法线（相邻两段法线的平均，两头分别朝下、朝右）
 */
internal fun cornerPoints(width: Float, height: Float, radius: Float): Pair<FloatArray, FloatArray> = synchronized(cornerCache) {
    cornerCache.getOrPut(Triple(width, height, radius)) {
        val w = width.toDouble().coerceAtLeast(1e-3)
        val h = height.toDouble().coerceAtLeast(1e-3)
        val r = radius.toDouble().coerceIn(0.0, minOf(w, h) / 2.0)
        val raw = ArrayList<Pair<Double, Double>>()
        fun add(x: Double, y: Double) {
            val qx = x - w / 2.0
            val qy = y - h / 2.0
            if (qx >= -1e-6 && qy >= -1e-6) raw += qx.coerceAtLeast(0.0) to qy.coerceAtLeast(0.0)
        }
        for (seg in MochiContinuity.createRoundedRectanglePathSegments(w, h, r, r, r, r, 1.0)) {
            when (seg) {
                is PathSegment.Cubic -> for (i in 0..32) {
                    val t = i / 32.0
                    val u = 1 - t
                    add(
                        u * u * u * seg.p0.x + 3 * u * u * t * seg.p1.x + 3 * u * t * t * seg.p2.x + t * t * t * seg.p3.x,
                        u * u * u * seg.p0.y + 3 * u * u * t * seg.p1.y + 3 * u * t * t * seg.p2.y + t * t * t * seg.p3.y,
                    )
                }

                is PathSegment.Arc -> {
                    // 角度单位按数值大小判断（超过一圈的弧度不会出现）
                    val k = if (abs(seg.sweepAngle) > 2 * PI + 1e-6 || abs(seg.startAngle) > 2 * PI + 1e-6) PI / 180.0 else 1.0
                    for (i in 0..32) {
                        val a = (seg.startAngle + seg.sweepAngle * i / 32.0) * k
                        add(seg.center.x + seg.radius * cos(a), seg.center.y + seg.radius * sin(a))
                    }
                }

                is PathSegment.Circle -> for (i in 0..128) {
                    val a = 2 * PI * i / 128.0
                    add(seg.center.x + seg.radius * cos(a), seg.center.y + seg.radius * sin(a))
                }

                else -> {
                    add(seg.from.x, seg.from.y)
                    add(seg.to.x, seg.to.y)
                }
            }
        }
        raw += 0.0 to h / 2.0
        raw += w / 2.0 to 0.0
        // 从下边（角度 90°）往右边（0°）排。直边各用一段，弯的那段按弧长等距取 CORNER_POINTS − 2 个点
        val pts = raw.sortedByDescending { atan2(it.second, it.first) }
        val i0 = pts.indexOfLast { it.second >= h / 2.0 - 1e-4 }.coerceAtLeast(0)
        val i1 = pts.indexOfFirst { it.first >= w / 2.0 - 1e-4 }.let { if (it < i0) pts.lastIndex else it }
        val curve = pts.subList(i0, i1 + 1)
        val len = DoubleArray(curve.size)
        for (i in 1 until curve.size) {
            len[i] =
                len[i - 1] + hypot(curve[i].first - curve[i - 1].first, curve[i].second - curve[i - 1].second)
        }
        val out = FloatArray(CORNER_POINTS * 2)
        out[1] = (h / 2.0).toFloat()
        out[CORNER_POINTS * 2 - 2] = (w / 2.0).toFloat()
        var j = 0
        val inner = CORNER_POINTS - 2
        for (k in 0 until inner) {
            val target = len.last() * k / (inner - 1)
            while (j < curve.size - 2 && len[j + 1] < target) j++
            val (x, y) = if (curve.size < 2) {
                curve[0]
            } else {
                val f = ((target - len[j]) / (len[j + 1] - len[j]).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
                (curve[j].first + (curve[j + 1].first - curve[j].first) * f) to
                    (curve[j].second + (curve[j + 1].second - curve[j].second) * f)
            }
            out[(k + 1) * 2] = x.toFloat()
            out[(k + 1) * 2 + 1] = y.toFloat()
        }
        val normals = FloatArray(CORNER_POINTS * 2)
        fun segNormal(i: Int): Pair<Float, Float> {
            val ex = out[(i + 1) * 2] - out[i * 2]
            val ey = out[(i + 1) * 2 + 1] - out[i * 2 + 1]
            val l = hypot(ex, ey)
            return if (l < 1e-6f) 0f to 0f else -ey / l to ex / l
        }
        for (k in 0 until CORNER_POINTS) {
            val (ax, ay) = if (k > 0) segNormal(k - 1) else 0f to 1f
            val (bx, by) = if (k < CORNER_POINTS - 1) segNormal(k) else 1f to 0f
            var nx = ax + bx
            var ny = ay + by
            // 弯的那段两头和直边相切，法线正朝下 / 朝右。直边长度为 0 时（圆、胶囊两端）这两点就在中轴上，
            // 法线要是带了斜段的方向，轴两边镜像后会一正一负，被折射放大成一条缝
            if (k <= 1) {
                nx = 0f
                ny = 1f
            }
            if (k >= CORNER_POINTS - 2) {
                nx = 1f
                ny = 0f
            }
            val l = hypot(nx, ny).coerceAtLeast(1e-6f)
            normals[k * 2] = nx / l
            normals[k * 2 + 1] = ny / l
        }
        out to normals
    }
}
