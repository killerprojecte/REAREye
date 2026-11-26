package hk.uwu.reareye.agent.data

import hk.uwu.reareye.agent.model.WorkspaceAttachment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Locale
import java.util.UUID

internal class WorkspaceFileAccess(sharedIds: Set<String>?, val ownerId: String?) {
    private val shared = sharedIds?.toSet()

    init {
        require((shared == null && ownerId == null) || (shared != null && isValidOwner(ownerId))) {
            "A child workspace requires both an explicit share set and a unique owner ID."
        }
    }

    fun allows(id: String, owner: String?): Boolean = shared == null || id in shared || ownerId == owner

    companion object {
        val Main = WorkspaceFileAccess(null, null)

        fun isValidOwner(owner: String?): Boolean = owner != null && owner.length <= 128 && owner.isNotBlank()
    }
}

/** Blocking storage; tool execution dispatches to IO. The session lock also guards transcript deletion/locking. */
internal class SessionWorkFiles(
    private val appFiles: File,
    private val lock: Any,
    private val sessionExists: (String) -> Boolean,
    private val requireOpenSession: (String) -> Unit,
) {
    @Serializable
    private data class Record(val ref: WorkspaceAttachment, val ownerId: String?)

    fun list(sessionId: String, access: WorkspaceFileAccess): List<WorkspaceAttachment> = synchronized(lock) {
        if (!sessionExists(sessionId)) return@synchronized emptyList()
        children(directory(sessionId)).sortedBy { it.fileName.toString() }.mapNotNull { path ->
            val id = path.fileName.toString()
            if (!ID.matches(id)) return@mapNotNull null
            val record = metadata(path, id) ?: return@mapNotNull null
            record.ref.takeIf { access.allows(id, record.ownerId) }
        }
    }

    fun readText(sessionId: String, access: WorkspaceFileAccess, id: String, offset: Int): WorkspaceText = synchronized(lock) {
        if (!sessionExists(sessionId)) throw missingFile()
        val path = file(sessionId, id)
        withRecord(path, id) { record, input ->
            if (!access.allows(id, record.ownerId)) throw missingFile()
            require(record.ref.mime in SessionWorkspace.TEXT_TYPES && record.ref.size <= MAX_TEXT_BYTES) {
                "仅可读取不超过 256 KiB 的 UTF-8 工作文件。"
            }
            val bytes = ByteArray(record.ref.size.toInt()).also(input::readFully)
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            require('\u0000' !in text) { "工作文件不是纯文本。" }
            workspaceTextPage(text, offset)
        } ?: throw missingFile()
    }

    fun createText(sessionId: String, access: WorkspaceFileAccess, name: String, text: String): WorkspaceAttachment {
        val bytes = encodeText(text)
        val normalizedName = validateName(name)
        val mime = TEXT_MIME[normalizedName.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "text/plain"
        return create(sessionId, access, normalizedName, mime, bytes.size.toLong(), bytes::inputStream)
    }

    fun copyAttachment(
        sessionId: String, access: WorkspaceFileAccess, ref: WorkspaceAttachment, name: String?, open: () -> InputStream,
    ): WorkspaceAttachment = create(
        sessionId, access, name?.let(::validateName) ?: ref.name.takeIf(::isValidName) ?: "附件副本", ref.mime, ref.size, open,
    )

    fun updateText(sessionId: String, access: WorkspaceFileAccess, id: String, text: String): WorkspaceAttachment {
        val bytes = encodeText(text)
        return synchronized(lock) {
            requireOpenSession(sessionId)
            val path = file(sessionId, id)
            val record = visibleRecord(path, id, access)
            require(record.ref.mime in SessionWorkspace.TEXT_TYPES) { "不能用文本覆盖非文本工作文件。" }
            val updated = record.copy(ref = record.ref.copy(size = bytes.size.toLong()))
            writeRecord(path, updated, replacing = true, open = bytes::inputStream)
            updated.ref
        }
    }

    fun delete(sessionId: String, access: WorkspaceFileAccess, id: String): WorkspaceAttachment = synchronized(lock) {
        requireOpenSession(sessionId)
        val path = file(sessionId, id)
        val record = visibleRecord(path, id, access)
        Files.delete(path)
        record.ref
    }

    fun deleteSession(sessionId: String): Unit = synchronized(lock) {
        require(SessionWorkspace.SESSION_ID.matches(sessionId)) { "Invalid session ID" }
        val root = root()
        if (Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
            deleteTree(File(checkedDirectory(root), sessionId).toPath())
        }
    }

    fun deleteAll() = synchronized(lock) { deleteTree(root().toPath()) }

    fun recover() = synchronized(lock) {
        val root = root()
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
            deleteTree(root.toPath())
            return@synchronized
        }
        for (entry in children(checkedDirectory(root))) {
            val id = entry.fileName.toString()
            if (!SessionWorkspace.SESSION_ID.matches(id) || !Files.isDirectory(entry, NOFOLLOW_LINKS) || !sessionExists(id)) {
                deleteTree(entry)
            } else {
                children(checkedDirectory(entry.toFile())).filter { it.fileName.toString().startsWith(TEMP_PREFIX) }
                    .forEach(::deleteTree)
            }
        }
    }

    private fun create(
        sessionId: String, access: WorkspaceFileAccess, name: String, mime: String, size: Long, open: () -> InputStream,
    ): WorkspaceAttachment = synchronized(lock) {
        requireOpenSession(sessionId)
        require(size in 0..MAX_FILE_BYTES) { "工作文件超过大小限制。" }
        val dir = directory(sessionId)
        Files.createDirectories(dir.toPath())
        var id: String
        var path: Path
        do {
            id = "work-${UUID.randomUUID()}"
            path = dir.toPath().resolve(id)
        } while (Files.exists(path, NOFOLLOW_LINKS))
        val ref = WorkspaceAttachment(id, name, mime, size)
        writeRecord(path, Record(ref, access.ownerId), replacing = false, open = open)
        ref
    }

    /** Metadata and content are one record so a reader never observes a half-updated file. */
    private fun writeRecord(path: Path, record: Record, replacing: Boolean, open: () -> InputStream) {
        val metadata = Json.encodeToString(record).encodeToByteArray()
        require(metadata.size in 1..MAX_METADATA_BYTES) { "工作文件信息超过大小限制。" }
        val directory = path.parent
        val entries = children(directory.toFile()).filter { ID.matches(it.fileName.toString()) && it != path }
        require(entries.size < MAX_FILES) { "此会话的工作文件数量已达上限。" }
        var used = Int.SIZE_BYTES + metadata.size + record.ref.size
        require(used <= MAX_SESSION_BYTES) { "此会话的工作文件存储空间已满。" }
        for (entry in entries) {
            if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) continue
            val bytes = Files.size(entry)
            require(bytes <= MAX_SESSION_BYTES - used) { "此会话的工作文件存储空间已满。" }
            used += bytes
        }
        val staged = Files.createTempFile(directory, TEMP_PREFIX, ".tmp")
        try {
            FileChannel.open(staged, WRITE, NOFOLLOW_LINKS).use { channel ->
                val output = DataOutputStream(Channels.newOutputStream(channel))
                output.writeInt(metadata.size)
                output.write(metadata)
                open().use { copyContent(it, output, record.ref.size) }
                channel.force(true)
            }
            if (replacing) Files.move(staged, path, ATOMIC_MOVE, REPLACE_EXISTING)
            else Files.move(staged, path, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun copyContent(input: InputStream, output: DataOutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("工作文件内容不完整。")
            output.write(buffer, 0, count)
            remaining -= count
        }
        if (input.read() != -1) throw IOException("工作文件大小已变化。")
    }

    private fun visibleRecord(path: Path, id: String, access: WorkspaceFileAccess): Record =
        metadata(path, id)?.takeIf { access.allows(id, it.ownerId) } ?: throw missingFile()

    private fun missingFile() = IOException("找不到此会话的工作文件。")

    private fun metadata(path: Path, id: String): Record? = try {
        withRecord(path, id) { record, _ -> record }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun <T> withRecord(path: Path, id: String, use: (Record, DataInputStream) -> T): T? {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val input = DataInputStream(Channels.newInputStream(channel))
            val length = input.readInt()
            if (length !in 1..MAX_METADATA_BYTES) return null
            val bytes = ByteArray(length).also(input::readFully)
            val record = try {
                Json.decodeFromString<Record>(bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (_: IllegalArgumentException) {
                return null
            }
            val ref = record.ref
            if (ref.id != id || !isValidName(ref.name) || ref.size !in 0..MAX_FILE_BYTES ||
                channel.size() != Int.SIZE_BYTES + length + ref.size ||
                (record.ownerId != null && !WorkspaceFileAccess.isValidOwner(record.ownerId))
            ) return null
            return use(record, input)
        }
    }

    private fun file(sessionId: String, id: String): Path {
        require(ID.matches(id)) { "需要有效的工作文件 ID。" }
        return directory(sessionId).toPath().resolve(id)
    }

    private fun directory(sessionId: String): File {
        require(SessionWorkspace.SESSION_ID.matches(sessionId)) { "Invalid session ID" }
        return checkedDirectory(File(checkedDirectory(root()), sessionId))
    }

    private fun root(): File = File(checkedDirectory(File(appFiles, "agent")), "workspace-files")

    private fun checkedDirectory(dir: File): File = dir.also {
        val path = it.toPath()
        require(it.canonicalFile == it.absoluteFile && !Files.isSymbolicLink(path) &&
            (!Files.exists(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS))) {
            "工作文件目录无效。"
        }
    }

    private fun children(directory: File): List<Path> {
        val path = directory.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return emptyList()
        return Files.newDirectoryStream(path).use { it.toList() }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun validateName(name: String): String = name.trim().also {
        require(isValidName(it)) { "需要 1–120 个字符的文件名，不能使用路径或控制字符。" }
    }

    private fun isValidName(name: String): Boolean = name.length in 1..120 && name.isNotBlank() &&
        name != "." && name != ".." && name.none {
            it == '/' || it == '\\' || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }

    private fun encodeText(text: String): ByteArray {
        require(text.length <= MAX_TEXT_BYTES && '\u0000' !in text) { "需要不超过 256 KiB 的 UTF-8 文本。" }
        return text.encodeToByteArray(throwOnInvalidSequence = true).also {
            require(it.size <= MAX_TEXT_BYTES) { "工作文件超过 256 KiB 文本限制。" }
        }
    }

    companion object {
        internal const val MAX_FILES = 32
        private const val MAX_SESSION_BYTES = 64L * 1024 * 1024
        // Copies may come from large image attachments; the work-file quota still applies to every record.
        private const val MAX_FILE_BYTES = MAX_SESSION_BYTES
        private const val MAX_METADATA_BYTES = 4096
        private const val TEMP_PREFIX = ".write-"
        private const val MAX_TEXT_BYTES = SessionWorkspace.MAX_READABLE_TEXT_BYTES
        private val ID = Regex("work-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val TEXT_MIME = mapOf(
            "md" to "text/markdown", "csv" to "text/csv", "html" to "text/html", "xml" to "application/xml",
            "json" to "application/json", "bbmodel" to "application/json", "yaml" to "application/yaml", "yml" to "application/yaml",
        )
    }
}

internal fun workspaceTextPage(text: String, offset: Int): WorkspaceText {
    require(offset in 0..text.length && (offset == text.length || !text[offset].isLowSurrogate())) { "无效的文本偏移。" }
    var end = minOf(offset + 16_000, text.length)
    if (end < text.length && text[end].isLowSurrogate()) end--
    return WorkspaceText(text.substring(offset, end), end.takeIf { it < text.length })
}
