package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Save

/**
 * An editable form with stationary native header actions and one scrolling body.
 * Keep composed through show=false; drafts, validation and commits belong to the caller.
 * The native sheet constrains the body after its header and system/IME insets have
 * been measured, so short forms wrap and long forms retain a reachable Save action.
 */
@Composable
fun GlassEditorSheet(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String,
    cancelText: String? = null,
    confirmEnabled: Boolean = true,
    allowDismiss: Boolean = true,
    sessionKey: Any? = Unit,
    dismissRequestKey: Any? = Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentShow by rememberUpdatedState(show)
    val currentAllowed by rememberUpdatedState(allowDismiss)
    val currentEnabled by rememberUpdatedState(confirmEnabled)
    val dismiss by rememberUpdatedState(onDismissRequest)
    val confirm by rememberUpdatedState(onConfirm)
    // Nullable edit targets normally disappear on Cancel/Save. Their key must
    // not dispose the old host before it observes show=false and finishes exit.
    var retainedSession by remember { mutableStateOf(sessionKey) }
    if (show && retainedSession != sessionKey) retainedSession = sessionKey
    key(retainedSession) {
        val scroll = rememberScrollState()
        GlassBottomSheet(
            show = show,
            title = title,
            sessionKey = retainedSession,
            dismissRequestKey = dismissRequestKey,
            size = GlassBottomSheetSize.Content,
            allowDismiss = allowDismiss,
            isContentScrolled = scroll.value > 0,
            onDismissRequest = { if (currentShow && currentAllowed) dismiss() },
            startAction = cancelText?.let { label -> {
                BottomSheetActionButton(
                    enabled = show && allowDismiss,
                    onClick = { if (currentShow && currentAllowed) dismiss() },
                ) {
                    Icon(MiuixIcons.Glass.Close, contentDescription = label)
                }
            } },
            endAction = {
                BottomSheetActionButton(
                    enabled = show && confirmEnabled,
                    onClick = { if (currentShow && currentEnabled) confirm() },
                ) {
                    Icon(MiuixIcons.Glass.Save, contentDescription = confirmText)
                }
            },
        ) {
            CompositionLocalProvider(LocalGlassFormControlContrast provides true) {
                val scrollGutter = if (LocalMixUiBackend.current == MixUiBackend.Miuix &&
                    LocalMiuixSheetTabs.current) 24.dp else 0.dp
                Column(
                    modifier.fillMaxWidth().testTag("mixui-editor-sheet-body")
                        // Same viewport treatment as ThemeStyleSheet: MIUIX's
                        // overscroll layer clips to its measured width. Expand
                        // that layer into the existing sheet margins, then put
                        // the content back. Tab/field geometry and row heights
                        // remain unchanged; only the shadow's drawing room grows.
                        .then(if (scrollGutter > 0.dp) Modifier.layout { measurable, constraints ->
                            val gutter = scrollGutter.roundToPx()
                            val child = measurable.measure(constraints.offset(horizontal = gutter * 2))
                            layout((child.width - gutter * 2).coerceAtLeast(0), child.height) {
                                child.placeRelative(-gutter, 0)
                            }
                        } else Modifier)
                        .verticalScroll(scroll)
                        .padding(horizontal = scrollGutter)
                        .padding(top = 8.dp, bottom = 24.dp),
                ) { content() }
            }
        }
    }
}

/** Reuse the containing full-window source; only standalone pages create one. */
@Composable
internal fun WithGlassSheetScene(content: @Composable () -> Unit) {
    if (LocalGlassSheetScene.current == null) GlassBottomSheetScene(content)
    else content()
}
