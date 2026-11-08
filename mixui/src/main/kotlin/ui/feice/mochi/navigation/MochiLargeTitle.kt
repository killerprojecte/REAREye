// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.MochiRefreshIndicator
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.mochiRefreshHold
import ui.feice.mochi.basic.rememberMochiRefreshState
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.effect.content.mochiBlur
import ui.feice.mochi.effect.content.mochiBlurAlpha
import ui.feice.mochi.effect.content.mochiRevealBlur
import ui.feice.mochi.effect.glass.LocalGlassSources
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.LocalMochiGlassShadow
import ui.feice.mochi.effect.glass.MochiNoGlassShadow
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBarBackdrop
import ui.feice.mochi.effect.mask.mochiScrollEdgeBlur
import ui.feice.mochi.effect.overscroll.MochiOverscrollEffect
import ui.feice.mochi.effect.overscroll.MochiScrollDefaults
import ui.feice.mochi.effect.overscroll.rememberMochiOverscrollEffect
import ui.feice.mochi.list.LocalMochiListItemSpacing
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.mochiOpticalLeading
import ui.feice.mochi.utils.smoothstep

/**
 * iOS 的大标题页面（UINavigationBar 的 large title）：
 * 页面已有「配色」「主题色板」这类卡片外分组小标题时默认用 [MochiCompactTitleScaffold]，不再新增正文独立大标题。
 * 没有此类分组小标题的目录页可保留展开大标题；自带滚动容器时用 [MochiTitleBar] 接滚动进度，不再套此滚动容器。
 * - 大标题跟着内容滚动；滚到顶栏下面时，顶栏中间的小标题淡入
 * - 顶栏的底色只在内容滚到它下面时出现（滚动 0 → 8dp 之间精确跟随），往下渐隐、不画分隔线
 * - 在顶部往下拉（越界回弹）时，大标题以左边为原点放大一点
 * - [searchBar] 放在大标题下面；停在「标题 + 搜索栏」半中间时自动吸附到展开或收起，和 iOS 一样
 * - 给了 [onRefresh] 就能下拉刷新（UIRefreshControl）：往下拉时顶栏下面的转圈一根根显出辐条，
 *   拉过阈值轻震一下、开始转、调用 onRefresh；松手后内容回弹到转圈下面停住，onRefresh 返回后再收回去
 *
 * 顶栏里放 [MochiBackButton]、[ui.feice.mochi.basic.MochiBarButton]、[ui.feice.mochi.basic.MochiBarIconButton]：
 * 都是 44dp 高、同一种底色，左右离屏幕边 16dp（和下面卡片的外边对齐）。侧推页操作配置稳定 navId，图标配置稳定 contentKey；返回按钮自动登记共享。
 *
 * @param subtitle 副标题（iOS 26 的 navigation subtitle）：大标题下面一行灰字，收起后顶栏小标题下面也有一行小字；
 *   内容变了模糊替换
 * @param navigationIcon 左上角（返回按钮）
 * @param actions 右上角
 */
@Composable
public fun MochiLargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrollState: ScrollState = rememberScrollState(),
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    searchBar: (@Composable () -> Unit)? = null,
    background: Color = MochiDefaults.groupedBackground(),
    barHeight: Dp = MochiTitleBarDefaults.Height,
    contentSpacing: Dp = MochiListDefaults.SectionSpacing,
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MochiTitleScaffold(
        largeTitle = true,
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        scroll = rememberTitleScroll(scrollState),
        navigationIcon = navigationIcon,
        actions = actions,
        searchBar = searchBar,
        background = background,
        barHeight = barHeight,
        contentSpacing = contentSpacing,
        onRefresh = onRefresh,
        body = columnBody(scrollState, content),
    )
}

/**
 * 带滚动内容的固定紧凑标题页：正文有卡片外分组小标题时的默认骨架。
 * 复用 [MochiLargeTitleScaffold] 的搜索、下拉刷新、越界回弹、键盘收起和安全区处理，
 * 不生成正文大标题、标题占位或大标题吸附。不要再包一层竖向滚动容器。
 * 自带 LazyList/Grid 等滚动容器的页面使用 [MochiTitleBar]。
 *
 * [navigationIcon]、[actions] 中的库顶栏按钮仍参与共享；侧推操作配置稳定 navId，图标配置 contentKey。
 * [subtitle] 仅显示在紧凑顶栏内；[onRefresh] 为 null 时不启用下拉刷新。
 */
@Composable
public fun MochiCompactTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrollState: ScrollState = rememberScrollState(),
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    searchBar: (@Composable () -> Unit)? = null,
    background: Color = MochiDefaults.groupedBackground(),
    barHeight: Dp = MochiTitleBarDefaults.Height,
    contentSpacing: Dp = MochiListDefaults.SectionSpacing,
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MochiTitleScaffold(
        largeTitle = false,
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        scroll = rememberTitleScroll(scrollState),
        navigationIcon = navigationIcon,
        actions = actions,
        searchBar = searchBar,
        background = background,
        barHeight = barHeight,
        contentSpacing = contentSpacing,
        onRefresh = onRefresh,
        body = columnBody(scrollState, content),
    )
}

/**
 * [MochiLargeTitleScaffold] 的懒加载版本：正文是 `LazyColumn`，只有屏幕附近的项在界面树里。
 * 长页面（分组多、一屏放不下）用它，见设计规范「长页面用懒加载列表」。大标题和搜索栏是列表的第 0 项，
 * 顶栏、大标题交接、吸附、越界放大、下拉刷新、拖动收键盘和 [MochiLargeTitleScaffold] 一样。
 * [content] 里每个分组放一项（`item { MochiListSection(…) }`）；项里的状态用 `rememberSaveable`，滑出屏幕再回来不丢
 */
@Composable
public fun MochiLazyLargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    searchBar: (@Composable () -> Unit)? = null,
    background: Color = MochiDefaults.groupedBackground(),
    barHeight: Dp = MochiTitleBarDefaults.Height,
    contentSpacing: Dp = MochiListDefaults.SectionSpacing,
    onRefresh: (suspend () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    MochiTitleScaffold(
        largeTitle = true,
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        scroll = rememberTitleScroll(listState),
        navigationIcon = navigationIcon,
        actions = actions,
        searchBar = searchBar,
        background = background,
        barHeight = barHeight,
        contentSpacing = contentSpacing,
        onRefresh = onRefresh,
        body = lazyBody(listState, content),
    )
}

/** [MochiCompactTitleScaffold] 的懒加载版本，用法同 [MochiLazyLargeTitleScaffold] */
@Composable
public fun MochiLazyCompactTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    searchBar: (@Composable () -> Unit)? = null,
    background: Color = MochiDefaults.groupedBackground(),
    barHeight: Dp = MochiTitleBarDefaults.Height,
    contentSpacing: Dp = MochiListDefaults.SectionSpacing,
    onRefresh: (suspend () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    MochiTitleScaffold(
        largeTitle = false,
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        scroll = rememberTitleScroll(listState),
        navigationIcon = navigationIcon,
        actions = actions,
        searchBar = searchBar,
        background = background,
        barHeight = barHeight,
        contentSpacing = contentSpacing,
        onRefresh = onRefresh,
        body = lazyBody(listState, content),
    )
}

/** 标题骨架读滚动位置：[value] 是滚过的距离（px），懒加载列表滚过第 0 项（标题区）以后按很远算 */
private class TitleScroll(
    val key: Any,
    val list: LazyListState?,
    val value: () -> Float,
    val scrolling: () -> Boolean,
    val snap: suspend (Int) -> Unit,
)

@Composable
private fun rememberTitleScroll(state: ScrollState): TitleScroll = remember(state) {
    TitleScroll(state, null, { state.value.toFloat() }, { state.isScrollInProgress }, { state.animateScrollTo(it) })
}

@Composable
private fun rememberTitleScroll(state: LazyListState): TitleScroll = remember(state) {
    TitleScroll(
        state,
        state,
        { if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE / 4f else state.firstVisibleItemScrollOffset.toFloat() },
        { state.isScrollInProgress },
        { state.animateScrollToItem(0, it) },
    )
}

/** 正文容器拿到的东西：容器自己的修饰（铺满 + 渐进模糊）、越界效果、紧凑页的刷新留白、顶部留白、分组间距、标题区（没有时 null） */
private class TitleBodyParams(
    val modifier: Modifier,
    val overscroll: MochiOverscrollEffect,
    val hold: Modifier,
    val barHeight: Dp,
    val top: Dp,
    val spacing: Dp,
    val header: (@Composable () -> Unit)?,
)

private typealias TitleBody = @Composable (TitleBodyParams) -> Unit

private fun columnBody(state: ScrollState, content: @Composable ColumnScope.() -> Unit): TitleBody = { b ->
    Column(
        b.modifier
            .verticalScroll(state, overscrollEffect = b.overscroll, flingBehavior = MochiScrollDefaults.flingBehavior())
            .statusBarsPadding()
            .padding(top = b.barHeight)
            .padding(start = 16.dp, top = b.top, end = 16.dp, bottom = MochiTabScaffoldDefaults.contentBottomPadding())
            .then(b.hold),
        verticalArrangement = Arrangement.spacedBy(b.spacing),
    ) {
        b.header?.invoke()
        content()
    }
}

private fun lazyBody(state: LazyListState, content: LazyListScope.() -> Unit): TitleBody = { b ->
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = MochiTabScaffoldDefaults.contentBottomPadding()
    // mochiListSection 的各行要跨过项间距连成一张卡片
    CompositionLocalProvider(LocalMochiListItemSpacing provides b.spacing) {
        LazyColumn(
            b.modifier.then(b.hold),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, top = status + b.barHeight + b.top, end = 16.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(b.spacing),
            flingBehavior = MochiScrollDefaults.flingBehavior(),
            overscrollEffect = b.overscroll,
        ) {
            b.header?.let { header -> item(key = TitleHeaderKey) { header() } }
            content()
        }
    }
}

private const val TitleHeaderKey = "mochi-title-header"

@Composable
private fun MochiTitleScaffold(
    title: String,
    largeTitle: Boolean,
    subtitle: String?,
    scroll: TitleScroll,
    navigationIcon: (@Composable () -> Unit)?,
    actions: (@Composable RowScope.() -> Unit)?,
    searchBar: (@Composable () -> Unit)?,
    background: Color,
    barHeight: Dp,
    contentSpacing: Dp,
    onRefresh: (suspend () -> Unit)?,
    modifier: Modifier = Modifier,
    body: TitleBody,
) {
    val density = LocalDensity.current
    val overscroll = rememberMochiOverscrollEffect()
    var headerPx by remember { mutableIntStateOf(0) }
    var titlePx by remember { mutableIntStateOf(0) }
    // 大标题还露着多少：往上滚进顶栏下面时从 1 降到 0（绘制阶段读，不重组）
    val titleVisible = {
        val t = titlePx.toFloat()
        if (t <= 0f) 1f else 1f - smoothstep(t * 0.3f, t, scroll.value())
    }
    val refresh = rememberMochiRefreshState(overscroll, scroll.list, onRefresh = onRefresh)
    // 顶栏下沿离屏幕顶多远：正文在这一段自己渐进模糊（主题有滚动边缘效果时）
    val barBottom = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + barHeight
    val edgeFade = with(density) { 8.dp.toPx() }
    // 一拖动内容就收起键盘（iOS 的 scrollDismissesKeyboard）：只收键盘、不清焦点，搜索框和 ✕ 还在
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(scroll.key) {
        snapshotFlow { scroll.scrolling() }.collect { if (it) keyboard?.hide() }
    }
    // 放在 MochiTabScaffold 的标签页里时：再点一次当前标签，平滑回到顶部
    MochiTabReselectHandler { scroll.snap(0) }

    // 停在标题区半中间：吸附到展开或收起
    LaunchedEffect(scroll.key, largeTitle) {
        if (!largeTitle) return@LaunchedEffect
        snapshotFlow { scroll.scrolling() }.collect { scrolling ->
            val limit = headerPx
            val v = scroll.value()
            if (!scrolling && limit > 0 && v >= 1f && v < limit) {
                scroll.snap(if (v < limit / 2) 0 else limit)
            }
        }
    }

    // 顶栏按钮是玻璃时（主题有 controlGlass），正文录下来给它们取样；顶栏和正文是兄弟，玻璃不会取样到自己
    val barBackdrop = rememberMochiBarBackdrop(background)
    Box(
        modifier
            .fillMaxSize()
            .background(background),
    ) {
        val header: (@Composable () -> Unit)? = if (largeTitle || searchBar != null) {
            {
                Column(
                    Modifier
                        // 刷新中内容停在转圈下面：标题区往下让出这段高度（摆放阶段读，不重组）
                        .layout { m, c ->
                            val p = m.measure(c)
                            val h = if (largeTitle) refresh.hold().roundToInt() else 0
                            layout(p.width, p.height + h) { p.place(0, h) }
                        }
                        .onSizeChanged { headerPx = it.height },
                ) {
                    if (largeTitle) {
                        Text(
                            title,
                            style = MochiTheme.textStyles.largeTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            minFontScale = 0.7f,
                            // 不额外缩进：大标题和下面卡片、搜索栏的外边在同一条竖线上
                            modifier = Modifier
                                .mochiOpticalLeading(MochiTheme.textStyles.largeTitle)
                                .onSizeChanged { titlePx = it.height }
                                .graphicsLayer {
                                    // 往上滚进顶栏下面时模糊淡出（和顶栏小标题的模糊淡入交接），跟着滚动位置走
                                    mochiBlurAlpha(titleVisible(), mochiRevealBlur(titleVisible(), 4.dp))
                                    // 往下拉：以左边为原点放大，最多 1.12 倍
                                    val pull = overscroll.offset.y.coerceAtLeast(0f)
                                    val s = 1f + (pull / 160.dp.toPx()).coerceAtMost(1f) * 0.12f
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = TransformOrigin(0f, 1f)
                                }
                                // 模糊结果缓存着：跟着滚动、下拉放大时不重新模糊
                                .mochiBlur { mochiRevealBlur(titleVisible(), 4.dp) },
                        )
                        if (subtitle != null) {
                            MochiBlurText(
                                subtitle,
                                style = MochiTheme.textStyles.subhead,
                                color = MochiTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // 紧贴大标题
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (searchBar != null) {
                        if (largeTitle) Spacer(Modifier.height(12.dp))
                        // 搜索栏在正文的标题区里，背后只有页面底色：玻璃不画外阴影
                        CompositionLocalProvider(LocalMochiGlassShadow provides MochiNoGlassShadow) { searchBar() }
                    }
                }
            }
        } else {
            null
        }
        // 正文里打开的菜单、面板：导航主机不录整页时取样这份正文
        CompositionLocalProvider(LocalGlassSources provides LocalGlassSources.current.let { it.copy(page = barBackdrop ?: it.page) }) {
            body(
                TitleBodyParams(
                    modifier = Modifier
                        .fillMaxSize()
                        // 录的是渐进模糊之后的样子：栏里的玻璃取样的和眼睛看到的一致（录原样的话贴边那圈会露出清晰的内容）
                        .mochiBackdropSource(barBackdrop)
                        .mochiScrollEdgeBlur(barBottom) { smoothstep(0f, edgeFade, scroll.value()) },
                    overscroll = overscroll,
                    // 紧凑页没有标题占位；刷新留白由整个正文一次性承担。
                    hold = if (largeTitle) Modifier else Modifier.mochiRefreshHold(refresh),
                    barHeight = barHeight,
                    top = if (largeTitle) 0.dp else 12.dp,
                    spacing = contentSpacing,
                    header = header,
                ),
            )
        }

        if (onRefresh != null) {
            // 转圈在「顶栏下沿」和「被拉下来的内容上沿」之间居中
            MochiRefreshIndicator(
                refresh,
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = barHeight),
            )
        }

        // 顶栏
        // 不画分隔线：底色往下多延一段渐隐（iOS 26 的滚动边缘效果），内容滚进顶栏下面时被柔和地盖住
        val fade = with(density) { 8.dp.toPx() }
        CompositionLocalProvider(LocalMochiBackdrop provides (barBackdrop ?: LocalMochiBackdrop.current)) {
            MochiTitleBar(
                title,
                subtitle = subtitle,
                navigationIcon = navigationIcon,
                actions = actions,
                background = background,
                barHeight = barHeight,
                titleProgress = {
                    val t = titlePx.toFloat()
                    if (!largeTitle) {
                        1f
                    } else if (t <= 0f) {
                        0f
                    } else {
                        smoothstep(t * 0.5f, t, scroll.value())
                    }
                },
                // 大标题、紧凑两种都跟着滚动：停在顶部时栏下面没有内容，渐隐不出现，不会盖住第一行
                backgroundProgress = { smoothstep(0f, fade, scroll.value()) },
            )
        }
    }
}
