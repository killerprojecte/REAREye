// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // Deliberate theme token, alongside LocalMochiColorScheme.

package ui.feice.mochi.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃材质（iOS 26 的 Liquid Glass）。浅色玻璃用 [light]，深色玻璃用 [dark]，默认跟 App 深浅色。
 * [adaptive] = true 时，背后的内容特别暗（浅色模式下平均亮度 < [flipToDark]）或特别亮（深色模式下 > [flipToLight]）
 * 会翻到另一种玻璃，上面的图标也跟着变白、变黑（iOS 的做法）。
 * [byLuma] 不为 null 时（要 [adaptive]），配方再按背后的平均亮度（sRGB 编码值的 709 加权，0..1）连续变化：
 * 参数是亮度和要不要浅色玻璃；没取到亮度前用 [light] / [dark]。
 * [fallback]：没有背景来源时整块用的颜色。
 *
 * 背景抓取、裁形状用 [Kyant 的 backdrop](https://github.com/Kyant0/AndroidLiquidGlass)。
 * 完整效果要 Android 13+；Android 12 只有模糊；更低的系统只剩 [fallback]。
 */
@Immutable
public data class MochiGlass(
    val fallback: Color,
    val light: MochiGlassRecipe,
    val dark: MochiGlassRecipe = light,
    val adaptive: Boolean = false,
    val flipToDark: Float = 0.2f,
    val flipToLight: Float = 0.8f,
    val byLuma: ((luma: Float, light: Boolean) -> MochiGlassRecipe)? = null,
    /** Blur-only material: no optical edges, refraction, dispersion, or pressed selection lens. */
    val blurOnly: Boolean = false,
) {
    /** Keep background blur, tint and content contrast while removing the optical glass effects. */
    public fun asBlurOnly(): MochiGlass {
        val by = byLuma
        return copy(
            light = light.withoutOptics(),
            dark = dark.withoutOptics(),
            byLuma = by?.let { f -> { luma, light -> f(luma, light).withoutOptics() } },
            blurOnly = true,
        )
    }
}

private fun MochiGlassRecipe.withoutOptics(): MochiGlassRecipe = MochiGlassRecipe(
    blur = maxOf(blur, fillBlur, blurEdge, 0.dp),
    faceMatrix = faceMatrix,
    contentMatrix = contentMatrix,
    accentMatrix = accentMatrix,
    selectionMatrix = selectionMatrix,
)

/**
 * 一种玻璃的配方。背后的内容依次经过（顺序和 iOS 的玻璃着色器一致）：
 *
 * 1. 内折射：离边 [refractionHeight] 以内往里取样，边上偏移最大 [refraction]，按 `1 - sqrt(t(2 - t))` 变小
 *    （t = 离边距离 / refractionHeight）。偏移比离边距离大，所以边上那一圈是倒过来的。取样的是模糊过 [blur] 的背景；
 *    [blurEdge] ≥ 0 时模糊随（折射后的）离边距离从 [blurEdgeStart] 处的 [blur] 线性变到 [blurEdgeEnd] 处的 [blurEdge]。
 *    贴边还有外折射：往外取 [outerRefraction]（按 [outerRefractionHeight] 变小），从 [outerRefractionStart] 到
 *    [outerRefractionEnd] 权重从 0 升到 [outerRefractionOpacity]
 * 2. 模糊填充：同一块背景另外重模糊 [fillBlur]（不折射），和上一步逐通道混合：
 *    `M = darken·min + lighten·max + (1 − darken − lighten)·折射结果`，`结果 = mix(M, 重模糊, normal)`
 * 3. 面色：3×4 颜色矩阵 [faceMatrix]（行优先，每行 r、g、b 系数和偏移）
 * 4. 边缘泄色（0.1.294 起不画，内置配方都没用到，字段保留）：从背景离边往外偏 [bleed]（按 [bleedHeight] 变小）的地方取色（模糊 [bleedBlur]），过 [bleedMatrix]，
 *    权重 `bleedOpacity · (v² · t)²`，v = [bleedLumaScale]·亮度 + [bleedLumaOffset]，t 从 [bleedStart] 到 [bleedEnd]（离边的有符号距离，里面为负）
 * 5. 黑环：把轮廓平移 ([ringOffsetX], [ringOffsetY]) 后，贴着它往里宽 [ringWidth]、软化 [ringRadius] 的一圈黑，强度 [ringOpacity]；
 *    [ringMask] = 1 时只出现在玻璃里面，0 时里外都有。轮廓外面还有外阴影：轮廓偏移 [shadowOffsetX] / [shadowOffsetY]、
 *    往外扩 −[shadowSpread]、软化 [shadowSoftness]、强度 [shadowOpacity]
 * 6. 定向高光：方向 (sin θ, −cos θ)，θ = [highlightAngle]（度，0 朝上）；正反两个方向都亮。离边 [highlightHeight] 以内，
 *    [highlightSpread] 是余弦阈值（法线和方向夹角的 cos 低于它不亮），[highlightAmount] 是输入值（着色器里用 1/amount − 2）。
 *    颜色 `C × (1 + bias·H·(3 − 2C))`，[highlightBias] 正为提亮、负为压暗。
 *    [highlightOffset] 为负且等于 −[highlightHeight] 时是细描边模式：只画在轮廓外贴边那一两个像素（iOS 玻璃那道细边）。
 *    另外还能往高光颜色矩阵混：`C = mix(C, clamp(M·C), H)`，M = [highlightMatrix]（3×4，iOS 26 亮边的 vibrancy 矩阵；单位矩阵等于不混）
 * 6½. 边光（形状照 RenderBox 的 sdf_diffuse_kf_highlight；iOS 26.5 起玻璃的边光就是它，CASDFKeyFillHighlightEffect）：
 *    主光从 [edgeLightAngle]（度，0 = 从上方照来）方向照来，补光从反方向。每一侧一条贴边的亮边（深 [edgeLightHeight]），
 *    按离边深度衰减（[edgeLightCurvature]：边上 1，到深处降到 1 − 它），再按法线和光方向的夹角衰减
 *    （[edgeLightSpread] 是 cos 阈值；[edgeLightSpreadBySize] 时按玻璃大小取原生值），侧边不降到 0、留 [edgeLightSide] 那么亮那么厚，
 *    用 `w / (1 + a(1 − w))` 收紧（a = [edgeLightAmount]）。主光乘 [edgeLightKey]、补光乘 [edgeLightFill]。
 *    权重按边光颜色矩阵 [edgeLightMatrix]（null 时用 [highlightMatrix]）把这一处的颜色提亮提艳：`mix(C, clamp(C + (M·C − C)·g), H)`，g = [edgeLightGain]，
 *    1 是原生，大于 1 更亮更艳（安卓上没有 HDR，用它补）。只画在玻璃里面。
 *    亮边那一圈的颜色换成边外的环境色（在面色矩阵之前，跟玻璃一起上色）：玻璃覆盖的地方挖空，只把外面的背景模糊
 *    [edgeLightDiffuseBlur]（sigma，按透明度归一化），每一点沿法线取边外 [edgeLightDiffuseOffset] 处的颜色，柔和、不混进玻璃后面的内容。
 *    上边带上面的颜色、下边带下面的，沿着边连续过渡；换多少跟着亮边的强弱走（侧边留 [edgeLightSide]）。
 *    [edgeLightDiffuseOpacity] > 0 时这份颜色再往里渗（越贴边越浓，深度是亮边的 [edgeLightDiffuseHeight] 倍）
 * 7. 色散（iOS 的 glassForeground）：离边 [aberrationEdge] 以内，红、蓝两个通道沿法线转过 [aberrationAngle] 度的方向
 *    朝相反两边各多偏 [aberration]（越靠边越多，到范围边上为 0）；0 时关闭，负值方向相反
 *
 * 玻璃上的内容（标签栏用，null 时用组件自己的颜色）：都是 3×4 矩阵，作用在玻璃本身的颜色上（iOS 的 vibrancy）。
 * [contentMatrix] 是未选中的图标和文字，[selectionMatrix] 是选中块（作用在它下面的玻璃上），
 * [accentMatrix] 是选中的图标和文字（作用在选中块的颜色上）
 */
@Immutable
public data class MochiGlassRecipe(
    val blur: Dp = 0.5.dp,
    val refraction: Dp = 0.dp,
    val refractionHeight: Dp = 12.dp,
    val outerRefraction: Dp = 0.dp,
    val outerRefractionHeight: Dp = 8.dp,
    val outerRefractionStart: Dp = (-1).dp,
    val outerRefractionEnd: Dp = 0.dp,
    val outerRefractionOpacity: Float = 0f,
    val blurEdge: Dp = (-1).dp,
    val blurEdgeStart: Dp = (-30).dp,
    val blurEdgeEnd: Dp = (-1).dp,
    val fillBlur: Dp = 0.dp,
    val fillDarken: Float = 0f,
    val fillLighten: Float = 0f,
    val fillNormal: Float = 0f,
    val faceMatrix: List<Float> = IdentityMatrix,
    val bleed: Dp = 0.dp,
    val bleedHeight: Dp = 5.dp,
    val bleedStart: Dp = (-4).dp,
    val bleedEnd: Dp = 0.dp,
    val bleedOpacity: Float = 0f,
    val bleedLumaScale: Float = 0f,
    val bleedLumaOffset: Float = 1f,
    val bleedMatrix: List<Float> = IdentityMatrix,
    val bleedBlur: Dp = 0.dp,
    val ringOffsetX: Dp = 0.dp,
    val ringOffsetY: Dp = 0.dp,
    val ringWidth: Dp = 0.dp,
    val ringRadius: Dp = 0.5.dp,
    val ringOpacity: Float = 0f,
    val ringMask: Float = 1f,
    val shadowOffsetX: Dp = 0.dp,
    val shadowOffsetY: Dp = 0.dp,
    val shadowSpread: Dp = 0.dp,
    val shadowSoftness: Dp = 1.dp,
    val shadowOpacity: Float = 0f,
    val highlightAngle: Float = 0f,
    val highlightHeight: Dp = 0.dp,
    val highlightSpread: Float = 0f,
    val highlightAmount: Float = 0.5f,
    val highlightOffset: Dp = 0.dp,
    val highlightBias: Float = 0f,
    val highlightMatrix: List<Float> = IdentityMatrix,
    val edgeLightKey: Float = 0f,
    val edgeLightFill: Float = 0f,
    val edgeLightAngle: Float = 0f,
    val edgeLightHeight: Dp = 1.dp,
    val edgeLightSpread: Float = 0f,
    val edgeLightSpreadBySize: Boolean = false,
    val edgeLightCurvature: Float = 1f,
    val edgeLightAmount: Float = 0f,
    val edgeLightDiffuseHeight: Float = 6f,
    val edgeLightDiffuseOpacity: Float = 0f,
    val edgeLightDiffuseBlur: Dp = 8.dp,
    val edgeLightDiffuseOffset: Dp = 4.dp,
    val edgeLightMatrix: List<Float>? = null,
    val edgeLightGain: Float = 1f,
    val edgeLightSide: Float = 0f,
    val aberration: Dp = 0.dp,
    val aberrationAngle: Float = 0f,
    val aberrationEdge: Dp = 14.dp,
    val contentMatrix: List<Float>? = null,
    val accentMatrix: List<Float>? = null,
    val selectionMatrix: List<Float>? = null,
) {
    init {
        require(
            listOfNotNull(faceMatrix, bleedMatrix, highlightMatrix, edgeLightMatrix, contentMatrix, accentMatrix, selectionMatrix).all {
                it.size ==
                    12
            },
        ) { "Color matrices are 3×4 (12 values)" }
    }

    public companion object {
        /** 不改颜色的 3×4 矩阵 */
        public val IdentityMatrix: List<Float> = listOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
    }
}

/**
 * 主题里的材质：每个位置用纯色（null，默认，和以前一样）还是玻璃。
 *
 * - [bar]：浮在内容上的栏（标签栏）
 * - [control]：栏里的按钮（顶栏文字 / 图标按钮、按钮组、返回按钮、底部工具栏）和栏里的搜索框；null 时用 [bar]
 * - [menu]：下拉菜单、上下文菜单、气泡（选择列表）；null 时用 [bar]
 * - [alert]：警告框、操作确认框；null 时用 [menu]
 * - [sheet]：面板；null 时用 [bar]
 * - [clear]：iOS 的 .clear 玻璃，clearGlass / prominentClearGlass 按钮用；null 时用 [control]
 * - [scrollEdge]：顶栏 / 底栏的滚动边缘效果（`mochiScrollEdgeFade`）；null 时是底色 + 渐隐，
 *   不为 null 时按它画渐进模糊 + 随背后亮度分档的压暗（iOS 26 的 scroll edge effect）
 *
 * 玻璃要有背景来源才看得到背后的内容：在页面内容上加 `Modifier.mochiBackdropSource`，
 * 并把同一个 [ui.feice.mochi.effect.glass.MochiBackdrop] 用 `LocalMochiBackdrop` 提供给栏；没有来源时按 [MochiGlass.fallback] 画纯色。
 * 标题脚手架会把正文录给顶栏按钮，MochiNavHost 会把整页录给浮层（菜单、警告框、面板），不用另外接。
 * 正文里的控件（列表里的搜索框、分段控件）不做玻璃：玻璃不能取样包含它自己的内容。
 * 玻璃（[MochiGlass] 及各处玻璃材质）是长期 Beta：外观、性能和 API 都可能在小版本里调整，不保证兼容；[scrollEdge] 不在此列。
 * 材质只管浮在内容上的栏、菜单、面板、弹窗和栏里的控件，不改正文内容：正文按钮画不画玻璃由按钮样式决定，
 * 写了玻璃样式（MochiButtonStyle.ProminentGlass / Glass / ClearGlass / ProminentClearGlass）且 [control] 是玻璃时才画（clear 两种用 [clear]），背景是一块纯色画布（见 MochiButton）。
 */
@Immutable
public data class MochiMaterials(
    val bar: MochiGlass? = null,
    val scrollEdge: MochiScrollEdge? = null,
    val control: MochiGlass? = null,
    val menu: MochiGlass? = null,
    val alert: MochiGlass? = null,
    val sheet: MochiGlass? = null,
    val clear: MochiGlass? = null,
) {
    /** 按上面的回退顺序取到的各处材质 */
    val controlGlass: MochiGlass? get() = control ?: bar
    val menuGlass: MochiGlass? get() = menu ?: bar
    val alertGlass: MochiGlass? get() = alert ?: menuGlass
    val sheetGlass: MochiGlass? get() = sheet ?: bar
    val clearGlass: MochiGlass? get() = clear ?: controlGlass

    /** 任何一处是玻璃 */
    val anyGlass: Boolean get() = bar != null || control != null || menu != null || alert != null || sheet != null || clear != null
}

/** 当前主题的材质；默认全部纯色。外观模块（比如 mochi-ios）在根部提供。 */
public val LocalMochiMaterials: androidx.compose.runtime.ProvidableCompositionLocal<MochiMaterials> =
    staticCompositionLocalOf { MochiMaterials() }

/**
 * 只给 [content] 这一块换材质，配色、字体、控件配色都不动。比如带 WebView 的页面整块不用玻璃：
 * `MochiMaterialsScope(MochiMaterials()) { … }`；只关正文按钮的玻璃不需要它，按钮写纯色样式就行
 */
@Composable
public fun MochiMaterialsScope(materials: MochiMaterials, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMochiMaterials provides materials, content = content)
}

/**
 * 滚动边缘效果（iOS 26 的 scroll edge effect，UIKit 的 ScrollEdgeEffectView）。栏朝内容的一侧铺一层遮罩，
 * 遮罩是一条矩形边的阴影（[MochiScrollEdgeSide.softness]），再把透明度按 1.25·a − 0.25 收紧；遮罩里面：
 *
 * - 背后的内容做渐进模糊，模糊量 = [MochiScrollEdgeSide.blur] × 遮罩（0 时不模糊）
 * - 再叠一层颜色：按栏后面内容的平均亮度（sRGB 编码值的 709 加权，按 1/31 分档）选 [light] / [dark] 里的一档，
 *   页面底色按 [MochiScrollEdgeLevel.replay] 盖上去、黑色按 [MochiScrollEdgeLevel.dim] 盖上去
 *
 * 没有背景来源（`LocalMochiBackdrop`）或 Android 12 及以下时只有颜色那一层。
 */
@Immutable
public data class MochiScrollEdge(
    val top: MochiScrollEdgeSide,
    val bottom: MochiScrollEdgeSide,
    val light: List<MochiScrollEdgeLevel>,
    val dark: List<MochiScrollEdgeLevel>,
) {
    /** 亮度 [luma] 落在哪一档：第一个 maxLuma ≥ luma 的，都不满足取最后一档 */
    public fun level(luma: Float, isDark: Boolean): MochiScrollEdgeLevel =
        (if (isDark) dark else light).let { list -> list.firstOrNull { luma <= it.maxLuma } ?: list.last() }
}

/**
 * 一侧（顶栏 / 底栏）的遮罩和模糊。
 *
 * @param softness 遮罩边缘的高斯 sigma
 * @param inset 遮罩 50%（收紧前）那条线离栏朝内容那条边多远，正数往栏里面
 * @param blur 遮罩满时内容的模糊 sigma；0 = 不模糊
 */
@Immutable
public data class MochiScrollEdgeSide(val softness: Dp, val inset: Dp, val blur: Dp)

/**
 * 一档颜色：亮度不超过 [maxLuma] 时用。[replay] 是页面底色盖上去的不透明度（底栏可另给 [replayBottom]），[dim] 是黑色的。
 * [darkText]：这一档栏上的文字用深色（true）、浅色（false）还是主题的文字色（null）。文字跟着这一档的遮罩走，不再另看背后内容：
 * 比如浅色模式下这一档撤掉白色底色、改成压暗，文字就跟着换成白色，不会和压暗后的内容融成一片
 */
@Immutable
public data class MochiScrollEdgeLevel(
    val maxLuma: Float,
    val replay: Float,
    val dim: Float,
    val replayBottom: Float = replay,
    val darkText: Boolean? = null,
)
