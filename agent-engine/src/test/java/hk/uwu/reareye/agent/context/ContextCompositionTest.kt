package hk.uwu.reareye.agent.context

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.NativeMessageEncoding
import hk.uwu.reareye.agent.model.ToolCall
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ContextCompositionTest {
    /** Frozen previous implementation, so changing the shared implementation cannot fake equality. */
    private fun previousEstimate(system: String, tools: List<ToolSpec>, messages: List<ChatMessage>): Int {
        var total = TokenBudgetEstimator.estimateTokens(system).toLong() + 16
        tools.forEach { total += TokenBudgetEstimator.estimateTokens(it.name + it.description + it.parametersSchema()).toLong() + 16 }
        KoogTranscriptCodec.validate(messages)
        messages.forEach { message ->
            message.nativeEncoding?.let {
                total += TokenBudgetEstimator.estimateTokens(it.message.toString()).toLong() + 12
                return@forEach
            }
            total += 12 + TokenBudgetEstimator.estimateTokens(message.content.orEmpty()).toLong()
            total += TokenBudgetEstimator.estimateTokens(message.name.orEmpty() + message.toolCallId.orEmpty())
            message.toolCalls.forEach { total += TokenBudgetEstimator.estimateTokens(it.id + it.name + it.argumentsJson).toLong() + 12 }
            message.protocolItems.forEach { total += TokenBudgetEstimator.estimateTokens(it.toString()).toLong() + 8 }
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    @Test fun mixedPayloadTotalsMatchExistingBudgetExactly() {
        val tools = listOf(ToolSpec("lookup", "Read a record"))
        val messages = listOf(
            ChatMessage(MessageRole.SYSTEM, "Extra system prefix"),
            ChatMessage(MessageRole.USER, "你好, hello!", name = "owner"),
            ChatMessage(MessageRole.ASSISTANT, "Working", toolCalls = listOf(ToolCall("call", "lookup", "{\"id\":1}")),
                protocolItems = listOf(buildJsonObject { put("type", "opaque-reasoning"); put("payload", "signed") })),
            ChatMessage(MessageRole.TOOL, "Result", toolCallId = "call", name = "lookup"),
        )
        val snapshot = ContextBudget.estimateComposition("Be useful", tools, messages)
        assertEquals(previousEstimate("Be useful", tools, messages), snapshot.totalTokens)
        assertEquals(snapshot.totalTokens, ContextBudget.estimateInput("Be useful", tools, messages))
        assertEquals(snapshot.totalTokens.toLong(), snapshot.segments.sumOf { it.tokens.toLong() })
        assertEquals(snapshot.totalTokens.toLong(), snapshot.categories.sumOf { it.tokens.toLong() })
        assertEquals(ContextCategory.entries.toSet(), snapshot.categories.map { it.category }.toSet())
        assertFalse(snapshot.saturated)
        assertTrue(snapshot.segments.filter { it.messageIndex != null }.map { it.messageIndex!! }.zipWithNext().all { it.first <= it.second })
    }

    @Test fun authoritativeNativePayloadIsCountedOnceWithoutInventedSplit() {
        val native = KoogTranscriptCodec.fromNative(listOf(Message.User("Native user text", RequestMetaInfo.Empty)))
        val snapshot = ContextBudget.estimateComposition("", emptyList(), native)
        assertEquals(previousEstimate("", emptyList(), native), snapshot.totalTokens)
        assertEquals(setOf(ContextCategory.NATIVE_PROTOCOL, ContextCategory.FORMATTING), snapshot.categories.map { it.category }.toSet())
        assertFalse(snapshot.categories.any { it.category == ContextCategory.USER })
    }

    @Test fun invalidNativeProjectionStillFailsInsteadOfDisplayingPartialNumbers() {
        val valid = KoogTranscriptCodec.fromNative(listOf(Message.User("Original", RequestMetaInfo.Empty))).single()
        assertThrows(IllegalArgumentException::class.java) {
            ContextBudget.estimateComposition("", emptyList(), listOf(valid.copy(content = "Changed")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextBudget.estimateComposition("", emptyList(), listOf(ChatMessage(MessageRole.USER,
                nativeEncoding = NativeMessageEncoding(message = buildJsonObject { put("bad", true) }))))
        }
    }

    @Test fun emptyPayloadStillShowsRealEstimatorEnvelopeAndFixtureCanBeZero() {
        val snapshot = ContextBudget.estimateComposition("", emptyList(), emptyList())
        assertEquals(16, snapshot.totalTokens)
        assertEquals(listOf(ContextCategoryUsage(ContextCategory.FORMATTING, 16)), snapshot.categories)
        val empty = ContextComposition.fromSegments(listOf(ContextSegment(ContextCategory.USER, 0)))
        assertEquals(0, empty.totalTokens)
        assertTrue(empty.segments.isEmpty())
        assertTrue(empty.categories.isEmpty())
    }

    @Test fun saturationRetainsExactSumsWithoutNegativeOverflow() {
        val snapshot = ContextComposition.fromSegments(listOf(
            ContextSegment(ContextCategory.SYSTEM, Int.MAX_VALUE - 10),
            ContextSegment(ContextCategory.USER, 20),
            ContextSegment(ContextCategory.TOOL_RESULTS, 30),
        ))
        assertEquals(Int.MAX_VALUE, snapshot.totalTokens)
        assertTrue(snapshot.saturated)
        assertEquals(10, snapshot.segments.last().tokens)
        assertEquals(Int.MAX_VALUE.toLong(), snapshot.segments.sumOf { it.tokens.toLong() })
        assertEquals(Int.MAX_VALUE.toLong(), snapshot.categories.sumOf { it.tokens.toLong() })
    }

    @Test fun snapshotDefensivelyCopiesInputAndHasNoPayloadFields() {
        val source = mutableListOf(ContextSegment(ContextCategory.USER, 12, 0))
        val snapshot = ContextComposition.fromSegments(source)
        source.clear()
        assertEquals(12, snapshot.totalTokens)
        assertEquals(1, snapshot.segments.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.segments as MutableList<ContextSegment>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) { ContextSegment(ContextCategory.USER, -1) }
        assertThrows(IllegalArgumentException::class.java) { ContextSegment(ContextCategory.USER, 1, -1) }
    }
}
