package hk.uwu.mixui.components

import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme

/**
 * Pinned, theme-colored title bar with native controls supplied by the caller.
 * Both backends own their top status inset and protect horizontal system insets.
 *
 * This deliberately is not a drop-in alias for every MIUIX SmallTopAppBar option:
 * custom title colors, alpha ramps, slot margins and inset overrides require a
 * separate contract. In particular, no unsupported parameter is accepted and
 * silently discarded. Existing slot state, focus modifiers and callbacks are
 * forwarded without introducing another remembered interaction state.
 */
@Composable
fun CompactTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.basic.SmallTopAppBar(
            title = title,
            modifier = modifier,
            color = MixTheme.colorScheme.surface,
            navigationIcon = navigationIcon,
            actions = actions,
        )
        MixUiBackend.Mochi -> ui.feice.mochi.navigation.MochiTitleBar(
            title = title,
            modifier = modifier.windowInsetsPadding(horizontalBarInsets()),
            background = MixTheme.colorScheme.surface,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

/**
 * Owns the vertical scroll boundary and the large-to-compact title transition.
 * [content] must contain non-scrolling column children, not a nested scroll view.
 * The optional Compose [scrollState] is the only exposed scrolling state; vendor
 * scroll behavior and its nested-scroll connection stay inside this adapter.
 *
 * [bottomObstruction] is the height reserved for an existing bottom overlay,
 * including its system inset. The host avoids the IME locally and disregards that
 * overlay while the IME is open. Popup layout remains rooted in a full-window
 * scaffold, outside that resizing subtree.
 *
 * Native geometry is intentional: MIUIX keeps 12dp side/heading gaps and 24dp
 * bottom breathing room. Mochi retains its 16dp body sides and native large title with LTR alignment to
 * MIUIX's 26dp title inset. It keeps at least
 * its native 40dp end padding; the bottom overlay still gets at least 24dp of
 * clearance. This is not a pixel-equivalent arbitrary-padding API.
 */
@Composable
fun LargeTitleColumnScaffold(
    title: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    bottomObstruction: Dp = 0.dp,
    contentSpacing: Dp = 8.dp,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val background = MixTheme.colorScheme.surface
    GlassPreferenceScene(modifier = modifier) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            val nativeState = top.yukonga.miuix.kmp.basic.rememberTopAppBarState()
            val scrollBehavior = rememberMiuixPageScrollBehavior(state = nativeState)
            // A short column can collapse the native bar before its own offset moves.
            // Give native toolbar buttons the bar context instead of standalone glass.
            val materialVisible = scrollState.value > 0 || nativeState.heightOffset < 0f
            val bodyBackdrop = if (isRuntimeShaderSupported()) rememberLayerBackdrop() else null
            Scaffold(
                popupHost = {},
                containerColor = background,
                contentWindowInsets = WindowInsets.systemBars,
                topBar = {
                    if (bodyBackdrop != null) {
                        top.yukonga.miuix.kmp.glass.GlassTopAppBar(
                            title = title,
                            isContentScrolled = materialVisible,
                            backdrop = bodyBackdrop,
                            progressiveBlur = true,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = navigationIcon,
                            actions = actions,
                        )
                    } else {
                        top.yukonga.miuix.kmp.basic.TopAppBar(
                            title = title,
                            color = background,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = navigationIcon,
                            actions = actions,
                        )
                    }
                },
            ) { padding ->
                // Record only the body for the bar. The existing outer preference
                // scene still records body + bar for its sibling popup host.
                Box(Modifier.fillMaxSize()
                    .then(if (bodyBackdrop != null) Modifier.layerBackdrop(bodyBackdrop) else Modifier)
                    .background(background)) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .imePadding()
                            .verticalScroll(scrollState)
                            .padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = padding.calculateTopPadding() + 12.dp,
                                bottom = 24.dp + if (imeVisible) 0.dp else maxOf(
                                    padding.calculateBottomPadding(), bottomObstruction,
                                ),
                            ),
                        verticalArrangement = Arrangement.spacedBy(contentSpacing),
                        content = content,
                    )
                }
            }
        }
        MixUiBackend.Mochi -> {
            // The native host calculates max(navigation inset + 40dp,
            // bottom-bar inset + 16dp). Add 8dp to retain at least the existing
            // page's 24dp clearance above a caller-specified bottom overlay.
            val inheritedBottom = ui.feice.mochi.navigation.LocalMochiBottomBarInset.current
            val bottomBarInset = if (imeVisible) 0.dp else maxOf(
                inheritedBottom,
                if (bottomObstruction > 0.dp) bottomObstruction + 8.dp else 0.dp,
            )
            Scaffold(
                popupHost = {},
                containerColor = background,
                // Mochi owns status and navigation insets. Do not apply a second
                // scaffold content padding or recreate its scrolling container.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                CompositionLocalProvider(
                    ui.feice.mochi.navigation.LocalMochiBottomBarInset provides bottomBarInset,
                ) {
                    MochiLargeTitleAlignment(title) { callerStyle ->
                        MochiPageScaffold(
                            title = title,
                            modifier = Modifier.imePadding().windowInsetsPadding(horizontalBarInsets()),
                            scrollState = scrollState,
                            background = background,
                            contentSpacing = contentSpacing,
                            navigationIcon = { callerStyle.Restore { navigationIcon() } },
                            actions = {
                                val rowScope = this
                                callerStyle.Restore { actions(rowScope) }
                            },
                            columnContent = {
                                val columnScope = this
                                callerStyle.Restore { content(columnScope) }
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun horizontalBarInsets(): WindowInsets =
    WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
