package hk.uwu.reareye.agent.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How dangerous a tool is. Only two levels exist on purpose: Mary runs without root, so the
 * blast radius of any single tool is bounded by what the module can do as an ordinary app.
 */
enum class ToolRisk {
    /** Pure reads. Safe to run without asking. */
    READ_ONLY,

    /** Changes persisted configuration or device state. Requires user confirmation. */
    MUTATING,
}

/** One parameter of a tool, described well enough for a model to fill in correctly. */
data class ToolParam(
    val name: String,
    val description: String,
    val type: ParamType,
    val required: Boolean = true,
    /** Allowed values, for enum-like parameters. */
    val enumValues: List<String> = emptyList(),
    val default: String? = null,
) {
    enum class ParamType(val wire: String) {
        STRING("string"),
        INTEGER("integer"),
        NUMBER("number"),
        BOOLEAN("boolean"),
        STRING_ARRAY("array"),
    }
}

/**
 * A tool as advertised to the model. The description carries most of the burden of making a
 * tool usable — state what it does, what it returns, and any preconditions.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    val risk: ToolRisk = ToolRisk.MUTATING,
    /**
     * Per-tool override for the result-size cap, in characters. `null` uses the global setting.
     *
     * Needed because one global value cannot serve both a short status read and a tool that returns
     * a whole generated document: sizing the cap for the document would let every other result
     * flood the context, and sizing it for the status read truncates the document in half.
     */
    val maxResultChars: Int? = null,
) {
    /** Render as a JSON Schema object suitable for OpenAI's `function.parameters`. */
    fun parametersSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                params.forEach { param ->
                    put(param.name, paramSchema(param))
                }
            },
        )
        val required = params.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) {
            put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        put("additionalProperties", false)
    }

    private fun paramSchema(param: ToolParam): JsonObject = buildJsonObject {
        when (param.type) {
            ToolParam.ParamType.STRING_ARRAY -> {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            }

            else -> put("type", param.type.wire)
        }
        put("description", param.description)
        if (param.enumValues.isNotEmpty()) {
            put(
                "enum",
                kotlinx.serialization.json.JsonArray(
                    param.enumValues.map { kotlinx.serialization.json.JsonPrimitive(it) },
                ),
            )
        }
        param.default?.let { put("default", it) }
    }
}

/** Outcome of a tool execution, rendered back to the model as a `tool` message. */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
) {
    companion object {
        fun ok(content: String) = ToolResult(content)
        fun error(message: String) = ToolResult(message, isError = true)
    }
}

/**
 * Implemented by the app: maps a tool name plus arguments onto real module behaviour.
 * The engine never touches Android APIs directly.
 */
interface ToolEnvironment {
    fun availableTools(): List<ToolSpec>
    suspend fun execute(toolName: String, args: JsonObject): ToolResult
}

/** An environment with no tools, used before any tool is registered. */
object EmptyToolEnvironment : ToolEnvironment {
    override fun availableTools(): List<ToolSpec> = emptyList()
    override suspend fun execute(toolName: String, args: JsonObject): ToolResult =
        ToolResult.error("No tools are available.")
}
