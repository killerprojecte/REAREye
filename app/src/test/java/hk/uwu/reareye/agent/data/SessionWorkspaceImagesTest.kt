package hk.uwu.reareye.agent.data

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.WorkspaceAttachment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.zip.CRC32

// Native Robolectric does not support ImageDecoder's file-descriptor JNI on this host.
// Adapt only the source; the real Android codec still decodes, scales and re-encodes the bytes.
internal fun robolectricWorkspaceStore(root: File): AgentSessionStore =
    AgentSessionStore(root, WorkspaceImagePreparation { file -> ImageDecoder.createSource(file.readBytes()) })

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionWorkspaceImagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val smallPng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
    )

    @Test fun largeOriginalStaysAvailableForHistoryAndPreviewWhileSendUsesTransparentDerivative() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val png = bitmapBytes(3000, 1500, Bitmap.CompressFormat.PNG) {
            eraseColor(Color.TRANSPARENT)
            setPixels(IntArray(1500 * 1500) { Color.argb(127, 255, 0, 0) }, 0, 1500, 1500, 0, 1500, 1500)
        }
        val original = withPngPadding(png, 9 * 1024 * 1024)
        val ref = store.workspace.importFile(session.id, "transparent.png", "image/png", sizeHint = Long.MAX_VALUE) {
            original.inputStream()
        }
        assertEquals(original.size.toLong(), ref.size)
        val user = ChatMessage(MessageRole.USER, "retain original", attachments = listOf(ref))
        store.save(session.copy(messages = listOf(user)))
        val reopened = robolectricWorkspaceStore(temporary.root)
        assertEquals(listOf(ref), reopened.load(session.id)?.messages?.single()?.attachments)
        val preview = reopened.workspace.previewUri(session.id, ref)!!
        assertArrayEquals(original, File(preview.path!!).readBytes())

        val durable = KoogTranscriptCodec.toNative(listOf(user))
        val projected = reopened.workspace.materialize(session.id, durable, allowImages = true)
        val sent = projected.single().images().single()
        assertEquals("image/webp", sent.mimeType)
        assertEquals(listOf(ref), KoogTranscriptCodec.workspaceAttachments(durable.single()))
        assertTrue(durable.single().images().isEmpty())
        val decoded = BitmapFactory.decodeByteArray(sent.bytes, 0, sent.bytes.size)
        try {
            assertEquals(2048, decoded.width)
            assertEquals(1024, decoded.height)
            assertEquals(0, Color.alpha(decoded.getPixel(10, 10)))
            assertEquals(127, Color.alpha(decoded.getPixel(decoded.width - 10, 10)))
        } finally {
            decoded.recycle()
        }
    }

    @Test fun resizedJpegAppliesExifOrientationBeforeEncoding() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val jpeg = bitmapBytes(2600, 520, Bitmap.CompressFormat.JPEG) {
            setPixels(IntArray(width * height) { if (it % width < width / 2) Color.RED else Color.BLUE },
                0, width, 0, 0, width, height)
        }
        val rotated = jpegWithClockwiseOrientation(jpeg)
        val ref = store.workspace.importFile(session.id, "camera.jpeg", "image/jpeg") { rotated.inputStream() }
        val prepared = store.workspace.prepareCurrentImages(session.id, listOf(ref), allowImages = true)
        val sent = prepared.images.getValue(ref)
        assertEquals("image/jpeg", sent.mime)
        val decoded = BitmapFactory.decodeByteArray(sent.bytes, 0, sent.bytes.size)
        try {
            assertEquals(2048, decoded.height)
            assertEquals(409, decoded.width)
            val top = decoded.getPixel(decoded.width / 2, 10)
            val bottom = decoded.getPixel(decoded.width / 2, decoded.height - 10)
            assertTrue(Color.red(top) > 240 && Color.blue(top) < 20)
            assertTrue(Color.blue(bottom) > 240 && Color.red(bottom) < 20)
        } finally {
            decoded.recycle()
        }
    }

    @Test fun wireBudgetPreservesCurrentOriginalsAndChargesActualBase64BeforeHistory() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val source = withPngPadding(smallPng, 1510 * 1024)
        val refs = (0..4).map { i ->
            store.workspace.importFile(session.id, "image-$i.png", "image/png") { source.inputStream() }
        }
        assertTrue(refs.sumOf { it.size } < SessionWorkspace.MAX_REQUEST_IMAGE_BYTES)
        assertTrue(refs.sumOf { workspaceImageWireBytes(it.size, it.mime) } > SessionWorkspace.MAX_REQUEST_IMAGE_BYTES)
        val current = refs.drop(1)
        val prepared = store.workspace.prepareCurrentImages(session.id, current, allowImages = true)
        val durable = KoogTranscriptCodec.toNative(listOf(
            ChatMessage(MessageRole.USER, "old", attachments = refs.take(1)),
            ChatMessage(MessageRole.USER, "new", attachments = current),
            ChatMessage(MessageRole.USER, "follow-up without image"),
        ))
        val projected = store.workspace.materialize(session.id, durable, allowImages = true, preparedCurrentImages = prepared)
        val currentImages = projected[1].images()
        assertEquals(4, currentImages.size)
        currentImages.forEach { assertArrayEquals(source, it.bytes) }
        val history = projected[0].images().single()
        assertTrue(history.bytes.size < 1024)
        assertTrue(projected[2].images().isEmpty())
        val total = projected.flatMap { it.images() }.sumOf {
            val actual = "data:${it.mimeType};base64,".length + Base64.getEncoder().encodeToString(it.bytes).length
            assertEquals(actual.toLong(), workspaceImageWireBytes(it.bytes.size.toLong(), it.mimeType))
            actual.toLong()
        }
        assertTrue(total <= SessionWorkspace.MAX_REQUEST_IMAGE_BYTES)
    }

    @Test fun overBudgetDraftCompressesTheLargestFirstEvenWhenItAppearsBeforeSmallerImages() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val medium = withPngPadding(smallPng, 1910 * 1024)
        val sources = listOf(withPngPadding(smallPng, 2850 * 1024), smallPng, medium, medium)
        val refs = sources.mapIndexed { index, bytes ->
            store.workspace.importFile(session.id, "ordered-$index.png", "image/png") { bytes.inputStream() }
        }
        assertTrue(refs.sumOf { workspaceImageWireBytes(it.size, it.mime) } > SessionWorkspace.MAX_REQUEST_IMAGE_BYTES)
        val prepared = store.workspace.prepareCurrentImages(session.id, refs, allowImages = true)
        assertEquals(refs, prepared.images.keys.toList())
        assertTrue(prepared.images.getValue(refs.first()).bytes.size < 1024)
        refs.indices.drop(1).forEach { index ->
            assertArrayEquals(sources[index], prepared.images.getValue(refs[index]).bytes)
        }
        val reversed = store.workspace.prepareCurrentImages(session.id, refs.reversed(), allowImages = true)
        assertEquals(refs.reversed(), reversed.images.keys.toList())
        refs.forEach { ref -> assertArrayEquals(prepared.images.getValue(ref).bytes, reversed.images.getValue(ref).bytes) }
    }

    @Test fun fillsImageSlotsWithCurrentBeforeRecentHistoryAndNamesNinthCurrentImage() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val refs = (0..8).map { i ->
            store.workspace.importFile(session.id, "file-$i.png", "image/png") { smallPng.inputStream() }
        }
        val error = assertThrows(WorkspaceImagePreparationException::class.java) {
            store.workspace.prepareCurrentImages(session.id, refs, allowImages = true)
        }
        assertEquals(refs.last(), error.attachment)
        assertTrue(error.message.orEmpty().contains(refs.last().name))

        val current = refs.drop(2)
        val durable = KoogTranscriptCodec.toNative(listOf(
            ChatMessage(MessageRole.USER, "oldest", attachments = refs.take(1)),
            ChatMessage(MessageRole.USER, "more recent", attachments = listOf(refs[1])),
            ChatMessage(MessageRole.USER, "current", attachments = current),
        ))
        val projected = store.workspace.materialize(session.id, durable, allowImages = true)
        assertTrue(projected[0].images().isEmpty())
        assertTrue(projected[0].textContent().contains("Image request budget exceeded"))
        assertEquals(1, projected[1].images().size)
        assertEquals(7, projected[2].images().size)
    }

    @Test fun validatesActualFormatDimensionsAndStorageQuotaBeforeCommittingOriginal() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val workspace = store.workspace
        assertThrows(IllegalArgumentException::class.java) {
            workspace.importFile(session.id, "truncated.png", "image/png") { smallPng.copyOf(28).inputStream() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.importFile(session.id, "wrong.jpeg", "image/jpeg") { smallPng.inputStream() }
        }
        val tooWide = bitmapBytes(17_000, 1, Bitmap.CompressFormat.PNG) { eraseColor(Color.WHITE) }
        val dimensions = assertThrows(IllegalArgumentException::class.java) {
            workspace.importFile(session.id, "too-wide.png", "image/png") { tooWide.inputStream() }
        }
        assertTrue(dimensions.message.orEmpty().contains("图片尺寸"))
        val older = workspace.importFile(session.id, "takes-space.png", "image/png") { smallPng.inputStream() }
        val body = File(workspace.previewUri(session.id, older)!!.path!!)
        // Quota is measured from the local file, not the attachment metadata or the external size hint.
        RandomAccessFile(body, "rw").use { it.setLength(64L * 1024 * 1024 - smallPng.size + 1) }
        val quota = assertThrows(IllegalArgumentException::class.java) {
            workspace.importFile(session.id, "no-room.png", "image/png", sizeHint = 1L) { smallPng.inputStream() }
        }
        assertTrue(quota.message.orEmpty().contains("存储空间"))
        assertEquals(listOf(older.id), body.parentFile?.parentFile?.list()?.toList())
    }

    @Test fun currentMissingOrCorruptIsNamedButBrokenHistoryIsSkipped() {
        val store = robolectricWorkspaceStore(temporary.root)
        val session = store.create()
        val workspace = store.workspace
        val bad = workspace.importFile(session.id, "damaged.png", "image/png") { smallPng.inputStream() }
        val good = workspace.importFile(session.id, "intact.png", "image/png") { smallPng.inputStream() }
        File(workspace.previewUri(session.id, bad)!!.path!!).writeBytes(ByteArray(smallPng.size))
        val error = assertThrows(WorkspaceImagePreparationException::class.java) {
            workspace.prepareCurrentImages(session.id, listOf(bad), allowImages = true)
        }
        assertEquals(bad, error.attachment)
        assertTrue(error.message.orEmpty().contains("damaged.png"))
        val durable = KoogTranscriptCodec.toNative(listOf(
            ChatMessage(MessageRole.USER, "history", attachments = listOf(bad)),
            ChatMessage(MessageRole.USER, "current", attachments = listOf(good)),
        ))
        val prepared = workspace.prepareCurrentImages(session.id, listOf(good), allowImages = true)
        val projected = workspace.materialize(session.id, durable, allowImages = true, preparedCurrentImages = prepared)
        assertTrue(projected.first().images().isEmpty())
        assertTrue(projected.first().textContent().contains("Could not read a valid image"))
        assertArrayEquals(smallPng, projected.last().images().single().bytes)
        assertThrows(WorkspaceImagePreparationException::class.java) {
            workspace.materialize(session.id, durable.take(1), allowImages = true, preparedCurrentImages = prepared)
        }
        workspace.discardDraft(session.id, good.id)
        assertFalse(workspace.contains(session.id, good))
        val missing = assertThrows(WorkspaceImagePreparationException::class.java) {
            workspace.materialize(session.id, durable, allowImages = true, preparedCurrentImages = prepared)
        }
        assertEquals(good, missing.attachment)
    }

    private fun Message.images(): List<AttachmentSource.Image> = parts.filterIsInstance<MessagePart.Attachment>()
        .map { it.source as AttachmentSource.Image }
    private val AttachmentSource.Image.bytes: ByteArray get() = (content as AttachmentContent.Binary.Bytes).data

    private fun bitmapBytes(width: Int, height: Int, format: Bitmap.CompressFormat, paint: Bitmap.() -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.paint()
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(format, 92, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun withPngPadding(png: ByteArray, size: Int): ByteArray = ByteArrayOutputStream(png.size + size + 12).use { buffer ->
        val iendOffset = png.size - 12
        val chunk = "reAr".encodeToByteArray() // private ancillary PNG chunk
        val padding = ByteArray(minOf(size, 1024 * 1024))
        DataOutputStream(buffer).use { out ->
            out.write(png, 0, iendOffset)
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, padding.size)
                val crc = CRC32().apply { update(chunk); update(padding, 0, count) }.value.toInt()
                out.writeInt(count)
                out.write(chunk)
                out.write(padding, 0, count)
                out.writeInt(crc)
                remaining -= count
            }
            out.write(png, iendOffset, 12)
        }
        buffer.toByteArray()
    }

    private fun jpegWithClockwiseOrientation(jpeg: ByteArray): ByteArray {
        val app1 = byteArrayOf(
            -1, -31, 0, 34, // JPEG APP1 marker, segment length
            69, 120, 105, 102, 0, 0, // Exif signature
            77, 77, 0, 42, 0, 0, 0, 8, // big-endian TIFF header
            0, 1, 1, 18, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, // one SHORT orientation field: 6
            0, 0, 0, 0,
        )
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }
}
