package hk.uwu.reareye.repository.rearwidget

import org.json.JSONObject

/** UI ordering metadata is separate from the card payload consumed by existing hooks. */
data class RearCardOrderSetting(
    val automatic: Boolean = false,
    val position: Int = Int.MAX_VALUE,
)

object RearCardPriorityManager {
    fun parse(raw: String): Map<String, RearCardOrderSetting> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            root.keys().forEach { id ->
                val entry = root.optJSONObject(id) ?: return@forEach
                put(
                    id,
                    RearCardOrderSetting(
                        entry.optBoolean("automatic", false),
                        entry.optInt("position", Int.MAX_VALUE)
                    )
                )
            }
        }
    }

    fun encode(settings: Map<String, RearCardOrderSetting>): String = JSONObject().apply {
        settings.forEach { (id, setting) ->
            put(
                id,
                JSONObject().put("automatic", setting.automatic).put("position", setting.position)
            )
        }
    }.toString()

    /** Preserve the host's existing larger-value-first ordering and stable positions for ties. */
    fun sorted(
        cards: List<RearCardConfig>,
        settings: Map<String, RearCardOrderSetting>
    ): List<RearCardConfig> =
        cards.sortedWith(compareByDescending<RearCardConfig> { it.priority }
            .thenBy { settings[it.id]?.position ?: Int.MAX_VALUE })

    fun rememberOrder(
        cards: List<RearCardConfig>,
        settings: Map<String, RearCardOrderSetting>
    ): Map<String, RearCardOrderSetting> =
        cards.mapIndexed { index, card ->
            card.id to (settings[card.id]
                ?: RearCardOrderSetting(automatic = true)).copy(position = index)
        }.toMap()

    /** Assign consecutive values from 500 upwards, starting at the bottom of the visible list. */
    fun assignAutomaticPriorities(
        cards: List<RearCardConfig>,
        settings: Map<String, RearCardOrderSetting>
    ): List<RearCardConfig> {
        val result = cards.toMutableList()
        val reservedManualPriorities = cards.asSequence()
            .filter { settings[it.id]?.automatic == false }
            .map { it.priority }
            .toSet()
        var nextPriority = 500L
        for (index in cards.indices.reversed()) {
            val card = cards[index]
            if (settings[card.id]?.automatic != false) {
                while (nextPriority <= Int.MAX_VALUE.toLong() && nextPriority.toInt() in reservedManualPriorities) {
                    nextPriority += 1
                }
                result[index] =
                    card.copy(priority = nextPriority.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                nextPriority += 1
            }
        }
        return sorted(result, settings)
    }
}
