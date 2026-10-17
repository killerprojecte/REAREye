package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Explicit Chat Completions adapter backed by Koog's native OpenAI client. */
class OpenAiCompatibleProvider(
    client: OkHttpClient,
    configProvider: () -> ProviderConfig,
    @Suppress("UNUSED_PARAMETER") json: Json = DEFAULT_JSON,
) : LlmProvider by KoogProviderAdapter(
    client,
    { configProvider().copy(protocol = Protocol.CHAT_COMPLETIONS) }) {
    override val id = "openai-compatible"
    companion object {
        val DEFAULT_JSON =
            Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
    }
}
