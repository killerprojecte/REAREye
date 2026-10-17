package hk.uwu.reareye.agent.tools

import hk.uwu.reareye.agent.model.ToolRisk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class MaryComponentEditingToolsTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val files = MaryComponentEditingFiles
    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            out.setComment("original-package-metadata")
            entries.forEach { (name, bytes) ->
                val item = ZipEntry(name).apply {
                    comment = "entry-metadata"
                    time = 1700000000000L
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                }
                out.putNextEntry(item)
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun text(value: String) = value.toByteArray(Charsets.UTF_8)
    private fun failure(code: String? = null, block: () -> Unit) {
        try {
            block(); fail("Expected rejection")
        } catch (error: Exception) {
            if (code != null) assertEquals(
                code,
                (error as? MaryComponentEditingFiles.Failure)?.code
            )
        }
    }

    @Test
    fun zipUpdatePreservesResourcesUntouchedEntriesAndMetadata() {
        val image = ByteArray(1024) { (it % 256).toByte() }
        val file = zip(
            "manifest.xml" to text("<Root/>"),
            "images/picture.png" to image,
            "scripts/main.lua" to text("return { id='demo', providers={} }"),
            "config.json" to text("{\"id\":\"stable\"}")
        )
        val before = files.read(file)
        val originalHash = before.sha256
        val bytes = files.replacement(
            before,
            "manifest.xml",
            "<Root><Text/></Root>",
            files.sha(before.entry("manifest.xml").bytes)
        )
        val afterFile = temp.newFile("edited.zip").apply { writeBytes(bytes) }
        val after = files.read(afterFile)
        assertEquals(originalHash, files.read(file).sha256)
        assertArrayEquals(image, after.entry("images/picture.png").bytes)
        assertEquals(before.comment, after.comment)
        before.entries.filterNot { it.name == "manifest.xml" }.forEach { old ->
            val changed = after.entry(old.name)
            assertArrayEquals(old.bytes, changed.bytes)
            assertEquals(old.metadata?.comment, changed.metadata?.comment)
            assertEquals(old.metadata?.time, changed.metadata?.time)
            assertEquals(old.metadata?.method, changed.metadata?.method)
        }
        assertEquals("<Root><Text/></Root>", files.text(after.entry("manifest.xml")))
    }

    @Test
    fun rawTextTemplateKeepsStableRelativeNameAcrossStaging() {
        val original =
            temp.newFile("managed_timestamp.json").apply { writeText("{\"before\":true}") }
        val pack = files.read(original)
        assertFalse(pack.zipped)
        assertEquals("template.json", pack.entries.single().name)
        val output = files.replacement(
            pack,
            "template.json",
            "{\"after\":true}",
            files.sha(pack.entries.single().bytes)
        )
        val stage = temp.newFile("another_timestamp.json").apply { writeBytes(output) }
        assertEquals("template.json", files.read(stage).entries.single().name)
        assertEquals("{\"after\":true}", files.text(files.read(stage).entries.single()))
        assertEquals("{\"before\":true}", original.readText())
    }

    @Test
    fun conflictsInvalidHashesAndMissingEntriesNeverTouchOriginal() {
        val original = zip("manifest.xml" to text("<Root/>"))
        val before = files.read(original)
        failure("conflict") { files.replacement(before, "manifest.xml", "<New/>", "0".repeat(64)) }
        failure("invalid_hash") { files.replacement(before, "manifest.xml", "<New/>", "short") }
        failure("entry_not_found") {
            files.replacement(
                before,
                "absent.xml",
                "<New/>",
                "0".repeat(64)
            )
        }
        assertEquals(before.sha256, files.read(original).sha256)
    }

    @Test
    fun pathsRejectTraversalAbsoluteBackslashEmptySegmentsAndDriveNames() {
        for (path in listOf(
            "../secret.xml",
            "/secret.xml",
            "C:/secret.xml",
            "a/../b.xml",
            "a//b.xml",
            "./a.xml",
            "a\\b.xml",
            "",
            "a\u0000.xml"
        )) {
            failure("invalid_path") { files.validPath(path) }
        }
        files.validPath("nested/组件.xml")
    }

    @Test
    fun maliciousZipPathsAndDuplicateNamesAreRejectedBeforeEditing() {
        failure("invalid_path") { files.read(zip("../outside.xml" to text("<Root/>"))) }
        failure("duplicate_entry") {
            files.read(
                zip(
                    "A.xml" to text("<Root/>"),
                    "a.xml" to text("<Root/>")
                )
            )
        }
        failure("duplicate_entry") {
            files.read(
                zip(
                    "nested" to text("file"),
                    "nested/file.xml" to text("<Root/>")
                )
            )
        }
        // ZipOutputStream itself rejects exact duplicates: patch both central and local filenames.
        val duplicate = zip("a.xml" to text("<A/>"), "b.xml" to text("<B/>"))
        val bytes = duplicate.readBytes()
        val target = text("b.xml")
        for (index in 0..bytes.size - target.size) {
            if (target.indices.all { bytes[index + it] == target[it] }) bytes[index] =
                'a'.code.toByte()
        }
        duplicate.writeBytes(bytes)
        failure("duplicate_entry") { files.read(duplicate) }
    }

    @Test
    fun actualExpandedEntryBytesAndEntryCountAreBounded() {
        failure("size_limit") { files.read(zip("huge.xml" to ByteArray(files.MAX_ENTRY_BYTES + 1))) }
        val many = (0..files.MAX_ENTRIES).map { "entry$it.xml" to text("<R/>") }.toTypedArray()
        failure("size_limit") { files.read(zip(*many)) }
        val raw =
            temp.newFile("huge.json").apply { writeBytes(ByteArray(files.MAX_PACKAGE_BYTES + 1)) }
        failure("size_limit") { files.read(raw) }
    }

    @Test
    fun compressedBombUsesActualBytesNotCompressedLength() {
        val bomb = temp.newFile("compressed-bomb.zip")
        ZipOutputStream(bomb.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("bomb.xml"))
            out.write(ByteArray(files.MAX_ENTRY_BYTES + 1) { 65 })
            out.closeEntry()
        }
        assertTrue(bomb.length() < files.MAX_ENTRY_BYTES)
        failure("size_limit") { files.read(bomb) }
    }

    @Test
    fun formatValidationAndTextSizeRejectInvalidReplacementWithoutMutation() {
        val original = zip("template.xml" to text("<Root/>"), "config.json" to text("{}"))
        val before = files.read(original)
        failure("unsafe_xml") {
            files.validate(
                "template.xml",
                "<!DOCTYPE Root SYSTEM 'file:///secret'><Root/>"
            )
        }
        failure { files.validate("template.xml", "<Root>") }
        failure { files.validate("config.json", "{broken}") }
        failure("size_limit") {
            files.validate(
                "config.json",
                " ".repeat(files.MAX_TEXT_BYTES + 1)
            )
        }
        failure("format_validation_failed") { files.validate("index.html", "<html></html>") }
        failure("unsupported_format") { files.validate("image.png", "not text") }
        failure("invalid_text") { files.validate("config.json", "\"\uD800\"") }
        assertEquals(before.sha256, files.read(original).sha256)
    }

    @Test
    fun invalidUtf8AndBinaryEntriesAreNotEditable() {
        val pack = files.read(
            zip(
                "bad.json" to byteArrayOf(0xC3.toByte(), 0x28),
                "image.png" to byteArrayOf(1, 2)
            )
        )
        failure { files.text(pack.entry("bad.json")) }
        failure("unsupported_format") { files.text(pack.entry("image.png")) }
    }

    @Test
    fun luaEntryAndHelperAreStaticallyDistinguishedWithoutExecutingCode() {
        files.validate("scripts/main.lua", "error('must never execute'); return { providers={} }")
        files.validate("scripts/lib/helper.lua", "local x = 1")
        failure("format_validation_failed") { files.validate("scripts/main.lua", "local x = 1") }
        failure("format_validation_failed") { files.validate("scripts/lib/helper.lua", " ") }
    }

    @Test
    fun sourceScriptMappingFollowsInstallerAndRejectsAmbiguousSuffixes() {
        val pack = files.read(
            zip(
                "wrapper/scripts/main.lua" to text("return {}"),
                "wrapper/scripts/lib/util.lua" to text("return {}")
            )
        )
        assertEquals(
            mapOf(
                "main.lua" to "wrapper/scripts/main.lua",
                "lib/util.lua" to "wrapper/scripts/lib/util.lua"
            ), pack.scriptMap()
        )
        val ambiguous = files.read(
            zip(
                "one/scripts/main.lua" to text("return {}"),
                "two/scripts/main.lua" to text("return {}")
            )
        )
        failure("ambiguous_script") { ambiguous.scriptMap() }
    }

    @Test
    fun refreshingInstalledScriptsPreservesJsonAndImageResources() {
        val image = byteArrayOf(0, 127, -1, 42)
        val packageFile = zip(
            "wrapper/scripts/main.lua" to text("return { providers={} }"),
            "wrapper/scripts/settings.json" to text("{\"keep\":true}"),
            "wrapper/scripts/assets/icon.png" to image
        )
        val pack = files.read(packageFile)
        assertEquals(listOf("main.lua"), pack.scriptMap().keys.toList())
        assertEquals(3, pack.installedScriptMap().size)
        val context = RuntimeEnvironment.getApplication()
        val owner = "mary_component_resources_test"
        val installed = hk.uwu.reareye.script.ScriptPaths.components(context).resolve(owner)
        try {
            MaryComponentEditingTools(context).refreshOwnedCache(owner, pack)
            assertEquals("{\"keep\":true}", installed.resolve("settings.json").readText())
            assertArrayEquals(image, installed.resolve("assets/icon.png").readBytes())
            assertEquals("return { providers={} }", installed.resolve("main.lua").readText())
        } finally {
            installed.deleteRecursively()
        }
    }

    @Test
    fun updatingNonLuaScriptResourceSelectsOwnerAndSynchronizesChangedContent() {
        val resourcePath = "wrapper/scripts/settings.json"
        val image = byteArrayOf(1, 2, -1)
        val source = zip(
            "wrapper/scripts/main.lua" to text("return { providers={} }"),
            resourcePath to text("{\"version\":1}"), "wrapper/scripts/assets/icon.png" to image,
            "template.xml" to text("<Root/>")
        )
        val before = files.read(source)
        val bytes = files.replacement(
            before,
            resourcePath,
            "{\"version\":2}",
            files.sha(before.entry(resourcePath).bytes)
        )
        val persisted = files.read(temp.newFile("updated-resource.zip").apply { writeBytes(bytes) })
        val context = RuntimeEnvironment.getApplication()
        val storeWidgetId = "mary/nonlua_resource_test"
        // This is the same decision function used by update(), not a Lua-only test path.
        val owner = files.installedScriptOwner(before, resourcePath, storeWidgetId)
        assertEquals("mary_nonlua_resource_test", owner)
        assertNull(files.installedScriptOwner(before, "template.xml", storeWidgetId))
        assertNull(files.installedScriptOwner(before, resourcePath, null))
        val installed = hk.uwu.reareye.script.ScriptPaths.components(context).resolve(owner!!)
        val tools = MaryComponentEditingTools(context)
        try {
            tools.refreshOwnedCache(owner, before)
            assertEquals("{\"version\":1}", installed.resolve("settings.json").readText())
            files.installedScriptOwner(persisted, resourcePath, storeWidgetId)
                ?.let { tools.refreshOwnedCache(it, persisted) }
            assertEquals("{\"version\":2}", installed.resolve("settings.json").readText())
            assertEquals("return { providers={} }", installed.resolve("main.lua").readText())
            assertArrayEquals(image, installed.resolve("assets/icon.png").readBytes())
            assertEquals("{\"version\":1}", files.text(files.read(source).entry(resourcePath)))
        } finally {
            installed.deleteRecursively()
        }
    }

    @Test
    fun lockedComponentsAreAlwaysRejected() {
        failure("component_locked") { files.requireWritable(false) }
        files.requireWritable(true)
    }

    @Test
    fun schemasRequireApprovalHashAndExactRegisteredIdentity() {
        val read = MaryComponentEditingTools.SPECS.single { it.name == "read_component" }
        val update = MaryComponentEditingTools.SPECS.single { it.name == "update_component" }
        assertEquals(ToolRisk.READ_ONLY, read.risk)
        assertEquals(ToolRisk.DESTRUCTIVE, update.risk)
        assertTrue(update.description.contains("explicit user confirmation"))
        assertEquals(
            setOf("business", "path", "source", "expected_sha256"),
            update.params.filter { it.required }.map { it.name }.toSet()
        )
        assertTrue(update.params.none { it.name.contains("override") || it.name == "filePath" })
    }

    @Test
    fun disconnectedExecuteRejectsWriteAndUnknownNameFallsThrough() = runBlocking {
        val tools = MaryComponentEditingTools(RuntimeEnvironment.getApplication())
        assertNull(tools.execute("not_a_component_tool", buildJsonObject {}))
        val result = tools.execute("update_component", buildJsonObject {
            put("business", "demo"); put("path", "template.xml"); put(
            "source",
            "<New/>"
        ); put("expected_sha256", "0".repeat(64))
        })!!
        assertTrue(result.isError)
        assertEquals(
            JsonPrimitive("remote_not_ready"),
            (Json.parseToJsonElement(result.content) as JsonObject)["error"]
        )
        val owned = tools.updateOwnedScript("demo", "main.lua", "return {}", "0".repeat(64))
        assertTrue(owned.isError)
        assertEquals(
            JsonPrimitive("remote_not_ready"),
            (Json.parseToJsonElement(owned.content) as JsonObject)["error"]
        )
    }
}
