package hk.uwu.reareye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ModuleSearchBarStyle
import hk.uwu.reareye.ui.config.PrefsManager
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import hk.uwu.mixui.components.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.theme.MixTheme

@Composable
fun RearSearchBar(
    query: String,
    hint: String,
    prefsManager: PrefsManager,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit = {},
    onSearchFocusChange: (Boolean) -> Unit = {},
) {
    when (
        ModuleSearchBarStyle.fromValue(
            prefsManager.getInt(
                ConfigKeys.MODULE_SEARCH_BAR_STYLE,
                ModuleSearchBarStyle.default.value,
            )
        )
    ) {
        ModuleSearchBarStyle.DEFAULT -> DefaultRearSearchBar(
            query = query,
            hint = hint,
            modifier = modifier,
            onQueryChange = onQueryChange,
            onSearchSubmit = onSearchSubmit,
            onSearchFocusChange = onSearchFocusChange,
        )

        ModuleSearchBarStyle.MIUIX -> MiuixRearSearchBar(
            query = query,
            hint = hint,
            modifier = modifier,
            onQueryChange = onQueryChange,
            onSearchSubmit = onSearchSubmit,
            onSearchFocusChange = onSearchFocusChange,
        )
    }
}

@Composable
private fun DefaultRearSearchBar(
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
private fun MiuixRearSearchBar(
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

    SearchBar(
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
