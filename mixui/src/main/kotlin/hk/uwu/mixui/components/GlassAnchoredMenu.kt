package hk.uwu.mixui.components

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import hk.uwu.mixui.icons.Icons
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.glassSurface
import ui.feice.mochi.basic.MochiPopoverDefaults
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.MochiGlassSurface
import ui.feice.mochi.theme.MochiTheme

sealed interface GlassAnchoredMenuPlacement {
    data object Default : GlassAnchoredMenuPlacement

    /** Stays in the editor's window and prefers the area above the button. */
    data class AboveAnchor(val bottomInset: Dp = 0.dp) : GlassAnchoredMenuPlacement
}

/** A null [selected] denotes an action rather than a selectable option. */
data class GlassMenuOption(
    val title: String,
    val summary: String? = null,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val dismissOnClick: Boolean = true,
    val dividerBefore: Boolean = false,
    val icon: (@Composable (Modifier) -> Unit)? = null,
)

/**
 * A native glass menu for an existing toolbar button or long-press target.
 * The caller keeps its anchor gestures; this wrapper only measures their bounds.
 * Requires GlassPreferenceScene or one of mixui's glass/large-title page hosts.
 * Popup and outside-touch layers are never part of the recorded page backdrop.
 * [menuContent] preserves specialized row layouts; its caller owns row selection
 * and closes [expanded] before invoking an action, just like the default rows.
 * [GlassAnchoredMenuPlacement.AboveAnchor] uses the standard option rows so the
 * whole menu can be sized before committing to either side of the anchor.
 */
@Composable
fun GlassAnchoredMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<GlassMenuOption>,
    onOptionClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 280.dp,
    maxHeight: Dp? = null,
    menuContent: (@Composable ColumnScope.() -> Unit)? = null,
    placement: GlassAnchoredMenuPlacement = GlassAnchoredMenuPlacement.Default,
    anchor: @Composable () -> Unit,
) {
    val backdrop = checkNotNull(LocalGlassPreferenceBackdrop.current) {
        "GlassAnchoredMenu requires GlassPreferenceScene or a mixui glass/large-title scaffold"
    }
    val active = enabled && (menuContent != null || options.isNotEmpty())
    LaunchedEffect(active) { if (!active && expanded) onExpandedChange(false) }
    val rows: @Composable ColumnScope.() -> Unit = menuContent ?: {
        options.forEachIndexed { index, option ->
            if (option.dividerBefore) Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Spacer(Modifier.fillMaxWidth().height(0.5.dp)
                    .background(MixTheme.colorScheme.outline.copy(alpha = 0.35f)))
            }
            GlassMenuRow(option, enabled = active && expanded && option.enabled) {
                // Ignore input during closing and disabled options, including accessibility.
                if (active && expanded && option.enabled) {
                    if (option.dismissOnClick) onExpandedChange(false)
                    onOptionClick(index)
                }
            }
        }
    }
    if (placement is GlassAnchoredMenuPlacement.AboveAnchor) {
        require(menuContent == null) { "AboveAnchor uses GlassMenuOption rows" }
        // Keep the window inset listener active before the first opening. Reading it
        // only inside the overlay defers the initial system-bar/IME geometry.
        val safeDrawing = WindowInsets.safeDrawing
        val bounds = remember { mutableStateOf(IntRect.Zero) }
        val trackAnchor = remember { Modifier.onLayoutRectChanged(debounceMillis = 0) { bounds.value = it.boundsInWindow } }
        Box(modifier.then(trackAnchor)) { anchor() }
        if (expanded && active) {
            GlassPreferenceOverlay {
                AboveAnchorMenu(backdrop, { bounds.value }, safeDrawing, placement.bottomInset, width, maxHeight,
                    onDismiss = { onExpandedChange(false) }, rows = rows)
            }
        }
        return
    }
    when (backdrop) {
        is GlassFormBackdrop.Miuix -> {
            var bounds by remember { mutableStateOf(Rect.Zero) }
            Box(modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) { anchor() }
            GlassPreferenceOverlay { origin ->
                GlassDropdownPopup(
                    show = expanded && active,
                    onDismissRequest = { onExpandedChange(false) },
                    anchorBounds = bounds.translate(-origin),
                    backdrop = backdrop.value,
                    sizing = GlassPopupSizing(minWidth = minOf(220.dp, width), maxWidth = width,
                        maxHeight = maxHeight ?: GlassPopupSizing().maxHeight),
                    content = rows,
                )
            }
        }
        is GlassFormBackdrop.Mochi -> {
            val materials = ui.feice.mochi.theme.MochiTheme.materials
            val menuGlass = materials.menuGlass
            // Native overlays prefer their inherited window chain over LocalMochiBackdrop.
            // Bind the surface explicitly to our body recording instead. Keep the native
            // popover for placement, outside touch, Back and open/close motion.
            val shellMaterials = if (menuGlass != null) materials.copy(bar = null, menu = null) else materials
            CompositionLocalProvider(
                LocalMochiBackdrop provides backdrop.value,
                ui.feice.mochi.theme.LocalMochiMaterials provides shellMaterials,
            ) {
                ui.feice.mochi.basic.MochiPopoverBox(
                    expanded = expanded && active,
                    onExpandedChange = { onExpandedChange(it && active) },
                    popover = {
                        CompositionLocalProvider(ui.feice.mochi.theme.LocalMochiMaterials provides materials) {
                            val panelContent: @Composable () -> Unit = {
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    if (maxHeight != null) {
                                        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), content = rows)
                                    } else rows()
                                }
                            }
                            if (menuGlass != null) {
                                ui.feice.mochi.effect.glass.MochiGlassSurface(
                                    modifier = Modifier.fillMaxWidth(),
                                    material = menuGlass,
                                    backdrop = backdrop.value,
                                    cornerRadius = ui.feice.mochi.basic.MochiPopoverDefaults.CornerRadius,
                                ) { panelContent() }
                            } else panelContent()
                        }
                    },
                    modifier = modifier,
                    width = width,
                    align = ui.feice.mochi.basic.MochiAlign.End,
                    contentPadding = PaddingValues(0.dp),
                    colors = if (menuGlass != null) ui.feice.mochi.basic.MochiPopoverDefaults.colors(
                        panel = androidx.compose.ui.graphics.Color.Transparent,
                        shadow = androidx.compose.ui.graphics.Color.Transparent,
                    ) else ui.feice.mochi.basic.MochiPopoverDefaults.colors(),
                    anchor = {
                        CompositionLocalProvider(ui.feice.mochi.theme.LocalMochiMaterials provides materials) { anchor() }
                    },
                )
            }
        }
    }
}

@Composable
private fun AboveAnchorMenu(
    backdrop: GlassFormBackdrop,
    anchorBounds: () -> IntRect,
    safeDrawing: WindowInsets,
    bottomInset: Dp,
    width: Dp,
    maxHeight: Dp?,
    onDismiss: () -> Unit,
    rows: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler { dismiss() }
    val view = LocalView.current
    DisposableEffect(view) {
        // Dismiss the menu before the IME handles system Back; don't focus a new window.
        val dispatcher = view.findOnBackInvokedDispatcher()
        val callback = OnBackInvokedCallback { dismiss() }
        dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }

    val windowInfo = LocalWindowInfo.current
    var origin by remember { mutableStateOf<IntOffset?>(null) }
    val trackOrigin = remember { Modifier.onLayoutRectChanged(debounceMillis = 0) { origin = it.boundsInWindow.topLeft } }

    Layout(
        modifier = Modifier.fillMaxSize().then(trackOrigin),
        content = {
            // A sibling of the panel catches taps outside without focusing the page underneath.
            Box(Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } })
            val panelModifier = Modifier.testTag("glass-anchored-menu-above")
                .semantics { this[SemanticsProperties.IsPopup] = Unit }
                .pointerInput(Unit) {
                    // Own even the padded areas of the panel; don't consume taps meant for rows.
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                }
            AboveAnchorMenuSurface(backdrop, panelModifier) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = rows)
            }
        },
    ) { measurables, constraints ->
        val hostWidth = constraints.maxWidth
        val hostHeight = constraints.maxHeight
        val hostOrigin = origin ?: return@Layout layout(hostWidth, hostHeight) {}
        val anchor = anchorBounds()
        val window = windowInfo.containerSize
        val left = maxOf(hostOrigin.x, safeDrawing.getLeft(this, layoutDirection))
        val top = maxOf(hostOrigin.y, safeDrawing.getTop(this))
        val right = minOf(hostOrigin.x + hostWidth, window.width - safeDrawing.getRight(this, layoutDirection))
        val bottom = minOf(hostOrigin.y + hostHeight,
            window.height - maxOf(safeDrawing.getBottom(this), bottomInset.roundToPx()))
        if (minOf(right, anchor.right) <= maxOf(left, anchor.left) ||
            minOf(bottom, anchor.bottom) <= maxOf(top, anchor.top)
        ) return@Layout layout(hostWidth, hostHeight) {}

        val margin = 8.dp.roundToPx()
        val gap = 6.dp.roundToPx()
        val panelWidth = minOf(width.roundToPx(), right - left - margin * 2)
        val above = (anchor.top - gap - top - margin).coerceAtLeast(0)
        val below = (bottom - margin - anchor.bottom - gap).coerceAtLeast(0)
        val heightLimit = maxHeight?.roundToPx() ?: Constraints.Infinity
        if (panelWidth <= 0 || heightLimit <= 0 || maxOf(above, below) == 0) {
            return@Layout layout(hostWidth, hostHeight) {}
        }

        val panel = measurables[1]
        // If below is larger, use it only when the full menu fits there (or there's
        // no room above at all). Otherwise scroll in the preferred upper space.
        val useBelow = above == 0 || (below > above &&
            minOf(heightLimit, panel.maxIntrinsicHeight(panelWidth)).let { it in (above + 1)..below })
        val card = panel.measure(Constraints.fixedWidth(panelWidth).copy(
            maxHeight = minOf(heightLimit, if (useBelow) below else above)))
        val anchorStart = if (layoutDirection == LayoutDirection.Ltr) anchor.left else anchor.right - card.width
        val x = anchorStart.coerceIn(left + margin, right - margin - card.width)
        val y = if (useBelow) anchor.bottom + gap else anchor.top - gap - card.height
        val outside = measurables[0].measure(Constraints.fixed(hostWidth, hostHeight))
        layout(hostWidth, hostHeight) {
            outside.place(0, 0)
            card.place(x - hostOrigin.x, y - hostOrigin.y)
        }
    }
}

@Composable
private fun AboveAnchorMenuSurface(
    backdrop: GlassFormBackdrop,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    when (backdrop) {
        is GlassFormBackdrop.Miuix -> {
            val radius = 24.dp
            val surface = glassSurface(backdrop.value, GlassPopupDefaults.visuals(), radius)(RoundedCornerShape(radius))
            Box(modifier.then(surface)) { content() }
        }
        is GlassFormBackdrop.Mochi -> {
            val material = MochiTheme.materials.menuGlass
            val radius = MochiPopoverDefaults.CornerRadius
            if (material != null) {
                MochiGlassSurface(modifier, material = material, backdrop = backdrop.value, cornerRadius = radius) {
                    content()
                }
            } else {
                val shape = RoundedCornerShape(radius)
                Box(modifier.shadow(10.dp, shape).clip(shape).background(MixTheme.colorScheme.surfaceContainer)) {
                    content()
                }
            }
        }
    }
}

/** A sheet anchor needs its own topmost popup on Miuix; the page overlay sits below the scrim. */
@Composable
fun GlassSheetAnchoredMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<GlassMenuOption>,
    onOptionClick: (Int) -> Unit,
    enabled: Boolean = true,
    maxHeight: Dp = 280.dp,
    anchor: @Composable () -> Unit,
) {
    if (LocalGlassPreferenceBackdrop.current is GlassFormBackdrop.Mochi) {
        GlassAnchoredMenu(expanded, onExpandedChange, options, onOptionClick,
            enabled = enabled, maxHeight = maxHeight, anchor = anchor)
        return
    }
    Box {
        anchor()
        if (expanded && enabled && options.isNotEmpty()) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            val margin = with(LocalDensity.current) { 10.dp.roundToPx() }
            val position = remember(gap, margin) {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                        val w = popupContentSize.width
                        val h = popupContentSize.height
                        val x = anchorBounds.left + (anchorBounds.width - w) / 2
                        val below = anchorBounds.bottom + gap
                        val above = anchorBounds.top - gap - h
                        val y = if (below + h <= windowSize.height - margin) below else above
                        return IntOffset(x.coerceIn(margin, (windowSize.width - w - margin).coerceAtLeast(margin)),
                            y.coerceIn(margin, (windowSize.height - h - margin).coerceAtLeast(margin)))
                    }
                }
            }
            Popup(position, onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true)) {
                val shape = RoundedCornerShape(24.dp)
                Column(Modifier.width(260.dp).heightIn(max = maxHeight).shadow(10.dp, shape).clip(shape)
                    .background(MixTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)) {
                    options.forEachIndexed { index, option ->
                        GlassMenuRow(option, enabled = option.enabled) {
                            if (option.dismissOnClick) onExpandedChange(false)
                            onOptionClick(index)
                        }
                    }
                }
            }
        }
    }
}

/** Register outside recording while retaining the caller's density, direction and window locals. */
@Composable
internal fun GlassPreferenceOverlay(content: @Composable BoxScope.(Offset) -> Unit) {
    val overlays = checkNotNull(LocalPreferenceOverlays.current)
    val token = remember { Any() }
    val locals = currentCompositionLocalContext
    val current by rememberUpdatedState<@Composable BoxScope.(Offset) -> Unit>({ origin ->
        CompositionLocalProvider(locals) { content(origin) }
    })
    DisposableEffect(overlays, token) {
        overlays.entries[token] = { origin -> current(origin) }
        onDispose { overlays.entries.remove(token) }
    }
}

@Composable
private fun GlassMenuRow(option: GlassMenuOption, enabled: Boolean, onClick: () -> Unit) {
    val colors = MixTheme.colorScheme
    val interaction = option.selected?.let {
        Modifier.selectable(selected = it, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } ?: Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().then(interaction).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        option.icon?.invoke(Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(option.title, style = MixTheme.textStyles.body1,
                color = colors.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
            option.summary?.let {
                Text(it, style = MixTheme.textStyles.body2,
                    color = colors.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else 0.4f))
            }
        }
        if (option.selected == true) Icon(Icons.Basic.Check, contentDescription = null,
            tint = colors.primary.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
    }
}
