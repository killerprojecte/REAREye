package hk.uwu.reareye.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import hk.uwu.reareye.repository.presetpack.PresetPackManifest
import hk.uwu.reareye.repository.presetpack.PresetPackRelease
import hk.uwu.reareye.repository.presetpack.PresetPackRepository
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

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
    val colorScheme = MiuixTheme.colorScheme
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
    val icon = if (isUpdate) MiuixIcons.Download else Icons.Rounded.ErrorOutline
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
    var installed by remember { mutableStateOf<PresetPackInstalled?>(null) }
    var staged by remember { mutableStateOf<PresetPackManifest?>(null) }
    var latest by remember { mutableStateOf<PresetPackRelease?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refreshInstalled() {
        scope.launch(Dispatchers.IO) {
            val result = repository.readInstalled()
            withContext(Dispatchers.Main) {
                installed = result.getOrNull()
                result.exceptionOrNull()?.let { message = it.message }
            }
        }
    }

    LaunchedEffect(show) {
        if (show) {
            staged = withContext(Dispatchers.IO) {
                runCatching {
                    repository.stagedFile().takeIf { it.isFile }
                        ?.let { hk.uwu.reareye.repository.presetpack.PresetPackValidator.validate(it) }
                }
                    .getOrNull()
            }
            refreshInstalled()
        }
    }

    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            busy = true
            message = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    repository.import(uri) { done, total ->
                        progress = done to total
                    }
                }
                busy = false
                progress = null
                result.fold(
                    onSuccess = { manifest ->
                        staged = manifest; message =
                        "已导入 ${manifest.packVersion}，点击应用覆盖当前版本"
                    },
                    onFailure = { error -> message = error.message ?: "导入失败" },
                )
            }
        }

    OverlayDialog(
        show = show,
        title = androidx.compose.ui.res.stringResource(R.string.preset_pack_title),
        summary = androidx.compose.ui.res.stringResource(R.string.preset_pack_dialog_summary),
        onDismissRequest = { if (!busy) onDismissRequest() },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("当前版本：${installed?.manifest?.packVersion ?: "未安装"}")
                    installed?.let { Text("文件：${formatBytes(it.file.length())}") }
                    installed?.let { Text("SHA-256：${it.sha256.take(16)}…") }
                    staged?.let { Text("待应用版本：${it.packVersion}") }
                    progress?.let { (done, total) ->
                        Text(
                            if (total > 0) "处理中：${formatBytes(done)} / ${formatBytes(total)}" else "处理中：${
                                formatBytes(
                                    done
                                )
                            }"
                        )
                    }
                    message?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { repository.checkLatest() } }
                                .onSuccess { release ->
                                    latest = release
                                    message = "发现版本 ${release.version}"
                                }
                                .onFailure { message = it.message ?: "检查更新失败" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("检查 GitHub") }
                Button(
                    onClick = {
                        importer.launch(
                            arrayOf(
                                "application/octet-stream",
                                "application/zip",
                                "*/*"
                            )
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("手动导入") }
            }

            latest?.let { release ->
                Button(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.downloadLatest(release) { done, total ->
                                        progress = done to total
                                    }
                                }
                            }
                            busy = false
                            progress = null
                            result.fold(
                                onSuccess = { manifest ->
                                    staged = manifest; message =
                                    "已下载 ${manifest.packVersion}，点击应用"
                                },
                                onFailure = { message = it.message ?: "下载失败" },
                            )
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("下载 ${release.version}")
                        Icon(imageVector = MiuixIcons.Download, contentDescription = null)
                    }
                }
            }

            staged?.let { manifest ->
                Button(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { repository.applyStaged() }
                            busy = false
                            result.fold(
                                onSuccess = { value ->
                                    installed = value
                                    staged = null
                                    message = "已应用 ${manifest.packVersion}，重启目标应用后生效"
                                    onApplied()
                                },
                                onFailure = { message = it.message ?: "应用失败，当前版本未改变" },
                            )
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("应用并覆盖当前版本") }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { refreshInstalled() },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("重新校验") }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val result =
                                withContext(Dispatchers.IO) { repository.removeInstalled() }
                            busy = false
                            result.fold(
                                onSuccess = {
                                    installed = null; staged = null; message =
                                    "已删除活动资源包，目标侧不会注入"
                                },
                                onFailure = { message = it.message ?: "删除失败" },
                            )
                            if (result.isSuccess) onApplied()
                        }
                    },
                    enabled = !busy && installed != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError,
                    ),
                ) { Text("删除") }
            }
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    if (value < 1024L * 1024L) return "%.1f KB".format(value / 1024f)
    if (value < 1024L * 1024L * 1024L) return "%.1f MB".format(value / (1024f * 1024f))
    return "%.1f GB".format(value / (1024f * 1024f * 1024f))
}
