package hk.uwu.mixui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.LocalMochiGlassShadow
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBarBackdrop
import ui.feice.mochi.effect.glass.rememberMochiBackdrop

/**
 * Page host with a vendor-native top bar and a page-only backdrop.
 *
 * Put scrolling page content in [content], using its padding as the scroll
 * container's content padding, not as padding on the container itself. This lets
 * content pass behind the bar. Forms belong in [overlayContent], never [content].
 * Two separate recordings avoid feedback: body-only for the bar; page plus bar
 * for forms. The root popup host, overlays and scrims are outside both recordings.
 * No backdrop or vendor scroll state escapes. [pinnedHeader] sits below the bar
 * and contributes to the returned content padding, for tabs or filters.
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
    overlayContent: @Composable () -> Unit = {},
    topBarModifier: Modifier = Modifier,
    pinnedHeader: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val background = MixTheme.colorScheme.surface
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            val scrollBehavior = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior()
            val supported = isRuntimeShaderSupported()
            val backdrop = if (supported) rememberLayerBackdrop() else null
            val formBackdrop = if (supported) rememberLayerBackdrop() else null
            val tabSurfaceAlpha = animateFloatAsState(
                targetValue = if (isContentScrolled) 1f else 0f,
                animationSpec = top.yukonga.miuix.kmp.glass.GlassMotion.topBarButtonFloat(),
                label = "headerTabMaterial",
            )
            GlassFormOverlayHost(
                modifier = modifier,
                background = background,
                sceneModifier = if (formBackdrop != null) Modifier.layerBackdrop(formBackdrop) else Modifier,
                backdrop = remember(formBackdrop) { GlassFormBackdrop.Miuix(formBackdrop) },
                overlayContent = overlayContent,
            ) { sceneModifier ->
                Scaffold(
                    modifier = sceneModifier,
                    containerColor = background,
                    // The outer host renders popups; recording them here would
                    // feed the form and its scrim back into its own glass.
                    popupHost = {},
                    topBar = {
                        Column {
                            if (backdrop != null) {
                                top.yukonga.miuix.kmp.glass.GlassTopAppBar(
                                    title = title,
                                    modifier = topBarModifier,
                                    isContentScrolled = isContentScrolled,
                                    backdrop = backdrop,
                                    // Match the 799 GlassPage default: native band plus button
                                    // underlay. Progressive blur is an opt-in demo setting.
                                    progressiveBlur = false,
                                    scrollBehavior = scrollBehavior,
                                    navigationIcon = navigationIcon,
                                    actions = actions,
                                    // Native bottomContent inherits the bar's material/underlay.
                                    // It is outside the body-only backdrop recording.
                                    bottomContent = {
                                        CompositionLocalProvider(
                                            LocalGlassTabsSurfaceAlpha provides tabSurfaceAlpha.value,
                                        ) { pinnedHeader() }
                                    },
                                )
                            } else {
                                top.yukonga.miuix.kmp.basic.TopAppBar(
                                    title = title,
                                    modifier = topBarModifier,
                                    color = background,
                                    scrollBehavior = scrollBehavior,
                                    navigationIcon = navigationIcon,
                                    actions = actions,
                                )
                                pinnedHeader()
                            }
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
        }
        MixUiBackend.Mochi -> {
            val backdrop = rememberMochiBarBackdrop(background)
            val formBackdrop = rememberMochiBackdrop(background)
            GlassFormOverlayHost(
                modifier = modifier,
                background = background,
                sceneModifier = Modifier.mochiBackdropSource(formBackdrop),
                backdrop = remember(formBackdrop) { GlassFormBackdrop.Mochi(formBackdrop) },
                overlayContent = overlayContent,
            ) { sceneModifier ->
                Scaffold(
                    modifier = sceneModifier,
                    containerColor = background,
                    popupHost = {},
                    topBar = {
                        Column {
                            ui.feice.mochi.navigation.MochiTitleBar(
                                title = title,
                                modifier = topBarModifier.windowInsetsPadding(horizontalBarInsets()),
                                background = background,
                                backgroundProgress = { if (isContentScrolled) 1f else 0f },
                                backdrop = backdrop,
                                navigationIcon = navigationIcon,
                                actions = actions,
                            )
                            CompositionLocalProvider(
                                LocalGlassTabsSurfaceAlpha provides 1f,
                                LocalMochiBackdrop provides backdrop,
                                LocalMochiGlassShadow provides { if (isContentScrolled) 1f else 0f },
                            ) { pinnedHeader() }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().mochiBackdropSource(backdrop).background(background)) {
                        content(padding)
                    }
                }
            }
        }
    }
}

/**
 * Full-window popup owner. Only the inner page is recorded; overlayContent is its
 * sibling. The inner Scaffold disables its popup host, while renderInRootScaffold
 * resolves to this outer host or an existing ancestor root, neither recorded here.
 */
@Composable
private fun GlassFormOverlayHost(
    modifier: Modifier,
    background: Color,
    sceneModifier: Modifier,
    backdrop: GlassFormBackdrop,
    overlayContent: @Composable () -> Unit,
    page: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Box(Modifier.fillMaxSize()) {
            page(Modifier.fillMaxSize().then(sceneModifier))
            CompositionLocalProvider(LocalGlassFormBackdrop provides backdrop) {
                overlayContent()
            }
        }
    }
}

/**
 * Native toolbar control. Use in [GlassTopBarScaffold]'s navigation/actions slots.
 *
 * MIUIX 799 GlassIconButton has no disabled API. Disabled controls therefore use
 * its non-clickable basic IconButton at the same footprint, visibly dimmed and
 * marked disabled for accessibility. Enabled controls keep the native glass
 * material inherited from the bar; no duplicate backdrop is passed here.
 */
@Composable
fun TopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Native Mochi buttons expose an action but do not merge their icon label.
    // Keep the label and vendor-owned action in one accessible control.
    val buttonModifier = modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        if (!enabled) disabled()
    }
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            if (enabled && isRuntimeShaderSupported()) {
                top.yukonga.miuix.kmp.glass.GlassIconButton(
                    onClick = onClick, modifier = buttonModifier, content = content,
                )
            } else {
                top.yukonga.miuix.kmp.basic.IconButton(
                    onClick = onClick,
                    modifier = buttonModifier.alpha(if (enabled) 1f else 0.38f),
                    enabled = enabled,
                    minWidth = top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults.ButtonSize,
                    minHeight = top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults.ButtonSize,
                    content = content,
                )
            }
        }
        MixUiBackend.Mochi -> ui.feice.mochi.basic.MochiBarIconButton(
            onClick = onClick, modifier = buttonModifier, enabled = enabled, content = content,
        )
    }
}

/** Apply native edge feedback to the page's sole scroll container. */
@Composable
fun Modifier.pageScrollFeedback(): Modifier = when (LocalMixUiBackend.current) {
    MixUiBackend.Miuix -> this.scrollEndHaptic().overScrollVertical()
    MixUiBackend.Mochi -> this
}
