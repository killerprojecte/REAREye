package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.glassSurface
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import ui.feice.mochi.effect.glass.MochiGlassSurface
import ui.feice.mochi.theme.MochiTheme

/**
 * Native menu optics over the navigation host's page-only recording, in every bar mode.
 * The caller owns size, positioning, animation, hover and click/drag arbitration.
 * Never put navigationBackdrop on this surface or its Popup/scrim ancestor.
 */
@Composable
fun NavigationQuickActionSurface(
    backdrop: NavigationBackdrop,
    modifier: Modifier = Modifier,
    hovered: Boolean = false,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val backend = LocalMixUiBackend.current
    val mochiMaterial = if (backend == MixUiBackend.Mochi) MochiTheme.materials.menuGlass else null
    val sampled = glassEnabled && isRuntimeShaderSupported() && when (backend) {
        MixUiBackend.Miuix -> backdrop.miuix != null
        MixUiBackend.Mochi -> backdrop.mochi != null && mochiMaterial != null
    }
    // Multiple capsules share one recording, retained through the exit animation.
    DisposableEffect(backdrop, sampled) {
        if (sampled) backdrop.quickActionConsumers++
        onDispose { if (sampled) backdrop.quickActionConsumers-- }
    }
    val shape = RoundedCornerShape(50)
    val foreground: @Composable BoxScope.() -> Unit = {
        if (hovered) Box(Modifier.matchParentSize().background(
            MixTheme.colorScheme.primary.copy(alpha = 0.14f), shape))
        content()
    }
    when (backend) {
        MixUiBackend.Miuix -> {
            val surface = glassSurface(
                backdrop = backdrop.miuix.takeIf { sampled },
                visuals = GlassPopupDefaults.visuals(),
                cornerRadius = 20.dp,
            )(shape)
            Box(modifier.then(surface), contentAlignment = Alignment.Center, content = foreground)
        }
        MixUiBackend.Mochi -> {
            if (sampled && mochiMaterial != null) {
                MochiGlassSurface(modifier = modifier, backdrop = backdrop.mochi,
                    material = mochiMaterial, cornerRadius = 20.dp,
                    contentAlignment = Alignment.Center, content = foreground)
            } else {
                Box(modifier.clip(shape).background(MixTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center, content = foreground)
            }
        }
    }
}

/**
 * Keep a Popup (and its independent input window), but give its root the page window's
 * origin. Both vendors' public LayerBackdrop APIs use positionInWindow across roots;
 * a tightly sized Popup otherwise samples the top-left of the page, not its anchor.
 * Only the transparent viewport is full-window: [positionProvider] still places the
 * original measured capsule group without changing its size, spacing or hit bounds.
 */
@Composable
fun NavigationQuickActionPopup(
    positionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    var popupAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var menuBounds by remember { mutableStateOf(Rect.Zero) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val viewportPosition = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                if (popupAnchorBounds != anchorBounds) popupAnchorBounds = anchorBounds
                return IntOffset.Zero
            }
        }
    }
    Popup(
        popupPositionProvider = viewportPosition,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = false, dismissOnBackPress = true,
            dismissOnClickOutside = true, clippingEnabled = false),
    ) {
        Layout(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!menuBounds.contains(down.position)) {
                        down.consume()
                        dismiss()
                    }
                }
            },
            content = { Box { content() } },
        ) { measurables, constraints ->
            val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val window = IntSize(constraints.maxWidth, constraints.maxHeight)
            val offset = positionProvider.calculatePosition(popupAnchorBounds, window, layoutDirection,
                IntSize(menu.width, menu.height))
            menuBounds = Rect(offset.x.toFloat(), offset.y.toFloat(),
                (offset.x + menu.width).toFloat(), (offset.y + menu.height).toFloat())
            layout(window.width, window.height) { menu.place(offset.x, offset.y) }
        }
    }
}
