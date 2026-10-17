package hk.uwu.reareye.agent.tools

import android.content.Context
import hk.uwu.reareye.agent.luascript.LuaScriptValidator
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.script.ScriptPaths
import hk.uwu.reareye.script.ScriptProject
import hk.uwu.reareye.script.ScriptProjectKind
import hk.uwu.reareye.script.ScriptProjectStore
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** File editing only: no Lua execution, provider loading, or writes to installed component caches. */
internal class MaryScriptEditingTools(
    context: Context,
    private val remoteReady: () -> Boolean = {
        try {
            context.applicationContext.getPrefsManager().isRemoteReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    },
) {
    private val context = context.applicationContext
    private val store = ScriptProjectStore(this.context)
    private val files = MaryScriptEditingFiles(this.context.filesDir)

    suspend fun execute(name: String, args: JsonObject): ToolResult? {
        if (SPECS.none { it.name == name }) return null
        return withContext(Dispatchers.IO) {
            try {
                LOCK.withLock {
                    when (name) {
                        "read_script" -> read(args)
                        "list_script_files" -> list(args)
                        else -> {
                            require(remoteReady()) { "config_unavailable: module configuration service is disconnected; no changes made" }
                            when (name) {
                                "update_script" -> update(args)
                                "rename_script" -> rename(args)
                                "create_script" -> create(args)
                                else -> error("Unknown script tool")
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ToolResult.error(buildJsonObject {
                    put("error", "script_operation_failed")
                    put("detail", (failure.message ?: failure.javaClass.simpleName).take(500))
                    put(
                        "recovery",
                        if (failure.message?.let { it.startsWith("Symlinks") || it.startsWith("Path escapes") } == true)
                            "Use regular files and directories inside application script storage; re-reading the id or hash will not resolve a blocked path."
                        else "Re-read the exact type-qualified id and file hash before retrying.")
                }.toString())
            }
        }
    }

    private fun resolve(args: JsonObject, exact: Boolean = false): ScriptProject {
        val id = args.required("id")
        if (exact) require(ScriptProjectKind.entries.any { id.startsWith("${it.name}:") }) {
            "id must be exact KIND:local_id from list_scripts"
        }
        // list() may initialize the sample file, so validate storage ancestors before invoking it.
        val storage = context.filesDir.resolve("script-projects")
        files.noSymlinks(storage)
        listOf("standalone", "projects", "components").forEach {
            files.noSymlinks(storage.resolve(it))
        }
        val matches = store.list().filter { scriptIdMatches(id, it.kind.name, it.id) }
        require(matches.size <= 1) { "ambiguous_script_id: use exact KIND:local_id" }
        val project = matches.singleOrNull() ?: error("script_not_found: $id")
        // Reject symlinks in the project directory as well as within a relative path.
        files.noSymlinks(project.root)
        files.noSymlinks(project.mainFile)
        return project
    }

    private fun relative(project: ScriptProject, args: JsonObject): String =
        args.text("relative_path")
            ?: if (project.kind == ScriptProjectKind.STANDALONE) project.mainFile.name else "main.lua"

    private fun localFile(project: ScriptProject, relative: String): File {
        if (project.kind == ScriptProjectKind.STANDALONE) {
            require(relative == project.mainFile.name) { "STANDALONE exposes only its own Lua file" }
        }
        return files.resolve(project.root, relative)
    }

    private suspend fun source(project: ScriptProject, relative: String): String {
        val source = if (project.kind == ScriptProjectKind.COMPONENT) {
            MaryComponentEditingTools(context).readOwnedScriptSource(
                project.componentId ?: error("Missing component id"), relative
            )
        } else files.read(localFile(project, relative))
        MaryScriptEditingFiles.validateSource(source)
        return source
    }

    private suspend fun read(args: JsonObject): ToolResult {
        val project = resolve(args)
        val relative = relative(project, args)
        val text = source(project, relative)
        val offset = args.integer("offset", 0)
        val limit = args.integer("limit", 4000)
        require(offset in 0..text.length && limit in 1..12000) { "offset must be 0..source length and limit 1..12000 characters" }
        var end = minOf(text.length.toLong(), offset.toLong() + limit).toInt()
        fun payload(): String = buildJsonObject {
            put("id", qualifiedScriptId(project.kind.name, project.id))
            put("local_id", project.id)
            put("name", project.name)
            put("kind", project.kind.name)
            put("valid", project.isValid)
            project.error?.let { put("error", it) }
            put("relative_path", relative)
            put("sha256", MaryScriptEditingFiles.sha256(text))
            put("total_chars", text.length)
            put("offset", offset)
            put("limit", limit)
            put("returned_chars", end - offset)
            put("complete", offset == 0 && end == text.length)
            put("has_more", end < text.length)
            if (end < text.length) put("next_offset", end)
            put("source", text.substring(offset, end))
            if (relative == if (project.kind == ScriptProjectKind.STANDALONE) project.mainFile.name else "main.lua") {
                put("issues", LuaScriptValidator.validate(text).report().take(2000))
            }
        }.toString()

        var serialized = payload()
        while (serialized.length > MAX_READ_RESULT_CHARS && end > offset) {
            end = offset + (end - offset) * 3 / 4
            serialized = payload()
        }
        require(serialized.length <= MAX_READ_RESULT_CHARS) { "Read metadata exceeds result size limit" }
        require(end > offset || offset == text.length) { "Read page cannot make progress within result size limit" }
        return ToolResult.ok(serialized)
    }

    private suspend fun list(args: JsonObject): ToolResult {
        val project = resolve(args)
        val paths = if (project.kind == ScriptProjectKind.COMPONENT) {
            MaryComponentEditingTools(context).listOwnedScriptFiles(
                project.componentId ?: error("Missing component id")
            )
        } else if (project.kind == ScriptProjectKind.STANDALONE) {
            listOf(project.mainFile.name)
        } else files.list(project.root)
        val offset = args.integer("offset", 0)
        val limit = args.integer("limit", 80)
        require(offset in 0..paths.size && limit in 1..200) { "Invalid listing offset/limit" }
        val page = mutableListOf<String>()
        var pageChars = 0
        for (path in paths.drop(offset).take(limit)) {
            require(path.length <= 512) { "Project relative path exceeds 512 characters" }
            val cost = path.length * 2 + 80
            if (pageChars + cost > 12000) break
            page += path
            pageChars += cost
        }
        return ToolResult.ok(buildJsonObject {
            put("id", qualifiedScriptId(project.kind.name, project.id))
            put("total_files", paths.size)
            put("offset", offset)
            put("has_more", offset + page.size < paths.size)
            if (offset + page.size < paths.size) put("next_offset", offset + page.size)
            put("files", buildJsonArray {
                page.forEach { path ->
                    add(buildJsonObject {
                        put("relative_path", path)
                        put("editable", path.endsWith(".lua", true))
                    })
                }
            })
        }.toString())
    }

    private suspend fun update(args: JsonObject): ToolResult {
        val project = resolve(args, exact = true)
        val relative = args.required("relative_path")
        val source = args.required("source")
        val expected = args.required("expected_sha256")
        require(
            relative.endsWith(
                ".lua",
                true
            )
        ) { "Only existing Lua text files can be updated; non-Lua files are not supported" }
        MaryScriptEditingFiles.validateSource(source)
        if (project.kind == ScriptProjectKind.COMPONENT) {
            return MaryComponentEditingTools(context).updateOwnedScript(
                project.componentId ?: error("Missing component id"), relative, source, expected,
            )
        }
        val file = localFile(project, relative)
        if (file.canonicalFile == project.mainFile.canonicalFile) validateEntry(source)
        files.update(file, source, expected)
        return saved(project, relative, source)
    }

    private fun rename(args: JsonObject): ToolResult {
        val project = resolve(args, exact = true)
        require(project.kind != ScriptProjectKind.COMPONENT) { "COMPONENT scripts cannot be renamed independently; edit the owning component" }
        val name = args.required("name")
        val relative = args.text("relative_path")
        val renamed = if (relative != null) {
            require(project.kind == ScriptProjectKind.PROJECT) { "Use project rename for STANDALONE" }
            val file = localFile(project, relative)
            require(
                file.isFile && file.extension.equals(
                    "lua",
                    true
                )
            ) { "Only existing Lua files can be renamed" }
            files.read(file)
            require(file.name != "main.lua") { "main.lua cannot be renamed" }
            require(Regex("[A-Za-z0-9_.-]+\\.lua").matches(name) && name != "main.lua") { "name must be a Lua basename other than main.lua" }
            val target = localFile(
                project, file.parentFile!!.relativeTo(project.root).invariantSeparatorsPath
                    .let { if (it.isEmpty()) name else "$it/$name" })
            require(!target.exists()) { "Target already exists" }
            Files.move(file.toPath(), target.toPath())
            require(target.isFile && !file.exists()) { "Rename read-back failed" }
            project
        } else {
            require(Regex("[A-Za-z0-9_-]+").matches(name.removeSuffix(".lua"))) { "Invalid project name" }
            val current =
                if (project.kind == ScriptProjectKind.STANDALONE) project.mainFile else project.root
            if (project.kind == ScriptProjectKind.STANDALONE) files.read(current)
            val target = current.parentFile!!.resolve(
                if (project.kind == ScriptProjectKind.STANDALONE) "${
                    name.removeSuffix(".lua")
                }.lua" else name.removeSuffix(".lua")
            )
            files.noSymlinks(target)
            require(!target.exists()) { "Target already exists" }
            store.renameProject(project, name) ?: error("Rename failed")
        }
        return ToolResult.ok(buildJsonObject {
            put("ok", true)
            put("id", qualifiedScriptId(renamed.kind.name, renamed.id))
            if (relative != null) put(
                "relative_path",
                localFile(project, relative).parentFile!!.resolve(name)
                    .relativeTo(project.root).invariantSeparatorsPath
            )
        }.toString())
    }

    private fun create(args: JsonObject): ToolResult {
        val source = args.required("source")
        MaryScriptEditingFiles.validateSource(source)
        validateEntry(source)
        val kind = args.text("kind") ?: "STANDALONE"
        require(kind == "STANDALONE" || kind == "PROJECT") { "kind must be STANDALONE or PROJECT" }
        val requested = args.text("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: args.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "script"
        val base =
            requested.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "script" }.take(80)
        files.noSymlinks(
            context.filesDir.resolve("script-projects")
                .resolve(if (kind == "PROJECT") "projects" else "standalone")
        )
        val parent =
            if (kind == "PROJECT") ScriptPaths.projects(context) else ScriptPaths.standalone(context)
        files.noSymlinks(parent)
        fun target(id: String) = parent.resolve(if (kind == "PROJECT") id else "$id.lua")
        var id = base
        var suffix = 2
        while (target(id).exists()) id = "${base}_${suffix++}"
        val destination = target(id)
        files.noSymlinks(destination)
        if (kind == "STANDALONE") {
            files.create(destination, source)
        } else {
            files.noSymlinks(context.filesDir.resolve("script-projects/cache"))
            val stagingParent = ScriptPaths.cache(context)
            files.noSymlinks(stagingParent)
            val staging =
                Files.createTempDirectory(stagingParent.toPath(), ".mary-create-").toFile()
            try {
                files.create(staging.resolve("main.lua"), source)
                require(!destination.exists()) { "Target already exists" }
                Files.move(staging.toPath(), destination.toPath())
                require(files.read(destination.resolve("main.lua")) == source) { "Create read-back failed" }
            } finally {
                staging.deleteRecursively()
            }
        }
        return ToolResult.ok(buildJsonObject {
            put("ok", true)
            put("id", "$kind:$id")
            put("sha256", MaryScriptEditingFiles.sha256(source))
            put(
                "note",
                "Created without execution; test the script on the scripts screen. Existing names receive a suffix."
            )
        }.toString())
    }

    private fun validateEntry(source: String) {
        val validation = LuaScriptValidator.validate(source)
        require(validation.passed) { "script_validation_failed: ${validation.report()}" }
    }

    private fun saved(project: ScriptProject, path: String, source: String) =
        ToolResult.ok(buildJsonObject {
            put("ok", true)
            put("id", qualifiedScriptId(project.kind.name, project.id))
            put("relative_path", path)
            put("sha256", MaryScriptEditingFiles.sha256(source))
        }.toString())

    companion object {
        private val LOCK = Mutex()
        private const val MAX_READ_RESULT_CHARS = 32000
        private fun string(name: String, description: String, required: Boolean = true) =
            ToolParam(name, description, ToolParam.ParamType.STRING, required)

        private val ID = string(
            "id",
            "Exact KIND:local_id returned by list_scripts; legacy ids accepted only for reads when unambiguous"
        )
        private val PATH = string(
            "relative_path",
            "Relative path inside the script project; no absolute paths, traversal or symlinks",
            false
        )
        private val PAGING = listOf(
            ToolParam(
                "offset",
                "Zero-based character offset for read_script, file offset for list_script_files",
                ToolParam.ParamType.INTEGER,
                false
            ),
            ToolParam(
                "limit",
                "Read character count (default 4000, max 12000) or file count (default 80, max 200)",
                ToolParam.ParamType.INTEGER,
                false
            ),
        )
        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                "read_script",
                "Read UTF-8 project text without execution; defaults to entry file. Returns full-file sha256 and explicit complete/has_more/next_offset character pagination. Component Lua is read from its source package and requires connected module service. Files are capped at 262144 UTF-8 bytes.",
                listOf(ID, PATH) + PAGING,
                ToolRisk.READ_ONLY,
                MAX_READ_RESULT_CHARS
            ),
            ToolSpec(
                "list_script_files",
                "List project-relative files without execution or following symlinks. Only existing Lua text can be updated. Component listing reads the source package.",
                listOf(ID) + PAGING,
                ToolRisk.READ_ONLY,
                20000
            ),
            ToolSpec(
                "update_script",
                "Replace one existing Lua text file in place using mandatory full-file expected_sha256. Preserves project identity and resources. Entry validation is static; modules need no descriptor. Component writes update the source package and reinstall; locked components rejected. Requires connected module service and explicit user confirmation: edited scripts may already be enabled and access root or network when executed. Never executes Lua during editing.",
                listOf(
                    ID,
                    PATH.copy(required = true),
                    string("source", "Complete replacement Lua text"),
                    string("expected_sha256", "Full-file sha256 from read_script")
                ),
                ToolRisk.DESTRUCTIVE
            ),
            ToolSpec(
                "rename_script",
                "Rename a standalone/project, or one auxiliary Lua file via relative_path and Lua basename name. Cannot rename main.lua or any COMPONENT script. Refuses existing targets. Requires connected module service; imports are not rewritten.",
                listOf(ID, string("name", "New project id or auxiliary Lua filename"), PATH),
                ToolRisk.MUTATING
            ),
            ToolSpec(
                "create_script",
                "Create Lua after get_script_spec, with static entry validation and no execution. kind defaults STANDALONE, or PROJECT with main.lua. Existing names receive suffixes, never overwritten. Requires connected module service and explicit confirmation; generated scripts may access root/network when executed.",
                listOf(
                    string("source", "Complete Lua source"),
                    string("name", "Display name used to derive filename"),
                    string("id", "Optional local filename id", false),
                    ToolParam(
                        "kind",
                        "New script storage kind",
                        ToolParam.ParamType.STRING,
                        false,
                        listOf("STANDALONE", "PROJECT")
                    )
                ),
                ToolRisk.DESTRUCTIVE
            ),
        )
    }
}

private fun JsonObject.text(key: String): String? {
    val element = this[key] ?: return null
    require(element is JsonPrimitive && element.isString) { "$key must be a string" }
    return element.content
}

private fun JsonObject.required(key: String) =
    text(key)?.takeIf { it.isNotBlank() } ?: error("$key is required")

private fun JsonObject.integer(key: String, default: Int): Int = this[key]?.let {
    require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "$key must be an integer" }
    it.intOrNull!!
} ?: default

/** Bounded, strict UTF-8 file primitives confined to the trusted application files directory. */
internal class MaryScriptEditingFiles(trustedRoot: File) {
    private val boundary = trustedRoot.absoluteFile.toPath().normalize()
    private val canonicalBoundary = trustedRoot.canonicalFile.toPath()

    fun noSymlinks(file: File) {
        val path = file.absoluteFile.toPath().normalize()
        require(path.startsWith(boundary)) { "Path escapes application files directory" }
        // Context.filesDir is the trust anchor. Android may expose it through system aliases;
        // reject links BELOW it, without inspecting or rejecting those trusted ancestors.
        var current = path
        while (current != boundary) {
            require(!Files.isSymbolicLink(current)) { "Symlinks are not allowed inside application files directory" }
            current = current.parent
        }
        require(
            file.canonicalFile.toPath().startsWith(canonicalBoundary)
        ) { "Path escapes application files directory" }
    }

    fun resolve(root: File, path: String): File {
        require(
            path.isNotBlank() && path.length <= 512 && !path.contains('\\') && !path.contains(
                ':'
            ) && !File(path).isAbsolute
        ) { "Invalid relative path" }
        require(
            path.split('/')
                .all { it.isNotEmpty() && it != "." && it != ".." && !it.any { ch -> ch.code < 32 } }) { "Path traversal is not allowed" }
        noSymlinks(root)
        val target = root.absoluteFile.resolve(path)
        noSymlinks(target)
        require(
            target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
        ) { "Path escapes project root" }
        return target
    }

    fun read(file: File): String {
        noSymlinks(file)
        require(file.isFile && file.length() <= MAX_BYTES) { "Missing file or file exceeds $MAX_BYTES bytes" }
        val bytes = file.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "File too large" }
        val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            .toString()
        validateSource(source)
        return source
    }

    companion object {
        const val MAX_BYTES = 262144
        fun validateSource(source: String) {
            require(source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Source too large" }
            require(source.none { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) { "Binary/control characters are not supported" }
            require(!source.startsWith("\u001bLua")) { "Lua bytecode is not supported" }
        }

        fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun list(root: File): List<String> {
        noSymlinks(root)
        val found = mutableListOf<String>()
        var visitedEntries = 0
        fun visit(directory: File, depth: Int) {
            require(depth <= 32) { "Project nesting limit exceeded" }
            directory.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                require(++visitedEntries <= 4000) { "Project tree entry limit exceeded" }
                noSymlinks(file)
                require(found.size < 2000) { "Project file limit exceeded" }
                if (file.isDirectory) visit(file, depth + 1)
                else if (file.isFile) found += file.relativeTo(root).invariantSeparatorsPath
            }
        }
        visit(root, 0)
        return found
    }

    @Synchronized
    fun update(file: File, source: String, expected: String) {
        require(file.extension.equals("lua", true)) { "Only Lua updates are supported" }
        require(Regex("[a-fA-F0-9]{64}").matches(expected)) { "Invalid expected_sha256" }
        validateSource(source)
        val previous = read(file)
        require(
            sha256(previous).equals(
                expected,
                true
            )
        ) { "hash_conflict: file changed; re-read before editing" }
        val temporary =
            Files.createTempFile(file.parentFile!!.toPath(), ".mary-update-", ".tmp").toFile()
        try {
            writeSynced(temporary, source)
            require(read(temporary) == source) { "Temporary file read-back mismatch" }
            noSymlinks(file)
            require(
                sha256(read(file)).equals(
                    expected,
                    true
                )
            ) { "hash_conflict: file changed during update" }
            // No non-atomic fallback: an unsupported atomic move must leave the original intact.
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            require(read(file) == source) { "Saved file read-back mismatch" }
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun create(file: File, source: String) {
        noSymlinks(file)
        validateSource(source)
        val temporary =
            Files.createTempFile(file.parentFile!!.toPath(), ".mary-create-", ".tmp").toFile()
        try {
            writeSynced(temporary, source)
            require(read(temporary) == source) { "Temporary file read-back mismatch" }
            require(
                !Files.exists(
                    file.toPath(),
                    LinkOption.NOFOLLOW_LINKS
                )
            ) { "Target already exists" }
            // Files.move without REPLACE_EXISTING preserves a concurrently created destination.
            Files.move(temporary.toPath(), file.toPath())
            require(read(file) == source) { "Created file read-back mismatch" }
        } finally {
            temporary.delete()
        }
    }

    private fun writeSynced(file: File, source: String) {
        FileOutputStream(file).use { output -> output.write(source.toByteArray(Charsets.UTF_8)); output.fd.sync() }
    }
}
