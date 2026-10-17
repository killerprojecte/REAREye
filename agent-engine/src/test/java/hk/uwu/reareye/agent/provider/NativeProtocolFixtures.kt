package hk.uwu.reareye.agent.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Expand terse legacy assertions into complete, valid provider wire DTOs for the native clients. */
internal fun nativeFixture(raw: String): String {
    if (raw == "[DONE]") return raw
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
    val type = root["type"]?.jsonPrimitive?.contentOrNull
    if (type?.startsWith("response.") == true) {
        val fields = root.toMutableMap()
        fields.putIfAbsent("sequence_number", JsonPrimitive(1))
        if (type in listOf(
                "response.output_text.delta",
                "response.output_text.done",
                "response.function_call_arguments.delta",
                "response.function_call_arguments.done"
            )
        ) {
            fields.putIfAbsent("item_id", JsonPrimitive("fc_1"))
            fields.putIfAbsent("content_index", JsonPrimitive(0))
        }
        if (root["response"] is JsonObject) {
            val response = root.getValue("response").jsonObject.toMutableMap()
            response.putIfAbsent("id", JsonPrimitive("response_test"))
            response.putIfAbsent("created_at", JsonPrimitive(1))
            response.putIfAbsent("model", JsonPrimitive("test"))
            response.putIfAbsent("output", JsonArray(emptyList()))
            response.putIfAbsent("parallel_tool_calls", JsonPrimitive(false))
            response.putIfAbsent(
                "status",
                JsonPrimitive(if (type == "response.incomplete") "incomplete" else "completed")
            )
            response.putIfAbsent(
                "text",
                buildJsonObject { putJsonObject("format") { put("type", "text") } })
            (response["usage"] as? JsonObject)?.let {
                val usage = it.toMutableMap()
                usage.putIfAbsent(
                    "input_tokens_details",
                    buildJsonObject { put("cached_tokens", 0) })
                usage.putIfAbsent(
                    "output_tokens_details",
                    buildJsonObject { put("reasoning_tokens", 0) })
                usage.putIfAbsent(
                    "total_tokens",
                    JsonPrimitive(
                        (it["input_tokens"]?.jsonPrimitive?.int
                            ?: 0) + (it["output_tokens"]?.jsonPrimitive?.int ?: 0)
                    )
                )
                response["usage"] = JsonObject(usage)
            }
            fields["response"] = JsonObject(response)
        }
        return JsonObject(fields).toString()
    }
    if (type == "message_start") {
        val message = root["message"]?.jsonObject.orEmpty().toMutableMap()
        message.putIfAbsent("id", JsonPrimitive("message_test")); message.putIfAbsent(
            "type",
            JsonPrimitive("message")
        )
        message.putIfAbsent("role", JsonPrimitive("assistant")); message.putIfAbsent(
            "content",
            JsonArray(emptyList())
        )
        message.putIfAbsent("model", JsonPrimitive("test"))
        return JsonObject(root + ("message" to JsonObject(message))).toString()
    }
    if (type != null) return raw
    if (root.containsKey("choices")) {
        val fields = root.toMutableMap()
        fields.putIfAbsent("id", JsonPrimitive("chat_test")); fields.putIfAbsent(
            "model",
            JsonPrimitive("test")
        )
        fields.putIfAbsent("created", JsonPrimitive(1)); fields.putIfAbsent(
            "object",
            JsonPrimitive("chat.completion.chunk")
        )
        (root["choices"] as? JsonArray)?.let { choices ->
            fields["choices"] = JsonArray(choices.mapIndexed { index, element ->
                JsonObject(element.jsonObject + ("index" to JsonPrimitive(index)))
            })
        }
        (root["usage"] as? JsonObject)?.let { usage ->
            fields["usage"] = JsonObject(
                usage + ("total_tokens" to JsonPrimitive(
                    (usage["prompt_tokens"]?.jsonPrimitive?.int
                        ?: 0) + (usage["completion_tokens"]?.jsonPrimitive?.int ?: 0)
                ))
            )
        }
        return JsonObject(fields).toString()
    }
    return raw
}
