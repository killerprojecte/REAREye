package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import okhttp3.OkHttpClient

/** Dispatch at collection time so settings changes never leave a stale protocol behind. */
object ProviderFactory {
    /** One native connection per turn; callers must close it after graph execution. */
    fun connect(client: OkHttpClient, config: ProviderConfig): KoogConnection =
        nativeConnection(client, config).connection

    fun create(
        client: OkHttpClient = OpenAiCompatibleProvider.defaultClient(),
        configProvider: () -> ProviderConfig,
    ): LlmProvider = object : LlmProvider {
        override val id = "native-protocol"
        private fun provider(config: ProviderConfig): LlmProvider = createForConfig(client, config)
        override fun chat(request: ChatRequest) = kotlinx.coroutines.flow.flow {
            provider(configProvider()).chat(request).collect { emit(it) }
        }

        override suspend fun listModels() = provider(configProvider()).listModels()
        override suspend fun listModelDetails() =
            KoogProviderAdapter(client, configProvider).listModelDetails()

        override suspend fun modelContextWindow(model: String) =
            provider(configProvider()).modelContextWindow(model)
    }

    internal fun createForConfig(client: OkHttpClient, config: ProviderConfig): LlmProvider =
        when (config.protocol) {
            Protocol.CHAT_COMPLETIONS -> OpenAiCompatibleProvider(client, { config })
            Protocol.RESPONSES -> OpenAiResponsesProvider(client, config)
            Protocol.ANTHROPIC -> AnthropicMessagesProvider(client, config)
        }
}
