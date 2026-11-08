// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.graphics.compositeOver
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
import androidx.compose.ui.semantics.onLongClick
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
import com.kyant.capsule.ContinuousRoundedRectangle
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
import ui.feice.mochi.basic.menu.AnchorTracker
import ui.feice.mochi.basic.menu.Overlay
import ui.feice.mochi.basic.menu.rememberMochiEngine
import ui.feice.mochi.effect.elastic.MochiRubberBandState
import ui.feice.mochi.effect.elastic.jellyCrossScale
import ui.feice.mochi.effect.elastic.jellyStretch
import ui.feice.mochi.effect.elastic.rememberMochiRubberBandState
import ui.feice.mochi.effect.elastic.rubberStretched
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.GlassSurface
import ui.feice.mochi.effect.glass.glassOn
import ui.feice.mochi.effect.glass.overlayBackdropSource
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

/** 内部用的扁平参数表（各个公开参数合起来） */
@Immutable
internal data class MochiStyle(
    val buttonColor: Color,
    val onButtonColor: Color,
    val panelColor: Color,
    val onPanelColor: Color,
    val selectedColor: Color,
    val onSelectedColor: Color,
    val highlightColor: Color,
    val destructiveColor: Color,
    val shadowColor: Color,
    val glowColor: Color,
    val openMode: MochiOpenMode,
    val pressStyle: MochiPressStyle,
    val itemLayout: MochiItemLayout,
    val hapticProfile: MochiHapticProfile,
    val buttonCornerRadius: Dp,
    val panelCornerRadius: Dp,
    val g2Smoothing: Float,
    val fuseRadius: Dp,
    val gap: Dp,
    val gel: Float,
    val squash: Float,
    val rubberMax: Dp,
    val menuWidth: Dp,
    val itemHeight: Dp,
    val panelPadding: Dp,
    val align: MochiAlign,
    val screenMargin: Dp,
    /** 玻璃菜单：按 iOS 27 玻璃菜单排版（[LiquidMenu]） */
    val liquid: Boolean = false,
)

/**
 * 开了液态玻璃（主题有 [ui.feice.mochi.theme.MochiMaterials.menuGlass]，页面有背景录制）时菜单换成 iOS 27 玻璃菜单的样子：
 * 面板圆角、上下留白、行高照 [LiquidMenu]；[cover] 时按钮直接变形成菜单（iOS 的菜单从按钮长出来）。
 * 宽度是库默认值时换成 iOS 的默认宽度，调用方自己给的宽度不动
 */
internal fun MochiStyle.liquid(cover: Boolean): MochiStyle = copy(
    liquid = true,
    openMode = if (cover) MochiOpenMode.Cover else openMode,
    // iOS 菜单的形变不经过中间形状（morph 的 usingIntermediateShapes 传的 false），面板和来源之间不连融球颈
    fuseRadius = 0.dp,
    panelCornerRadius = LiquidMenu.CornerRadius,
    panelPadding = LiquidMenu.Inset,
    itemHeight = LiquidMenu.ItemHeight,
    menuWidth = if (menuWidth in DefaultMenuWidths) LiquidMenu.Width else menuWidth,
)

/**
 * 玻璃菜单没指定动画（用的主题默认）时换成 iOS 27 菜单的原生弹簧。从玻璃按钮组里脱出来的菜单也用这一组：
 * 试过 AnimationKit 的 mainEjectSpring / mainAbsorbSpring（0.75 / 0.5、0.8 / 0.7，278–281）并按 settings 的 speed 0.7 放慢，
 * 时长和 iOS 录屏对得上，但实机拖沓发硬，回到 liquidMorph 的 0.8 / 0.3
 */
@Composable
internal fun liquidMotion(liquid: Boolean, motion: MochiMotion): MochiMotion = if (liquid && motion == MochiTheme.motionScheme.menu) {
    MochiMotions.Ios27Menu
} else {
    motion
}

/** 按钮组里的一格按下时图标的不透明度（iOS 录屏里「⋯」按下后的深浅量出来的，约 0.23，近似） */
private const val GroupItemPressedAlpha = 0.23f

/** 菜单这会儿是不是玻璃菜单：主题有菜单玻璃，窗口里有页面录制可取样 */
@Composable
internal fun liquidMenu(): Boolean = glassOn(GlassRole.Menu) && overlayBackdropSource() != null

/**
 * iOS 27 玻璃菜单的排版，读自 24A435 UIKit：_UIContextMenuPlatformMetrics_Glass（圆角、留白、行高、边距、图标框）、
 * 它的父类（默认宽度、对勾那一列的缩进），以及 _UIContextMenuLargeCellLayout / LargeLeadingImageCellLayout 的约束算法。
 * iOS 的行占满菜单宽、高亮块左右缩进 10；Mochi 的列表整体缩进 [Inset]，所以行内的边距都减掉它
 */
internal object LiquidMenu {
    /** menuCornerRadius */
    val CornerRadius = 32.dp

    /** 列表上下留白（sectionInsets 上下 10），也是高亮块左右的缩进（largeItemContentShapeInsets 左右 10，高亮圆角 32 − 10） */
    val Inset = 10.dp

    /** 最小行高 = itemTopToFirstBaseline 27 + itemLastBaselineToBottom 15 */
    val ItemHeight = 42.dp

    /** 文字离行左右边（menuItemInternalPadding 左右 28） */
    val Padding = 28.dp

    /** 有选中项（单选）的菜单左边多留一列给对勾（leadingIndentationWidth） */
    val SelectionIndent = 15.dp

    /** 对勾中心 = 左边距 / 2 + 它（leadingDecorationViewOffset） */
    val CheckOffset = 6.dp

    /** 图标在文字前面：图标框宽（dynamicImageBoxWidth）、框往左挪（leadingImageOffset）、框到文字（minIntraItemSpacing） */
    val ImageBox = 40.dp
    val ImageOffset = (-8).dp
    val ImageGap = 4.dp

    /** defaultMenuWidth */
    val Width = 250.dp
}

internal fun mochiStyle(c: MochiDropdownColors, b: MochiDropdownBehavior, p: MochiDropdownPhysics, menuWidth: Dp) = MochiStyle(
    // 融色引擎要求按钮色不透明：打开时真按钮隐藏、浮层里画克隆，下面还垫着面板的阴影。
    // 半透明的按钮色（灰色 fill）会让阴影从按钮里透出来，像一块突然浮起的半透明遮罩，收起时又随浮层一下消失。
    // 所以半透明色先合成成不透明色（调用方知道底色时应自己合成到底色上，这里兜底合成到面板色上），
    // 真按钮和克隆用同一个不透明色；完全透明（长按预览的来源）不受影响
    buttonColor = opaqueButtonColor(c.buttonColor, c.panelColor),
    onButtonColor = c.buttonContentColor,
    panelColor = c.panelColor,
    onPanelColor = c.panelContentColor,
    selectedColor = c.selectedColor,
    onSelectedColor = c.selectedContentColor,
    highlightColor = c.highlightColor,
    destructiveColor = c.destructiveColor,
    shadowColor = c.shadowColor,
    glowColor = c.glowColor,
    openMode = b.openMode,
    pressStyle = b.pressStyle,
    itemLayout = b.itemLayout,
    hapticProfile = b.hapticProfile,
    buttonCornerRadius = MochiDropdownDefaults.ButtonCornerRadius,
    panelCornerRadius = MochiDropdownDefaults.PanelCornerRadius,
    g2Smoothing = p.g2Smoothing,
    fuseRadius = p.fuseRadius,
    gap = MochiDropdownDefaults.Gap,
    gel = p.gel,
    squash = p.squash,
    rubberMax = p.rubberMax,
    menuWidth = menuWidth,
    itemHeight = MochiDropdownDefaults.ItemHeight,
    panelPadding = MochiDropdownDefaults.PanelPadding,
    align = b.align,
    screenMargin = MochiDropdownDefaults.ScreenMargin,
)

/**
 * 从按钮里「长」出来的融球选择菜单。
 *
 * - 按下（或长按）打开后手指不用松开，直接滑到选项上松手即选中；滑出面板会被橡皮筋拉住
 * - selectedIndex 传 -1（默认）就是普通操作菜单（没有选中态）
 * - 颜色 / 行为 / 形变参数分别来自 [MochiDropdownDefaults.dropdownColors] / [MochiDropdownDefaults.behavior] / [MochiDropdownDefaults.physics]
 * - API 33+ 用 AGSL 画融球；33 以下自动降级成两个独立 G2 形状
 * - 系统关了动画（开发者选项动画时长 0）时，果冻 / 融球 / 橡皮筋这些弹性效果全关，对应 iOS 的「减弱动态效果」
 * - [onClick]：给了并且 trigger = LongPress 时，轻点执行它、长按才打开菜单（比如返回按钮：点了返回，长按看历史）
 */
@Composable
public fun MochiDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: ImmutableList<MochiItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectedIndex: Int = -1,
    colors: MochiDropdownColors = MochiDropdownDefaults.dropdownColors(),
    behavior: MochiDropdownBehavior = MochiDropdownDefaults.behavior(),
    physics: MochiDropdownPhysics = MochiDropdownDefaults.physics(),
    motion: MochiMotion = MochiTheme.motionScheme.menu,
    menuWidth: Dp = MochiDropdownDefaults.MenuWidth,
    buttonPadding: PaddingValues = MochiDropdownDefaults.ButtonPadding,
    buttonMinWidth: Dp = 0.dp,
    buttonMinHeight: Dp = MochiDropdownDefaults.ButtonMinHeight,
    onClick: (() -> Unit)? = null,
    buttonContent: @Composable RowScope.() -> Unit,
) {
    DropdownCore(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        items = items,
        onItemClick = onItemClick,
        enabled = enabled,
        selectedIndex = selectedIndex,
        colors = colors,
        behavior = behavior,
        physics = physics,
        motion = motion,
        menuWidth = menuWidth,
        buttonPadding = buttonPadding,
        buttonMinWidth = buttonMinWidth,
        buttonMinHeight = buttonMinHeight,
        onClick = onClick,
        modifier = modifier,
        buttonContent = buttonContent,
    )
}

/**
 * 下拉菜单、弹出层按钮（[MochiPopoverButton]）、从按钮弹出的操作确认（[MochiConfirmationDialogBox]）共用的实现：
 * 按钮本身（按压放大、发光、隐藏 / 克隆）、开关动画、按住滑选、融色和融球颈都在这里，所以三者手感完全一致。
 *
 * @param header 选项上面的说明文字（操作确认的标题），手指在它上面不算选项
 * @param content 不为 null = 弹出层：面板里放任意内容，按内容尺寸长出来（没有选项、不滑选）
 * @param panelCornerRadius 面板圆角，null = 菜单默认
 * @param group 按钮是玻璃按钮组里的一格（[MochiBarGroupDropdownItem]）：按下时图标变暗、不放大、不画按压光（组的玻璃自己发光）；
 *   开了玻璃菜单时整个组变形成菜单（276–282 试过水滴式脱出、和组融成一块，实机拖沓，没用）
 */
@Composable
internal fun DropdownCore(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: ImmutableList<MochiItem>,
    onItemClick: (Int) -> Unit,
    enabled: Boolean,
    selectedIndex: Int,
    colors: MochiDropdownColors,
    behavior: MochiDropdownBehavior,
    physics: MochiDropdownPhysics,
    motion: MochiMotion,
    menuWidth: Dp,
    buttonPadding: PaddingValues,
    buttonMinWidth: Dp,
    buttonMinHeight: Dp,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    header: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    panelCornerRadius: Dp? = null,
    group: BarGroupHandle? = null,
    buttonContent: @Composable RowScope.() -> Unit,
) {
    // 弹出层（content）不是选项列表，不换玻璃菜单的排版
    val liquid = content == null && liquidMenu()
    val style = remember(colors, behavior, physics, menuWidth, panelCornerRadius, liquid) {
        mochiStyle(colors, behavior, physics, menuWidth).let {
            if (panelCornerRadius !=
                null
            ) {
                it.copy(panelCornerRadius = panelCornerRadius)
            } else {
                it
            }
        }.let { if (liquid) it.liquid(cover = true) else it }
    }
    val menuMotion = liquidMotion(liquid, motion)
    val trigger = behavior.trigger
    val view = LocalView.current
    // 锚点位置平时不进可观察状态（转场里每帧都变），浮层打开期间才跟踪
    val anchor = remember { AnchorTracker() }
    // 开关动画、发光、触感、滑选、返回键都在引擎里：和长按预览、返回历史菜单是同一套
    val engine = rememberMochiEngine(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        onItemClick = onItemClick,
        itemCount = items.size,
        style = style,
        motion = menuMotion,
        dismissDelayMillis = behavior.dismissDelayMillis,
        anchorSize = { anchor.rect.size },
        onTap = onClick,
    )
    val controller = engine.controller
    val callbacks = engine.callbacks
    val glow = engine.glow
    val press = engine.press
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val currentOnClick by rememberUpdatedState(onClick)

    val buttonShape: Shape = remember(style.buttonCornerRadius, style.g2Smoothing) {
        ContinuousRoundedRectangle(style.buttonCornerRadius, continuityFor(style.g2Smoothing))
    }

    // 栏里的按钮（返回按钮的历史菜单等）在有玻璃材质和背景来源时按钮本身画成玻璃；按钮组里的一格由组画
    val buttonGlass = if (group == null) LocalBarControlGlass.current else null
    // 布局里的真按钮。菜单打开期间隐藏，由浮层里的克隆 + shader 接管
    Box(
        modifier = modifier
            .onGloballyPositioned {
                anchor.update(Rect(it.positionInWindow() + view.windowOriginOnScreen(), it.size.toSize()))
            }
            .then(if (enabled) Modifier.mochiButtonGesture(trigger, controller, { anchor.rect.topLeft }, callbacks) else Modifier)
            .semantics {
                role = Role.Button
                if (enabled) {
                    onClick {
                        val c = currentOnClick
                        if (c != null) c() else currentOnExpandedChange(!currentExpanded)
                        true
                    }
                    if (currentOnClick != null) {
                        onLongClick {
                            currentOnExpandedChange(true)
                            true
                        }
                    }
                } else {
                    disabled()
                }
            }
            .graphicsLayer {
                // 浮层接手画整个按钮组时，组自己不画，这一格照常录进组的内容里（浮层按组画它）
                alpha = if (engine.overlayReady && group?.hidden != true) {
                    0f
                } else if (!enabled) {
                    0.38f
                } else if (group != null && glow.held) {
                    // 按钮组里的一格按下时不放大、不发光（组的玻璃自己发光），图标变暗（iOS 的高亮）
                    GroupItemPressedAlpha
                } else {
                    1f
                }
                val s = if (group != null) 1f else press.value
                scaleX = s
                scaleY = s
            }
            // 不用 clip：Android 的 clipPath 不抗锯齿，G2 曲线边缘会有锯齿；直接按形状画（有 AA）
            .drawWithCache {
                val outline = buttonShape.createOutline(size, layoutDirection, this)
                onDrawBehind { if (buttonGlass == null) drawOutline(outline, style.buttonColor) }
            }
            // 长按还没打开前，按钮自己画发光（打开后由浮层的融球 shader 画）；玻璃按钮不画（近似：玻璃的按压光没接这条手势）
            .then(
                Modifier.mochiGlow(glow, buttonShape, origin = { anchor.rect.topLeft }).takeIf { group == null && buttonGlass == null }
                    ?: Modifier,
            )
            .sizeIn(minWidth = buttonMinWidth, minHeight = buttonMinHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (buttonGlass != null) GlassSurface(buttonGlass, null, null, Modifier.matchParentSize())
        Row(
            Modifier.padding(buttonPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides style.onButtonColor, LocalBarControlGlass provides null) { buttonContent() }
        }
    }

    anchor.tracking = expanded || engine.overlayVisible
    engine.Overlay(
        anchorOnScreen = anchor.observed(),
        trigger = trigger,
        items = items,
        selectedIndex = selectedIndex,
        motion = menuMotion,
        anchorShape = buttonShape,
        anchorPadding = buttonPadding,
        header = header,
        content = content,
        contentPadding = contentPadding,
        group = group,
        anchorContent = buttonContent,
    )
}

/** 圆形图标按钮版（⋯、+ 这类），默认包裹模式、右对齐 */
@Composable
public fun MochiIconDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: ImmutableList<MochiItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectedIndex: Int = -1,
    colors: MochiDropdownColors = MochiDropdownDefaults.iconDropdownColors(),
    behavior: MochiDropdownBehavior = MochiDropdownDefaults.iconBehavior(),
    physics: MochiDropdownPhysics = MochiDropdownDefaults.physics(),
    motion: MochiMotion = MochiTheme.motionScheme.menu,
    menuWidth: Dp = MochiDropdownDefaults.IconMenuWidth,
    size: Dp = MochiDropdownDefaults.IconButtonSize,
    onClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    MochiDropdown(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        items = items,
        onItemClick = onItemClick,
        modifier = modifier,
        enabled = enabled,
        selectedIndex = selectedIndex,
        colors = colors,
        behavior = behavior,
        physics = physics,
        motion = motion,
        menuWidth = menuWidth,
        buttonPadding = PaddingValues(0.dp),
        buttonMinWidth = size,
        buttonMinHeight = size,
        onClick = onClick,
    ) { icon() }
}

/** 半透明的按钮色合成到 [under] 上变成不透明色；不透明和完全透明的原样返回 */
internal fun opaqueButtonColor(color: Color, under: Color): Color =
    if (color.alpha > 0f && color.alpha < 1f) color.compositeOver(under.copy(alpha = 1f)) else color

/** 没改过的默认菜单宽度：液态菜单换成自己的宽度 */
private val DefaultMenuWidths = setOf(MochiDropdownDefaults.MenuWidth, MochiDropdownDefaults.IconMenuWidth)
