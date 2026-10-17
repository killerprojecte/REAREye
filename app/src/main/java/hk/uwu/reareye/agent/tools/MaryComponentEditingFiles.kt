package hk.uwu.reareye.agent.tools

import hk.uwu.reareye.agent.htmlwidget.WidgetHtmlValidator
import hk.uwu.reareye.agent.luascript.LuaScriptValidator
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** No extraction or execution. Limits apply to actual bytes, not trusted ZIP size headers. */
internal object MaryComponentEditingFiles {
    const val MAX_PACKAGE_BYTES = 16 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val MAX_ENTRIES = 512
    private val extensions = setOf("html", "htm", "xml", "json", "lua")

    class Failure(val code: String, message: String) : IllegalArgumentException(message)
    data class Entry(val name: String, val bytes: ByteArray, val metadata: ZipEntry? = null) {
        val directory get() = name.endsWith('/')
        val editable
            get() = !directory && name.substringAfterLast('.', "")
                .lowercase(Locale.ROOT) in extensions
    }

    data class Package(
        val zipped: Boolean,
        val sha256: String,
        val entries: List<Entry>,
        val comment: String? = null
    ) {
        fun entry(path: String): Entry {
            validPath(path)
            return entries.singleOrNull { it.name == path && !it.directory }
                ?: fail("entry_not_found", "Select an existing file from entries.")
        }

        fun scriptMap(): Map<String, String> =
            installedScriptMap().filterKeys { it.endsWith(".lua", true) }

        fun installedScriptMap(): Map<String, String> {
            val result = linkedMapOf<String, String>()
            entries.filter { !it.directory }.forEach { entry ->
                // RearStoreRepository.installComponentScripts strips through the first scripts/.
                val index = entry.name.indexOf("scripts/")
                if (index >= 0) {
                    val relative = entry.name.substring(index + "scripts/".length)
                    validPath(relative)
                    if (result.put(relative, entry.name) != null) fail(
                        "ambiguous_script",
                        "Multiple source entries map to one installed script."
                    )
                }
            }
            result.keys.forEach { path ->
                if (result.keys.any { it.startsWith("$path/") }) fail(
                    "ambiguous_script",
                    "An installed script file shadows a directory."
                )
            }
            return result
        }
    }

    /** Shared by the update transaction: every installed resource, not only Lua, needs a refresh. */
    fun installedScriptOwner(pack: Package, path: String, storeWidgetId: String?): String? {
        val installed = pack.installedScriptMap() // Reject ambiguous mappings before persistence.
        return storeWidgetId?.trim()?.replace(Regex("[^A-Za-z0-9_-]"), "_")
            ?.takeIf { it.isNotBlank() && path in installed.values }
    }

    fun fail(code: String, message: String): Nothing = throw Failure(code, message)
    fun requireWritable(renameable: Boolean) {
        if (!renameable) fail("component_locked", "renameable=false components cannot be edited.")
    }

    fun validPath(path: String) {
        if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.contains('\\') || path.contains(
                ':'
            ) ||
            path.any { it.code < 32 || it.code == 127 } || path.removeSuffix("/").split('/')
                .any { it.isBlank() || it == "." || it == ".." }
        ) fail(
            "invalid_path",
            "Only exact package-relative paths are accepted; no absolute paths or traversal."
        )
    }

    fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun bounded(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size().toLong() + count > max) fail(
                "size_limit",
                "File or expanded archive exceeds its byte limit."
            )
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun read(file: File): Package {
        if (!file.isFile) fail(
            "source_unavailable",
            "Registered component source is not a readable file."
        )
        val raw = file.inputStream().use { bounded(it, MAX_PACKAGE_BYTES) }
        val zip = file.extension.equals(
            "zip",
            true
        ) || (raw.size >= 2 && raw[0] == 80.toByte() && raw[1] == 75.toByte())
        if (!zip) {
            validPath(file.name)
            return Package(
                false,
                sha(raw),
                listOf(Entry("template.${file.extension.lowercase(Locale.ROOT)}", raw))
            )
        }
        val entries = mutableListOf<Entry>()
        val seen = hashSetOf<String>()
        var total = 0
        var comment: String?
        ZipFile(file).use { archive ->
            comment = archive.comment
            val iterator = archive.entries()
            while (iterator.hasMoreElements()) {
                if (entries.size >= MAX_ENTRIES) fail(
                    "size_limit",
                    "Archive exceeds $MAX_ENTRIES entries."
                )
                val item = iterator.nextElement()
                validPath(item.name)
                val key = item.name.removeSuffix("/").lowercase(Locale.ROOT)
                if (!seen.add(key)) fail(
                    "duplicate_entry",
                    "Archive contains duplicate or ambiguous entry names."
                )
                val bytes = archive.getInputStream(item)
                    .use { bounded(it, minOf(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total)) }
                total += bytes.size
                if (item.isDirectory && bytes.isNotEmpty()) fail(
                    "invalid_archive",
                    "Directory entries cannot contain data."
                )
                if (item.crc >= 0 && CRC32().apply { update(bytes) }.value != item.crc) fail(
                    "invalid_archive",
                    "ZIP CRC mismatch."
                )
                entries += Entry(item.name, bytes, ZipEntry(item))
            }
        }
        // File/directory prefix collisions are also ambiguous to downstream extractors.
        entries.filter { !it.directory }.forEach { fileEntry ->
            val prefix = fileEntry.name.lowercase(Locale.ROOT) + "/"
            if (entries.any {
                    it.name.lowercase(Locale.ROOT).startsWith(prefix)
                }) fail("duplicate_entry", "Archive file shadows a directory.")
        }
        if (file.inputStream()
                .use { sha(bounded(it, MAX_PACKAGE_BYTES)) } != sha(raw)
        ) fail("conflict", "Registered source changed while being read; retry from the package.")
        return Package(true, sha(raw), entries, comment)
    }

    fun text(entry: Entry): String {
        if (!entry.editable) fail(
            "unsupported_format",
            "Only existing HTML/XML/JSON/Lua text entries may be edited."
        )
        if (entry.bytes.size > MAX_TEXT_BYTES) fail(
            "size_limit",
            "Text exceeds $MAX_TEXT_BYTES UTF-8 bytes."
        )
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(entry.bytes))
            .toString()
    }

    fun validate(path: String, source: String, baseWidth: Int = 904, baseHeight: Int = 572) {
        if (!Charsets.UTF_8.newEncoder().canEncode(source)) fail(
            "invalid_text",
            "Source contains malformed Unicode surrogates; lossless UTF-8 is required."
        )
        if (source.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) fail(
            "size_limit",
            "Replacement text exceeds $MAX_TEXT_BYTES bytes."
        )
        if (source.contains('\u0000')) fail("invalid_text", "NUL is not allowed in text.")
        when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html", "htm" -> {
                if (baseWidth <= baseHeight || baseHeight <= 0) fail(
                    "invalid_dimensions",
                    "HTML requires positive landscape dimensions."
                )
                val result = WidgetHtmlValidator.validate(source, baseWidth, baseHeight)
                if (!result.passed) fail("format_validation_failed", result.report())
            }

            "xml" -> {
                if (Regex(
                        "<!\\s*(DOCTYPE|ENTITY)",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(source)
                ) fail("unsafe_xml", "DTD and entities are forbidden.")
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false
                )
                factory.isExpandEntityReferences = false
                factory.newDocumentBuilder().apply {
                    setEntityResolver { _, _ ->
                        fail(
                            "unsafe_xml",
                            "External entities are forbidden."
                        )
                    }
                }
                    .parse(source.byteInputStream())
            }

            "json" -> Json.parseToJsonElement(source)
            "lua" -> {
                // The existing validator is heuristic, not a Lua parser. Never load or execute Lua.
                // Helpers are not descriptor entries and may legitimately have no return/providers.
                if (source.isBlank()) fail(
                    "format_validation_failed",
                    "Lua source must not be blank."
                )
                val relative = path.substringAfter("scripts/", path)
                if (relative == "main.lua" || path == "main.lua") {
                    val result = LuaScriptValidator.validate(source)
                    if (!result.passed) fail("format_validation_failed", result.report())
                }
            }

            else -> fail("unsupported_format", "Only HTML/XML/JSON/Lua are supported.")
        }
    }

    fun replacement(
        pack: Package,
        path: String,
        source: String,
        expectedSha256: String,
        baseWidth: Int = 904,
        baseHeight: Int = 572
    ): ByteArray {
        val entry = pack.entry(path)
        text(entry)
        if (!Regex("[a-fA-F0-9]{64}").matches(expectedSha256)) fail(
            "invalid_hash",
            "expected_sha256 must be a full SHA-256 from read_component/read_script."
        )
        if (!sha(entry.bytes).equals(expectedSha256, true)) fail(
            "conflict",
            "Source SHA-256 changed; read the original package again before retrying."
        )
        validate(path, source, baseWidth, baseHeight)
        val replacement = source.toByteArray(Charsets.UTF_8)
        if (!pack.zipped) return replacement
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { archive ->
            pack.comment?.let(archive::setComment)
            pack.entries.forEach { old ->
                val bytes = if (old.name == path) replacement else old.bytes
                val item = old.metadata?.let { ZipEntry(it) } ?: ZipEntry(old.name)
                if (item.method == ZipEntry.STORED) {
                    item.size = bytes.size.toLong()
                    item.compressedSize = bytes.size.toLong()
                    item.crc = CRC32().apply { update(bytes) }.value
                } else {
                    item.compressedSize = -1
                    item.size = bytes.size.toLong()
                    item.crc = CRC32().apply { update(bytes) }.value
                }
                archive.putNextEntry(item)
                archive.write(bytes)
                archive.closeEntry()
            }
        }
        val result = out.toByteArray()
        if (result.size > MAX_PACKAGE_BYTES) fail(
            "size_limit",
            "Updated archive exceeds package size limit."
        )
        return result
    }
}
