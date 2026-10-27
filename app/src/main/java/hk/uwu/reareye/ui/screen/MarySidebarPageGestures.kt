package hk.uwu.reareye.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** One root host covers the page and its sibling app navigation, only while Mary registers it. */
@Stable
internal class MarySidebarPageGestureHost {
    internal var registration by mutableStateOf<MarySidebarPageRegistration?>(null)
}

internal class MarySidebarPageRegistration(
    val state: MarySidebarGestureState,
    val enabled: () -> Boolean,
    val beforeOpen: () -> Unit,
    val open: () -> Unit,
)

internal val LocalMarySidebarPageGestureHost = staticCompositionLocalOf<MarySidebarPageGestureHost?> { null }

@Composable
internal fun RegisterMarySidebarPageGestures(
    host: MarySidebarPageGestureHost?,
    state: MarySidebarGestureState,
    enabled: Boolean,
    beforeOpen: () -> Unit,
    onOpen: () -> Unit,
) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestBefore by rememberUpdatedState(beforeOpen)
    val latestOpen by rememberUpdatedState(onOpen)
    DisposableEffect(host, state) {
        val registration = MarySidebarPageRegistration(state, { latestEnabled }, { latestBefore() }, { latestOpen() })
        host?.registration = registration
        onDispose {
            // AnimatedContent can briefly retain both old/new owners; do not clear a newer owner.
            if (host != null && host.registration === registration) host.registration = null
        }
    }
}

@Composable
internal fun Modifier.marySidebarPageGesture(host: MarySidebarPageGestureHost, enabled: Boolean): Modifier {
    val registration = host.registration
    val enabledNow by rememberUpdatedState(enabled)
    if (!enabled || registration == null || !registration.enabled()) return this
    fun current() = enabledNow && host.registration === registration && registration.enabled()
    return marySidebarOpenGesture(
        registration.state,
        beforeOpen = { if (current()) registration.beforeOpen() },
        onOpen = { if (current()) registration.open() },
    )
}
