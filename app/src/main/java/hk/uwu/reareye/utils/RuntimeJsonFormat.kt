package hk.uwu.reareye.utils

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

internal object RuntimeJsonFormat {
    fun validate(text: String) {
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (error: IllegalArgumentException) {
            throw IOException("runtime.json is unreadable or invalid; refusing to replace it", error)
        }
        if (parsed !is JsonArray) {
            throw IOException("runtime.json must contain an array; refusing to replace it")
        }
    }
}
