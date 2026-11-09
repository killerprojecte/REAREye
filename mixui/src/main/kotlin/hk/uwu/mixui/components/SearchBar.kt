package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar as MiuixSearchBar
import hk.uwu.mixui.theme.MixTheme

import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import ui.feice.mochi.basic.MochiSearchBar

/** Default keeps the compact acrylic field; Native uses the selected backend's search control. */
enum class SearchBarStyle { Default, Native }

/** A controlled query field. IME submission always dismisses the keyboard and clears focus. */
@Composable
fun SearchBar(
    query: String,
    hint: String,
    modifier: Modifier = Modifier,
    style: SearchBarStyle = SearchBarStyle.Default,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit = {},
    onSearchFocusChange: (Boolean) -> Unit = {},
) {
    when {
        style == SearchBarStyle.Default -> DefaultSearchBar(
            query, hint, modifier, onQueryChange, onSearchSubmit, onSearchFocusChange,
        )
        LocalMixUiBackend.current == MixUiBackend.Miuix -> NativeMiuixSearchBar(
            query, hint, modifier, onQueryChange, onSearchSubmit, onSearchFocusChange,
        )
        else -> NativeMochiSearchBar(
            query, hint, modifier, onQueryChange, onSearchSubmit, onSearchFocusChange,
        )
    }
}

@Composable
private fun DefaultSearchBar(
    query: String,
    hint: String,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchAcrylicShape = RoundedCornerShape(14.dp)
    val searchAcrylicBase = Color(0xFF9EA6B2).copy(alpha = 0.34f)
    val searchAcrylicStroke = Color.White.copy(alpha = 0.34f)
    val searchAcrylicOverlay = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.18f),
            Color.White.copy(alpha = 0.04f),
        )
    )
    val searchTextStyle = TextStyle(
        color = MixTheme.colorScheme.onBackground,
        fontSize = 14.sp,
    )

    Card(
        modifier = modifier
            .border(1.dp, searchAcrylicStroke, searchAcrylicShape)
            .fillMaxWidth(),
        cornerRadius = 14.dp,
        colors = CardDefaults.defaultColors(
            color = searchAcrylicBase,
            contentColor = MixTheme.colorScheme.onSurfaceVariantSummary,
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(searchAcrylicOverlay)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 6.dp),
            )

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onSearchFocusChange(it.isFocused) },
                singleLine = true,
                textStyle = searchTextStyle,
                cursorBrush = SolidColor(MixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onSearchSubmit()
                    }
                ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = hint,
                                style = MixTheme.textStyles.body2,
                                color = MixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
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

    MiuixSearchBar(
        modifier = modifier.fillMaxWidth(),
        insideMargin = DpSize(0.dp, 0.dp),
        inputField = {
            InputField(
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
