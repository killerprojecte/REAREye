package hk.uwu.reareye.script

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ScriptProjectStore(context: Context) {
    private val appContext = context.applicationContext

    fun list(): List<ScriptProject> {
        ensureSample()
        val result = mutableListOf<ScriptProject>()
        ScriptPaths.standalone(appContext).listFiles { f -> f.isFile && f.extension == "lua" }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { file ->
                result += ScriptProject(
                    id = file.nameWithoutExtension,
                    name = file.nameWithoutExtension,
                    root = file.parentFile ?: ScriptPaths.standalone(appContext),
                    mainFile = file,
                    kind = ScriptProjectKind.STANDALONE,
                )
            }
        ScriptPaths.projects(appContext).listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { dir -> result += projectFromDir(dir, ScriptProjectKind.PROJECT) }
        ScriptPaths.components(appContext).listFiles { f -> f.isDirectory }
            ?.filterNot { it.name.startsWith('.') }
            ?.forEach { component ->
                result += projectFromDir(component, ScriptProjectKind.COMPONENT, component.name)
            }
        return result.sortedWith(
            compareBy<ScriptProject>(
                { it.kind == ScriptProjectKind.STANDALONE },
                { it.name.lowercase() })
        )
    }

    fun project(id: String): ScriptProject? = list().firstOrNull { it.id == id }

    fun read(project: ScriptProject): String =
        project.mainFile.takeIf { it.exists() }?.readText().orEmpty()

    fun luaFiles(project: ScriptProject): List<File> {
        if (project.kind == ScriptProjectKind.STANDALONE) return listOf(project.mainFile)
        return project.root.walkTopDown()
            .filter { it.isFile && it.extension.equals("lua", ignoreCase = true) }
            .sortedBy { it.relativeTo(project.root).path.lowercase() }
            .toList()
    }

    /** Returns the visible project tree, with folders before files at each level. */
    fun projectEntries(project: ScriptProject): List<File> {
        if (project.kind == ScriptProjectKind.STANDALONE) return listOf(project.mainFile)
        return project.root.walkTopDown()
            .filter { it != project.root && !it.name.startsWith('.') }
            .sortedWith(
                compareBy<File>(
                    { !it.isDirectory },
                    { it.relativeTo(project.root).path.lowercase() }),
            )
            .toList()
    }

    fun readFile(file: File): String = file.takeIf { it.exists() }?.readText().orEmpty()

    fun saveFile(file: File, source: String) {
        file.parentFile?.mkdirs()
        file.writeText(source)
    }

    fun save(project: ScriptProject, source: String) {
        project.mainFile.parentFile?.mkdirs()
        project.mainFile.writeText(source)
    }

    fun exportLua(project: ScriptProject, output: OutputStream) {
        require(project.kind == ScriptProjectKind.STANDALONE)
        project.mainFile.inputStream().use { it.copyTo(output) }
    }

    fun exportProject(project: ScriptProject, output: OutputStream) {
        require(project.kind != ScriptProjectKind.STANDALONE)
        ZipOutputStream(output).use { zip ->
            project.root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(project.root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    fun suggestedLuaName(uri: Uri): String {
        val display = importDisplayName(uri)
        val base = display.substringBeforeLast('.', display).trim()
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .trim('_')
            .ifBlank { "imported" }
        return "$base.lua"
    }

    fun isZip(uri: Uri): Boolean =
        appContext.contentResolver.getType(uri)?.contains("zip", ignoreCase = true) == true ||
                importDisplayName(uri).endsWith(".zip", ignoreCase = true)

    fun suggestedProjectName(uri: Uri): String {
        val display = importDisplayName(uri)
        return display.substringBeforeLast('.', display)
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifBlank { "imported_project" }
    }

    private fun importDisplayName(uri: Uri): String =
        appContext.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "imported"

    fun hasStandalone(name: String): Boolean =
        ScriptPaths.standalone(appContext).resolve(name).exists()

    fun hasProject(name: String): Boolean = ScriptPaths.projects(appContext).resolve(name).exists()

    fun importLua(uri: Uri, fileName: String, overwrite: Boolean): Boolean {
        val safeName = fileName.substringBeforeLast('.', fileName)
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .trim('_')
            .ifBlank { "imported" } + ".lua"
        val target = ScriptPaths.standalone(appContext).resolve(safeName)
        if (target.exists() && !overwrite) return false
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    fun importProject(uri: Uri, name: String, overwrite: Boolean): Boolean {
        val safeName = name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        if (safeName.isBlank()) return false
        val target = ScriptPaths.projects(appContext).resolve(safeName)
        if (target.exists() && !overwrite) return false
        val staging = ScriptPaths.cache(appContext).resolve("import_${System.nanoTime()}")
        staging.deleteRecursively()
        staging.mkdirs()
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name.isBlank()) continue
                        val output = ScriptFiles.resolve(staging, entry.name.replace('\\', '/'))
                        if (entry.isDirectory) output.mkdirs()
                        else {
                            output.parentFile?.mkdirs()
                            output.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: return false
            val children = staging.listFiles().orEmpty()
            if (children.size == 1 && children[0].isDirectory && children[0].resolve("main.lua").isFile) {
                val nested = children[0]
                nested.listFiles().orEmpty().forEach { child ->
                    child.renameTo(staging.resolve(child.name))
                }
                nested.deleteRecursively()
            }
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            true
        }.getOrElse {
            staging.deleteRecursively()
            false
        }
    }

    /** Renames the user-owned project directory or standalone script file. */
    fun renameProject(project: ScriptProject, requestedName: String): ScriptProject? {
        if (project.kind == ScriptProjectKind.COMPONENT) return null
        val name = requestedName.trim().removeSuffix(".lua")
        if (!ID.matches(name)) return null
        val current =
            if (project.kind == ScriptProjectKind.STANDALONE) project.mainFile else project.root
        val target = if (project.kind == ScriptProjectKind.STANDALONE) {
            project.root.resolve("$name.lua")
        } else {
            project.root.parentFile?.resolve(name) ?: return null
        }
        if (target.exists() && target.absoluteFile != current.absoluteFile) return null
        if (target.absoluteFile != current.absoluteFile && !current.renameTo(target)) return null
        return if (project.kind == ScriptProjectKind.STANDALONE) {
            ScriptProject(name, name, target.parentFile!!, target, project.kind)
        } else {
            ScriptProject(name, name, target, target.resolve("main.lua"), project.kind)
        }
    }

    /** Renames a Lua file inside a project. The required main.lua entry is kept stable. */
    fun renameFile(project: ScriptProject, file: File, requestedName: String): File? {
        if (project.kind == ScriptProjectKind.STANDALONE || file.name == "main.lua") return null
        val root = runCatching { project.root.canonicalFile }.getOrNull() ?: return null
        val source = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!source.toPath().startsWith(root.toPath())) return null
        val baseName = requestedName.trim().removeSuffix(".lua")
        if (!FILE_NAME.matches(baseName)) return null
        val target = source.parentFile?.resolve("$baseName.lua") ?: return null
        if (target.exists() && target.absoluteFile != source.absoluteFile) return null
        return if (target.absoluteFile == source.absoluteFile || source.renameTo(target)) target else null
    }

    fun createStandalone(id: String, source: String): ScriptProject {
        require(ID.matches(id)) { "Invalid script id" }
        val file = ScriptPaths.standalone(appContext).resolve("$id.lua")
        file.writeText(source)
        return ScriptProject(id, id, file.parentFile!!, file, ScriptProjectKind.STANDALONE)
    }

    fun createProject(id: String, source: String): ScriptProject {
        require(ID.matches(id)) { "Invalid project id" }
        val root = ScriptPaths.projects(appContext).resolve(id)
        require(!root.exists()) { "Project already exists" }
        root.mkdirs()
        val main = root.resolve("main.lua")
        main.writeText(source)
        return ScriptProject(id, id, root, main, ScriptProjectKind.PROJECT)
    }

    fun importStandalone(uri: Uri): ScriptProject? {
        val target =
            ScriptPaths.standalone(appContext).resolve("import_${System.currentTimeMillis()}.lua")
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            ScriptProject(
                target.nameWithoutExtension,
                target.nameWithoutExtension,
                target.parentFile!!,
                target,
                ScriptProjectKind.STANDALONE
            )
        }.getOrElse {
            target.delete()
            null
        }
    }

    fun delete(project: ScriptProject): Boolean {
        if (project.kind == ScriptProjectKind.STANDALONE) return project.mainFile.delete()
        return project.root.deleteRecursively()
    }

    /** Reinstall semantics: component-owned projects are always replaced completely. */
    fun installComponentScripts(componentId: String, sourceScriptsDir: File) {
        require(ID.matches(componentId)) { "Invalid component id" }
        val target = ScriptPaths.components(appContext).resolve(componentId)
        val staging = ScriptPaths.components(appContext).resolve(".$componentId.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        sourceScriptsDir.copyRecursively(staging, overwrite = true)
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
    }

    fun uninstallComponent(componentId: String): Boolean {
        val safeId =
            componentId.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { return false }
        return ScriptPaths.components(appContext).resolve(safeId).deleteRecursively()
    }

    private fun projectFromDir(
        dir: File,
        kind: ScriptProjectKind,
        componentId: String? = null
    ): ScriptProject {
        val main = dir.resolve("main.lua")
        return ScriptProject(
            id = if (componentId == null) dir.name else "component:$componentId",
            name = dir.name,
            root = dir,
            mainFile = main,
            kind = kind,
            componentId = componentId,
            error = if (main.isFile) null else "main.lua not found",
        )
    }

    private fun ensureSample() {
        val marker = ScriptPaths.root(appContext).resolve(".initialized")
        if (marker.exists()) return
        val file = ScriptPaths.standalone(appContext).resolve("hello.lua")
        if (!file.exists()) file.writeText(SAMPLE)
        marker.writeText("")
    }

    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]+$")
        private val FILE_NAME = Regex("^[A-Za-z0-9_.-]+$")
        private val SAMPLE = """
local function hello(params, config, context)
    return "Hello " .. tostring(params.name or "REAREye")
end

return {
    id = "hello",
    name = "Hello Script",
    description = "A small standalone script example.",
    providers = { hello = hello }
}
""".trimIndent()
    }
}
