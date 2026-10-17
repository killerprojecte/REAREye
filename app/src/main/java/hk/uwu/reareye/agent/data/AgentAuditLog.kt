package hk.uwu.reareye.agent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.serialization.encodeToString

/** One executed tool call. The audit trail exists so the user can answer "what did Mary change?". */
@Serializable
data class AuditEntry(
    val timestamp: Long,
    val sessionId: String,
    val toolName: String,
    /** Truncated argument summary; never the raw model output. */
    val arguments: String,
    val success: Boolean,
    val detail: String? = null,
    val durationMs: Long,
)

/**
 * Append-only JSONL log of tool executions.
 *
 * Append-only so a crash cannot lose earlier records, and deliberately simple: this is an audit
 * trail for a module that edits system-level configuration, not an analytics store.
 */
class AgentAuditLog(root: File) {

    private val dir = File(root, "agent/audit")

    private val json = Json { encodeDefaults = true }

    private fun file() = File(dir, "audit-${stamp()}.jsonl")

    fun append(entry: AuditEntry) {
        runCatching {
            dir.mkdirs()
            file().appendText(json.encodeToString(entry) + "\n")
        }
    }

    /** Most recent entries first, spanning at most [maxFiles] daily files. */
    fun readRecent(limit: Int = 200, maxFiles: Int = 7): List<AuditEntry> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name }
            ?.take(maxFiles)
            .orEmpty()

        val entries = mutableListOf<AuditEntry>()
        for (file in files) {
            runCatching {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    runCatching { json.decodeFromString<AuditEntry>(line) }
                        .getOrNull()
                        ?.let { entries += it }
                }
            }
        }
        return entries.sortedByDescending { it.timestamp }.take(limit)
    }

    fun clear() {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun stamp(): String {
        val now = java.util.Calendar.getInstance()
        val year = now.get(java.util.Calendar.YEAR)
        val month = now.get(java.util.Calendar.MONTH) + 1
        val day = now.get(java.util.Calendar.DAY_OF_MONTH)
        return "%04d%02d%02d".format(year, month, day)
    }
}
