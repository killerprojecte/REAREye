package hk.uwu.reareye.ui.config

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import hk.uwu.reareye.hook.support.LEGACY_PREFS_MIGRATION_COMPLETED_V2
import hk.uwu.reareye.hook.support.LEGACY_PREFS_MIGRATION_COMPLETED_V3
import hk.uwu.reareye.hook.support.LEGACY_PREFS_MIGRATION_COMPLETED_V4
import hk.uwu.reareye.hook.support.LEGACY_PREFS_MIGRATION_STATUS_KEY
import hk.uwu.reareye.hook.support.XposedModuleStatus
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 一次性旧 Yuki 配置迁移的结果分类。 */
enum class LegacyPreferenceMigrationOutcome {
    /** 已写入 authoritative RemotePreferences 并完成精确回读验证。 */
    COMPLETED,

    /** remote 已有 v4 完成状态，未执行 root。 */
    SKIPPED_ALREADY_COMPLETED,

    /** 任一阶段失败，完成状态未被本次迁移写入。 */
    FAILED,
}

/** 迁移结果的无值诊断摘要。blob 字节本体永远不会进入此结果或日志。 */
data class LegacyPreferenceMigrationResult(
    val outcome: LegacyPreferenceMigrationOutcome,
    val sourcePath: String,
    val sourceCount: Int,
    val transferCount: Int,
    /** 兼容既有调用方字段名；当前表示成功计划迁移的 blob 数量，而不是省略数量。 */
    val omittedDerivedCount: Int,
    /** 兼容既有调用方字段名；当前表示成功计划迁移的 blob 解码字节数。 */
    val omittedDerivedBytes: Long,
    val remoteBeforeCount: Int,
    val writtenCount: Int,
    val remoteAfterCount: Int,
    val verified: Boolean,
)

private const val REMOTE_PREFERENCES_COMMIT_BODY_LIMIT_BYTES = 768 * 1024

private data class RemoteBlobWrite(
    val fileName: String,
    val bytes: ByteArray,
)

private class MigrationTransferDiagnostics {
    var stage: String = "initial"
        private set
    var blobCount: Int = 0
        private set
    var blobBytes: Long = 0L
        private set
    var measuredBytes: Int = 0
        private set

    fun update(
        stage: String,
        blobCount: Int,
        blobBytes: Long,
        measuredBytes: Int,
    ) {
        this.stage = stage
        this.blobCount = blobCount
        this.blobBytes = blobBytes
        this.measuredBytes = measuredBytes
    }

    fun describe(): String =
        "stage=$stage blobCount=$blobCount blobBytes=$blobBytes measuredBytes=$measuredBytes " +
                "limitBytes=$REMOTE_PREFERENCES_COMMIT_BODY_LIMIT_BYTES"
}

/**
 * 对一个稳定的 RemotePreferences 快照执行一次旧 Yuki 配置迁移。
 *
 * 普通配置原样进入 authoritative map；rear widget blob payload 先逐项 Base64 解码并写入稳定
 * RemoteFile，再仅把小 marker 放入 map。所有远程文件成功写完后，才通过一个带 clear 的最终
 * RemotePreferences commit 提交普通配置、blob marker、来源/hash-size 元数据和 v4 完成状态。
 */
internal class LegacyPreferenceMigrator(
    private val source: LegacyPreferenceSource,
) {
    /** 在调用线程执行一次迁移；调用方负责把它放在 IO 线程。 */
    fun migrate(remote: PrefsManager): LegacyPreferenceMigrationResult = migrateInternal(remote)

    private fun migrateInternal(remote: PrefsManager): LegacyPreferenceMigrationResult {
        var sourcePath = "<none>"
        var sourceCount = -1
        var transferCount = -1
        var blobCount = -1
        var blobBytes = -1L
        var remoteBeforeCount = -1
        var writtenCount = 0
        var remoteAfterCount = -1
        val diagnostics = MigrationTransferDiagnostics()

        return try {
            diagnostics.update("remote-read", 0, 0L, 0)
            val remoteBefore = normalizePreferenceMap(remote.all())
            remoteBeforeCount = remoteBefore.size
            when (remoteBefore[LEGACY_PREFS_MIGRATION_STATUS_KEY]) {
                LEGACY_PREFS_MIGRATION_COMPLETED_V4 -> {
                    diagnostics.update("already-completed", 0, 0L, 0)
                    LegacyPreferenceMigrationResult(
                        outcome = LegacyPreferenceMigrationOutcome.SKIPPED_ALREADY_COMPLETED,
                        sourcePath = "<already-completed>",
                        sourceCount = 0,
                        transferCount = 0,
                        omittedDerivedCount = 0,
                        omittedDerivedBytes = 0L,
                        remoteBeforeCount = remoteBeforeCount,
                        writtenCount = 0,
                        remoteAfterCount = remoteBeforeCount,
                        verified = true,
                    ).also { logResult(it, diagnostics, skipped = true) }
                }

                null,
                LEGACY_PREFS_MIGRATION_COMPLETED_V2,
                LEGACY_PREFS_MIGRATION_COMPLETED_V3,
                    -> migrateFromSource(
                    remote = remote,
                    remoteBeforeCount = remoteBeforeCount,
                    setSourcePath = { sourcePath = it },
                    setSourceCount = { sourceCount = it },
                    setTransferCount = { transferCount = it },
                    setBlobCount = { blobCount = it },
                    setBlobBytes = { blobBytes = it },
                    setWrittenCount = { writtenCount = it },
                    setRemoteAfterCount = { remoteAfterCount = it },
                    diagnostics = diagnostics,
                )

                else -> error("Legacy migration status key has an unexpected value")
            }
        } catch (throwable: Throwable) {
            val result = LegacyPreferenceMigrationResult(
                outcome = LegacyPreferenceMigrationOutcome.FAILED,
                sourcePath = sourcePath,
                sourceCount = sourceCount,
                transferCount = transferCount,
                omittedDerivedCount = blobCount,
                omittedDerivedBytes = blobBytes,
                remoteBeforeCount = remoteBeforeCount,
                writtenCount = writtenCount,
                remoteAfterCount = remoteAfterCount,
                verified = false,
            )
            logFailure(result, diagnostics, throwable)
            result
        }
    }

    private fun migrateFromSource(
        remote: PrefsManager,
        remoteBeforeCount: Int,
        setSourcePath: (String) -> Unit,
        setSourceCount: (Int) -> Unit,
        setTransferCount: (Int) -> Unit,
        setBlobCount: (Int) -> Unit,
        setBlobBytes: (Long) -> Unit,
        setWrittenCount: (Int) -> Unit,
        setRemoteAfterCount: (Int) -> Unit,
        diagnostics: MigrationTransferDiagnostics,
    ): LegacyPreferenceMigrationResult {
        diagnostics.update("source-read", 0, 0L, 0)
        val snapshot = source.read()
        setSourcePath(snapshot.sourcePath.value)
        val legacyValues = normalizePreferenceMap(snapshot.values)
        setSourceCount(legacyValues.size)
        require(LEGACY_PREFS_MIGRATION_STATUS_KEY !in legacyValues) {
            "Legacy preference source contains the reserved migration status key"
        }

        diagnostics.update("blob-decode", 0, 0L, 0)
        val authoritativeValues = LinkedHashMap<String, Any?>(legacyValues.size + 1)
        val blobWrites = mutableListOf<RemoteBlobWrite>()
        var decodedBlobBytes = 0L
        legacyValues.forEach { (key, value) ->
            if (!RearWidgetConfigCodec.isBusinessBlobPayloadKey(key)) {
                authoritativeValues[key] = value
                return@forEach
            }

            val encoded = value as? String
                ?: error("Rear widget blob payload must be a String: blobIndex=${blobWrites.size + 1}")
            val bytes = Base64.decode(
                RearWidgetConfigCodec.legacyBase64Payload(encoded),
                Base64.DEFAULT,
            )
            val fileName = RearWidgetConfigCodec.businessBlobRemoteFileNameForKey(key)
            authoritativeValues[key] = RearWidgetConfigCodec.remoteBlobMarker(fileName)
            blobWrites += RemoteBlobWrite(fileName, bytes)
            decodedBlobBytes = Math.addExact(decodedBlobBytes, bytes.size.toLong())
            diagnostics.update("blob-decode", blobWrites.size, decodedBlobBytes, 0)
        }
        setTransferCount(authoritativeValues.size)
        setBlobCount(blobWrites.size)
        setBlobBytes(decodedBlobBytes)

        authoritativeValues[LEGACY_PREFS_MIGRATION_STATUS_KEY] = LEGACY_PREFS_MIGRATION_COMPLETED_V4
        val commitBytes = measureCommitBundleSize(clear = true, putValues = authoritativeValues)
        diagnostics.update("authoritative-plan", blobWrites.size, decodedBlobBytes, commitBytes)
        check(commitBytes <= REMOTE_PREFERENCES_COMMIT_BODY_LIMIT_BYTES) {
            "Legacy preference authoritative map exceeds Binder limit: ${diagnostics.describe()}"
        }

        diagnostics.update("remote-file-write", 0, 0L, commitBytes)
        blobWrites.forEachIndexed { index, blob ->
            check(remote.writeRemoteFile(blob.fileName, blob.bytes)) {
                "Legacy preference RemoteFile write failed: blobIndex=${index + 1} " +
                        "blobCount=${blobWrites.size} blobBytes=$decodedBlobBytes"
            }
            diagnostics.update("remote-file-write", index + 1, decodedBlobBytes, commitBytes)
        }
        logBlobTransfer(legacyValues.size, blobWrites.size, decodedBlobBytes, "remote-file-write")

        diagnostics.update("remote-clear", blobWrites.size, decodedBlobBytes, commitBytes)
        check(remote.clearRemotePreferences()) {
            "Legacy preference RemotePreferences clear failed: ${diagnostics.describe()}"
        }

        diagnostics.update("authoritative-commit", blobWrites.size, decodedBlobBytes, commitBytes)
        setRemoteAfterCount(0)
        authoritativeValues.forEach { (key, value) ->
            putPreferenceValue(remote, key, value)
        }
        setWrittenCount(authoritativeValues.size)

        diagnostics.update("verify", blobWrites.size, decodedBlobBytes, commitBytes)
        val actual = normalizePreferenceMap(remote.all())
        check(verifyExactPreferences(authoritativeValues, actual)) {
            "Legacy preference authoritative RemotePreferences verification failed: ${diagnostics.describe()}"
        }
        setRemoteAfterCount(actual.size)

        return LegacyPreferenceMigrationResult(
            outcome = LegacyPreferenceMigrationOutcome.COMPLETED,
            sourcePath = snapshot.sourcePath.value,
            sourceCount = legacyValues.size,
            transferCount = authoritativeValues.size - 1,
            omittedDerivedCount = blobWrites.size,
            omittedDerivedBytes = decodedBlobBytes,
            remoteBeforeCount = remoteBeforeCount,
            writtenCount = authoritativeValues.size,
            remoteAfterCount = actual.size,
            verified = true,
        ).also { logResult(it, diagnostics, skipped = false) }
    }

    /** 使用 libxposed RemotePreferences.Editor.buildCommitBundle 的字段和容器类型测量事务主体。 */
    @Suppress("DEPRECATION")
    private fun measureCommitBundleSize(
        clear: Boolean,
        putValues: Map<String, Any?>,
    ): Int {
        val commitBundle = Bundle().apply {
            putBoolean("clear", clear)
            putSerializable("delete", HashSet<String>())
            putSerializable("put", HashMap(putValues))
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(commitBundle)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private fun putPreferenceValue(editor: PrefsManager, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Set<*> -> {
                require(value.all { it is String }) {
                    "Unsupported StringSet element type in legacy preference source"
                }
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }

            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            else -> error("Unsupported legacy preference value type")
        }
    }

    private fun verifyExactPreferences(
        expected: Map<String, Any?>,
        actual: Map<String, Any?>
    ): Boolean {
        if (expected.keys != actual.keys) return false
        return expected.all { (key, value) -> preferenceValueEquals(value, actual.getValue(key)) }
    }

    private fun preferenceValueEquals(expected: Any?, actual: Any?): Boolean = when (expected) {
        is String -> actual is String && actual == expected
        is Set<*> -> actual is Set<*> && actual.all { it is String } && actual == expected
        is Boolean -> actual is Boolean && actual == expected
        is Int -> actual is Int && actual == expected
        is Long -> actual is Long && actual == expected
        is Float -> actual is Float && actual.toBits() == expected.toBits()
        else -> false
    }

    private fun normalizePreferenceMap(values: Map<String, *>): Map<String, Any?> =
        values.mapValues { (_, value) ->
            when (value) {
                is Set<*> -> {
                    require(value.all { it is String }) {
                        "Unsupported SharedPreferences StringSet element type"
                    }
                    value.toSet()
                }

                is String, is Boolean, is Int, is Long, is Float -> value
                else -> error("Unsupported SharedPreferences value type")
            }
        }

    private fun logBlobTransfer(sourceCount: Int, blobCount: Int, blobBytes: Long, stage: String) {
        YLog.info(
            "Legacy Yuki preference migration blob transfer: " +
                    "stage=$stage sourceCount=$sourceCount blobCount=$blobCount blobBytes=$blobBytes",
        )
    }

    private fun logResult(
        result: LegacyPreferenceMigrationResult,
        diagnostics: MigrationTransferDiagnostics,
        skipped: Boolean,
    ) {
        val message =
            "Legacy Yuki preference migration " + if (skipped) "skipped: " else "verified: "
        YLog.info(
            message + result.describeWithoutValues() + " " + diagnostics.describe(),
        )
    }

    private fun logFailure(
        result: LegacyPreferenceMigrationResult,
        diagnostics: MigrationTransferDiagnostics,
        throwable: Throwable,
    ) {
        YLog.error(
            "Legacy Yuki preference migration failed: " +
                    result.describeWithoutValues() + " " + diagnostics.describe(),
            throwable,
        )
    }

    private fun LegacyPreferenceMigrationResult.describeWithoutValues(): String =
        "path=$sourcePath sourceCount=$sourceCount transferCount=$transferCount " +
                "blobCount=$omittedDerivedCount blobBytes=$omittedDerivedBytes " +
                "remoteBeforeCount=$remoteBeforeCount writtenCount=$writtenCount " +
                "remoteAfterCount=$remoteAfterCount verified=$verified"
}

/**
 * 监听 service 远程偏好代际，在后台线程触发迁移。
 *
 * 每个 service 代际最多尝试一次；失败不会写完成状态，后续新代际或应用重启仍可再次尝试。
 */
internal class LegacyPreferenceMigrationCoordinator(
    private val context: Context,
    source: LegacyPreferenceSource = RootLegacyPreferenceSource(context),
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reareye-yuki-preference-migration").apply { isDaemon = true }
    },
) {
    private val migrator = LegacyPreferenceMigrator(source)
    private val started = AtomicBoolean(false)
    private val generationLock = Any()
    private var lastScheduledGeneration = Long.MIN_VALUE

    /** 注册一次 service 代际观察。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            XposedModuleStatus.observeRemotePreferences(::onRemotePreferencesGeneration)
        }.onFailure {
            YLog.error("Unable to observe remote preference generations for legacy migration", it)
        }
    }

    /** 供 service 观察回调和行为测试调用。 */
    internal fun onRemotePreferencesGeneration(generation: Long) {
        if (generation <= 0L) return
        synchronized(generationLock) {
            if (generation <= lastScheduledGeneration) return
            lastScheduledGeneration = generation
        }
        runCatching {
            executor.execute {
                runCatching {
                    migrator.migrate(context.getPrefsManager())
                }.onFailure {
                    YLog.error(
                        "Legacy Yuki preference migration task failed: generation=$generation",
                        it,
                    )
                }
            }
        }.onFailure {
            YLog.error(
                "Unable to schedule legacy Yuki preference migration: generation=$generation",
                it,
            )
        }
    }
}
