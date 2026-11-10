package hk.uwu.mixui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.effect.content.mochiBlurReveal
import ui.feice.mochi.effect.glass.*
import ui.feice.mochi.effect.mask.mochiScrollEdgeBlur
import ui.feice.mochi.navigation.*
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.mochiOpticalLeading

/** Only scroll-aware title bars opt into hiding the native button surface at the top. */
internal val LocalMochiTopBarMaterialVisible = staticCompositionLocalOf { true }

/** Native material, app-owned geometry. Mochi 0.2 has a fixed 64dp title margin.
 * Measure the actual slots before laying out the title, including large font and RTL.
 * The native bar still owns the edge material, glass-source composition and buttons.
 */
@Composable
internal fun BoundedMochiTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    background: androidx.compose.ui.graphics.Color,
    backdrop: MochiBackdrop? = null,
    titleProgress: () -> Float = { 1f },
    backgroundProgress: () -> Float = titleProgress,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val materialVisible by remember(backgroundProgress) {
        derivedStateOf { backgroundProgress() > 0f }
    }
    MochiTitleBar(
        title = "", modifier = modifier, background = background, backdrop = backdrop,
        backgroundProgress = backgroundProgress,
        // One full-width slot lets the vendor provide all glass locals while we
        // measure each control only once. Never record this row into its own source.
        navigationIcon = {
            CompositionLocalProvider(LocalMochiTopBarMaterialVisible provides materialVisible) {
                SubcomposeLayout(Modifier.fillMaxWidth().height(MochiTitleBarDefaults.Height)) { constraints ->
                    val loose = constraints.copy(minWidth = 0, minHeight = 0)
                    val nav = subcompose("navigation") { Row(verticalAlignment = Alignment.CenterVertically) { navigationIcon() } }
                        .single().measure(loose)
                    val action = subcompose("actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                    }.single().measure(loose.copy(maxWidth = (constraints.maxWidth - nav.width).coerceAtLeast(0)))
                    val gap = 8.dp.roundToPx()
                    val start = nav.width + if (nav.width > 0) gap else 0
                    val end = constraints.maxWidth - action.width - if (action.width > 0) gap else 0
                    val width = (end - start).coerceAtLeast(0)
                    val text = subcompose("title") {
                        MochiBlurText(title, style = MochiTheme.textStyles.navTitle, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, contentAlignment = Alignment.Center,
                            modifier = Modifier.mochiBlurReveal(titleProgress, rise = 6.dp))
                    }.single().measure(loose.copy(maxWidth = width))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        nav.placeRelative(0, (constraints.maxHeight - nav.height) / 2)
                        action.placeRelative(constraints.maxWidth - action.width, (constraints.maxHeight - action.height) / 2)
                        // Prefer the whole bar's center when the complete title fits
                        // between controls. Otherwise center in the actual free interval,
                        // not at the nearest edge; maxWidth already ellipsizes only when
                        // even that interval cannot contain the title.
                        val centered = (constraints.maxWidth - text.width) / 2
                        val titleX = if (centered >= start && centered + text.width <= end) {
                            centered
                        } else {
                            start + (width - text.width) / 2
                        }
                        text.placeRelative(titleX, (constraints.maxHeight - text.height) / 2)
                    }
                }
            }
        },
    )
}

private fun headerProgress(offset: Float, height: Int): Float =
    if (height <= 0) 0f else (offset / height).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/** Scroll extent is at least the header height, even when Home fits the viewport.
 * Otherwise a native snap-to-header is clamped halfway, leaving both titles blurred.
 */
@Composable
internal fun MochiPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    background: androidx.compose.ui.graphics.Color,
    scrollState: ScrollState? = null,
    listState: LazyListState? = null,
    contentSpacing: Dp,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    searchBar: (@Composable () -> Unit)? = null,
    columnContent: (@Composable ColumnScope.() -> Unit)? = null,
    lazyContent: (LazyListScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    var headerHeight by remember { mutableIntStateOf(0) }
    val offset = { scrollState?.value?.toFloat() ?: if (listState!!.firstVisibleItemIndex > 0) Float.MAX_VALUE else listState.firstVisibleItemScrollOffset.toFloat() }
    val scrolling = { scrollState?.isScrollInProgress ?: listState!!.isScrollInProgress }
    suspend fun snap(value: Int) {
        if (scrollState != null) scrollState.animateScrollTo(value) else listState!!.animateScrollToItem(0, value)
    }
    MochiTabReselectHandler { snap(0) }
    LaunchedEffect(scrollState, listState) {
        snapshotFlow { scrolling() }.collect { moving ->
            val value = offset()
            if (!moving && headerHeight > 0 && value > 0 && value < headerHeight) {
                snap(if (value < headerHeight / 2f) 0 else headerHeight)
            }
        }
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val dragInteractions = scrollState?.interactionSource ?: listState!!.interactionSource
    LaunchedEffect(dragInteractions, keyboard) {
        // IME avoidance and bringIntoView are scrolls too. Only a real user drag
        // dismisses the keyboard; automatic focus positioning must keep it open.
        dragInteractions.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) keyboard?.hide()
        }
    }
    val backdrop = rememberMochiBarBackdrop(background)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + MochiTitleBarDefaults.Height
    val bottom = MochiTabScaffoldDefaults.contentBottomPadding()
    val edge = with(density) { 8.dp.toPx() }
    val progress = { headerProgress(offset(), headerHeight) }
    val material = { (offset() / edge).coerceIn(0f, 1f) }
    // Keep the native search in the scroll/input tree, but paint its recorded
    // pixels only after the body source is complete. The source never draws this
    // layer, so native glass cannot sample itself.
    val searchLayer = rememberGraphicsLayer()
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var searchCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val searchVisible by remember(listState) { derivedStateOf {
        listState == null || listState.layoutInfo.visibleItemsInfo.any { it.key == "mixui-title-header" }
    } }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(background)
        .onGloballyPositioned { viewportCoordinates = it }) {
        val viewport = maxHeight
        val header: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }) {
                ui.feice.mochi.basic.Text(title, style = MochiTheme.textStyles.largeTitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, minFontScale = 0.7f,
                    modifier = Modifier.mochiOpticalLeading(MochiTheme.textStyles.largeTitle)
                        .mochiBlurReveal({ 1f - progress() }))
                searchBar?.let {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth()
                        .onGloballyPositioned { searchCoordinates = it }
                        .drawWithContent { searchLayer.record { this@drawWithContent.drawContent() } }) {
                        it()
                    }
                }
            }
        }
        CompositionLocalProvider(
            LocalMochiBackdrop provides (backdrop ?: LocalMochiBackdrop.current),
            ui.feice.mochi.list.LocalMochiListItemSpacing provides contentSpacing,
        ) {
            // Share MIUIX gesture feedback and Compose decay across visual backends.
            // The Mochi edge blur below is a material effect, not scroll physics.
            val body = Modifier.fillMaxSize().pageScrollFeedback().mochiScrollEdgeBlur(top, material)
            // Record the fixed viewport including its clearing background, before
            // overscroll translates content. Search and the compact bar stay outside.
            Box(Modifier.fillMaxSize().mochiBackdropSource(backdrop).background(background)) {
            if (scrollState != null) {
                Column(body.verticalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, top = top, bottom = bottom)) {
                    header()
                    Spacer(Modifier.height(contentSpacing))
                    Column(Modifier.fillMaxWidth().heightIn(min = (viewport - top - bottom - contentSpacing).coerceAtLeast(0.dp)),
                        verticalArrangement = Arrangement.spacedBy(contentSpacing)) { columnContent?.invoke(this) }
                }
            } else {
                val state = requireNotNull(listState)
                val footerHeight by remember(state, top, bottom) {
                    derivedStateOf {
                        val info = state.layoutInfo
                        val bodyItems = info.visibleItemsInfo.filter { it.key != "mixui-title-header" && it.key != "mixui-title-footer" }
                        if (info.totalItemsCount > 0 && bodyItems.size == info.totalItemsCount - 2) {
                            val total = bodyItems.sumOf { it.size } + with(density) { contentSpacing.roundToPx() } * (bodyItems.size + 1)
                            (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding - total).coerceAtLeast(0)
                        } else 0
                    }
                }
                LazyColumn(body, state = state, contentPadding = PaddingValues(16.dp, top, 16.dp, bottom),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing), overscrollEffect = null) {
                    item(key = "mixui-title-header") { header() }
                    lazyContent?.invoke(this)
                    item(key = "mixui-title-footer") { Spacer(Modifier.height(with(density) { footerHeight.toDp() })) }
                }
            }
            }
            if (searchBar != null && searchVisible) {
                Box(Modifier.fillMaxSize().mochiScrollEdgeBlur(top, material).drawWithContent {
                    val viewport = viewportCoordinates
                    val search = searchCoordinates
                    if (viewport?.isAttached == true && search?.isAttached == true) {
                        // Resolve at draw time, including overscroll transforms. No
                        // post-layout State-to-offset feedback or input overlay.
                        val position = viewport.localPositionOf(search, Offset.Zero)
                        translate(position.x, position.y) { drawLayer(searchLayer) }
                    }
                })
            }
        }
        BoundedMochiTitleBar(title, background = background, backdrop = backdrop,
            titleProgress = progress, backgroundProgress = material, navigationIcon = navigationIcon, actions = actions)
    }
}
