package hk.uwu.mixui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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

/** Allows a keep-alive page host to attach native scroll only to its selected page. */
val LocalPageTopBarScrollConnection = staticCompositionLocalOf<NestedScrollConnection?> { null }

// Controls inside the bar keep its native motion even when using their own material.
private val LocalInheritMiuixBarMaterial = staticCompositionLocalOf { true }

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
 * [inheritMiuixBarMaterial] keeps the native action-bar underlay by default. When
 * false, buttons use their own native glass material and [pinnedHeader] is hosted
 * outside the bar's material context. Both still sample the page-only backdrop
 * and follow the same scroll visibility. [topBarModifier] covers the complete
 * bar and pinned header in this mode. Mochi and solid fallbacks are unchanged.
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
    contentListState: LazyListState? = null,
    inheritMiuixBarMaterial: Boolean = true,
    attachNestedScrollToContent: Boolean = true,
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
            // Scope native collapse to the active list (including manager sub-tabs).
            // Do not equate a short list's zero offset with a request to expand: native
            // pre-scroll can legitimately collapse the bar before the list moves.
            val nativeState = key(contentListState) {
                top.yukonga.miuix.kmp.basic.rememberTopAppBarState()
            }
            val scrollBehavior = rememberMiuixPageScrollBehavior(state = nativeState)
            LaunchedEffect(contentListState, nativeState) {
                val list = contentListState ?: return@LaunchedEffect
                // Wait for the vendor bar to measure its finite collapse range. Restore
                // a previously scrolled list only on selection, never on every idle frame.
                snapshotFlow { nativeState.heightOffsetLimit }.first { it > -Float.MAX_VALUE }
                if (list.canScrollBackward) {
                    nativeState.heightOffset = nativeState.heightOffsetLimit
                    nativeState.contentOffset = -1f
                }
            }
            // Native pre-scroll can collapse the bar before a short list moves.
            // Material must follow that motion as well as the list's own offset.
            val materialVisible = isContentScrolled || nativeState.heightOffset < 0f
            val supported = isRuntimeShaderSupported()
            val backdrop = if (supported) rememberMiuixScrollBackdrop {
                nativeState.heightOffset
                nativeState.contentOffset
                contentListState?.firstVisibleItemIndex
                contentListState?.firstVisibleItemScrollOffset
            } else null
            val formBackdrop = if (supported) rememberLayerBackdrop() else null
            val headerOutsideBar = backdrop != null && !inheritMiuixBarMaterial
            val tabSurfaceAlpha = animateFloatAsState(
                targetValue = if (materialVisible) 1f else 0f,
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
                        // A detached header still belongs to the same measured toolbar/guide
                        // region. Apply the caller modifier once, around both native pieces.
                        Column(modifier = if (headerOutsideBar) topBarModifier else Modifier) {
                            if (backdrop != null) {
                                top.yukonga.miuix.kmp.glass.GlassTopAppBar(
                                    title = title,
                                    modifier = if (headerOutsideBar) Modifier else topBarModifier,
                                    isContentScrolled = materialVisible,
                                    backdrop = backdrop,
                                    // Use the native progressive background. Controls may select their
                                    // own material while retaining the bar's scroll motion.
                                    progressiveBlur = true,
                                    scrollBehavior = scrollBehavior,
                                    navigationIcon = {
                                        CompositionLocalProvider(LocalInheritMiuixBarMaterial provides inheritMiuixBarMaterial) {
                                            navigationIcon()
                                        }
                                    },
                                    actions = {
                                        val rowScope = this
                                        CompositionLocalProvider(LocalInheritMiuixBarMaterial provides inheritMiuixBarMaterial) {
                                            actions(rowScope)
                                        }
                                    },
                                    // The native joined track has no public inheritance opt-out.
                                    // Keep it here only when bar material inheritance is requested.
                                    bottomContent = {
                                        if (!headerOutsideBar) CompositionLocalProvider(
                                            LocalGlassTabsSurfaceAlpha provides tabSurfaceAlpha.value,
                                            LocalGlassTabsBackdrop provides backdrop,
                                        ) { pinnedHeader() }
                                    },
                                )
                                if (headerOutsideBar) {
                                    // Leave the vendor's internal context through public composition,
                                    // retaining the original native joined track and horizontal insets.
                                    Box(
                                        Modifier.windowInsetsPadding(horizontalBarInsets())
                                            .pointerInput(Unit) {
                                                // Preserve the native bar's blank-area click shield
                                                // without replacing its child controls' gestures.
                                                detectTapGestures { }
                                            },
                                    ) {
                                        CompositionLocalProvider(
                                            LocalGlassTabsSurfaceAlpha provides tabSurfaceAlpha.value,
                                            LocalGlassTabsBackdrop provides backdrop,
                                        ) { pinnedHeader() }
                                    }
                                }
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
                            .then(if (attachNestedScrollToContent) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop.source) else Modifier)
                            .background(background),
                    ) {
                        CompositionLocalProvider(LocalPageTopBarScrollConnection provides scrollBehavior.nestedScrollConnection) {
                            content(padding)
                        }
                    }
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
                            BoundedMochiTitleBar(
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
            GlassPreferenceOverlayHost(backdrop = backdrop) {
                page(Modifier.fillMaxSize().then(sceneModifier))
            }
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
 * material policy supplied by the bar; no duplicate backdrop is passed here.
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
                    onClick = onClick, modifier = buttonModifier,
                    inheritTopBarMaterial = LocalInheritMiuixBarMaterial.current,
                    content = content,
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
        MixUiBackend.Mochi -> {
            val colors = ui.feice.mochi.basic.MochiButtonDefaults.barColors()
            // Mochi 0.2.0's backgroundProgress fades only the edge/shadow, not the
            // button glass. Its public colors override selects the native plain
            // surface while retaining the same control, size, icon and gestures.
            // Never fade the entire button: the icon must remain visible at top.
            val hideMaterial = !LocalMochiTopBarMaterialVisible.current &&
                ui.feice.mochi.theme.MochiTheme.materials.controlGlass != null
            ui.feice.mochi.basic.MochiBarIconButton(
                onClick = onClick,
                modifier = buttonModifier,
                enabled = enabled,
                colors = if (hideMaterial) colors.copy(
                    container = Color.Transparent,
                    disabledContainer = Color.Transparent,
                    outline = Color.Transparent,
                ) else colors,
                content = content,
            )
        }
    }
}

/** Shared vertical gesture contract, independent of the selected visual backend.
 * Apply once to the page's sole scroll container. Lazy containers use Compose's
 * default fling and overscrollEffect=null: this modifier supplies the elastic
 * edge response and end haptic instead of stacking a second overscroll effect.
 */
@Composable
fun Modifier.pageScrollFeedback(): Modifier = this.scrollEndHaptic().overScrollVertical()
