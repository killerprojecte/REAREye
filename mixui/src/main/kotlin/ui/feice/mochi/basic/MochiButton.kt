// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // LocalBarControlGlass：栏按钮内部传玻璃，不出这个包

package ui.feice.mochi.basic

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.toSize
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ui.feice.mochi.effect.elastic.MochiFollowSpring
import ui.feice.mochi.effect.glass.GlassFill
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.GlassSource
import ui.feice.mochi.effect.glass.GlassSurface
import ui.feice.mochi.effect.glass.engine.GlassGlowState
import ui.feice.mochi.effect.glass.engine.GlassMerge
import ui.feice.mochi.effect.glass.engine.GlassMergePartner
import ui.feice.mochi.effect.glass.engine.glassFlexSpec
import ui.feice.mochi.effect.glass.engine.glassFlexTarget
import ui.feice.mochi.effect.glass.engine.rememberGlassGlowState
import ui.feice.mochi.effect.glass.glassFor
import ui.feice.mochi.effect.glow.MochiGlowDefaults
import ui.feice.mochi.effect.glow.MochiGlowSpec
import ui.feice.mochi.effect.glow.MochiGlowState
import ui.feice.mochi.effect.glow.drawGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.effect.metaball.MochiBlob
import ui.feice.mochi.effect.metaball.MochiMetaballColors
import ui.feice.mochi.effect.metaball.MochiMetaballFrame
import ui.feice.mochi.effect.metaball.drawMochiMetaball
import ui.feice.mochi.effect.metaball.proximityFuse
import ui.feice.mochi.effect.metaball.rememberMochiMetaballRenderer
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiGlass
import ui.feice.mochi.theme.MochiGlassRecipe
import ui.feice.mochi.theme.MochiMaterials
import ui.feice.mochi.theme.MochiTheme

/**
 * 按钮：按下轻微放大，从手指处亮起高光（光心带阻尼地跟手），并朝手指方向偏一点、略微拉长（跟手形变）；
 * 手指拖出按钮太远松手不算点击；松手带一点回弹恢复。
 *
 * 放在 [MochiButtonGroup] 里时，按下的按钮放大后和旁边靠得近的按钮像水滴一样用融球颈连起来，旁边的按钮也被吸过去一点。
 *
 * @param cornerRadius 圆角；null = 胶囊
 * @param minHeight 最小高度；顶栏里用 [MochiBarButton]（44dp）
 */
@Composable
public fun MochiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: MochiButtonStyle = MochiButtonStyle.Filled,
    colors: MochiButtonColors = MochiButtonDefaults.colors(style),
    cornerRadius: Dp? = null,
    contentPadding: PaddingValues = MochiButtonDefaults.ContentPadding,
    minHeight: Dp = MochiButtonDefaults.Height,
    behavior: MochiButtonBehavior = MochiButtonDefaults.behavior(),
    content: @Composable RowScope.() -> Unit,
) {
    val spec = bodyGlassSpec(style, colors, enabled)
    CompositionLocalProvider(LocalBarControlGlass provides (bodyGlass(spec) ?: LocalBarControlGlass.current)) {
        ButtonCore(onClick, enabled, colors, cornerRadius, behavior, modifier.defaultMinSize(minHeight = minHeight), spec) {
            Row(
                Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** 图标按钮：圆形（或给 cornerRadius 的圆角方形），默认 48dp */
@Composable
public fun MochiIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: MochiButtonStyle = MochiButtonStyle.Tonal,
    colors: MochiButtonColors = MochiButtonDefaults.colors(style),
    cornerRadius: Dp? = null,
    size: Dp = MochiButtonDefaults.IconSize,
    behavior: MochiButtonBehavior = MochiButtonDefaults.behavior(),
    content: @Composable () -> Unit,
) {
    val spec = bodyGlassSpec(style, colors, enabled)
    CompositionLocalProvider(LocalBarControlGlass provides (bodyGlass(spec) ?: LocalBarControlGlass.current)) {
        ButtonCore(onClick, enabled, colors, cornerRadius, behavior, modifier.size(size), spec) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

/**
 * 正文里的玻璃按钮：样式说了算。只有写了玻璃样式（[MochiButtonStyle.isGlass]）、主题的控件又是玻璃
 * （[ui.feice.mochi.theme.MochiMaterials.controlGlass]）时才画玻璃：[MochiButtonStyle.ProminentGlass] / [MochiButtonStyle.Glass]
 * 画成 iOS 的 prominentGlass / glass，[MochiButtonStyle.ProminentClearGlass] / [MochiButtonStyle.ClearGlass] 画成
 * prominentClearGlass / clearGlass。填充、色调、描边、文字按钮永远是纯色，主题开了液态玻璃也一样（纯色的橙色按钮照样能用）；
 * 主题没有玻璃时，玻璃样式按 [MochiButtonStyle.solid] 退回纯色。栏里的按钮另算（[barGlass]）。
 * iOS 26（24A435 UIKitCore 逆向）：glass / prominentGlass 的材质是 `_UIViewGlass` regular（variant 0），
 * clearGlass / prominentClearGlass 是 clear（variant 1）；都关掉自适应、可形变（setFlexible:YES），圆角样式 4（胶囊）；
 * prominent 两种另外把按钮的底色设成玻璃的 tintColor（禁用时换成 `_disabledColorForColor:`），文字颜色按底色取清晰的前景色。
 * 正文里的玻璃不能取样包含它自己的页面，背景是一块纯色画布（往外多铺一圈，边上的折射、色散取样不到透明处）：
 * prominent 两种用按钮的底色，并去掉配方里的面色调整（面色矩阵、提亮压暗），底色原样透出来
 * （近似：tintColor 怎么混进玻璃没逆出来）；色调按钮用默认配色时用材质的 fallback 色，自定义了底色就用那个底色；
 * clearGlass 用按钮的底色（默认是色调按钮的底色）。底色全透明的配色不画玻璃；半透明的先垫一层 surface 色（近似）。
 * 纯色画布上的边缘按 iOS 纯色截图处理（[bodyEdge]）：去掉暗色细描边，边光同色相略提亮、下面比上面弱。
 * 不画外阴影（背后就是纯色底，阴影只会显得脏，横排芯片的渐隐图层还会把它切成方角）；软黑环和按压光照常；折射对纯色不起作用
 */
@Composable
private fun bodyGlassSpec(style: MochiButtonStyle, colors: MochiButtonColors, enabled: Boolean): BodyGlassSpec? {
    if (LocalBarControlGlass.current != null) return null
    val glass = MochiTheme.materials.bodyButtonGlass(style) ?: return null
    val prominent = style == MochiButtonStyle.ProminentGlass || style == MochiButtonStyle.ProminentClearGlass
    val container = if (enabled) colors.container else colors.disabledContainer
    val base = when {
        style == MochiButtonStyle.Glass && colors == MochiButtonDefaults.colors(style) -> MochiTheme.materials.controlGlass!!.fallback
        else -> container
    }
    if (base.alpha <= 0f) return null
    val material = remember(glass, prominent) {
        if (prominent) {
            glass.copy(light = glass.light.untinted().bodyEdge(), dark = glass.dark.untinted().bodyEdge(), adaptive = false, byLuma = null)
        } else {
            glass.copy(light = glass.light.bodyEdge(), dark = glass.dark.bodyEdge(), adaptive = false, byLuma = null)
        }
    }
    return BodyGlassSpec(material, base)
}

/**
 * 正文按钮用哪种玻璃（纯函数，见 [bodyGlassSpec]）：不是玻璃样式、或主题的控件不是玻璃时是 null（画纯色）；
 * clear 两种用 [GlassRole.Clear]，另外两种用 [GlassRole.Control]
 */
internal fun MochiMaterials.bodyButtonGlass(style: MochiButtonStyle): MochiGlass? {
    if (!style.isGlass || controlGlass == null) return null
    val clear = style == MochiButtonStyle.ClearGlass || style == MochiButtonStyle.ProminentClearGlass
    return glassFor(if (clear) GlassRole.Clear else GlassRole.Control)
}

/** 正文玻璃按钮用的材质和纯色画布的颜色（见 [bodyGlassSpec]） */
@Immutable
internal data class BodyGlassSpec(val material: MochiGlass, val base: Color)

/** 单独的正文玻璃按钮的玻璃；按钮组里的由组画（[GroupGlassLayer]） */
@Composable
private fun bodyGlass(spec: BodyGlassSpec?): GlassFill? {
    if (spec == null || LocalMochiButtonGroup.current != null) return null
    val color by rememberUpdatedState(spec.base)
    // 半透明的底色（iOS 配色的 secondarySystemFill、禁用色）先垫一层不透明的页面色：玻璃按不透明的背景算，
    // 直接给半透明的会算成一块深灰。真正垫在按钮下面的是什么不知道，取 surface（iOS 的 systemBackground），近似
    val under by rememberUpdatedState(MochiTheme.colorScheme.surface)
    val canvas = rememberCanvasBackdrop {
        val pad = BodyGlassCanvasPad.toPx()
        val o = Offset(-pad, -pad)
        val s = Size(size.width + pad * 2f, size.height + pad * 2f)
        if (color.alpha < 1f) drawRect(under.copy(alpha = 1f), o, s)
        drawRect(color, o, s)
    }
    return remember(spec.material, canvas) { GlassFill(spec.material, GlassSource(canvas, canvas)) }
}

/** 去掉面色调整：底色原样透出来（填充的玻璃按钮用） */
private fun MochiGlassRecipe.untinted(): MochiGlassRecipe =
    copy(faceMatrix = MochiGlassRecipe.IdentityMatrix, fillDarken = 0f, fillLighten = 0f, fillNormal = 0f)

/**
 * 纯色画布上的边缘：
 * - 去掉原生的暗色细描边（定向高光那层）：它压暗轮廓外一像素，纯色上就是贴着按钮的一圈黑边。只能把高度设成 0，
 *   highlightAmount 只是响应曲线的参数，设 0 描边照样画。
 * - 边光不过 vibrant 矩阵（纯色上会把蓝推成青，下沿像一圈杂色），改成同色相略提亮 `1.2c + 0.04`、增益 1：
 *   近似，由 iOS 真机截图估出（shots26 light_capsule_blue：玻璃面 (11, 11, 188)，贴边 (24, 24, 232)，色相不变、上下对称）。
 * - 下面的补光减半，上沿是主光（近似，用户要求下面比上面弱）
 * - 没有外阴影：正文按钮背后是纯色底，不是浮在内容上
 */
private fun MochiGlassRecipe.bodyEdge(): MochiGlassRecipe = copy(
    highlightHeight = 0.dp,
    edgeLightMatrix = BodyEdgeMatrix,
    edgeLightGain = 1f,
    edgeLightFill = edgeLightFill * 0.5f,
    shadowOpacity = 0f,
)

/** 正文玻璃按钮边光的颜色矩阵（3×4）：每个通道 1.2c + 0.04 */
private val BodyEdgeMatrix = listOf(1.2f, 0f, 0f, 0.04f, 0f, 1.2f, 0f, 0.04f, 0f, 0f, 1.2f, 0.04f)

/** 正文玻璃按钮的纯色画布往按钮外多铺的宽度 */
private val BodyGlassCanvasPad = 32.dp

/**
 * 按钮组：里面的 [MochiButton] / [MochiIconButton] 的底色由组统一画。按下的按钮放大后，
 * 和间距小于 [MochiButtonGroupBehavior.reach] 的邻居用融球颈连起来（像 iOS 26 工具栏里挨着的按钮），邻居也朝它靠过去一点。
 * 组里的按钮建议用不透明底色（填充 / 色调）。
 */
@Composable
public fun MochiButtonGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    behavior: MochiButtonGroupBehavior = MochiButtonDefaults.groupBehavior(),
    content: @Composable RowScope.() -> Unit,
) {
    val group = remember { ButtonGroupState() }
    val renderer = rememberMochiMetaballRenderer()
    val density = LocalDensity.current
    with(density) {
        group.fusePx = behavior.fuseRadius.toPx()
        group.reachPx = behavior.reach.toPx()
        group.attractPx = behavior.attraction.toPx()
        group.acrossKinds = behavior.fuseAcrossKinds
        group.glassMargin = GroupGlassMargin.toPx()
    }
    // 玻璃层要融合形状，只在 Android 13+ 有；栏里的组（按钮已经是栏玻璃）不另画。
    // 组里哪些按钮是玻璃由各自的样式决定（[bodyGlassSpec]），纯色的照常由组画，两种可以混排
    group.glassOn = LocalBarControlGlass.current == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    Box(modifier, propagateMinConstraints = true) {
        if (group.glassOn) {
            val materials = group.buttons.mapNotNull { it.groupGlass?.material }.distinct()
            for (m in materials) key(m) { GroupGlassLayer(group, m) }
        }
        Row(
            Modifier
                .onPlaced { group.coords = it }
                .drawBehind { group.draw(this@drawBehind, renderer) },
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalMochiButtonGroup provides group) { content() }
        }
    }
}

/** 按钮组的玻璃层比组四周多出的宽度：按下放大（每边最多约 8dp）、拖动形变的按钮不能长出图层 */
private val GroupGlassMargin = 24.dp

/**
 * 按钮组里材质是 [material] 的那些按钮的玻璃，一层画完（像 SwiftUI 的 GlassEffectContainer）：形状是这些按钮的并集，
 * 按下的按钮和连上的邻居做光滑并集，融球颈也是玻璃（[ButtonGroupState.glassMerge]）。
 * 背景和单独的正文玻璃按钮一样是纯色画布：整块先铺第一个按钮的底色，别的颜色的按钮在自己的位置上铺自己的
 */
@Composable
private fun BoxScope.GroupGlassLayer(group: ButtonGroupState, material: MochiGlass) {
    val under by rememberUpdatedState(MochiTheme.colorScheme.surface)
    val canvas = rememberCanvasBackdrop {
        val l = group.layout() ?: return@rememberCanvasBackdrop
        val o = Offset(group.glassMargin, group.glassMargin)
        val mine = l.shifted.filter { it.first.groupGlass?.material == material }
        val first = mine.firstOrNull()?.first?.groupGlass?.base ?: return@rememberCanvasBackdrop
        val pad = BodyGlassCanvasPad.toPx()
        fun fill(c: Color, r: Rect) {
            if (c.alpha < 1f) drawRect(under.copy(alpha = 1f), r.topLeft, r.size)
            drawRect(c, r.topLeft, r.size)
        }
        fill(first, Rect(Offset(-pad, -pad), Size(size.width + pad * 2f, size.height + pad * 2f)))
        // 别的颜色往外多铺一段，按下拉出的融球颈是它自己的颜色；但最多铺到和别的颜色的按钮之间的一半，
        // 不然铺进旁边按钮里，旁边按钮的边缘折射会透出这个颜色
        for ((b, r) in mine) {
            val c = b.groupGlass?.base ?: continue
            if (c == first) continue
            var grow = group.reachPx / 2f
            for ((n, nr) in mine) {
                if (n === b || n.groupGlass?.base == c) continue
                val gap = maxOf(nr.left - r.right, r.left - nr.right, nr.top - r.bottom, r.top - nr.bottom)
                grow = minOf(grow, (gap / 2f).coerceAtLeast(0f))
            }
            fill(c, r.translate(o).inflate(grow))
        }
    }
    val fill = remember(material, canvas) { GlassFill(material, GlassSource(canvas, canvas)) }
    val glow = rememberGlassGlowState()
    DisposableEffect(group, material, glow) {
        group.glows[material] = glow
        onDispose { if (group.glows[material] === glow) group.glows.remove(material) }
    }
    val radius = remember { FloatArray(1) }
    GlassSurface(
        fill,
        null,
        glow,
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val m = group.glassMargin.toInt()
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + m * 2, constraints.maxHeight + m * 2))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-m, -m) }
            },
        cornerRadiusPx = { radius[0] },
        merge = {
            group.glassMerge(material)?.let { (merge, r) ->
                radius[0] = r
                merge
            }
        },
    )
}

// ───────────────────────── 实现 ─────────────────────────

@Composable
@Suppress("ktlint:compose:content-slot-reused") // 玻璃 / 纯色两个分支按样式选一个，同一个按钮不会来回切
private fun ButtonCore(
    onClick: () -> Unit,
    enabled: Boolean,
    colors: MochiButtonColors,
    cornerRadius: Dp?,
    behavior: MochiButtonBehavior,
    modifier: Modifier = Modifier,
    glassSpec: BodyGlassSpec? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val group = LocalMochiButtonGroup.current
    // 栏里的按钮（MochiBarButton 等）在有玻璃材质和背景来源时画成玻璃；按钮组里的由组统一画（纯色，或组的玻璃层）
    val glass = LocalBarControlGlass.current.takeIf { group == null }
    val glassGlow = if (glass != null) rememberGlassGlowState() else null
    val groupGlass = glassSpec.takeIf { group != null && group.glassOn }
    val visual = remember { ButtonVisual() }
    val glow = rememberMochiGlowState(remember(colors.glow) { MochiGlowSpec(colors.glow) })
    val scope = rememberCoroutineScope()
    val currentOnClick by rememberUpdatedState(onClick)
    val path = remember { G2PathCache() }
    val radiusPx = cornerRadius?.let { with(density) { it.toPx() } }
    val shape = remember(cornerRadius) { cornerRadius?.let { MochiRoundedRectangle(it) } ?: MochiCapsule }
    val container = if (enabled) colors.container else colors.disabledContainer
    val contentColor = if (enabled) colors.content else colors.disabledContent

    visual.container = container
    visual.content = contentColor
    visual.fuseGroup = behavior.fuseGroup
    visual.radius = radiusPx
    visual.glow = glow
    visual.pressScale = behavior.pressScale
    visual.flex = glass != null || groupGlass != null
    visual.groupGlass = groupGlass
    visual.unit = density.density
    visual.maxGrowPx = if (behavior.maxGrow.isSpecified) with(density) { behavior.maxGrow.toPx() } else Float.POSITIVE_INFINITY
    visual.followPx = with(density) { behavior.follow.toPx() }

    if (group != null) {
        DisposableEffect(group, visual) {
            group.buttons += visual
            onDispose { group.buttons -= visual }
        }
    }

    Box(
        modifier
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) {
                        currentOnClick()
                        true
                    } else {
                        false
                    }
                }
            }
            .onPlaced { visual.coords = it }
            .pointerInput(enabled, behavior.cancelSlop) {
                if (!enabled) return@pointerInput
                val slop = behavior.cancelSlop.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // 在 Main 阶段声明点击归属，避免面板的空白点击手势把按钮的按下事件抢走。
                    down.consume()
                    visual.finger = down.position
                    visual.down = down.position
                    visual.held = true
                    visual.start(scope)
                    val groupGlow = if (group != null && groupGlass != null) group.glowFor(visual) else null
                    val groupOrigin = if (groupGlow != null) group?.glassOrigin(visual) ?: Offset.Zero else Offset.Zero
                    if (glassGlow != null) {
                        glassGlow.press(down.position)
                    } else if (groupGlow != null) {
                        groupGlow.press(down.position + groupOrigin)
                    } else {
                        scope.launch { glow.press(down.position, MochiGlowDefaults.radius(size.toSize(), this@pointerInput)) }
                    }
                    val touchSlop = viewConfiguration.touchSlop
                    var moved = false
                    var click = false
                    try {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Main)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            visual.finger = c.position
                            glow.moveTo(c.position)
                            if (!moved && (c.position - down.position).getDistance() > touchSlop) moved = true
                            if (moved) {
                                glassGlow?.drag(c.position)
                                groupGlow?.drag(c.position + groupOrigin)
                            }
                            if (!c.pressed) {
                                val p = c.position
                                click = p.x > -slop && p.y > -slop && p.x < size.width + slop && p.y < size.height + slop
                                c.consume()
                                break
                            }
                            // 按在按钮上的这一整下都归按钮：Main 阶段先于外层消费移动，外层的滚动、面板拖动、
                            // 右滑返回都不会接手，按钮一直跟手直到松手（拖出 cancelSlop 外松手不算点击）
                            c.consume()
                        }
                    } finally {
                        // enabled 改变会取消 pointerInput；禁用、滚动接管和卸载也必须释放形变与高光。
                        visual.held = false
                        glow.markReleased()
                        scope.launch { glow.release() }
                        glassGlow?.release()
                        groupGlow?.release()
                    }
                    if (click) currentOnClick()
                }
            }
            // 放大 + 跟手：只改图层；底色在组里时由组画（才能和邻居融在一起）。玻璃按钮的形变交给玻璃（背景不跟着放大）
            .then(if (glass == null) Modifier.graphicsLayer { pressTransform(visual) } else Modifier)
            .drawBehind {
                if (glass != null) return@drawBehind
                val r = (radiusPx ?: (size.height / 2f)).coerceAtMost(min(size.width, size.height) / 2f)
                val rect = Rect(Offset.Zero, size)
                val p = path.get(rect, r, 1f)
                if (group == null) {
                    if (container.alpha > 0f) drawPath(p, container)
                    if (enabled && colors.pressedContainer.alpha > 0f) {
                        val k = visual.pressAmount
                        if (k > 0.01f) drawPath(p, colors.pressedContainer, alpha = k)
                    }
                    if (colors.outline.alpha > 0f) drawPath(p, colors.outline, style = Stroke(1.dp.toPx()))
                    drawGlow(glow, Outline.Generic(p), glow.center, base = container)
                } else {
                    if (colors.outline.alpha > 0f) drawPath(p, colors.outline, style = Stroke(1.dp.toPx()))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (glass != null) {
            GlassSurface(
                glass,
                cornerRadius,
                glassGlow,
                Modifier.matchParentSize().graphicsLayer {
                    translationX = visual.shift.x
                    translationY = visual.shift.y
                },
                scaleX = { visual.flexX },
                scaleY = { visual.flexY },
            )
            Box(Modifier.graphicsLayer { pressTransform(visual) }, contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides contentColor, LocalBarControlGlass provides null) { content() }
            }
        } else {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}

/** 按下放大、跟手偏移、朝手指方向略微拉长；玻璃按钮照 iOS 的形变（见 [ButtonVisual.flex]） */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.pressTransform(visual: ButtonVisual) {
    if (visual.flex) {
        scaleX = visual.flexX
        scaleY = visual.flexY
        translationX = visual.shift.x
        translationY = visual.shift.y
        return
    }
    val s = visual.scale
    val sh = visual.shift
    val w = size.width.coerceAtLeast(1f)
    val h = size.height.coerceAtLeast(1f)
    scaleX = s * (1f + abs(sh.x) / w * 0.6f)
    scaleY = s * (1f + abs(sh.y) / h * 0.6f)
    translationX = sh.x
    translationY = sh.y
}

/** 栏里的按钮（[MochiBarButton]、[MochiBarIconButton]、返回按钮）自己的那块玻璃：材质加背景。不是背景来源，只在按钮内部读 */
internal val LocalBarControlGlass = staticCompositionLocalOf<GlassFill?> { null }

/** 一个按钮此刻的样子：放大、跟手偏移，和画底色需要的信息 */
@Stable
internal class ButtonVisual {
    var scale by mutableFloatStateOf(1f)
        private set
    var shift by mutableStateOf(Offset.Zero)
        private set
    var held = false
    var finger = Offset.Zero
    var coords: LayoutCoordinates? by mutableStateOf(null)
    var container: Color by mutableStateOf(Color.Transparent)
    var content: Color = Color.Unspecified
    var fuseGroup: Any? = null
    var radius: Float? = null
    var glow: MochiGlowState? = null
    var pressScale = 1.08f

    /**
     * 玻璃按钮：按下放大和拖动形变照 iOS 的 _UIFlexInteraction（[glassFlexSpec]），不用 [pressScale] / [maxGrowPx] / [followPx]，
     * 横竖各自缩放（[flexX] / [flexY]），[shift] 是朝手指方向的挪动
     */
    var flex = false

    /** 1dp 合多少 px（iOS 的 pt 按 dp 算） */
    var unit = 1f

    /** 这一下按在哪（按钮坐标） */
    var down = Offset.Zero
    var flexX by mutableFloatStateOf(1f)
        private set

    /** 按钮组的玻璃层画它时用的材质和画布颜色；null = 组里按纯色画 */
    var groupGlass: BodyGlassSpec? by mutableStateOf(null)

    /** 这次按下的目标放大倍数（[pressAmount] 用） */
    private var flexLift = 1f
    var flexY by mutableFloatStateOf(1f)
        private set
    var maxGrowPx = Float.POSITIVE_INFINITY
    var followPx = 0f

    /** 按下时实际放大到的比例：宽按钮按每边最多长 [maxGrowPx] 封顶 */
    private val targetScale: Float
        get() {
            val w = coords?.takeIf { it.isAttached }?.size?.width ?: 0
            if (w <= 0 || maxGrowPx.isInfinite()) return pressScale
            return min(pressScale, 1f + 2f * maxGrowPx / w)
        }
    private var loop: Job? = null

    fun width(): Float = (coords?.takeIf { it.isAttached }?.size?.width ?: 1).toFloat().coerceAtLeast(1f)
    fun height(): Float = (coords?.takeIf { it.isAttached }?.size?.height ?: 1).toFloat().coerceAtLeast(1f)

    /** 0..1，放大到了多少 */
    val pressAmount: Float get() = if (flex) {
        ((flexX + flexY) / 2f - 1f) / (flexLift - 1f).coerceAtLeast(1e-3f)
    } else {
        (scale - 1f) / (targetScale - 1f).coerceAtLeast(1e-3f)
    }.coerceIn(0f, 1f)

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        if (loop?.isActive == true) return
        if (flex) {
            loop = scope.launch { runFlex() }
            return
        }
        loop = scope.launch {
            val s = MochiFollowSpring(0.26f, 0.6f)
            val o = MochiFollowSpring(0.3f, 0.7f)
            s.snapTo(Offset(scale, 0f))
            o.snapTo(shift)
            while (true) {
                val dt = s.awaitFrameDelta()
                scale = s.step(Offset(if (held) targetScale else 1f, 0f), dt).x
                shift = o.step(shiftTarget(), dt)
                if (!held && abs(scale - 1f) < 0.0005f && abs(s.velocity.x) < 0.01f && shift.getDistance() < 0.05f &&
                    o.velocity.getDistance() < 1f
                ) {
                    scale = 1f
                    shift = Offset.Zero
                    break
                }
            }
        }
    }

    /** iOS 的按压形变：放大、形变、挪动同一根弹簧，按住时用 tracking 那组参数 */
    private suspend fun runFlex() {
        val f = MochiFollowSpring(1f, 1f)
        val o = MochiFollowSpring(1f, 1f)
        f.snapTo(Offset(flexX, flexY))
        o.snapTo(shift)
        while (true) {
            val dt = f.awaitFrameDelta()
            val w = width()
            val h = height()
            val spec = glassFlexSpec(w / unit, h / unit)
            flexLift = (max(w, h) + spec.lift * unit) / max(w, h).coerceAtLeast(1f)
            val response = if (held) spec.trackingResponse else spec.response
            val damping = if (held) spec.trackingDamping else spec.damping
            f.response = response
            f.dampingRatio = damping
            o.response = response
            o.dampingRatio = damping
            val t = if (held) glassFlexTarget(spec, w, h, down, finger, unit) else null
            val s = f.step(if (t == null) Offset(1f, 1f) else Offset(t.scaleX, t.scaleY), dt)
            flexX = s.x
            flexY = s.y
            shift = o.step(t?.drift ?: Offset.Zero, dt)
            if (!held && abs(s.x - 1f) < 0.0005f && abs(s.y - 1f) < 0.0005f && f.velocity.getDistance() < 0.01f &&
                shift.getDistance() < 0.05f && o.velocity.getDistance() < 1f
            ) {
                flexX = 1f
                flexY = 1f
                shift = Offset.Zero
                break
            }
        }
    }

    private fun shiftTarget(): Offset {
        val c = coords ?: return Offset.Zero
        if (!held) return Offset.Zero
        val w = c.size.width / 2f
        val h = c.size.height / 2f
        return Offset(
            followPx * tanh((finger.x - w) / w.coerceAtLeast(1f)),
            followPx * tanh((finger.y - h) / h.coerceAtLeast(1f)),
        )
    }

    /** 在 group 坐标系里的样子（含放大、跟手、略微拉长） */
    fun rectIn(group: LayoutCoordinates): Rect? {
        val c = coords?.takeIf { it.isAttached } ?: return null
        val r = group.localBoundingBoxOf(c, clipBounds = false)
        val w = r.width
        val h = r.height
        val sx = if (flex) flexX else scale * (1f + abs(shift.x) / w.coerceAtLeast(1f) * 0.6f)
        val sy = if (flex) flexY else scale * (1f + abs(shift.y) / h.coerceAtLeast(1f) * 0.6f)
        val center = r.center + shift
        return Rect(center.x - w * sx / 2f, center.y - h * sy / 2f, center.x + w * sx / 2f, center.y + h * sy / 2f)
    }

    /** 画在 group 里时的圆角 */
    fun cornerFor(rect: Rect): Float = (radius?.let { it * (if (flex) min(flexX, flexY) else scale) } ?: (rect.height / 2f))
        .coerceAtMost(min(rect.width, rect.height) / 2f)
}

/** 按钮组：统一画组里按钮的底色，按下的和邻居融在一起 */
@Stable
internal class ButtonGroupState {
    val buttons = mutableStateListOf<ButtonVisual>()
    var coords: LayoutCoordinates? by mutableStateOf(null)
    private val paths = HashMap<ButtonVisual, G2PathCache>()
    private val materialPaint = Paint()

    /** 组能画玻璃层（Android 13+、不在栏里）：带 [ButtonVisual.groupGlass] 的按钮由玻璃层画，纯色那层跳过它们 */
    var glassOn = false

    /** 玻璃层比组四周多出的宽度（px）：按下放大的按钮会长出组的范围 */
    var glassMargin = 0f

    /** 每个玻璃层（按材质分）的按压光斑 */
    val glows = HashMap<MochiGlass, GlassGlowState>()

    var fusePx = 0f
    var reachPx = 0f
    var attractPx = 0f
    var acrossKinds = false

    fun glowFor(button: ButtonVisual): GlassGlowState? = button.groupGlass?.let { glows[it.material] }

    /** 按钮左上角在它那个玻璃层里的位置（光斑坐标用） */
    fun glassOrigin(button: ButtonVisual): Offset {
        val g = coords?.takeIf { it.isAttached } ?: return Offset.Zero
        val c = button.coords?.takeIf { it.isAttached } ?: return Offset.Zero
        return g.localBoundingBoxOf(c, clipBounds = false).topLeft + Offset(glassMargin, glassMargin)
    }

    /** 同一种按钮才融在一起：意思相反、样式不同的按钮连成一滴水会被读成「是一回事」 */
    private fun sameKind(a: ButtonVisual, b: ButtonVisual): Boolean = acrossKinds ||
        if (a.fuseGroup != null || b.fuseGroup != null) {
            a.fuseGroup == b.fuseGroup
        } else {
            a.container == b.container && a.content == b.content && a.groupGlass == b.groupGlass
        }

    /** 每个按钮此刻在组里的位置（含按下放大、被吸过去的位移），和按下的按钮连上的邻居（邻居、位置、融球颈半径） */
    class Layout(val shifted: List<Pair<ButtonVisual, Rect>>, val links: Map<ButtonVisual, List<Triple<ButtonVisual, Rect, Float>>>)

    fun layout(): Layout? {
        val g = coords?.takeIf { it.isAttached } ?: return null
        val rects = buttons.mapNotNull { b -> b.rectIn(g)?.let { b to it } }
        if (rects.isEmpty()) return null
        val pressed = rects.filter { it.first.pressAmount > 0.01f }
        // 邻居被按下的那个吸过去一点
        val shifted = rects.map { (b, r) ->
            var d = Offset.Zero
            for ((p, pr) in pressed) {
                if (p === b) continue
                // 不同种的邻居只靠过去三分之一，不连上
                val k = proximityFuse(r, pr, reachPx) * p.pressAmount * (if (sameKind(b, p)) 1f else 0.35f)
                if (k <= 0f) continue
                val dir = pr.center - r.center
                val len = dir.getDistance().coerceAtLeast(1f)
                d += dir / len * (attractPx * k)
            }
            b to r.translate(d)
        }
        val links = shifted.filter { it.first.pressAmount > 0.01f }.associate { (p, pr) ->
            p to shifted.mapNotNull { (n, nr) ->
                if (n === p || !sameKind(n, p)) return@mapNotNull null
                val fuse = fusePx * proximityFuse(nr, pr, reachPx) * p.pressAmount
                if (fuse <= 0.5f) null else Triple(n, nr, fuse)
            }
        }
        return Layout(shifted, links)
    }

    /**
     * 材质 [material] 那个玻璃层此刻的形状和主形状的圆角：主形状是这层里按得最深的按钮（没有按下的就是第一个），
     * 其余按钮按顺序并进来，和主形状连上的先并、按融球颈半径做光滑并集，别的直接取并集。
     * 坐标是玻璃层里的（组的坐标加 [glassMargin]）
     */
    fun glassMerge(material: MochiGlass): Pair<GlassMerge, Float>? {
        val l = layout() ?: return null
        val mine = l.shifted.filter { it.first.groupGlass?.material == material }
        if (mine.isEmpty()) return null
        val main = mine.maxByOrNull { it.first.pressAmount } ?: return null
        val links = l.links[main.first].orEmpty().associate { it.first to it.third }
        val o = Offset(glassMargin, glassMargin)
        val others = mine.filter { it.first !== main.first }.sortedByDescending { links[it.first] ?: 0f }
        val partners = others.map { (b, r) -> GlassMergePartner(r.translate(o), b.cornerFor(r), links[b] ?: 0f) }
        return GlassMerge(main.second.translate(o), partners) to main.first.cornerFor(main.second)
    }

    fun draw(scope: DrawScope, renderer: ui.feice.mochi.effect.metaball.MochiMetaballRenderer) {
        val g = coords?.takeIf { it.isAttached } ?: return
        val l = layout() ?: return
        paths.keys.retainAll(buttons.toSet())
        // 玻璃层画的按钮不在这里画
        fun solid(b: ButtonVisual) = !(glassOn && b.groupGlass != null)
        val shifted = l.shifted.filter { solid(it.first) }
        val linksByButton = l.links.filterKeys { solid(it) }.mapValues { (_, v) -> v.filter { solid(it.first) } }
        val linked = linksByButton.filterValues { it.isNotEmpty() }.flatMap { (p, neighbours) ->
            listOf(p) + neighbours.map { it.first }
        }.toSet()
        with(scope) {
            // Independent buttons keep their original alpha and glow.
            for ((button, rect) in shifted) {
                if (button in linked) continue
                val path = paths.getOrPut(button) { G2PathCache() }.get(rect, button.cornerFor(rect), 1f)
                if (button.container.alpha > 0f) drawPath(path, button.container)
                if (button.pressAmount > 0.01f) {
                    val origin = g.localBoundingBoxOf(button.coords ?: continue, clipBounds = false).topLeft
                    button.glow?.let { drawGlow(it, Outline.Generic(path), it.center + origin, base = button.container) }
                }
            }
            // Equal-alpha materials (including iOS semantic fills) form one opaque
            // intermediate surface. Apply their alpha once after all joins, so AA
            // edges cannot reveal either repeated bodies or pairwise clip seams.
            val materialAlpha = linked.firstOrNull()?.container?.alpha
            val isolated = materialAlpha != null && materialAlpha > 0f && materialAlpha < 1f &&
                linked.all { it.container.alpha == materialAlpha }
            val canvas = drawContext.canvas
            if (isolated) {
                materialPaint.alpha = checkNotNull(materialAlpha)
                val bounds = shifted.filter { it.first in linked }.map { it.second }
                canvas.saveLayer(
                    Rect(bounds.minOf { it.left }, bounds.minOf { it.top }, bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
                        .inflate(fusePx + 2f),
                    materialPaint,
                )
            }
            try {
                val painted = LinkedHashSet<ButtonVisual>()
                val paintedLinks = HashSet<Set<ButtonVisual>>()
                for ((p, pr) in shifted) {
                    val links = linksByButton[p].orEmpty()
                    if (links.isEmpty()) continue
                    val origin = g.localBoundingBoxOf(p.coords ?: continue, clipBounds = false).topLeft
                    for ((n, nr, fuse) in links) {
                        if (!paintedLinks.add(setOf(n, p))) continue
                        val covered = if (isolated || (n.container.alpha >= 1f && p.container.alpha >= 1f)) {
                            emptyList()
                        } else {
                            painted.mapNotNull { button ->
                                shifted.firstOrNull { it.first === button }?.second?.let { r ->
                                    paths.getOrPut(button) { G2PathCache() }.get(r, button.cornerFor(r), 1f)
                                }
                            }
                        }
                        fun DrawScope.drawUncovered(index: Int) {
                            if (index < covered.size) {
                                clipPath(covered[index], ClipOp.Difference) { drawUncovered(index + 1) }
                                return
                            }
                            drawMochiMetaball(
                                renderer,
                                MochiMetaballFrame(MochiBlob(nr, n.cornerFor(nr)), MochiBlob(pr, p.cornerFor(pr)), fuse),
                                MochiMetaballColors(
                                    if (isolated) n.container.copy(alpha = 1f) else n.container,
                                    if (isolated) p.container.copy(alpha = 1f) else p.container,
                                    Color.Transparent,
                                ),
                                glow = p.glow,
                                glowOrigin = -origin,
                            )
                        }
                        drawUncovered(0)
                        painted += n
                        painted += p
                    }
                }
            } finally {
                if (isolated) canvas.restore()
            }
        }
    }
}

internal val LocalMochiButtonGroup = staticCompositionLocalOf<ButtonGroupState?> { null }
