package hk.uwu.reareye.agent.tools

import hk.uwu.reareye.repository.rearwidget.parseDateStringToMillis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Composite UI values are translated without round-tripping through a lossy OneConfig DTO. */
internal object MaryConfigEditingComposite {
    private val v = MaryConfigEditingValidation
    fun bucket(tag: String): String? = when (tag) {
        "ColorGroup" -> "colorGroupConfig"
        "SetDate" -> "dateSetConfig"
        "CustomColor" -> "customColorSaveConfig"
        "MultiImageSelect" -> "multiImageConfig"
        "Intent" -> "intentSaveConfig"
        "AnimatVar" -> "animationSaveConfig"
        "BackgroudMode", "MultiModeImage" -> "backgroundModeSaveConfig"
        "MultiModeImageList" -> "multiModeImageListSaveConfig"
        else -> null
    }

    fun contract(tag: String): String = when (tag) {
        "ColorGroup" -> "object {itemName: colorString}; partial child merge; only schema item names; palette value or #RRGGBB/#AARRGGBB"
        "SetDate" -> "ISO yyyy-MM-dd string; repeatName/repeatValue are preserved, not editable"
        "CustomColor" -> "color string: palette value or #RRGGBB/#AARRGGBB; preserves index"
        "MultiImageSelect" -> "array of numeric 0/1 with exactly items.length entries; single=true permits at most one 1"
        "Intent" -> "object {returnValueKey: typedValue}; only schema returnValue keys; boolean, int, finite float/double/number, otherwise string. Does not launch activities or change intent targets."
        "AnimatVar" -> "partial object with finite numeric x,y,scaleX,scaleY,rotation,displayScale; no other keys"
        "BackgroudMode", "MultiModeImage" -> "partial object {value:integer,resType:integer,uri:string,videoUri:string,foregroundUri:string}; URI must equal current value or schema default; binding names cannot change"
        "MultiModeImageList" -> "partial object {size:nonnegative integer,itemList:[partial background objects]}; itemList length must equal existing/schema items; URI only existing value or schema default"
        "CustomEditLink" -> "read-only: external editor/deeplink launch is not a configuration write"
        else -> "typed scalar matching schema; image strings must be existing schema options"
    }

    private fun number(value: JsonElement?): Double {
        val p = value as? JsonPrimitive
        val n = p?.takeUnless { it.isString }?.doubleOrNull
        require(n != null && n.isFinite()) { "Expected finite number" }
        return n
    }

    private fun integer(value: JsonElement?): Int {
        val p = value as? JsonPrimitive
        return p?.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException("Expected integer")
    }

    private fun objectValue(value: JsonElement): JsonObject = value as? JsonObject
        ?: throw IllegalArgumentException("Expected object")

    private fun color(value: JsonElement, palette: JsonElement?) {
        val text = v.string(value)
        require(
            (palette as? JsonArray).orEmpty()
                .any { it == value } || Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(text)) { "Invalid color" }
    }

    private fun defaults(field: JsonObject, keys: List<String>): Map<String, JsonElement> =
        keys.mapNotNull { key -> field[key]?.let { key to it } }.toMap()

    fun apply(field: JsonObject, value: JsonElement, root: JsonObject): Pair<String, JsonElement> {
        val tag = v.string(field["tagName"])
        val storage = bucket(tag) ?: throw IllegalArgumentException("Read-only field")
        val name = v.string(field["name"])
        val existing = root[storage]
        require(existing == null || existing is JsonObject) { "Malformed composite storage" }
        val old = existing as? JsonObject
        if (tag != "Intent" && old != null) require(
            old["name"] == null || old["name"] == JsonPrimitive(
                name
            )
        ) {
            "Composite bucket is occupied by another field"
        }
        val base = old.orEmpty() + ("name" to JsonPrimitive(name))
        val next: JsonObject = when (tag) {
            "ColorGroup" -> {
                val changes = objectValue(value)
                require(changes.isNotEmpty()) { "Empty color group" }
                val items = (field["items"] as JsonArray).map { it as JsonObject }
                require(old?.get("selectColors") == null || old?.get("selectColors") is JsonObject) { "Malformed color group" }
                val colors = (old?.get("selectColors") as? JsonObject).orEmpty().toMutableMap()
                items.forEach { item ->
                    val key = v.string(item["name"])
                    if (key !in colors) colors[key] =
                        (item["values"] as? JsonArray)?.firstOrNull() ?: JsonPrimitive("")
                }
                changes.forEach { (key, itemValue) ->
                    val definition = items.singleOrNull { it["name"] == JsonPrimitive(key) }
                        ?: throw IllegalArgumentException("Unknown color group item")
                    color(itemValue, definition["values"]); colors[key] = itemValue
                }
                JsonObject(
                    base + mapOf(
                        "selectColors" to JsonObject(colors), "index" to JsonPrimitive(
                            items.indexOfFirst { it["name"] == JsonPrimitive(changes.keys.last()) })
                    )
                )
            }

            "SetDate" -> {
                val millis = parseDateStringToMillis(v.string(value))
                    ?: throw IllegalArgumentException("Invalid ISO date")
                JsonObject(
                    defaults(
                        field,
                        listOf("repeatName", "repeatValue")
                    ) + base + ("date" to JsonPrimitive(millis))
                )
            }

            "CustomColor" -> {
                color(value, field["values"])
                JsonObject(defaults(field, listOf("index")) + base + ("value" to value))
            }

            "MultiImageSelect" -> {
                val array = value as? JsonArray
                    ?: throw IllegalArgumentException("Expected selection array")
                require(array.size == (field["items"] as JsonArray).size) { "Selection length mismatch" }
                val selected =
                    array.map { number(it).also { n -> require(n == 0.0 || n == 1.0) { "Selection must be 0/1" } } }
                require((field["single"] as? JsonPrimitive)?.booleanOrNull != true || selected.count { it == 1.0 } <= 1) { "Only one image can be selected" }
                JsonObject(base + ("selectImages" to JsonArray(selected.map { JsonPrimitive(it) })))
            }

            "Intent" -> return storage to intent(field, objectValue(value), old.orEmpty(), name)
            "AnimatVar" -> {
                val keys = listOf("x", "y", "scaleX", "scaleY", "rotation", "displayScale")
                val changes = objectValue(value)
                require(changes.isNotEmpty() && changes.keys.all { it in keys }) { "Unknown animation property" }
                val initial =
                    defaults(field, keys).mapValues { (_, item) -> JsonPrimitive(number(item)) }
                val numericChanges = changes.mapValues { (_, item) -> JsonPrimitive(number(item)) }
                JsonObject(initial + base + numericChanges)
            }

            "BackgroudMode", "MultiModeImage" -> background(field, objectValue(value), old, name)
            "MultiModeImageList" -> {
                val changes = objectValue(value)
                require(changes.isNotEmpty() && changes.keys.all { it == "size" || it == "itemList" }) { "Unknown image list property" }
                val definitions = field["items"] as JsonArray
                val storedItems = old?.get("itemList")
                require(storedItems == null || storedItems == JsonNull || storedItems is JsonArray) { "Malformed image list" }
                val existingItems =
                    storedItems as? JsonArray ?: JsonArray(definitions.map { child ->
                        val definition = child as JsonObject
                        background(
                            definition,
                            buildJsonObject {
                                put(
                                    "value",
                                    definition["value"] ?: JsonPrimitive(0)
                                )
                            },
                            null,
                            ""
                        )
                    })
                val size = changes["size"] ?: old?.get("size") ?: field["size"] ?: JsonPrimitive(
                    definitions.size
                )
                require(integer(size) >= 0) { "List size must be nonnegative" }
                val items =
                    (changes["itemList"] as? JsonArray)?.also { require(it.size == existingItems.size && it.size == definitions.size) { "Cannot add/remove image resources" } }
                require("itemList" !in changes || items != null) { "itemList must be array" }
                val nextItems =
                    if (items == null) existingItems else JsonArray(items.mapIndexed { index, child ->
                        background(
                            definitions[index] as JsonObject,
                            objectValue(child),
                            existingItems[index] as? JsonObject,
                            ""
                        )
                    })
                JsonObject(base + mapOf("size" to size, "itemList" to nextItems))
            }

            else -> throw IllegalArgumentException("Unsupported composite field")
        }
        return storage to next
    }

    private fun background(
        field: JsonObject,
        changes: JsonObject,
        old: JsonObject?,
        name: String
    ): JsonObject {
        val keys = listOf("value", "resType", "uri", "videoUri", "foregroundUri")
        require(changes.isNotEmpty() && changes.keys.all { it in keys }) { "Unknown background property" }
        changes.forEach { (key, value) ->
            if (key == "value" || key == "resType") integer(value) else {
                v.string(value)
                require(value == old?.get(key) || value == field[key]) { "URI must be an existing resource; arbitrary paths/imports forbidden" }
            }
        }
        val bindings = listOf("uriName", "videoName", "foregroundName")
        val missingBindings = bindings.associateWith { JsonPrimitive("") }
        return JsonObject(
            missingBindings + defaults(field, keys + bindings) + old.orEmpty() +
                    mapOf("name" to JsonPrimitive(name)) + changes
        )
    }

    private fun intent(
        field: JsonObject,
        changes: JsonObject,
        storage: Map<String, JsonElement>,
        name: String
    ): JsonObject {
        require(changes.isNotEmpty()) { "Empty intent return values" }
        // Match RearWidgetIntentSaveConfigMapAdapter: singleton entries are flat, multiple entries keyed by name.
        val entries = if ("name" in storage) {
            val storedName = v.string(storage["name"])
            require(storedName.isNotBlank()) { "Malformed flat intent name" }
            mapOf(storedName to JsonObject(storage))
        } else storage
        require(entries[name] == null || entries[name] is JsonObject) { "Malformed intent entry" }
        val entry = entries[name] as? JsonObject
        for (key in listOf("returnValueString", "returnValueNumber")) {
            require(entry?.get(key) == null || entry?.get(key) is JsonObject) { "Malformed intent return map" }
        }
        val strings = (entry?.get("returnValueString") as? JsonObject).orEmpty().toMutableMap()
        val numbers = (entry?.get("returnValueNumber") as? JsonObject).orEmpty().toMutableMap()
        val keys = (field["returnValue"] as? JsonArray).orEmpty().map(v::string)
        val types = (field["valueType"] as? JsonArray).orEmpty().map(v::string)
        changes.forEach { (key, value) ->
            val index = keys.indexOf(key)
            require(index >= 0) { "Unknown intent return key" }
            when (types.getOrNull(index)?.lowercase()) {
                "boolean" -> {
                    val p = value as? JsonPrimitive
                    require(p != null && !p.isString && p.booleanOrNull != null) { "Expected boolean return value" }
                    numbers[key] = JsonPrimitive(if (p.boolean) 1.0 else 0.0); strings.remove(key)
                }

                "int" -> {
                    numbers[key] = JsonPrimitive(integer(value).toDouble()); strings.remove(key)
                }

                "float", "double", "number" -> {
                    numbers[key] = JsonPrimitive(number(value)); strings.remove(key)
                }

                else -> {
                    v.string(value); strings[key] = value; numbers.remove(key)
                }
            }
        }
        val nextEntries = entries + (name to JsonObject(
            entry.orEmpty() + mapOf(
                "name" to JsonPrimitive(name),
                "returnValueString" to JsonObject(strings),
                "returnValueNumber" to JsonObject(numbers)
            )
        ))
        if (nextEntries.size == 1) return nextEntries.values.single() as JsonObject
        require("name" !in nextEntries) { "Multiple intent fields cannot use reserved name 'name'" }
        return JsonObject(nextEntries)
    }
}
