package hk.uwu.reareye.repository.rearwidget

import hk.uwu.reareye.hook.support.RemoteFileName
import hk.uwu.reareye.widgetapi.RearWidgetSceneRouteSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class RearBusinessConfig(
    val id: String,
    val packageName: String,
    val business: String,
    val filePath: String,
    val defaultIndex: Int = 0,
    val defaultPriority: Int = 500,
    val renameable: Boolean = true,
    val downloadedFromStore: Boolean = false,
    val storeWidgetId: String? = null,
    val storeWidgetName: String? = null,
    val storeReleaseTag: String? = null,
    val storeReleaseAssetName: String? = null,
    val storeReleasePublishedAt: String? = null,
    val storeInstalledAt: String? = null,
)

data class RearCardConfig(
    val id: String,
    val title: String,
    val packageName: String,
    val business: String,
    val oneConfigJson: String? = null,
    val enabled: Boolean = true,
    val sticky: Boolean = true,
    val priority: Int = 500,
    val renameable: Boolean = true,
    val downloadedFromStore: Boolean = false,
    val storeWidgetId: String? = null,
    val storeWidgetName: String? = null,
    val storeReleaseTag: String? = null,
    val storeReleaseAssetName: String? = null,
    val storeReleasePublishedAt: String? = null,
)

data class RearWidgetSceneRouteConfig(
    val id: String,
    val packageName: String,
    val scene: String,
    val business: String,
    val downloadedFromStore: Boolean = false,
    val storeWidgetId: String? = null,
    val storeWidgetName: String? = null,
    val storeReleaseTag: String? = null,
    val storeReleaseAssetName: String? = null,
    val storeReleasePublishedAt: String? = null,
)

object RearWidgetConfigCodec {
    const val EMPTY_ARRAY = "[]"
    private const val BUSINESS_BLOB_PREFIX = "rear_widget_business_blob_"
    private const val BUSINESS_BLOB_META_PREFIX = "rear_widget_business_blob_meta_"
    private const val BUSINESS_BLOB_SOURCE_PREFIX = "rear_widget_business_blob_source_"
    private const val REMOTE_BLOB_MARKER_PREFIX = "reareye-remote-file-v1:"
    private const val REMOTE_BLOB_FILE_PREFIX = "reareye_blob_v1_"

    fun newBusinessId(packageName: String, business: String): String =
        "${packageName.trim()}::${business.trim()}"

    fun newSceneRouteId(packageName: String, scene: String): String =
        "${packageName.trim()}::scene::${RearWidgetSceneRouteSpec.normalizeScenePattern(scene)}"

    fun newCardId(): String = UUID.randomUUID().toString()

    fun businessBlobKey(business: String): String =
        BUSINESS_BLOB_PREFIX + sanitizeBusinessKey(business)

    fun businessBlobMetaKey(business: String): String =
        BUSINESS_BLOB_META_PREFIX + sanitizeBusinessKey(business)

    fun businessBlobSourceKey(business: String): String =
        BUSINESS_BLOB_SOURCE_PREFIX + sanitizeBusinessKey(business)

    /** 判断键是否为需要迁移到 RemoteFile 的旧 rear widget blob payload。 */
    fun isBusinessBlobPayloadKey(key: String): Boolean =
        key.startsWith(BUSINESS_BLOB_PREFIX) &&
                !key.startsWith(BUSINESS_BLOB_SOURCE_PREFIX) &&
                !key.startsWith(BUSINESS_BLOB_META_PREFIX)

    /** 为同一个业务键生成跨 UI/service/Hook 代际稳定的远程文件名。 */
    fun businessBlobRemoteFileName(business: String): String =
        businessBlobRemoteFileNameForKey(businessBlobKey(business))

    /** 为旧偏好中的完整 blob 键生成稳定远程文件名，避免清洗碰撞。 */
    fun businessBlobRemoteFileNameForKey(blobKey: String): String {
        require(isBusinessBlobPayloadKey(blobKey)) {
            "Unexpected rear widget blob payload key: $blobKey"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(blobKey.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return RemoteFileName.requireValid(REMOTE_BLOB_FILE_PREFIX + digest)
    }

    /** 将远程文件名编码为 RemotePreferences 中的小引用 marker。 */
    fun remoteBlobMarker(fileName: String): String =
        REMOTE_BLOB_MARKER_PREFIX + RemoteFileName.requireValid(fileName)

    /** 解析并校验远程 blob marker；普通旧 Base64 值返回 null。 */
    fun remoteBlobFileNameFromMarker(value: String?): String? {
        val raw = value?.trim() ?: return null
        if (!raw.startsWith(REMOTE_BLOB_MARKER_PREFIX)) return null
        val fileName = raw.removePrefix(REMOTE_BLOB_MARKER_PREFIX)
        if (fileName.isBlank()) return null
        return runCatching { RemoteFileName.requireValid(fileName) }.getOrNull()
    }

    /** 兼容迁移前的 plain Base64、base64: 和 data:*;base64, 形式。 */
    fun legacyBase64Payload(value: String): String {
        val raw = value.trim()
        if (raw.startsWith("base64:", ignoreCase = true)) {
            return raw.substringAfter(':')
        }
        val comma = raw.indexOf(',')
        if (raw.startsWith("data:", ignoreCase = true) &&
            raw.substringBefore(';').isNotBlank() &&
            raw.substringBefore(',').endsWith(";base64", ignoreCase = true) &&
            comma >= 0
        ) {
            return raw.substring(comma + 1)
        }
        return raw
    }

    /** 校验远程文件的 hash:size 元数据，防止截断或跨业务文件被部署。 */
    fun verifyBusinessBlobMeta(file: File, expectedMeta: String): Boolean {
        if (!file.isFile) return false
        val separator = expectedMeta.lastIndexOf(':')
        if (separator <= 0 || separator == expectedMeta.lastIndex) return false
        val expectedHash = expectedMeta.substring(0, separator)
        val expectedSize = expectedMeta.substring(separator + 1).toLongOrNull() ?: return false
        if (expectedSize < 0L || file.length() != expectedSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash =
            digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        return actualHash == expectedHash
    }

    private fun sanitizeBusinessKey(business: String): String =
        business.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")

    fun parseBusinesses(raw: String?): List<RearBusinessConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<RearBusinessConfig>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val packageName = obj.optString("packageName").trim()
            val business = obj.optString("business").trim()
            val filePath = obj.optString("filePath").trim()
            if (packageName.isBlank() || business.isBlank() || filePath.isBlank()) continue
            val id = obj.optString("id").ifBlank { newBusinessId(packageName, business) }
            out += RearBusinessConfig(
                id = id,
                packageName = packageName,
                business = business,
                filePath = filePath,
                defaultIndex = obj.optInt("defaultIndex", 0),
                defaultPriority = obj.optInt("defaultPriority", 500),
                renameable = obj.optBoolean("renameable", true),
                downloadedFromStore = obj.optBoolean("downloadedFromStore", false),
                storeWidgetId = obj.optString("storeWidgetId").trim().ifBlank { null },
                storeWidgetName = obj.optString("storeWidgetName").trim().ifBlank { null },
                storeReleaseTag = obj.optString("storeReleaseTag").trim().ifBlank { null },
                storeReleaseAssetName = obj.optString("storeReleaseAssetName").trim()
                    .ifBlank { null },
                storeReleasePublishedAt = obj.optString("storeReleasePublishedAt").trim()
                    .ifBlank { null },
                storeInstalledAt = obj.optString("storeInstalledAt").trim().ifBlank { null },
            )
        }
        return out
    }

    fun parseCards(raw: String?): List<RearCardConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<RearCardConfig>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val packageName = obj.optString("packageName").trim()
            val business = obj.optString("business").trim()
            if (packageName.isBlank() || business.isBlank()) continue
            val title = obj.optString("title").trim().ifBlank { business }
            val id = obj.optString("id").ifBlank { newCardId() }
            out += RearCardConfig(
                id = id,
                title = title,
                packageName = packageName,
                business = business,
                oneConfigJson = obj.optString("oneConfigJson").trim().ifBlank { null },
                enabled = obj.optBoolean("enabled", true),
                sticky = obj.optBoolean("sticky", true),
                priority = obj.optInt("priority", 500),
                renameable = obj.optBoolean("renameable", true),
                downloadedFromStore = obj.optBoolean("downloadedFromStore", false),
                storeWidgetId = obj.optString("storeWidgetId").trim().ifBlank { null },
                storeWidgetName = obj.optString("storeWidgetName").trim().ifBlank { null },
                storeReleaseTag = obj.optString("storeReleaseTag").trim().ifBlank { null },
                storeReleaseAssetName = obj.optString("storeReleaseAssetName").trim()
                    .ifBlank { null },
                storeReleasePublishedAt = obj.optString("storeReleasePublishedAt").trim()
                    .ifBlank { null },
            )
        }
        return out
    }

    fun parseSceneRoutes(raw: String?): List<RearWidgetSceneRouteConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<RearWidgetSceneRouteConfig>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val packageName = obj.optString("packageName").trim()
            val scene = obj.optString("scene").trim()
            val business = obj.optString("business").trim()
            if (packageName.isBlank() || scene.isBlank() || business.isBlank()) continue
            val id = obj.optString("id").ifBlank { newSceneRouteId(packageName, scene) }
            out += RearWidgetSceneRouteConfig(
                id = id,
                packageName = packageName,
                scene = scene,
                business = business,
                downloadedFromStore = obj.optBoolean("downloadedFromStore", false),
                storeWidgetId = obj.optString("storeWidgetId").trim().ifBlank { null },
                storeWidgetName = obj.optString("storeWidgetName").trim().ifBlank { null },
                storeReleaseTag = obj.optString("storeReleaseTag").trim().ifBlank { null },
                storeReleaseAssetName = obj.optString("storeReleaseAssetName").trim()
                    .ifBlank { null },
                storeReleasePublishedAt = obj.optString("storeReleasePublishedAt").trim()
                    .ifBlank { null },
            )
        }
        return out
    }

    fun encodeBusinesses(list: List<RearBusinessConfig>): String =
        JSONArray().also { arr ->
            list.forEach { item ->
                arr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("packageName", item.packageName)
                        .put("business", item.business)
                        .put("filePath", item.filePath)
                        .put("defaultIndex", item.defaultIndex)
                        .put("defaultPriority", item.defaultPriority)
                        .put("renameable", item.renameable)
                        .put("downloadedFromStore", item.downloadedFromStore)
                        .put("storeWidgetId", item.storeWidgetId)
                        .put("storeWidgetName", item.storeWidgetName)
                        .put("storeReleaseTag", item.storeReleaseTag)
                        .put("storeReleaseAssetName", item.storeReleaseAssetName)
                        .put("storeReleasePublishedAt", item.storeReleasePublishedAt)
                        .put("storeInstalledAt", item.storeInstalledAt)
                )
            }
        }.toString()

    fun encodeCards(list: List<RearCardConfig>): String =
        JSONArray().also { arr ->
            list.forEach { item ->
                arr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("packageName", item.packageName)
                        .put("business", item.business)
                        .put("oneConfigJson", item.oneConfigJson)
                        .put("enabled", item.enabled)
                        .put("sticky", item.sticky)
                        .put("priority", item.priority)
                        .put("renameable", item.renameable)
                        .put("downloadedFromStore", item.downloadedFromStore)
                        .put("storeWidgetId", item.storeWidgetId)
                        .put("storeWidgetName", item.storeWidgetName)
                        .put("storeReleaseTag", item.storeReleaseTag)
                        .put("storeReleaseAssetName", item.storeReleaseAssetName)
                        .put("storeReleasePublishedAt", item.storeReleasePublishedAt)
                )
            }
        }.toString()

    fun encodeSceneRoutes(list: List<RearWidgetSceneRouteConfig>): String =
        JSONArray().also { arr ->
            list.forEach { item ->
                arr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("packageName", item.packageName)
                        .put("scene", item.scene)
                        .put("business", item.business)
                        .put("downloadedFromStore", item.downloadedFromStore)
                        .put("storeWidgetId", item.storeWidgetId)
                        .put("storeWidgetName", item.storeWidgetName)
                        .put("storeReleaseTag", item.storeReleaseTag)
                        .put("storeReleaseAssetName", item.storeReleaseAssetName)
                        .put("storeReleasePublishedAt", item.storeReleasePublishedAt)
                )
            }
        }.toString()
}
