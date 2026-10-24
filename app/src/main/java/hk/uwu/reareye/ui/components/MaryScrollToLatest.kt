package hk.uwu.reareye.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Scroll activity, not incoming tokens or recomposition, owns the visibility timeout. */
@Composable
internal fun MaryScrollToLatest(
    awayFromBottom: Boolean,
    scrolling: Boolean,
    sessionKey: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealed by remember(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(sessionKey, awayFromBottom, scrolling) {
        revealed = false
        if (awayFromBottom && !scrolling) {
            revealed = true
            delay(3_000)
            revealed = false
        }
    }
    val visible = revealed && awayFromBottom && !scrolling
    val opacity by animateFloatAsState(
        if (visible) 1f else 0f,
        tween(200),
        label = "maryJumpOpacity"
    )
    if (opacity > 0.001f) {
        Surface(
            onClick = onClick,
            enabled = visible,
            shape = CircleShape,
            color = MiuixTheme.colorScheme.surfaceContainerHighest,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainerHighest,
            shadowElevation = 4.dp,
            modifier = modifier.size(46.dp).testTag("mary-jump-to-latest")
                .graphicsLayer { alpha = opacity }
                .border(0.5.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.25f), CircleShape),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(R.string.mary_scroll_to_bottom),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/** User intent is latched synchronously, before a drag changes list geometry. */
@Stable
internal class MaryTailFollowState {
    var following by mutableStateOf(true)
        private set
    private var userScroll = false

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) pauseForUserScroll()
            return Offset.Zero
        }
    }

    fun pauseForUserScroll() {
        userScroll = true
        following = false
    }

    fun onScrollIdle(atBottom: Boolean) {
        if (userScroll) {
            userScroll = false
            following = atBottom
        }
    }

    fun followLatest() {
        userScroll = false
        following = true
    }
}

@Composable
internal fun rememberMaryTailFollowState(
    list: LazyListState,
    sessionKey: String?
): MaryTailFollowState {
    val state = remember(list, sessionKey) { MaryTailFollowState() }
    LaunchedEffect(list, state) {
        snapshotFlow { list.isScrollInProgress to !list.canScrollForward }.collect { (scrolling, atBottom) ->
            // Never re-arm during the transient at-bottom frame at the start of a drag.
            if (!scrolling) state.onScrollIdle(atBottom)
        }
    }
    return state
}

private data class ConversationTail(val count: Int, val edge: Int, val index: Int, val bottom: Int)

/** Measure card expansion as well as tokens: an awaiting form must stay above the composer. */
@Composable
internal fun MaryFollowConversationTail(
    list: LazyListState,
    following: Boolean,
    sessionKey: String?,
    canFollow: () -> Boolean = { following },
) {
    val currentCanFollow by rememberUpdatedState(canFollow)
    LaunchedEffect(list, following, sessionKey) {
        if (!following) return@LaunchedEffect
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            ConversationTail(
                info.totalItemsCount,
                info.viewportEndOffset - info.afterContentPadding,
                last?.index ?: -1,
                last?.let { it.offset + it.size } ?: 0)
        }.collect { tail ->
            // A pre-scroll callback can pause us before Compose has recomposed this effect.
            if (!currentCanFollow() || tail.count == 0) return@collect
            if (tail.index != tail.count - 1) list.scrollToItem(tail.count - 1)
            if (!currentCanFollow()) return@collect
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@collect
            if (last.index != info.totalItemsCount - 1) return@collect
            val overflow =
                last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
            if (overflow > 0) list.scrollBy(overflow.toFloat())
        }
    }
}

/** A tall final item needs its bottom aligned, not merely its top scrolled into view. */
internal suspend fun scrollMaryConversationToBottom(list: LazyListState) {
    val index = list.layoutInfo.totalItemsCount - 1
    if (index < 0) return
    list.animateScrollToItem(index)
    val info = list.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    if (last.index != info.totalItemsCount - 1) return
    val overflow = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
    if (overflow > 0) list.animateScrollBy(overflow.toFloat())
}
