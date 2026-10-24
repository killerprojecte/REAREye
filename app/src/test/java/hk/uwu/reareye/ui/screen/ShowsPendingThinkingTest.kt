package hk.uwu.reareye.ui.screen

import hk.uwu.reareye.agent.ChatEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowsPendingThinkingTest {
    private val user = ChatEntry.UserMessage("u1", "hi")
    private val settled = ChatEntry.AssistantMessage("a1", "done")
    private val answering = ChatEntry.AssistantMessage("a1", "partial", streaming = true)
    private val runningTool = ChatEntry.ToolCall("t1", "Search", "{}", running = true)
    private val settledTool = ChatEntry.ToolCall("t1", "Search", "{}", running = false)

    @Test
    fun aRunningTurnWithNothingToShowYetReportsThatItIsThinking() {
        assertTrue(showsPendingThinking(running = true, reasoning = "", entries = listOf(user)))
        assertTrue(showsPendingThinking(running = true, reasoning = "", entries = emptyList()))
    }

    @Test
    fun theRowIsGivenUpOnceReasoningTextArrives() {
        // Reasoning owns the row from here, so the placeholder must step aside rather than stack.
        assertFalse(
            showsPendingThinking(running = true, reasoning = "let me check", entries = listOf(user))
        )
    }

    @Test
    fun aStreamingAnswerIsItsOwnIndicator() {
        assertFalse(showsPendingThinking(true, "", listOf(user, answering)))
    }

    @Test
    fun aToolThatIsStillRunningIsItsOwnIndicator() {
        assertFalse(showsPendingThinking(true, "", listOf(user, runningTool)))
    }

    @Test
    fun aFinishedRowLeavesTheRowFreeBecauseTheTurnMovedOn() {
        // The answer is no longer streaming, so the model is working on whatever comes next.
        assertTrue(showsPendingThinking(true, "", listOf(user, settled)))
        assertTrue(showsPendingThinking(true, "", listOf(user, settledTool)))
    }

    @Test
    fun nothingIsShownWhenTheTurnIsNotRunning() {
        assertFalse(showsPendingThinking(running = false, reasoning = "", entries = listOf(user)))
        assertFalse(showsPendingThinking(running = false, reasoning = "", entries = emptyList()))
    }
}
