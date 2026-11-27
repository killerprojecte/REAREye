package hk.uwu.reareye.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.mixui.components.BottomSheetAction
import hk.uwu.mixui.components.BottomSheetActionButton
import hk.uwu.mixui.components.BottomSheetActionIcon
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.GlassBottomSheet
import hk.uwu.mixui.components.HorizontalDivider
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.Tabs
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.icons.Icons as UiIcons
import hk.uwu.mixui.theme.MixTheme
import hk.uwu.reareye.R
import hk.uwu.reareye.service.PresetPackDownloadPhase

/** Pure presentation. Both tabs share the service state; switching never starts an operation. */
@Composable
internal fun PresetPackSheet(
    show: Boolean,
    state: PresetPackSheetState,
    onDismissRequest: () -> Unit,
    onAction: (PresetPackAction) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val cloudScroll = rememberScrollState()
    val localScroll = rememberScrollState()
    val scroll = if (selectedTab == 0) cloudScroll else localScroll
    GlassBottomSheet(
        show = show,
        title = stringResource(R.string.preset_pack_title),
        allowDismiss = !state.localBusy,
        onDismissRequest = { if (!state.localBusy) onDismissRequest() },
        isContentScrolled = scroll.value > 0,
        startAction = {
            BottomSheetActionButton(
                onClick = { onAction(PresetPackAction.REMOVE) },
                enabled = state.actionsEnabled && state.canRemove,
                modifier = Modifier.testTag("preset-pack-uninstall"),
            ) {
                Icon(UiIcons.Delete, stringResource(R.string.preset_sheet_uninstall),
                    tint = MixTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            }
        },
        endAction = {
            BottomSheetActionButton(
                onClick = { if (!state.localBusy) onDismissRequest() },
                enabled = !state.localBusy,
                modifier = Modifier.testTag("preset-pack-close"),
            ) {
                BottomSheetActionIcon(BottomSheetAction.Close, Icons.Outlined.Close,
                    stringResource(R.string.preset_sheet_close))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            CurrentVersion(state)
            Spacer(Modifier.height(16.dp))
            Tabs(
                tabs = listOf(stringResource(R.string.preset_sheet_tab_cloud), stringResource(R.string.preset_sheet_tab_local)),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.fillMaxWidth().testTag("preset-pack-tabs"),
            )
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("preset-pack-body")
                    .verticalScroll(scroll).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.operation?.takeIf { it != PresetPackOperation.LOAD && (it != PresetPackOperation.CHECK || selectedTab != 0) }?.let {
                    StoreProgressChip(stringResource(operationLabel(it)), progress = null)
                }
                state.notice?.let { notice ->
                    Text(notice.text, fontSize = 13.sp, lineHeight = 19.sp,
                        color = when (notice.severity) {
                            PresetPackNoticeSeverity.ERROR -> MixTheme.colorScheme.error
                            PresetPackNoticeSeverity.SUCCESS -> MixTheme.colorScheme.primary
                            PresetPackNoticeSeverity.INFO -> MixTheme.colorScheme.onSurfaceVariantSummary
                        }, modifier = Modifier.testTag("preset-pack-notice-${notice.severity.name.lowercase()}"))
                }
                state.staged?.let { staged ->
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryText(stringResource(R.string.preset_sheet_staged))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(staged.packVersion, modifier = Modifier.weight(1f), fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val applyDescription = stringResource(R.string.preset_sheet_apply)
                                StoreDownloadBadge(stringResource(R.string.preset_sheet_apply_short),
                                    onClick = { onAction(PresetPackAction.APPLY) }, enabled = state.actionsEnabled,
                                    icon = UiIcons.Basic.Check,
                                    modifier = Modifier.testTag("preset-pack-apply").semantics { contentDescription = applyDescription })
                            }
                            SecondaryText(stringResource(R.string.preset_sheet_staged_summary))
                        }
                    }
                }
                if (selectedTab == 0) CloudReleases(state, onAction) else LocalSources(state, onAction)
            }
        }
    }
}

@Composable
private fun CurrentVersion(state: PresetPackSheetState) {
    Card(modifier = Modifier.fillMaxWidth().testTag("preset-pack-current"), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SecondaryText(stringResource(R.string.preset_sheet_current_version))
            Text(when (state.localStatus) {
                PresetPackLocalStatus.LOADING -> stringResource(R.string.preset_sheet_loading)
                PresetPackLocalStatus.NOT_INSTALLED -> stringResource(R.string.preset_sheet_missing)
                PresetPackLocalStatus.INVALID -> stringResource(R.string.preset_sheet_invalid)
                PresetPackLocalStatus.READY -> state.current?.version.orEmpty()
            }, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (state.localStatus == PresetPackLocalStatus.INVALID) MixTheme.colorScheme.error else MixTheme.colorScheme.onSurface)
            state.current?.let {
                SecondaryText(stringResource(R.string.preset_sheet_metadata, formatPresetPackBytes(it.bytes), it.entryCount))
            }
        }
    }
}

@Composable
private fun CloudReleases(state: PresetPackSheetState, onAction: (PresetPackAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("preset-pack-cloud-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preset_sheet_available_versions), modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            val checkDescription = stringResource(R.string.preset_sheet_check_updates)
            StoreDownloadBadge("GitHub", onClick = { onAction(PresetPackAction.CHECK) },
                enabled = state.actionsEnabled, icon = UiIcons.Refresh,
                modifier = Modifier.testTag("preset-pack-check").semantics { contentDescription = checkDescription })
        }
        if (state.operation == PresetPackOperation.CHECK) {
            StoreProgressChip(stringResource(R.string.preset_sheet_checking), progress = null)
        }
        val release = state.latest ?: state.download.release
        if (release == null) {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                SecondaryText(stringResource(R.string.preset_sheet_no_release))
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(release.version, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SecondaryText(release.tagName)
                    HorizontalDivider(thickness = 0.5.dp, color = MixTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ReleaseAssetRow(state, onAction)
                }
            }
        }
        if (state.download.running) SecondaryText(stringResource(R.string.preset_sheet_background))
        state.download.message?.takeIf { !state.download.running }?.let {
            Text(it, fontSize = 13.sp, lineHeight = 19.sp,
                color = if (state.download.toPresentation().phase == PresetPackDownloadPhase.FAILED)
                    MixTheme.colorScheme.error else MixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun ReleaseAssetRow(state: PresetPackSheetState, onAction: (PresetPackAction) -> Unit) {
    val release = state.latest ?: state.download.release ?: return
    val transfer = state.download.toPresentation()
    Row(Modifier.fillMaxWidth().testTag("preset-pack-release-row"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(release.assetName, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (transfer.phase == PresetPackDownloadPhase.IDLE) {
                SecondaryText(if (release.assetSize > 0) formatPresetPackBytes(release.assetSize)
                    else stringResource(R.string.preset_sheet_unknown_size))
            } else {
                SecondaryText(stringResource(transferLabel(transfer.phase)))
                SecondaryText(transfer.total?.let {
                    stringResource(R.string.preset_sheet_bytes, formatPresetPackBytes(transfer.downloaded), formatPresetPackBytes(it))
                } ?: formatPresetPackBytes(transfer.downloaded))
            }
        }
        if (state.download.running) {
            val progress = transfer.progress.takeIf { transfer.phase == PresetPackDownloadPhase.DOWNLOADING }
            val text = when (transfer.phase) {
                PresetPackDownloadPhase.PROCESSING -> stringResource(R.string.preset_sheet_processing_short)
                PresetPackDownloadPhase.CONNECTING -> stringResource(R.string.preset_sheet_connecting_short)
                else -> transfer.percent?.let { stringResource(R.string.preset_sheet_percent, it) }
                    ?: stringResource(R.string.preset_sheet_downloading_short)
            }
            val pauseDescription = stringResource(R.string.preset_sheet_pause)
            StoreProgressChip(text, progress, onClick = { onAction(PresetPackAction.PAUSE) }, enabled = !state.localBusy,
                modifier = Modifier.testTag("preset-pack-progress").semantics {
                    contentDescription = pauseDescription
                    progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
                })
        } else {
            StoreDownloadBadge(stringResource(when (transfer.phase) {
                PresetPackDownloadPhase.PAUSED -> R.string.preset_sheet_resume
                PresetPackDownloadPhase.FAILED -> R.string.preset_sheet_retry
                else -> R.string.preset_sheet_download_short
            }), onClick = { onAction(PresetPackAction.DOWNLOAD) }, enabled = state.actionsEnabled,
                modifier = Modifier.testTag("preset-pack-download"))
        }
    }
}

@Composable
private fun LocalSources(state: PresetPackSheetState, onAction: (PresetPackAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("preset-pack-local-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, tint = MixTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.preset_sheet_import), modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                StoreDownloadBadge(stringResource(R.string.preset_sheet_choose_file),
                    onClick = { onAction(PresetPackAction.IMPORT) }, enabled = state.actionsEnabled,
                    icon = UiIcons.Basic.ArrowRight, modifier = Modifier.testTag("preset-pack-import"))
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudDownload, null, tint = MixTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.preset_sheet_cloud), modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    StoreDownloadBadge(stringResource(R.string.preset_sheet_open),
                        onClick = { onAction(PresetPackAction.CLOUD) }, enabled = !state.localBusy,
                        icon = UiIcons.Link, modifier = Modifier.testTag("preset-pack-cloud-drive"))
                }
                SecondaryText(stringResource(R.string.preset_sheet_cloud_summary))
            }
        }
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(text, color = MixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp, lineHeight = 19.sp)
}

private fun operationLabel(operation: PresetPackOperation): Int = when (operation) {
    PresetPackOperation.LOAD, PresetPackOperation.REVALIDATE -> R.string.preset_sheet_loading
    PresetPackOperation.CHECK -> R.string.preset_sheet_checking
    PresetPackOperation.IMPORT -> R.string.preset_sheet_importing
    PresetPackOperation.APPLY -> R.string.preset_sheet_applying
    PresetPackOperation.REMOVE -> R.string.preset_sheet_removing
    PresetPackOperation.PERMISSION -> R.string.preset_sheet_connecting
}

private fun transferLabel(phase: PresetPackDownloadPhase): Int = when (phase) {
    PresetPackDownloadPhase.CONNECTING, PresetPackDownloadPhase.IDLE -> R.string.preset_sheet_connecting
    PresetPackDownloadPhase.DOWNLOADING -> R.string.preset_sheet_downloading
    PresetPackDownloadPhase.PROCESSING -> R.string.preset_sheet_processing
    PresetPackDownloadPhase.PAUSED -> R.string.preset_sheet_paused
    PresetPackDownloadPhase.FAILED -> R.string.preset_sheet_failed
    PresetPackDownloadPhase.COMPLETED -> R.string.preset_sheet_completed
}
