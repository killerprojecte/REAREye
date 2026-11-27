package hk.uwu.reareye.agent.data

import hk.uwu.reareye.agent.CapabilityScope
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import kotlinx.serialization.Serializable

/** Scope for native/signed continuation state, deliberately without keys or raw endpoint URLs. */
@Serializable
data class MaryTranscriptModel(
    val providerId: String?,
    val protocol: Protocol,
    val apiModelId: String,
    val endpoint: String,
) {
    internal companion object {
        fun of(providerId: String?, config: ProviderConfig) = MaryTranscriptModel(
            providerId, config.protocol, config.model, CapabilityScope.from(config).endpoint,
        )
    }
}

internal fun List<ChatMessage>.forModel(from: MaryTranscriptModel?, to: MaryTranscriptModel): List<ChatMessage> {
    if (from == to) return this
    return mapNotNull { message ->
        if (message.role == MessageRole.ASSISTANT && message.content.isNullOrBlank() && message.toolCalls.isEmpty()) null
        else message.copy(protocolItems = emptyList(), nativeEncoding = null)
    }
}
