package hk.uwu.reareye.ui.components.config

import hk.uwu.mixui.icons.Icons as UiIcons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.GlassBottomSheet
import hk.uwu.mixui.components.ListItem
import hk.uwu.mixui.theme.MixTheme

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
    val layoutDirection = LocalLayoutDirection.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val availableHeight = (windowHeight - imeBottomPadding).coerceAtLeast(0.dp)
    val minimumContentHeight = availableHeight * 0.52f
    val maximumContentHeight = availableHeight * 0.68f
    val resultListState = rememberLazyListState()
    LaunchedEffect(query) { resultListState.scrollToItem(0) }
    val entries = remember(categories) {
        categories.flatMap { category ->
            category.category.children.filterIsInstance<ConfigItem>().map { item ->
                MoreSearchEntry(item, category)
            }
        }
    }
    val results = searchMoreEntries(entries, query, context::getString)

    GlassBottomSheet(
        show = show,
        title = null,
        startAction = {
            hk.uwu.mixui.components.SearchBar(
                query = query,
                hint = stringResource(R.string.config_more_search_hint),
                onQueryChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        horizontalPadding = 16.dp,
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
                            ListItem(
                                title = stringResource(entry.item.titleRes),
                                summary = stringResource(entry.category.group.titleRes),
                                startAction = {
                                    Box(modifier = Modifier.padding(end = 6.dp)) {
                                        Icon(
                                            imageVector = entry.category.group.icon,
                                            contentDescription = null,
                                            tint = MixTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                endActions = {
                                    Icon(
                                        imageVector = UiIcons.Basic.ArrowRight,
                                        contentDescription = null,
                                        tint = MixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(width = 10.dp, height = 16.dp).graphicsLayer {
                                            scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                        },
                                    )
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
