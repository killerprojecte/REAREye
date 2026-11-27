package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.icons.Icons
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.MochiGlassSurface
import ui.feice.mochi.theme.MochiTheme
import kotlin.math.roundToInt

private val SuggestionCardRadius = 20.dp
private val SuggestionHighlightHorizontalInset = 6.dp
private val SuggestionHighlightVerticalInset = 4.dp
private val SuggestionHighlightRadius = SuggestionCardRadius - SuggestionHighlightHorizontalInset

/**
 * Editable, non-focusable suggestions: prefer a shorter panel BELOW the field,
 * using a same-window glass overlay above its sheet. Neither the keyboard nor
 * input focus belongs to the suggestions. The caller owns Back handling.
 */
@Composable
fun SuggestionMenu(
    expanded: Boolean,
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    boundsInWindow: IntRect = IntRect.Zero,
    width: Dp = Dp.Unspecified,
    maxHeight: Dp = 200.dp,
    optionSummaries: List<String?> = emptyList(),
    onVisibilityChanged: (Boolean) -> Unit = {},
    anchor: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val selectNow by rememberUpdatedState(onSelectedIndexChange)
    val dismissNow by rememberUpdatedState(onDismissRequest)
    val expandedNow by rememberUpdatedState(expanded)
    val direction = LocalLayoutDirection.current
    val window = LocalWindowInfo.current.containerSize
    val safe = WindowInsets.safeDrawing
    val imeBottom = WindowInsets.ime.getBottom(density)
    val windowBounds = IntRect(safe.getLeft(density, direction), safe.getTop(density),
        (window.width - safe.getRight(density, direction)).coerceAtLeast(0),
        (window.height - maxOf(imeBottom, safe.getBottom(density))).coerceAtLeast(0))
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    val gap = with(density) { 6.dp.roundToPx() }
    val clipBounds = suggestionViewport(windowBounds, boundsInWindow, with(density) { 8.dp.roundToPx() })
    val minimumHeight = with(density) { 64.dp.roundToPx() }
    val availableHeight = suggestionAvailableHeight(anchorBounds, clipBounds,
        with(density) { maxHeight.roundToPx() }, minimumHeight, gap)
    val popupWidth = with(density) {
        (if (width.isSpecified) width.roundToPx() else anchorBounds.width)
            .coerceIn(0, clipBounds.width.coerceAtLeast(0)).toDp()
    }
    val popupHeight = with(density) { availableHeight.toDp() }
    val bringIntoView = remember { BringIntoViewRequester() }
    // Reserve a useful candidate area on focus/IME changes, not on every letter
    // or every anchor move. A small viewport can still fall back above the field.
    LaunchedEffect(expanded, imeBottom, clipBounds.top, clipBounds.bottom) {
        if (expanded && options.isNotEmpty()) {
            withFrameNanos { }
            if (anchorBounds.width > 0 && anchorBounds.height > 0) {
                bringIntoView.bringIntoView(Rect(0f, 0f, anchorBounds.width.toFloat(),
                    (anchorBounds.height + gap + minimumHeight * 2).toFloat()))
            }
        }
    }
    Box(modifier.testTag("suggestion-menu-anchor").bringIntoViewRequester(bringIntoView)
        .onGloballyPositioned { anchorBounds = it.boundsInWindow().roundToIntRect() }) { anchor() }

    val visible = expanded && suggestionAnchorVisible(anchorBounds, clipBounds) &&
        options.isNotEmpty() && popupWidth > 0.dp && popupHeight > 0.dp
    val visibilityCallback by rememberUpdatedState(onVisibilityChanged)
    DisposableEffect(visible) {
        visibilityCallback(visible)
        onDispose { visibilityCallback(false) }
    }
    if (!visible) return
    val host = LocalSuggestionOverlays.current
    val panel: @Composable (Modifier, Boolean) -> Unit = { panelModifier, allowGlass ->
        SuggestionCard(panelModifier.testTag("glass-suggestion-menu")
            .semantics { this[SemanticsProperties.IsPopup] = Unit }, allowGlass) {
            val list = rememberLazyListState()
            LaunchedEffect(options, selectedIndex) {
                list.scrollToItem(selectedIndex.coerceIn(0, options.lastIndex))
            }
            // A title is stable across filtering. Occurrence disambiguates callers
            // that deliberately expose duplicate labels without changing their indices.
            val rowKeys = remember(options) {
                val occurrences = HashMap<String, Int>()
                options.map { title ->
                    val occurrence = occurrences[title] ?: 0
                    occurrences[title] = occurrence + 1
                    title to occurrence
                }
            }
            LazyColumn(Modifier.width(popupWidth).heightIn(max = popupHeight)
                .testTag("glass-suggestion-options"), state = list,
                contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(options, key = { index, _ -> rowKeys[index] }) { index, title ->
                    val selected = selectedIndex == index
                    val primary = MixTheme.colorScheme.primary
                    val highlight = if (selected) Modifier.drawBehind {
                        // Inset only the paint: keep the full row tappable and the text aligned.
                        val horizontal = SuggestionHighlightHorizontalInset.toPx()
                        val vertical = SuggestionHighlightVerticalInset.toPx()
                        val width = size.width - 2 * horizontal
                        val height = size.height - 2 * vertical
                        if (width > 0f && height > 0f) {
                            drawRoundRect(
                                color = primary.copy(alpha = 0.08f),
                                topLeft = Offset(horizontal, vertical),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(SuggestionHighlightRadius.toPx()),
                            )
                        }
                    } else Modifier
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .focusProperties { canFocus = false }
                        .selectable(selected = selected, role = Role.RadioButton) {
                            if (expandedNow) selectNow(index)
                        }
                        .then(highlight)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, color = if (selected) primary else MixTheme.colorScheme.onSurface,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            optionSummaries.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MixTheme.textStyles.footnote1,
                                    color = MixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (selected) Icon(Icons.Basic.Check, contentDescription = null,
                            tint = primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    if (host != null) {
        // LayerBackdrop's different-window fallback lacks popup window origins.
        // Keep the real glass and its source in the same root instead of faking a
        // backdrop in an Android Popup, and never record this overlay itself.
        // The host forwards the latest content/locals without replacing its token.
        // Keep panel, list and measurement identity through query and IME updates.
        SuggestionOverlay {
            var origin by remember { mutableStateOf(Offset.Zero) }
            var viewportSize by remember { mutableStateOf(IntSize.Zero) }
            var size by remember { mutableStateOf(IntSize.Zero) }
            var menuBounds by remember { mutableStateOf(IntRect.Zero) }
            Box(Modifier.fillMaxSize().onGloballyPositioned {
                origin = it.positionInWindow()
                viewportSize = it.size
            })
            // Only the four OUTSIDE regions share input with the underlying
            // sheet. A full-window sharing ancestor would let candidate taps
            // reach an editor behind the menu and steal focus before UP.
            if (menuBounds != IntRect.Zero && viewportSize.width > 0) {
                val left = (menuBounds.left - origin.x.roundToInt()).coerceIn(0, viewportSize.width)
                val right = (menuBounds.right - origin.x.roundToInt()).coerceIn(left, viewportSize.width)
                val top = (menuBounds.top - origin.y.roundToInt()).coerceIn(0, viewportSize.height)
                val bottom = (menuBounds.bottom - origin.y.roundToInt()).coerceIn(top, viewportSize.height)
                val outside = listOf(
                    IntRect(0, 0, viewportSize.width, top),
                    IntRect(0, bottom, viewportSize.width, viewportSize.height),
                    IntRect(0, top, left, bottom),
                    IntRect(right, top, viewportSize.width, bottom),
                )
                outside.forEach { area ->
                    if (area.width > 0 && area.height > 0) {
                        Box(Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft)
                            .absoluteOffset { IntOffset(area.left, area.top) }
                            .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
                            .observeSuggestionOutsideTap { position ->
                                val point = IntOffset((position.x + area.left + origin.x).roundToInt(),
                                    (position.y + area.top + origin.y).roundToInt())
                                if (!anchorBounds.contains(point)) dismissNow()
                            })
                    }
                }
            }
            panel(Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft)
                .absoluteOffset {
                    val offset = suggestionPosition(anchorBounds, clipBounds, size, direction, gap)
                    IntOffset(offset.x - origin.x.roundToInt(), offset.y - origin.y.roundToInt())
                }
                .onSizeChanged { size = it }
                .onGloballyPositioned { menuBounds = it.boundsInWindow().roundToIntRect() }, true)
        }
    } else {
        // Standalone editors without a same-window source keep a solid popup.
        // It still never takes the editor's focus or dismisses the IME.
        val currentAnchor by rememberUpdatedState(anchorBounds)
        val position = remember(clipBounds, gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                    suggestionPosition(currentAnchor, clipBounds, popupContentSize, layoutDirection, gap)
            }
        }
        Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = false, dismissOnBackPress = false,
                dismissOnClickOutside = true)) { panel(Modifier, false) }
    }
}

@Composable
private fun SuggestionCard(modifier: Modifier, allowGlass: Boolean, content: @Composable () -> Unit) {
    val fallback = MixTheme.colorScheme.surfaceContainerHigh
    Box(modifier) {
        when (LocalMixUiBackend.current) {
            MixUiBackend.Miuix -> {
                val backdrop = if (allowGlass) LocalGlassSheetPopupBackdrop.current
                    ?: (LocalGlassFormBackdrop.current as? GlassFormBackdrop.Miuix)?.value
                    ?: (LocalGlassPreferenceBackdrop.current as? GlassFormBackdrop.Miuix)?.value else null
                val dark = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background.luminance() < 0.5f
                val shape = GlassShape(SuggestionCardRadius)
                Box(Modifier.testTag(if (backdrop != null) "glass-suggestion-miuix" else "solid-suggestion-card")
                    .glassPanel(backdrop = backdrop, shape = shape, style = GlassDefaults.style(dark),
                        material = modalGlassMaterial(), stroke = GlassEdgeStrokes.forTheme(dark),
                        shadow = GlassShadows.Float, shading = false,
                        fallback = Modifier.background(fallback, shape))) { content() }
            }
            MixUiBackend.Mochi -> {
                val backdrop = if (allowGlass) LocalMochiBackdrop.current
                    ?: (LocalGlassFormBackdrop.current as? GlassFormBackdrop.Mochi)?.value else null
                val material = MochiTheme.materials.menuGlass
                if (backdrop != null && material != null) {
                    MochiGlassSurface(Modifier.testTag("glass-suggestion-mochi"), backdrop = backdrop,
                        material = material, cornerRadius = SuggestionCardRadius) { content() }
                } else Box(Modifier.testTag("solid-suggestion-card")
                    .background(fallback, RoundedCornerShape(SuggestionCardRadius))) { content() }
            }
        }
    }
}

/** Inset window edges, not the form's horizontal insets: the panel matches its editor's width. */
internal fun suggestionViewport(window: IntRect, dialog: IntRect, inset: Int): IntRect {
    val xInset = minOf(inset, window.width.coerceAtLeast(0) / 2)
    val yInset = minOf(inset, window.height.coerceAtLeast(0) / 2)
    val safe = IntRect(window.left + xInset, window.top + yInset,
        maxOf(window.left + xInset, window.right - xInset),
        maxOf(window.top + yInset, window.bottom - yInset))
    if (dialog == IntRect.Zero) return safe
    val left = maxOf(safe.left, dialog.left)
    val top = maxOf(safe.top, dialog.top)
    return IntRect(left, top, maxOf(left, minOf(safe.right, dialog.right)),
        maxOf(top, minOf(safe.bottom, dialog.bottom)))
}

/** Prefer a shorter scrollable panel below when at least one useful row fits there. */
internal fun suggestionAvailableHeight(anchor: IntRect, viewport: IntRect, desired: Int,
    minimumRow: Int, gap: Int = 0): Int {
    val below = (viewport.bottom - anchor.bottom - gap).coerceAtLeast(0)
    val above = (anchor.top - viewport.top - gap).coerceAtLeast(0)
    val useBelow = below >= minOf(desired, minimumRow) || below >= above
    return minOf(desired.coerceAtLeast(0), if (useBelow) below else above,
        viewport.height.coerceAtLeast(0))
}

internal fun suggestionPosition(anchor: IntRect, viewport: IntRect, size: IntSize,
    direction: LayoutDirection, gap: Int = 0): IntOffset {
    val x = if (direction == LayoutDirection.Ltr) anchor.left else anchor.right - size.width
    val below = viewport.bottom - anchor.bottom - gap
    val above = anchor.top - viewport.top - gap
    val y = if (below >= size.height || below >= above) anchor.bottom + gap else anchor.top - gap - size.height
    return IntOffset(x.coerceIn(viewport.left, maxOf(viewport.left, viewport.right - size.width)),
        y.coerceIn(viewport.top, maxOf(viewport.top, viewport.bottom - size.height)))
}

/** A clipped editor must not leave a detached suggestion window at a viewport edge. */
internal fun suggestionAnchorVisible(anchor: IntRect, viewport: IntRect): Boolean =
    minOf(anchor.right, viewport.right) > maxOf(anchor.left, viewport.left) &&
        minOf(anchor.bottom, viewport.bottom) > maxOf(anchor.top, viewport.top)
