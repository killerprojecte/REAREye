package hk.uwu.reareye.agent.catalog

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val MAX_CATALOG_SNAPSHOT_BYTES = 1024 * 1024

internal interface ReasoningCatalogCache {
    fun read(sourceId: String): ByteArray?
    fun write(sourceId: String, data: ByteArray)
}

internal class FileReasoningCatalogCache(private val directory: File) : ReasoningCatalogCache {
    override fun read(sourceId: String): ByteArray? {
        val file = fileFor(sourceId)
        if (!file.exists()) return null
        return file.inputStream().use { it.readCatalogBytes(MAX_CATALOG_SNAPSHOT_BYTES) }
    }

    override fun write(sourceId: String, data: ByteArray) {
        require(data.size <= MAX_CATALOG_SNAPSHOT_BYTES)
        val target = fileFor(sourceId).toPath()
        val parent = directory.toPath()
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "$sourceId-", ".tmp")
        try {
            temporary.toFile().outputStream().use {
                it.write(data)
                it.fd.sync()
            }
            // Same-directory atomic replacement. Failure leaves the previous snapshot untouched.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun fileFor(sourceId: String): File {
        require(isCatalogSourceId(sourceId))
        return File(directory, "$sourceId.json")
    }
}

private val sourceIdPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")

internal fun isCatalogSourceId(value: String): Boolean = sourceIdPattern.matches(value)
