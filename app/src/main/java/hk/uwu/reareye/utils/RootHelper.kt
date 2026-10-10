package hk.uwu.reareye.utils

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class RootCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val outputLimitExceeded: Boolean = false,
)

object RootHelper {
    private const val TAG = "RootHelper"
    private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    private const val DEFAULT_OUTPUT_LIMIT_BYTES = 2 * 1024 * 1024

    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.use { it.write("exit\n".toByteArray()) }
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root access check failed", e)
            false
        }
    }

    fun executeRootCommand(command: String): Pair<Int, String> {
        val result = executeRootCommandDetailed(command)
        if (result.stderr.isNotBlank()) Log.w(TAG, "Command stderr: ${result.stderr.trim()}")
        return result.exitCode to result.stdout.trim()
    }

    fun executeRootCommandDetailed(
        command: String,
        stdin: String = "",
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        outputLimitBytes: Int = DEFAULT_OUTPUT_LIMIT_BYTES,
    ): RootCommandResult {
        require(command.isNotBlank()) { "Root command cannot be blank" }
        require(timeoutMillis > 0) { "Root command timeout must be positive" }
        require(outputLimitBytes > 0) { "Root command output limit must be positive" }
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
            process.outputStream.bufferedWriter().use { writer ->
                if (stdin.isNotEmpty()) writer.write(stdin)
            }

            val stdoutReader = BoundedStreamReader(process.inputStream, outputLimitBytes)
            val stderrReader = BoundedStreamReader(process.errorStream, outputLimitBytes)
            val stdoutThread =
                Thread(stdoutReader, "reareye-script-stdout").apply { isDaemon = true }
            val stderrThread =
                Thread(stderrReader, "reareye-script-stderr").apply { isDaemon = true }
            stdoutThread.start()
            stderrThread.start()

            var timedOut = false
            if (!process.waitFor(timeoutMillis.coerceIn(100L, 120_000L), TimeUnit.MILLISECONDS)) {
                timedOut = true
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
            stdoutThread.join(1_000L)
            stderrThread.join(1_000L)
            if (process.isAlive) process.destroyForcibly()

            RootCommandResult(
                exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
                stdout = stdoutReader.text(),
                stderr = stderrReader.text(),
                timedOut = timedOut,
                outputLimitExceeded = stdoutReader.exceeded || stderrReader.exceeded,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute root command", e)
            RootCommandResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    fun executeRootCommandSuccess(command: String): Boolean {
        val (exitCode, _) = executeRootCommand(command)
        return exitCode == 0
    }

    fun executeRootCommandOutput(command: String): String {
        val (_, output) = executeRootCommand(command)
        return output
    }

    private class BoundedStreamReader(
        private val input: InputStream,
        private val limitBytes: Int,
    ) : Runnable {
        private val output = ByteArrayOutputStream(minOf(limitBytes, 16 * 1024))

        @Volatile
        var exceeded: Boolean = false
            private set

        override fun run() {
            val buffer = ByteArray(8 * 1024)
            var total = 0
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = limitBytes - total
                    if (count > remaining) {
                        if (remaining > 0) output.write(buffer, 0, remaining)
                        exceeded = true
                        break
                    }
                    output.write(buffer, 0, count)
                    total += count
                }
            } finally {
                input.close()
            }
        }

        fun text(): String = output.toString(StandardCharsets.UTF_8.name())
    }
}
