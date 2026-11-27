package hk.uwu.reareye.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialogFormColumn(
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    verticalSpacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navBarBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val minimalBringIntoViewSpec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = when {
                containerSize <= 0f -> 0f
                offset >= 0f && offset + size <= containerSize -> 0f
                size > containerSize -> offset
                offset < 0f -> offset
                else -> offset + size - containerSize
            }
        }
    }
    val boundedMaxHeight = remember(windowHeight, imeBottom, statusBarTop, navBarBottom, maxHeight) {
        val viewportLimit = if (windowHeight > 0.dp) {
            val restingLimit = (windowHeight * 0.68f).coerceAtLeast(240.dp)
            if (imeBottom > 0.dp) {
                val availableForForm = (windowHeight - statusBarTop - maxOf(imeBottom, navBarBottom) - 132.dp)
                    .coerceAtLeast(120.dp)
                minOf(restingLimit, availableForForm)
            } else {
                restingLimit
            }
        } else {
            null
        }
        when {
            maxHeight != null && viewportLimit != null -> minOf(maxHeight, viewportLimit)
            maxHeight != null -> maxHeight
            viewportLimit != null -> viewportLimit
            else -> null
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides minimalBringIntoViewSpec) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (boundedMaxHeight != null) {
                        Modifier.heightIn(max = boundedMaxHeight)
                    } else {
                        Modifier
                    }
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}
