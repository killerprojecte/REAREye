package hk.uwu.reareye.ui.screen

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.data.AgentSessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A separate modal window is intentional: the app navigation is a sibling ABOVE MaryScreen. */
@Composable
internal fun MaryConversationSidebar(
    show: Boolean,
    revision: Int,
    currentSessionId: String?,
    hasSession: Boolean,
    locked: Boolean,
    load: () -> List<AgentSessionSummary>,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onLock: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClosed: () -> Unit = {},
    gestureState: MarySidebarGestureState? = null,
) {
    val gestures = gestureState ?: remember { MarySidebarGestureState() }
    val dragProgress = gestures.progress.takeUnless { gestures.isOpening }
    val openingProgress = gestures.progress.takeIf { gestures.isOpening }
    val openingSettle = gestures.openingSettle
    val pageHost = LocalMarySidebarPageGestureHost.current
    val pageActive = pageHost?.pageEnabled?.value != false
    var previewActive by remember { mutableStateOf(false) }
    var modalReady by remember { mutableStateOf(false) }
    var settlingOpening by remember { mutableStateOf(false) }

    var mounted by remember { mutableStateOf(false) }
    val animation = remember { Animatable(0f) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(revision) { deleting = emptySet() }
    fun closeThen(action: () -> Unit) {
        if (show && gestures.progress == null && pendingAction == null) {
            pendingAction = action
            onDismiss()
        }
    }
    val latestOnClosed by rememberUpdatedState(onClosed)
    val latestShow by rememberUpdatedState(show)
    LaunchedEffect(show, dragProgress, openingProgress, openingSettle, pageActive) {
        if (!pageActive) {
            // AnimatedContent may retain the departing Mary screen. Never let its delayed
            // release animation create a Window over the destination page or another modal.
            gestures.cancel()
            animation.snapTo(0f)
            previewActive = false
            mounted = false
            modalReady = false
            settlingOpening = false
            pendingAction = null
            onDismiss()
            return@LaunchedEffect
        }
        if (openingProgress != null) {
            previewActive = true
            modalReady = false
            if (openingSettle == null) {
                settlingOpening = false
                animation.snapTo(openingProgress)
                return@LaunchedEffect
            }
            // Reversing a release animation (Back/ESC) starts at its current visible position,
            // not the stale fraction at which the finger was lifted.
            if (!settlingOpening) animation.snapTo(openingProgress)
            settlingOpening = true
            // Finish in the original Activity window, never move an active pointer to Dialog.
            val accepted = openingSettle && show
            animation.animateTo(if (accepted) 1f else 0f, tween(140, easing = FastOutSlowInEasing))
            if (accepted && pageHost?.pageEnabled?.value != false) mounted = true
            else {
                previewActive = false
                val wasMounted = mounted
                mounted = false
                modalReady = false
                settlingOpening = false
                gestures.cancel()
                if (wasMounted) {
                    withFrameNanos { }
                    latestOnClosed()
                }
            }
            return@LaunchedEffect
        }
        if (dragProgress != null) {
            mounted = true
            animation.snapTo(dragProgress)
            return@LaunchedEffect
        }
        if (show) {
            mounted = true
            pendingAction = null
        }
        animation.animateTo(if (show) 1f else 0f, tween(260, easing = FastOutSlowInEasing))
        if (!show) {
            previewActive = false
            modalReady = false
            settlingOpening = false
            val wasMounted = mounted
            mounted = false
            // Let Dialog leave composition/window focus before restoring the originating menu.
            if (wasMounted) {
                withFrameNanos { }
                latestOnClosed()
            }
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
    }
    val progress = dragProgress ?: openingProgress?.takeIf { openingSettle == null } ?: animation.value
    var sessions by remember { mutableStateOf(emptyList<AgentSessionSummary>()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val latestLoad by rememberUpdatedState(load)
    val visible = show || dragProgress != null || openingProgress != null
    LaunchedEffect(visible, revision, retry) {
        if (!visible) return@LaunchedEffect
        loading = true
        failed = false
        try {
            sessions = withContext(Dispatchers.IO) { latestLoad() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        } finally {
            loading = false
        }
    }
    val dismiss = {
        if (previewActive && !modalReady) gestures.rejectOpening() else gestures.cancel()
        onDismiss()
    }
    BackHandler(enabled = previewActive && !modalReady) { dismiss() }

    @Composable fun surface(preview: Boolean, scrimVisible: Boolean) {
        val pane = stringResource(R.string.mary_sidebar_title)
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .then(if (preview) Modifier.semantics { hideFromAccessibility() }.testTag("mary-sidebar-preview")
                    .pointerInput(Unit) {
                        // New hit paths are blocked. The already-down opening pointer retains its
                        // original path through the root draggable; this layer never steals it.
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                else Modifier.testTag("mary-sidebar-modal")
                    .marySidebarCloseGesture(gestures, enabled = show,
                        initialProgress = { animation.value }, onClose = dismiss))
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                        dismiss(); true
                    } else false
                },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = if (scrimVisible) 0.32f * progress else 0f))
                    .then(if (preview) Modifier else Modifier.clickable(
                        interactionSource = null, indication = null,
                        onClickLabel = stringResource(R.string.mary_sidebar_close), onClick = dismiss,
                    )).testTag("mary-sidebar-scrim"),
            )
            MarySidebarContent(
                sessions = sessions,
                currentSessionId = currentSessionId,
                hasSession = hasSession,
                locked = locked,
                loading = loading,
                loadFailed = failed,
                deleting = deleting.isNotEmpty(),
                visualOnly = preview,
                onRetry = { if (!preview) retry++ },
                onDismiss = { if (!preview) dismiss() },
                onNewChat = { if (!preview) closeThen(onNewChat) },
                onLock = { if (!preview) closeThen(onLock) },
                onOpenSettings = { if (!preview) closeThen(onOpenSettings) },
                onSelect = { id -> if (!preview) closeThen { onSelect(id) } },
                onDelete = { id ->
                    if (!preview && show && gestures.progress == null && pendingAction == null && deleting.isEmpty()) {
                        deleting = deleting + id
                        onDelete(id)
                    }
                },
                modifier = Modifier.align(Alignment.CenterStart)
                    .width(minOf(maxWidth * 0.82f, 360.dp)).fillMaxHeight()
                    .graphicsLayer {
                        translationX = (if (rtl) 1 else -1) * size.width * (1f - progress)
                    }.then(if (preview) Modifier.clearAndSetSemantics { testTag = "mary-sidebar-preview-panel" }
                        else Modifier.semantics { paneTitle = pane }),
            )
        }
    }

    RegisterMarySidebarPreview(pageHost, dismiss) { if (pageActive && (previewActive || openingProgress != null)) surface(preview = true, scrimVisible = true) }
    // Standalone Mary has no sibling navigation; production uses the root portal above navigation.
    if (pageHost == null && (previewActive || openingProgress != null)) surface(preview = true, scrimVisible = true)
    // Do not expose app navigation while the panel is still sliding away.
    if (!mounted || !pageActive) return
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            // We animate our own scrim; Android's default dim would double it and snap on close.
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        LaunchedEffect(Unit) {
            // Keep the opaque, identical Activity preview until the Dialog has laid out a frame.
            // During overlap only the Activity draws the scrim, preventing a double-dark flash.
            withFrameNanos { }
            withFrameNanos { }
            if (!previewActive) modalReady = true
            else if (latestShow && gestures.isOpening && gestures.openingSettle == true) {
                modalReady = true
                previewActive = false
                settlingOpening = false
                gestures.cancel()
            }
        }
        surface(preview = false, scrimVisible = !previewActive)

    }
}

/** Real drawer UI shared with native rendering tests; no model/network access required. */
@Composable
internal fun MarySidebarContent(
    sessions: List<AgentSessionSummary>,
    currentSessionId: String?,
    hasSession: Boolean,
    locked: Boolean,
    loading: Boolean = false,
    loadFailed: Boolean = false,
    deleting: Boolean = false,
    visualOnly: Boolean = false,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onLock: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(visualOnly) { if (!visualOnly) closeFocus.requestFocus() }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Column(
        modifier.background(colors.surface).testTag(if (visualOnly) "mary-sidebar-preview-panel" else "mary-sidebar-panel")
            // Consume panel whitespace; it must never dismiss through the scrim beneath it.
            .pointerInput(Unit) { detectTapGestures { } }
            // This surface spans the physical window. Keep its geometry independent of IME
            // animation so the Activity preview and Dialog agree when the keyboard hides on UP.
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.mary_title), style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).focusRequester(closeFocus).testTag("mary-sidebar-close")) {
                Icon(Icons.Outlined.Close, stringResource(R.string.mary_sidebar_close))
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("mary-sidebar-history"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            userScrollEnabled = !visualOnly,
        ) {
            // Actions scroll with history on short windows/large fonts; the footer stays reachable.
            item(key = "actions") {
                SidebarAction(
                    icon = if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    label = stringResource(if (locked) R.string.mary_sidebar_locked else R.string.mary_session_lock),
                    enabled = hasSession && !locked, onClick = onLock, tag = "mary-sidebar-lock",
                )
                SidebarAction(MiuixIcons.Settings, stringResource(R.string.mary_settings_title),
                    onClick = onOpenSettings, tag = "mary-sidebar-settings")
                Box(Modifier.padding(horizontal = 12.dp, vertical = 16.dp).fillMaxWidth().height(1.dp)
                    .background(colors.outline.copy(alpha = 0.35f)))
                Text(stringResource(R.string.mary_history_title), style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            if (loading || loadFailed || sessions.isEmpty()) {
                item {
                    Text(
                        stringResource(when {
                            loading -> R.string.mary_history_loading
                            loadFailed -> R.string.mary_history_load_failed
                            else -> R.string.mary_history_empty
                        }),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(12.dp),
                    )
                    if (loadFailed) SidebarAction(MiuixIcons.Refresh,
                        stringResource(R.string.credits_contributors_retry), onClick = onRetry,
                        tag = "mary-sidebar-retry")
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    val current = session.id == currentSessionId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (current) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .semantics { selected = current }
                            .clickable(role = Role.Button) { onSelect(session.id) }
                            .testTag("mary-sidebar-session-${session.id}")
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, style = MiuixTheme.textStyles.body1,
                                color = colors.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.mary_history_meta,
                                formatter.format(Date(session.updatedAt)), session.messageCount),
                                style = MiuixTheme.textStyles.footnote2,
                                color = colors.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (session.locked) Icon(Icons.Outlined.Lock,
                            stringResource(R.string.mary_sidebar_locked), modifier = Modifier.size(16.dp),
                            tint = colors.onSurfaceVariantSummary)
                        IconButton(onClick = { onDelete(session.id) }, enabled = !deleting,
                            modifier = Modifier.size(48.dp).testTag("mary-sidebar-delete-${session.id}")) {
                            Icon(MiuixIcons.Delete, stringResource(R.string.mary_history_delete),
                                modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
        // Fixed footer never scrolls under gesture navigation or the app's bottom navigation.
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)).background(colors.primary)
                .clickable(role = Role.Button, onClick = onNewChat)
                .testTag("mary-sidebar-new-chat").heightIn(min = 52.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MiuixIcons.Add, null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.mary_action_new_chat), color = colors.onPrimary,
                style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SidebarAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    tag: String,
) {
    val color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(22.dp), tint = color)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MiuixTheme.textStyles.body1, color = color)
    }
}
