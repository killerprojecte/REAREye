package hk.uwu.reareye.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import hk.uwu.reareye.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.*
import hk.uwu.mixui.theme.MixTheme

/** Editor-only state: changing tabs must never mutate the applied app theme. */
internal data class ThemeStyleDraft(
    val backend: MixUiBackend,
    val monet: Boolean,
    val mochiStyle: AppMochiThemeStyle,
    val brightness: Int,
    val navigationStyle: AppNavigationBarStyle = AppNavigationBarStyle.default,
) {
    val mode: AppThemeMode get() = AppThemeMode.fromValue(
        when {
            backend == MixUiBackend.Mochi -> listOf(6, 7, 8)[brightness]
            monet -> listOf(2, 4, 5)[brightness]
            else -> listOf(3, 0, 1)[brightness]
        }
    )
    companion object {
        fun from(
            mode: AppThemeMode,
            style: AppMochiThemeStyle,
            navigationStyle: AppNavigationBarStyle = AppNavigationBarStyle.default,
        ) = ThemeStyleDraft(
            mode.backend, mode.value in listOf(2, 4, 5, 9, 10, 11), style,
            when (mode.value) { 0, 4, 7, 10 -> 1; 1, 5, 8, 11 -> 2; else -> 0 },
            navigationStyle,
        )
    }
}

/** The caller commits all values together before dismissing. Draft changes stay in this host. */
@Composable
internal fun ThemeStyleSheet(
    show: Boolean,
    appliedMode: AppThemeMode,
    appliedStyle: AppMochiThemeStyle,
    appliedNavigationStyle: AppNavigationBarStyle = AppNavigationBarStyle.default,
    saving: Boolean = false,
    saveFailed: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (ThemeStyleDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(ThemeStyleDraft.from(appliedMode, appliedStyle, appliedNavigationStyle)) }
    var resetDraft by remember { mutableStateOf(false) }
    var sheetSession by remember { mutableStateOf(0) }
    LaunchedEffect(show, resetDraft) {
        if (show) {
            draft = ThemeStyleDraft.from(appliedMode, appliedStyle, appliedNavigationStyle)
            if (resetDraft) sheetSession++
            resetDraft = false
        }
    }
    var scrollGeneration by remember { mutableStateOf(0) }
    val sheetScroll = androidx.compose.runtime.key(show, scrollGeneration) { rememberScrollState() }
    // Cancel is a transaction boundary even when close/reopen is coalesced into
    // one frame. Do not depend only on observing an intermediate show=false.
    val discardDraft: () -> Unit = {
        if (!saving) {
            resetDraft = true
            scrollGeneration++
            onDismiss()
        }
    }
    MixUiHost(backend = draft.backend, colorMode = draft.mode.colorSchemeMode, mochiStyle = draft.mochiStyle.style) {
    GlassBottomSheet(
        show = show, onDismissRequest = discardDraft, title = stringResource(R.string.theme_editor_title),
        allowDismiss = !saving,
        sessionKey = sheetSession,
        isContentScrolled = sheetScroll.canScrollBackward,
        startAction = {
            BottomSheetActionButton(onClick = discardDraft, enabled = !saving) {
                BottomSheetActionIcon(BottomSheetAction.Close, Icons.Outlined.Close, contentDescription = stringResource(R.string.theme_editor_close))
            }
        },
        endAction = {
            BottomSheetActionButton(onClick = { onSave(draft) }, enabled = !saving) {
                if (saving) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                else BottomSheetActionIcon(BottomSheetAction.Save, Icons.Outlined.Check, contentDescription = stringResource(R.string.theme_editor_save))
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().testTag("theme-style-sheet")
                // Give the scrolling render layer the sheet's full width, while
                // keeping labels and tracks at their original 24dp inset. Shadows
                // can then paint into the margin rather than ending at the track edge.
                .layout { measurable, constraints ->
                    val gutter = 24.dp.roundToPx()
                    val child = measurable.measure(constraints.offset(horizontal = gutter * 2))
                    layout((child.width - gutter * 2).coerceAtLeast(0), child.height) {
                        child.placeRelative(-gutter, 0)
                    }
                }
                .verticalScroll(sheetScroll).padding(horizontal = 24.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.testTag("theme-style-tabs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme_editor_style), style = MixTheme.textStyles.subtitle, fontWeight = FontWeight.SemiBold)
                GlassTabs(listOf("MIUIX", "Mochi"), if (draft.backend == MixUiBackend.Miuix) 0 else 1,
                    { if (!saving) draft = draft.copy(backend = if (it == 0) MixUiBackend.Miuix else MixUiBackend.Mochi) },
                    Modifier.fillMaxWidth())
            }
            Column(Modifier.testTag("theme-palette-tabs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme_editor_palette), style = MixTheme.textStyles.subtitle, fontWeight = FontWeight.SemiBold)
                if (draft.backend == MixUiBackend.Miuix) {
                    GlassTabs(listOf(stringResource(R.string.theme_editor_default), "Monet"), if (draft.monet) 1 else 0,
                        { if (!saving) draft = draft.copy(monet = it == 1) }, Modifier.fillMaxWidth())
                } else {
                    GlassTabs(listOf(stringResource(R.string.theme_editor_default), "iOS", "Nothing"), draft.mochiStyle.value,
                        { if (!saving) draft = draft.copy(mochiStyle = AppMochiThemeStyle.fromValue(it)) },
                        Modifier.fillMaxWidth())
                }
            }
            Column(Modifier.testTag("theme-brightness-tabs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme_editor_brightness), style = MixTheme.textStyles.subtitle, fontWeight = FontWeight.SemiBold)
                GlassTabs(listOf(stringResource(R.string.theme_editor_system), stringResource(R.string.theme_editor_light), stringResource(R.string.theme_editor_dark)), draft.brightness,
                    { if (!saving) draft = draft.copy(brightness = it) }, Modifier.fillMaxWidth())
            }
            if (draft.backend == MixUiBackend.Miuix) {
                Column(Modifier.testTag("theme-navigation-tabs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.theme_editor_navigation_style), style = MixTheme.textStyles.subtitle, fontWeight = FontWeight.SemiBold)
                    GlassTabs(listOf("iOS", "HyperOS"), draft.navigationStyle.value,
                        { if (!saving) draft = draft.copy(navigationStyle = AppNavigationBarStyle.fromValue(it)) },
                        Modifier.fillMaxWidth())
                }
            }
            if (saveFailed) {
                Text(stringResource(R.string.module_theme_mode_save_failed),
                    modifier = Modifier.testTag("theme-save-error"),
                    style = MixTheme.textStyles.footnote1)
            }
        }
    }
}
}
