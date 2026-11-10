package hk.uwu.mixui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import ui.feice.mochi.effect.mask.mochiScrollEdgeBlur
import ui.feice.mochi.navigation.MochiTitleBarDefaults

private val LocalCompactBarScrolled = staticCompositionLocalOf { false }
private val LocalCompactBarBackdrop = staticCompositionLocalOf<GlassFormBackdrop?> { null }

/**
 * Body-only sampling for a pinned header and its native popovers. Apply the supplied
 * modifier once to the body, never the Scaffold or a parent of the header/popups.
 * This host owns no scroll connection, padding, IME avoidance or focus behavior.
 */
@Composable
fun CompactGlassTopBarScene(
    isContentScrolled: Boolean = false,
    content: @Composable (bodyModifier: Modifier) -> Unit,
) {
    val background = MixTheme.colorScheme.surface
    val backdrop: GlassFormBackdrop
    val body: Modifier
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            val source = if (isRuntimeShaderSupported()) rememberLayerBackdrop() else null
            backdrop = remember(source) { GlassFormBackdrop.Miuix(source) }
            body = (if (source != null) Modifier.layerBackdrop(source) else Modifier).background(background)
        }
        MixUiBackend.Mochi -> {
            val source = rememberMochiBackdrop(background)
            backdrop = remember(source) { GlassFormBackdrop.Mochi(source) }
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + MochiTitleBarDefaults.Height
            // The library's progressive edge blur supplies the actual bar material;
            // the title bar's fade alone would only be a translucent color overlay.
            body = Modifier.mochiBackdropSource(source).mochiScrollEdgeBlur(top, { if (isContentScrolled) 1f else 0f }).background(background)
        }
    }
    Scaffold(containerColor = background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
        GlassPreferenceOverlayHost(backdrop) {
            CompositionLocalProvider(
                LocalCompactBarBackdrop provides backdrop,
                LocalCompactBarScrolled provides isContentScrolled,
                LocalGlassFormBackdrop provides backdrop,
            ) { content(body) }
        }
    }
}

/** Fixed native compact geometry: no collapse state can move the transcript's top padding. */
@Composable
fun CompactGlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val backdrop = checkNotNull(LocalCompactBarBackdrop.current) {
        "CompactGlassTopBar requires CompactGlassTopBarScene"
    }
    val background = MixTheme.colorScheme.surface
    val scrolled = LocalCompactBarScrolled.current
    when (backdrop) {
        is GlassFormBackdrop.Miuix -> {
            // Use the same native progressive background as Home/Configuration,
            // while retaining the compact bar's fixed height and transcript padding.
            val maskProgress by animateFloatAsState(
                targetValue = if (scrolled) 1f else 0f,
                animationSpec = GlassMotion.topBarMask(), label = "compactTopBarMask",
            )
            val rawBackdrop = backdrop.value
            var backgroundCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            // Controls sample body + progressive band, never themselves. This is the
            // same composition used by native GlassTopAppBar(progressiveBlur = true).
            val progressiveBackdrop = if (rawBackdrop != null) rememberLayerBackdrop {
                val coordinates = backgroundCoordinates?.takeIf { it.isAttached }
                with(rawBackdrop) { drawBackdrop(this@rememberLayerBackdrop, coordinates) }
                drawContent()
            } else null
            val controlBackdrop = remember(progressiveBackdrop, backdrop) {
                progressiveBackdrop?.let { GlassFormBackdrop.Miuix(it) } ?: backdrop
            }
            Box(modifier) {
                if (rawBackdrop != null && progressiveBackdrop != null) {
                    val blurColors = BlurDefaults.blurColors(
                        blendColors = listOf(BlendColorEntry(background.copy(alpha = 0.3f))),
                    )
                    Box(Modifier.matchParentSize()
                        .layout { measurable, constraints ->
                            // Extend only the rendered band, not the measured header.
                            val bandHeight = ((constraints.maxHeight + 20.dp.toPx()) * 1.2f).roundToInt()
                            val placeable = measurable.measure(constraints.copy(minHeight = bandHeight, maxHeight = bandHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
                        }
                        .onGloballyPositioned { backgroundCoordinates = it }
                        .layerBackdrop(progressiveBackdrop)
                        .graphicsLayer { alpha = maskProgress }
                        .progressiveTextureBlur(
                            backdrop = rawBackdrop, shape = RectangleShape,
                            gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                            blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                            colors = blurColors,
                        ))
                } else {
                    Box(Modifier.matchParentSize().background(background))
                }
                CompositionLocalProvider(LocalCompactBarBackdrop provides controlBackdrop) {
                    top.yukonga.miuix.kmp.basic.SmallTopAppBar(
                        title = title, color = Color.Transparent,
                        navigationIcon = navigationIcon, actions = actions,
                    )
                }
            }
        }
        is GlassFormBackdrop.Mochi -> BoundedMochiTitleBar(
            title = title, modifier = modifier.windowInsetsPadding(horizontalBarInsets()),
            background = background, backdrop = backdrop.value,
            backgroundProgress = { if (scrolled) 1f else 0f },
            navigationIcon = navigationIcon, actions = actions,
        )
    }
}

/**
 * Native control for the fixed compact bar. Unlike GlassTopAppBar, SmallTopAppBar
 * provides no native glass context. Passing only a backdrop would leave the
 * vendor button permanently floating, including at the transcript's top.
 *
 * Keep one native clickable/focus node mounted and remove all material inputs at
 * rest. surfaceAlpha alone is insufficient: MIUIX still samples/blurs its backdrop
 * at alpha zero. No whole-button alpha is used, so the icon/ring stays legible.
 * Ordinary IconButton children, including those in dialogs, are unaffected.
 */
@Composable
fun CompactTopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = checkNotNull(LocalCompactBarBackdrop.current) {
        "CompactTopBarIconButton requires CompactGlassTopBarScene"
    }
    val scrolled = LocalCompactBarScrolled.current
    val buttonModifier = modifier.size(48.dp).semantics(mergeDescendants = true) {
        role = Role.Button
    }
    when (backdrop) {
        is GlassFormBackdrop.Miuix -> {
            if (backdrop.value != null) {
                top.yukonga.miuix.kmp.glass.GlassIconButton(
                    onClick = onClick,
                    modifier = buttonModifier,
                    inheritTopBarMaterial = false,
                    backdrop = backdrop.value.takeIf { scrolled },
                    size = 48.dp,
                    fill = Color.Transparent,
                    stroke = if (scrolled) top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults.buttonStroke() else null,
                    shadow = if (scrolled) top.yukonga.miuix.kmp.glass.GlassShadows.Regular else null,
                    content = content,
                )
            } else {
                top.yukonga.miuix.kmp.basic.IconButton(
                    onClick = onClick, modifier = buttonModifier,
                    minWidth = 48.dp, minHeight = 48.dp, content = content,
                )
            }
        }
        // BoundedMochiTitleBar supplies the body backdrop and scroll visibility.
        // TopBarIconButton already selects the vendor plain surface at rest.
        is GlassFormBackdrop.Mochi -> {
            // Mochi 0.2.0 owns pointer gestures and accessibility OnClick, but its
            // ButtonCore has no focus target or key handler. Add only keyboard
            // behavior here; another clickable would compete with native gestures.
            var pressedKey by remember { mutableStateOf<Key?>(null) }
            TopBarIconButton(
                onClick = onClick,
                modifier = buttonModifier
                    .onFocusChanged { if (!it.isFocused) pressedKey = null }
                    .onKeyEvent { event ->
                        val activationKey = event.key == Key.Enter ||
                            event.key == Key.NumPadEnter || event.key == Key.DirectionCenter ||
                            event.key == Key.Spacebar
                        if (!activationKey) return@onKeyEvent false
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (pressedKey == null) pressedKey = event.key
                                true
                            }
                            KeyEventType.KeyUp -> {
                                val activate = pressedKey == event.key
                                pressedKey = null
                                if (activate) onClick()
                                true
                            }
                            else -> false
                        }
                    }
                    .focusable(),
                content = content,
            )
        }
    }
}
