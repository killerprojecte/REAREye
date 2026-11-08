package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Native lazy large-title page. Owns the only vertical scroll container.
 * MIUIX pins [searchBar] below its collapsing bar; Mochi places it in its native
 * snapping title header. Do not put another LazyColumn/verticalScroll in content.
 *
 * [bottomObstruction] includes the system inset of an existing bottom overlay,
 * as in LargeTitleColumnScaffold. Popup hosting stays outside the IME-resized
 * subtree and outside the backdrop recording.
 *
 * [listState] uses native indices: Mochi inserts a title/search header at index 0.
 * Prefer stable content keys; do not persist one backend's raw item index as a
 * backend-independent navigation destination.
 */
@Composable
fun LargeTitleLazyScaffold(
    title: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    bottomObstruction: Dp = 0.dp,
    contentSpacing: Dp = 8.dp,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    searchBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val background = MixTheme.colorScheme.surface
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val horizontalInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal)
    GlassPreferenceScene(modifier = modifier) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            val scrollBehavior = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior()
            val backdrop = if (isRuntimeShaderSupported()) rememberLayerBackdrop() else null
            Scaffold(
                popupHost = {},
                containerColor = background,
                topBar = {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(horizontalInsets)) {
                        if (backdrop != null) {
                            top.yukonga.miuix.kmp.glass.GlassTopAppBar(
                                title = title,
                                isContentScrolled = listState.canScrollBackward,
                                backdrop = backdrop,
                                progressiveBlur = false,
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
                        searchBar?.let {
                            Column(Modifier.background(background).padding(12.dp)) { it() }
                        }
                    }
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                        .windowInsetsPadding(horizontalInsets)
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .imePadding()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                        .background(background),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = padding.calculateTopPadding() + 12.dp,
                        bottom = 24.dp + if (imeVisible) 0.dp else maxOf(
                            padding.calculateBottomPadding(), bottomObstruction,
                        ),
                    ),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                    overscrollEffect = null,
                    content = content,
                )
            }
        }
        MixUiBackend.Mochi -> {
            val inheritedBottom = ui.feice.mochi.navigation.LocalMochiBottomBarInset.current
            val bottomBarInset = if (imeVisible) 0.dp else maxOf(
                inheritedBottom,
                if (bottomObstruction > 0.dp) bottomObstruction + 8.dp else 0.dp,
            )
            Scaffold(
                popupHost = {},
                containerColor = background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                CompositionLocalProvider(
                    ui.feice.mochi.navigation.LocalMochiBottomBarInset provides bottomBarInset,
                ) {
                    MochiLargeTitleAlignment(title) { callerStyle ->
                        ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold(
                            title = title,
                            modifier = Modifier.imePadding().windowInsetsPadding(horizontalInsets),
                            listState = listState,
                            background = background,
                            contentSpacing = contentSpacing,
                            navigationIcon = { callerStyle.Restore { navigationIcon() } },
                            actions = {
                                val rowScope = this
                                callerStyle.Restore { actions(rowScope) }
                            },
                            searchBar = searchBar?.let { search -> { callerStyle.Restore { search() } } },
                            content = { content(callerStyle.lazyScope(this)) },
                        )
                    }
                }
            }
        }
    }
    }
}
