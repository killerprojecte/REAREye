// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiIconButton
import ui.feice.mochi.basic.MochiSearchBar
import ui.feice.mochi.basic.MochiSearchBarDefaults
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.shadow.drawMochiShadow
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.shape.MochiContinuity
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/** 侧边栏打开时的样子 */
public enum class MochiSidebarStyle {
    /** iPhone 上 AI 应用的抽屉：侧边栏在底下，主页面整体往右推开、变成圆角卡片并压暗 */
    Push,

    /** iPadOS 26 的浮动侧边栏：离屏幕边 8dp 的同心圆角面板从左边滑进来盖在主页面上，主页面不动只压暗 */
    Floating,
}

/**
 * 侧边栏的开合状态。[open] / [close] / [toggle] 可以在任何地方调用（点击回调里也行），动画由 [MochiSidebarLayout] 跑。
 */
@Stable
public class MochiSidebarState internal constructor(initiallyOpen: Boolean) {
    internal val anim = Animatable(if (initiallyOpen) 1f else 0f)
    internal var spec: AnimationSpec<Float> = MochiMotions.responseSpring(0.42f, 1f)
    internal var scope: CoroutineScope? = null

    /** 目标状态：打开中、已打开为 true；拖动途中按松手前的目标算 */
    public var isOpen: Boolean by mutableStateOf(initiallyOpen)
        private set

    /** 此刻的打开程度，0 = 收起，1 = 完全打开（读它会在每帧变化时触发重组，绘制里读更好） */
    public val progress: Float get() = anim.value

    public fun open(): Unit = settle(true)

    public fun close(): Unit = settle(false)

    public fun toggle(): Unit = settle(!isOpen)

    /** 挂起直到动画停稳 */
    public suspend fun animateTo(open: Boolean) {
        isOpen = open
        anim.animateTo(if (open) 1f else 0f, spec)
    }

    /** @param velocity 打开进度每秒的变化量，接着手势的速度走 */
    internal fun settle(open: Boolean, velocity: Float = 0f) {
        isOpen = open
        val s = scope ?: return
        s.launch { anim.animateTo(if (open) 1f else 0f, spec, initialVelocity = velocity) }
    }

    internal companion object {
        val Saver: Saver<MochiSidebarState, Boolean> = Saver({ it.isOpen }, { MochiSidebarState(it) })
    }
}

@Composable
public fun rememberMochiSidebarState(initiallyOpen: Boolean = false): MochiSidebarState =
    rememberSaveable(saver = MochiSidebarState.Saver) { MochiSidebarState(initiallyOpen) }

@Immutable
public data class MochiSidebarColors(
    /** 推开样式里侧边栏（底层）的底色：默认用卡片色，和通常是分组底色的主页面拉开一层（深色下也分得清） */
    val background: Color,
    /** 浮动样式里面板的底色 */
    val panel: Color,
    /** 浮动样式打开时盖在主页面上的暗色（alpha 是完全打开时的） */
    val scrim: Color,
    /** 推开样式里主页面的压暗：很浅，推开的主页面仍然看得清，像一张浮起的卡片 */
    val pushScrim: Color,
    /** 选中行的底色 */
    val selected: Color,
    /** 按下时叠加的高亮 */
    val pressed: Color,
    val content: Color,
    val secondaryContent: Color,
)

public object MochiSidebarDefaults {
    /** 侧边栏最宽多少；实际宽度 = min(屏宽 × [WidthFraction], 它) */
    public val MaxWidth: Dp = 360.dp

    /** 侧边栏占屏宽的比例：主页面推开后右边还露出一条，点它关闭 */
    public const val WidthFraction: Float = 0.82f

    /** 浮动面板离屏幕上、下、左三边的距离 */
    public val FloatingInset: Dp = 8.dp

    /** 一行的最小高度 */
    public val ItemHeight: Dp = 44.dp

    /** 行的高亮离侧边栏左右边的距离 */
    public val ItemInset: Dp = 10.dp

    /** 行内文字离高亮边的距离 */
    public val ItemPadding: Dp = 14.dp

    /**
     * 整页右划是否打开侧边栏的默认值：所在页是 [MochiNavHost] 的子页（下面还有页）时为 false，右划留给返回；
     * 根页或不在导航里时为 true
     */
    @Composable
    public fun swipeToOpen(): Boolean {
        val entry = LocalMochiNavEntry.current
        val nav = LocalMochiNavigator.current
        return entry == null || nav == null || nav.under(entry) == null
    }

    @Composable
    public fun colors(
        background: Color = MochiDefaults.groupedContainer(),
        panel: Color = MochiDefaults.groupedContainer(),
        scrim: Color = Color.Black.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.4f else 0.18f),
        pushScrim: Color = Color.Black.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.24f else 0.05f),
        selected: Color = MochiTheme.colorScheme.onSurface.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.14f else 0.08f),
        pressed: Color = MochiTheme.colorScheme.onSurface.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.1f else 0.06f),
        content: Color = MochiTheme.colorScheme.onSurface,
        secondaryContent: Color = MochiTheme.colorScheme.onSurfaceVariant,
    ): MochiSidebarColors = MochiSidebarColors(background, panel, scrim, pushScrim, selected, pressed, content, secondaryContent)
}

/** 侧边栏宽度（px） */
internal fun sidebarWidth(container: Float, max: Float, fraction: Float = MochiSidebarDefaults.WidthFraction): Float =
    min(container * fraction, max).coerceAtLeast(0f)

/**
 * 松手后打开还是收起：甩得够快按方向，否则看有没有过半。
 * @param velocity 打开进度每秒的变化量；[fling] 同单位的阈值
 */
internal fun sidebarSettle(progress: Float, velocity: Float, fling: Float): Boolean = when {
    velocity > fling -> true
    velocity < -fling -> false
    else -> progress > 0.5f
}

/**
 * 侧边栏容器（AI 应用常见的那种，外观按 iOS 26）：
 * - [MochiSidebarStyle.Push]：侧边栏盖在主页面上面，和主页面连在一起往右推：侧边栏从左边滑进来，主页面跟着往右移并压暗，
 *   不加阴影、不变圆角卡片（ChatGPT / Claude 的做法）
 * - [MochiSidebarStyle.Floating]：浮动面板在状态栏下方，离状态栏、屏幕底边和左边 8dp；顶角和搜索框同心（28dp），底角和屏幕底角同心；
 *   带柔和阴影，从左边滑进来，主页面不动只压暗。打开时默认藏起手势小白条（[hideNavigationBar]），
 *   底部的行和面板底角同心
 *
 * 手势：
 * - [swipeToOpen] 时，在主页面任意位置往右划打开，1:1 跟手；松手过半或甩得够快就打开，否则收回
 * - 只接子元素没消费的拖动：横向滚动、滑块、左滑行照常；包着内部 [MochiNavHost] 时，它的子页先接到右划（返回），
 *   退到根页右划才打开侧边栏
 * - 打开时：点主页面关闭（主页面不接别的触摸）；任意位置往左拖跟手收起；系统返回键跟手收起
 *
 * 只改 graphicsLayer，主页面始终按整屏排版。侧边栏内容自带状态栏 / 导航栏安全区。
 * @param sidebar 侧边栏内容（[MochiSidebarHeader]、放 [MochiSidebarItem] 的 LazyColumn、[MochiSidebarProfileRow]……）
 * @param swipeToOpen 整页右划是否打开；默认 [MochiSidebarDefaults.swipeToOpen]
 * @param hideNavigationBar 浮动样式打开时藏起系统导航栏（手势小白条），关闭后恢复；藏起来时从底边上滑还能临时叫出来
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun MochiSidebarLayout(
    state: MochiSidebarState,
    sidebar: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    style: MochiSidebarStyle = MochiSidebarStyle.Push,
    swipeToOpen: Boolean = MochiSidebarDefaults.swipeToOpen(),
    maxWidth: Dp = MochiSidebarDefaults.MaxWidth,
    colors: MochiSidebarColors = MochiSidebarDefaults.colors(),
    hideNavigationBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val spec = MochiNavDefaults.spec().push
    SideEffect {
        state.scope = scope
        state.spec = spec
    }
    val density = LocalDensity.current
    val screen = rememberMochiScreenCorners()
    val fallback = with(density) { SlideFallbackCorner.toPx() }
    val tl = if (screen.topLeft > 0.5f) screen.topLeft else fallback
    val bl = if (screen.bottomLeft > 0.5f) screen.bottomLeft else fallback
    val showing by remember(state) { derivedStateOf { state.isOpen || state.anim.value > 0.0005f } }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelPx = sidebarWidth(constraints.maxWidth.toFloat(), with(density) { maxWidth.toPx() })
        val panelDp = with(density) { panelPx.toDp() }
        val insetPx = with(density) { MochiSidebarDefaults.FloatingInset.toPx() }
        val floating = style == MochiSidebarStyle.Floating
        // 打开进度 1 对应手指走的距离：推开样式是主页面平移的宽度，浮动样式是面板右边缘走的距离
        val travel = if (floating) panelPx + insetPx else panelPx
        val flingPx = with(density) { SidebarFlingVelocity.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state, floating, swipeToOpen, travel) {
                    sidebarGestures(state, swipeToOpen, travel, flingPx / travel.coerceAtLeast(1f), scope) {
                        val p = state.anim.value.coerceIn(0f, 1f)
                        if (floating) (insetPx + panelPx) * p else panelPx * p
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { if (!floating) translationX = panelPx * state.anim.value.coerceIn(0f, 1f) }
                    .drawWithContent {
                        drawContent()
                        val p = state.anim.value.coerceIn(0f, 1f)
                        if (p > 0f) drawRect(if (floating) colors.scrim else colors.pushScrim, alpha = p)
                    },
            ) { content() }
            if (!floating) {
                // 侧边栏在主页面上面，右边缘贴着主页面的左边缘一起走，主页面只压暗
                Column(
                    Modifier
                        .width(panelDp)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val p = state.anim.value.coerceIn(0f, 1f)
                            alpha = if (p > 0.0005f) 1f else 0f
                            translationX = -(1f - p) * panelPx
                        }
                        .background(colors.background)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    content = sidebar,
                )
            } else {
                // 顶角 = 搜索框圆头半径 + 10dp：和离边 10dp 的搜索框同心（顶边不贴屏幕，不跟屏幕圆角）；
                // 底角和屏幕底角同心（屏幕圆角 − 8dp，不设上限，大圆角机型照样对齐）
                val top = FloatingTopCorner
                val radius = with(density) { (bl - insetPx).toDp() }.coerceAtLeast(top)
                val shape = remember(radius) { ContinuousRoundedRectangle(top, top, radius, radius, MochiContinuity) }
                val dark = MochiTheme.colorScheme.isDark
                val shadow = MochiTheme.shadows.floating
                val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
                val navBottom =
                    with(density) { (WindowInsets.navigationBarsIgnoringVisibility.getBottom(this) - insetPx).coerceAtLeast(0f).toDp() }
                // 同心：头部搜索框（上下各 8dp 留白）离面板顶边也是 10dp；底部的行（胶囊高亮）和面板底角同心
                val topPad = (top - MochiSearchBarDefaults.Height / 2 - SidebarHeaderVerticalPadding).coerceAtLeast(0.dp)
                val concentricBottom = (radius - MochiSidebarDefaults.ItemHeight / 2).coerceAtLeast(MochiSidebarDefaults.FloatingInset)
                val bottomPad = if (hideNavigationBar) concentricBottom else maxOf(concentricBottom, navBottom)
                if (hideNavigationBar) HideNavBarWhileOpen(state)
                if (showing) {
                    Column(
                        Modifier
                            .padding(
                                start = MochiSidebarDefaults.FloatingInset,
                                top = statusTop + MochiSidebarDefaults.FloatingInset,
                                bottom = MochiSidebarDefaults.FloatingInset,
                            )
                            .width(panelDp)
                            .fillMaxHeight()
                            .graphicsLayer {
                                val p = state.anim.value.coerceIn(0f, 1f)
                                // 连阴影一起滑出屏幕
                                translationX = -(1f - p) * (travel + FloatingShadowMargin.toPx())
                            }
                            .drawWithCache {
                                val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                                onDrawBehind {
                                    val p = state.anim.value.coerceIn(0f, 1f)
                                    drawMochiShadow(path, shadow.resolve(this, dark, visibility = p), Color.Black)
                                }
                            }
                            .clip(shape)
                            .background(colors.panel)
                            .padding(top = topPad, bottom = bottomPad),
                        content = sidebar,
                    )
                }
            }
        }
    }
    // 放在内容后面登记：比页面里（包括内部导航）的返回处理更晚注册，打开时优先收起侧边栏
    PredictiveBackHandler(enabled = state.isOpen) { events ->
        try {
            events.collect { e -> state.anim.snapTo(1f - SidebarBackFollow * e.progress) }
            state.settle(false)
        } catch (c: CancellationException) {
            state.settle(true)
            throw c
        }
    }
}

/**
 * 一次手势：
 * - 打开时按在主页面上：Initial 阶段就拦下（主页面里的东西收不到），没动就是点按关闭，横向拖动跟手开合；
 *   收起动画途中不再拦，主页面的点击立刻生效
 * - 其他位置：只看子元素没消费的拖动；收起时往右（且允许 [swipeToOpen]）、打开时任意横向拖动都接手（往右停在完全打开，不会漏给外层变成返回）
 * @param mainLeft 主页面此刻在屏幕上的左边缘（打开时它右边才是「主页面」）
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.sidebarGestures(
    state: MochiSidebarState,
    swipeToOpen: Boolean,
    travel: Float,
    fling: Float,
    scope: CoroutineScope,
    mainLeft: () -> Float,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val open = state.isOpen || state.anim.value > 0.0005f
        // 只有停在打开（或正在打开）时才拦主页面；开始收起的那一刻起，点主页面照常落到主页面里，横向拖动仍能接手
        val onMain = state.isOpen && down.position.x >= mainLeft()
        if (!open && !swipeToOpen) return@awaitEachGesture
        if (onMain) down.consume()
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        var claimed = false
        var base = 0f
        while (true) {
            val ev = awaitPointerEvent(if (onMain) PointerEventPass.Initial else PointerEventPass.Main)
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) {
                if (onMain) ch.consume()
                break
            }
            // 按下那一下会在 Main 阶段再送来一次，行的点按检测已经消费了它，不算子元素接手；只看之后的移动
            if (!claimed && !onMain && ch.previousPressed && ch.isConsumed) return@awaitEachGesture
            if (onMain || claimed) ch.consume()
            tracker.addPosition(ch.uptimeMillis, ch.position)
            dx += ch.position.x - ch.previousPosition.x
            dy += ch.position.y - ch.previousPosition.y
            if (!claimed) {
                val horizontal = abs(dx) > slop && abs(dx) > abs(dy) * 1.2f
                val start = when {
                    onMain -> abs(dx) > slop || abs(dy) > slop

                    open -> {
                        if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                        // 往右也接手（到 1 就停住）：打开时右划不能漏给外层变成返回
                        horizontal
                    }

                    else -> {
                        if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                        if (dx < -slop) return@awaitEachGesture
                        horizontal && dx > 0f
                    }
                }
                if (start) {
                    // 接手的这一下就要消费掉：外层（父级）的整页返回在同一个事件里用同样的阈值判断，不消费它也会接手
                    ch.consume()
                    claimed = true
                    scope.launch { state.anim.stop() }
                    base = state.anim.value.coerceIn(0f, 1f)
                    dx = 0f
                    dy = 0f
                }
            } else {
                val p = (base + dx / travel).coerceIn(0f, 1f)
                scope.launch { state.anim.snapTo(p) }
            }
        }
        if (claimed) {
            val v = tracker.calculateVelocity().x / travel
            val p = (base + dx / travel).coerceIn(0f, 1f)
            state.settle(sidebarSettle(p, v, fling), v)
        } else if (onMain) {
            state.close()
        }
    }
}

/** 甩得比这快就按方向开合 */
private val SidebarFlingVelocity = 500.dp

/** 预测性返回拖到底时侧边栏收起多少 */
private const val SidebarBackFollow = 0.35f

/** 浮动面板顶角：搜索框圆头半径 + 离边 10dp，和搜索框同心；底角不小于它 */
private val FloatingTopCorner = MochiSearchBarDefaults.Height / 2 + MochiSidebarDefaults.ItemInset

/** [MochiSidebarHeader] 上下的留白 */
private val SidebarHeaderVerticalPadding = 8.dp

/** 浮动样式打开时藏起系统导航栏（手势小白条），关掉后恢复 */
@Composable
private fun HideNavBarWhileOpen(state: MochiSidebarState) {
    val view = LocalView.current
    LaunchedEffect(state, view) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val ctl = WindowCompat.getInsetsController(window, view)
        val oldBehavior = ctl.systemBarsBehavior
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        try {
            snapshotFlow { state.isOpen }
                .distinctUntilChanged()
                .collect { hide ->
                    if (hide) ctl.hide(WindowInsetsCompat.Type.navigationBars()) else ctl.show(WindowInsetsCompat.Type.navigationBars())
                }
        } finally {
            ctl.show(WindowInsetsCompat.Type.navigationBars())
            ctl.systemBarsBehavior = oldBehavior
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 浮动面板收起时多滑出去的距离，连阴影一起离开屏幕 */
private val FloatingShadowMargin = 32.dp

/**
 * 侧边栏顶部：搜索框 + 行尾按钮（如「新建」）。按钮用 [MochiSidebarHeaderButton]，和搜索框同高（[MochiSearchBarDefaults.Height]）。
 */
@Composable
public fun MochiSidebarHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = MochiSidebarDefaults.ItemInset, vertical = SidebarHeaderVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MochiSearchBar(query, onQueryChange, Modifier.weight(1f), placeholder = placeholder)
        action?.invoke()
    }
}

/** 侧边栏头部的圆形按钮，和搜索框同高 */
@Composable
public fun MochiSidebarHeaderButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // 和旁边的搜索框一样跟主题：有控件玻璃时是玻璃圆钮，没有时是色调纯色
    MochiIconButton(onClick, modifier, style = MochiButtonStyle.Glass, size = MochiSearchBarDefaults.Height, content = content)
}

/** 日期分组这类小标题（「今天」「前 7 天」），和行内文字左对齐 */
@Composable
public fun MochiSidebarSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    colors: MochiSidebarColors = MochiSidebarDefaults.colors(),
) {
    Text(
        text,
        modifier
            .fillMaxWidth()
            .padding(
                start = MochiSidebarDefaults.ItemInset + MochiSidebarDefaults.ItemPadding,
                end = MochiSidebarDefaults.ItemInset + MochiSidebarDefaults.ItemPadding,
                top = 18.dp,
                bottom = 6.dp,
            ),
        style = MochiTheme.textStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = colors.secondaryContent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 侧边栏的一项（一条对话、一个分区）：胶囊高亮，按下有高亮；选中底色淡入淡出，选中项换了不会瞬切。标题单行省略。
 */
@Composable
public fun MochiSidebarItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    colors: MochiSidebarColors = MochiSidebarDefaults.colors(),
) {
    SidebarRow(onClick, selected, MochiSidebarDefaults.ItemHeight, colors, modifier) {
        leading?.invoke()
        Text(
            title,
            Modifier.weight(1f),
            style = MochiTheme.textStyles.body,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/**
 * 侧边栏底部的个人资料行：圆形头像（默认取名字第一个字）+ 名字 + 可选副标题。
 */
@Composable
public fun MochiSidebarProfileRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    avatar: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    colors: MochiSidebarColors = MochiSidebarDefaults.colors(),
) {
    SidebarRow(onClick, false, 52.dp, colors, modifier.padding(vertical = 4.dp)) {
        if (avatar != null) {
            avatar()
        } else {
            val cs = MochiTheme.colorScheme
            Box(Modifier.size(32.dp).background(cs.primaryContainer, MochiCapsule), contentAlignment = Alignment.Center) {
                Text(
                    name.take(1),
                    style = MochiTheme.textStyles.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = cs.onPrimaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MochiTheme.textStyles.body, color = colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MochiTheme.textStyles.footnote,
                    color = colors.secondaryContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SidebarRow(
    onClick: () -> Unit,
    selected: Boolean,
    minHeight: Dp,
    colors: MochiSidebarColors,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sel by animateFloatAsState(if (selected) 1f else 0f, MochiMotions.responseSpring(0.3f, 1f), label = "selected")
    val press by animateFloatAsState(if (pressed) 1f else 0f, MochiMotions.responseSpring(0.2f, 1f), label = "pressed")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = MochiSidebarDefaults.ItemInset)
            .heightIn(min = minHeight)
            .drawWithCache {
                val outline = MochiRoundedRectangle(minHeight / 2).createOutline(size, layoutDirection, this)
                onDrawBehind {
                    if (sel > 0f) drawOutline(outline, colors.selected, alpha = sel.coerceIn(0f, 1f))
                    if (press > 0f) drawOutline(outline, colors.pressed, alpha = press.coerceIn(0f, 1f))
                }
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = MochiSidebarDefaults.ItemPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
