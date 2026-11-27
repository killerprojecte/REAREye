package hk.uwu.reareye.agent.data

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import hk.uwu.reareye.agent.model.WorkspaceAttachment
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal class WorkspaceImagePreparationException(
    val attachment: WorkspaceAttachment,
    reason: String,
    cause: Throwable? = null,
) : IOException("图片「${attachment.name}」无法发送：$reason", cause)

/** Created before the draft is committed; only reused by requests from this turn and session. */
internal class WorkspacePreparedImages(
    val sessionId: String,
    val images: Map<WorkspaceAttachment, WorkspaceRequestImage>,
)

internal class WorkspaceRequestImage(val bytes: ByteArray, val mime: String) {
    val wireBytes: Long get() = workspaceImageWireBytes(bytes.size.toLong(), mime)
}

/** Unwrapped Base64 with padding plus the data-URL prefix used by OpenAI-compatible transports. */
internal fun workspaceImageWireBytes(binaryBytes: Long, mime: String): Long =
    ((binaryBytes + 2L) / 3L) * 4L + dataUrlPrefixLength(mime)

private fun dataUrlPrefixLength(mime: String): Int = "data:$mime;base64,".length

internal class WorkspaceImagePreparation(
    private val sourceForFile: (File) -> ImageDecoder.Source = { ImageDecoder.createSource(it) },
) {
    companion object {
        private const val MAX_REQUEST_EDGE = 2048
        private const val MAX_WIRE_IMAGE_BYTES = 4L * 1024 * 1024
        private const val MAX_ORIGINAL_EDGE = 16_384
        private const val MAX_ORIGINAL_PIXELS = 64L * 1024 * 1024
        private const val VALIDATION_EDGE = 256
        private const val MIN_REQUEST_EDGE = 256
        // Cover imports and concurrent primary/child requests without holding the session-store lock.
        private val decodeLock = Any()
    }

    fun validateImport(file: File, mime: String) = withDecodeLock {
        decode(file, mime) { _, _ -> VALIDATION_EDGE }.recycle()
    }

    fun prepare(file: File, ref: WorkspaceAttachment, availableWireBytes: Long): WorkspaceRequestImage? {
        // Avoid decoding a historical image when there is no meaningful space left for it.
        if (availableWireBytes <= dataUrlPrefixLength(ref.mime) + 4) return null
        return withDecodeLock {
            val allowance = minOf(availableWireBytes, MAX_WIRE_IMAGE_BYTES)
            var keepOriginal = false
            var bitmap = decode(file, ref.mime) { width, height ->
                keepOriginal = maxOf(width, height) <= MAX_REQUEST_EDGE &&
                    workspaceImageWireBytes(ref.size, ref.mime) <= allowance
                if (keepOriginal) VALIDATION_EDGE else MAX_REQUEST_EDGE
            }
            try {
                if (keepOriginal) {
                    val bytes = Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                        // Bounded by the encoded allowance above; detect truncation or replacement.
                        ByteArray(ref.size.toInt()).also { bytes ->
                            if (input.readNBytes(bytes, 0, bytes.size) != bytes.size || input.read() != -1) {
                                throw IOException("原图大小已变化。")
                            }
                        }
                    }
                    WorkspaceRequestImage(bytes, ref.mime)
                } else {
                    fun compress(format: Bitmap.CompressFormat, mime: String, quality: Int): WorkspaceRequestImage? {
                        val maxBytes = ((allowance - dataUrlPrefixLength(mime)) / 4L * 3L).toInt()
                        if (maxBytes <= 0) return null
                        val output = BoundedImageOutput(maxBytes)
                        if (!bitmap.compress(format, quality, output)) throw IOException("Android 无法编码图片。")
                        return output.toByteArray()?.let { WorkspaceRequestImage(it, mime) }
                    }
                    while (true) {
                        val lossless = if (bitmap.hasAlpha() || ref.mime == "image/png") {
                            compress(Bitmap.CompressFormat.WEBP_LOSSLESS, "image/webp", 35)
                        } else null
                        val encoded = lossless ?: if (bitmap.hasAlpha()) {
                            compress(Bitmap.CompressFormat.WEBP_LOSSY, "image/webp", 82)
                        } else {
                            compress(Bitmap.CompressFormat.JPEG, "image/jpeg", 86)
                                ?: compress(Bitmap.CompressFormat.JPEG, "image/jpeg", 70)
                        }
                        if (encoded != null) return@withDecodeLock encoded
                        val edge = maxOf(bitmap.width, bitmap.height)
                        if (edge <= MIN_REQUEST_EDGE) break
                        val (width, height) = fit(bitmap.width, bitmap.height, (edge * 3 / 4).coerceAtLeast(MIN_REQUEST_EDGE))
                        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                        bitmap.recycle()
                        bitmap = scaled
                    }
                    null
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun decode(file: File, mime: String, targetEdge: (Int, Int) -> Int): Bitmap = try {
        // File source streams from the checked private copy; never load the full original into a byte array.
        if (file.canonicalFile != file.absoluteFile || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("找不到可读取的原图。")
        }
        ImageDecoder.decodeBitmap(sourceForFile(file)) { decoder, info, _ ->
            require(info.mimeType == mime) { "文件内容与声明的图片类型不符。" }
            val (width, height) = info.size.let { it.width to it.height }
            require(width in 1..MAX_ORIGINAL_EDGE && height in 1..MAX_ORIGINAL_EDGE &&
                width.toLong() * height <= MAX_ORIGINAL_PIXELS) { "图片尺寸过大或无效。" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setOnPartialImageListener { false }
            // ImageDecoder applies EXIF flips/rotation and reports oriented dimensions.
            val (w, h) = fit(width, height, targetEdge(width, height))
            if (w != width || h != height) decoder.setTargetSize(w, h)
        }
    } catch (e: ImageDecoder.DecodeException) {
        throw IOException("文件不完整、已损坏或不是可解码的图片。", e)
    }

    private fun fit(width: Int, height: Int, edge: Int): Pair<Int, Int> {
        val longEdge = maxOf(width, height)
        if (longEdge <= edge) return width to height
        return (width * edge / longEdge).coerceAtLeast(1) to (height * edge / longEdge).coerceAtLeast(1)
    }

    private inline fun <T> withDecodeLock(block: () -> T): T = synchronized(decodeLock) {
        try {
            block()
        } catch (error: OutOfMemoryError) {
            throw IOException("设备内存不足，无法处理这张图片。", error)
        }
    }

    /** Never grow a bitmap encoder buffer beyond the actual binary allowance. */
    private class BoundedImageOutput(private val limit: Int) : OutputStream() {
        private var buffer = ByteArray(minOf(DEFAULT_BUFFER_SIZE, limit))
        private var size = 0
        private var overflowed = false

        override fun write(b: Int) {
            if (accept(1)) buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (accept(len)) {
                b.copyInto(buffer, size, off, off + len)
                size += len
            }
        }

        fun toByteArray(): ByteArray? = if (!overflowed && size > 0) buffer.copyOf(size) else null

        private fun accept(count: Int): Boolean {
            if (overflowed || count > limit - size) {
                overflowed = true
                return false
            }
            val required = size + count
            if (required > buffer.size) buffer = buffer.copyOf(maxOf(required, buffer.size * 2).coerceAtMost(limit))
            return true
        }
    }
}
