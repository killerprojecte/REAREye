package hk.uwu.reareye.agent.tools

import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearCardConfig
import hk.uwu.reareye.repository.rearwidget.RearCardOrderSetting
import hk.uwu.reareye.repository.rearwidget.RearCardPriorityManager
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearWidgetSceneRouteConfig
import hk.uwu.reareye.widgetapi.RearWidgetSceneRouteSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull

/** Pure validation: no coercion of strings into numbers/booleans and no arbitrary image paths. */
internal object MaryConfigEditingValidation {
    fun objectJson(raw: String): JsonObject = Json.parseToJsonElement(raw) as? JsonObject
        ?: throw IllegalArgumentException("Expected a JSON object")

    fun string(value: JsonElement?): String {
        val p = value as? JsonPrimitive
        require(p != null && p.isString) { "Expected string" }
        return p.content
    }

    fun strings(value: JsonElement?): List<String> {
        val array = value as? JsonArray ?: throw IllegalArgumentException("Expected string array")
        return array.map(::string)
            .also { require(it.distinct().size == it.size) { "Duplicate IDs" } }
    }

    private fun bucket(field: JsonObject): String? = when (string(field["tagName"])) {
        "Text" -> "textConfig"
        "Color" -> "colorConfig"
        "FontSize" -> "textSizeConfig"
        "Align" -> "alignStyleConfig"
        "SeekBar" -> "seekBarSaveConfig"
        "ImageSelect", "ImagePick" -> "imageConfig"
        "DropDown" -> "dropDownSaveConfig"
        "OnOff" -> "onOffConfig"
        "StringVar" -> "stringVarSaveConfig"
        else -> MaryConfigEditingComposite.bucket(string(field["tagName"]))
    }

    fun fields(schema: JsonObject): List<JsonObject> =
        (schema["items"] as? JsonArray ?: error("Missing schema items")).map {
            it as? JsonObject ?: error("Invalid schema field")
        }

    fun constraints(schema: JsonObject): JsonArray = JsonArray(fields(schema).map { field ->
        JsonObject(
            field + mapOf(
                "mary_writable" to JsonPrimitive(bucket(field) != null),
                "storage_bucket" to (bucket(field)?.let(::JsonPrimitive) ?: JsonNull),
                "value_contract" to JsonPrimitive(MaryConfigEditingComposite.contract(string(field["tagName"]))),
            )
        )
    })

    /** values_json is a flat object keyed by schema field name, NOT raw OneConfig buckets. */
    fun merge(schema: JsonObject, current: JsonObject, updates: JsonObject): JsonObject {
        require(updates.isNotEmpty()) { "values_json must not be empty" }
        val definitions = fields(schema)
        val result = current.toMutableMap()
        updates.forEach { (name, value) ->
            val field = definitions.singleOrNull { string(it["name"]) == name }
                ?: throw IllegalArgumentException("Unknown or ambiguous field: $name")
            val storage = bucket(field) ?: throw IllegalArgumentException("Read-only field: $name")
            if (MaryConfigEditingComposite.bucket(string(field["tagName"])) != null) {
                val (key, next) = MaryConfigEditingComposite.apply(field, value, JsonObject(result))
                result[key] = next
                return@forEach
            }
            validate(field, value)
            val old = result[storage]
            require(old == null || old is JsonObject) { "Malformed current storage: $storage" }
            result[storage] = JsonObject((old as? JsonObject).orEmpty() + (name to value))
        }
        return JsonObject(result)
    }

    private fun validate(field: JsonObject, value: JsonElement) {
        val tag = string(field["tagName"])
        fun enumValues(key: String) = (field[key] as? JsonArray).orEmpty().map(::string)
        fun options(key: String) =
            (field[key] as? JsonArray).orEmpty().map { string((it as JsonObject)["value"]) }
        when (tag) {
            "FontSize", "SeekBar", "Align" -> {
                val p = value as? JsonPrimitive
                val number = p?.takeUnless { it.isString }?.intOrNull
                require(
                    number != null && number >= (field["from"] as JsonPrimitive).int &&
                            number <= (field["to"] as JsonPrimitive).int
                ) { "Integer out of range" }
            }

            "OnOff" -> require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
                "Expected boolean"
            }

            else -> {
                val text = string(value)
                when (tag) {
                    "Text" -> {
                        val editable = (field["editable"] as? JsonPrimitive)?.booleanOrNull == true
                        require(editable || text in enumValues("valueList")) { "Non-editable text requires preset" }
                        val min = (field["minLength"] as? JsonPrimitive)?.intOrNull ?: 0
                        val max = (field["maxLength"] as? JsonPrimitive)?.intOrNull ?: 0
                        require(text.length >= min && (max <= 0 || text.length <= max)) { "Text length out of range" }
                    }

                    "Color" -> require(
                        text in enumValues("values") || Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(
                            text
                        )
                    ) { "Invalid color" }

                    "DropDown" -> require(text in options("items")) { "Invalid enum option" }
                    "ImageSelect" -> require(text in options("values")) { "Image must be an existing option" }
                    "ImagePick" -> require(text in options("options")) { "Image must be an existing option" }
                }
            }
        }
    }

    fun order(
        cards: List<RearCardConfig>, settings: Map<String, RearCardOrderSetting>, ids: List<String>,
        automaticIds: List<String>?
    ): Pair<List<RearCardConfig>, Map<String, RearCardOrderSetting>> {
        require(ids.distinct().size == ids.size && ids.size == cards.size && ids.toSet() == cards.map { it.id }
            .toSet()) {
            "card_ids must contain every current card exactly once"
        }
        require(
            automaticIds == null || (automaticIds.distinct().size == automaticIds.size && ids.containsAll(
                automaticIds
            ))
        ) {
            "automatic_ids must be a unique subset of card_ids"
        }
        val nextSettings = settings.toMutableMap()
        ids.forEachIndexed { index, id ->
            nextSettings[id] = RearCardOrderSetting(
                automatic = automaticIds?.contains(id) ?: (settings[id]?.automatic ?: true),
                position = index
            )
        }
        val ordered = ids.map { id -> cards.single { it.id == id } }
        val next = RearCardPriorityManager.assignAutomaticPriorities(ordered, nextSettings)
        cards.filterNot { it.renameable }.forEach { locked ->
            require(next.single { it.id == locked.id } == locked && nextSettings[locked.id] == settings[locked.id]) {
                "Locked card cannot be reordered or change mode"
            }
        }
        return next to nextSettings
    }

    fun route(
        routes: List<RearWidgetSceneRouteConfig>, businesses: List<RearBusinessConfig>, id: String,
        packageName: String?, scene: String?, business: String?
    ): List<RearWidgetSceneRouteConfig> {
        val old =
            routes.singleOrNull { it.id == id } ?: throw IllegalArgumentException("Route not found")
        val pkg = (packageName ?: old.packageName).trim()
        val sc = RearWidgetSceneRouteSpec.normalizeScenePattern(scene ?: old.scene)
        val biz = (business ?: old.business).trim()
        for (pattern in listOf(pkg, sc)) {
            require(pattern.isNotBlank()) { "Route pattern must not be blank" }
            require(
                !pattern.equals("regex:", true) && !pattern.equals("re:", true) &&
                        !(pattern.length >= 2 && pattern.startsWith('/') && pattern.endsWith('/') && pattern.substring(
                            1,
                            pattern.length - 1
                        ).isBlank())
            ) { "Empty regex" }
            RearWidgetSceneRouteSpec.extractRegexPattern(pattern)?.let { Regex(it) }
        }
        val source = businesses.singleOrNull { it.business == old.business }
            ?: throw IllegalArgumentException("Source component missing")
        val target = businesses.singleOrNull { it.business == biz }
            ?: throw IllegalArgumentException("Target component missing")
        require(source.renameable && target.renameable) { "Locked component" }
        val newId = RearWidgetConfigCodec.newSceneRouteId(pkg, sc)
        require(routes.none {
            it.id != old.id && RearWidgetConfigCodec.newSceneRouteId(
                it.packageName,
                it.scene
            ) == newId
        }) {
            "Route conflicts with existing package/scene"
        }
        val replacement = old.copy(id = newId, packageName = pkg, scene = sc, business = biz)
        return routes.map { if (it.id == id) replacement else it }
    }

    fun verify(condition: Boolean) {
        check(condition) { "READBACK_MISMATCH: persisted state differs; write may have partially applied" }
    }
}
