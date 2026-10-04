package hk.uwu.reareye.repository.presetpack

import android.content.Context
import android.net.Uri
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class PresetPackRelease(
    val version: String,
    val tagName: String,
    val assetUrl: String,
    val assetName: String,
    val assetSize: Long,
    val releaseUrl: String,
)

data class PresetPackInstalled(
    val manifest: PresetPackManifest,
    val file: File,
    val sha256: String,
)

class PresetPackRepository(
    context: Context,
    private val prefs: PrefsManager,
) {
    companion object {
        private val downloadMutex = Mutex()
    }

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "preset-pack")
    private val activeFile = File(root, "active.rpp")
    private val stagingFile = File(root, "staging.rpp")
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    fun activeFile(): File = activeFile

    fun stagedFile(): File = stagingFile

    fun readInstalled(): Result<PresetPackInstalled?> = runCatching {
        if (!activeFile.isFile) return@runCatching null
        val manifest = PresetPackValidator.validate(activeFile)
        PresetPackInstalled(manifest, activeFile, activeFile.sha256())
    }

    suspend fun checkLatest(): PresetPackRelease {
        val request = Request.Builder()
            .url(PresetPackContract.GITHUB_LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "REAREye/${appContext.packageName}")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}")
            val json = JSONObject(response.body.string())
            val tag = json.optString("tag_name").ifBlank { error("GitHub release has no tag") }
            val assets = json.optJSONArray("assets") ?: error("GitHub release has no assets")
            var selected: JSONObject? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (name.startsWith(PresetPackContract.RELEASE_ASSET_PREFIX) && name.endsWith(".rpp")) {
                    selected = asset
                    break
                }
            }
            val asset = selected ?: error("GitHub release has no RPP asset")
            val name = asset.optString("name").ifBlank { error("RPP asset has no name") }
            val version =
                name.removePrefix(PresetPackContract.RELEASE_ASSET_PREFIX).removeSuffix(".rpp")
            PresetPackRelease(
                version = version,
                tagName = tag,
                assetUrl = asset.optString("browser_download_url").ifBlank {
                    error("RPP asset has no download URL")
                },
                assetName = name,
                assetSize = asset.optLong("size", -1L),
                releaseUrl = json.optString("html_url"),
            )
        }
    }

    suspend fun downloadLatest(
        release: PresetPackRelease,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): PresetPackManifest = downloadMutex.withLock {
        val downloader = PresetPackDownloader(client, File(root, "download"))
        val downloaded = downloader.download(
            release.assetUrl,
            "${release.tagName}/${release.assetName}",
            release.assetSize,
            onProgress,
        )
        // Validate before replacing an existing staged pack. Corrupt bytes must not
        // become a permanent checkpoint that makes every retry fail validation.
        val manifest = try {
            PresetPackValidator.validate(downloaded)
        } catch (error: Exception) {
            downloader.clear()
            throw error
        }
        currentCoroutineContext().ensureActive()
        atomicReplace(downloaded, stagingFile)
        downloader.clear()
        manifest
    }

    fun import(
        uri: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<PresetPackManifest> = runCatching {
        val resolver = appContext.contentResolver
        val source = resolver.openInputStream(uri) ?: error("Unable to open selected RPP")
        source.use { writeStaging(it, -1L, onProgress) }
        validateStaging()
    }

    fun applyStaged(): Result<PresetPackInstalled> = runCatching {
        val manifest = validateStaging()
        val temporaryActive = File(root, "active.rpp.tmp")
        try {
            root.mkdirs()
            stagingFile.copyTo(temporaryActive, overwrite = true)
            val hash = temporaryActive.sha256()

            val currentSlot = prefs.getString(ConfigKeys.PRESET_REMOTE_SLOT, "")
            val nextSlot = PresetPackContract.otherSlot(currentSlot)
            val remoteName = PresetPackContract.remoteFileName(nextSlot)
            check(prefs.writeRemoteFile(remoteName, temporaryActive)) {
                "RemoteFile service is unavailable; restart the module service and retry"
            }
            atomicReplace(temporaryActive, activeFile)
            prefs.putString(ConfigKeys.PRESET_REMOTE_SLOT, nextSlot)
            prefs.putString(ConfigKeys.PRESET_REMOTE_VERSION, manifest.packVersion)
            prefs.putString(ConfigKeys.PRESET_REMOTE_HASH, hash)
            stagingFile.delete()
            PresetPackInstalled(manifest, activeFile, hash)
        } finally {
            if (temporaryActive.exists()) temporaryActive.delete()
        }
    }

    fun removeInstalled(): Result<Unit> = runCatching {
        activeFile.delete()
        stagingFile.delete()
        val slot = prefs.getString(ConfigKeys.PRESET_REMOTE_SLOT, "")
        if (slot.isNotBlank()) {
            prefs.deleteRemoteFile(PresetPackContract.remoteFileName(slot))
        }
        prefs.putString(ConfigKeys.PRESET_REMOTE_SLOT, "")
        prefs.putString(ConfigKeys.PRESET_REMOTE_VERSION, "")
        prefs.putString(ConfigKeys.PRESET_REMOTE_HASH, "")
    }

    private fun validateStaging(): PresetPackManifest {
        check(stagingFile.isFile) { "No staged RPP file" }
        return PresetPackValidator.validate(stagingFile)
    }

    private fun writeStaging(
        input: InputStream,
        expectedLength: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        root.mkdirs()
        val temporary = File(root, "staging.rpp.download")
        var copied = 0L
        input.buffered().use { source ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    check(copied <= PresetPackContract.MAX_PACK_BYTES) { "RPP file exceeds size limit" }
                    output.write(buffer, 0, count)
                    onProgress(copied, expectedLength)
                }
            }
        }
        check(copied > 0L) { "RPP download is empty" }
        if (expectedLength >= 0L) check(copied == expectedLength) {
            "RPP download size mismatch: expected=$expectedLength actual=$copied"
        }
        atomicReplace(temporary, stagingFile)
    }

    private fun atomicReplace(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            check(source.delete() || !source.exists()) { "Unable to remove temporary RPP file" }
        }
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
