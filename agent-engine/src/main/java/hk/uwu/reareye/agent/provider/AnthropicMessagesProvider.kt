package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import okhttp3.OkHttpClient

/** Explicit Anthropic Messages adapter backed by Koog's native Anthropic client. */
internal class AnthropicMessagesProvider(client: OkHttpClient, config: ProviderConfig) :
    LlmProvider by KoogProviderAdapter(client, { config.copy(protocol = Protocol.ANTHROPIC) }) {
    override val id = "anthropic"
}
