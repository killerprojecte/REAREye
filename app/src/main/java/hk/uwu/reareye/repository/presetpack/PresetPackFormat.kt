package hk.uwu.reareye.repository.presetpack

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** RPP 远程文件和共享偏好使用的稳定协议常量。 */
object PresetPackContract {
    const val PACK_ID = "reareye.preset.default"
    const val FORMAT_VERSION = 1
    const val REMOTE_SLOT_A = "reareye_preset_pack_a"
    const val REMOTE_SLOT_B = "reareye_preset_pack_b"
    const val REMOTE_SLOT_A_VALUE = "a"
    const val REMOTE_SLOT_B_VALUE = "b"
    const val PAYLOAD_PREFIX = "payload/"
    const val MANIFEST_NAME = "manifest.json"
    const val GITHUB_LATEST_RELEASE =
        "https://api.github.com/repos/NekoStash/REAREye-Preset-Resources/releases/latest"
    const val RELEASE_ASSET_PREFIX = "reareye-presets-"
    const val MAX_PACK_BYTES = 300L * 1024L * 1024L

    fun remoteFileName(slot: String): String = when (slot) {
        REMOTE_SLOT_A_VALUE -> REMOTE_SLOT_A
        REMOTE_SLOT_B_VALUE -> REMOTE_SLOT_B
        else -> error("Unknown preset pack remote slot: $slot")
    }

    fun otherSlot(slot: String): String = when (slot) {
        REMOTE_SLOT_A_VALUE -> REMOTE_SLOT_B_VALUE
        REMOTE_SLOT_B_VALUE -> REMOTE_SLOT_A_VALUE
        else -> REMOTE_SLOT_A_VALUE
    }
}

data class PresetPackEntry(
    val path: String,
    val archivePath: String,
    val kind: String,
    val size: Long,
    val compressedSize: Long,
    val sha256: String,
    val compression: String,
    val required: Boolean,
)

data class PresetPackManifest(
    val format: String,
    val formatVersion: Int,
    val packId: String,
    val packVersion: String,
    val generatedAt: String,
    val sourceCommit: String?,
    val sourceRoot: String,
    val payloadRoot: String,
    val minModuleVersion: String,
    val supportedPackages: List<String>,
    val entryCount: Int,
    val uncompressedBytes: Long,
    val compressedPayloadBytes: Long,
    val entries: List<PresetPackEntry>,
) {
    companion object {
        fun parse(bytes: ByteArray): PresetPackManifest =
            parse(JSONObject(bytes.toString(Charsets.UTF_8)))

        fun parse(json: JSONObject): PresetPackManifest {
            val entriesJson = json.optJSONArray("entries") ?: throw PresetPackValidationException(
                "RPP manifest has no entries"
            )
            val entries = buildList(entriesJson.length()) {
                for (index in 0 until entriesJson.length()) {
                    val item = entriesJson.optJSONObject(index)
                        ?: throw PresetPackValidationException("RPP manifest entry $index is invalid")
                    add(
                        PresetPackEntry(
                            path = item.requireNonBlank("path"),
                            archivePath = item.requireNonBlank("archivePath"),
                            kind = item.optString("kind", "data"),
                            size = item.requireLong("size"),
                            compressedSize = item.requireLong("compressedSize"),
                            sha256 = item.requireNonBlank("sha256"),
                            compression = item.requireNonBlank("compression"),
                            required = item.optBoolean("required", true),
                        )
                    )
                }
            }
            return PresetPackManifest(
                format = json.optString("format"),
                formatVersion = json.optInt("formatVersion", -1),
                packId = json.optString("packId"),
                packVersion = json.optString("packVersion"),
                generatedAt = json.optString("generatedAt"),
                sourceCommit = json.optString("sourceCommit")
                    .takeIf { it.isNotBlank() && it != "null" },
                sourceRoot = json.optString("sourceRoot", "rear_preset"),
                payloadRoot = json.optString("payloadRoot", "payload"),
                minModuleVersion = json.optString("minModuleVersion", "0.0.0"),
                supportedPackages = json.optJSONArray("supportedPackages").toStringList(),
                entryCount = json.optInt("entryCount", -1),
                uncompressedBytes = json.optLong("uncompressedBytes", -1L),
                compressedPayloadBytes = json.optLong("compressedPayloadBytes", -1L),
                entries = entries,
            )
        }
    }
}

class PresetPackValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** 读取并完整校验一个 RPP；校验失败时不会返回部分结果。 */
object PresetPackValidator {
    fun validate(
        file: File,
        expectedPackId: String = PresetPackContract.PACK_ID
    ): PresetPackManifest {
        if (!file.isFile || file.length() <= 0L) {
            throw PresetPackValidationException("RPP file is missing or empty")
        }
        if (file.length() > PresetPackContract.MAX_PACK_BYTES) {
            throw PresetPackValidationException("RPP file is too large: ${file.length()} bytes")
        }

        try {
            ZipFile(file).use { zip ->
                val manifestEntry = zip.getEntry(PresetPackContract.MANIFEST_NAME)
                    ?: throw PresetPackValidationException("RPP manifest.json is missing")
                if (manifestEntry.isDirectory || manifestEntry.size <= 0L) {
                    throw PresetPackValidationException("RPP manifest.json is invalid")
                }
                val manifest = zip.getInputStream(manifestEntry).use { input ->
                    PresetPackManifest.parse(input.readBytes())
                }
                if (manifest.format != "rpp" || manifest.formatVersion != PresetPackContract.FORMAT_VERSION) {
                    throw PresetPackValidationException("Unsupported RPP format")
                }
                if (manifest.packId != expectedPackId) {
                    throw PresetPackValidationException("Unexpected RPP pack id: ${manifest.packId}")
                }
                if (manifest.packVersion.isBlank() || manifest.entries.isEmpty()) {
                    throw PresetPackValidationException("RPP manifest is incomplete")
                }

                val expectedNames = HashSet<String>()
                var totalSize = 0L
                var totalCompressedSize = 0L
                manifest.entries.forEach { entry ->
                    requireSafePath(entry.path)
                    requireSafePath(entry.archivePath)
                    if (!entry.archivePath.startsWith(PresetPackContract.PAYLOAD_PREFIX)) {
                        throw PresetPackValidationException("RPP entry is outside payload/: ${entry.archivePath}")
                    }
                    if (entry.archivePath != PresetPackContract.PAYLOAD_PREFIX + entry.path) {
                        throw PresetPackValidationException("RPP archive path mismatch: ${entry.path}")
                    }
                    if (!expectedNames.add(entry.archivePath)) {
                        throw PresetPackValidationException("Duplicate RPP entry: ${entry.path}")
                    }
                    val zipEntry = zip.getEntry(entry.archivePath)
                        ?: throw PresetPackValidationException("Missing RPP entry: ${entry.path}")
                    if (zipEntry.isDirectory || zipEntry.size != entry.size ||
                        zipEntry.compressedSize != entry.compressedSize
                    ) {
                        throw PresetPackValidationException("RPP size metadata mismatch: ${entry.path}")
                    }
                    val expectedCompression = when (entry.compression) {
                        "store" -> java.util.zip.ZipEntry.STORED
                        "deflate" -> java.util.zip.ZipEntry.DEFLATED
                        else -> throw PresetPackValidationException("Unknown RPP compression: ${entry.compression}")
                    }
                    if (zipEntry.method != expectedCompression) {
                        throw PresetPackValidationException("RPP compression metadata mismatch: ${entry.path}")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(zipEntry).use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                    }
                    if (!digest.digest().toHex().equals(entry.sha256, ignoreCase = true)) {
                        throw PresetPackValidationException("RPP SHA-256 mismatch: ${entry.path}")
                    }
                    totalSize += zipEntry.size
                    totalCompressedSize += zipEntry.compressedSize
                }

                val actualPayloadNames = zip.entries().asSequence()
                    .filter { it.name.startsWith(PresetPackContract.PAYLOAD_PREFIX) && !it.isDirectory }
                    .map { it.name }
                    .toSet()
                if (actualPayloadNames != expectedNames) {
                    throw PresetPackValidationException("RPP contains unlisted payload files")
                }
                if (manifest.entryCount != manifest.entries.size ||
                    manifest.uncompressedBytes != totalSize ||
                    manifest.compressedPayloadBytes != totalCompressedSize
                ) {
                    throw PresetPackValidationException("RPP aggregate metadata mismatch")
                }
                return manifest
            }
        } catch (error: PresetPackValidationException) {
            throw error
        } catch (error: Throwable) {
            throw PresetPackValidationException("Unable to read RPP: ${error.message}", error)
        }
    }

    fun requireSafePath(value: String) {
        val normalized = value.replace('\\', '/')
        val parts = normalized.split('/')
        if (normalized.isBlank() || normalized.startsWith('/') || normalized.contains('\u0000') ||
            parts.any { it.isBlank() || it == "." || it == ".." } ||
            parts.firstOrNull()?.contains(':') == true
        ) {
            throw PresetPackValidationException("Unsafe RPP path: $value")
        }
    }
}

private fun JSONObject.requireNonBlank(name: String): String =
    optString(name).takeIf { it.isNotBlank() }
        ?: throw PresetPackValidationException("RPP manifest field is missing: $name")

private fun JSONObject.requireLong(name: String): Long {
    val value = optLong(name, Long.MIN_VALUE)
    if (value < 0L) throw PresetPackValidationException("RPP manifest field is invalid: $name")
    return value
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
