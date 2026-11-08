// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package ui.feice.mochi.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.MochiTabBar
import ui.feice.mochi.basic.MochiTabBarBehavior
import ui.feice.mochi.basic.MochiTabBarColors
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.basic.rememberMochiTabBarScrollBehavior
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.LocalGlassSources
import ui.feice.mochi.effect.glass.glassOn
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/**
 * 底部栏（标签栏、底部工具栏）从屏幕底边往上占了多高，包括导航栏和栏四周的留白。
 * [MochiTabScaffold] 提供；Mochi 的标题骨架据此把正文底部留够，最后一项不会停在标签栏下面。
 * 自己写的滚动页用 [MochiTabScaffoldDefaults.contentBottomPadding] 取底部留白，不要手写 `Spacer(112.dp)`。
 */
public val LocalMochiBottomBarInset: ProvidableCompositionLocal<Dp> = compositionLocalOf { 0.dp }

/** [MochiTabScaffold] 的选中状态。程序里切标签（比如从详情页跳到「检测」）用 [select] */
@Stable
public class MochiTabScaffoldState internal constructor(initialIndex: Int) {
    /** 当前选中的标签 */
    public var selectedIndex: Int by mutableIntStateOf(initialIndex)
        private set

    internal val pages = HashMap<Int, TabPageSlot>()

    /** 切到第 [index] 个标签（和点标签栏一样，有同层级过渡） */
    public fun select(index: Int) {
        selectedIndex = index
    }

    internal companion object {
        val Saver: Saver<MochiTabScaffoldState, Int> = Saver(save = { it.selectedIndex }, restore = { MochiTabScaffoldState(it) })
    }
}

/** 选中的标签跨重建保存 */
@Composable
public fun rememberMochiTabScaffoldState(initialIndex: Int = 0): MochiTabScaffoldState =
    rememberSaveable(saver = MochiTabScaffoldState.Saver) { MochiTabScaffoldState(initialIndex) }

public object MochiTabScaffoldDefaults {
    /** 底部栏和正文最后一项之间留的空 */
    public val BarClearance: Dp = 16.dp

    /** 没有底部栏时，正文底部在导航栏之上留的空 */
    public val EndPadding: Dp = 40.dp

    /**
     * 滚动正文的底部留白：在 [MochiTabScaffold] 里 = 底部栏占的高度 + [BarClearance]，
     * 不在时 = 导航栏 + [EndPadding]。Mochi 的标题骨架已经用了它
     */
    @Composable
    public fun contentBottomPadding(): Dp {
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bar = LocalMochiBottomBarInset.current
        return if (bar > 0.dp) maxOf(nav + EndPadding, bar + BarClearance) else nav + EndPadding
    }
}

/**
 * 根页的标签页骨架（iOS 的 UITabBarController）：底部浮起的 [MochiTabBar] + 每个标签一页。接好了标签页的那些惯例：
 * - 各标签的页面状态和滚动位置各自保留（切走再切回来还在原处）；切换用同层级的景深过渡，也可以左右滑跟手切换
 * - 再点一次当前标签：平滑回到顶部（页面用 Mochi 的标题骨架时自动接好；自己写的滚动页用 [MochiTabReselectHandler]）
 * - 正文底部自动让出标签栏的高度（[LocalMochiBottomBarInset]），不用在列表末尾手写补白
 * - 往下滚时标签栏收成只剩当前图标的小圆、往上滚展开（纯色、玻璃标签栏都是）
 * - 玻璃标签栏（主题 materials.bar 不为 null）：标签页内容录成玻璃的背景来源；纯色标签栏：后面垫一层渐隐底色
 * - 放在 [MochiNavHost] 的根页里时，推入新页标签栏跟着收起（[mochiHideWhenCovered]）
 *
 * ```
 * MochiNavHost(nav) { route ->
 *     when (route) {
 *         "home" -> MochiTabScaffold(tabs, state = tabState) { index ->
 *             when (index) { 0 -> CatalogPage(); 1 -> DetectorPage(); else -> SettingsPage() }
 *         }
 *         …
 *     }
 * }
 * ```
 *
 * @param state 选中状态，程序里切标签时自己持有一个 [rememberMochiTabScaffoldState]
 * @param onSelect 选中的标签变了（点击或左右滑）之后调用，用来同步业务状态
 * @param collapseOnScroll 是否随正文滚动收起标签栏；false 时保持展开，再点回顶和转场隐藏照常。
 * @param swipeBetweenTabs 左右滑切换标签
 * @param content 第 index 个标签的页面
 */
@Composable
public fun MochiTabScaffold(
    items: ImmutableList<MochiTabItem>,
    modifier: Modifier = Modifier,
    state: MochiTabScaffoldState = rememberMochiTabScaffoldState(),
    onSelect: (Int) -> Unit = {},
    colors: MochiTabBarColors = MochiTabBarDefaults.colors(),
    behavior: MochiTabBarBehavior = MochiTabBarDefaults.behavior(),
    background: Color = MochiDefaults.groupedBackground(),
    swipeBetweenTabs: Boolean = true,
    collapseOnScroll: Boolean = true,
    content: @Composable (index: Int) -> Unit,
) {
    val scrollBehavior = rememberMochiTabBarScrollBehavior()
    val backdrop = rememberMochiBackdrop(background)
    val glass = glassOn(GlassRole.Bar)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var barPx by remember { mutableIntStateOf(0) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val select: (Int) -> Unit = remember(state, scrollBehavior) {
        { index ->
            state.select(index)
            scrollBehavior.expand()
            currentOnSelect(index)
        }
    }
    val pages = remember(items.size) { List(items.size) { it } }
    Box(modifier.fillMaxSize().background(background)) {
        CompositionLocalProvider(LocalMochiBottomBarInset provides with(density) { barPx.toDp() }) {
            MochiPeerTransition(
                targetState = state.selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                modifier = Modifier
                    .fillMaxSize()
                    .mochiBackdropSource(backdrop)
                    .then(if (collapseOnScroll) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier),
                pages = if (swipeBetweenTabs) pages else null,
                onPageChange = select,
                direction = { from, to -> to.compareTo(from) },
                background = background,
                spec = MochiPeerTransitionSpec.Depth,
            ) { page ->
                val slot = remember { TabPageSlot() }
                DisposableEffect(state, page, slot) {
                    state.pages[page] = slot
                    onDispose { if (state.pages[page] === slot) state.pages.remove(page) }
                }
                CompositionLocalProvider(
                    LocalMochiTabPage provides slot,
                    LocalGlassSources provides LocalGlassSources.current.copy(page = backdrop),
                ) { content(page) }
            }
        }
        // 纯色标签栏后面垫一层底色、朝内容那侧渐隐（iOS 26 滚动边缘效果）；玻璃标签栏直接透出内容
        if (!glass) {
            Spacer(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 36.dp)
                    .mochiScrollEdgeFade(background, bottom = true, extent = 56.dp),
            )
        }
        MochiTabBar(
            selectedIndex = state.selectedIndex,
            onSelect = select,
            items = items,
            colors = colors,
            behavior = behavior,
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 量的是含留白的整块高度；收起、推入新页时的位移不影响它，正文底部不跟着跳
                .onSizeChanged { barPx = it.height }
                .mochiHideWhenCovered()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            scrollBehavior = if (collapseOnScroll) scrollBehavior else null,
            onReselect = { index ->
                scrollBehavior.expand()
                scope.launch { state.pages[index]?.action?.invoke() }
            },
        )
    }
}

/**
 * 在 [MochiTabScaffold] 的标签页里登记「再点一次当前标签」要做的事（通常是回到顶部）。
 * Mochi 的标题骨架已经登记；自己写 LazyColumn + [MochiTitleBar] 的页面调用它：
 * `MochiTabReselectHandler { listState.animateScrollToItem(0) }`。一页只认最先登记的那个，
 * 页里面板等嵌套滚动区域的登记不会顶替页面本身。不在标签页里时什么都不做。
 */
@Composable
public fun MochiTabReselectHandler(onReselect: suspend () -> Unit) {
    val slot = LocalMochiTabPage.current ?: return
    val current by rememberUpdatedState(onReselect)
    val token = remember { Any() }
    DisposableEffect(slot, token) {
        if (slot.owner == null) {
            slot.owner = token
            slot.action = { current() }
        }
        onDispose {
            if (slot.owner === token) {
                slot.owner = null
                slot.action = null
            }
        }
    }
}

internal class TabPageSlot {
    var owner: Any? = null
    var action: (suspend () -> Unit)? = null
}

private val LocalMochiTabPage = staticCompositionLocalOf<TabPageSlot?> { null }
