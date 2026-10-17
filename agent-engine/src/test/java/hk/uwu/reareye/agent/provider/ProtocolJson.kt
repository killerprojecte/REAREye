package hk.uwu.reareye.agent.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// Assertion conveniences only; no copied production protocol decoder.
internal fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.obj(key: String) = get(key) as? JsonObject
