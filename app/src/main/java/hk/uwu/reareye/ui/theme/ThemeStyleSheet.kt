package hk.uwu.reareye.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
) {
    val mode: AppThemeMode get() = AppThemeMode.fromValue(
        when {
            backend == MixUiBackend.Mochi -> listOf(6, 7, 8)[brightness]
            monet -> listOf(2, 4, 5)[brightness]
            else -> listOf(3, 0, 1)[brightness]
        }
    )
    companion object {
        fun from(mode: AppThemeMode, style: AppMochiThemeStyle) = ThemeStyleDraft(
            mode.backend, mode.value in listOf(2, 4, 5, 9, 10, 11), style,
            when (mode.value) { 0, 4, 7, 10 -> 1; 1, 5, 8, 11 -> 2; else -> 0 },
        )
    }
}

/** The caller commits both values together before dismissing. Draft changes stay in this host. */
@Composable
internal fun ThemeStyleSheet(
    show: Boolean,
    appliedMode: AppThemeMode,
    appliedStyle: AppMochiThemeStyle,
    saving: Boolean = false,
    saveFailed: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (ThemeStyleDraft) -> Unit,
) {
    var draft by remember(show) { mutableStateOf(ThemeStyleDraft.from(appliedMode, appliedStyle)) }
    MixUiHost(backend = draft.backend, colorMode = draft.mode.colorSchemeMode, mochiStyle = draft.mochiStyle.style) {
    BottomSheet(
        show = show, onDismissRequest = { if (!saving) onDismiss() }, title = stringResource(R.string.theme_editor_title),
        allowDismiss = !saving,
        startAction = {
            IconButton(onClick = onDismiss, enabled = !saving) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.theme_editor_close))
            }
        },
        endAction = {
            IconButton(onClick = { onSave(draft) }, enabled = !saving) {
                if (saving) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.theme_editor_save))
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().testTag("theme-style-sheet")
                .verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.testTag("theme-style-tabs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme_editor_style), style = MixTheme.textStyles.subtitle, fontWeight = FontWeight.SemiBold)
                Tabs(listOf("MIUIX", "Mochi"), if (draft.backend == MixUiBackend.Miuix) 0 else 1,
                    { if (!saving) draft = draft.copy(backend = if (it == 0) MixUiBackend.Miuix else MixUiBackend.Mochi) },
                    Modifier.fillMaxWidth())
            }
            Column(Modifier.testTag("theme-palette-tabs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme_editor_palette), style = MixTheme.textStyles.subtitle, fontWeight = FontWeight.SemiBold)
                if (draft.backend == MixUiBackend.Miuix) {
                    Tabs(listOf(stringResource(R.string.theme_editor_default), "Monet"), if (draft.monet) 1 else 0,
                        { if (!saving) draft = draft.copy(monet = it == 1) }, Modifier.fillMaxWidth())
                } else {
                    Tabs(listOf(stringResource(R.string.theme_editor_default), "iOS", "Nothing"), draft.mochiStyle.value,
                        { if (!saving) draft = draft.copy(mochiStyle = AppMochiThemeStyle.fromValue(it)) },
                        Modifier.fillMaxWidth())
                }
            }
            Column(Modifier.testTag("theme-brightness-tabs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme_editor_brightness), style = MixTheme.textStyles.subtitle, fontWeight = FontWeight.SemiBold)
                Tabs(listOf(stringResource(R.string.theme_editor_system), stringResource(R.string.theme_editor_light), stringResource(R.string.theme_editor_dark)), draft.brightness,
                    { if (!saving) draft = draft.copy(brightness = it) }, Modifier.fillMaxWidth())
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
