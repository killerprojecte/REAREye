package hk.uwu.reareye.agent.tools

import android.content.Context
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.script.ScriptProjectStore
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

internal class MaryComponentEditingTools(context: Context) {
    private val context = context.applicationContext
    private val prefs get() = context.getPrefsManager()

    suspend fun execute(name: String, args: JsonObject): ToolResult? {
        if (name != READ && name != UPDATE) return null
        return withContext(Dispatchers.IO) {
            protect {
                if (name == READ) read(args) else mutationLock.withLock {
                    ready()
                    val business = required(args, "business")
                    val existing = resolveBusiness(business)
                    update(
                        existing,
                        required(args, "path"),
                        required(args, "source"),
                        required(args, "expected_sha256"),
                        integer(args, "base_width", 904),
                        integer(args, "base_height", 572)
                    )
                }
            }
        }
    }

    /** componentId is the sanitized storeWidgetId, not a business id or a guessed cache path. */
    internal suspend fun updateOwnedScript(
        componentId: String,
        relativePath: String,
        source: String,
        expectedSha256: String
    ): ToolResult =
        withContext(Dispatchers.IO) {
            protect {
                mutationLock.withLock {
                    ready()
                    val business = resolveOwned(componentId)
                    val pack = MaryComponentEditingFiles.read(File(business.filePath))
                    val path = scriptEntry(pack, relativePath)
                    update(business, path, source, expectedSha256)
                }
            }
        }

    internal suspend fun readOwnedScriptSource(componentId: String, relativePath: String): String =
        withContext(Dispatchers.IO) {
            ready()
            val business = resolveOwned(componentId)
            val pack = MaryComponentEditingFiles.read(File(business.filePath))
            MaryComponentEditingFiles.text(pack.entry(scriptEntry(pack, relativePath)))
        }

    internal suspend fun listOwnedScriptFiles(componentId: String): List<String> =
        withContext(Dispatchers.IO) {
            ready()
            val business = resolveOwned(componentId)
            MaryComponentEditingFiles.read(File(business.filePath)).scriptMap().keys.sorted()
        }

    private fun scriptEntry(pack: MaryComponentEditingFiles.Package, relativePath: String): String {
        MaryComponentEditingFiles.validPath(relativePath)
        if (!relativePath.endsWith(
                ".lua",
                true
            )
        ) MaryComponentEditingFiles.fail(
            "unsupported_format",
            "Only an existing Lua source may be updated through the script API."
        )
        return pack.scriptMap()[relativePath] ?: MaryComponentEditingFiles.fail(
            "entry_not_found",
            "Lua source was not found in the registered component package."
        )
    }

    private fun resolveBusiness(business: String): RearBusinessConfig {
        ready()
        return RearWidgetManagerRepository.loadBusinesses(prefs)
            .singleOrNull { it.business == business }
            ?: MaryComponentEditingFiles.fail(
                "component_not_found",
                "business must uniquely identify a registered component."
            )
    }

    private fun resolveOwned(componentId: String): RearBusinessConfig {
        if (!Regex("[A-Za-z0-9_-]+").matches(componentId)) MaryComponentEditingFiles.fail(
            "invalid_component_id",
            "Invalid component script owner id."
        )
        return RearWidgetManagerRepository.loadBusinesses(prefs).singleOrNull { item ->
            item.storeWidgetId?.trim()?.replace(Regex("[^A-Za-z0-9_-]"), "_") == componentId
        } ?: MaryComponentEditingFiles.fail(
            "owner_unresolved",
            "No unique registered source package matches this installed script owner; cache paths are not authoritative."
        )
    }

    private fun read(args: JsonObject): ToolResult {
        val business = resolveBusiness(required(args, "business"))
        val pack = MaryComponentEditingFiles.read(File(business.filePath))
        val requested = string(args, "path")
        val offset = integer(args, "offset", 0)
        val limit = integer(args, "limit", 16000)
        if (offset < 0 || limit !in 1..32000) MaryComponentEditingFiles.fail(
            "invalid_args",
            "offset >= 0 and limit between 1 and 32000 are required."
        )
        val entryOffset = integer(args, "entry_offset", 0)
        val entryLimit = integer(args, "entry_limit", 32)
        if (entryOffset !in 0..pack.entries.size || entryLimit !in 1..32) MaryComponentEditingFiles.fail(
            "invalid_args",
            "entry_offset must be within entries and entry_limit must be 1..32."
        )
        val entryEnd = minOf(pack.entries.size, entryOffset + entryLimit)
        val scriptPaths = pack.scriptMap()
        val result = buildJsonObject {
            put("business", business.business)
            put("component_id", business.id)
            put("writable", business.renameable)
            put("package_sha256", pack.sha256)
            put("zip", pack.zipped)
            put("total_entries", pack.entries.size)
            put("entry_offset", entryOffset)
            put("next_entry_offset", entryEnd)
            put("entries_complete", entryOffset == 0 && entryEnd == pack.entries.size)
            put("has_more_entries", entryEnd < pack.entries.size)
            put("entries", JsonArray(pack.entries.subList(entryOffset, entryEnd).map { entry ->
                buildJsonObject {
                    put("path", entry.name)
                    put("size_bytes", entry.bytes.size)
                    put(
                        "editable",
                        entry.editable && entry.bytes.size <= MaryComponentEditingFiles.MAX_TEXT_BYTES
                    )
                    put("sha256", MaryComponentEditingFiles.sha(entry.bytes))
                    val relative = scriptPaths.entries.firstOrNull { it.value == entry.name }?.key
                    relative?.let {
                        put("script_relative_path", it); put(
                        "lua_role",
                        if (it == "main.lua") "entry" else "helper"
                    )
                    }
                }
            }))
            if (requested != null) {
                val entry = pack.entry(requested)
                val text = MaryComponentEditingFiles.text(entry)
                if (offset > text.length) MaryComponentEditingFiles.fail(
                    "invalid_args",
                    "offset exceeds source length."
                )
                val end = minOf(text.length.toLong(), offset.toLong() + limit).toInt()
                put("path", entry.name)
                put("source", text.substring(offset, end))
                put("sha256", MaryComponentEditingFiles.sha(entry.bytes))
                put("offset", offset)
                put("next_offset", end)
                put("total_chars", text.length)
                put("complete", offset == 0 && end == text.length)
                put("has_more", end < text.length)
            } else put("complete", false)
            put(
                "limits",
                "package=16MiB; entries=512; entry=4MiB; expanded=32MiB; text=1MiB UTF-8; source_page=32000 UTF-16 chars; entry_page=32"
            )
        }.toString()
        // This hard guard also accounts for JSON escaping, not just unescaped source length.
        if (result.length > 300000) MaryComponentEditingFiles.fail(
            "result_too_large",
            "Reduce limit and entry_limit; no truncated JSON was returned."
        )
        return ToolResult.ok(result)
    }

    private fun update(
        target: RearBusinessConfig,
        path: String,
        source: String,
        expectedSha256: String,
        width: Int = 904,
        height: Int = 572
    ): ToolResult {
        MaryComponentEditingFiles.requireWritable(target.renameable)
        val original = MaryComponentEditingFiles.read(File(target.filePath))
        val owner =
            MaryComponentEditingFiles.installedScriptOwner(original, path, target.storeWidgetId)
        if (owner != null && resolveOwned(owner).id != target.id) MaryComponentEditingFiles.fail(
            "owner_unresolved",
            "Installed script owner does not match this registered package."
        )
        val bytes = MaryComponentEditingFiles.replacement(
            original,
            path,
            source,
            expectedSha256,
            width,
            height
        )
        val stagingRoot = File(
            context.filesDir,
            "agent_component_edit_staging"
        ).apply { check(mkdirs() || isDirectory) }
        val suffix = if (original.zipped) "zip" else File(target.filePath).extension
        val stage = File(stagingRoot, "${UUID.randomUUID()}.$suffix")
        var registered = false
        try {
            check(stage.createNewFile()) { "Cannot allocate staging source" }
            stage.outputStream().use { it.write(bytes) }
            val staged = MaryComponentEditingFiles.read(stage)
            verifyEntries(original, staged, path, source)
            ready()
            // Authoritative re-read immediately before the whole-list replacement.
            val current = RearWidgetManagerRepository.loadBusinesses(prefs)
            if (current.singleOrNull { it.id == target.id } != target) MaryComponentEditingFiles.fail(
                "conflict",
                "Component registration changed; read it again."
            )
            if (MaryComponentEditingFiles.read(File(target.filePath)).sha256 != original.sha256) MaryComponentEditingFiles.fail(
                "conflict",
                "Source package changed; read it again."
            )
            val cards = RearWidgetManagerRepository.loadCards(prefs)
            ready()
            registered = true // A failed save may still have persisted this source path.
            RearWidgetManagerRepository.saveBusinesses(
                context,
                prefs,
                current.map { if (it.id == target.id) it.copy(filePath = stage.absolutePath) else it })
            ready()
            val saved = RearWidgetManagerRepository.loadBusinesses(prefs)
            registered = saved.any { it.filePath == stage.absolutePath }
            val actual = saved.singleOrNull { it.id == target.id }
                ?: MaryComponentEditingFiles.fail(
                    "verification_failed",
                    "Component missing after registration; persistence may be partial."
                )
            if (actual.copy(filePath = target.filePath) != target || actual.filePath == stage.absolutePath ||
                saved.filterNot { it.id == target.id } != current.filterNot { it.id == target.id } ||
                RearWidgetManagerRepository.loadCards(prefs) != cards
            ) MaryComponentEditingFiles.fail(
                "verification_failed",
                "Registration fields or card references changed unexpectedly; persistence may be partial."
            )
            val persisted = MaryComponentEditingFiles.read(File(actual.filePath))
            if (persisted.sha256 != MaryComponentEditingFiles.sha(bytes)) MaryComponentEditingFiles.fail(
                "verification_failed",
                "Managed package SHA-256 differs from staged source; persistence may be partial."
            )
            verifyEntries(original, persisted, path, source)
            // The public installer is used only AFTER the authoritative source is verified.
            // Rebuild the installed tree from the verified package, never edit the cache alone.
            owner?.let { refreshOwnedCache(it, persisted) }
            return ToolResult.ok(buildJsonObject {
                put("business", target.business)
                put("component_id", target.id)
                put("path", path)
                put("sha256", MaryComponentEditingFiles.sha(source.toByteArray(Charsets.UTF_8)))
                put("package_sha256", persisted.sha256)
                put("persisted", true)
                put("verified", true)
                put("runtime_verified", false)
                put(
                    "detail",
                    "Registered package bytes and unchanged entries/metadata/card references verified. Rendering and script execution were not tested."
                )
            }.toString())
        } finally {
            // If the repository fell back to the staging path, do not leave a dangling reference.
            if (!registered) stage.delete()
        }
    }

    internal fun refreshOwnedCache(owner: String, pack: MaryComponentEditingFiles.Package) {
        val dir = File(context.cacheDir, "component-script-refresh-${UUID.randomUUID()}")
        try {
            check(dir.mkdirs()) { "Cannot allocate script installation staging directory" }
            pack.installedScriptMap().forEach { (relative, source) ->
                val file = File(dir, relative)
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                file.writeBytes(pack.entry(source).bytes)
            }
            ScriptProjectStore(context).installComponentScripts(owner, dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun verifyEntries(
        before: MaryComponentEditingFiles.Package,
        after: MaryComponentEditingFiles.Package,
        path: String,
        source: String
    ) {
        if (before.zipped != after.zipped || before.entries.size != after.entries.size) MaryComponentEditingFiles.fail(
            "verification_failed",
            "Package structure changed unexpectedly."
        )
        before.entries.forEachIndexed { index, entry ->
            val actual = after.entries[index]
            val expected =
                if (entry.name == path) source.toByteArray(Charsets.UTF_8) else entry.bytes
            if ((before.zipped && entry.name != actual.name) || !expected.contentEquals(actual.bytes)) MaryComponentEditingFiles.fail(
                "verification_failed",
                "Changed text or untouched resource verification failed."
            )
        }
    }

    private fun ready() {
        // Obtaining prefs itself can fail before a service is bound (including test environments).
        val available = runCatching { prefs.isRemoteReady() }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            false
        }
        if (!available) MaryComponentEditingFiles.fail(
            "remote_not_ready",
            "Remote preferences are unavailable; no write attempted. Reconnect the module service and retry."
        )
    }

    private suspend fun protect(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ToolResult.error(buildJsonObject {
            put(
                "error",
                (failure as? MaryComponentEditingFiles.Failure)?.code
                    ?: "component_operation_failed"
            )
            put("detail", failure.message ?: failure.javaClass.simpleName)
            put(
                "recovery",
                "Read the registered source again. A save/cache/verification failure may follow partial persistence; never assume rollback or runtime success."
            )
        }.toString())
    }

    private fun string(args: JsonObject, key: String): String? {
        val value = args[key] ?: return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) MaryComponentEditingFiles.fail(
            "invalid_args",
            "$key must be a string."
        )
        return primitive.contentOrNull
    }

    private fun required(args: JsonObject, key: String): String = string(args, key)
        ?: MaryComponentEditingFiles.fail("invalid_args", "$key is required.")

    private fun integer(args: JsonObject, key: String, default: Int): Int {
        val value = args[key] ?: return default
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.intOrNull == null) MaryComponentEditingFiles.fail(
            "invalid_args",
            "$key must be an integer."
        )
        return primitive.intOrNull!!
    }

    companion object {
        private const val READ = "read_component"
        private const val UPDATE = "update_component"
        private val mutationLock = Mutex()
        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                READ,
                "Read a registered component's original package (not runtime/cache). Omit path to list entries; supply an exact returned path for UTF-8 source and SHA-256. Pagination counts UTF-16 chars; complete=false means not a full text. No arbitrary filesystem paths. Limits: package 16MiB, expanded 32MiB, 512 entries, each 4MiB, text 1MiB.",
                listOf(
                    ToolParam(
                        "business",
                        "Registered business from list_components",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam(
                        "path",
                        "Exact existing package-relative entry from entries; omit to list",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "offset",
                        "Character offset >= 0",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                        default = "0"
                    ),
                    ToolParam(
                        "limit",
                        "Page size 1..32000 chars",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                        default = "16000"
                    ),
                    ToolParam(
                        "entry_offset",
                        "Entry list offset; see next_entry_offset/has_more_entries",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                        default = "0"
                    ),
                    ToolParam(
                        "entry_limit",
                        "Entry list page size 1..32; entries_complete marks a complete list",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                        default = "32"
                    ),
                ),
                risk = ToolRisk.READ_ONLY,
                maxResultChars = 320000
            ),
            ToolSpec(
                UPDATE,
                "Requires explicit user confirmation: may alter active executable component content. Replace ONE existing HTML/XML/JSON/Lua text entry in a registered writable component; preserve all other entry bytes, IDs, metadata and cards. Requires the original entry SHA-256. Validates statically, stages a new source, re-registers and verifies read-back. Never claims rendering/execution validation. No arbitrary filesystem paths; locked components rejected.",
                listOf(
                    ToolParam(
                        "business",
                        "Registered business from list_components",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam(
                        "path",
                        "Exact existing package-relative entry from read_component",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam(
                        "source",
                        "Complete replacement UTF-8 source, at most 1MiB",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam(
                        "expected_sha256",
                        "Full original entry SHA-256 from read_component (not package_sha256)",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam(
                        "base_width",
                        "HTML validation base width",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                        default = "904"
                    ),
                    ToolParam(
                        "base_height",
                        "HTML validation base height",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                        default = "572"
                    ),
                ),
                risk = ToolRisk.DESTRUCTIVE
            ),
        )
    }
}
