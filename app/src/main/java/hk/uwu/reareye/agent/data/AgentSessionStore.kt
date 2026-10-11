package hk.uwu.reareye.agent.data

import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString

/** One stored conversation. */
@Serializable
data class AgentSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
)

/** Listing entry, kept in a single index file so the list screen never reads every session. */
@Serializable
data class AgentSessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

@Serializable
private data class AgentSessionIndex(val sessions: List<AgentSessionSummary> = emptyList())

/**
 * File-backed conversation store.
 *
 * One JSON file per session plus an index file, rather than a database: the data volume is small,
 * the schema is simple, and it keeps the module free of a persistence dependency. Every write goes
 * through a temp file and an atomic rename so a kill mid-write cannot truncate a conversation.
 */
class AgentSessionStore(root: File) {

    private val dir = File(root, "agent/sessions")
    private val indexFile = File(root, "agent/index.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        dir.mkdirs()
    }

    fun list(): List<AgentSessionSummary> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<AgentSessionIndex>(indexFile.readText()).sessions
        }.getOrElse { emptyList() }
            .sortedByDescending { it.updatedAt }
    }

    fun load(id: String): AgentSession? {
        val file = sessionFile(id)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<AgentSession>(file.readText()) }.getOrNull()
    }

    fun create(title: String = DEFAULT_TITLE): AgentSession {
        val now = System.currentTimeMillis()
        val session = AgentSession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        write(session)
        return session
    }

    fun save(session: AgentSession) {
        write(session.copy(updatedAt = System.currentTimeMillis()))
    }

    fun delete(id: String) {
        sessionFile(id).delete()
        rewriteIndex(list().filterNot { it.id == id })
    }

    fun deleteAll(): List<String> {
        val ids = list().map { it.id }
        ids.forEach { sessionFile(it).delete() }
        rewriteIndex(emptyList())
        return ids
    }

    private fun write(session: AgentSession) {
        val stamped = session.copy(updatedAt = System.currentTimeMillis())
        atomicWrite(sessionFile(session.id), json.encodeToString(stamped))

        val summaries = list().toMutableList()
        val summary = AgentSessionSummary(
            id = stamped.id,
            title = stamped.title,
            createdAt = stamped.createdAt,
            updatedAt = stamped.updatedAt,
            messageCount = stamped.messages.size,
        )
        val existing = summaries.indexOfFirst { it.id == stamped.id }
        if (existing >= 0) summaries[existing] = summary else summaries += summary
        rewriteIndex(summaries)
    }

    private fun rewriteIndex(summaries: List<AgentSessionSummary>) {
        atomicWrite(indexFile, json.encodeToString(AgentSessionIndex(summaries)))
    }

    private fun sessionFile(id: String) = File(dir, "$id.json")

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            // Some filesystems refuse to replace; fall back to delete-then-rename.
            target.delete()
            temp.renameTo(target)
        }
    }

    companion object {
        const val DEFAULT_TITLE = "新的对话"

        /** Derive a short title from the first user message. */
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
