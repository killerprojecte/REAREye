package hk.uwu.reareye.utils

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
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** No root shell, guessed UID, permission widening, delete-before-rename, or copy fallback. */
internal class AndroidSharedRuntimeFileOperations : SharedRuntimeFileStore.FileOperations {
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

    override fun preserveMetadata(temp: File, metadata: SharedRuntimeFileStore.Metadata) {
        val fd = Os.open(temp.path, OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        withDescriptor(fd) {
            val output = FileOutputStream(fd)
            val current = Os.fstat(fd)
            requireRegular(current)
            checkStagedIdentity(temp, current)
            // A non-privileged hook will normally be unable to transfer ownership. That is
            // an error, NOT permission to replace the system-owned original with our inode.
            if (current.st_uid != metadata.uid || current.st_gid != metadata.gid) {
                Os.fchown(fd, metadata.uid, metadata.gid)
            }
            Os.fchmod(fd, metadata.mode)
            if (securityLabel(temp) != metadata.securityLabel) {
                throw IOException("Cannot preserve shared file SELinux label")
            }
            if (metadataOf(Os.fstat(fd), securityLabel(temp)) != metadata) {
                throw IOException("Cannot preserve shared file ownership or permissions")
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
