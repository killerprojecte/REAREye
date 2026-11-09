package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as iosLayerBackdrop
import hk.uwu.mixui.components.iosnavigation.FloatingBottomBarHeight
import hk.uwu.mixui.components.iosnavigation.FloatingBottomBarPressedIndicatorHeight
import hk.uwu.mixui.components.iosnavigation.FloatingBottomBar
import hk.uwu.mixui.components.iosnavigation.FloatingBottomBarItem
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import kotlinx.collections.immutable.toImmutableList
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBarBackdrop

/** No vendor state escapes into the application. Record the page, never the bar or popups. */
class NavigationBackdrop internal constructor(
    internal val ios: com.kyant.backdrop.backdrops.LayerBackdrop? = null,
    internal val miuix: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    internal val mochi: ui.feice.mochi.effect.glass.MochiBackdrop? = null,
)

@Composable
fun rememberNavigationBackdrop(): NavigationBackdrop = when (LocalMixUiBackend.current) {
    MixUiBackend.Miuix -> {
        val surface = MixTheme.colorScheme.surface
        val layer = if (isRuntimeShaderSupported()) rememberLayerBackdrop {
            drawRect(surface)
            drawContent()
        } else null
        val ios = com.kyant.backdrop.backdrops.rememberLayerBackdrop { drawRect(surface); drawContent() }
        remember(layer, ios) { NavigationBackdrop(miuix = layer, ios = ios) }
    }
    MixUiBackend.Mochi -> {
        val layer = rememberMochiBarBackdrop(MixTheme.colorScheme.surface)
        remember(layer) { NavigationBackdrop(mochi = layer) }
    }
}

fun Modifier.navigationBackdrop(backdrop: NavigationBackdrop): Modifier = when {
    backdrop.ios != null -> with(backdrop.ios) {
        val iosModifier = this@navigationBackdrop.iosLayerBackdrop(this)
        if (backdrop.miuix != null) iosModifier.layerBackdrop(backdrop.miuix) else iosModifier
    }
    backdrop.mochi != null -> mochiBackdropSource(backdrop.mochi)
    else -> this
}

enum class NavigationBarStyle { IOS, HyperOS }

enum class NavigationBarMode { Normal, Floating, FloatingGlass, Translucent }

@Immutable
data class NavigationItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    /** Optional screen-reader action; ordinary tab selection stays vendor-native. */
    val secondaryActionLabel: String? = null,
    /** Independent accessibility action, e.g. opening an editor directly. */
    val editActionLabel: String? = null,
)

/** Pixel coordinates in the same root used by onGloballyPositioned.positionInRoot(). */
data class NavigationAnchor(val centerX: Float, val top: Float, val density: Float)

/**
 * Vendor-native navigation with a single inset owner. The caller owns routes and popups.
 * Glass modes sample [backdrop]; Floating deliberately requests a solid capsule.
 * Normal is docked on an opaque surface, Translucent is docked on sampled glass.
 *
 * Secondary gestures keep the old half-long-press/drag contract. Vendor pointer selections
 * are buffered until release so MIUIX's pointer-down callback cannot navigate before a
 * long press opens a menu. Keyboard/TalkBack selections are dispatched immediately.
 */
@Composable
fun NavigationBar(
    items: List<NavigationItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    backdrop: NavigationBackdrop,
    modifier: Modifier = Modifier,
    mode: NavigationBarMode = NavigationBarMode.Normal,
    style: NavigationBarStyle = NavigationBarStyle.IOS,
    shadowVisibilityProgress: Float = 1f,
    onSecondaryAction: (String, NavigationAnchor) -> Unit = { _, _ -> },
    onSecondaryDrag: (Offset) -> Unit = {},
    onSecondaryEnd: (cancelled: Boolean) -> Unit = {},
    onEditAction: (String) -> Unit = {},
) {
    if (items.isEmpty()) return
    require(items.map { it.key }.distinct().size == items.size) { "Navigation keys must be unique" }
    val backend = LocalMixUiBackend.current
    val floating = mode == NavigationBarMode.Floating || mode == NavigationBarMode.FloatingGlass
    val glass = mode == NavigationBarMode.FloatingGlass || mode == NavigationBarMode.Translucent
    val density = LocalDensity.current
    val ambientDirection = LocalLayoutDirection.current
    // Mochi 0.2.0 uses physical LTR pointer coordinates. Keep BOTH native renderers
    // LTR, and explicitly reverse their data for RTL instead of mismatching hit tests.
    val renderedItems = if (backend == MixUiBackend.Mochi && ambientDirection == LayoutDirection.Rtl) {
        items.asReversed()
    } else items
    val direction = if (backend == MixUiBackend.Mochi) LayoutDirection.Ltr else ambientDirection
    val selectedIndex = renderedItems.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    var nativeEpoch by remember(backend, mode, style) { mutableIntStateOf(0) }
    val dispatch by rememberUpdatedState(onSelect)
    val secondary by rememberUpdatedState(onSecondaryAction)
    val drag by rememberUpdatedState(onSecondaryDrag)
    val end by rememberUpdatedState(onSecondaryEnd)
    val edit by rememberUpdatedState(onEditAction)
    val inset = when {
        backend == MixUiBackend.Mochi -> 4.dp
        mode == NavigationBarMode.Normal -> 0.dp
        style == NavigationBarStyle.IOS -> 4.dp
        mode != NavigationBarMode.Normal -> 8.dp
        else -> 0.dp
    }
    val horizontalInset = with(density) { inset.toPx() }
    // Every pointerInput restart must receive a fresh gate. The old coroutine's
    // finally block retires its bridge, including when geometry changes mid-press.
    val bridge = remember(backend, mode, style, renderedItems, horizontalInset, direction, density) {
        NavigationGestureBridge()
    }
    val activeBridge by rememberUpdatedState(bridge)
    DisposableEffect(bridge) { onDispose { bridge.retired = true } }
    // Rejected gestures also recreate the native subtree without replacing its
    // outer gesture adapter. Ignore any callback from that disposed native instance.
    val nativeInstance = remember(bridge, nativeEpoch) { Any() }
    val activeNativeInstance by rememberUpdatedState(nativeInstance)
    val select: (Int) -> Unit = { index ->
        if (bridge === activeBridge && !bridge.retired &&
            nativeInstance === activeNativeInstance && index in renderedItems.indices) {
            bridge.selection.select(index)?.let { dispatch(renderedItems[it].key) }
        }
    }
    val navigationPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val captionPadding = WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
    val barModifier = Modifier
        .onGloballyPositioned { bridge.origin = it.positionInRoot(); bridge.width = it.size.width.toFloat() }
        .semantics {
            customActions = renderedItems.flatMapIndexed { index, item ->
                buildList {
                    item.secondaryActionLabel?.let { label ->
                        add(CustomAccessibilityAction(label) {
                            val visualIndex = if (direction == LayoutDirection.Rtl) renderedItems.lastIndex - index else index
                            val span = (bridge.width - 2 * horizontalInset) / renderedItems.size
                            secondary(item.key, NavigationAnchor(
                                bridge.origin.x + horizontalInset + span * (visualIndex + 0.5f),
                                bridge.origin.y, density.density,
                            ))
                            true
                        })
                    }
                    item.editActionLabel?.let { label ->
                        add(CustomAccessibilityAction(label) { edit(item.key); true })
                    }
                }
            }
        }
        .pointerInput(bridge, renderedItems, horizontalInset, direction) {
            // The IOS glass lens grows beyond its layout while pressed. A sequence that
            // began on the bar may continue inside that visible lens; it must not be
            // cancelled by the smaller unscaled layout. HyperOS shrinks its indicator,
            // and non-glass/normal bars do not render this expansion.
            val verticalOverflow = if (backend == MixUiBackend.Miuix &&
                style == NavigationBarStyle.IOS && glass
            ) ((FloatingBottomBarPressedIndicatorHeight - FloatingBottomBarHeight) / 2).toPx() else 0f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                bridge.selection.begin()
                // Ancestors may own an opening/page drag while the native bar still receives
                // the hit path. Reject its callbacks, but never consume the owner's next event.
                var ancestorOwnsGesture = down.isConsumed
                if (ancestorOwnsGesture) bridge.selection.cancel()
                var secondaryStarted = false
                var secondaryFinished = false
                var secondaryLeftBar = false
                var pointerEnded = false
                var pointersRemain = true
                var completed = false
                try {
                    val x = if (direction == LayoutDirection.Rtl) size.width - down.position.x else down.position.x
                    val span = (size.width - horizontalInset * 2) / renderedItems.size
                    val index = if (span > 0f) ((x - horizontalInset) / span).toInt().coerceIn(renderedItems.indices) else 0
                    var releaseIndex = index
                    val canOpen = !ancestorOwnsGesture && renderedItems[index].secondaryActionLabel != null
                    val timeout = maxOf(180L, viewConfiguration.longPressTimeoutMillis / 2)
                    val held = canOpen && withTimeoutOrNull(timeout) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            pointersRemain = event.changes.any { it.pressed }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                bridge.selection.cancel()
                                pointerEnded = true
                            } else {
                                // Initial-pass consumption belongs to an ancestor (or system CANCEL).
                                if (change.isConsumed) {
                                    ancestorOwnsGesture = true
                                    bridge.selection.cancel()
                                }
                                bridge.selection.move(
                                    beyondSlop = (change.position - down.position).getDistance() > viewConfiguration.touchSlop,
                                    horizontalDrag = kotlin.math.abs(change.position.x - down.position.x) > kotlin.math.abs(change.position.y - down.position.y),
                                    outside = change.position.x !in 0f..size.width.toFloat() ||
                                        change.position.y !in -verticalOverflow..(size.height + verticalOverflow),
                                    multiplePointers = event.changes.count { it.pressed } > 1,
                                )
                                val releaseX = if (direction == LayoutDirection.Rtl) size.width - change.position.x else change.position.x
                                releaseIndex = if (span > 0f) ((releaseX - horizontalInset) / span).toInt().coerceIn(renderedItems.indices) else index
                                pointerEnded = !change.pressed
                                if (!ancestorOwnsGesture && bridge.selection.intent == NavigationSelectionGate.Intent.Cancelled) change.consume()
                            }
                            awaitPointerEvent(PointerEventPass.Final)
                            if (pointerEnded || bridge.selection.intent == NavigationSelectionGate.Intent.Cancelled || bridge.selection.intent == NavigationSelectionGate.Intent.Drag) {
                                return@withTimeoutOrNull false
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } == null
                    if (held && bridge.selection.openSecondary()) {
                        secondaryStarted = true
                        secondary(renderedItems[index].key, NavigationAnchor(
                            bridge.origin.x + horizontalInset + span * (
                                (if (direction == LayoutDirection.Rtl) renderedItems.lastIndex - index else index) + 0.5f
                            ), bridge.origin.y, density.density,
                        ))
                        drag(bridge.origin + down.position)
                    }
                    // Drain even a cancelled sequence to its final release. A native control
                    // can call onSelect after its own release animation has already started.
                    while (!pointerEnded || pointersRemain) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        pointersRemain = event.changes.any { it.pressed }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            bridge.selection.cancel()
                            pointerEnded = true
                        } else {
                            // Initial-pass consumption belongs to an ancestor (or system CANCEL).
                            if (change.isConsumed) {
                                ancestorOwnsGesture = true
                                bridge.selection.cancel()
                            }
                            bridge.selection.move(
                                beyondSlop = (change.position - down.position).getDistance() > viewConfiguration.touchSlop,
                                    horizontalDrag = kotlin.math.abs(change.position.x - down.position.x) > kotlin.math.abs(change.position.y - down.position.y),
                                outside = change.position.x !in 0f..size.width.toFloat() ||
                                    change.position.y !in -verticalOverflow..(size.height + verticalOverflow),
                                multiplePointers = event.changes.count { it.pressed } > 1,
                            )
                            val releaseX = if (direction == LayoutDirection.Rtl) size.width - change.position.x else change.position.x
                            releaseIndex = if (span > 0f) ((releaseX - horizontalInset) / span).toInt().coerceIn(renderedItems.indices) else index
                            if (bridge.selection.intent == NavigationSelectionGate.Intent.Secondary) {
                                val insideBar = change.position.x in 0f..size.width.toFloat() &&
                                    change.position.y in -verticalOverflow..(size.height + verticalOverflow)
                                if (!insideBar) secondaryLeftBar = true
                                val delta = change.position - down.position
                                // A horizontal move to another tab means navigation, even after
                                // holding Config. Once the finger leaves for the menu, retain
                                // secondary ownership so its upward selection contract survives.
                                if (!secondaryLeftBar && releaseIndex != index &&
                                    kotlin.math.abs(delta.x) > viewConfiguration.touchSlop &&
                                    kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y) &&
                                    bridge.selection.resumeNavigationDrag()
                                ) {
                                    secondaryFinished = true
                                    end(true)
                                } else {
                                    drag(bridge.origin + change.position)
                                }
                            }
                            if (!ancestorOwnsGesture && (bridge.selection.intent == NavigationSelectionGate.Intent.Secondary || bridge.selection.intent == NavigationSelectionGate.Intent.Cancelled)) change.consume()
                            pointerEnded = !change.pressed
                        }
                        awaitPointerEvent(PointerEventPass.Final)
                    }
                    val cancelled = bridge.selection.intent == NavigationSelectionGate.Intent.Cancelled
                    if (secondaryStarted && !secondaryFinished) {
                        secondaryFinished = true
                        end(cancelled)
                    }
                    val wasDrag = bridge.selection.intent == NavigationSelectionGate.Intent.Drag
                    if (wasDrag) bridge.selection.select(releaseIndex)
                    val result = bridge.selection.finish()
                    completed = true
                    // A rejected native selection can leave Mochi's private indicator at the
                    // wrong target. Recreate only the native subtree AFTER Main and gate finish.
                    val correctStaleMochiTarget = backend == MixUiBackend.Mochi &&
                        result.selectedIndex != null && result.selectedIndex != (if (wasDrag) releaseIndex else index)
                    if (result.resetNative || correctStaleMochiTarget) nativeEpoch++
                    result.selectedIndex?.let { nativeIndex ->
                        // Mochi glass press schedules its target asynchronously. A down/up
                        // in one input batch may therefore report the previous tab. For a
                        // valid, uncancelled Tap the pressed cell is authoritative; keep
                        // native callbacks for keyboard/accessibility and all native motion.
                        val target = if (wasDrag) releaseIndex else if (backend == MixUiBackend.Mochi) index else nativeIndex
                        dispatch(renderedItems[target].key)
                    }
                } finally {
                    if (secondaryStarted && !secondaryFinished) end(true)
                    if (!completed) {
                        bridge.retired = true
                        bridge.selection.cancel()
                        if (bridge.selection.finish().resetNative) nativeEpoch++
                    }
                }
            }
        }
    Box(
        modifier.fillMaxWidth()
            .background(if (mode == NavigationBarMode.Normal) MixTheme.colorScheme.surface else Color.Transparent)
            .padding(bottom = navigationPadding + captionPadding + if (floating) 12.dp else 0.dp)
            .padding(horizontal = if (floating) 16.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Bound Mochi's own capsule to the same measured area as the gesture adapter.
        val nativeModifier = if (backend == MixUiBackend.Mochi) {
            Modifier.widthIn(max = (renderedItems.size * 92 + 8).dp).then(barModifier)
        } else barModifier
        Box(nativeModifier, propagateMinConstraints = true) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                key(bridge, nativeEpoch) {
                    when (backend) {
                        MixUiBackend.Miuix -> if (mode == NavigationBarMode.Normal) {
                            top.yukonga.miuix.kmp.basic.NavigationBar(
                                modifier = Modifier,
                                defaultWindowInsetsPadding = false,
                            ) {
                                renderedItems.forEachIndexed { index, item ->
                                    MiuixNavigationBarItem(
                                        selected = index == selectedIndex, onClick = { select(index) },
                                        icon = item.icon, label = item.label,
                                    )
                                }
                            }
                        } else if (style == NavigationBarStyle.IOS) {
                            FloatingBottomBar(
                                selectedIndex = selectedIndex, onSelected = select,
                                backdrop = requireNotNull(backdrop.ios), tabsCount = renderedItems.size,
                                isBlurEnabled = glass, shadowVisibilityProgress = shadowVisibilityProgress,
                            ) {
                                renderedItems.forEachIndexed { index, item ->
                                    FloatingBottomBarItem(
                                        onClick = { select(index) },
                                        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                                    ) {
                                        Icon(item.icon, contentDescription = item.label, tint = MixTheme.colorScheme.onSurface)
                                        Text(item.label, fontSize = 11.sp, lineHeight = 14.sp,
                                            color = MixTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        } else {
                            val shadow = top.yukonga.miuix.kmp.glass.GlassShadows.Float
                            top.yukonga.miuix.kmp.glass.GlassNavigationBar(
                                modifier = Modifier,
                                items = renderedItems.map { top.yukonga.miuix.kmp.glass.GlassNavigationItem(it.icon, it.label, it.label) },
                                selectedIndex = selectedIndex, onSelect = select,
                                backdrop = if (glass) backdrop.miuix else null,
                                defaultWindowInsetsPadding = false,
                                shape = if (floating) top.yukonga.miuix.kmp.glass.GlassShape(
                                    CornerSize(50), CornerSize(50), CornerSize(50), CornerSize(50),
                                ) else top.yukonga.miuix.kmp.glass.GlassShape(
                                    CornerSize(0), CornerSize(0), CornerSize(0), CornerSize(0),
                                ),
                                shadow = if (floating) shadow.copy(color = shadow.color.copy(
                                    alpha = shadow.color.alpha * shadowVisibilityProgress.coerceIn(0f, 1f),
                                )) else null,
                            )
                        }
                        MixUiBackend.Mochi -> ui.feice.mochi.basic.MochiTabBar(
                            modifier = Modifier,
                            selectedIndex = selectedIndex, onSelect = select, onReselect = select,
                            items = renderedItems.map { item ->
                                ui.feice.mochi.basic.MochiTabItem(label = item.label) { tint ->
                                    Icon(imageVector = item.icon, contentDescription = null, tint = tint)
                                }
                            }.toImmutableList(),
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            material = if (glass) ui.feice.mochi.theme.MochiTheme.materials.bar else null,
                            backdrop = if (glass) backdrop.mochi else null,
                            colors = ui.feice.mochi.basic.MochiTabBarDefaults.colors().let { colors ->
                                colors.copy(shadow = colors.shadow.copy(
                                    alpha = colors.shadow.alpha * shadowVisibilityProgress.coerceIn(0f, 1f),
                                ))
                            },
                        )
                    }
                }
            }
        }
    }
}

private class NavigationGestureBridge {
    var retired = false
    val selection = NavigationSelectionGate()
    var origin = Offset.Zero
    var width = 0f
}
