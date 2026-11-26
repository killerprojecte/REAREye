package hk.uwu.reareye.agent

import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MarySubagentManagerTest {
    private val initial = ChatMessage(MessageRole.USER, "task")

    @Test fun waitTimeoutDoesNotCancelAndWatchIsDeliveredOnce() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val manager = MarySubagentManager(this) {}
        val id = checkNotNull(manager.start("model", initial) { run ->
            assertTrue(run.send("progress"))
            release.await()
            MarySubagentManager.Outcome(ToolResult.ok("done"))
        })
        assertTrue(manager.watch(id))
        assertTrue(manager.await(id, 5).isError)
        assertFalse(manager.hasWake())
        assertEquals("progress", manager.drainParent().single().text)
        release.complete(Unit)
        assertEquals("done", manager.await(id, 1_000).content)
        assertTrue(manager.hasWake())
        assertTrue(manager.watch(id))
        val event = manager.drainParent().single()
        assertEquals("completed", event.kind)
        assertEquals(event, runtimeEventFromMessage(event.toMessage().content))
        assertEquals("done", manager.await(id, 1).content)
        assertTrue(manager.watch(id))
        assertFalse(manager.hasWake())
    }

    @Test fun followupIsDeliveredEvenAfterLastBoundaryAndUsesPreviousContext() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val subsequent = CompletableDeferred<MarySubagentManager.Invocation>()
        val manager = MarySubagentManager(this) {}
        val id = checkNotNull(manager.start("model", initial) { run ->
            if (run.previous.isEmpty()) {
                releaseFirst.await()
                MarySubagentManager.Outcome(ToolResult.ok("first"), listOf(initial, ChatMessage(MessageRole.ASSISTANT, "first")))
            } else {
                subsequent.complete(run)
                MarySubagentManager.Outcome(ToolResult.ok("second"))
            }
        })
        assertTrue(manager.send(id, "follow up"))
        releaseFirst.complete(Unit)
        val run = subsequent.await()
        assertTrue(run.input.content.orEmpty().contains("follow up"))
        assertEquals(listOf("task", "first"), run.previous.map { it.content })
        assertEquals("second", manager.await(id, 1_000).content)
        assertEquals(2, manager.list().single().round)
    }

    @Test fun stopDeletesAndCancelsWaitersAndClosingCancelsRemainingChildren() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val manager = MarySubagentManager(this) {}
        val id = checkNotNull(manager.start("model", initial) {
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        })
        assertTrue(manager.watch(id))
        val waiting = async { manager.await(id, 5_000) }
        yield()
        assertTrue(manager.stop(id))
        assertTrue(waiting.await().content.contains("removed"))
        stopped.await()
        assertTrue(manager.list().isEmpty())
        assertFalse(manager.hasWake())
        val remaining = CompletableDeferred<Unit>()
        manager.start("model", initial) {
            try { awaitCancellation() } finally { remaining.complete(Unit) }
        }
        yield()
        manager.close().joinAll()
        assertTrue(remaining.isCompleted)
        assertNull(manager.start("model", initial) { error("closed") })
    }
}
