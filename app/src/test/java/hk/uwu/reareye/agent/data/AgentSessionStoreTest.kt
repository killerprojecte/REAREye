package hk.uwu.reareye.agent.data

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.NativeMessageEncoding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentSessionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val root: File get() = temporaryFolder.root
    private val json = Json { encodeDefaults = true }

    @Test
    fun historicalSchemaLoadsWithSafeDefaults() {
        val store = AgentSessionStore(root)
        File(root, "agent/sessions/legacy.json").writeText(
            """{"id":"legacy","title":"旧对话","createdAt":1,"updatedAt":2,"messages":[{"role":"user","content":"历史"}],"futureField":true}"""
        )
        val session = requireNotNull(store.load("legacy"))
        assertFalse(session.locked)
        assertNull(session.successorSessionId)
        assertNull(session.contextMessages)
        assertNull(session.pendingInteraction)
        assertFalse(session.interrupted)
        assertEquals("历史", session.messages.single().content)
        assertFalse(store.list().single().locked)
    }

    @Test
    fun lockingCreatesOneEmptySuccessorAndPreservesCompleteHistory() {
        val store = AgentSessionStore(root)
        val original = store.create().copy(
            messages = listOf(ChatMessage(MessageRole.USER, "完整记录")),
            contextMessages = listOf(ChatMessage(MessageRole.SYSTEM, "压缩摘要")),
            pendingInteraction = "{\"type\":\"approval\"}", interrupted = true,
        )
        val next = store.lockAndCreateSuccessor(original)
        val locked = requireNotNull(store.load(original.id))
        assertTrue(locked.locked)
        assertEquals(next.id, locked.successorSessionId)
        assertEquals(original.messages, locked.messages)
        assertEquals(original.contextMessages, locked.contextMessages)
        assertEquals(original.pendingInteraction, locked.pendingInteraction)
        assertTrue(locked.interrupted)
        assertTrue(next.messages.isEmpty())
        assertNull(next.contextMessages)
        assertNull(next.pendingInteraction)
        assertFalse(next.locked)
        assertFalse(next.interrupted)
        assertEquals(next.id, store.lockAndCreateSuccessor(original).id)
        assertEquals(2, store.list().size)
        assertTrue(store.list().first { it.id == original.id }.locked)
    }

    @Test
    fun manualLockAndExtendedFieldsSurviveReopening() {
        val store = AgentSessionStore(root)
        val session = store.create().copy(
            locked = true, contextMessages = emptyList(),
            pendingInteraction = "{}", interrupted = true
        )
        store.save(session)
        val loaded = requireNotNull(AgentSessionStore(root).load(session.id))
        assertTrue(loaded.locked)
        assertEquals(emptyList<ChatMessage>(), loaded.contextMessages)
        assertEquals("{}", loaded.pendingInteraction)
        assertTrue(loaded.interrupted)
    }

    @Test
    fun lockedSessionRejectsStaleSnapshotsAndHistoryChangesButAllowsCleanup() {
        val store = AgentSessionStore(root)
        val original = store.create().copy(
            messages = listOf(ChatMessage(MessageRole.USER, "immutable")),
            contextMessages = listOf(ChatMessage(MessageRole.SYSTEM, "summary")),
            pendingInteraction = "{}", interrupted = true,
        )
        store.lockAndCreateSuccessor(original)
        val locked = requireNotNull(store.load(original.id))
        assertIllegalArgument { store.save(original) }
        assertIllegalArgument { store.save(locked.copy(locked = false)) }
        assertIllegalArgument { store.save(locked.copy(successorSessionId = "different")) }
        assertIllegalArgument { store.save(locked.copy(successorSessionId = null)) }
        assertIllegalArgument { store.save(locked.copy(messages = emptyList())) }
        assertIllegalArgument {
            store.save(
                locked.copy(
                    messages = locked.messages + ChatMessage(
                        MessageRole.USER,
                        "late"
                    )
                )
            )
        }
        assertIllegalArgument { store.save(locked.copy(contextMessages = emptyList())) }
        assertIllegalArgument { store.save(locked.copy(contextMessages = null)) }
        assertEquals(locked, store.load(original.id))
        store.save(locked.copy(pendingInteraction = null, interrupted = false))
        val cleaned = requireNotNull(store.load(original.id))
        assertTrue(cleaned.locked)
        assertEquals(locked.successorSessionId, cleaned.successorSessionId)
        assertEquals(locked.messages, cleaned.messages)
        assertEquals(locked.contextMessages, cleaned.contextMessages)
        assertNull(cleaned.pendingInteraction)
        assertFalse(cleaned.interrupted)
    }

    @Test
    fun lockRetryNeverOverwritesManuallyLockedHistoryFromStaleSnapshot() {
        val store = AgentSessionStore(root)
        val stale = store.create()
        val locked = stale.copy(
            locked = true,
            messages = listOf(ChatMessage(MessageRole.USER, "persisted")),
            contextMessages = listOf(ChatMessage(MessageRole.SYSTEM, "persisted context"))
        )
        store.save(locked)
        val successor = store.lockAndCreateSuccessor(stale)
        val persisted = requireNotNull(store.load(stale.id))
        assertEquals(locked.messages, persisted.messages)
        assertEquals(locked.contextMessages, persisted.contextMessages)
        assertEquals(successor.id, persisted.successorSessionId)
        store.delete(successor.id)
        val recovered = store.lockAndCreateSuccessor(stale)
        assertEquals(successor.id, recovered.id)
        assertEquals(locked.messages, store.load(stale.id)?.messages)
        assertEquals(locked.contextMessages, store.load(stale.id)?.contextMessages)
    }

    @Test
    fun missingCorruptAndStaleIndexesRebuildFromSessions() {
        val store = AgentSessionStore(root)
        val first = store.create("first")
        val second = store.create("second")
        val index = File(root, "agent/index.json")
        for (contents in listOf<String?>(null, "broken json", "{\"sessions\":[]}")) {
            if (contents == null) assertTrue(index.delete()) else index.writeText(contents)
            assertEquals(setOf(first.id, second.id), store.list().map { it.id }.toSet())
            assertTrue(index.readText().contains(first.id))
        }
    }

    @Test
    fun interruptedTransactionIsCompletedWithoutExecutingAnything() {
        val store = AgentSessionStore(root)
        val previous = store.create().copy(
            locked = true, successorSessionId = "successor",
            pendingInteraction = "{\"tool\":\"do-not-run\"}"
        )
        val successor = AgentSession("successor", "new", 3L, 3L)
        val journal = File(root, "agent/successor-transaction.json")
        journal.writeText(
            "{\"previous\":${json.encodeToString(previous)},\"successor\":${
                json.encodeToString(
                    successor
                )
            }}"
        )
        // Simulate a stop after writing the old session but before creating the new session.
        File(root, "agent/sessions/${previous.id}.json").writeText(json.encodeToString(previous))
        val reopened = AgentSessionStore(root)
        assertEquals(previous, reopened.load(previous.id))
        assertEquals(successor, reopened.load(successor.id))
        assertEquals(successor.id, reopened.lockAndCreateSuccessor(previous).id)
        assertEquals(2, reopened.list().size)
        assertFalse(journal.exists())
    }

    @Test
    fun failedIndexReplacementKeepsRedoRecordForRecovery() {
        val store = AgentSessionStore(root)
        val original = store.create().copy(messages = listOf(ChatMessage(MessageRole.USER, "保留")))
        val index = File(root, "agent/index.json")
        assertTrue(index.delete())
        assertTrue(index.mkdir())
        File(index, "blocker").writeText("prevent replacement")
        try {
            store.lockAndCreateSuccessor(original)
            fail("Index replacement must fail against a non-empty directory")
        } catch (_: java.io.IOException) {
            // Both session writes can have succeeded, but the transaction must remain recoverable.
        }
        assertTrue(File(root, "agent/successor-transaction.json").isFile)
        assertTrue(index.deleteRecursively())
        val reopened = AgentSessionStore(root)
        val previous = requireNotNull(reopened.load(original.id))
        assertTrue(previous.locked)
        assertEquals(original.messages, previous.messages)
        val successor = requireNotNull(reopened.load(requireNotNull(previous.successorSessionId)))
        assertEquals(successor.id, reopened.lockAndCreateSuccessor(original).id)
        assertEquals(2, reopened.list().size)
        assertFalse(File(root, "agent/successor-transaction.json").exists())
    }

    @Test
    fun illegalSessionPathsAreRejectedWithoutTouchingOtherFiles() {
        val store = AgentSessionStore(root)
        val sentinel = File(root, "outside.json").apply { writeText("untouched") }
        for (id in listOf(
            "../outside",
            "..",
            "",
            "/absolute",
            "a/b",
            "a\\b",
            "C:escape",
            "a.json"
        )) {
            assertIllegalArgument { store.load(id) }
            assertIllegalArgument { store.delete(id) }
            assertIllegalArgument { store.save(AgentSession(id, "bad", 0L, 0L)) }
            assertIllegalArgument { store.lockAndCreateSuccessor(AgentSession(id, "bad", 0L, 0L)) }
        }
        assertIllegalArgument {
            store.save(
                AgentSession(
                    "valid",
                    "bad",
                    0L,
                    0L,
                    successorSessionId = "../outside"
                )
            )
        }
        assertEquals("untouched", sentinel.readText())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun corruptOrMismatchedSessionFilesDoNotPoisonList() {
        val store = AgentSessionStore(root)
        val good = store.create()
        File(root, "agent/sessions/corrupt.json").writeText("broken")
        File(root, "agent/sessions/mismatch.json").writeText(json.encodeToString(good))
        assertEquals(listOf(good.id), store.list().map { it.id })
        assertNull(store.load("corrupt"))
        assertNull(store.load("mismatch"))
    }

    @Test
    fun deletingSuccessorDoesNotResurrectItDuringReopen() {
        val store = AgentSessionStore(root)
        val previous = store.create()
        val successor = store.lockAndCreateSuccessor(previous)
        store.delete(successor.id)
        val reopened = AgentSessionStore(root)
        assertNull(reopened.load(successor.id))
        assertEquals(listOf(previous.id), reopened.list().map { it.id })
    }

    @Test
    fun nativeFullTranscriptAndCompactedContextSurviveLockAndReopenIndependently() {
        val native = listOf<Message>(
            Message.User("question", RequestMetaInfo.Empty),
            Message.Assistant(
                listOf(
                    MessagePart.Reasoning(emptyList(), encrypted = "opaque-responses", id = "rs_1"),
                    MessagePart.Reasoning("thinking", encrypted = "signature"),
                    MessagePart.Reasoning("", encrypted = "reareye:anthropic:redacted:opaque"),
                    MessagePart.Tool.Call("call-id", "dangerous-tool", "{}")
                ), ResponseMetaInfo.Empty
            ),
            Message.User(
                MessagePart.Tool.Result("call-id", "dangerous-tool", "not executed again"),
                RequestMetaInfo.Empty
            )
        )
        val context = listOf<Message>(Message.Assistant("summary only", ResponseMetaInfo.Empty))
        val store = AgentSessionStore(root)
        val session = store.create().copy(
            messages = KoogTranscriptCodec.fromNative(native),
            contextMessages = KoogTranscriptCodec.fromNative(context),
            pendingInteraction = "approval",
            interrupted = true
        )
        store.save(session)
        val successor = store.lockAndCreateSuccessor(session)
        val reopened = AgentSessionStore(root)
        val loaded = requireNotNull(reopened.load(session.id))
        assertEquals(native, KoogTranscriptCodec.toNative(loaded.messages))
        assertEquals(context, KoogTranscriptCodec.toNative(requireNotNull(loaded.contextMessages)))
        assertTrue(loaded.locked)
        assertEquals(successor.id, loaded.successorSessionId)
        assertEquals("approval", loaded.pendingInteraction)
        assertTrue(loaded.interrupted)
        assertEquals(3, loaded.schemaVersion)
        assertIllegalArgument { reopened.save(loaded.copy(messages = loaded.messages.dropLast(1))) }
    }

    @Test
    fun versionTwoHistoryMigratesWithoutReplacingCompleteHistoryWithContext() {
        val store = AgentSessionStore(root)
        File(root, "agent/sessions/v2.json").writeText(
            """{"id":"v2","title":"old","createdAt":1,"updatedAt":2,"schemaVersion":2,"messages":[{"role":"user","content":"complete"}],"contextMessages":[{"role":"assistant","content":"summary"}]}"""
        )
        val loaded = requireNotNull(store.load("v2"))
        assertEquals(3, loaded.schemaVersion)
        assertEquals("complete", loaded.messages.single().content)
        assertEquals("summary", loaded.contextMessages!!.single().content)
        store.save(loaded)
        assertEquals(loaded.messages, AgentSessionStore(root).load("v2")!!.messages)
    }

    @Test
    fun nativeCorruptionAndProjectionChangesAreExplicitErrorsNotMissingSessions() {
        val store = AgentSessionStore(root)
        val valid =
            KoogTranscriptCodec.fromNative(listOf(Message.User("real", RequestMetaInfo.Empty)))
                .single()
        val session = store.create().copy(messages = listOf(valid))
        store.save(session)
        assertIllegalArgument { store.save(session.copy(messages = listOf(valid.copy(content = "edited")))) }
        assertEquals(session.messages, store.load(session.id)!!.messages)
        val corrupt =
            session.copy(messages = listOf(valid.copy(nativeEncoding = NativeMessageEncoding(message = buildJsonObject {}))))
        val file = File(root, "agent/sessions/${session.id}.json")
        val raw = json.encodeToString(corrupt)
        file.writeText(raw)
        assertIllegalArgument { store.load(session.id) }
        assertIllegalArgument { store.list() }
        assertEquals(raw, file.readText())
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid session id to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
