package hk.uwu.reareye.agent.provider

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult

/**
 * Incremental reader for `text/event-stream` payloads.
 *
 * Handles the three things that bite in practice:
 *  - CRLF, LF and CR line endings;
 *  - a multi-byte UTF-8 character split across two reads, carried over via the decoder's
 *    underflow path rather than being replaced with U+FFFD;
 *  - comment lines and other SSE fields, which are skipped.
 *
 * Construct with [of] to read from an Okio source or a plain InputStream, or with the internal
 * lambda-taking constructor in tests.
 */
class SseReader internal constructor(
    private val read: (ByteArray) -> Int,
) {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)

    private val pending = StringBuilder()
    private var eof = false

    /** Bytes of a multi-byte character that arrived split across reads, kept for next time. */
    private var leftover: ByteArray = ByteArray(0)

    private val readBuffer = ByteArray(8192)
    private val byteBuffer = ByteBuffer.allocate(readBuffer.size + 16)
    private val charBuffer = CharBuffer.allocate(readBuffer.size + 16)

    /**
     * Return the next data event, or null at end of stream.
     *
     * Lines that carry no data — the blank line that terminates an event, comments, other SSE
     * fields — are consumed and skipped. Only true exhaustion yields null.
     */
    fun next(): SseEvent? {
        while (true) {
            val newline = indexOfLineBreak(pending)
            if (newline >= 0) {
                handleLine(takeLine(newline))?.let { return it }
                continue
            }
            if (eof) {
                if (pending.isEmpty()) return null
                val line = pending.toString()
                pending.setLength(0)
                handleLine(line)?.let { return it }
                return null
            }
            if (!pump()) {
                eof = true
                decode(bytes = ByteArray(0), endOfInput = true)
            }
        }
    }

    /** Read every event until the stream ends. */
    fun readAll(): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        while (true) out += next() ?: break
        return out
    }

    private fun pump(): Boolean {
        val count = read(readBuffer)
        if (count <= 0) return false
        decode(readBuffer.copyOf(count), endOfInput = false)
        return true
    }

    private fun decode(bytes: ByteArray, endOfInput: Boolean) {
        val input = ByteArray(leftover.size + bytes.size)
        leftover.copyInto(input)
        bytes.copyInto(input, leftover.size)

        byteBuffer.clear()
        byteBuffer.put(input)
        byteBuffer.flip()
        charBuffer.clear()

        val result = decoder.decode(byteBuffer, charBuffer, endOfInput)
        if (result.isError) {
            // Malformed sequences are already replaced by the decoder; drop them and move on.
            byteBuffer.position(byteBuffer.limit())
        }
        charBuffer.flip()
        if (charBuffer.hasRemaining()) pending.append(charBuffer)

        // Whatever the decoder could not consume is the head of a character split across reads.
        leftover = if (byteBuffer.hasRemaining()) {
            ByteArray(byteBuffer.remaining()).also { byteBuffer.get(it) }
        } else {
            ByteArray(0)
        }

        if (endOfInput && leftover.isNotEmpty()) {
            // Trailing garbage with no more input; surface it as a replacement rather than losing it.
            pending.append('\uFFFD')
            leftover = ByteArray(0)
        }
    }

    /** Index of the line break ending the first line, or -1 when incomplete. */
    private fun indexOfLineBreak(buffer: StringBuilder): Int {
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c == '\n' || c == '\r') return i
        }
        return -1
    }

    private fun takeLine(index: Int): String {
        val breakWidth =
            if (pending[index] == '\r' && index + 1 < pending.length && pending[index + 1] == '\n') 2
            else 1
        val line = pending.substring(0, index)
        pending.delete(0, index + breakWidth)
        return line
    }

    private fun handleLine(line: String): SseEvent? {
        if (line.isEmpty()) return null
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        if (field != "data") return null
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        return SseEvent(value)
    }

    companion object {
        fun of(source: okio.BufferedSource): SseReader = SseReader { out ->
            val count = source.read(out, 0, out.size)
            if (count <= 0) -1 else count
        }

        fun of(input: java.io.InputStream): SseReader = SseReader { out -> input.read(out) }
    }
}

/** One `data:` payload, exactly as received. */
@JvmInline
value class SseEvent(val data: String)
