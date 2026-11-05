package hk.uwu.reareye.agent.provider

import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Koog 1.3 understands reasoning items/deltas, but its content-part DTO only admits text/refusal.
 * Keep reasoning boundary snapshots until the real output-item completion. Never turn reasoning
 * into assistant text, concatenate a full snapshot as a delta, or swallow unknown event types.
 * State is scoped to one stream, never a conversation.
 */
internal class ResponsesReasoningParts {
    private class Part {
        val text = StringBuilder()
        var added = false
        var done = false
    }

    private class Item(val id: String) {
        val parts = sortedMapOf<Int, Part>()
        var complete = false
    }

    private val items = mutableMapOf<Int, Item>()

    private fun string(value: JsonElement?, field: String): String {
        require(value is JsonPrimitive && value.isString) { "Responses reasoning $field must be a string" }
        return value.content
    }

    private fun index(event: JsonObject, field: String): Int {
        val value = event[field]
        require(value is JsonPrimitive && !value.isString && (value.intOrNull ?: -1) >= 0) {
            "Responses reasoning $field must be a nonnegative integer"
        }
        return requireNotNull(value.intOrNull)
    }

    private fun item(output: Int, id: String): Item {
        require(id.isNotEmpty()) { "Responses reasoning item ID is empty" }
        return items.getOrPut(output) { Item(id) }.also {
            require(it.id == id && !it.complete) { "Responses reasoning item identity or completion mismatch" }
        }
    }

    private fun snapshot(
        part: Part,
        text: String,
        id: String,
        output: Int,
        ready: ArrayDeque<StreamFrame>,
        finalItem: Boolean = false,
    ) {
        val seen = part.text.toString()
        require(text.startsWith(seen) && (!part.done || finalItem || text == seen)) {
            "Responses reasoning snapshot differs from streamed fragments"
        }
        val suffix = text.substring(seen.length)
        if (suffix.isNotEmpty()) {
            part.text.append(suffix)
            ready += StreamFrame.ReasoningDelta(id = id, text = suffix, index = output)
        }
    }

    fun observe(event: JsonObject, ready: ArrayDeque<StreamFrame>): JsonObject {
        val type = event["type"]?.jsonPrimitive?.contentOrNull
        val boundary = type == "response.content_part.added" || type == "response.content_part.done"
        val partObject = event["part"] as? JsonObject
        if (boundary && partObject?.get("type") == JsonPrimitive("reasoning_text")) {
            val output = index(event, "output_index")
            val content = index(event, "content_index")
            val id = string(event["item_id"], "item_id")
            val text = string(partObject["text"], "part.text")
            val part = item(output, id).parts.getOrPut(content) { Part() }
            if (type == "response.content_part.added") {
                require(!part.added && !part.done && part.text.isEmpty()) { "Responses reasoning part added out of order" }
                part.added = true
            }
            snapshot(part, text, id, output, ready)
            if (type == "response.content_part.done") part.done = true
            // Native validates the original identity/index/sequence fields with its known DTO.
            // This DTO is a no-op in Koog; the retained snapshot and ready deltas above own text.
            return JsonObject(event - "part" + mapOf(
                "type" to JsonPrimitive("response.reasoning_text.done"),
                "text" to JsonPrimitive(text)
            ))
        }
        if (type == "response.reasoning_text.delta") {
            val output = index(event, "output_index")
            val content = index(event, "content_index")
            val id = string(event["item_id"], "item_id")
            val part = item(output, id).parts.getOrPut(content) { Part() }
            require(!part.done) { "Responses reasoning delta after part completion" }
            // Deltas are additive, including legitimate repeated fragments such as ha + ha.
            part.text.append(string(event["delta"], "delta"))
        }
        if (type == "response.reasoning_text.done") {
            val output = index(event, "output_index")
            val content = index(event, "content_index")
            val id = string(event["item_id"], "item_id")
            val part = item(output, id).parts.getOrPut(content) { Part() }
            snapshot(part, string(event["text"], "text"), id, output, ready)
            part.done = true
        }
        val outputItem = event["item"] as? JsonObject
        if (type == "response.output_item.done" && outputItem?.get("type") == JsonPrimitive("reasoning")) {
            val output = index(event, "output_index")
            val id = string(outputItem["id"], "item.id")
            val item = item(output, id)
            val rawContent = outputItem["content"]
            require(rawContent == null || rawContent == JsonNull || rawContent is JsonArray) {
                "Responses reasoning item.content must be an array"
            }
            val content = rawContent as? JsonArray
            val result = if (content.isNullOrEmpty() && item.parts.values.any { it.text.isNotEmpty() }) {
                require(item.parts.keys.toList() == (0 until item.parts.size).toList()) {
                    "Responses reasoning content indices are incomplete"
                }
                val recovered = JsonArray(item.parts.values.map {
                    JsonObject(mapOf("type" to JsonPrimitive("reasoning_text"), "text" to JsonPrimitive(it.text.toString())))
                })
                JsonObject(event + ("item" to JsonObject(outputItem + ("content" to recovered))))
            } else {
                require(item.parts.keys.all { it < (content?.size ?: 0) || item.parts.getValue(it).text.isEmpty() }) {
                    "Responses reasoning completion is missing a content part"
                }
                content?.forEachIndexed { index, value ->
                    require(value is JsonObject && (value["type"] == null || value["type"] == JsonPrimitive("reasoning_text"))) {
                        "Unsupported Responses reasoning content part"
                    }
                    snapshot(item.parts.getOrPut(index) { Part() }, string(value["text"], "content.text"), id, output, ready, finalItem = true)
                }
                event
            }
            item.complete = true
            // The native output_item.done mapper alone emits ReasoningComplete, preserving
            // summary/encrypted_content/id and producing exactly one replayable item.
            return result
        }
        return event
    }

    fun requireComplete() {
        require(items.values.all { it.complete }) { "Responses reasoning ended without output item completion" }
    }
}
