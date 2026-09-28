package hk.uwu.reareye.ui.config

import android.content.Context
import hk.uwu.reareye.hook.support.YLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** root 固定命令的一次执行结果。 */
data class RootCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
    val timedOut: Boolean = false,
    val outputLimitExceeded: Boolean = false,
)

/**
 * 旧 Yuki 来源使用的受限 root 命令接口。
 *
 * 接口不接收任意命令或任意路径：生产实现只实现固定发现命令和从发现结果创建的复制目标。
 */
internal interface RootCommandRunner {
    /** 扫描固定 apexdata 根目录下的旧偏好文件尾部。 */
    fun discoverLegacyPreferencePaths(): RootCommandResult

    /** 把已经通过源路径和目标路径校验的旧偏好文件复制到应用临时 SharedPreferences。 */
    fun copyLegacyPreference(
        path: LegacyPreferencePath,
        destination: LegacyPreferenceCopyDestination,
    ): RootCommandResult
}

/**
 * 通过 su 执行固定命令的 root runner。
 *
 * stdout/stderr 均有上限，命令有明确超时；失败时不会把命令输出或 XML 配置值写入日志。
 */
internal class ProcessBuilderRootCommandRunner(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val outputLimitBytes: Int = DEFAULT_OUTPUT_LIMIT_BYTES,
) : RootCommandRunner {
    init {
        require(timeoutMillis > 0) { "Root command timeout must be positive" }
        require(outputLimitBytes > 0) { "Root command output limit must be positive" }
    }

    override fun discoverLegacyPreferencePaths(): RootCommandResult = execute(
        "find /data/misc/apexdata -type f -path '*/prefs/hk.uwu.reareye/hk.uwu.reareye_preferences.xml' -print",
    )

    override fun copyLegacyPreference(
        path: LegacyPreferencePath,
        destination: LegacyPreferenceCopyDestination,
    ): RootCommandResult {
        LegacyPreferencePath.fromDiscoveredPath(path.value)
        destination.validate()
        val source = shellQuote(path.value)
        val target = shellQuote(destination.value)
        val owner = "${destination.ownerUid}:${destination.ownerUid}"
        return execute(
            "cp -- $source $target && " +
                    "chown $owner $target && " +
                    "chmod 600 $target",
        )
    }

    private fun execute(command: String): RootCommandResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()
        val stdoutReader = BoundedStreamReader(process.inputStream, outputLimitBytes)
        val stderrReader = BoundedStreamReader(process.errorStream, outputLimitBytes)
        val stdoutThread = Thread(stdoutReader, "reareye-root-stdout").apply { isDaemon = true }
        val stderrThread = Thread(stderrReader, "reareye-root-stderr").apply { isDaemon = true }
        stdoutThread.start()
        stderrThread.start()

        var timedOut = false
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                timedOut = true
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            stdoutThread.join(STREAM_JOIN_MILLIS)
            stderrThread.join(STREAM_JOIN_MILLIS)
            if (process.isAlive) process.destroyForcibly()
        }

        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        return RootCommandResult(
            exitCode = exitCode,
            stdout = stdoutReader.text(),
            stderr = stderrReader.text(),
            timedOut = timedOut,
            outputLimitExceeded = stdoutReader.exceeded || stderrReader.exceeded,
        )
    }

    private class BoundedStreamReader(
        private val input: InputStream,
        private val limitBytes: Int,
    ) : Runnable {
        private val output = ByteArrayOutputStream(minOf(limitBytes, INITIAL_BUFFER_BYTES))

        @Volatile
        var exceeded: Boolean = false
            private set

        override fun run() {
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
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

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_OUTPUT_LIMIT_BYTES = 64 * 1024 * 1024
        const val STREAM_BUFFER_BYTES = 8 * 1024
        const val INITIAL_BUFFER_BYTES = 16 * 1024
        const val STREAM_JOIN_MILLIS = 1_000L

        fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"
    }
}

/**
 * root 旧 Yuki 偏好来源。
 *
 * 发现结果必须恰好包含一个固定尾部路径；0 个或多个来源都会明确失败，不会猜测 UUID 或用户层级。
 * root 只负责把文件复制到当前应用的临时 SharedPreferences，实际 XML 类型和值读取交给 Android
 * SharedPreferences 实现完成。
 */
internal class RootLegacyPreferenceSource(
    context: Context,
    private val commandRunner: RootCommandRunner = ProcessBuilderRootCommandRunner(),
) : LegacyPreferenceSource {
    private val applicationContext = context.applicationContext

    override fun read(): LegacyPreferenceSnapshot {
        var sourcePath = "<none>"
        var sourceCount = -1
        var destination: LegacyPreferenceCopyDestination? = null
        var snapshot: LegacyPreferenceSnapshot? = null
        var failure: Throwable? = null

        try {
            val discovery = commandRunner.discoverLegacyPreferencePaths()
            requireCommandSuccess("discover", discovery)
            val rawPaths = parseDiscoveryOutput(discovery.stdout)
            sourceCount = rawPaths.size
            require(sourceCount == 1) {
                "Legacy preference source count must be exactly one: sourceCount=$sourceCount"
            }
            val path = LegacyPreferencePath.fromDiscoveredPath(rawPaths.single())
            sourcePath = path.value

            val temporaryDestination = LegacyPreferenceCopyDestination.create(applicationContext)
            destination = temporaryDestination
            val copyResult = commandRunner.copyLegacyPreference(path, temporaryDestination)
            requireCommandSuccess("copy", copyResult)

            snapshot = LegacyPreferenceSnapshot(
                sourcePath = path,
                values = readCopiedPreferences(temporaryDestination.tempName),
            )
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            destination?.let { temporaryDestination ->
                val cleanupFailure = runCatching {
                    deleteTemporaryPreferences(temporaryDestination.tempName)
                }.exceptionOrNull()
                if (cleanupFailure != null) {
                    YLog.error(
                        "Unable to delete temporary legacy preference file: " +
                                "path=$sourcePath tempName=${temporaryDestination.tempName}",
                        cleanupFailure,
                    )
                    if (failure == null) {
                        failure = cleanupFailure
                    } else {
                        failure?.addSuppressed(cleanupFailure)
                    }
                }
            }
        }

        failure?.let { throwable ->
            YLog.error(
                "Legacy Yuki preference source failed: path=$sourcePath sourceCount=$sourceCount",
                throwable,
            )
            throw throwable
        }
        return requireNotNull(snapshot) { "Legacy preference source produced no snapshot" }
    }

    private fun readCopiedPreferences(tempName: String): Map<String, Any?> {
        val preferences = applicationContext.getSharedPreferences(tempName, Context.MODE_PRIVATE)
        return preferences.all.mapValues { (_, value) ->
            when (value) {
                is Set<*> -> {
                    require(value.all { it is String }) {
                        "Copied legacy preference contains a non-string set value"
                    }
                    value.toSet()
                }

                is String, is Boolean, is Int, is Long, is Float -> value
                else -> error("Copied legacy preference contains an unsupported value type")
            }
        }
    }

    private fun deleteTemporaryPreferences(tempName: String) {
        require(applicationContext.deleteSharedPreferences(tempName)) {
            "Unable to delete temporary legacy preference file"
        }
    }

    private fun parseDiscoveryOutput(stdout: String): List<String> {
        val lines = stdout.split('\n').map { it.removeSuffix("\r") }.toMutableList()
        while (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        require(lines.isNotEmpty()) { "Legacy preference source discovery returned no paths" }
        require(lines.none(String::isEmpty)) { "Legacy preference source discovery returned an empty path" }
        return lines
    }

    private fun requireCommandSuccess(operation: String, result: RootCommandResult) {
        require(!result.timedOut) { "Root preference $operation command timed out" }
        require(!result.outputLimitExceeded) { "Root preference $operation output exceeded the limit" }
        require(result.exitCode == 0) { "Root preference $operation command failed: exitCode=${result.exitCode}" }
        require(result.stderr.isBlank()) { "Root preference $operation command wrote to stderr" }
    }
}
