package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import okhttp3.OkHttpClient

/** Explicit Responses adapter backed by Koog's native OpenAI client and Responses params. */
internal class OpenAiResponsesProvider(client: OkHttpClient, config: ProviderConfig) :
    LlmProvider by KoogProviderAdapter(client, { config.copy(protocol = Protocol.RESPONSES) }) {
    override val id = "responses"
}
