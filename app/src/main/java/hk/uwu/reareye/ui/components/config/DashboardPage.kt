package hk.uwu.reareye.ui.components.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

/** Per-dashboard-page contract, scoped to each kept-alive tab, never shared globally. */
internal class DashboardPage {
    var listState by mutableStateOf<LazyListState?>(null)
    var header by mutableStateOf<(@Composable () -> Unit)?>(null)
}

internal val LocalDashboardPage = staticCompositionLocalOf<DashboardPage?> { null }
internal val LocalDashboardPadding = staticCompositionLocalOf { PaddingValues(0.dp) }

@Composable
internal fun BindDashboardPage(
    embedded: Boolean,
    listState: LazyListState,
    header: (@Composable () -> Unit)? = null,
) {
    val page = LocalDashboardPage.current.takeIf { embedded } ?: return
    val currentHeader = rememberUpdatedState(header)
    DisposableEffect(page, listState, header != null) {
        page.listState = listState
        page.header = if (header == null) null else { { currentHeader.value?.invoke() } }
        onDispose {
            if (page.listState === listState) {
                page.listState = null
                page.header = null
            }
        }
    }
}
