// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic.menu

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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotion
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.basic.BarGroupHandle
import ui.feice.mochi.basic.LocalBarControlGlass
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.MochiItemLayout
import ui.feice.mochi.basic.MochiOpenMode
import ui.feice.mochi.basic.MochiStyle
import ui.feice.mochi.basic.MochiTrigger
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.liquid
import ui.feice.mochi.basic.popoverPlacement
import ui.feice.mochi.effect.elastic.MochiRubberBandState
import ui.feice.mochi.effect.elastic.jellyCrossScale
import ui.feice.mochi.effect.elastic.jellyStretch
import ui.feice.mochi.effect.elastic.rememberMochiRubberBandState
import ui.feice.mochi.effect.elastic.rubberStretched
import ui.feice.mochi.effect.glass.GlassFill
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.GlassSurface
import ui.feice.mochi.effect.glass.engine.GlassMerge
import ui.feice.mochi.effect.glass.rememberGlassFill
import ui.feice.mochi.effect.glow.MochiGlowDefaults
import ui.feice.mochi.effect.glow.MochiGlowSpec
import ui.feice.mochi.effect.glow.MochiGlowState
import ui.feice.mochi.effect.glow.drawGlow
import ui.feice.mochi.effect.glow.mochiGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.effect.mask.MochiFadeEdge
import ui.feice.mochi.effect.mask.MochiFadeEdges
import ui.feice.mochi.effect.mask.mochiFadeMask
import ui.feice.mochi.effect.metaball.MochiBlob
import ui.feice.mochi.effect.metaball.MochiMetaballColors
import ui.feice.mochi.effect.metaball.MochiMetaballFrame
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
import ui.feice.mochi.theme.lerp
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.MochiHaptics
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.smoothstep
import ui.feice.mochi.utils.windowOriginOnScreen

// ───────────────────────── 浮层 ─────────────────────────

@Composable
internal fun MochiOverlay(
    anchorOnScreen: Rect,
    progress: Animatable<Float, *>,
    effects: () -> Float,
    press: () -> Float,
    glow: MochiGlowState,
    controller: MochiController,
    callbacks: MochiCallbacks,
    trigger: MochiTrigger,
    items: List<MochiItem>,
    selectedIndex: Int,
    style: MochiStyle,
    motion: MochiMotion,
    elastic: Float,
    buttonShape: Shape,
    buttonPadding: PaddingValues,
    scrim: Color,
    matchAnchorWidth: Boolean,
    onReady: () -> Unit,
    header: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    group: BarGroupHandle? = null,
    buttonContent: @Composable RowScope.() -> Unit,
) {
    val view = LocalView.current
    val headerMeasurer = rememberTextMeasurer()
    val headerStyle = MochiTheme.textStyles.footnote.copy(textAlign = TextAlign.Center)
    var contentHeight by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val px = remember(style, density) { MochiPx(style, density) }
    val renderer = rememberMochiMetaballRenderer()
    var origin by remember { mutableStateOf<Offset?>(null) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    val cover = style.openMode == MochiOpenMode.Cover
    val ios = style.itemLayout == MochiItemLayout.Ios
    val hlPathCache = remember { G2PathCache() }
    val metaballColors = remember(style) { MochiMetaballColors(style.buttonColor, style.panelColor, style.shadowColor) }
    val scope = rememberCoroutineScope()
    val scroller = remember(controller, density) { DropdownScroller(controller, scope, density) }
    DisposableEffect(scroller) {
        onDispose { scroller.stop() }
    }
    // 滚动条：滚动时出现，停下一会儿淡出（打开时闪一下，提示能滚）
    val barAlpha = remember { Animatable(0f) }
    LaunchedEffect(controller) {
        snapshotFlow { controller.scroll }.collectLatest {
            if (controller.maxScroll <= 0f) return@collectLatest
            barAlpha.snapTo(1f)
            delay(700)
            barAlpha.animateTo(0f, MochiMotions.responseSpring(0.3f, 1f))
        }
    }
    // 从按钮按住滑选时，手指贴近面板上下沿就自动滚（越深越快）；手指要先进过面板，刚从按钮滑出来不算
    LaunchedEffect(controller, px, scroller) {
        snapshotFlow { controller.autoScroll && controller.pointer != null }.collectLatest { on ->
            if (!on) return@collectLatest
            var entered = false
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                val p = controller.pointer ?: continue
                if (controller.hover >= 0) entered = true
                if (!entered || controller.maxScroll <= 0f) continue
                val r = controller.panelOnScreen
                val zone = px.itemHeight
                val top = r.top + px.panelPadding + zone
                val bottom = r.bottom - px.panelPadding - zone
                val depth = when {
                    p.y < top -> -((top - p.y) / zone).coerceAtMost(2.5f)
                    p.y > bottom -> ((p.y - bottom) / zone).coerceAtMost(2.5f)
                    else -> 0f
                }
                if (depth == 0f || p.x < r.left - zone || p.x > r.right + zone) continue
                scroller.scrollByClamped(depth * abs(depth) * zone * AutoScrollSpeed * dt)
                controller.track(p)
            }
        }
    }

    // 橡皮筋：手指在面板外时，面板被拉过去（往按钮方向拉得少一点）
    val rubber = rememberMochiRubberBandState(
        pointer = { controller.pointer },
        bounds = { controller.panelOnScreen },
        maxPull = style.rubberMax,
        adjust = { towardButtonResist(it, controller.upward) },
    )

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInWindow() + view.windowOriginOnScreen()
                containerSize = it.size.toSize()
            }
            // 长按预览：背景跟着展开进度压暗
            .drawBehind { if (scrim.alpha > 0f) drawRect(scrim, alpha = progress.value.coerceIn(0f, 1f)) }
            .pointerInput(Unit) { detectTapGestures { callbacks.close() } },
    ) {
        val o = origin ?: return@Box
        if (containerSize == Size.Zero) return@Box
        val button = anchorOnScreen.translate(-o)
        val safeTop = WindowInsets.statusBars.getTop(density).toFloat()
        val safeBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        // 主题有菜单玻璃（menuGlass）且浮层底下有页面录制时，面板画成玻璃
        val glass = rememberGlassFill(GlassRole.Menu)
        // iOS 的玻璃菜单（style.liquid）：从按钮开始就是玻璃，跟着当前面板实时变形，开合途中玻璃里有透镜
        val liquidGlass = glass != null && style.liquid
        // 菜单从玻璃按钮组里弹出来：整个组直接变形成菜单（组藏起来，面板从组的胶囊长出来，收起时缩回组的胶囊），
        // 组里的图标（连同弹菜单的那一格）画在面板上，开头淡出、收起最后淡回。不画成两块：单独从那一格长出来时组还留在原地，像分开的两块玻璃
        // 纯色的组（group.color）也一样：融球从组的胶囊长出面板，按钮那一端用组的底色
        val mergeGroup = if (liquidGlass || group?.color != null) group else null
        // 按钮本身已经是玻璃（按钮组、栏里的玻璃下拉按钮）：浮层从头到尾都是玻璃，开头、结尾不先画成按钮的纯色
        val glassButton = liquidGlass && ((group != null && group.color == null) || LocalBarControlGlass.current != null)
        val anchor = mergeGroup?.rect?.translate(-o) ?: button
        val start = anchor
        val panelWidth = if (matchAnchorWidth) max(px.menuWidth, anchor.width) else px.menuWidth
        val headerPx = if (header == null || content != null) {
            0f
        } else {
            val hp = with(density) { HeaderPadding.toPx() }
            val layout = headerMeasurer.measure(
                header,
                headerStyle,
                constraints = Constraints(maxWidth = (panelWidth - px.panelPadding * 2f - hp * 2f).roundToInt().coerceAtLeast(1)),
            )
            layout.size.height + with(density) { (HeaderTop + HeaderBottom).toPx() }
        }
        val (target, upward) = if (content != null) {
            popoverPlacement(
                button,
                containerSize,
                panelWidth,
                contentHeight,
                gap = if (cover) 0f else px.gap,
                margin = px.margin,
                safeTop = safeTop,
                safeBottom = safeBottom,
                cover = cover,
                align = style.align,
            ).let { it.rect to it.upward }
        } else {
            computeTarget(anchor, containerSize, items.size, style.openMode, px, matchAnchorWidth, safeTop, safeBottom, headerPx)
        }
        val viewport = target.height - px.panelPadding * 2f - headerPx
        SideEffect {
            controller.panelOnScreen = target.translate(o)
            controller.headerPx = headerPx
            controller.upward = upward
            controller.maxScroll = max(0f, items.size * px.itemHeight - viewport)
            scroller.viewport = viewport
        }
        // 打开时把选中项滚到视口中间
        LaunchedEffect(scroller) {
            scroller.snapTo(if (selectedIndex >= 0) selectedIndex * px.itemHeight - (viewport - px.itemHeight) / 2f else 0f)
        }
        val currentOnReady by rememberUpdatedState(onReady)
        LaunchedEffect(Unit) { currentOnReady() }

        // iOS 玻璃菜单开合时的透镜和 kick（见 MenuLens；只在 liquid 玻璃菜单里跑，其余时候保持 0）
        val lens = remember { MenuLens() }
        // 一帧的几何只算一次：shader、克隆按钮、裁剪、内容四处都要读，用 derivedStateOf 缓存
        val frameState = remember(start, anchor, target, upward, px, cover, elastic) {
            derivedStateOf {
                val f = computeFrame(
                    p = progress.value, velocity = progress.velocity,
                    button = start, target = target, upward = upward, px = px,
                    pressScale = if (mergeGroup !=
                        null
                    ) {
                        1f
                    } else {
                        press()
                    },
                    pull = rubber.pull, cover = cover, elastic = elastic, liquid = style.liquid,
                )
                // 按钮组菜单收起时不踢（iOS 录屏里收起没有往外踢的那一下）
                val dy = lens.kickOffset(density, anchor, target)
                if (dy == 0f) f else f.panelShifted(0f, dy, cover)
            }
        }
        val frame = { frameState.value }
        val shadows = MochiTheme.shadows

        if (liquidGlass) {
            LaunchedEffect(progress, lens) {
                snapshotFlow { progress.targetValue >= 1f }.dropWhile { !it }.collectLatest { open -> lens.run(open) }
            }
        }
        // 菜单从玻璃按钮组里脱出来：浮层接手画整个组，组和面板画成一块玻璃（光滑并集，见 MenuLens），
        // 组里的内容（连同弹菜单的那一格）画在玻璃上面；组自己藏起来。组的位置跟着来源的 kick 走。
        if (mergeGroup != null) {
            DisposableEffect(mergeGroup) {
                mergeGroup.hidden = true
                onDispose { mergeGroup.hidden = false }
            }
        }

        // 1) shader 层：按钮 + 面板 + 融球 + 阴影 + 发光
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val f = frame()
                    val cl = progress.value.coerceIn(0f, 1f)
                    // 两层阴影，面板越大越深越软（官方说法：大尺寸的玻璃投更深更柔的影子）；
                    // 跟着按压 / 展开平滑出现，不会一按就「啪」地冒出来
                    val vis = (progress.value * 3f + abs(press() - 1f) * 10f).coerceIn(0f, 1f)
                    val dark = style.panelColor.luminance() < 0.5f
                    val shadow = lerp(shadows.rest, shadows.floating, cl).resolve(this, dark, vis)
                    // 先画阴影 + 融球颈，再用 Kyant 连续曲率 Path 画本体（形状抗锯齿），
                    // 本体颜色也由 shader 算：按钮色 → 面板色的渐变融色和按压发光都在里面
                    // 玻璃菜单：面板的纯色跟着玻璃淡入淡掉（只留阴影），收起最后一段按钮的颜色回来
                    val base = mergeGroup?.color?.let { metaballColors.copy(a = it) } ?: metaballColors
                    val colors = if (liquidGlass) {
                        val b = base.b
                        val glassIn = if (glassButton) 1f else glassAlpha(progress.value)
                        base.copy(b = b.copy(alpha = b.alpha * (1f - glassIn)))
                    } else {
                        base
                    }
                    // 从按钮组里脱出来时按压的光交给组的玻璃，这里不画
                    drawMochiMetaball(
                        renderer,
                        f.metaball,
                        colors,
                        shadow,
                        style.g2Smoothing,
                        if (group !=
                            null
                        ) {
                            null
                        } else {
                            glow
                        },
                        glowOrigin = o,
                    )
                },
        )

        if (liquidGlass) {
            LiquidPanelGlass(glass, frame, style.panelCornerRadius, null, { 0f }) {
                if (glassButton) 1f else glassAlpha(progress.value)
            }
        }
        if (mergeGroup != null) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - smoothstep(0f, 0.25f, progress.value) }
                    .drawBehind {
                        // 跟着面板的左上角走（展开开头面板还和组差不多大）
                        val p = frame().panel
                        translate(p.left, p.top) { drawLayer(mergeGroup.content) }
                    },
            )
        }

        // 2) 按钮克隆：和真按钮同位置同手势，包裹模式下内容淡出
        with(density) {
            Row(
                Modifier
                    .offset { button.topLeft.round() }
                    .size(button.width.toDp(), button.height.toDp())
                    .mochiButtonGesture(trigger, controller, { button.topLeft + o }, callbacks)
                    .graphicsLayer {
                        val f = frame()
                        scaleX = f.scale
                        scaleY = f.scale
                        // 从按钮组里脱出来时这一格的图标跟着组画（见上），克隆只留手势
                        alpha = if (mergeGroup != null) {
                            0f
                        } else if (cover) {
                            1f - smoothstep(0f, 0.25f, progress.value)
                        } else {
                            1f
                        }
                    }
                    .padding(buttonPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalContentColor provides style.onButtonColor) { buttonContent() }
            }
        }

        // 3) 菜单内容，裁剪到当前面板形状；整块面板接手指（按住滑动选择）
        val itemH = px.itemHeight
        val pivot = TransformOrigin(
            pivotFractionX = if (style.align == MochiAlign.End) {
                1f
            } else if (style.align == MochiAlign.Center) {
                0.5f
            } else {
                0f
            },
            pivotFractionY = if (upward) 1f else 0f,
        )

        // 弹出层（内容模式）：面板里可能有自己处理拖动的控件
        val hasContent = content != null

        // 裁剪画布覆盖整个浮层，不能把动态轮廓塞进最终 target 大小的 layer：
        // 开合、过冲和橡皮筋会越过 target，固定 layer 边界会把末行文字横向切掉。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val f = frame()
                    clip = true
                    shape = G2RectClipShape(f.panel, f.panelRadius, style.g2Smoothing)
                },
        ) {
            // iOS 玻璃菜单：开合途中内容经过透镜（放大、模糊、贴边位移，见 MenuLens）
            Box(if (liquidGlass) Modifier.fillMaxSize().menuLens(lens, frame, target, density) else Modifier.fillMaxSize()) {
                // 其余带玻璃的面板（弹出层）：玻璃盖在融球画的纯色面板上面，随展开淡入
                if (glass != null && !liquidGlass) {
                    with(density) {
                        Box(Modifier.offset { target.topLeft.round() }.size(target.width.toDp(), target.height.toDp())) {
                            PanelGlass(glass, target, frame, style.panelCornerRadius) { smoothstep(0f, 0.6f, progress.value) }
                        }
                    }
                }
                with(density) {
                    Box(
                        Modifier
                            .offset { target.topLeft.round() }
                            .size(target.width.toDp(), target.height.toDp())
                            .pointerInput(controller, target, scroller, hasContent) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val origin2 = target.topLeft + o
                                    // 放不下时竖着拖 = 滚动（越过触摸阈值后），否则按住滑动选择；
                                    // 列表还在惯性滑动时这一按只是把它接住，不点选项
                                    val canScroll = controller.maxScroll > 0f
                                    val caught = scroller.stop()
                                    if (!caught) {
                                        callbacks.panelPressed(true)
                                        controller.track(origin2 + down.position)
                                    }
                                    down.consume()
                                    val tracker = VelocityTracker()
                                    tracker.addPosition(down.uptimeMillis, down.position)
                                    val slop = viewConfiguration.touchSlop
                                    var scrolling = false
                                    // 内容里自己处理拖动的控件（滚筒、滑块、横向列表）接手了这次拖动：面板放手，不发光、不滚、不点选
                                    var contentOwns = false
                                    while (true) {
                                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!scrolling && !contentOwns && c.isConsumed) {
                                            contentOwns = true
                                            if (!caught) callbacks.panelPressed(false)
                                            controller.pointer = null
                                            controller.hover = -1
                                        }
                                        if (contentOwns) {
                                            if (!c.pressed) break
                                            continue
                                        }
                                        tracker.addPosition(c.uptimeMillis, c.position)
                                        if (canScroll && !scrolling && abs(c.position.y - down.position.y) > slop) {
                                            scrolling = true
                                            if (!caught) callbacks.panelPressed(false)
                                            controller.pointer = null
                                            controller.hover = -1
                                        }
                                        if (scrolling) {
                                            scroller.dragBy(c.position.y - c.previousPosition.y)
                                        } else if (!caught) {
                                            controller.track(origin2 + c.position)
                                        }
                                        // 内容模式下没在滚就不消费移动：滚筒这类控件要等越过触摸阈值才接手，
                                        // 期间的移动被面板消费掉，它会以为「别人接手了」而放弃，结果划不动
                                        if (!hasContent || scrolling) c.consume()
                                        if (!c.pressed) break
                                    }
                                    if (contentOwns) {
                                        if (caught) scroller.settle()
                                        return@awaitEachGesture
                                    }
                                    if (scrolling) {
                                        scroller.fling(-tracker.calculateVelocity().y)
                                        return@awaitEachGesture
                                    }
                                    if (caught) {
                                        scroller.settle()
                                        return@awaitEachGesture
                                    }
                                    callbacks.panelPressed(false)
                                    val h = controller.hover
                                    controller.pointer = null
                                    if (h >= 0) callbacks.select(h) else controller.hover = -1
                                }
                            },
                    ) {
                        if (content != null) {
                            PanelContent(content, contentPadding, { contentHeight = it }, frame, target, effects, pivot)
                            return@Box
                        }
                        if (header != null) {
                            Text(
                                header,
                                style = headerStyle,
                                color = style.onPanelColor.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = px.panelPadding.toDp() + HeaderPadding, end = px.panelPadding.toDp() + HeaderPadding)
                                    .padding(top = px.panelPadding.toDp() + HeaderTop)
                                    .graphicsLayer {
                                        val f = frame()
                                        val ev = effects().coerceIn(0f, 1f)
                                        alpha = ev
                                        translationX = f.panel.center.x - target.center.x
                                        translationY = f.panel.center.y - target.center.y
                                    },
                            )
                        }
                        MochiList(
                            items = items,
                            selectedIndex = selectedIndex,
                            style = style,
                            px = px,
                            motion = motion,
                            upward = upward,
                            hover = { controller.hover },
                            pointer = { controller.pointer },
                            contentOrigin = {
                                val f = frame()
                                o + target.topLeft + Offset(px.panelPadding, px.panelPadding + headerPx) + (f.panel.center - target.center)
                            },
                            effects = effects,
                            onSelect = { callbacks.select(it) },
                            scroll = { controller.scroll },
                            window = ((target.height + px.rubberMax) / itemH).toInt() + 2,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(style.panelPadding)
                                .padding(top = headerPx.toDp())
                                // 显影、跟随面板的平移放在遮罩外层：遮罩开离屏图层时按自己的矩形裁切，
                                // 平移放在里面的话，内容一跟着果冻 / 橡皮筋移出矩形就被切出一条直边
                                // 内容「显影」：透明度 + 轻微放大（原点在按钮那一角），并跟着面板的拉伸走。
                                // 不做模糊：RenderEffect 模糊每帧重采样，边缘有锯齿描边，还很吃 GPU
                                .graphicsLayer {
                                    val f = frame()
                                    val ev = effects().coerceIn(0f, 1f)
                                    alpha = ev
                                    // iOS 玻璃菜单不另外缩放（放大交给透镜）
                                    val s = if (liquidGlass) 1f else 0.94f + 0.06f * ev
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = pivot
                                    translationX = f.panel.center.x - target.center.x
                                    translationY = f.panel.center.y - target.center.y
                                }
                                // 能滚的菜单：还能往哪边滚，那一边渐隐（通用渐变遮罩，深浅跟着离边的距离连续变）
                                .mochiFadeMask(MochiFadeEdges.vertical(MenuFadeExtent)) { edge ->
                                    val max = controller.maxScroll
                                    val e = with(density) { MenuFadeExtent.toPx() }
                                    when {
                                        max <= 0f -> 0f
                                        edge == MochiFadeEdge.Top -> (controller.scroll / e).coerceIn(0f, 1f)
                                        edge == MochiFadeEdge.Bottom -> ((max - controller.scroll) / e).coerceIn(0f, 1f)
                                        else -> 0f
                                    }
                                },
                        ) {
                            // 滚动条：贴在右侧内边距里的细胶囊，越界时被压短（和 iOS 一样）
                            if (items.size * itemH > viewport) {
                                Spacer(
                                    Modifier
                                        .fillMaxSize()
                                        .drawBehind {
                                            val a = barAlpha.value
                                            if (a <= 0.01f) return@drawBehind
                                            val content = items.size * itemH
                                            val max = content - size.height
                                            val sc = controller.scroll
                                            val over = if (sc < 0f) {
                                                -sc
                                            } else if (sc > max) {
                                                sc - max
                                            } else {
                                                0f
                                            }
                                            val w = 3.dp.toPx()
                                            val h = (size.height * size.height / content - over).coerceAtLeast(w * 3f)
                                            val y = (sc.coerceIn(0f, max) / max) * (size.height - h)
                                            val x = size.width + px.panelPadding / 2f - w / 2f
                                            drawRoundRect(
                                                style.onPanelColor.copy(alpha = 0.35f * a),
                                                topLeft = Offset(x, y),
                                                size = Size(w, h),
                                                cornerRadius = CornerRadius(w / 2f),
                                            )
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 弹出层（内容模式）的面板内容：按自然高度测量（放不下时可以滚），显影和菜单项一样跟着面板走。
 * 点按交给内容自己；没被内容接住的按压由外层面板手势接住（面板发光、不算点外面）。
 */
@Composable
private fun PanelContent(
    body: @Composable ColumnScope.() -> Unit,
    contentPadding: PaddingValues,
    onHeight: (Float) -> Unit,
    frame: () -> DropdownFrame,
    target: Rect,
    effects: () -> Float,
    pivot: TransformOrigin,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .onSizeChanged { onHeight(it.height.toFloat()) }
            .padding(contentPadding)
            .graphicsLayer {
                val f = frame()
                val ev = effects().coerceIn(0f, 1f)
                alpha = ev
                val s = 0.94f + 0.06f * ev
                scaleX = s
                scaleY = s
                transformOrigin = pivot
                translationX = f.panel.center.x - target.center.x
                translationY = f.panel.center.y - target.center.y
            },
        content = body,
    )
}

/** 标题（操作确认的说明）左右离面板内边的距离 */
private val HeaderPadding = 12.dp
private val HeaderTop = 8.dp
private val HeaderBottom = 6.dp

/** 菜单上下边渐隐的长度 */
private val MenuFadeExtent = 20.dp

/** 自动滚动速度：手指贴边一个选项高时每秒滚过几个选项 */
private const val AutoScrollSpeed = 6f

/** 玻璃的透明度：按钮的纯色在展开头 1/4 换成玻璃（Mochi 的衔接；iOS 的按钮本来就是玻璃） */
private fun glassAlpha(p: Float): Float = (4f * p).coerceIn(0f, 1f)

/** 内容图层套上 [MenuLens]：按当前面板中心放大，位移和模糊的范围换算回放大前的坐标 */
private fun Modifier.menuLens(lens: MenuLens, frame: () -> DropdownFrame, target: Rect, density: Density): Modifier = graphicsLayer {
    val f = frame()
    val s = lens.scale(density, target.height)
    val c = f.panel.center
    scaleX = s
    scaleY = s
    transformOrigin = TransformOrigin(c.x / size.width, c.y / size.height)
    val panel = Rect(
        c.x + (f.panel.left - c.x) / s,
        c.y + (f.panel.top - c.y) / s,
        c.x + (f.panel.right - c.x) / s,
        c.y + (f.panel.bottom - c.y) / s,
    )
    renderEffect = lens.effect(density, panel, f.panelRadius, s)
}

/**
 * iOS 玻璃菜单的面板玻璃：按当前面板（[frame]）的位置、大小、圆角排版（layout 阶段读，不重组），
 * 从按钮的胶囊连续变成面板，不缩放。
 * 给了 [group]（按钮组当前的位置，null = 这会儿没有组）时，玻璃按面板和组的外包框排版，画成两块的光滑并集（半径 [smooth]，px），组按胶囊算
 */
@Composable
private fun LiquidPanelGlass(
    glass: GlassFill,
    frame: () -> DropdownFrame,
    corner: Dp,
    group: (() -> Rect?)?,
    smooth: () -> Float,
    alpha: () -> Float,
) {
    // 排版的矩形：没有组时是面板；有组时是外包框，按整像素往外取（并集的坐标从它的左上角量），再留出光滑并集往外鼓的量（最多半径的 1/4）
    fun bounds(): Rect {
        val p = frame().panel
        val g = group?.invoke() ?: return p
        val m = smooth() / 4f + 2f
        return Rect(
            floor(min(p.left, g.left) - m),
            floor(min(p.top, g.top) - m),
            ceil(max(p.right, g.right) + m),
            ceil(max(p.bottom, g.bottom) + m),
        )
    }
    GlassSurface(
        glass,
        corner,
        null,
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val r = bounds()
                val placeable = measurable.measure(
                    Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
            },
        alpha = alpha,
        cornerRadiusPx = { frame().panelRadius },
        merge = group?.let { g ->
            {
                val gr = g()
                if (gr == null) {
                    null
                } else {
                    val o = bounds().topLeft
                    GlassMerge(frame().panel.translate(-o), gr.translate(-o), gr.height / 2f, smooth())
                }
            }
        },
    )
}

/**
 * 菜单面板的玻璃：按展开后的面板（[target]）排版一次，开合、过冲、橡皮筋时用平移和绕中心缩放跟着当前面板（[frame]）走，
 * 背景不跟着缩放。圆角取展开后的 [corner]
 */
@Composable
internal fun BoxScope.PanelGlass(glass: GlassFill, target: Rect, frame: () -> DropdownFrame, corner: Dp, alpha: () -> Float) {
    GlassSurface(
        glass,
        corner,
        null,
        Modifier.matchParentSize().graphicsLayer {
            val f = frame()
            translationX = f.panel.center.x - target.center.x
            translationY = f.panel.center.y - target.center.y
        },
        scaleX = { frame().panel.width / target.width.coerceAtLeast(1f) },
        scaleY = { frame().panel.height / target.height.coerceAtLeast(1f) },
        alpha = alpha,
    )
}
