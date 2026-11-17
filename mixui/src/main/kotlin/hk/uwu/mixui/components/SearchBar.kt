package hk.uwu.mixui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.*
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
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

/** Page overlap only: focusing, typing and clearing never raise the material or its shadow. */
internal val LocalSearchSurfaceAlpha = staticCompositionLocalOf { 0f }

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
    val ramp = if (backdrop != null) LocalSearchSurfaceAlpha.current.coerceIn(0f, 1f) else 0f
    val density = LocalDensity.current
    var measuredHeight by remember { mutableStateOf(0.dp) }
    // The vendor segmented track uses height / 2 with zero smoothing: true capsule caps.
    // Measure the native input instead of changing its dimensions to match a tab's height.
    val shape = GlassShape(measuredHeight / 2, smoothing = 0f)
    val restingFill = MiuixTheme.colorScheme.surfaceContainerHigh
    MiuixSearchBar(
        modifier = modifier.fillMaxWidth(),
        insideMargin = DpSize(0.dp, 0.dp),
        inputField = {
            InputField(
                // Same order/ramp as 3a980f7c GlassSegmentedTabRow: shadow outside
                // clip, then glass, then resting fill. At rest no sampling is enabled.
                modifier = Modifier.onSizeChanged { size ->
                    measuredHeight = with(density) { size.height.toDp() }
                }.then(if (backdrop != null) Modifier
                    .glassShadow(shape, GlassShadows.Float, ramp)
                    .clip(shape)
                    .glass(
                        backdrop = backdrop,
                        shape = shape,
                        style = GlassDefaults.Style,
                        alpha = ramp,
                        material = GlassSegmentedTabRowDefaults.material(),
                        stroke = GlassSegmentedTabRowDefaults.stroke(),
                        enabled = ramp > 0f,
                        shading = false,
                    ) else Modifier),
                color = restingFill.copy(alpha = restingFill.alpha * (1f - ramp)),
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
