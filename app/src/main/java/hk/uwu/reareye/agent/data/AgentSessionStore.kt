package hk.uwu.reareye.agent.data

import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Complete display history and model context are deliberately separate. */
@Serializable
data class AgentSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val schemaVersion: Int = 3,
    val locked: Boolean = false,
    val successorSessionId: String? = null,
    val contextMessages: List<ChatMessage>? = null,
    val pendingInteraction: String? = null,
    val interrupted: Boolean = false,
)

@Serializable
data class AgentSessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val locked: Boolean = false,
)

@Serializable
private data class AgentSessionIndex(val sessions: List<AgentSessionSummary> = emptyList())

/** Durable redo record: recovery only writes data, never executes pending tools. */
@Serializable
private data class SuccessorTransaction(val previous: AgentSession, val successor: AgentSession)

/** File-backed store. Session files are authoritative; the index is a rebuildable cache. */
class AgentSessionStore(root: File) {
    private val dir = File(root, "agent/sessions")
    private val indexFile = File(root, "agent/index.json")
    private val transactionFile = File(root, "agent/successor-transaction.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create session directory" }
        recoverTransaction()
    }

    @Synchronized
    fun list(): List<AgentSessionSummary> {
        recoverTransaction()
        // Also repairs a valid but stale index after a process dies between the two writes.
        return rebuildIndex()
    }

    @Synchronized
    fun load(id: String): AgentSession? {
        sessionFile(id) // Validate before any recovery side effects.
        recoverTransaction()
        return readSession(id)
    }

    @Synchronized
    fun create(title: String = DEFAULT_TITLE): AgentSession {
        recoverTransaction()
        val now = System.currentTimeMillis()
        val session = AgentSession(UUID.randomUUID().toString(), title, now, now)
        write(session)
        return session
    }

    @Synchronized
    fun save(session: AgentSession) {
        validateSession(session)
        recoverTransaction()
        readSession(session.id)?.takeIf { it.locked }?.let { existing ->
            require(session.locked) { "A locked session cannot be unlocked" }
            require(session.successorSessionId == existing.successorSessionId) {
                "A locked session's successor cannot be changed"
            }
            require(session.messages == existing.messages && session.contextMessages == existing.contextMessages) {
                "A locked session's history and context cannot be changed"
            }
        }
        write(session.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Idempotently locks this conversation and starts an empty, independent conversation. */
    @Synchronized
    fun lockAndCreateSuccessor(session: AgentSession): AgentSession {
        validateSession(session)
        recoverTransaction()
        val existing = readSession(session.id)
        existing?.successorSessionId?.let { id ->
            readSession(id)?.let { return it }
        }
        val now = System.currentTimeMillis()
        // A retry may carry an unlocked, stale in-memory snapshot. Once locked, disk is
        // authoritative, including when the successor was deleted or not yet persisted.
        val source = existing?.takeIf { it.locked } ?: session
        val successorId = source.successorSessionId ?: UUID.randomUUID().toString()
        val successor = AgentSession(successorId, DEFAULT_TITLE, now, now)
        val previous =
            source.copy(locked = true, successorSessionId = successor.id, updatedAt = now)
        // Once this record is durable, any partial application is completed on reopen/retry.
        atomicWrite(transactionFile, json.encodeToString(SuccessorTransaction(previous, successor)))
        recoverTransaction()
        return successor
    }

    @Synchronized
    fun delete(id: String) {
        val file = sessionFile(id)
        recoverTransaction()
        deleteChecked(file)
        rebuildIndex()
    }

    @Synchronized
    fun deleteAll(): List<String> {
        recoverTransaction()
        val ids = readSessions().map { it.id }
        ids.forEach { deleteChecked(sessionFile(it)) }
        rebuildIndex()
        return ids
    }

    private fun readSession(id: String): AgentSession? {
        val file = sessionFile(id)
        if (!file.isFile) return null
        val raw = file.readText()
        val session = runCatching {
            json.decodeFromString<AgentSession>(raw).also {
                require(it.id == id) { "Session filename and id differ" }
            }
        }.getOrElse { error ->
            // Preserve legacy corrupt-file handling, but never disguise damaged native history
            // as an absent session (which a caller could replace with an empty conversation).
            val document = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            val hasNative = listOf("messages", "contextMessages").any { field ->
                (document?.get(field) as? JsonArray)?.any {
                    val encoding = (it as? JsonObject)?.get("nativeEncoding")
                    encoding != null && encoding != JsonNull
                } == true
            }
            if (hasNative) throw IllegalArgumentException("Invalid native session: $id", error)
            return null
        }
        validateSession(session)
        return session.copy(schemaVersion = 3)
    }

    private fun readSessions(): List<AgentSession> =
        (dir.listFiles() ?: throw IOException("Cannot list session directory"))
            .filter { it.isFile && it.extension == "json" }
            .filter { SESSION_ID.matches(it.nameWithoutExtension) }
            .mapNotNull { readSession(it.nameWithoutExtension) }

    private fun write(session: AgentSession) {
        validateSession(session)
        atomicWrite(sessionFile(session.id), json.encodeToString(session))
        rebuildIndex()
    }

    private fun rebuildIndex(): List<AgentSessionSummary> {
        val summaries = readSessions().map {
            AgentSessionSummary(
                it.id,
                it.title,
                it.createdAt,
                it.updatedAt,
                it.messages.size,
                it.locked
            )
        }.sortedByDescending { it.updatedAt }
        atomicWrite(indexFile, json.encodeToString(AgentSessionIndex(summaries)))
        return summaries
    }

    private fun recoverTransaction() {
        if (!transactionFile.exists()) return
        // A damaged journal is an explicit error, not permission to silently discard history.
        val transaction = json.decodeFromString<SuccessorTransaction>(transactionFile.readText())
        validateSession(transaction.previous)
        validateSession(transaction.successor)
        require(
            transaction.previous.locked &&
                    transaction.previous.successorSessionId == transaction.successor.id &&
                    transaction.previous.id != transaction.successor.id
        ) { "Invalid successor transaction" }
        atomicWrite(sessionFile(transaction.previous.id), json.encodeToString(transaction.previous))
        atomicWrite(
            sessionFile(transaction.successor.id),
            json.encodeToString(transaction.successor)
        )
        rebuildIndex()
        deleteChecked(transactionFile)
    }

    private fun validateSession(session: AgentSession) {
        require(session.schemaVersion in 1..3) { "Unsupported session schema: ${session.schemaVersion}" }
        KoogTranscriptCodec.validate(session.messages)
        session.contextMessages?.let(KoogTranscriptCodec::validate)
        sessionFile(session.id)
        session.successorSessionId?.let {
            sessionFile(it)
            require(it != session.id) { "A session cannot succeed itself" }
        }
    }

    private fun sessionFile(id: String): File {
        require(SESSION_ID.matches(id)) { "Invalid session id" }
        return File(dir, "$id.json").also {
            require(it.canonicalFile.parentFile == dir.canonicalFile) { "Session escapes storage directory" }
        }
    }

    private fun deleteChecked(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Cannot delete ${file.name}")
    }

    private fun atomicWrite(target: File, content: String) {
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create storage directory" }
        val temp = File.createTempFile("${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temp).use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Native same-directory rename either replaces the destination or leaves it intact.
                // Never delete the last good destination to make replacement succeed.
                if (!temp.renameTo(target)) throw IOException("Cannot atomically replace ${target.name}")
            } catch (_: UnsupportedOperationException) {
                if (!temp.renameTo(target)) throw IOException("Cannot atomically replace ${target.name}")
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val DEFAULT_TITLE = "新的对话"
        private val SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

        fun deriveTitle(messages: List<ChatMessage>): String =
            messages.firstOrNull { it.role == MessageRole.USER }
                ?.content
                ?.trim()
                ?.replace('\n', ' ')
                ?.take(24)
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_TITLE
    }
}
