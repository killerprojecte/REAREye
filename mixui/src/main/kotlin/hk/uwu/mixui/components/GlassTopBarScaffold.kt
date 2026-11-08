package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBarBackdrop

/**
 * Page host with a vendor-native top bar and a page-only backdrop.
 *
 * Put scrolling page content in [content], using its padding as the scroll
 * container's content padding, not as padding on the container itself. This lets
 * content pass behind the bar. Dialogs and scrims belong outside this host (or in
 * the scaffold's popup host), never inside [content]: the bar must not sample
 * itself or a foreground overlay. No backdrop or vendor scroll state escapes.
 *
 * MIUIX uses its collapsing glass bar when runtime shaders are supported and its
 * ordinary solid bar otherwise. Mochi uses its own title bar and root-theme
 * materials, including the library's solid fallback when glass is disabled.
 */
@Composable
fun GlassTopBarScaffold(
    title: String,
    isContentScrolled: Boolean,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val background = MixTheme.colorScheme.surface
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            val scrollBehavior = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior()
            val supported = isRuntimeShaderSupported()
            val backdrop = if (supported) rememberLayerBackdrop() else null
            Scaffold(
                modifier = modifier,
                containerColor = background,
                topBar = {
                    if (backdrop != null) {
                        top.yukonga.miuix.kmp.glass.GlassTopAppBar(
                            title = title,
                            isContentScrolled = isContentScrolled,
                            backdrop = backdrop,
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
                Box(
                    Modifier.fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                        .background(background),
                ) { content(padding) }
            }
        }
        MixUiBackend.Mochi -> {
            val backdrop = rememberMochiBarBackdrop(background)
            Scaffold(
                modifier = modifier,
                containerColor = background,
                topBar = {
                    ui.feice.mochi.navigation.MochiTitleBar(
                        title = title,
                        background = background,
                        backgroundProgress = { if (isContentScrolled) 1f else 0f },
                        backdrop = backdrop,
                        navigationIcon = navigationIcon,
                        actions = actions,
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().mochiBackdropSource(backdrop).background(background)) {
                    content(padding)
                }
            }
        }
    }
}

/** Native toolbar control. Use in [GlassTopBarScaffold]'s navigation/actions slots. */
@Composable
fun TopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            if (isRuntimeShaderSupported()) {
                top.yukonga.miuix.kmp.glass.GlassIconButton(
                    onClick = onClick, modifier = modifier, content = content,
                )
            } else {
                top.yukonga.miuix.kmp.basic.IconButton(
                    onClick = onClick, modifier = modifier, content = content,
                )
            }
        }
        MixUiBackend.Mochi -> ui.feice.mochi.basic.MochiBarIconButton(
            onClick = onClick, modifier = modifier, content = content,
        )
    }
}
