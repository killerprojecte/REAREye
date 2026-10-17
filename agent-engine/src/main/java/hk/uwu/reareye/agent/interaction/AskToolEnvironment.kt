package hk.uwu.reareye.agent.interaction

import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** Built-in interactive tool; its name is reserved and cannot be shadowed by the delegate. */
class AskToolEnvironment(private val delegate: ToolEnvironment, private val ask: AskCallback) :
    ToolEnvironment {
    override fun availableTools(): List<ToolSpec> =
        delegate.availableTools().filterNot { it.name == NAME } + SPEC

    override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
        if (toolName != NAME) return delegate.execute(toolName, args)
        return executeAsk(args, toolCallId = null)
    }

    /** The host supplies the model call ID; it is never accepted from tool arguments. */
    suspend fun executeAsk(args: JsonObject, toolCallId: String?): ToolResult {
        val request = try {
            require(args.keys.all {
                it in setOf(
                    "question",
                    "option_ids",
                    "option_labels",
                    "multiple"
                )
            }) {
                "Unsupported Ask arguments; purpose is host-controlled"
            }
            val ids = args["option_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val labels =
                args["option_labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            require(ids.size == labels.size) { "Option IDs and labels must have equal lengths" }
            AskRequest(
                id = UUID.randomUUID().toString(),
                question = args.getValue("question").jsonPrimitive.content,
                options = ids.zip(labels) { id, label -> AskOption(id, label) },
                multiple = args["multiple"]?.jsonPrimitive?.boolean ?: false,
                allowCustom = true,
                toolCallId = toolCallId,
            ).also { it.validate() }
        } catch (e: IllegalArgumentException) {
            return ToolResult.error("Invalid Ask arguments: ${e.message}")
        } catch (e: NoSuchElementException) {
            return ToolResult.error("Invalid Ask arguments: ${e.message}")
        }
        val answer = ask(request)
        try {
            request.validateAnswer(answer)
        } catch (e: IllegalArgumentException) {
            return ToolResult.error("Invalid Ask answer: ${e.message}")
        }
        return ToolResult.ok(buildJsonObject {
            put("selected_ids", JsonArray(answer.selectedIds.map(::JsonPrimitive)))
            answer.customText?.let { put("custom_text", it) }
        }.toString())
    }

    companion object {
        const val NAME = "Ask"
        val SPEC = ToolSpec(
            name = NAME,
            description = "Ask the user a question with single or multiple choice options. Custom answers are always available. Wait for the user's answer before continuing.",
            params = listOf(
                ToolParam("question", "Question to display", ToolParam.ParamType.STRING),
                ToolParam(
                    "option_ids",
                    "Stable unique option IDs",
                    ToolParam.ParamType.STRING_ARRAY,
                    required = false
                ),
                ToolParam(
                    "option_labels",
                    "Labels in the same order as option_ids",
                    ToolParam.ParamType.STRING_ARRAY,
                    required = false
                ),
                ToolParam(
                    "multiple",
                    "Allow multiple selections",
                    ToolParam.ParamType.BOOLEAN,
                    required = false
                ),
            ),
            risk = ToolRisk.READ_ONLY,
        )
    }
}
