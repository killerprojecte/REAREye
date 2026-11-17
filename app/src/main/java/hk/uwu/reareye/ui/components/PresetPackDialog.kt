package hk.uwu.reareye.ui.components

import hk.uwu.mixui.icons.Icons as UiIcons

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.presetpack.PresetPackInstalled
import hk.uwu.reareye.repository.presetpack.PresetPackRelease
import hk.uwu.reareye.repository.presetpack.PresetPackRepository
import hk.uwu.reareye.service.PresetPackDownloadService
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.CardDefaults
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.theme.MixTheme
import hk.uwu.mixui.components.PressFeedbackType

enum class PresetPackLocalStatus {
    LOADING,
    NOT_INSTALLED,
    READY,
    INVALID,
}

data class PresetPackLocalSnapshot(
    val status: PresetPackLocalStatus,
    val installed: PresetPackInstalled? = null,
    val error: String? = null,
)

data class PresetPackHomeSnapshot(
    val local: PresetPackLocalSnapshot,
    val latest: PresetPackRelease? = null,
)

@Composable
fun rememberPresetPackHomeSnapshot(refreshToken: Any? = Unit): PresetPackHomeSnapshot {
    val context = LocalContext.current
    val prefs = remember { context.getPrefsManager() }
    var snapshot by remember {
        mutableStateOf(
            PresetPackHomeSnapshot(
                local = PresetPackLocalSnapshot(PresetPackLocalStatus.LOADING),
            ),
        )
    }
    LaunchedEffect(refreshToken) {
        val local = withContext(Dispatchers.IO) {
            PresetPackRepository(context, prefs).readInstalled().fold(
                onSuccess = { installed ->
                    if (installed == null) {
                        PresetPackLocalSnapshot(PresetPackLocalStatus.NOT_INSTALLED)
                    } else {
                        PresetPackLocalSnapshot(PresetPackLocalStatus.READY, installed)
                    }
                },
                onFailure = { error ->
                    PresetPackLocalSnapshot(
                        status = PresetPackLocalStatus.INVALID,
                        error = error.message ?: "RPP validation failed",
                    )
                },
            )
        }
        // Apply local changes immediately; a slow update check must not keep a stale notice visible.
        snapshot = snapshot.copy(
            local = local,
            latest = snapshot.latest.takeIf { local.status == PresetPackLocalStatus.READY },
        )
        val latest = if (local.status == PresetPackLocalStatus.READY) {
            withContext(Dispatchers.IO) {
                runCatching { PresetPackRepository(context, prefs).checkLatest() }.getOrNull()
            }
        } else {
            null
        }
        snapshot = PresetPackHomeSnapshot(local = local, latest = latest)
    }
    return snapshot
}

fun PresetPackRelease.isNewerThan(currentVersion: String?): Boolean {
    if (currentVersion.isNullOrBlank()) return true
    val latestParts = Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()
    val currentParts =
        Regex("\\d+").findAll(currentVersion).map { it.value.toIntOrNull() ?: 0 }.toList()
    if (latestParts.isEmpty() || currentParts.isEmpty()) return version != currentVersion
    val size = maxOf(latestParts.size, currentParts.size)
    for (index in 0 until size) {
        val latest = latestParts.getOrElse(index) { 0 }
        val current = currentParts.getOrElse(index) { 0 }
        if (latest != current) return latest > current
    }
    return false
}

@Composable
fun PresetPackStatusCard(
    snapshot: PresetPackLocalSnapshot,
    updateAvailable: Boolean = false,
    latestVersion: String? = null,
    useMonetColors: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MixTheme.colorScheme
    val isUpdate = snapshot.status == PresetPackLocalStatus.READY && updateAvailable
    val background = when {
        isUpdate && useMonetColors -> lerp(colorScheme.surface, colorScheme.primary, 0.2f)
        isUpdate -> Color(0xFFFFF3CD)
        useMonetColors -> lerp(colorScheme.surface, colorScheme.error, 0.2f)
        else -> Color(0xFFFDE9E9)
    }
    val iconTint = when {
        isUpdate && useMonetColors -> lerp(
            colorScheme.onSurfaceVariantSummary,
            colorScheme.primary,
            0.86f
        )

        isUpdate -> Color(0xFFE0A100)
        useMonetColors -> lerp(colorScheme.onSurfaceVariantSummary, colorScheme.error, 0.82f)
        else -> Color(0xFFD94B4B)
    }
    val titleTint = when {
        useMonetColors -> lerp(
            colorScheme.onSurface,
            if (isUpdate) colorScheme.primary else colorScheme.error,
            0.4f,
        )

        isUpdate -> Color(0xFF7A5A00)
        else -> Color(0xFF8D3030)
    }
    val summaryTint = when {
        useMonetColors -> lerp(
            colorScheme.onSurfaceVariantSummary,
            if (isUpdate) colorScheme.primary else colorScheme.error,
            0.24f,
        )

        isUpdate -> Color(0xFF8A6B00)
        else -> Color(0xFF9D4A4A)
    }
    val icon = if (isUpdate) UiIcons.Download else Icons.Rounded.ErrorOutline
    val title = when {
        isUpdate -> androidx.compose.ui.res.stringResource(R.string.preset_pack_update_title)
        snapshot.status == PresetPackLocalStatus.NOT_INSTALLED ->
            androidx.compose.ui.res.stringResource(R.string.preset_pack_missing_title)

        snapshot.status == PresetPackLocalStatus.INVALID ->
            androidx.compose.ui.res.stringResource(R.string.preset_pack_invalid_title)

        else -> androidx.compose.ui.res.stringResource(R.string.preset_pack_title)
    }
    val summary = when {
        isUpdate -> androidx.compose.ui.res.stringResource(
            R.string.preset_pack_update_summary,
            latestVersion.orEmpty(),
        )

        snapshot.status == PresetPackLocalStatus.NOT_INSTALLED ->
            androidx.compose.ui.res.stringResource(R.string.preset_pack_missing_summary)

        snapshot.status == PresetPackLocalStatus.INVALID ->
            androidx.compose.ui.res.stringResource(R.string.preset_pack_invalid_summary)

        else -> androidx.compose.ui.res.stringResource(R.string.preset_pack_summary)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = background),
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    color = titleTint,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summary,
                    color = summaryTint,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
fun PresetPackDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onApplied: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getPrefsManager() }
    val repository = remember { PresetPackRepository(context, prefs) }
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf(PresetPackSheetState(operation = PresetPackOperation.LOAD)) }
    val downloadState by PresetPackDownloadService.state.collectAsState()

    fun notice(resource: Int, severity: PresetPackNoticeSeverity, vararg args: Any) {
        ui = ui.copy(notice = PresetPackNotice(context.getString(resource, *args), severity))
    }
    fun failure(error: Throwable) {
        notice(R.string.preset_sheet_error, PresetPackNoticeSeverity.ERROR,
            error.localizedMessage ?: error.javaClass.simpleName)
    }
    suspend fun refreshInstalled(reportSuccess: Boolean = false) {
        val result = withContext(Dispatchers.IO) {
            repository.readInstalled().map { value -> value?.toDetails() }
        }
        ui = ui.copy(current = result.getOrNull(), localStatus = when {
            result.isFailure -> PresetPackLocalStatus.INVALID
            result.getOrNull() == null -> PresetPackLocalStatus.NOT_INSTALLED
            else -> PresetPackLocalStatus.READY
        })
        result.exceptionOrNull()?.let(::failure)
        if (reportSuccess && result.isSuccess && result.getOrNull() != null) {
            notice(R.string.preset_sheet_validated, PresetPackNoticeSeverity.SUCCESS)
        }
    }
    // Acquire the local gate synchronously, before launching: rapid taps cannot race recomposition.
    fun localTask(operation: PresetPackOperation, block: suspend () -> Unit) {
        if (ui.localBusy || PresetPackDownloadService.state.value.running) return
        ui = ui.copy(operation = operation, notice = null)
        scope.launch {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error) }
            finally { ui = ui.copy(operation = null) }
        }
    }

    LaunchedEffect(downloadState.release, downloadState.running, downloadState.manifest) {
        ui = ui.copy(
            latest = downloadState.release ?: ui.latest,
            staged = if (!downloadState.running && downloadState.manifest != null) downloadState.manifest else ui.staged,
        )
    }
    LaunchedEffect(show) {
        if (show) {
            // Reopening observes service-owned progress; it neither starts nor pauses a download.
            ui = ui.copy(operation = PresetPackOperation.LOAD)
            try {
                refreshInstalled()
                val staged = withContext(Dispatchers.IO) {
                    runCatching {
                        repository.stagedFile().takeIf { it.isFile }
                            ?.let { hk.uwu.reareye.repository.presetpack.PresetPackValidator.validate(it) }
                    }.getOrNull()
                }
                ui = ui.copy(staged = staged)
            } finally { ui = ui.copy(operation = null) }
        }
    }

    var pendingDownload by remember { mutableStateOf<PresetPackRelease?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Permission affects notification visibility, not whether download may start.
        pendingDownload?.let { PresetPackDownloadService.start(context, it) }
        pendingDownload = null
        ui = ui.copy(operation = null)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            ui = ui.copy(operation = null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { repository.import(uri).getOrThrow() }
                PresetPackDownloadService.clearResult()
                ui = ui.copy(staged = manifest)
                notice(R.string.preset_sheet_imported, PresetPackNoticeSeverity.SUCCESS, manifest.packVersion)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error) }
            finally { ui = ui.copy(operation = null) }
        }
    }

    PresetPackSheet(show, ui.copy(download = downloadState),
        onDismissRequest = { if (!ui.localBusy) onDismissRequest() },
        onAction = action@{ action ->
            if (ui.localBusy) return@action
            if (action != PresetPackAction.CLOUD && action != PresetPackAction.PAUSE &&
                PresetPackDownloadService.state.value.running) return@action
            when (action) {
                PresetPackAction.CHECK -> localTask(PresetPackOperation.CHECK) {
                    val release = withContext(Dispatchers.IO) { repository.checkLatest() }
                    ui = ui.copy(latest = release)
                    notice(R.string.preset_sheet_found, PresetPackNoticeSeverity.INFO, release.version)
                }
                PresetPackAction.DOWNLOAD -> ui.latest?.let { release ->
                    ui = ui.copy(notice = null)
                    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pendingDownload = release
                        ui = ui.copy(operation = PresetPackOperation.PERMISSION)
                        try { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        catch (error: Exception) { pendingDownload = null; ui = ui.copy(operation = null); failure(error) }
                    } else PresetPackDownloadService.start(context, release)
                }
                PresetPackAction.CLOUD -> {
                    if (!openPresetPackCloud(context)) {
                        notice(R.string.preset_sheet_cloud_unavailable, PresetPackNoticeSeverity.ERROR)
                    }
                }
                PresetPackAction.IMPORT -> {
                    ui = ui.copy(operation = PresetPackOperation.IMPORT, notice = null)
                    try { importer.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }
                    catch (error: Exception) { ui = ui.copy(operation = null); failure(error) }
                }
                PresetPackAction.PAUSE -> PresetPackDownloadService.pause(context)
                PresetPackAction.APPLY -> localTask(PresetPackOperation.APPLY) {
                    val value = withContext(Dispatchers.IO) { repository.applyStaged().getOrThrow().toDetails() }
                    PresetPackDownloadService.clearResult()
                    ui = ui.copy(current = value, staged = null, localStatus = PresetPackLocalStatus.READY)
                    notice(R.string.preset_sheet_applied, PresetPackNoticeSeverity.SUCCESS, value.version)
                    onApplied()
                }
                PresetPackAction.REVALIDATE -> localTask(PresetPackOperation.REVALIDATE) { refreshInstalled(reportSuccess = true) }
                PresetPackAction.REMOVE -> localTask(PresetPackOperation.REMOVE) {
                    withContext(Dispatchers.IO) { repository.removeInstalled().getOrThrow() }
                    PresetPackDownloadService.clearResult()
                    ui = ui.copy(current = null, staged = null, localStatus = PresetPackLocalStatus.NOT_INSTALLED)
                    notice(R.string.preset_sheet_removed, PresetPackNoticeSeverity.SUCCESS)
                    onApplied()
                }
            }
        },
    )
}

private fun PresetPackInstalled.toDetails() = PresetPackDetails(
    version = manifest.packVersion, bytes = file.length(), entryCount = manifest.entryCount,
    generatedAt = manifest.generatedAt, sha256 = sha256,
)

internal fun presetPackCloudIntent(): android.content.Intent = android.content.Intent(
    android.content.Intent.ACTION_VIEW,
    android.net.Uri.parse(hk.uwu.reareye.repository.presetpack.PresetPackContract.CLOUD_SHARE_URL),
).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun openPresetPackCloud(context: android.content.Context): Boolean = try {
    context.startActivity(presetPackCloudIntent())
    true
} catch (_: android.content.ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
