package hk.uwu.mixui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar as MiuixSearchBar

import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import ui.feice.mochi.basic.MochiSearchBar

/** Only a sibling viewport recorder may supply this source; never record the search itself. */
internal val LocalSearchBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Controlled native search control selected solely by the current UI backend. */
@Composable
fun SearchBar(
    query: String,
    hint: String,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit = {},
    onSearchFocusChange: (Boolean) -> Unit = {},
) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> NativeMiuixSearchBar(query, hint, modifier, onQueryChange, onSearchSubmit, onSearchFocusChange)
        MixUiBackend.Mochi -> NativeMochiSearchBar(query, hint, modifier, onQueryChange, onSearchSubmit, onSearchFocusChange)
    }
}

@Composable
private fun NativeMiuixSearchBar(
    query: String,
    hint: String,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputExpanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused = interactionSource.collectIsFocusedAsState().value

    LaunchedEffect(focused) {
        inputExpanded = focused
        onSearchFocusChange(focused)
    }

    val backdrop = LocalSearchBackdrop.current
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    MiuixSearchBar(
        modifier = modifier.fillMaxWidth(),
        insideMargin = DpSize(0.dp, 0.dp),
        inputField = {
            InputField(
                // Match the wide native GlassSegmentedTabRow track. Keep the
                // shadow outside glass's clipping, just as the vendor does.
                modifier = if (backdrop != null) Modifier
                    .glassShadow(GlassShape(50.dp), GlassShadows.Float)
                    .glass(
                        backdrop = backdrop,
                        shape = GlassShape(50.dp),
                        style = GlassDefaults.style(dark),
                        material = GlassMaterials.puredThinGlass(dark),
                        shading = false,
                    ) else Modifier,
                color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainerHigh,
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onSearchSubmit()
                },
                expanded = inputExpanded,
                onExpandedChange = { expanded ->
                    if (expanded) {
                        inputExpanded = true
                    }
                },
                label = hint,
                interactionSource = interactionSource,
            )
        },
        onExpandedChange = {},
        expanded = false,
    ) {}
}

@Composable
private fun NativeMochiSearchBar(
    query: String,
    hint: String,
    modifier: Modifier,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Mochi 0.2 has no focus callback or interactionSource slot. Observe the
    // group's descendant focus rather than the non-focusable outer Row itself.
    Box(modifier.onFocusChanged { onSearchFocusChange(it.hasFocus) }.focusGroup()) {
        MochiSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = hint,
            onSearch = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                onSearchSubmit()
            },
            onCancel = { keyboardController?.hide() },
        )
    }
}
