package hk.uwu.reareye.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

@Serializable
enum class ReasoningStatus { UNKNOWN, UNSUPPORTED, SUPPORTED }

@Serializable
enum class ReasoningMapping {
    OPENAI_EFFORT, DEEPSEEK_THINKING, CLAUDE_BUDGET, CLAUDE_ADAPTIVE, OPENROUTER_REASONING;

    val allowedLevels: List<ReasoningEffort>
        get() = when (this) {
            CLAUDE_BUDGET -> listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH
            )

            CLAUDE_ADAPTIVE -> listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX
            )

            DEEPSEEK_THINKING -> listOf(
                ReasoningEffort.NONE,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX
            )

            OPENAI_EFFORT, OPENROUTER_REASONING -> ReasoningEffort.entries.toList()
        }
}

@Serializable
enum class CapabilitySource { MANUAL, SERVICE_METADATA, OFFLINE, UNKNOWN }

/** Local routing identity only. The credential is never retained, only a one-way fingerprint. */
@Serializable
data class CapabilityScope(
    /** One-way endpoint identity, avoiding URL userinfo/query credentials in persisted metadata. */
    val endpoint: String,
    val protocol: Protocol,
    val model: String,
    val credentialFingerprint: String,
) {
    companion object {
        fun credentialTag(apiKey: String): String = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Explicit public view; do not use a plain copy(apiKey = "") for scoped capabilities. */
        fun publicView(config: ProviderConfig): ProviderConfig = config.copy(
            apiKey = "", credentialScopeTag = from(config).credentialFingerprint,
        )

        fun from(config: ProviderConfig) = CapabilityScope(
            credentialTag(config.baseUrl.trim().trimEnd('/')), config.protocol, config.model.trim(),
            if (config.apiKey.isEmpty() && config.credentialScopeTag != null) config.credentialScopeTag
            else credentialTag(config.apiKey),
        )
    }
}

@Serializable
data class ReasoningCapability(
    val status: ReasoningStatus,
    val levels: List<ReasoningEffort> = emptyList(),
    val mapping: ReasoningMapping? = null,
    val scope: CapabilityScope,
    val source: CapabilitySource,
    /** Positive coarse support does not imply any particular adjustable effort level. */
    val supportsReasoning: Boolean? = null,
) {
    fun validFor(config: ProviderConfig): Boolean =
        scope == CapabilityScope.from(config) && when (status) {
            ReasoningStatus.SUPPORTED -> levels.isNotEmpty() && levels.distinct().size == levels.size && mapping != null &&
                    levels.all { it in mapping.allowedLevels } &&
                    (mapping !in setOf(
                        ReasoningMapping.CLAUDE_BUDGET,
                        ReasoningMapping.CLAUDE_ADAPTIVE
                    ) || config.protocol == Protocol.ANTHROPIC) &&
                    (mapping !in setOf(
                        ReasoningMapping.OPENAI_EFFORT,
                        ReasoningMapping.OPENROUTER_REASONING
                    ) || config.protocol != Protocol.ANTHROPIC)

            ReasoningStatus.UNSUPPORTED, ReasoningStatus.UNKNOWN -> levels.isEmpty() && mapping == null
        }
}

data class ModelDescriptor(val id: String, val reasoning: ReasoningCapability? = null)

/** Explicit extension contract; standard /models fields never imply adjustable reasoning levels.
 * reasoning_capabilities: { supported: boolean, levels: ["low", "high"], mapping: "openai_effort" }
 * A positive boolean without exact levels AND a mapping remains unknown. Invalid extensions are ignored.
 */
object ModelCapabilityParser {
    fun parse(item: JsonObject, config: ProviderConfig): ModelDescriptor {
        val id =
            (item["id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                ?: throw IllegalArgumentException("GET models item must contain a nonblank string id")
        val scope = CapabilityScope.from(config.copy(model = id))
        val extension = item["reasoning_capabilities"] as? JsonObject
        val reasoning = extension?.let { metadata ->
            val supported =
                (metadata["supported"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            when (supported) {
                false -> ReasoningCapability(
                    ReasoningStatus.UNSUPPORTED,
                    scope = scope,
                    source = CapabilitySource.SERVICE_METADATA
                )

                true -> {
                    val rawLevels = metadata["levels"] as? JsonArray
                    val levels = rawLevels?.mapNotNull { value ->
                        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { raw ->
                            ReasoningEffort.entries.firstOrNull {
                                it.name.equals(
                                    raw,
                                    ignoreCase = true
                                )
                            }
                        }
                    }
                    val rawMapping =
                        (metadata["mapping"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val mapping = ReasoningMapping.entries.firstOrNull {
                        it.name.equals(
                            rawMapping,
                            ignoreCase = true
                        )
                    }
                    if (rawLevels == null || levels == null || levels.size != rawLevels.size || levels.isEmpty() || levels.distinct().size != levels.size || mapping == null) null
                    else ReasoningCapability(
                        ReasoningStatus.SUPPORTED,
                        levels.sortedBy { it.ordinal },
                        mapping,
                        scope,
                        CapabilitySource.SERVICE_METADATA
                    )
                        .takeIf { it.validFor(config.copy(model = id)) }
                }

                null -> null
            }
        }
        // Contract: https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#discovering-per-model-reasoning-options
        // OpenRouter's documented models[].reasoning.supported_efforts contract: explicit null
        // accepts all gateway efforts; an omitted field does not advertise effort selection.
        // This is distinct from coarse reasoning booleans / supported_parameters.
        val gateway = item["reasoning"] as? JsonObject
        val rawEfforts = gateway?.get("supported_efforts") as? JsonArray
        val efforts = rawEfforts?.mapNotNull { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { raw ->
                ReasoningEffort.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
        }
        val mandatory =
            (gateway?.get("mandatory") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val exactEfforts = when {
            gateway?.get("supported_efforts") == JsonNull -> ReasoningEffort.entries.filter {
                mandatory != true || it != ReasoningEffort.NONE
            }

            rawEfforts != null && efforts != null && efforts.isNotEmpty() &&
                    efforts.size == rawEfforts.size && efforts.distinct().size == efforts.size &&
                    !(mandatory == true && ReasoningEffort.NONE in efforts) -> efforts.sortedBy { it.ordinal }

            else -> null
        }
        val mandatoryValid = gateway?.get("mandatory")
            .let { it == null || (it is JsonPrimitive && !it.isString && it.booleanOrNull != null) }
        val gatewayCapability = exactEfforts?.takeIf { mandatoryValid }?.let {
            ReasoningCapability(
                ReasoningStatus.SUPPORTED, it, ReasoningMapping.OPENROUTER_REASONING,
                scope, CapabilitySource.SERVICE_METADATA, supportsReasoning = true
            ).takeIf { cap -> cap.validFor(config.copy(model = id)) }
        }
        val parameters = item["supported_parameters"] as? JsonArray
        val coarse =
            (item["reasoning"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true ||
                    (parameters != null && parameters.all { it is JsonPrimitive && it.isString } && parameters.any {
                        it.jsonPrimitive.content in setOf(
                            "reasoning",
                            "reasoning_effort",
                            "include_reasoning"
                        )
                    }) || (mandatory == true || efforts?.isNotEmpty() == true) ||
                    ((extension?.get("supported") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true)
        val capability = reasoning ?: gatewayCapability ?: if (coarse) ReasoningCapability(
            ReasoningStatus.UNKNOWN,
            scope = scope,
            source = CapabilitySource.SERVICE_METADATA,
            supportsReasoning = true,
        ) else null
        return ModelDescriptor(id, capability)
    }
}
