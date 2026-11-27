package hk.uwu.reareye.agent

import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolParam.ParamType.INTEGER
import hk.uwu.reareye.agent.model.ToolParam.ParamType.STRING
import hk.uwu.reareye.agent.model.ToolParam.ParamType.STRING_ARRAY
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal class MarySubagentEnvironment(
    private val parent: ToolEnvironment,
    private val manager: MarySubagentManager,
    private val models: Map<String, String>,
    private val defaultModelId: String,
    private val allowedAttachments: Set<String>,
    private val timeoutMs: Long,
    private val start: suspend (modelId: String, task: String, attachmentIds: Set<String>, workIds: Set<String>) -> String?,
) : ToolEnvironment {
    private val id = ToolParam("id", "Subagent ID from subagent_start or subagent_list.", STRING)
    private val startParams = listOf(
        ToolParam("task", "A self-contained task and expected result (1–8000 characters).", STRING),
        ToolParam("model_id", "Optional model ID. Available: " + models.entries.take(32).joinToString { "${it.key}: ${it.value.take(80)}" }, STRING, required = false),
        ToolParam("attachment_ids", "Explicit read-only conversation attachment IDs.", STRING_ARRAY, required = false),
        ToolParam("file_ids", "Explicit editable workspace IDs, from workspace_list_files.", STRING_ARRAY, required = false),
    )
    private val waitParams = listOf(id, ToolParam("timeout_ms", "Optional wait deadline; does not cancel the subagent.", INTEGER, required = false))
    private val specs = listOf(
        ToolSpec(START, "Start a bounded subagent in the background and return its ID. It can use shared workspace files, but cannot use device tools or delegate. Use await or watch to get the result.", startParams),
        ToolSpec(NAME, "Delegate a bounded task and wait for its result. Prefer subagent_start plus subagent_watch when work can overlap.", startParams, maxResultChars = 8_000),
        ToolSpec(LIST, "List current subagents, their model, execution number and status.", risk = ToolRisk.READ_ONLY),
        ToolSpec(SEND, "Send a follow-up to a subagent. A completed subagent starts a new execution with its existing context.", listOf(id, ToolParam("message", "Message from parent to child.", STRING))),
        ToolSpec(AWAIT, "Wait for the current execution. A completed execution returns immediately. Timeout never stops the child.", waitParams, risk = ToolRisk.READ_ONLY, maxResultChars = 8_000),
        ToolSpec(WATCH, "Return immediately and deliver one completion event for the current execution. An idle parent is resumed by completion.", listOf(id), risk = ToolRisk.READ_ONLY),
        ToolSpec(STOP, "Cancel the running work and remove the subagent from the list. Committed workspace edits are kept.", listOf(id)),
    )
    private val ownNames = specs.mapTo(mutableSetOf()) { it.name }

    override fun availableTools(): List<ToolSpec> = parent.availableTools().filterNot { it.name in ownNames } + specs

    override suspend fun execute(toolName: String, args: JsonObject): ToolResult = when (toolName) {
        START, NAME -> launch(toolName, args)
        LIST -> ToolResult.ok(JsonArray(manager.list().map { agent -> buildJsonObject {
            put("id", agent.id); put("model", agent.model); put("round", agent.round); put("status", agent.status)
        } }).toString())
        SEND, AWAIT, WATCH, STOP -> handle(toolName, args)
        else -> parent.execute(toolName, args)
    }

    private suspend fun launch(toolName: String, args: JsonObject): ToolResult {
        val task = args.text("task") ?: return ToolResult.error("Provide a task of 1–8000 characters.")
        val modelId = if ("model_id" in args) args.text("model_id", 80) else defaultModelId
        if (modelId == null || modelId !in models) return ToolResult.error("The requested subagent model is not configured.")
        val attachments = args.ids("attachment_ids") ?: return ToolResult.error("attachment_ids must be a list of up to eight valid IDs.")
        val files = args.ids("file_ids") ?: return ToolResult.error("file_ids must be a list of up to eight valid IDs.")
        if (!allowedAttachments.containsAll(attachments)) return ToolResult.error("An attachment is outside this conversation.")
        val created = start(modelId, task, attachments, files)
            ?: return ToolResult.error("Could not start subagent: check file IDs and the active subagent limit.")
        return if (toolName == NAME) manager.await(created, timeoutMs) else ToolResult.ok(buildJsonObject { put("id", created) }.toString())
    }

    private suspend fun handle(name: String, args: JsonObject): ToolResult {
        val id = args.text("id", 80) ?: return ToolResult.error("Provide a subagent ID.")
        return when (name) {
            SEND -> {
                val text = args.text("message") ?: return ToolResult.error("Provide a message of 1–8000 characters.")
                if (manager.send(id, text)) ToolResult.ok("Message accepted.") else ToolResult.error("Subagent not found or its queue is full.")
            }
            AWAIT -> {
                val deadline = if ("timeout_ms" in args) (args["timeout_ms"] as? JsonPrimitive)?.intOrNull?.toLong()
                    ?.takeIf { it in 1..timeoutMs } ?: return ToolResult.error("timeout_ms must be between 1 and $timeoutMs.")
                else timeoutMs
                manager.await(id, deadline)
            }
            WATCH -> if (manager.watch(id)) ToolResult.ok("Completion is being watched.") else ToolResult.error("Unknown subagent ID.")
            else -> if (manager.stop(id)) ToolResult.ok("Subagent stopped and removed.") else ToolResult.error("Unknown subagent ID.")
        }
    }

    companion object {
        const val NAME = "delegate_subagent"
        const val START = "subagent_start"
        const val LIST = "subagent_list"
        const val SEND = "subagent_send"
        const val AWAIT = "subagent_await"
        const val WATCH = "subagent_watch"
        const val STOP = "subagent_stop"
    }
}

internal class MarySubagentMailbox(private val delegate: ToolEnvironment, private val send: (String) -> Boolean) : ToolEnvironment {
    private val spec = ToolSpec(NAME, "Send a progress update or finding directly to the parent agent.",
        listOf(ToolParam("message", "Message from child to parent, at most 8000 characters.", STRING)), risk = ToolRisk.READ_ONLY)

    override fun availableTools() = delegate.availableTools() + spec

    override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
        if (toolName != NAME) return delegate.execute(toolName, args)
        val message = args.text("message") ?: return ToolResult.error("Provide a message of 1–8000 characters.")
        return if (send(message)) ToolResult.ok("Message accepted.") else ToolResult.error("The parent mailbox is unavailable or full.")
    }

    companion object { const val NAME = "subagent_send_parent" }
}

private fun JsonObject.text(key: String, max: Int = 8_000) = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }

private fun JsonObject.ids(key: String): Set<String>? {
    if (key !in this) return emptySet()
    val items = get(key) as? JsonArray ?: return null
    if (items.size > 8) return null
    return items.mapTo(linkedSetOf()) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        ?.takeIf { id -> id.length in 1..80 } ?: return null }
}
