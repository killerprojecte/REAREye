package hk.uwu.reareye.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.agent.ModelDescriptor
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.data.MaryModelCapability
import hk.uwu.reareye.agent.data.MaryModelConfig
import hk.uwu.reareye.agent.data.MaryModelValueSource
import hk.uwu.reareye.agent.data.MaryProviderConfig
import hk.uwu.reareye.agent.data.MarySettings

/** Hosts the production settings UI in memory for screenshot and interaction tests. */
@Composable
internal fun MarySettingsPreview(
    bottomInnerPadding: Dp = 0.dp,
    onBack: () -> Unit = {},
    initialTab: MarySettingsTab = MarySettingsTab.AGENT,
    fixture: MarySettingsDraft = remember { maryPreviewDraft() },
    candidates: Map<String, List<MaryModelCandidate>> = maryPreviewCandidates,
    modelCatalog: MarySettingsModelCatalog? = null,
) {
    var draft by remember(fixture) { mutableStateOf(fixture) }
    val discovery = remember(candidates) {
        object : MarySettingsDiscovery {
            override suspend fun candidates(source: MaryProviderDraft) = candidates[source.id].orEmpty()
            override suspend fun test(source: MaryProviderDraft, model: hk.uwu.reareye.ui.components.ModelDraft) = Unit
            override suspend fun context(source: MaryProviderDraft, selected: MaryModelCandidate) = selected.context
        }
    }
    MarySettingsContent(
        draft = draft, onChange = { draft = it }, discovery = discovery,
        bottomInnerPadding = bottomInnerPadding, onBack = onBack, initialTab = initialTab,
        initialCandidates = candidates,
        modelCatalog = modelCatalog,
    )
}

private const val PREVIEW_MAIN_ID = "main"
private const val PREVIEW_APPROVAL_ID = "approval"

private val maryPreviewCandidates = mapOf(
    PREVIEW_MAIN_ID to listOf(MaryModelCandidate(
        ModelDescriptor("gpt-6.2-sol"), "GPT 6.2 Sol", context = 256_000, output = 32_000,
        capabilities = setOf(MaryModelCapability.TEXT, MaryModelCapability.IMAGE, MaryModelCapability.TOOLS),
    )),
    PREVIEW_APPROVAL_ID to listOf(MaryModelCandidate(
        ModelDescriptor("deepseek-v4.1-flash"), "DeepSeek v4.1 Flash", context = 128_000, output = 16_384,
        capabilities = setOf(MaryModelCapability.TEXT, MaryModelCapability.TOOLS),
    )),
)

private fun maryPreviewDraft(): MarySettingsDraft {
    val settings = MarySettings(
        mainModelId = PREVIEW_MAIN_ID, approvalModelId = PREVIEW_APPROVAL_ID, subagentModelId = PREVIEW_APPROVAL_ID,
        providers = listOf(
            MaryProviderConfig(PREVIEW_MAIN_ID, "OpenAI", "https://openai.example/v1", Protocol.RESPONSES, modelCount = 1),
            MaryProviderConfig(PREVIEW_APPROVAL_ID, "DeepSeek", "https://deepseek.example/v1", Protocol.CHAT_COMPLETIONS, modelCount = 1),
        ),
        models = maryPreviewCandidates.map { (id, options) ->
            val model = options.single()
            MaryModelConfig(id, id, model.details.id, model.displayName,
                contextWindowTokens = requireNotNull(model.context), contextSource = MaryModelValueSource.PROVIDER,
                maxOutputTokens = model.output, outputSource = MaryModelValueSource.PROVIDER, capabilities = model.capabilities)
        },
    ).projectLegacy()
    return MarySettingsDraft.from(settings)
}
