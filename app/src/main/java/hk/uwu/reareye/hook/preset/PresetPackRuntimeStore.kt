package hk.uwu.reareye.hook.preset

import android.content.pm.ApplicationInfo
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.repository.presetpack.PresetPackContract
import hk.uwu.reareye.repository.presetpack.PresetPackManifest
import hk.uwu.reareye.repository.presetpack.PresetPackValidator
import hk.uwu.reareye.ui.config.ConfigKeys
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/** Hook 进程中的 RPP 快照。没有完整可校验包时整个快照保持关闭。 */
class PresetPackRuntimeStore(
    private val prefs: HookPrefs,
    private val appInfo: ApplicationInfo,
) {
    private val installing = ThreadLocal.withInitial { false }
    private var packRoot: File? = null
    private var manifest: PresetPackManifest? = null

    fun load(): Boolean {
        val slot = prefs.getString(ConfigKeys.PRESET_REMOTE_SLOT, "")
        val expectedHash = prefs.getString(ConfigKeys.PRESET_REMOTE_HASH, "").trim().lowercase()
        if (slot.isBlank() || expectedHash.length != 64 ||
            expectedHash.any { it !in "0123456789abcdef" }
        ) return false
        val base = appInfo.dataDir?.let { File(it) } ?: return false
        val packDir = File(base, "cache/reareye-preset-pack/$expectedHash")
        val archive = File(packDir, "active.rpp")
        val payload = File(packDir, "payload")
        val marker = File(packDir, ".ready")

        return runCatching {
            if (!marker.isFile || !archive.isFile || archive.sha256() != expectedHash) {
                if (packDir.exists()) deleteTree(packDir)
                check(packDir.mkdirs() || packDir.isDirectory) { "Unable to create RPP cache" }
                val temporary = File(packDir, "active.rpp.download")
                check(prefs.copyRemoteFileTo(PresetPackContract.remoteFileName(slot), temporary)) {
                    "Remote preset pack is unavailable"
                }
                check(temporary.isFile && temporary.sha256() == expectedHash) {
                    "Remote preset pack hash mismatch"
                }
                if (!temporary.renameTo(archive)) {
                    temporary.copyTo(archive, overwrite = true)
                    temporary.delete()
                }
                val verified = PresetPackValidator.validate(archive)
                extractPayload(archive, payload, verified)
                marker.writeText(verified.packVersion, StandardCharsets.UTF_8)
            }
            val verified = PresetPackValidator.validate(archive)
            check(marker.readText(StandardCharsets.UTF_8).trim() == verified.packVersion) {
                "RPP marker mismatch"
            }
            manifest = verified
            packRoot = payload.takeIf { it.isDirectory }
            check(packRoot != null) { "RPP payload directory is missing" }
            true
        }.onFailure {
            YLog.warn("Preset pack unavailable in ${appInfo.packageName}: ${it.message}")
            packRoot = null
            manifest = null
        }.getOrDefault(false)
    }

    fun redirect(path: String?): File? {
        if (path.isNullOrBlank() || installing.get() == true || path.contains("reareye-preset-pack")) return null
        val normalized = path.replace('\\', '/')
        val root = packRoot ?: return null
        if (isCatalog(normalized)) return mergedCatalog(normalized)
        val relative = dataRelative(normalized)
        if (relative != null) return File(root, "data/$relative").takeIf { it.isFile == true }
        val card = appCardRelative(normalized)
        if (card != null) return File(root, "appcard/$card").takeIf { it.isFile == true }
        return null
    }

    fun redirectDir(path: String?): File? {
        if (path.isNullOrBlank() || installing.get() == true) return null
        val normalized = path.replace('\\', '/').trimEnd('/')
        val root = packRoot ?: return null
        val data = dataRelative(normalized)
        if (data != null) return File(root, "data/$data").takeIf { it.isDirectory == true }
        val card = appCardRelative(normalized)
        if (card != null) return File(root, "appcard/$card").takeIf { it.isDirectory == true }
        return null
    }

    fun appendNames(path: String?, current: Array<String>?): Array<String>? {
        val directory = redirectDir(path) ?: return current
        val extras = directory.list().orEmpty()
        if (extras.isEmpty()) return current
        val result = ArrayList<String>((current?.size ?: 0) + extras.size)
        result += current.orEmpty()
        val seen = result.toHashSet()
        extras.forEach { if (seen.add(it)) result += it }
        return result.toTypedArray()
    }

    private fun mergedCatalog(path: String): File? {
        val root = packRoot ?: return null
        val output = File(
            root,
            if (path.contains("/template/default/")) "catalog/merged-default.json" else "catalog/merged-normal.json"
        )
        if (output.isFile && output.length() > 0L) return output
        val patch = File(root, "catalog/patch.json")
        if (!patch.isFile) return null
        installing.set(true)
        return try {
            val original = readJson(path)
            val patchJson = readJson(patch.absolutePath) ?: return null
            val merged = original ?: JSONObject()
            mergePatch(merged, patchJson)
            output.parentFile?.mkdirs()
            writeText(output, merged.toString())
            output.takeIf { it.isFile }
        } catch (error: Throwable) {
            YLog.warn("Preset catalog merge failed: ${error.message}")
            null
        } finally {
            installing.set(false)
        }
    }

    private fun mergePatch(original: JSONObject, patch: JSONObject) {
        var products = original.optJSONArray("products") ?: JSONArray()
        val extras = patch.optJSONArray("products") ?: return
        listOf("ai-mate", "ai").forEachIndexed { groupIndex, type ->
            val extra = findGroup(extras, type) ?: return@forEachIndexed
            localizeGroup(extra)
            val existingIndex = indexOfType(products, type)
            products = if (existingIndex < 0) {
                insertGroup(products, if (type == "ai") minOf(1, products.length()) else 0, extra)
            } else {
                val host = products.optJSONObject(existingIndex) ?: return@forEachIndexed
                if (type == "ai-mate") {
                    host.put("title", "智能伙伴")
                    host.remove("titleResId")
                }
                prependItems(host, extra)
                products
            }
        }
        original.put("products", products)
        original.put("count", products.length())
    }

    private fun localizeGroup(group: JSONObject) {
        val items = group.optJSONArray("items") ?: return
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            listOf("resLocalPath", "metaPath", "rightPath", "resPreviewPath").forEach { key ->
                val raw = item.optString(key)
                redirect(raw)?.absolutePath?.let { item.put(key, it) }
            }
        }
    }

    private fun prependItems(host: JSONObject, patch: JSONObject) {
        val extra = patch.optJSONArray("items") ?: return
        val current = host.optJSONArray("items")
        val next = JSONArray()
        val ids = HashSet<String>()
        for (index in 0 until extra.length()) {
            val item = extra.optJSONObject(index) ?: continue
            ids += item.optString("resId")
            next.put(item)
        }
        if (current != null) {
            for (index in 0 until current.length()) {
                val item = current.optJSONObject(index) ?: continue
                if (!ids.contains(item.optString("resId"))) next.put(item)
            }
        }
        host.put("items", next)
    }

    private fun findGroup(products: JSONArray, type: String): JSONObject? =
        (0 until products.length()).asSequence()
            .mapNotNull { products.optJSONObject(it) }
            .firstOrNull { it.optString("type") == type }

    private fun indexOfType(products: JSONArray, type: String): Int =
        (0 until products.length()).firstOrNull {
            products.optJSONObject(it)?.optString("type") == type
        } ?: -1

    private fun insertGroup(products: JSONArray, index: Int, group: JSONObject): JSONArray {
        val result = JSONArray()
        for (position in 0 until products.length()) {
            if (position == index) result.put(group)
            result.put(products.optJSONObject(position))
        }
        if (index >= products.length()) result.put(group)
        return result
    }

    private fun readJson(path: String): JSONObject? = runCatching {
        FileInputStream(path).use { JSONObject(it.readBytes().toString(StandardCharsets.UTF_8)) }
    }.getOrNull()

    private fun writeText(file: File, value: String) {
        file.outputStream().use { it.write(value.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun extractPayload(archive: File, payload: File, verified: PresetPackManifest) {
        if (payload.exists()) deleteTree(payload)
        check(payload.mkdirs() || payload.isDirectory) { "Unable to create RPP payload directory" }
        ZipFile(archive).use { zip ->
            verified.entries.forEach { item ->
                val entry =
                    zip.getEntry(item.archivePath) ?: error("Missing RPP payload ${item.path}")
                val target = File(payload, item.path)
                val canonicalRoot = payload.canonicalFile
                val canonicalTarget = target.canonicalFile
                check(
                    canonicalTarget.path == canonicalRoot.path || canonicalTarget.path.startsWith(
                        canonicalRoot.path + File.separator
                    )
                ) {
                    "RPP path escapes payload: ${item.path}"
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                }
            }
        }
    }

    private fun isCatalog(path: String): Boolean =
        path.endsWith("/template/normal/rearScreen.json") || path.endsWith("/template/default/rearScreen.json")

    private fun appCardRelative(path: String): String? {
        val marker = "/media/rearscreen/"
        val index = path.indexOf(marker)
        if (index < 0) return null
        val suffix = path.substring(index + marker.length)
        return suffix.removePrefix("appcard/")
    }

    private fun dataRelative(path: String): String? {
        val groupOnline = "5f7623a7-2cb9-41f1-b2f1-dfc5ee443469"
        val caseOnline = "f7ca6299-33c0-45bb-aa82-f1ae6a4bea2e"
        val groupId = "71991c07-b903-4b99-b5c3-d7eda667be0b"
        val caseId = "aabbada0-94be-459c-afe5-371f0dbfba74"
        when {
            path.endsWith("/$groupOnline.mrm") -> return "meta/rearscreen/$groupId.mrm"
            path.endsWith("/$groupOnline.mrc") -> return "content/rearscreen/$groupId.mrc"
            path.endsWith("/$caseOnline.mrm") -> return "meta/rearscreen/$caseId.mrm"
            path.endsWith("/$caseOnline.mrc") -> return "content/rearscreen/$caseId.mrc"
        }
        val marker = "/precust_theme/theme/.data/"
        val index = path.indexOf(marker)
        if (index < 0) return null
        return path.substringOrNull(index + marker.length)
    }

    private fun String.substringOrNull(start: Int): String? =
        if (start < 0 || start > length) null else substring(start)

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        file.listFiles()?.forEach(::deleteTree)
        file.delete()
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
