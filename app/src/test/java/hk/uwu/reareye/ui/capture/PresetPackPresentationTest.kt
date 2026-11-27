package hk.uwu.reareye.ui.capture

import hk.uwu.reareye.repository.presetpack.PresetPackManifest
import hk.uwu.reareye.service.PresetPackDownloadPhase
import hk.uwu.reareye.service.PresetPackDownloadState
import hk.uwu.reareye.ui.components.PresetPackLocalStatus
import hk.uwu.reareye.ui.components.PresetPackOperation
import hk.uwu.reareye.ui.components.PresetPackSheetState
import hk.uwu.reareye.ui.components.toPresentation
import org.junit.Assert.*
import org.junit.Test

/** No Android runtime, network, files or services are touched. */
class PresetPackPresentationTest {
    @Test fun zeroHalfAndFullUseOneBoundedRatio() {
        listOf(0L to 0, 512L to 50, 1024L to 100).forEach { (done, expected) ->
            val value = PresetPackDownloadState(running = true, downloaded = done, total = 1024).toPresentation()
            assertEquals(expected, value.percent)
            assertEquals(expected / 100f, value.progress!!, 0.0001f)
            assertEquals(if (expected == 100) PresetPackDownloadPhase.PROCESSING else PresetPackDownloadPhase.DOWNLOADING, value.phase)
        }
    }

    @Test fun unknownAndZeroTotalNeverInventPercentage() {
        listOf(-1L, 0L, Long.MIN_VALUE).forEach { total ->
            val value = PresetPackDownloadState(running = true, downloaded = 500, total = total).toPresentation()
            assertNull(value.total)
            assertNull(value.progress)
            assertNull(value.percent)
            assertEquals(500L, value.downloaded)
            assertEquals(PresetPackDownloadPhase.CONNECTING, value.phase)
        }
    }

    @Test fun negativeOversizedAndLongMaxProgressIsSafe() {
        assertEquals(0, PresetPackDownloadState(downloaded = -10, total = 100).toPresentation().percent)
        assertEquals(100, PresetPackDownloadState(downloaded = Long.MAX_VALUE, total = 1).toPresentation().percent)
        assertEquals(29, PresetPackDownloadState(downloaded = 29, total = 100).toPresentation().percent)
        val enormous = PresetPackDownloadState(downloaded = Long.MAX_VALUE / 2 + 1, total = Long.MAX_VALUE).toPresentation()
        assertEquals(50, enormous.percent)
        assertEquals(0.5f, enormous.progress!!, 0.0001f)
    }

    @Test fun manifestWhileRunningDoesNotAnnounceCompletion() {
        val unfinished = PresetPackDownloadState(running = true, downloaded = 100, total = 100,
            manifest = presetPresentationManifest(), phase = PresetPackDownloadPhase.COMPLETED)
        assertEquals(PresetPackDownloadPhase.PROCESSING, unfinished.toPresentation().phase)
        assertEquals(PresetPackDownloadPhase.COMPLETED, unfinished.copy(running = false).toPresentation().phase)
    }

    @Test fun explicitPhasesNeverDependOnMessageLanguage() {
        listOf(PresetPackDownloadPhase.PAUSED, PresetPackDownloadPhase.FAILED,
            PresetPackDownloadPhase.COMPLETED).forEach { phase ->
            assertEquals(phase, PresetPackDownloadState(phase = phase, message = "arbitrary / 任意文案").toPresentation().phase)
        }
        assertEquals(PresetPackDownloadPhase.PROCESSING, PresetPackDownloadState(
            running = true, phase = PresetPackDownloadPhase.PROCESSING).toPresentation().phase)
    }

    @Test fun onlyLocalWorkBlocksDismissAndBackgroundWorkBlocksMutations() {
        val background = PresetPackSheetState(download = PresetPackDownloadState(running = true))
        assertFalse(background.localBusy)
        assertFalse(background.actionsEnabled)
        PresetPackOperation.entries.forEach { operation ->
            val local = background.copy(operation = operation)
            assertTrue(local.localBusy)
            assertFalse(local.actionsEnabled)
        }
        assertTrue(PresetPackSheetState(localStatus = PresetPackLocalStatus.INVALID).canRemove)
        assertFalse(PresetPackSheetState(localStatus = PresetPackLocalStatus.NOT_INSTALLED).canRemove)
    }
}

internal fun presetPresentationManifest() = PresetPackManifest(
    format = "rpp", formatVersion = 1, packId = "fixture", packVersion = "2.0-fixture",
    generatedAt = "2000-01-01T00:00:00Z", sourceCommit = null, sourceRoot = "fixture", payloadRoot = "payload",
    minModuleVersion = "0.0.0", supportedPackages = emptyList(), entryCount = 1,
    uncompressedBytes = 1024, compressedPayloadBytes = 512, entries = emptyList(),
)
