package hk.uwu.mixui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import kotlinx.collections.immutable.toImmutableList
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout
import top.yukonga.miuix.kmp.window.WindowListPopup
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiDropdown
import ui.feice.mochi.basic.MochiDropdownDefaults
import ui.feice.mochi.basic.MochiItem

/** [id] identifies a choice even when several choices have the same title. */
data class GlassSheetDropdownOption(val id: String, val title: String, val summary: String? = null)

/** The anchor supplies only its visuals; the native dropdown owns its interaction and dismissal. */
@Composable
fun GlassSheetDropdown(
    options: List<GlassSheetDropdownOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    anchor: @Composable (expanded: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = enabled && options.isNotEmpty()
    LaunchedEffect(active) { if (!active) expanded = false }
    val showing = expanded && active
    val dismiss = { expanded = false }
    fun select(id: String) {
        if (expanded && active) {
            dismiss()
            onSelect(id)
        }
    }

    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> MiuixSheetDropdown(
            options, selectedId, showing, active, modifier,
            onOpen = { expanded = true }, onDismiss = dismiss, onSelect = ::select, anchor = anchor,
        )
        MixUiBackend.Mochi -> {
            var selectionSent by remember { mutableStateOf(false) }
            // Mochi 0.2 has a single-line label, not a subtitle slot. Keep the provider
            // in that label so equal model names are still distinct in the native menu.
            val items = remember(options) {
                options.map { option ->
                    MochiItem(label = if (option.summary.isNullOrBlank()) option.title else "${option.title} · ${option.summary}")
                }.toImmutableList()
            }
            MochiDropdown(
                expanded = showing,
                onExpandedChange = { next ->
                    val open = next && active
                    if (open && !expanded) selectionSent = false
                    expanded = open
                },
                items = items,
                onItemClick = { index ->
                    val option = options.getOrNull(index)
                    if (expanded && active && !selectionSent && option != null) {
                        // The native menu briefly animates its new checkmark before closing.
                        selectionSent = true
                        onSelect(option.id)
                    }
                },
                modifier = modifier.semantics(mergeDescendants = true) {},
                enabled = active,
                selectedIndex = options.indexOfFirst { it.id == selectedId },
                colors = MochiDropdownDefaults.dropdownColors(buttonColor = Color.Transparent),
                behavior = MochiDropdownDefaults.behavior(align = MochiAlign.Center),
                menuWidth = 300.dp,
                buttonPadding = PaddingValues(0.dp),
            ) { anchor(showing) }
        }
    }
}

@Composable
private fun MiuixSheetDropdown(
    options: List<GlassSheetDropdownOption>,
    selectedId: String?,
    expanded: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    anchor: @Composable (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val rows: @Composable () -> Unit = {
        options.forEach { option ->
            key(option.id) {
                GlassPopupItem(
                    text = option.title, summary = option.summary, selected = option.id == selectedId,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSelect(option.id)
                    },
                )
            }
        }
    }
    val inNativeSheet = LocalMiuixSheetTabs.current
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    val interaction = if (enabled) Modifier.clickable(role = Role.DropdownList, onClick = {
        if (expanded) onDismiss() else {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            onOpen()
        }
    }) else Modifier.semantics(mergeDescendants = true) { disabled(); role = Role.DropdownList }
    Box(modifier.then(if (inNativeSheet) Modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() } else Modifier)
        .clip(RoundedCornerShape(20.dp)).then(interaction)) {
        anchor(expanded)
        if (!inNativeSheet) {
            // A legacy window sheet has no same-window glass source or local Scaffold.
            WindowListPopup(show = expanded, enableWindowDim = false, onDismissRequest = onDismiss,
                maxHeight = 280.dp, minWidth = 220.dp) {
                ListPopupColumn { rows() }
            }
        }
    }
    if (!inNativeSheet) return

    val backdrop = LocalGlassSheetPopupBackdrop.current
    val mounted = remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(false) }
    val retain = expanded || rendered
    SideEffect { mounted.value = retain }
    if (mounted.value) {
        // Route to the sheet's own popup host, above the sheet material recording.
        // Keep it mounted until the native dropdown finishes its exit motion.
        PopupLayout(visible = mounted, enableWindowDim = false, enableBackHandler = false,
            enterTransition = EnterTransition.None, exitTransition = ExitTransition.None,
            renderInRootScaffold = false) {
            if (anchorBounds != Rect.Zero) {
                Box(Modifier.fillMaxSize()) {
                    GlassDropdownPopup(show = expanded, onDismissRequest = onDismiss, anchorBounds = anchorBounds,
                        backdrop = backdrop, sizing = GlassPopupSizing(minWidth = 220.dp, maxWidth = 300.dp, maxHeight = 280.dp)) {
                        DisposableEffect(Unit) {
                            rendered = true
                            onDispose { rendered = false }
                        }
                        rows()
                    }
                }
            }
        }
    }
}
