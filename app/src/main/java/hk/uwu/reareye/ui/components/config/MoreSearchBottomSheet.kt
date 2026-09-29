package hk.uwu.reareye.ui.components.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MoreSearchBottomSheet(
    show: Boolean,
    categories: List<MoreCategory>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    onResultSelected: (ConfigCategory) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val availableHeight = (windowHeight - imeBottomPadding).coerceAtLeast(0.dp)
    val minimumContentHeight = availableHeight * 0.52f
    val maximumContentHeight = availableHeight * 0.68f
    var searchFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = query,
                selection = TextRange(query.length),
            )
        )
    }
    val resultListState = rememberLazyListState()
    LaunchedEffect(query) {
        if (query != searchFieldValue.text) {
            val selectionStart = searchFieldValue.selection.start.coerceAtMost(query.length)
            val selectionEnd = searchFieldValue.selection.end.coerceAtMost(query.length)
            searchFieldValue = TextFieldValue(
                text = query,
                selection = TextRange(selectionStart, selectionEnd),
            )
        }
    }
    LaunchedEffect(query) { resultListState.scrollToItem(0) }
    val entries = remember(categories) {
        categories.flatMap { category ->
            category.category.children.filterIsInstance<ConfigItem>().map { item ->
                MoreSearchEntry(item, category)
            }
        }
    }
    val results = searchMoreEntries(entries, query, context::getString)

    OverlayBottomSheet(
        show = show,
        title = null,
        startAction = {
            StableSearchInputField(
                value = searchFieldValue,
                onValueChange = { nextValue ->
                    searchFieldValue = nextValue
                    if (nextValue.text != query) {
                        onQueryChange(nextValue.text)
                    }
                },
                label = stringResource(R.string.config_more_search_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        insideMargin = DpSize(16.dp, 0.dp),
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (results.isEmpty()) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(minimumContentHeight),
                )
            } else {
                LazyColumn(
                    state = resultListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = minimumContentHeight,
                            max = maximumContentHeight,
                        ),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.item.key }) { entry ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = stringResource(entry.item.titleRes),
                                summary = stringResource(entry.category.group.titleRes),
                                startAction = {
                                    Box(modifier = Modifier.padding(end = 6.dp)) {
                                        Icon(
                                            imageVector = entry.category.group.icon,
                                            contentDescription = null,
                                            tint = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                insideMargin = PaddingValues(
                                    horizontal = 16.dp,
                                    vertical = 14.dp,
                                ),
                                onClick = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    onResultSelected(entry.category.category)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StableSearchInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val textColor = MiuixTheme.colorScheme.onSurface
    val secondaryColor = MiuixTheme.colorScheme.onSurfaceContainerHigh
    val capsuleColor = MiuixTheme.colorScheme.surfaceContainerHigh

    BasicTextField(
        value = value,
        onValueChange = currentOnValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MiuixTheme.textStyles.main.copy(
            color = textColor,
            fontWeight = FontWeight.Medium,
        ),
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 45.dp)
                    .background(capsuleColor, CircleShape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MiuixIcons.Basic.Search,
                    contentDescription = null,
                    tint = secondaryColor,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 8.dp)
                        .size(20.dp),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = label,
                            color = secondaryColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    innerTextField()
                }
                if (value.text.isNotEmpty()) {
                    Icon(
                        imageVector = MiuixIcons.Basic.SearchCleanup,
                        contentDescription = null,
                        tint = secondaryColor,
                        modifier = Modifier
                            .padding(start = 8.dp, end = 16.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable {
                                currentOnValueChange(
                                    TextFieldValue(
                                        text = "",
                                        selection = TextRange.Zero,
                                    )
                                )
                            },
                    )
                }
            }
        },
    )
}
