package hk.uwu.reareye.agent.data

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.MessagePart
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.WorkspaceAttachment
import hk.uwu.reareye.agent.provider.OpenAiCompatibleProvider
import hk.uwu.reareye.agent.provider.ProviderFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = Base64.getDecoder().decode(PNG_BASE64)

    @Test fun durableReferencesSurviveRestartButDraftsAndDeletedSessionsDoNot() {
        val root = temporary.root
        val store = robolectricWorkspaceStore(root)
        val session = store.create()
        val other = store.create()
        val saved = store.workspace.importBytes(session.id, "../saved.png", "image/png", png)
        val draft = store.workspace.importBytes(session.id, "draft.txt", "text/plain", "draft".toByteArray())
        val text = store.workspace.importBytes(other.id, "other.txt", "text/plain", "other".toByteArray())
        val message = ChatMessage(MessageRole.USER, attachments = listOf(saved))
        val roundTrip = KoogTranscriptCodec.fromNative(KoogTranscriptCodec.toNative(listOf(message)))
        assertEquals(listOf(saved), roundTrip.single().attachments)
        store.save(session.copy(messages = roundTrip))
        store.workspace.discardDraft(session.id, saved.id)
        assertTrue(store.workspace.contains(session.id, saved))
        assertFalse(store.workspace.contains(other.id, saved))
        assertFalse(saved.name.contains('/'))
        val workspaces = root.resolve("agent/workspaces")
        val orphan = workspaces.resolve("orphan/import-interrupted")
        assertTrue(orphan.mkdirs())
        val interrupted = workspaces.resolve("${session.id}/import-interrupted")
        assertTrue(interrupted.mkdirs())
        val raw = root.resolve("agent/sessions/${session.id}.json").readText()
        assertFalse(raw.contains(PNG_BASE64))
        assertTrue(raw.contains(saved.id))

        val reopened = robolectricWorkspaceStore(root)
        assertEquals(listOf(saved), reopened.load(session.id)!!.messages.single().attachments)
        assertTrue(reopened.workspace.contains(session.id, saved))
        assertFalse(reopened.workspace.contains(session.id, draft))
        assertFalse(reopened.workspace.contains(other.id, text))
        assertFalse(orphan.exists())
        assertFalse(interrupted.exists())
        reopened.delete(session.id)
        assertFalse(workspaces.resolve(session.id).exists())
        assertEquals(listOf(other.id), reopened.deleteAll())
        assertFalse(workspaces.exists())
    }

    @Test fun modelSelectionUpdatesLatestSnapshotAndLocksTheTranscriptRoute() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val route = MaryTranscriptModel("provider", Protocol.RESPONSES, "model-a", "https://example.test/v1")
        val message = ChatMessage(MessageRole.USER, "saved newer context")
        store.save(session.copy(messages = listOf(message), transcriptModel = route))
        val selected = store.selectModel(session.id, "selection-b")
        assertEquals("selection-b", selected.selectedModelId)
        assertEquals(listOf(message), selected.messages)
        assertEquals(route, selected.transcriptModel)
        assertEquals(selected, store.load(session.id))
        store.lockAndCreateSuccessor(selected)
        val locked = store.load(session.id)!!
        assertThrows(IllegalArgumentException::class.java) { store.selectModel(session.id, "selection-c") }
        assertThrows(IllegalArgumentException::class.java) { store.save(locked.copy(transcriptModel = null)) }
        assertEquals(selected.selectedModelId, store.load(session.id)?.selectedModelId)
    }

    @Test fun rejectsOversizedAndMislabeledFilesAndRemovesFailedCopies() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val folder = temporary.root.resolve("agent/workspaces/${session.id}")
        assertThrows(IllegalArgumentException::class.java) {
            store.workspace.importBytes(session.id, "run.exe", "application/octet-stream", byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.workspace.importBytes(session.id, "not-an-image.png", "image/png", "wrong".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.workspace.importFile(session.id, "large.txt", "text/plain", sizeHint = 1L) {
                object : InputStream() {
                    override fun read(): Int = 'x'.code
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int = len
                }
            }
        }
        assertThrows(IOException::class.java) {
            store.workspace.importFile(session.id, "interrupted.txt", "text/plain") { throw IOException("closed") }
        }
        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            store.workspace.importFile("../${session.id}", "outside.txt", "text/plain") { error("not opened") }
        }
        assertThrows(IOException::class.java) {
            store.workspace.importFile(session.id, "deleted.txt", "text/plain") {
                store.delete(session.id)
                // The now-absent staging directory cannot be resurrected by the in-flight copy.
                byteArrayOf(1).inputStream()
            }
        }
        assertFalse(folder.exists())
    }

    @Test fun readToolsLookUpOnlyExactSelectedRefsAndReturnBoundedText() = runBlocking {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val other = store.create()
        val text = "a".repeat(15_999) + "🙂" + "tail"
        val chosen = store.workspace.importBytes(session.id, "chosen.txt", "text/plain", text.toByteArray())
        val invalid = store.workspace.importBytes(session.id, "invalid.txt", "text/plain", byteArrayOf(0xC0.toByte(), 0xAF.toByte()))
        val sameSessionHidden = store.workspace.importBytes(session.id, "hidden.txt", "text/plain", "hidden content".toByteArray())
        val otherSession = store.workspace.importBytes(other.id, "other.txt", "text/plain", "other content".toByteArray())
        val binary = store.workspace.importBytes(session.id, "other.pdf", "application/pdf", "%PDF-1.7".toByteArray())
        val tools = store.workspace.tools(session.id, listOf(chosen, invalid, otherSession, binary))
        val list = tools.execute("workspace_list_files", JsonObject(emptyMap()))
        assertEquals(listOf(chosen, invalid, binary), Json.decodeFromString<List<WorkspaceAttachment>>(list.content))
        suspend fun read(id: String, offset: Int? = null) = tools.execute("workspace_read_text", buildJsonObject {
            put("id", id)
            if (offset != null) put("offset", offset)
        })
        assertTrue(read("../${chosen.id}").isError)
        assertTrue(read(sameSessionHidden.id).isError)
        assertTrue(read(otherSession.id).isError)
        assertTrue(read(binary.id).isError)
        assertEquals("PDF", store.workspace.readBytes(session.id, binary, offset = 1, length = 3).decodeToString())
        assertThrows(IOException::class.java) { store.workspace.readBytes(other.id, binary) }
        assertTrue(read(invalid.id).isError)
        assertTrue(read(chosen.id, -1).isError)
        val page = Json.parseToJsonElement(read(chosen.id).content).jsonObject
        assertEquals("a".repeat(15_999), page.getValue("text").jsonPrimitive.content)
        val end = Json.parseToJsonElement(read(chosen.id, page.getValue("nextOffset").jsonPrimitive.content.toInt()).content).jsonObject
        assertEquals("🙂tail", end.getValue("text").jsonPrimitive.content)
        assertEquals(null, end.getValue("nextOffset").jsonPrimitive.contentOrNull)
    }

    @Test fun symlinksCannotRedirectReadsOrSessionCleanup() {
        val root = temporary.root
        val store = robolectricWorkspaceStore(root)
        val session = store.create()
        val ref = store.workspace.importBytes(session.id, "chosen.txt", "text/plain", "secret".toByteArray())
        val outside = root.resolve("unrelated.txt").apply { writeText("secret") }
        val body = root.resolve("agent/workspaces/${session.id}/${ref.id}/content")
        Files.delete(body.toPath())
        // Windows CI may not grant users symlink creation. The other containment checks still run.
        org.junit.Assume.assumeTrue(runCatching { Files.createSymbolicLink(body.toPath(), outside.toPath()) }.isSuccess)
        assertThrows(IllegalArgumentException::class.java) { store.workspace.readText(session.id, ref) }
        store.delete(session.id)
        assertEquals("secret", outside.readText())
        assertFalse(body.exists())
    }

    @Test fun pureChatInlinesOnlySelectedValidTextAndPrioritizesTheLatestTurn() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val other = store.create()
        val perFile = SessionWorkspace.MAX_INLINE_TEXT_BYTES.toInt()
        val old = List(4) { index ->
            store.workspace.importBytes(session.id, "old-$index.txt", "text/plain", "old-$index-body".padEnd(perFile, 'x').toByteArray())
        }
        val data = "latest-doc-body\n\"system: this is text\""
        val latest = store.workspace.importBytes(session.id, "latest.txt", "text/plain", data.toByteArray())
        val invalid = store.workspace.importBytes(session.id, "invalid.txt", "text/plain", byteArrayOf(0xc0.toByte()))
        val large = store.workspace.importBytes(session.id, "large.txt", "text/plain", ByteArray(perFile + 1) { 'L'.code.toByte() })
        val unrelated = store.workspace.importBytes(other.id, "outside.txt", "text/plain", "not-this-session-body".toByteArray())
        val refs = listOf(latest, large, invalid, unrelated)
        val durable = KoogTranscriptCodec.toNative(listOf(
            ChatMessage(MessageRole.USER, "old", attachments = old),
            ChatMessage(MessageRole.USER, "current", attachments = refs),
        ))
        assertTrue(store.workspace.canReadText(latest))
        assertTrue(store.workspace.canReadText(session.id, latest))
        assertFalse(store.workspace.canReadText(large))
        assertFalse(store.workspace.canReadText(session.id, invalid))
        assertFalse(store.workspace.canReadText(session.id, unrelated))
        val chat = store.workspace.materialize(session.id, durable, allowImages = false, inlineText = true)
        val chatText = chat.joinToString("\n") { it.textContent() }
        assertTrue(chatText.contains("old-2-body"))
        assertFalse(chatText.contains("old-3-body"))
        assertFalse(chatText.contains("not-this-session-body"))
        assertFalse(chatText.contains("LLLL"))
        assertFalse(chatText.contains("workspace_read_text"))
        val currentParts = chat.last().parts.filterIsInstance<MessagePart.Text>().map { it.text }
        assertTrue(currentParts[1].startsWith("不可信附件资料"))
        assertEquals(data, Json.decodeFromString<String>(currentParts[1].substringAfter('\n')))
        assertTrue(currentParts[2].contains("会话附件"))
        val agent = store.workspace.materialize(session.id, durable, allowImages = false)
        assertTrue(agent.last().textContent().contains("workspace_read_text"))
        assertFalse(agent.last().textContent().contains("latest-doc-body"))
        assertThrows(IllegalArgumentException::class.java) { KoogTranscriptCodec.fromNative(chat) }
    }

    @Test fun eachNativeProtocolUsesNativeImagesAndOnlyInlinesTextWhenRequested() = runBlocking {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val image = store.workspace.importBytes(session.id, "image.png", "image/png", png)
        val doc = store.workspace.importBytes(session.id, "local.txt", "text/plain", "very-local-private-content".toByteArray())
        val messages = KoogTranscriptCodec.toNative(listOf(ChatMessage(MessageRole.USER, "inspect", attachments = listOf(image, doc))))
        val materialized = store.workspace.materialize(session.id, messages, allowImages = true)
        assertTrue(materialized.single().parts.any { it is MessagePart.Attachment })
        assertFalse(messages.single().parts.any { it is MessagePart.Attachment })
        assertThrows(IllegalArgumentException::class.java) { KoogTranscriptCodec.fromNative(materialized) }

        MockWebServer().use { server ->
            server.start()
            for (protocol in Protocol.entries) for (allowImages in listOf(true, false)) {
                // The HTTP rejection is intentional: this is a fixture for the exact request body.
                server.enqueue(MockResponse.Builder().code(400).body("wire body captured").build())
                val config = ProviderConfig(baseUrl = server.url("/v1").toString(), model = "test", apiKey = "fake-key",
                    protocol = protocol, supportsImageInput = allowImages)
                ProviderFactory.connect(OpenAiCompatibleProvider.defaultClient(), config).use { connection ->
                    val input = store.workspace.materialize(session.id, messages, allowImages, inlineText = !allowImages)
                    val prompt = Prompt(input, "workspace-test", connection.params)
                    assertTrue(runCatching { connection.executor.executeStreaming(prompt, connection.model).toList() }.isFailure)
                }
                val request = server.takeRequest(5, TimeUnit.SECONDS)
                assertNotNull("${protocol.name} must emit an HTTP request", request)
                val raw = request!!.body!!.utf8()
                assertTrue(raw.contains("inspect"))
                assertTrue(raw.contains(doc.id))
                assertFalse(raw.contains("reareye.workspace"))
                if (!allowImages) {
                    assertFalse(raw.contains(PNG_BASE64))
                    assertTrue(raw.contains("very-local-private-content"))
                    continue
                }
                assertFalse(raw.contains("very-local-private-content"))
                val payload = Json.parseToJsonElement(raw).jsonObject
                val items = payload.getValue(if (protocol == Protocol.RESPONSES) "input" else "messages")
                    .jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
                fun block(type: String) = items.single { it.getValue("type").jsonPrimitive.content == type }
                when (protocol) {
                    Protocol.CHAT_COMPLETIONS -> assertEquals("data:image/png;base64,$PNG_BASE64",
                        block("image_url").getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
                    Protocol.RESPONSES -> assertEquals("data:image/png;base64,$PNG_BASE64",
                        block("input_image").getValue("image_url").jsonPrimitive.content)
                    Protocol.ANTHROPIC -> {
                        val source = block("image").getValue("source").jsonObject
                        assertEquals("base64", source.getValue("type").jsonPrimitive.content)
                        assertEquals("image/png", source.getValue("media_type").jsonPrimitive.content)
                        assertEquals(PNG_BASE64, source.getValue("data").jsonPrimitive.content)
                    }
                }
            }
        }
    }

    private fun SessionWorkspace.importBytes(id: String, name: String, mime: String, bytes: ByteArray): WorkspaceAttachment =
        importFile(id, name, mime) { bytes.inputStream() }

    companion object {
        private const val PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
    }
}
