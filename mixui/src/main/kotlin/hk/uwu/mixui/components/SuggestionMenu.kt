package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import hk.uwu.mixui.theme.MixTheme

/**
 * Ordinary, opaque suggestions for an editable field. The popup deliberately never
 * takes keyboard focus: the caller's editor and IME remain active while filtering.
 * [boundsInWindow] optionally limits suggestions to a containing dialog. The caller
 * owns Back handling, since a non-focusable Android popup cannot consume Back.
 * No renderer internals, private overlay host, or glass input treatment are used.
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
    width: Dp = 288.dp,
    maxHeight: Dp = 150.dp,
    anchor: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val imeBottom = WindowInsets.ime.getBottom(density)
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    val inset = with(density) { 8.dp.roundToPx() }
    val viewport = IntRect(0, 0, window.width, (window.height - imeBottom).coerceAtLeast(0))
    val clipBounds = suggestionViewport(viewport, boundsInWindow, inset)
    val below = (clipBounds.bottom - anchorBounds.bottom).coerceAtLeast(0)
    val above = (anchorBounds.top - clipBounds.top).coerceAtLeast(0)
    val room = maxOf(below, above).coerceAtMost(clipBounds.height.coerceAtLeast(0))
    val popupWidth = with(density) { width.roundToPx().coerceAtMost(clipBounds.width.coerceAtLeast(0)).toDp() }
    val popupHeight = with(density) { minOf(maxHeight.roundToPx(), room).toDp() }
    val position = remember(clipBounds) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                suggestionPosition(anchorBounds, clipBounds, popupContentSize, layoutDirection)
        }
    }
    Box(modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow().roundToIntRect() }) {
        anchor()
        if (expanded && suggestionAnchorVisible(anchorBounds, clipBounds) && options.isNotEmpty() && popupWidth > 0.dp && popupHeight > 0.dp) {
            Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest,
                properties = PopupProperties(focusable = false, dismissOnBackPress = false,
                    dismissOnClickOutside = true)) {
                val shape = RoundedCornerShape(16.dp)
                LazyColumn(Modifier.width(popupWidth).heightIn(max = popupHeight)
                    .clip(shape).background(MixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f))
                    .border(1.dp, MixTheme.colorScheme.outline.copy(alpha = 0.32f), shape)) {
                    itemsIndexed(options) { index, title ->
                        Text(title, modifier = Modifier.fillMaxWidth()
                            .selectable(selected = selectedIndex == index, role = Role.RadioButton) {
                                onSelectedIndexChange(index)
                            }.padding(horizontal = 20.dp, vertical = 14.dp),
                            color = if (selectedIndex == index) MixTheme.colorScheme.primary
                                else MixTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

internal fun suggestionViewport(window: IntRect, dialog: IntRect, inset: Int): IntRect {
    val bounds = if (dialog == IntRect.Zero) window else IntRect(
        maxOf(window.left, dialog.left), maxOf(window.top, dialog.top),
        minOf(window.right, dialog.right), minOf(window.bottom, dialog.bottom))
    val xInset = minOf(inset, bounds.width.coerceAtLeast(0) / 2)
    val yInset = minOf(inset, bounds.height.coerceAtLeast(0) / 2)
    return IntRect(bounds.left + xInset, bounds.top + yInset,
        maxOf(bounds.left + xInset, bounds.right - xInset),
        maxOf(bounds.top + yInset, bounds.bottom - yInset))
}

internal fun suggestionPosition(anchor: IntRect, viewport: IntRect, size: IntSize,
    direction: LayoutDirection): IntOffset {
    val x = if (direction == LayoutDirection.Ltr) anchor.left else anchor.right - size.width
    val below = viewport.bottom - anchor.bottom
    val above = anchor.top - viewport.top
    val y = if (below >= size.height || below >= above) anchor.bottom else anchor.top - size.height
    return IntOffset(x.coerceIn(viewport.left, maxOf(viewport.left, viewport.right - size.width)),
        y.coerceIn(viewport.top, maxOf(viewport.top, viewport.bottom - size.height)))
}

/** A clipped editor must not leave a detached suggestion window at a viewport edge. */
internal fun suggestionAnchorVisible(anchor: IntRect, viewport: IntRect): Boolean =
    minOf(anchor.right, viewport.right) > maxOf(anchor.left, viewport.left) &&
        minOf(anchor.bottom, viewport.bottom) > maxOf(anchor.top, viewport.top)
