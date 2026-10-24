package hk.uwu.reareye.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
enum class ReasoningEffort { NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

/** Only adjustable levels, ordered from least to most reasoning. Empty means unknown or fixed. */
class ReasoningSupport internal constructor(
    val levels: List<ReasoningEffort>,
    internal val mode: ReasoningMode = ReasoningMode.UNKNOWN,
    val status: ReasoningStatus = if (levels.isEmpty()) ReasoningStatus.UNKNOWN else ReasoningStatus.SUPPORTED,
    val source: CapabilitySource = if (levels.isEmpty()) CapabilitySource.UNKNOWN else CapabilitySource.OFFLINE,
    private val exactLevels: Boolean = false,
) {
    fun effectiveEffort(requested: ReasoningEffort): ReasoningEffort? {
        if (levels.isEmpty()) return null
        if (!exactLevels && mode == ReasoningMode.DEEPSEEK) return when (requested) {
            ReasoningEffort.MINIMAL -> ReasoningEffort.LOW
            ReasoningEffort.MEDIUM, ReasoningEffort.XHIGH -> ReasoningEffort.HIGH
            else -> requested
        }
        return levels.firstOrNull { it.ordinal >= requested.ordinal } ?: levels.last()
    }
}

internal enum class ReasoningMode { UNKNOWN, OPENAI, CLAUDE_MANUAL, CLAUDE_OPUS45, CLAUDE_ADAPTIVE, DEEPSEEK, GATEWAY_REASONING }

/** Explicit model families only. Never infer capability from arbitrary chat/custom model names. */
object ReasoningPolicy {
    private val lowHigh = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
    private val gpt5 = listOf(ReasoningEffort.MINIMAL) + lowHigh
    private val gpt51 = listOf(ReasoningEffort.NONE) + lowHigh
    private val gpt52 = gpt51 + ReasoningEffort.XHIGH
    private val unknown = ReasoningSupport(emptyList())

    fun support(config: ProviderConfig): ReasoningSupport {
        val capability = config.reasoningOverride?.takeIf {
            it.source == CapabilitySource.MANUAL && it.validFor(config)
        }
            ?: config.reasoningMetadata?.takeIf {
                it.source == CapabilitySource.SERVICE_METADATA && it.validFor(
                    config
                )
            }
        if (capability != null && capability.status != ReasoningStatus.UNKNOWN) {
            val mode = when (capability.mapping) {
                ReasoningMapping.OPENAI_EFFORT -> ReasoningMode.OPENAI
                ReasoningMapping.OPENROUTER_REASONING -> ReasoningMode.GATEWAY_REASONING
                ReasoningMapping.DEEPSEEK_THINKING -> ReasoningMode.DEEPSEEK
                ReasoningMapping.CLAUDE_BUDGET -> ReasoningMode.CLAUDE_MANUAL
                ReasoningMapping.CLAUDE_ADAPTIVE -> ReasoningMode.CLAUDE_ADAPTIVE
                null -> ReasoningMode.UNKNOWN
            }
            return ReasoningSupport(
                capability.levels.sortedBy { it.ordinal },
                mode,
                capability.status,
                capability.source,
                exactLevels = true
            )
        }
        val raw = config.model.trim().lowercase()
        val model = when {
            raw.startsWith("openai/") -> raw.removePrefix("openai/")
            raw.startsWith("anthropic/") -> raw.removePrefix("anthropic/")
            raw.startsWith("deepseek/") -> raw.removePrefix("deepseek/")
            else -> raw
        }
        // A date suffix is an alias of a known family, not permission to match arbitrary suffixes.
        val name = model.replace(Regex("-(?:20\\d{2}-\\d{2}-\\d{2}|20\\d{6})$"), "")
        // deepseek-flash is also served under its explicit V4.1 model id by compatible gateways.
        if (name in setOf(
                "deepseek-flash",
                "deepseek-v4-pro",
                "deepseek-v4-flash",
                "deepseek-v4-flash-vision-exp",
                "deepseek-v4.1-flash"
            )
        ) {
            return ReasoningSupport(
                listOf(
                    ReasoningEffort.NONE,
                    ReasoningEffort.LOW,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.MAX
                ), ReasoningMode.DEEPSEEK
            )
        }
        if (config.protocol == Protocol.ANTHROPIC) return when (name) {
            "claude-opus-4-7", "claude-opus-4-8" -> ReasoningSupport(
                lowHigh + listOf(
                    ReasoningEffort.XHIGH,
                    ReasoningEffort.MAX
                ), ReasoningMode.CLAUDE_ADAPTIVE
            )

            "claude-opus-4-6", "claude-sonnet-4-6" -> ReasoningSupport(
                lowHigh + ReasoningEffort.MAX,
                ReasoningMode.CLAUDE_ADAPTIVE
            )

            "claude-opus-4-5" -> ReasoningSupport(lowHigh, ReasoningMode.CLAUDE_OPUS45)
            "claude-opus-4", "claude-opus-4-0", "claude-opus-4-1", "claude-sonnet-4", "claude-sonnet-4-0", "claude-sonnet-4-5", "claude-haiku-4-5" -> ReasoningSupport(
                lowHigh,
                ReasoningMode.CLAUDE_MANUAL
            )

            else -> unknown
        }
        return when (name) {
            "gpt-5", "gpt-5-mini", "gpt-5-nano" -> ReasoningSupport(gpt5, ReasoningMode.OPENAI)
            "gpt-5.1" -> ReasoningSupport(gpt51, ReasoningMode.OPENAI)
            "gpt-5.2", "gpt-5.4" -> ReasoningSupport(gpt52, ReasoningMode.OPENAI)
            "gpt-6-luna" -> ReasoningSupport(gpt52 + ReasoningEffort.MAX, ReasoningMode.OPENAI)
            "o1", "o3", "o3-mini", "o4-mini" -> ReasoningSupport(lowHigh, ReasoningMode.OPENAI)
            else -> unknown
        }
    }

    /** Apply after native DTO encoding so new wire fields survive older Koog DTOs. */
    internal fun apply(config: ProviderConfig, body: JsonObject): JsonObject {
        val support = support(config)
        val effort = support.effectiveEffort(config.reasoningEffort) ?: return if (
            config.model.trim().lowercase().removePrefix("deepseek/") == "deepseek-reasoner"
        ) JsonObject(body - "temperature") else body
        val fields = body.toMutableMap()
        val wire = effort.name.lowercase()
        fun thinking(type: String) = buildJsonObject { put("type", type) }
        fun outputEffort() {
            val existing = fields["output_config"] as? JsonObject ?: JsonObject(emptyMap())
            fields["output_config"] = JsonObject(existing + ("effort" to JsonPrimitive(wire)))
        }
        when (support.mode) {
            ReasoningMode.GATEWAY_REASONING -> {
                val existing = fields["reasoning"] as? JsonObject ?: JsonObject(emptyMap())
                fields["reasoning"] = JsonObject(existing + ("effort" to JsonPrimitive(wire)))
                fields.remove("temperature")
            }

            ReasoningMode.OPENAI -> {
                if (config.protocol == Protocol.RESPONSES) {
                    val existing = fields["reasoning"] as? JsonObject ?: JsonObject(emptyMap())
                    fields["reasoning"] = JsonObject(existing + ("effort" to JsonPrimitive(wire)))
                } else fields["reasoning_effort"] = JsonPrimitive(wire)
                // Conservatively omit sampling even for none; model-specific exceptions differ.
                fields.remove("temperature")
            }

            ReasoningMode.DEEPSEEK -> {
                when (config.protocol) {
                    Protocol.RESPONSES -> fields["reasoning"] =
                        buildJsonObject { put("effort", wire) }

                    Protocol.CHAT_COMPLETIONS -> {
                        fields["thinking"] =
                            thinking(if (effort == ReasoningEffort.NONE) "disabled" else "enabled")
                        if (effort != ReasoningEffort.NONE) fields["reasoning_effort"] =
                            JsonPrimitive(wire)
                        else fields.remove("reasoning_effort")
                    }

                    Protocol.ANTHROPIC -> {
                        fields["thinking"] =
                            thinking(if (effort == ReasoningEffort.NONE) "disabled" else "enabled")
                        if (effort != ReasoningEffort.NONE) outputEffort()
                    }
                }
                if (effort != ReasoningEffort.NONE) fields.remove("temperature")
            }

            ReasoningMode.CLAUDE_ADAPTIVE -> {
                fields["thinking"] =
                    thinking(if (effort == ReasoningEffort.NONE) "disabled" else "adaptive")
                if (effort != ReasoningEffort.NONE) {
                    outputEffort(); fields.remove("temperature")
                }
            }

            ReasoningMode.CLAUDE_MANUAL, ReasoningMode.CLAUDE_OPUS45 -> {
                if (effort == ReasoningEffort.NONE) {
                    fields["thinking"] = thinking("disabled")
                    return JsonObject(fields)
                }
                // Read the actual encoded max_tokens: approvals/summaries override connection params.
                val maximum = fields["max_tokens"]?.jsonPrimitive?.intOrNull ?: return body
                if (support.mode == ReasoningMode.CLAUDE_OPUS45) outputEffort()
                if (maximum > 1024) {
                    val target = when (effort) {
                        ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> 1024
                        ReasoningEffort.MEDIUM -> 4096
                        ReasoningEffort.HIGH -> 16384
                        ReasoningEffort.XHIGH -> 32768
                        ReasoningEffort.MAX -> 65536
                    }
                    fields["thinking"] = buildJsonObject {
                        put("type", "enabled"); put(
                        "budget_tokens",
                        minOf(target, maximum - 1)
                    )
                    }
                    fields.remove("temperature")
                }
            }

            ReasoningMode.UNKNOWN -> Unit
        }
        return JsonObject(fields)
    }
}
