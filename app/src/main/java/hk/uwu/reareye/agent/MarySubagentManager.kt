package hk.uwu.reareye.agent

import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

internal const val MARY_RUNTIME_SENDER = "mary_runtime"
private const val RUNTIME_LABEL = "Mary runtime event (not a user message): "

internal fun runtimeEventFromMessage(content: String?): MarySubagentManager.Event? = runCatching {
    val value = content?.takeIf { it.startsWith(RUNTIME_LABEL) } ?: return null
    val event = Json.parseToJsonElement(value.removePrefix(RUNTIME_LABEL)).jsonObject
    MarySubagentManager.Event(event.getValue("subagent").jsonPrimitive.content,
        event.getValue("round").jsonPrimitive.int, event.getValue("kind").jsonPrimitive.content,
        event.getValue("text").jsonPrimitive.content)
}.getOrNull()

/** Session-owned jobs outlive normal parent turns, not explicit cancellation or session changes. */
internal class MarySubagentManager(private val scope: CoroutineScope, private val changed: () -> Unit) {
    data class Outcome(val result: ToolResult, val context: List<ChatMessage> = emptyList())
    data class Agent(val id: String, val model: String, val round: Int, val status: String)
    data class Event(val id: String, val round: Int, val kind: String, val text: String) {
        fun toMessage(): ChatMessage = ChatMessage(MessageRole.USER, RUNTIME_LABEL +
            buildJsonObject { put("subagent", id); put("round", round); put("kind", kind); put("text", text) },
            name = MARY_RUNTIME_SENDER)
    }

    private class Run(val number: Int) {
        val done = CompletableDeferred<ToolResult>()
        var job: Job? = null
        var result: ToolResult? = null
        var watching = false
        var notified = false
    }

    private class Entry(val id: String, val model: String, val runner: suspend (Invocation) -> Outcome) {
        var run = Run(1)
        var context = emptyList<ChatMessage>()
        val inbox = ArrayDeque<ChatMessage>()
    }

    class Invocation internal constructor(
        val id: String,
        val previous: List<ChatMessage>,
        val input: ChatMessage,
        val inbox: () -> List<ChatMessage>,
        val send: (String) -> Boolean,
    )

    private val lock = Any()
    private val agents = linkedMapOf<String, Entry>()
    private val parentInbox = ArrayDeque<Event>()
    private val retired = mutableListOf<Job>()
    private var closed = false

    fun start(model: String, initial: ChatMessage, runner: suspend (Invocation) -> Outcome): String? = synchronized(lock) {
        if (closed || agents.size >= 12 || agents.values.count { it.run.result == null } >= 3) return null
        val entry = Entry(UUID.randomUUID().toString(), model, runner)
        agents[entry.id] = entry
        launch(entry, initial)
        changed()
        entry.id
    }

    fun list(): List<Agent> = synchronized(lock) {
        agents.values.map {
            val status = it.run.result?.let { result -> if (result.isError) "failed" else "completed" } ?: "running"
            Agent(it.id, it.model, it.run.number, status)
        }
    }

    fun send(id: String, message: String): Boolean = synchronized(lock) {
        val entry = agents[id] ?: return false
        val input = ChatMessage(MessageRole.USER, "Message from the parent agent (not a user message): " +
            buildJsonObject { put("text", message) }, name = MARY_RUNTIME_SENDER)
        if (entry.run.result == null) {
            if (entry.inbox.size >= 16) return false
            entry.inbox.addLast(input)
        } else {
            if (agents.values.count { it.run.result == null } >= 3) return false
            entry.run = Run(entry.run.number + 1)
            launch(entry, input)
        }
        changed()
        true
    }

    suspend fun await(id: String, timeoutMs: Long): ToolResult {
        val done = synchronized(lock) { agents[id]?.run?.done } ?: return ToolResult.error("Unknown subagent ID.")
        return withTimeoutOrNull(timeoutMs) { done.await() }
            ?: ToolResult.error("Wait timed out; the subagent is still running. Use await or watch again.")
    }

    /** Register completion delivery once per execution, even when completion won the race. */
    fun watch(id: String): Boolean = synchronized(lock) {
        val entry = agents[id] ?: return false
        entry.run.watching = true
        if (notifyCompletion(entry, entry.run)) changed()
        true
    }

    fun stop(id: String): Boolean {
        val run = synchronized(lock) {
            val entry = agents.remove(id) ?: return false
            parentInbox.removeAll { it.id == id }
            retired.removeAll { it.isCompleted }
            entry.run.also { stopped -> stopped.job?.takeUnless { it.isCompleted }?.let(retired::add) }
        }
        run.job?.cancel()
        run.done.complete(ToolResult.error("Subagent stopped and removed."))
        changed()
        return true
    }

    fun close(): List<Job> {
        val (runs, stoppedJobs) = synchronized(lock) {
            closed = true
            (agents.values.map { it.run } to retired.toList()).also {
                agents.clear(); parentInbox.clear(); retired.clear()
            }
        }
        runs.forEach {
            it.job?.cancel()
            it.done.complete(ToolResult.error("Parent stopped; subagent cancelled."))
        }
        changed()
        return runs.mapNotNull { it.job } + stoppedJobs
    }

    fun hasWake(): Boolean = synchronized(lock) { parentInbox.any { it.kind == "completed" || it.kind == "failed" } }

    fun drainParent(): List<Event> = synchronized(lock) { parentInbox.toList().also { parentInbox.clear() } }

    private fun notifyCompletion(entry: Entry, run: Run): Boolean {
        val result = run.result ?: return false
        if (!run.watching || run.notified) return false
        run.notified = true
        parentInbox.addLast(Event(entry.id, run.number, if (result.isError) "failed" else "completed", result.content.take(8_000)))
        return true
    }

    private fun launch(entry: Entry, input: ChatMessage) {
        val run = entry.run
        val previous = entry.context
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val invocation = Invocation(entry.id, previous, input, inbox = {
                synchronized(lock) { entry.inbox.toList().also { entry.inbox.clear() } }
            }, send = { text ->
                val accepted = synchronized(lock) {
                    if (agents[entry.id] !== entry || entry.run !== run || parentInbox.size >= 32) false
                    else {
                        parentInbox.addLast(Event(entry.id, run.number, "message", text))
                        true
                    }
                }
                if (accepted) changed()
                accepted
            })
            val outcome = try {
                entry.runner(invocation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Outcome(ToolResult.error("The subagent could not complete its task."))
            }
            synchronized(lock) {
                if (agents[entry.id] !== entry || entry.run !== run) return@synchronized
                entry.context = outcome.context.ifEmpty { previous + input }
                run.result = outcome.result
                notifyCompletion(entry, run)
                run.done.complete(outcome.result)
                // A message accepted during the response's final model call still gets processed.
                if (entry.inbox.isNotEmpty()) {
                    entry.run = Run(run.number + 1)
                    launch(entry, entry.inbox.removeFirst())
                }
            }
            changed()
        }
        run.job = job
        job.start()
    }
}
