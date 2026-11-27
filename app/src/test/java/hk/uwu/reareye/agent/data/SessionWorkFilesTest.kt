package hk.uwu.reareye.agent.data

import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.WorkspaceAttachment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class SessionWorkFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun visibilityIsLiveAndScopedToExplicitSharesAndChildOwnership() = runBlocking {
        val store = AgentSessionStore(temporary.root)
        val session = store.create()
        val main = store.workspace.tools(session.id, emptyList())
        val foreignSession = store.create()
        val foreign = store.workspace.tools(foreignSession.id, emptyList())
        assertEquals(emptySet<String>(), main.ids())
        assertEquals(ToolRisk.MUTATING, main.availableTools().single { it.name == "workspace_create_file" }.risk)
        assertEquals(ToolRisk.DESTRUCTIVE, main.availableTools().single { it.name == "workspace_delete_file" }.risk)
        val han = Regex("\\p{IsHan}")
        assertTrue(main.availableTools().all { spec ->
            !han.containsMatchIn(spec.description) && spec.params.none { han.containsMatchIn(it.description) }
        })

        val shared = main.create("notes.md", "shared")
        val hidden = main.create("notes.md", "private")
        val otherSession = foreign.create("foreign", "outside")
        val sharedIds = mutableSetOf(shared.id, otherSession.id)
        val child = store.workspace.tools(session.id, emptyList(), sharedWorkIds = sharedIds, ownerId = "child-one")
        val sibling = store.workspace.tools(session.id, emptyList(), sharedWorkIds = emptySet(), ownerId = "child-two")
        sharedIds += hidden.id
        val created = child.create("result.json", "{}")
        val siblingFile = sibling.create("result.json", "other child")

        assertEquals(setOf(shared.id, created.id), child.ids())
        assertEquals(setOf(siblingFile.id), sibling.ids())
        assertEquals(setOf(shared.id, hidden.id, created.id, siblingFile.id), main.ids())
        assertEquals(main.ids(), store.workspace.shareableWorkIds(session.id))
        assertEquals(setOf(otherSession.id), store.workspace.shareableWorkIds(foreignSession.id))
        assertEquals("{}", main.read(created.id).text())
        assertEquals(child.read(otherSession.id), child.read(hidden.id))
        for (privateId in listOf(hidden.id, siblingFile.id, otherSession.id, "../${shared.id}")) {
            assertTrue(child.read(privateId).isError)
            assertTrue(child.update(privateId, "no").isError)
            assertTrue(child.call("delete_file", "id" to privateId).isError)
        }
        assertFalse(child.update(shared.id, "revised").isError)
        assertEquals("revised", main.read(shared.id).text())
        assertFalse(main.update(created.id, "parent edit").isError)
        assertEquals("parent edit", child.read(created.id).text())
        assertTrue(sibling.read(created.id).isError)
        assertFalse(main.call("delete_file", "id" to shared.id).isError)
        assertEquals(setOf(created.id), child.ids())
    }

    @Test fun scopedAttachmentsCanBeCopiedWithoutChangingTheirOriginalBytes() = runBlocking {
        val store = AgentSessionStore(temporary.root)
        val session = store.create()
        val sourcePage = "a".repeat(15_999)
        val sourceTail = "🙂source"
        val ref = store.workspace.importBytes(session.id, "notes.txt", "text/plain", (sourcePage + sourceTail).encodeToByteArray())
        val pdfBytes = "%PDF-1.7\u0000".encodeToByteArray()
        val pdf = store.workspace.importBytes(session.id, "document.pdf", "application/pdf", pdfBytes)
        val hidden = store.workspace.importBytes(session.id, "hidden.txt", "text/plain", "hidden".encodeToByteArray())
        val foreignSession = store.create()
        val foreign = store.workspace.importBytes(foreignSession.id, "foreign.txt", "text/plain", "outside".encodeToByteArray())
        val main = store.workspace.tools(session.id, listOf(ref, pdf, foreign))
        val child = store.workspace.tools(session.id, listOf(ref), sharedWorkIds = emptySet(), ownerId = "copying-child")

        assertEquals(setOf(ref.id, pdf.id), main.ids())
        assertTrue(main.call("copy_file", "id" to foreign.id).isError)
        assertTrue(child.call("copy_file", "id" to hidden.id).isError)
        assertTrue(main.update(ref.id, "no").isError)
        assertTrue(main.call("delete_file", "id" to ref.id).isError)
        val copy = child.call("copy_file", "id" to ref.id, "name" to "revised.txt").ref()
        assertNotEquals(ref.id, copy.id)
        val originalPage = child.read(ref.id)
        assertEquals(sourcePage, originalPage.text())
        assertEquals(originalPage, main.read(copy.id))
        val offset = Json.parseToJsonElement(originalPage.content).jsonObject.getValue("nextOffset").jsonPrimitive.int
        assertEquals(sourceTail, child.read(ref.id, offset).text())
        assertEquals(sourceTail, main.read(copy.id, offset).text())
        assertFalse(child.update(copy.id, "different").isError)
        assertEquals("different", main.read(copy.id).text())
        assertEquals(originalPage, main.read(ref.id))
        assertEquals(sourceTail, main.read(ref.id, offset).text())

        val pdfCopy = main.call("copy_file", "id" to pdf.id).ref()
        assertEquals(pdf.copy(id = pdfCopy.id), pdfCopy)
        val stored = workDir(session.id).resolve(pdfCopy.id).readBytes()
        assertArrayEquals(pdfBytes, stored.takeLast(pdfBytes.size).toByteArray())
        assertTrue(main.read(pdfCopy.id).isError)
        assertTrue(main.update(pdfCopy.id, "no").isError)
        assertEquals(setOf(copy.id, pdfCopy.id), store.workspace.shareableWorkIds(session.id))
    }

    @Test fun restartPreservesWorkAndRecoversStagingWhileLockAndDeleteBoundItsLifetime() = runBlocking {
        val original = AgentSessionStore(temporary.root)
        val session = original.create()
        val main = original.workspace.tools(session.id, emptyList())
        val ref = main.create("notes.txt", "persisted")
        val dir = workDir(session.id)
        val staged = dir.resolve(".write-interrupted.tmp").apply { writeText("unfinished") }
        val orphan = workDir("orphan").apply { mkdirs() }

        val reopened = AgentSessionStore(temporary.root)
        val staleTools = reopened.workspace.tools(session.id, emptyList())
        assertFalse(staged.exists())
        assertFalse(orphan.exists())
        assertEquals("persisted", staleTools.read(ref.id).text())
        val successor = reopened.lockAndCreateSuccessor(session)
        assertEquals("persisted", staleTools.read(ref.id).text())
        assertTrue(staleTools.call("create_file", "name" to "new", "text" to "no").isError)
        assertTrue(staleTools.update(ref.id, "no").isError)
        assertTrue(staleTools.call("delete_file", "id" to ref.id).isError)
        assertEquals(emptySet<String>(), reopened.workspace.shareableWorkIds(successor.id))
        reopened.workspace.tools(successor.id, emptyList()).create("new", "successor only")
        reopened.delete(session.id)
        assertFalse(dir.exists())
        assertTrue(staleTools.read(ref.id).isError)
        assertTrue(staleTools.call("create_file", "name" to "new", "text" to "no").isError)
        assertFalse(dir.exists())
        reopened.deleteAll()
        assertFalse(dir.parentFile!!.exists())
    }

    @Test fun textAndQuotaFailuresLeaveExistingRecordsUnchanged() = runBlocking {
        val store = AgentSessionStore(temporary.root)
        val session = store.create()
        val main = store.workspace.tools(session.id, emptyList())
        val text = "a".repeat(15_999) + "🙂末尾"
        val ref = main.create("notes.txt", text)
        val first = main.read(ref.id)
        assertEquals("a".repeat(15_999), first.text())
        val offset = Json.parseToJsonElement(first.content).jsonObject.getValue("nextOffset").jsonPrimitive.int
        assertEquals("🙂末尾", main.read(ref.id, offset).text())
        assertTrue(main.read(ref.id, offset + 1).isError)
        assertTrue(main.call("create_file", "name" to "../escape", "text" to "x").isError)
        assertTrue(main.update(ref.id, "中".repeat(90_000)).isError)
        assertTrue(main.update(ref.id, "\u0000").isError)
        assertEquals(first, main.read(ref.id))

        val occupying = main.create("quota.txt", "")
        assertEquals("", main.read(occupying.id).text())
        // An oversized on-disk record must still count against the physical storage limit.
        val occupant = workDir(session.id).resolve(occupying.id)
        val originalLength = occupant.length()
        RandomAccessFile(occupant, "rw").use { it.setLength(64L * 1024 * 1024) }
        assertTrue(main.update(ref.id, "would overflow").isError)
        assertEquals(first, main.read(ref.id))
        RandomAccessFile(occupant, "rw").use { it.setLength(originalLength) }
        repeat(SessionWorkFiles.MAX_FILES - 2) { main.create("empty.txt", "") }
        assertTrue(main.call("create_file", "name" to "overflow.txt", "text" to "").isError)
        assertFalse(main.update(ref.id, "allowed at file count limit").isError)
        assertFalse(main.call("delete_file", "id" to occupying.id).isError)
        main.create("freed-slot.txt", "")
        assertEquals(SessionWorkFiles.MAX_FILES, main.ids().size)
        val attachment = store.workspace.importBytes(session.id, "original.txt", "text/plain", byteArrayOf(65))
        assertTrue(store.workspace.contains(session.id, attachment))
    }

    @Test fun linkedRecordsAndSessionDirectoriesCannotRedirectReadsWritesOrDeletion() = runBlocking {
        val store = AgentSessionStore(temporary.root)
        val session = store.create()
        val main = store.workspace.tools(session.id, emptyList())
        val ref = main.create("notes.txt", "preserve me")
        val record = workDir(session.id).resolve(ref.id).toPath()
        val outside = temporary.root.resolve("outside-record").toPath()
        Files.move(record, outside)
        assumeTrue(runCatching { Files.createSymbolicLink(record, outside) }.isSuccess)
        assertTrue(main.ids().isEmpty())
        assertTrue(main.read(ref.id).isError)
        assertTrue(main.update(ref.id, "no").isError)
        assertTrue(main.call("delete_file", "id" to ref.id).isError)
        val directory = record.parent
        val outsideDir = temporary.root.resolve("outside-directory").toPath()
        Files.move(directory, outsideDir)
        Files.createSymbolicLink(directory, outsideDir)
        assertTrue(main.call("list_files").isError)
        assertTrue(main.call("create_file", "name" to "new", "text" to "no").isError)
        store.delete(session.id)
        assertFalse(Files.exists(directory, NOFOLLOW_LINKS))
        assertTrue(Files.exists(outsideDir.resolve(ref.id), NOFOLLOW_LINKS))
        assertTrue(Files.exists(outside))
    }

    private fun workDir(id: String) = temporary.root.resolve("agent/workspace-files/$id")

    private fun SessionWorkspace.importBytes(sessionId: String, name: String, mime: String, bytes: ByteArray) =
        importFile(sessionId, name, mime, bytes.size.toLong(), bytes::inputStream)

    private suspend fun ToolEnvironment.call(tool: String, vararg params: Pair<String, String>) =
        execute("workspace_$tool", buildJsonObject { params.forEach { (key, value) -> put(key, value) } })

    private suspend fun ToolEnvironment.ids() = call("list_files").let { result ->
        assertFalse(result.content, result.isError)
        Json.decodeFromString<List<WorkspaceAttachment>>(result.content).mapTo(mutableSetOf()) { it.id }
    }

    private suspend fun ToolEnvironment.create(name: String, text: String) = call("create_file", "name" to name, "text" to text).ref()

    private suspend fun ToolEnvironment.update(id: String, text: String) = call("update_file", "id" to id, "text" to text)

    private suspend fun ToolEnvironment.read(id: String, offset: Int? = null) = execute("workspace_read_text", buildJsonObject {
        put("id", id)
        offset?.let { put("offset", it) }
    })

    private fun ToolResult.ref(): WorkspaceAttachment {
        assertFalse(content, isError)
        return Json.decodeFromString(content)
    }

    private fun ToolResult.text(): String {
        assertFalse(content, isError)
        return Json.parseToJsonElement(content).jsonObject.getValue("text").jsonPrimitive.content
    }
}
