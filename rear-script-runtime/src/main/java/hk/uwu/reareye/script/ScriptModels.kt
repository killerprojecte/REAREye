package hk.uwu.reareye.script

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class ScriptProjectKind { STANDALONE, PROJECT, COMPONENT }

/** id is the storage identity; the Lua metadata never controls paths or ownership. */
data class ScriptProject(
    val id: String,
    val name: String,
    val root: File,
    val mainFile: File,
    val kind: ScriptProjectKind,
    val componentId: String? = null,
    val description: String = "",
    val error: String? = null,
) {
    val isValid get() = error == null
}

data class ScriptConfigSpec(
    val id: String,
    val name: String,
    val type: String,
    val note: String = "",
    val defaultValue: Any? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: Map<String, String> = emptyMap(),
)

data class ScriptProvider(
    val id: String,
    val name: String,
    val description: String = "",
    val mimeType: String = "text/plain",
    val mode: String = "IMMEDIATE",
    val ttlMillis: Long = 0L,
)

data class ScriptDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val group: String,
    val providers: List<ScriptProvider>,
    val configs: List<ScriptConfigSpec>,
)

data class ScriptExecutionResult(
    val projectId: String,
    val providerId: String,
    val data: Any? = null,
    val error: String? = null,
    val elapsedMillis: Long = 0,
    val mimeType: String = "text/plain",
    val logs: List<String> = emptyList(),
    val cached: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0,
)

data class ScriptRootCommand(
    val command: String,
    val stdin: String = "",
    val timeoutMillis: Long = 10_000L,
)

data class ScriptRootResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val outputLimitExceeded: Boolean = false,
)

data class ScriptHttpRequest(
    val url: String,
    val method: String = "GET",
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMillis: Long = 15_000L,
    val followRedirects: Boolean = true,
    val followSslRedirects: Boolean = true,
)

data class ScriptHttpResponse(
    val statusCode: Int,
    val ok: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val contentType: String = "",
    val url: String = "",
)

/** Injected by the caller, including when this library is loaded inside another host. */
interface ScriptHost {
    fun now(): Long = System.currentTimeMillis()
    fun log(message: String) {}
    fun rootExec(request: ScriptRootCommand): ScriptRootResult = ScriptRootResult(
        exitCode = -1,
        stderr = "Root execution is unavailable",
    )

    fun httpRequest(request: ScriptHttpRequest): ScriptHttpResponse =
        ScriptHttpResponse(statusCode = 0, ok = false, body = "", contentType = "text/plain")
}

class ScriptCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()
}

object ScriptFiles {
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    fun resolve(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.contains('\\') && !relative.contains(':')) { "Invalid project path" }
        require(
            !File(relative).isAbsolute && relative.split('/')
                .none { it == ".." || it == "." }) { "Path escapes project" }
        val target = File(root, relative).canonicalFile
        require(
            target.toPath().startsWith(root.canonicalFile.toPath()) && target != root.canonicalFile
        ) { "Path escapes project" }
        return target
    }

    fun read(root: File, relative: String): String {
        val file = resolve(root, relative)
        require(file.isFile && file.length() <= MAX_FILE_BYTES) { "Missing or oversized project file: $relative" }
        return file.readText()
    }
}
