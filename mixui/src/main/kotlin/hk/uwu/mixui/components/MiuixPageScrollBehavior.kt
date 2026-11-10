package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState

/**
 * Collapsing the bar also moves the page's content padding. MIUIX tracks only
 * the body's consumed post-scroll distance in contentOffset, so its progressive
 * band stays transparent on short pages whose entire drag collapses the bar.
 * Include that real header displacement without replacing native scroll physics.
 */
@Composable
internal fun rememberMiuixPageScrollBehavior(
    state: TopAppBarState = rememberTopAppBarState(),
): ScrollBehavior {
    val behavior = MiuixScrollBehavior(state = state)
    LaunchedEffect(state) {
        var previous = state.heightOffset
        // A restored short page can already be collapsed before this effect starts.
        state.contentOffset = minOf(state.contentOffset, previous)
        snapshotFlow { state.heightOffset }.collect { height ->
            val delta = height - previous
            previous = height
            if (delta != 0f) state.contentOffset = (state.contentOffset + delta).coerceAtMost(0f)
        }
    }
    return behavior
}
