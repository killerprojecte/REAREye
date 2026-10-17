package hk.uwu.reareye.agent.context

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContextBudgetTest {
    @Test
    fun estimatorRetainsLegacyMultilingualBehavior() {
        assertEquals(0, TokenBudgetEstimator.estimateTokens(""))
        assertEquals(1, TokenBudgetEstimator.estimateTokens("abc"))
        assertEquals(2, TokenBudgetEstimator.estimateTokens("abcd"))
        assertEquals(3, TokenBudgetEstimator.estimateTokens("中文a"))
    }

    @Test
    fun opaqueReasoningAndBinaryAttachmentsAreNotEstimatedAsEmptyText() {
        val small = listOf(ChatMessage(MessageRole.ASSISTANT))
        val native = KoogTranscriptCodec.fromNative(
            listOf(
                Message.Assistant(
                    listOf(
                        MessagePart.Reasoning(emptyList(), encrypted = "cipher".repeat(2000)),
                        MessagePart.Attachment(
                            AttachmentSource.Image(
                                AttachmentContent.Binary.Base64(
                                    "aW1hZ2U=".repeat(1000)
                                ), "png"
                            )
                        ),
                    ), ResponseMetaInfo.Empty
                )
            )
        )
        assertNull(native.single().content)
        assertTrue(
            ContextBudget.estimateInput(
                "",
                emptyList(),
                native
            ) > ContextBudget.estimateInput("", emptyList(), small) + 4000
        )
        assertTrue(
            ContextBudgetRequest(
                native,
                "",
                emptyList(),
                contextWindowTokens = 4096
            ).needsCompaction
        )
    }

    @Test
    fun budgetingRejectsStaleNativeProjectionBeforeCompaction() {
        val native =
            KoogTranscriptCodec.fromNative(listOf(Message.User("real", RequestMetaInfo.Empty)))
                .single()
        try {
            ContextBudget.estimateInput("", emptyList(), listOf(native.copy(content = "edited")))
            fail("Stale projection must not become the basis of compaction")
        } catch (_: IllegalArgumentException) {
        }
    }
}
