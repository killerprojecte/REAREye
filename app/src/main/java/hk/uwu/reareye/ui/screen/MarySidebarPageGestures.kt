package hk.uwu.reareye.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*

/** One root host covers the page and its sibling app navigation, only while Mary registers it. */
@Stable
internal class MarySidebarPageGestureHost {
    // Independent of drawer-open registration.enabled: a pending release must die on leaving Mary.
    internal var pageEnabled by mutableStateOf<State<Boolean>?>(null)
    internal var registration by mutableStateOf<MarySidebarPageRegistration?>(null)
    internal var preview by mutableStateOf<MarySidebarPreviewRegistration?>(null)
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
    val enabledState = rememberUpdatedState(enabled)
    val enabledNow by enabledState
    SideEffect { host.pageEnabled = enabledState }
    val base = onPreviewKeyEvent {
        if (enabledNow && registration?.state?.isOpening == true &&
            it.key == Key.Escape && it.type == KeyEventType.KeyUp
        ) {
            host.preview?.dismiss?.invoke()
            true
        } else false
    }
    if (!enabled || registration == null || !registration.enabled()) return base
    fun current() = enabledNow && host.registration === registration && registration.enabled()
    return base.marySidebarOpenGesture(
        registration.state,
        beforeOpen = { if (current()) registration.beforeOpen() },
        onOpen = { if (current()) registration.open() },
    )
}

internal class MarySidebarPreviewRegistration(val dismiss: () -> Unit, val content: @Composable () -> Unit)

/** Portal into the Activity's last layer, above the sibling navigation without a new Window. */
@Composable
internal fun RegisterMarySidebarPreview(host: MarySidebarPageGestureHost?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val latest by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host) {
        val owner = MarySidebarPreviewRegistration({ latestDismiss() }) { latest() }
        host?.preview = owner
        onDispose { if (host?.preview === owner) host.preview = null }
    }
}

@Composable
internal fun MarySidebarPagePreview(host: MarySidebarPageGestureHost, enabled: Boolean) {
    if (enabled) host.preview?.content?.invoke()
}
