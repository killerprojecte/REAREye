package hk.uwu.reareye.agent.tools

import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.script.ScriptPaths
import hk.uwu.reareye.script.ScriptProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class MaryScriptEditingToolsTest {
    private val context: android.app.Application get() = RuntimeEnvironment.getApplication()
    private lateinit var tools: MaryScriptEditingTools
    private val entry = "return { id = 'demo', providers = {} }"

    @Before
    fun setUp() {
        ScriptPaths.root(context).deleteRecursively()
        ScriptProjectStore(context).list() // Initialize sample before setting up exact fixture ids.
        tools = MaryScriptEditingTools(context) { true }
    }

    private fun args(vararg values: Pair<String, String>): JsonObject = buildJsonObject {
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun result(name: String, args: JsonObject): ToolResult =
        runBlocking { tools.execute(name, args)!! }

    private fun json(result: ToolResult): JsonObject =
        Json.parseToJsonElement(result.content).jsonObject

    private fun project(id: String = "demo"): File =
        ScriptPaths.projects(context).resolve(id).also {
            it.mkdirs(); it.resolve("main.lua").writeText(entry)
        }

    private fun standalone(id: String = "demo"): File =
        ScriptPaths.standalone(context).resolve("$id.lua").also { it.writeText(entry) }

    private fun update(id: String, path: String, source: String, previous: String): ToolResult =
        result(
            "update_script", args(
                "id" to id,
                "relative_path" to path,
                "source" to source,
                "expected_sha256" to MaryScriptEditingFiles.sha256(previous),
            )
        )

    @Test
    fun unknownToolIsNotInterceptedAndSchemasAreRealAndEnglish() = runBlocking {
        assertNull(tools.execute("unknown", JsonObject(emptyMap())))
        assertEquals(
            setOf(
                "read_script",
                "list_script_files",
                "update_script",
                "rename_script",
                "create_script"
            ), MaryScriptEditingTools.SPECS.map { it.name }.toSet()
        )
        assertEquals(
            ToolRisk.DESTRUCTIVE,
            MaryScriptEditingTools.SPECS.single { it.name == "create_script" }.risk
        )
        assertEquals(
            ToolRisk.DESTRUCTIVE,
            MaryScriptEditingTools.SPECS.single { it.name == "update_script" }.risk
        )
        assertEquals(
            ToolRisk.MUTATING,
            MaryScriptEditingTools.SPECS.single { it.name == "rename_script" }.risk
        )
        MaryScriptEditingTools.SPECS.forEach { spec ->
            assertFalse(Regex("[\\p{IsHan}]").containsMatchIn(spec.description))
            assertTrue(spec.params.all { !Regex("[\\p{IsHan}]").containsMatchIn(it.description) })
        }
    }

    @Test
    fun identicalLocalIdsRequireDisambiguationAndUpdatesRequireQualifiedId() {
        standalone(); project()
        assertTrue(result("read_script", args("id" to "demo")).isError)
        assertFalse(result("read_script", args("id" to "STANDALONE:demo")).isError)
        assertFalse(result("read_script", args("id" to "PROJECT:demo")).isError)
        assertTrue(update("demo", "main.lua", entry, entry).isError)
    }

    @Test
    fun readsDefaultEntryAndPagesWithFullFileHash() {
        val file = standalone()
        val full = json(result("read_script", args("id" to "STANDALONE:demo")))
        assertEquals(entry, full["source"]!!.jsonPrimitive.content)
        assertTrue(full["complete"]!!.jsonPrimitive.boolean)
        val page = json(result("read_script", buildJsonObject {
            put("id", "STANDALONE:demo"); put("offset", 3); put("limit", 7)
        }))
        assertEquals(entry.substring(3, 10), page["source"]!!.jsonPrimitive.content)
        assertEquals(
            MaryScriptEditingFiles.sha256(file.readText()),
            page["sha256"]!!.jsonPrimitive.content
        )
        assertFalse(page["complete"]!!.jsonPrimitive.boolean)
        assertEquals(10, page["next_offset"]!!.jsonPrimitive.int)
    }

    @Test
    fun denseEscapingProducesCompleteBoundedJsonAndCorrectNextOffset() {
        val dense = "\"\t\r\n\\".repeat(4000)
        standalone().writeText(dense)
        val read = result("read_script", buildJsonObject {
            put("id", "STANDALONE:demo"); put("limit", 12000)
        })
        assertFalse(read.content, read.isError)
        val cap = MaryScriptEditingTools.SPECS.single { it.name == "read_script" }.maxResultChars!!
        assertTrue(read.content.length <= cap)
        val page = json(read)
        val returned = page["returned_chars"]!!.jsonPrimitive.int
        assertTrue(returned > 0)
        assertEquals(dense.substring(0, returned), page["source"]!!.jsonPrimitive.content)
        assertEquals(returned, page["next_offset"]!!.jsonPrimitive.int)
        assertEquals(MaryScriptEditingFiles.sha256(dense), page["sha256"]!!.jsonPrimitive.content)
    }

    @Test
    fun updatesEntryInPlaceWithoutDeletingModulesOrResources() {
        val root = project()
        root.resolve("helper.lua").writeText("local helper = 1")
        val asset = root.resolve("image.bin").also { it.writeBytes(byteArrayOf(0, 1, 2)) }
        val replacement = "return { id = 'demo', providers = {}, name = 'Changed' }"
        assertFalse(update("PROJECT:demo", "main.lua", replacement, entry).isError)
        assertEquals(replacement, root.resolve("main.lua").readText())
        assertEquals("local helper = 1", root.resolve("helper.lua").readText())
        assertArrayEquals(byteArrayOf(0, 1, 2), asset.readBytes())
        assertEquals(root, ScriptProjectStore(context).list().single { it.id == "demo" }.root)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".mary-") })
    }

    @Test
    fun modulesNeedNoReturnOrProvidersButEntryValidationStillApplies() {
        val root = project()
        root.resolve("helper.lua").writeText("local old = 1")
        assertFalse(
            update(
                "PROJECT:demo",
                "helper.lua",
                "local replacement = 2",
                "local old = 1"
            ).isError
        )
        assertTrue(update("PROJECT:demo", "main.lua", "local not_an_entry = 1", entry).isError)
        assertEquals(entry, root.resolve("main.lua").readText())
    }

    @Test
    fun staleHashDoesNotChangeOriginalAndNonLuaUpdateIsExplicitlyRejected() {
        val root = project()
        assertTrue(update("PROJECT:demo", "main.lua", entry + "\n", "stale").isError)
        assertEquals(entry, root.resolve("main.lua").readText())
        root.resolve("note.txt").writeText("hello")
        val refused = update("PROJECT:demo", "note.txt", "world", "hello")
        assertTrue(refused.isError)
        assertTrue(refused.content.contains("non-Lua"))
        assertEquals("hello", root.resolve("note.txt").readText())
    }

    @Test
    fun listingIncludesResourcesAndSupportsBoundedPaging() {
        val root = project()
        root.resolve("helper.lua").writeText("return {}")
        root.resolve("asset.txt").writeText("asset")
        val listed = json(result("list_script_files", buildJsonObject {
            put("id", "PROJECT:demo"); put("limit", 1)
        }))
        assertEquals(3, listed["total_files"]!!.jsonPrimitive.int)
        assertEquals(1, listed["files"]!!.jsonArray.size)
        assertTrue(listed["has_more"]!!.jsonPrimitive.boolean)
        assertEquals(1, listed["next_offset"]!!.jsonPrimitive.int)
    }

    @Test
    fun illegalPathsMissingFilesBinaryAndOversizedFilesAreRefused() {
        val root = project()
        for (path in listOf(
            "../outside.lua",
            "/main.lua",
            "C:/main.lua",
            "a/../main.lua",
            "a\\main.lua",
            "./main.lua",
            "missing.lua"
        )) {
            assertTrue(
                path,
                result("read_script", args("id" to "PROJECT:demo", "relative_path" to path)).isError
            )
            assertTrue(path, update("PROJECT:demo", path, entry, entry).isError)
        }
        root.resolve("binary.lua").writeBytes(byteArrayOf(0, 1))
        root.resolve("invalid.lua").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        root.resolve("large.lua").writeText("a".repeat(MaryScriptEditingFiles.MAX_BYTES + 1))
        for (path in listOf("binary.lua", "invalid.lua", "large.lua")) {
            assertTrue(
                result(
                    "read_script",
                    args("id" to "PROJECT:demo", "relative_path" to path)
                ).isError
            )
        }
        assertEquals(entry, root.resolve("main.lua").readText())
    }

    @Test
    fun symlinksAreRefusedWhenHostSupportsCreatingThem() {
        val root = project()
        val link = root.resolve("alias.lua")
        try {
            Files.createSymbolicLink(link.toPath(), root.resolve("main.lua").toPath())
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        } // Windows without symlink privilege.
        assertTrue(
            result(
                "read_script",
                args("id" to "PROJECT:demo", "relative_path" to "alias.lua")
            ).isError
        )
        assertTrue(update("PROJECT:demo", "alias.lua", entry + "\n", entry).isError)
    }

    private fun withDataDirectoryAlias(block: (android.content.Context, File) -> Unit) {
        val sandbox =
            Files.createTempDirectory(context.cacheDir.toPath(), "mary-path-alias-").toFile()
        val physical = sandbox.resolve("physical").apply { mkdirs() }
        val alias = sandbox.resolve("user0")
        try {
            try {
                Files.createSymbolicLink(alias.toPath(), physical.toPath())
            } catch (failure: UnsupportedOperationException) {
                org.junit.Assume.assumeNoException(failure); return
            } catch (failure: java.nio.file.FileSystemException) {
                org.junit.Assume.assumeNoException(failure); return
            }
            val aliasedContext = object : android.content.ContextWrapper(context) {
                override fun getApplicationContext(): android.content.Context = this
                override fun getFilesDir(): File = alias.resolve("files").apply { mkdirs() }
            }
            block(aliasedContext, physical.resolve("files"))
        } finally {
            Files.deleteIfExists(alias.toPath())
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun trustedSystemAncestorAliasAllowsReadListUpdateRenameAndCreate() =
        withDataDirectoryAlias { aliased, physical ->
            ScriptProjectStore(aliased).list()
            val file = ScriptPaths.standalone(aliased).resolve("test_script.lua")
                .apply { writeText(entry) }
            tools = MaryScriptEditingTools(aliased) { true }
            val read = result("read_script", args("id" to "STANDALONE:test_script"))
            assertFalse(read.content, read.isError)
            assertEquals(entry, json(read)["source"]!!.jsonPrimitive.content)
            val listed = result("list_script_files", args("id" to "STANDALONE:test_script"))
            assertFalse(listed.content, listed.isError)
            val replacement = entry + "\n-- edited"
            val updated = update("STANDALONE:test_script", "test_script.lua", replacement, entry)
            assertFalse(updated.content, updated.isError)
            assertEquals(replacement, file.readText())
            val renamed =
                result("rename_script", args("id" to "STANDALONE:test_script", "name" to "renamed"))
            assertFalse(renamed.content, renamed.isError)
            assertEquals(
                replacement,
                physical.resolve("script-projects/standalone/renamed.lua").readText()
            )
            for (kind in listOf("STANDALONE", "PROJECT")) {
                val created = result(
                    "create_script",
                    args("source" to entry, "id" to "created", "kind" to kind)
                )
                assertFalse(created.content, created.isError)
            }
            assertEquals(
                entry,
                physical.resolve("script-projects/standalone/created.lua").readText()
            )
            assertEquals(
                entry,
                physical.resolve("script-projects/projects/created/main.lua").readText()
            )
        }

    @Test
    fun trustedAliasStillRejectsInternalFileDirectoryAndStorageSymlinks() =
        withDataDirectoryAlias { aliased, physical ->
            ScriptProjectStore(aliased).list()
            tools = MaryScriptEditingTools(aliased) { true }
            val standalone = ScriptPaths.standalone(aliased)
            val original = standalone.resolve("original.lua").apply { writeText(entry) }
            val fileLink = standalone.resolve("linked.lua")
            Files.createSymbolicLink(fileLink.toPath(), original.toPath())
            val read = result("read_script", args("id" to "STANDALONE:linked"))
            assertTrue(read.content, read.isError)
            assertTrue(json(read)["recovery"]!!.jsonPrimitive.content.contains("regular files"))
            assertTrue(update("STANDALONE:linked", "linked.lua", entry + "\n", entry).isError)
            assertEquals(entry, original.readText())
            Files.delete(fileLink.toPath())

            val actualProject = ScriptPaths.projects(aliased).resolve("actual").apply { mkdirs() }
            actualProject.resolve("main.lua").writeText(entry)
            val directoryLink = actualProject.parentFile!!.resolve("linked")
            Files.createSymbolicLink(directoryLink.toPath(), actualProject.toPath())
            try {
                assertTrue(result("read_script", args("id" to "PROJECT:linked")).isError)
            } finally {
                Files.delete(directoryLink.toPath())
            }

            val storage = physical.resolve("script-projects")
            val moved = physical.resolve("moved-storage")
            Files.move(storage.toPath(), moved.toPath())
            Files.createSymbolicLink(storage.toPath(), moved.toPath())
            try {
                assertTrue(result("read_script", args("id" to "STANDALONE:original")).isError)
                assertTrue(
                    result(
                        "create_script",
                        args("source" to entry, "id" to "blocked")
                    ).isError
                )
                assertFalse(moved.resolve("standalone/blocked.lua").exists())
            } finally {
                Files.delete(storage.toPath())
            }
        }

    @Test
    fun filePrimitivesRejectPathsOutsideTrustedBoundary() {
        val boundary = context.filesDir.resolve("bounded-files").apply { mkdirs() }
        val outside =
            context.filesDir.resolve("bounded-files-sibling.lua").apply { writeText(entry) }
        val files = MaryScriptEditingFiles(boundary)
        try {
            for (candidate in listOf(outside, boundary.resolve("../bounded-files-sibling.lua"))) {
                try {
                    files.read(candidate); fail("Outside path must be rejected")
                } catch (failure: IllegalArgumentException) {
                    assertTrue(failure.message!!.contains("Path escapes"))
                }
            }
            assertEquals(entry, outside.readText())
        } finally {
            boundary.deleteRecursively(); outside.delete()
        }
    }

    @Test
    fun renameProtectsMainAndExistingTargetsButPreservesProjectFiles() {
        val root = project()
        root.resolve("helper.lua").writeText("return 7")
        root.resolve("taken.lua").writeText("return 8")
        assertTrue(
            result(
                "rename_script",
                args("id" to "PROJECT:demo", "relative_path" to "main.lua", "name" to "other.lua")
            ).isError
        )
        assertTrue(
            result(
                "rename_script",
                args("id" to "PROJECT:demo", "relative_path" to "helper.lua", "name" to "taken.lua")
            ).isError
        )
        assertFalse(
            result(
                "rename_script",
                args(
                    "id" to "PROJECT:demo",
                    "relative_path" to "helper.lua",
                    "name" to "renamed.lua"
                )
            ).isError
        )
        assertEquals("return 7", root.resolve("renamed.lua").readText())
        project("taken")
        assertTrue(result("rename_script", args("id" to "PROJECT:demo", "name" to "taken")).isError)
        assertFalse(
            result(
                "rename_script",
                args("id" to "PROJECT:demo", "name" to "renamed_project")
            ).isError
        )
        assertEquals(
            entry,
            ScriptPaths.projects(context).resolve("renamed_project/main.lua").readText()
        )
        assertEquals(
            "return 7",
            ScriptPaths.projects(context).resolve("renamed_project/renamed.lua").readText()
        )
    }

    @Test
    fun standaloneRenameAndCreateNeverOverwriteAndProjectCreationWritesMain() {
        standalone()
        standalone("taken")
        assertTrue(
            result(
                "rename_script",
                args("id" to "STANDALONE:demo", "name" to "taken")
            ).isError
        )
        assertFalse(
            result(
                "rename_script",
                args("id" to "STANDALONE:demo", "name" to "renamed")
            ).isError
        )
        assertEquals(entry, ScriptPaths.standalone(context).resolve("renamed.lua").readText())
        val created = result(
            "create_script",
            args("source" to entry, "name" to "New", "id" to "new_project", "kind" to "PROJECT")
        )
        assertFalse(created.content, created.isError)
        assertEquals("PROJECT:new_project", json(created)["id"]!!.jsonPrimitive.content)
        assertEquals(
            entry,
            ScriptPaths.projects(context).resolve("new_project/main.lua").readText()
        )
        val second = result(
            "create_script",
            args("source" to entry, "name" to "New", "id" to "new_project", "kind" to "PROJECT")
        )
        assertEquals("PROJECT:new_project_2", json(second)["id"]!!.jsonPrimitive.content)
        assertEquals(
            entry,
            ScriptPaths.projects(context).resolve("new_project/main.lua").readText()
        )
    }

    @Test
    fun componentWriteCannotFallBackToInstalledCacheAndCannotRename() {
        val root = ScriptPaths.components(context).resolve("missing-owner").also { it.mkdirs() }
        root.resolve("main.lua").writeText(entry)
        val id = "COMPONENT:component:missing-owner"
        assertTrue(update(id, "main.lua", entry + "\n", entry).isError)
        assertTrue(result("rename_script", args("id" to id, "name" to "changed")).isError)
        assertTrue(result("read_script", args("id" to id)).isError)
        assertEquals(entry, root.resolve("main.lua").readText())
    }

    @Test
    fun disconnectedServiceBlocksEveryMutationWithoutChangingFiles() {
        standalone()
        tools = MaryScriptEditingTools(context) { false }
        assertTrue(update("STANDALONE:demo", "demo.lua", entry + "\n", entry).isError)
        assertTrue(
            result(
                "rename_script",
                args("id" to "STANDALONE:demo", "name" to "changed")
            ).isError
        )
        assertTrue(result("create_script", args("source" to entry, "name" to "new")).isError)
        assertEquals(entry, ScriptPaths.standalone(context).resolve("demo.lua").readText())
        assertFalse(ScriptPaths.standalone(context).resolve("new.lua").exists())
        assertFalse(result("read_script", args("id" to "STANDALONE:demo")).isError)
    }

    @Test
    fun defaultDisconnectedServiceReportsUnavailableInsteadOfPrefsInitializationException() {
        tools = MaryScriptEditingTools(context)
        val refused = result("create_script", args("source" to entry, "name" to "default_guard"))
        assertTrue(refused.isError)
        assertTrue(refused.content, refused.content.contains("config_unavailable"))
        assertFalse(ScriptPaths.standalone(context).resolve("default_guard.lua").exists())
    }

    @Test
    fun cancellationIsNeverConvertedIntoToolError() = runBlocking {
        tools = MaryScriptEditingTools(context) { throw CancellationException("cancel") }
        try {
            tools.execute("create_script", args("source" to entry, "name" to "new"))
            fail("CancellationException must escape")
        } catch (_: CancellationException) { /* expected */
        }
    }
}
