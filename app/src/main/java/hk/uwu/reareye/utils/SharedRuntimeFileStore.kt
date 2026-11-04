package hk.uwu.reareye.utils

import java.io.File
import java.io.IOException

/**
 * Fail-closed replacement of an existing system-owned shared file.
 *
 * The platform adapter must reject missing files, symlinks and non-regular files and return
 * a stable read. In particular, failed reads must never be represented by an empty document.
 * The caller supplies the format validator so this transaction core remains Android-free.
 *
 * All instances loaded by the same classloader serialize staging and commit with one lock.
 * The last read detects stale snapshots, including updates made by another store instance.
 * It is NOT a filesystem compare-and-swap: a non-cooperating system writer can still race
 * between the final comparison and rename. Full cross-process serialization requires a
 * system-owned writing API/protocol shared by every writer.
 */
internal class SharedRuntimeFileStore(
    private val operations: FileOperations,
    private val validate: (String) -> Unit,
) {
    companion object {
        // Low-frequency shared runtime writes: one lock avoids per-path alias and lock-map
        // lifetime races. This does not coordinate another process or another classloader.
        private val commitLock = Any()
    }

    data class Metadata(val uid: Int, val gid: Int, val mode: Int, val securityLabel: String)

    data class Identity(
        val device: Long,
        val inode: Long,
        val size: Long,
        val modifiedNanos: Long,
        val changedNanos: Long,
    )

    data class FileState(val text: String, val metadata: Metadata, val identity: Identity)

    class Snapshot internal constructor(
        val target: File,
        val state: FileState,
        internal val owner: Any,
    )

    interface FileOperations {
        /** Read an existing regular, non-symlink file consistently, or throw. */
        fun read(target: File): FileState
        /** Exclusively create a unique temporary file in the target's directory. */
        fun createTemp(target: File): File
        fun writeAndSync(temp: File, text: String)
        /** Preserve all supplied metadata, or throw without modifying the target. */
        fun preserveMetadata(temp: File, metadata: Metadata)
        /** Atomic replacement only: never delete the target or fall back to copying. */
        fun replaceAtomically(temp: File, target: File)
        fun deleteTemp(temp: File)
    }

    private val owner = Any()

    @Synchronized
    fun read(target: File): Snapshot {
        val absoluteTarget = target.absoluteFile
        val state = operations.read(absoluteTarget)
        validate(state.text)
        return Snapshot(absoluteTarget, state, owner)
    }

    fun write(snapshot: Snapshot, text: String): Unit = synchronized(commitLock) {
        val temp = stage(snapshot, text)
        try {
            operations.replaceAtomically(temp, snapshot.target)
        } catch (failure: Throwable) {
            cleanupAfterFailure(temp, failure)
            throw failure
        }
        // On success rename consumed the temporary path. Do not report a later cleanup
        // failure as if the committed write failed (and thereby encourage unsafe retries).
    }

    /**
     * Check staging/metadata permissions before the caller modifies associated resources.
     * This does not reserve permissions or make those resource changes transactional: write
     * must still repeat every check because permissions and other writers can change later.
     */
    fun checkWritable(snapshot: Snapshot): Unit = synchronized(commitLock) {
        val temp = stage(snapshot, snapshot.state.text)
        operations.deleteTemp(temp)
    }

    private fun stage(snapshot: Snapshot, text: String): File {
        if (snapshot.owner !== owner) throw IOException("Runtime snapshot belongs to another store")
        validate(text)
        // Reject already-stale snapshots without even creating a temporary file.
        requireUnchanged(snapshot)
        val temp = operations.createTemp(snapshot.target)
        try {
            operations.writeAndSync(temp, text)
            operations.preserveMetadata(temp, snapshot.state.metadata)
            val staged = operations.read(temp)
            if (staged.metadata != snapshot.state.metadata || staged.text != text) {
                throw IOException("Runtime replacement did not preserve content and metadata")
            }
            requireUnchanged(snapshot)
            return temp
        } catch (failure: Throwable) {
            cleanupAfterFailure(temp, failure)
            throw failure
        }
    }

    private fun cleanupAfterFailure(temp: File, failure: Throwable) {
        try {
            operations.deleteTemp(temp)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun requireUnchanged(snapshot: Snapshot) {
        if (operations.read(snapshot.target) != snapshot.state) {
            throw IOException("Runtime file changed; reload before retrying")
        }
    }
}
