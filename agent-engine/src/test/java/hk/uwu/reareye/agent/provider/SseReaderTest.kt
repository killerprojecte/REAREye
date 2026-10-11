package hk.uwu.reareye.agent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class SseReaderTest {

    private fun reader(text: String): SseReader =
        SseReader.of(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))

    private fun events(text: String): List<String> = reader(text).readAll().map { it.data }

    @Test
    fun `emits one event per blank-line terminated data field`() {
        val result = events("data: a\n\ndata: b\n\n")
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `handles CRLF line endings`() {
        val result = events("data: a\r\n\r\ndata: b\r\n\r\n")
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `strips exactly one leading space after the colon`() {
        assertEquals(listOf("{\"x\": 1}"), events("data: {\"x\": 1}\n\n"))
        // Two spaces means one is content.
        assertEquals(listOf(" x"), events("data:  x\n\n"))
    }

    @Test
    fun `ignores comments and non-data fields`() {
        val result = events(": keep-alive\nevent: message\nid: 7\ndata: payload\n\n")
        assertEquals(listOf("payload"), result)
    }

    @Test
    fun `emits a trailing event that never got its blank line`() {
        assertEquals(listOf("tail"), events("data: tail"))
    }

    @Test
    fun `skips blank events`() {
        assertEquals(listOf("a"), events("\n\ndata: a\n\n"))
    }

    @Test
    fun `returns null once the stream is exhausted`() {
        val reader = reader("data: only\n\n")
        assertEquals("only", reader.next()?.data)
        assertNull(reader.next())
        assertNull(reader.next())
    }

    /**
     * A multi-byte character delivered across two reads must not be mangled. This is the failure
     * that silently corrupts CJK text in streaming responses.
     */
    @Test
    fun `decodes a multi-byte character split across reads`() {
        val bytes = "data: 你好\n\n".toByteArray(StandardCharsets.UTF_8)
        // "你" is E4 BD A0; cut after its first byte so the decoder must carry the remainder.
        val cut = bytes.indexOfFirst { it == 0xE4.toByte() } + 1
        var offset = 0
        val chunks = listOf(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size))
        val reader = SseReader { out ->
            if (offset >= chunks.size) return@SseReader -1
            val chunk = chunks[offset++]
            chunk.copyInto(out)
            chunk.size
        }
        assertEquals("你好", reader.next()?.data)
    }

    @Test
    fun `keeps a long payload intact`() {
        val payload = "x".repeat(50_000)
        assertEquals(listOf(payload), events("data: $payload\n\n"))
    }

    @Test
    fun `passes the done sentinel through verbatim`() {
        assertEquals(listOf("[DONE]"), events("data: [DONE]\n\n"))
    }

    @Test
    fun `handles many events in one buffer`() {
        val text = buildString {
            repeat(200) { append("data: e$it\n\n") }
        }
        val result = events(text)
        assertEquals(200, result.size)
        assertEquals("e0", result.first())
        assertEquals("e199", result.last())
    }
}
