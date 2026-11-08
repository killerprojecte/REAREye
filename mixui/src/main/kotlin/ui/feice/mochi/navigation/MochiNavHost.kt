// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.anim.MochiSprings
import ui.feice.mochi.anim.awaitFrameSettle
import ui.feice.mochi.anim.rememberFramePeriodNanos
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.LocalGlassSources
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberGlassFill
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import ui.feice.mochi.effect.glass.source
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.lerp

/**
 * 页面转场的手感。
 * @param open 打开（来源 → 整屏）的弹簧，接近 iOS 从桌面打开应用
 * @param close 关闭（整屏 / 松手时的画面 → 来源）的弹簧
 * @param cancel 预测性返回取消后弹回整屏的弹簧
 * @param push 侧推（没有来源的 push / pop）的弹簧：iOS 是临界阻尼，不回弹。打开和关闭用同一条，中途掉头时速度连续
 * @param underScale 来源转场的背景比例，默认 1 保持原位；显式小于 1 可启用旧的景深缩放
 * @param backScale 预测性返回拖到底时页面缩到多少
 * @param scrim 下层页面被压住时盖上的暗色（alpha 是最深时的）
 * @param backdrop 从来源打开的转场最底下的底色（下层页缩小后露出的四周和圆角外侧），像从桌面打开应用一样是黑的
 * @param pushBackdrop 侧推转场最底下的底色：任何一帧露出来的地方都用页面底色填上，不会闪黑。
 *   [Color.Unspecified] = iOS 分组样式的页面底色（MochiDefaults.groupedBackground）
 */
@Immutable
public data class MochiNavSpec(
    val open: AnimationSpec<Float>,
    val close: AnimationSpec<Float>,
    val cancel: AnimationSpec<Float>,
    val underScale: Float = 1f,
    val backScale: Float = 0.86f,
    val scrim: Color = Color.Black.copy(alpha = 0.18f),
    val backdrop: Color = Color.Black,
    val push: AnimationSpec<Float> = MochiMotions.responseSpring(0.42f, 1f),
    val pushBackdrop: Color = Color.Unspecified,
    val openMove: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.0005f),
    val closeMove: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.0005f),
)

public object MochiNavDefaults {
    @Composable
    public fun spec(
        // iOS 的 .smooth(duration: 0.3)：不回弹、300ms 平滑停住。回弹的弹簧尾巴拖得长，
        // 转场一直算「没停稳」，手势和点击都要等它，用起来粘手。途中换方向照样保留速度
        open: AnimationSpec<Float> = MochiSprings.smooth(300, visibilityThreshold = 0.0005f),
        close: AnimationSpec<Float> = MochiSprings.smooth(300, visibilityThreshold = 0.0005f),
        cancel: AnimationSpec<Float> = MochiMotions.responseSpring(0.35f, 1f),
        underScale: Float = 1f,
        backScale: Float = 0.86f,
        scrim: Color = Color.Black.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.4f else 0.18f),
        backdrop: Color = Color.Black,
        push: AnimationSpec<Float> = MochiMotions.responseSpring(0.42f, 1f),
        pushBackdrop: Color = MochiDefaults.groupedBackground(),
        // 位移和放大分开：位置用 iOS 的 snappy 300ms（回弹 0.15），到位时轻轻冲过一点再落回；大小、圆角仍用上面的 smooth
        openMove: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.0005f),
        closeMove: AnimationSpec<Float> = MochiSprings.snappy(300, visibilityThreshold = 0.0005f),
    ): MochiNavSpec = remember(open, close, cancel, underScale, backScale, scrim, backdrop, push, pushBackdrop, openMove, closeMove) {
        MochiNavSpec(open, close, cancel, underScale, backScale, scrim, backdrop, push, pushBackdrop, openMove, closeMove)
    }
}

/**
 * 页面容器：显示 [navigator] 栈顶的页面，处理转场和预测性返回。
 *
 * - 有来源（`push(route, from = key)`）时是「容器变形」：卡片本身变成页面。变形中的容器里同时画着卡片
 *   （录下来的画面，按容器宽度缩放）和页面（按容器宽度缩放），两者都贴着容器一起长大，卡片渐隐、页面渐显；
 *   关闭时原路缩回，落地时容器里的卡片画面正好和原卡片重合。所有过渡只由弹簧进度决定，
 *   途中返回或再点一次都从当前位置和速度连续掉头。下层页面默认保持原位，只随进度压暗。
 * - 没有来源时是 iOS 的侧推：新页从右边推进来，下层页往左退 30% 并压暗，新页左边缘带一道投影；
 *   打开和关闭是同一条弹簧，途中随时 pop / 再 push 同一页都会从当前位置连续掉头（转场中不拦触摸）。
 *   应用的侧推页操作使用带稳定 navId 的库顶栏控件，图标配置稳定 contentKey；布局容器本身不自动登记操作。
 * - 预测性返回：有来源的页跟手缩小（往滑入的那侧偏一点），松手缩回来源；侧推的页跟着手指往右滑，
 *   松手按手势末速度接着滑出去。取消就弹回。
 *   需要在 AndroidManifest 的 application 上加 `android:enableOnBackInvokedCallback="true"`。
 * - 转场中页面（包括被盖住的下层页）默认使用屏幕的物理圆角（[rememberMochiScreenCorners]），
 *   面板内则继承面板轮廓，全屏补底时底部两角保持直角。缩到来源附近时过渡到来源自己的圆角。
 *
 * 页面始终按整屏排版，转场只改 graphicsLayer，不会每帧重新布局。栈顶下面那一页保持组合
 * （滚动位置、来源元素的位置都还在），完全被盖住时不绘制；更深的页面状态由 SaveableStateHolder 保存。
 * @param predictiveBack 系统返回手势预览；false 时仍可正常返回，内层主机和面板继承此设置。应用内全屏右滑手势不受影响。
 * @param corners 页面转场圆角覆盖值；null 在面板内继承面板圆角，其他位置使用物理屏幕圆角；[MochiScreenCorners.None] 显式关闭内页转场圆角
 * @param windowGlass 最外层主机把整页录下来给浮层（菜单、警告框、面板）的玻璃取样。页面里有不能画进离屏图层的原生视图时传 false：
 *   WebView 在 Vulkan 渲染的机器上被玻璃取样会让渲染线程崩溃。关掉后浮层画成材质的纯色，页面里的栏玻璃不受影响
 */
@Composable
public fun MochiNavHost(
    navigator: MochiNavigator,
    modifier: Modifier = Modifier,
    spec: MochiNavSpec = MochiNavDefaults.spec(),
    corners: MochiScreenCorners? = null,
    predictiveBack: Boolean = LocalMochiPredictiveBack.current,
    windowGlass: Boolean = true,
    content: @Composable (route: String) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val screenCorners = rememberMochiScreenCorners()
    val surface = LocalMochiNavSurface.current
    val scope = rememberCoroutineScope()
    val geo = remember(navigator) { NavGeometry(navigator) }
    geo.spec = spec
    val framePeriod = rememberFramePeriodNanos()
    val pushBackdrop = spec.pushBackdrop.takeOrElse { MochiDefaults.groupedBackground() }
    geo.defaultCorners = corners ?: screenCorners
    geo.enclosingCorners = if (corners == null) surface?.corners else null
    geo.glassOpaque = surface?.takeIf { it.glass != null }?.opaque
    geo.fallbackCorner = if (corners == null && surface == null) with(LocalDensity.current) { SlideFallbackCorner.toPx() } else 0f

    BackHandler(enabled = !predictiveBack && navigator.canPop) { navigator.pop() }
    PredictiveBackHandler(enabled = predictiveBack && navigator.canPop) { events ->
        val e = navigator.entries.lastOrNull { !it.closing } ?: return@PredictiveBackHandler
        // 上一页还在「从来源打开」式地关闭（或者这一页就是那种页）：这次返回排队，手势不改这一页的画面，免得接手时跳一下
        val leaving = navigator.entries.lastOrNull()?.takeIf { it.closing }
        val queued = leaving != null && (leaving.source != null || e.source != null)
        var startY = Float.NaN
        var lastP = 0f
        var lastT = 0L
        var vel = 0f
        e.backActive = true
        try {
            events.collect { ev ->
                if (startY.isNaN()) startY = ev.touchY
                e.backEdge = ev.swipeEdge
                e.backDy = ev.touchY - startY
                // 手势速度（拖动进度 / 秒），平滑一下；侧推页松手后按它接着滑出去
                val t = System.nanoTime()
                if (lastT != 0L) {
                    val dt = (t - lastT) / 1e9f
                    if (dt > 0f) vel = lerp(vel, (ev.progress - lastP) / dt, 0.5f)
                }
                lastP = ev.progress
                lastT = t
                if (!queued) e.back.snapTo(ev.progress)
            }
            // 上面还有一页在侧推退出也照样提交：这一页跟着一起滑走（不再要求它必须是栈顶，连续返回不会被吞掉）
            if (e in navigator.entries && !e.closing) {
                if (e.source == null && !queued) {
                    // 侧推页：把此刻的画面换算回打开进度，关闭动画从这里、以手势速度连续地接着走
                    val pv = slideProgress(e)
                    e.progress.snapTo(pv)
                    e.back.snapTo(0f)
                    e.releaseVelocity = -SlideBackFollow * vel.coerceAtLeast(0f)
                }
                navigator.pop()
            }
        } catch (c: CancellationException) {
            scope.launch { e.back.animateTo(0f, spec.cancel) }
            throw c
        } finally {
            e.backActive = false
        }
    }

    // 最外层的导航主机把整页录下来给浮层（菜单、警告框、面板）的玻璃取样；面板里的内层主机、没有玻璃的主题不录
    val windowBackdrop = if (windowGlass && surface == null && LocalGlassSources.current.window == null && MochiTheme.materials.anyGlass) {
        rememberMochiBackdrop(MochiDefaults.groupedBackground())
    } else {
        null
    }
    CompositionLocalProvider(
        LocalMochiNavigator provides navigator,
        LocalMochiNavSurface provides null,
        LocalMochiPredictiveBack provides predictiveBack,
        LocalGlassSources provides LocalGlassSources.current.let { it.copy(window = windowBackdrop?.source ?: it.window) },
    ) {
        Box(
            modifier
                .fillMaxSize()
                .then(
                    if (surface == null) {
                        Modifier
                    } else {
                        Modifier.layout { measurable, constraints ->
                            // The sheet's stable content box excludes system insets and may extend
                            // below a floating sheet. Only visual layers use the actual shell height.
                            val h = surface.height()
                            val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                            layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                        }
                    },
                )
                .onSizeChanged { geo.size = it.toSize() }
                // 只在转场时画底色，平时不多画一层
                .drawBehind {
                    if (!navigator.isTransitioning) return@drawBehind
                    // 有「从来源打开」的页在动就是黑底（像桌面打开应用）；只有侧推时用页面底色，露出来也不会闪黑
                    val opening = navigator.entries.any { it.source != null && it.inTransition }
                    drawRect(if (opening) geo.spec.backdrop else pushBackdrop)
                },
        ) {
            // 页面录成整页的背景来源（浮层、转场里的共享顶栏玻璃取样它）；共享顶栏在录制外面，不会取样到自己
            Box(Modifier.fillMaxSize().then(if (windowBackdrop != null) Modifier.mochiBackdropSource(windowBackdrop) else Modifier)) {
                val entries = navigator.entries
                val n = entries.size
                // 组合哪些页：从栈顶往下，只要这一页在转场（没把下面完全盖住）就把下面那页也带上，
                // 快速连续推入 / 返回时，好几页同时在动，每一页下面都有东西，不会露出黑底；
                // 至少保留栈顶下面那一页（滚动位置、来源元素的位置都还在，返回手势一开始就能露出来）
                var first = (n - 2).coerceAtLeast(0)
                while (first > 0 && entries[first + 1].inTransition) first--
                for (i in first until n) {
                    val e = entries[i]
                    key(e.id) {
                        // 侧推阴影：画在这一页下面、单独一层，不被任何页面的圆角裁切截断
                        Spacer(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {}
                                .drawBehind { geo.drawSlideShadow(e, this) },
                        )
                        EntryLayer(e, geo, surface) {
                            holder.SaveableStateProvider(e.id) {
                                CompositionLocalProvider(LocalMochiNavEntry provides e) { content(e.route) }
                            }
                        }
                        LaunchedEffect(e, e.closing) {
                            val slide = e.source == null
                            if (!e.closing) {
                                // 新打开的页：先等它组合、排版、绘制完、帧率回稳（第一次打开时类加载、首屏子组合可能要几百毫秒）
                                // 再开始动，不然动画的开头几帧被这几帧吞掉，看起来是从卡片一下跳到半屏。等待期间画面还停在起点
                                if (e.progress.value == 0f && !e.started) awaitFrameSettle(framePeriod)
                                e.started = true
                                // 打开；关闭途中又被 push 回来时也走这里，从当前位置和速度掉头（Animatable 换目标时保留速度）
                                if (slide) {
                                    if (e.progress.value < 1f) e.progress.animateTo(1f, geo.spec.push)
                                } else {
                                    coroutineScope {
                                        launch { if (e.move.value < 1f || e.move.isRunning) e.move.animateTo(1f, geo.spec.openMove) }
                                        if (e.progress.value < 1f) e.progress.animateTo(1f, geo.spec.open)
                                    }
                                }
                                return@LaunchedEffect
                            }
                            if (slide) {
                                // 侧推：同一条弹簧直接往 0 走，打开途中返回也是从当前位置连续掉头
                                val v = e.releaseVelocity
                                e.releaseVelocity = 0f
                                e.progress.animateTo(0f, geo.spec.push, initialVelocity = if (v != 0f) v else e.progress.velocity)
                                navigator.remove(e)
                                holder.removeState(e.id)
                                navigator.onClosed()
                                return@LaunchedEffect
                            }
                            // 有来源：同一条弹簧从当前位置往 0 走（不重新起算，所以能随时掉头）；
                            // 返回手势的缩小量一起弹回 0，落地时正好是来源的矩形
                            coroutineScope {
                                launch { e.back.animateTo(0f, geo.spec.close) }
                                launch { e.move.animateTo(0f, geo.spec.closeMove) }
                                e.progress.animateTo(0f, geo.spec.close)
                            }
                            navigator.remove(e)
                            holder.removeState(e.id)
                            navigator.onClosed()
                        }
                    }
                }
            }
            // 跨页共享的顶栏：侧推转场中画在所有页面上面、留在顶栏原位变形（见 MochiNavBarItem.kt）。不拦触摸
            // 玻璃按钮的形状画成玻璃（取样整页录制），其余形状和所有图标画在 Spacer 里
            // 不录整页时（windowGlass = false）玻璃按钮取样各自页面的背景，见 SharedBarGlass
            val barGlass = rememberGlassFill(GlassRole.Control, windowBackdrop?.source)
            if (navigator.isTransitioning) SharedBarGlass(navigator, barGlass)
            val barPaths = remember { BarPathCache() }
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    // 从来源打开时，下层页的顶栏 / 底栏（mochiNavOccluder）先盖在变形中的容器上、再随进度淡掉，不会被一下盖住
                    geo.drawOccluders(this)
                    drawSharedBarItems(navigator, barPaths, glass = true)
                },
            )
        }
    }
}

@Composable
private fun EntryLayer(e: MochiNavEntry, geo: NavGeometry, surface: MochiNavSurface?, content: @Composable () -> Unit) {
    val cover = rememberGraphicsLayer()
    val pageMask = remember { NavPageMask() }
    DisposableEffect(e, cover) {
        e.coverLayer = cover
        onDispose { if (e.coverLayer === cover) e.coverLayer = null }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { e.coverCoords = it }
            // 变换后的整页再录一份：下层页的玻璃在转场中读它（rememberMochiNavCoverBackdrop），原地照常画
            .drawWithContent {
                val outline = geo.outline(e)
                // Reuse the captured page, rather than installing a path clip on
                // every descendant. Clear only the exterior of the G2 contour.
                cover.compositingStrategy = if (outline != null) {
                    androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                } else {
                    androidx.compose.ui.graphics.layer.CompositingStrategy.Auto
                }
                cover.record {
                    this@drawWithContent.drawContent()
                    if (outline != null) pageMask.clearOutside(this, outline)
                }
                drawLayer(cover)
            }
            .graphicsLayer { geo.applyLayer(e, this) }
            .drawWithContent {
                // 完全被盖住的下层页不画。不用 alpha = 0：那样露出来时只改了图层属性，
                // 内容可能没重新录制（真机上会画成空白）；在这里跳过，露出来时这块会重新执行、整页重画。
                // 这里只读「是否转场中」（derivedStateOf），不读每帧变化的进度，转场中不会每帧重录整页
                if (!geo.isHidden(e)) {
                    // The incoming page also covers the previous page's dimming below its safe body.
                    // 玻璃面板：页面是透明的，底色按面板此刻的不透明度画（半屏透出面板的玻璃，全屏和面板一样不透明）
                    if (surface != null) drawRect(surface.color, alpha = if (surface.glass == null) 1f else surface.opaque())
                    drawContent()
                }
            },
    ) {
        val scope = rememberCoroutineScope()
        // 正在缩回来源：只有看得见的那块接触摸（画面不裁）。点来源卡片照常掉头长回来，这一块以外落到下层页上
        val hit = if (e.closing) Modifier.visibleHitArea(e, geo) else Modifier
        Box(
            hit
                .fillMaxSize()
                // 页面内容单独一层：被盖住时外层跳过绘制、露出来时外层只重录一条「画这一层」，
                // 页面本身的绘制记录保留着，转场第一帧不用把整页重新录一遍。
                // 转场中这一层画进离屏纹理：外层每帧缩放、裁切的只是这张图，文字字形和路径不随缩放每帧重新光栅化；
                // 每帧变的盖色 / 压暗在旁边自己那一层，不会让这张图作废
                .graphicsLayer {
                    // 被「从来源打开」的页盖着的下层页也一样：它这段时间只缩放（页里收起的栏由下面那层代画）
                    val cached = e.inTransition || geo.nav.isCarriedUnderSource(e)
                    compositingStrategy = if (cached) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .then(
                    if (surface == null) {
                        Modifier
                    } else {
                        Modifier.layout { measurable, constraints ->
                            // Keep text and cards at the original safe height while their page's
                            // background, scrim, shadow and corner clipping reach the visible bottom.
                            val h = surface.contentHeight()
                            val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                            layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                        }
                    },
                )
                // iOS 26：在页面任意位置往右滑都能返回（不必从屏幕边缘开始）
                .pointerInput(e) { fullSwipeBack(e, geo, scope) }
                // 整页都算命中，触摸不会漏到下面那页
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .onPlaced { e.contentCoords = it },
        ) { content() }
        // 从来源打开的转场中，这一页收起的栏（标签栏、底部工具栏）在这里代画：位置、下滑和原来一样，
        // 压在下面的压暗层下面；它每帧在动，放在自己的图层里，不会让上面那张整页缓存作废
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {}
                .drawBehind { geo.drawCarriedBars(e, this) },
        )
        // 正在退出的页不接触摸（iOS 也是）：点在它还露着的部分不会误触，点在露出来的下层页上照常。
        // 有来源的页正在缩回去时点它 = 再打开：从当前位置掉头长回来
        if (e.closing) {
            val nav = geo.nav
            Spacer(
                hit
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                for (ch in ev.changes) ch.consume()
                                if (e.source != null && e.closing && ev.changes.any { it.changedToDown() }) nav.push(e.route, e.source)
                            }
                        }
                    },
            )
        }
        // 盖色 / 压暗每帧都变：放在自己的小图层里，只重录这一层，页面内容不跟着重录
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {}
                .drawBehind { geo.drawOver(e, this) },
        )
    }
}

/** 只把顶上 [NavGeometry.visibleHeight] 那么高报成自己的大小（命中范围跟着缩），内容照整页排版、照常画 */
private fun Modifier.visibleHitArea(e: MochiNavEntry, geo: NavGeometry): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val h = ceil(geo.visibleHeight(e)).toInt().coerceIn(constraints.minHeight, p.height)
    layout(p.width, h) { p.place(0, 0) }
}

/** 侧推时下层页往左退多少（屏宽的比例，iOS 约 0.3） */
internal const val SlideUnderShift = 0.3f

/** 预测性返回拖到底时，侧推页最多滑出去多少（屏宽比例） */
private const val SlideBackFollow = 0.9f

/** 屏幕没有物理圆角时侧推用的圆角 */
internal val SlideFallbackCorner = 28.dp

/** 侧推页此刻的「可见打开程度」：打开进度和返回手势里滑得更远的那个 */
internal fun slideProgress(e: MochiNavEntry): Float = 1f - max(1f - e.progress.value, SlideBackFollow * e.back.value)
