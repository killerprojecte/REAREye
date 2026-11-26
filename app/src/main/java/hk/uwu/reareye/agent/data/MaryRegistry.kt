package hk.uwu.reareye.agent.data

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningCapability
import hk.uwu.reareye.agent.ReasoningEffort
import kotlinx.serialization.Serializable
import java.util.UUID

internal const val LEGACY_DIALOGUE_PROVIDER_ID = "legacy-dialogue-provider"
internal const val LEGACY_APPROVAL_PROVIDER_ID = "legacy-approval-provider"
internal const val LEGACY_DIALOGUE_MODEL_ID = "legacy-dialogue-model"
internal const val LEGACY_APPROVAL_MODEL_ID = "legacy-approval-model"

internal fun newMaryRegistryId(): String = UUID.randomUUID().toString()

/** A reusable connection. API keys are not part of this serializable record. */
@Serializable
data class MaryProviderConfig(
    val id: String,
    val displayName: String = "",
    val baseUrl: String = ProviderConfig.DEFAULT_BASE_URL,
    val protocol: Protocol = Protocol.CHAT_COMPLETIONS,
    val modelCount: Int? = null,
) {
    internal fun connection(key: String = "") = ProviderConfig(baseUrl = baseUrl, protocol = protocol, apiKey = key)
}

@Serializable
enum class MaryModelCapability { TEXT, IMAGE, AUDIO, TOOLS }

@Serializable
enum class MaryModelValueSource { DEFAULT, MANUAL, PROVIDER, CATALOG }

/** A model ID chooses a registry entry; [modelId] is the provider's API identifier. */
@Serializable
data class MaryModelConfig(
    val id: String,
    val providerId: String,
    val modelId: String,
    val displayName: String = "",
    val manualDisplayName: Boolean = displayName.isNotBlank(),
    val contextWindowTokens: Int = 128_000,
    val contextSource: MaryModelValueSource = MaryModelValueSource.MANUAL,
    val maxOutputTokens: Int? = null,
    val outputSource: MaryModelValueSource = MaryModelValueSource.MANUAL,
    val temperature: Double? = null,
    val capabilities: Set<MaryModelCapability> = emptySet(),
    val manualCapabilities: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val reasoningOverride: ReasoningCapability? = null,
    val reasoningMetadata: ReasoningCapability? = null,
) {
    val name: String get() = displayName.trim().ifEmpty { modelId }

    internal fun resolve(connection: ProviderConfig) = connection.copy(
        model = modelId, contextWindowTokens = contextWindowTokens, maxOutputTokens = maxOutputTokens,
        temperature = temperature, reasoningEffort = reasoningEffort, reasoningOverride = reasoningOverride,
        reasoningMetadata = reasoningMetadata,
        supportsImageInput = MaryModelCapability.IMAGE in capabilities,
    )

    internal fun withLegacyProjection(config: ProviderConfig): MaryModelConfig = copy(
        modelId = config.model,
        contextWindowTokens = config.contextWindowTokens,
        contextSource = if (contextWindowTokens == config.contextWindowTokens) contextSource else MaryModelValueSource.MANUAL,
        maxOutputTokens = config.maxOutputTokens,
        outputSource = if (maxOutputTokens == config.maxOutputTokens) outputSource else MaryModelValueSource.MANUAL,
        temperature = config.temperature,
        capabilities = if (config.supportsImageInput) capabilities + MaryModelCapability.IMAGE else capabilities - MaryModelCapability.IMAGE,
        reasoningEffort = config.reasoningEffort,
        reasoningOverride = config.reasoningOverride,
        reasoningMetadata = config.reasoningMetadata,
    )
}

@Serializable
internal data class MaryRegistrySnapshot(
    val providers: List<MaryProviderConfig> = emptyList(),
    val models: List<MaryModelConfig> = emptyList(),
    val agentMode: Boolean = true,
    val mainModelId: String? = null,
    val approvalModelId: String? = null,
    val subagentModelId: String? = null,
) {
    fun validate() {
        val providerIds = providers.map { it.id }.toSet()
        val modelIds = models.map { it.id }.toSet()
        require(providerIds.size == providers.size && providerIds.all(validRegistryId::matches)) { "Invalid provider IDs" }
        require(modelIds.size == models.size && modelIds.all(validRegistryId::matches)) { "Invalid model IDs" }
        require(models.all { it.providerId in providerIds }) { "A model references a missing provider" }
        require(listOfNotNull(mainModelId, approvalModelId, subagentModelId).all { it in modelIds }) {
            "An agent role references a missing model"
        }
    }
}

private val validRegistryId = Regex("[a-zA-Z0-9_-]{1,80}")

internal fun MarySettings.registrySnapshot() = MaryRegistrySnapshot(providers, models, agentMode, mainModelId, approvalModelId, subagentModelId)

internal fun MarySettings.withRegistry(registry: MaryRegistrySnapshot) = copy(
    providers = registry.providers, models = registry.models, agentMode = registry.agentMode,
    mainModelId = registry.mainModelId, approvalModelId = registry.approvalModelId, subagentModelId = registry.subagentModelId,
)

internal fun legacyRegistry(dialogue: ProviderConfig, independentApproval: ProviderConfig?): MaryRegistrySnapshot = MaryRegistrySnapshot(
    providers = buildList {
        add(MaryProviderConfig(LEGACY_DIALOGUE_PROVIDER_ID, baseUrl = dialogue.baseUrl, protocol = dialogue.protocol))
        independentApproval?.let {
            add(MaryProviderConfig(LEGACY_APPROVAL_PROVIDER_ID, baseUrl = it.baseUrl, protocol = it.protocol))
        }
    },
    models = buildList {
        add(MaryModelConfig(LEGACY_DIALOGUE_MODEL_ID, LEGACY_DIALOGUE_PROVIDER_ID, dialogue.model).withLegacyProjection(dialogue))
        independentApproval?.let {
            add(MaryModelConfig(LEGACY_APPROVAL_MODEL_ID, LEGACY_APPROVAL_PROVIDER_ID, it.model).withLegacyProjection(it))
        }
    },
    mainModelId = LEGACY_DIALOGUE_MODEL_ID,
    approvalModelId = independentApproval?.let { LEGACY_APPROVAL_MODEL_ID },
)

/** Earlier migration registered the default independent approval connection even when it was not used. */
internal fun MaryRegistrySnapshot.withoutUnusedApprovalPlaceholder(
    inheritApprovalModel: Boolean,
    hasApprovalCredential: Boolean,
): MaryRegistrySnapshot {
    if (!inheritApprovalModel || hasApprovalCredential ||
        LEGACY_APPROVAL_MODEL_ID in listOf(mainModelId, subagentModelId)) return this
    val provider = MaryProviderConfig(LEGACY_APPROVAL_PROVIDER_ID)
    val model = MaryModelConfig(LEGACY_APPROVAL_MODEL_ID, provider.id, "")
    if (providers.firstOrNull { it.id == provider.id } != provider ||
        models.filter { it.providerId == provider.id }.singleOrNull() != model) return this
    return copy(
        providers = providers.filterNot { it.id == provider.id },
        models = models.filterNot { it.id == model.id },
        approvalModelId = approvalModelId?.takeUnless { it == model.id },
    )
}
