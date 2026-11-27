package hk.uwu.reareye.ui.components

import hk.uwu.reareye.repository.presetpack.PresetPackManifest
import hk.uwu.reareye.repository.presetpack.PresetPackRelease
import hk.uwu.reareye.service.PresetPackDownloadPhase
import hk.uwu.reareye.service.PresetPackDownloadState
import java.util.Locale

/** Immutable display data: rendering never reads files, starts services or opens URLs. */
internal data class PresetPackDetails(
    val version: String,
    val bytes: Long,
    val entryCount: Int,
    val generatedAt: String,
    val sha256: String,
)

internal enum class PresetPackOperation { LOAD, CHECK, IMPORT, APPLY, REVALIDATE, REMOVE, PERMISSION }
internal enum class PresetPackNoticeSeverity { INFO, SUCCESS, ERROR }
internal data class PresetPackNotice(val text: String, val severity: PresetPackNoticeSeverity)
internal enum class PresetPackAction { CHECK, DOWNLOAD, CLOUD, IMPORT, PAUSE, APPLY, REVALIDATE, REMOVE }

internal data class PresetPackSheetState(
    val localStatus: PresetPackLocalStatus = PresetPackLocalStatus.LOADING,
    val current: PresetPackDetails? = null,
    val staged: PresetPackManifest? = null,
    val latest: PresetPackRelease? = null,
    val operation: PresetPackOperation? = null,
    val download: PresetPackDownloadState = PresetPackDownloadState(),
    val notice: PresetPackNotice? = null,
) {
    val localBusy: Boolean get() = operation != null
    val actionsEnabled: Boolean get() = !localBusy && !download.running
    val canRemove: Boolean get() = localStatus == PresetPackLocalStatus.READY || localStatus == PresetPackLocalStatus.INVALID
}

internal data class PresetPackTransferPresentation(
    val phase: PresetPackDownloadPhase,
    val downloaded: Long,
    val total: Long?,
    val progress: Float?,
    val percent: Int?,
)

/** The same byte pair drives the bounded ring and label; neither calculation can overflow Long. */
internal fun PresetPackDownloadState.toPresentation(): PresetPackTransferPresentation {
    val done = downloaded.coerceAtLeast(0L)
    val knownTotal = total.takeIf { it > 0L }
    val ratio = knownTotal?.let { (done.toDouble() / it.toDouble()).coerceIn(0.0, 1.0) }
    val displayedPhase = when {
        running && (phase == PresetPackDownloadPhase.PROCESSING || phase == PresetPackDownloadPhase.COMPLETED ||
            manifest != null || (knownTotal != null && done >= knownTotal)) -> PresetPackDownloadPhase.PROCESSING
        running && (knownTotal != null || phase == PresetPackDownloadPhase.DOWNLOADING) -> PresetPackDownloadPhase.DOWNLOADING
        running -> PresetPackDownloadPhase.CONNECTING
        // A result must not be advertised until the service has ended its work.
        manifest != null -> PresetPackDownloadPhase.COMPLETED
        else -> phase
    }
    // Exact floor avoids 29/100 becoming 28% through binary floating point, without Long overflow.
    val percent = knownTotal?.let {
        (done.coerceAtMost(it).toBigInteger() * 100.toBigInteger() / it.toBigInteger()).toInt()
    }
    return PresetPackTransferPresentation(displayedPhase, done, knownTotal, ratio?.toFloat(), percent)
}

internal fun formatPresetPackBytes(value: Long): String {
    val bytes = value.coerceAtLeast(0L)
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
