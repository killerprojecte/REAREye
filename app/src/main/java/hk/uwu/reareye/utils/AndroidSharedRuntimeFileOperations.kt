package hk.uwu.reareye.utils

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class AndroidSharedRuntimeFileOperations(
    private val preserveWithRoot: (File, StructStat, SharedRuntimeFileStore.FileState) -> Boolean,
) : SharedRuntimeFileStore.FileOperations {
    private val stagedInodes = ConcurrentHashMap<String, Pair<Long, Long>>()

    override fun read(target: File): SharedRuntimeFileStore.FileState {
        val fd = Os.open(target.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        return withDescriptor(fd) {
            val input = FileInputStream(fd)
            val before = Os.fstat(fd)
            requireRegular(before)
            checkStagedIdentity(target, before)
            val label = securityLabel(target)
            val bytes = input.readBytes()
            val after = Os.fstat(fd)
            val afterLabel = securityLabel(target)
            val named = Os.lstat(target.path)
            if (identity(before) != identity(after) || identity(after) != identity(named) ||
                metadataOf(before, label) != metadataOf(named, afterLabel)) {
                throw IOException("Shared file changed while being read: ${target.name}")
            }
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            SharedRuntimeFileStore.FileState(text, metadataOf(after, label), identity(after))
        }
    }

    override fun createTemp(target: File): File {
        val temp = File(target.absoluteFile.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        val fd = Os.open(temp.path, OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180)
        withDescriptor(fd) {
            val stat = Os.fstat(fd)
            stagedInodes[temp.path] = stat.st_dev to stat.st_ino
        }
        return temp
    }

    override fun writeAndSync(temp: File, text: String) {
        val fd = Os.open(temp.path, OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        withDescriptor(fd) {
            val output = FileOutputStream(fd)
            requireRegular(Os.fstat(fd))
            checkStagedIdentity(temp, Os.fstat(fd))
            Os.ftruncate(fd, 0)
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    override fun preserveMetadata(temp: File, original: SharedRuntimeFileStore.FileState) {
        val metadata = original.metadata
        val fd = Os.open(temp.path, OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        withDescriptor(fd) {
            val output = FileOutputStream(fd)
            val current = Os.fstat(fd)
            requireRegular(current)
            checkStagedIdentity(temp, current)
            val localFailure = try {
                val temporaryLabel = securityLabel(temp)
                if (temporaryLabel != metadata.securityLabel) {
                    IOException(
                        "Temporary runtime SELinux label differs from the original " +
                            "(${describeLabel(temporaryLabel)} versus ${describeLabel(metadata.securityLabel)})",
                    )
                } else {
                    if (current.st_uid != metadata.uid || current.st_gid != metadata.gid) {
                        Os.fchown(fd, metadata.uid, metadata.gid)
                    }
                    Os.fchmod(fd, metadata.mode)
                    null
                }
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EACCES && error.errno != OsConstants.EPERM) throw error
                error
            }
            // Keep this descriptor open while the helper checks the inode, so it cannot be recycled.
            if (localFailure != null) {
                try {
                    if (!preserveWithRoot(temp, current, original)) throw localFailure
                } catch (error: Exception) {
                    if (error !== localFailure) error.addSuppressed(localFailure)
                    throw error
                }
            }
            checkStagedIdentity(temp, Os.lstat(temp.path))
            if (metadataOf(Os.fstat(fd), securityLabel(temp)) != metadata) {
                throw IOException("Temporary runtime file did not retain the original ownership, mode and SELinux label")
            }
            output.fd.sync()
        }
    }

    override fun replaceAtomically(temp: File, target: File) {
        // Same-directory rename replaces the directory entry without a missing-file window.
        // This is NOT compare-and-swap against uncooperative system writers.
        checkStagedIdentity(temp, Os.lstat(temp.path))
        Os.rename(temp.path, target.path)
        stagedInodes.remove(temp.path)
    }

    override fun deleteTemp(temp: File) {
        try {
            checkStagedIdentity(temp, Os.lstat(temp.path))
            Os.remove(temp.path)
        } finally {
            stagedInodes.remove(temp.path)
        }
    }

    // Android stream wrappers do not own a descriptor supplied by the caller.
    private inline fun <T> withDescriptor(fd: FileDescriptor, block: () -> T): T {
        var failure: Throwable? = null
        try {
            return block()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                Os.close(fd)
            } catch (closeError: Throwable) {
                if (failure != null) failure.addSuppressed(closeError) else throw closeError
            }
        }
    }

    private fun securityLabel(file: File): String =
        Base64.getEncoder().encodeToString(Os.getxattr(file.path, "security.selinux"))

    private fun describeLabel(encoded: String): String =
        Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8).trimEnd('\u0000')

    private fun requireRegular(stat: StructStat) {
        if (!OsConstants.S_ISREG(stat.st_mode)) throw IOException("Shared file is not a regular file")
    }

    private fun checkStagedIdentity(file: File, stat: StructStat) {
        val expected = stagedInodes[file.path] ?: return
        if (!OsConstants.S_ISREG(stat.st_mode) || expected != (stat.st_dev to stat.st_ino)) {
            throw IOException("Temporary shared file was replaced")
        }
    }

    private fun metadataOf(stat: StructStat, label: String) = SharedRuntimeFileStore.Metadata(
        stat.st_uid, stat.st_gid, stat.st_mode and 0xFFF, label,
    )

    private fun identity(stat: StructStat) = SharedRuntimeFileStore.Identity(
        stat.st_dev, stat.st_ino, stat.st_size,
        stat.st_mtim.tv_sec * 1_000_000_000L + stat.st_mtim.tv_nsec,
        stat.st_ctim.tv_sec * 1_000_000_000L + stat.st_ctim.tv_nsec,
    )
}
