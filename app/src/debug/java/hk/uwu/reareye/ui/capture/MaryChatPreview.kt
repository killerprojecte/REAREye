package hk.uwu.reareye.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.components.CompactGlassTopBarScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.CapabilityScope
import hk.uwu.reareye.agent.CapabilitySource
import hk.uwu.reareye.agent.ChatEntry
import hk.uwu.reareye.agent.MaryAvailableModel
import hk.uwu.reareye.agent.MaryUiAttachment
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningCapability
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningMapping
import hk.uwu.reareye.agent.ReasoningStatus
import hk.uwu.reareye.ui.screen.MaryConversationList
import hk.uwu.reareye.ui.screen.MaryConversationSidebar
import hk.uwu.reareye.ui.screen.MaryFloatingChatLayout
import hk.uwu.reareye.ui.screen.MaryLandingTranscript
import hk.uwu.reareye.ui.screen.MaryTopBar
import hk.uwu.reareye.ui.screen.UserBubble

private val previewProviders = listOf(
    ProviderConfig(model = "gpt-6.2-sol", protocol = Protocol.RESPONSES, reasoningEffort = ReasoningEffort.XHIGH).let { config ->
        config.copy(reasoningOverride = ReasoningCapability(
            status = ReasoningStatus.SUPPORTED,
            levels = listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                ReasoningEffort.XHIGH, ReasoningEffort.HIGH, ReasoningEffort.MAX).sortedBy { it.ordinal },
            mapping = ReasoningMapping.OPENAI_EFFORT,
            scope = CapabilityScope.from(config), source = CapabilitySource.MANUAL,
        ))
    },
    ProviderConfig(model = "deepseek-v4.1-flash", baseUrl = "https://api.deepseek.com", reasoningEffort = ReasoningEffort.HIGH),
)
private val previewModelOptions = previewProviders.mapIndexed { index, provider ->
    MaryAvailableModel(id = provider.model, name = maryModelDisplayName(provider.model), providerName = if (index == 0) "OpenAI" else "DeepSeek")
}
private val sampleFile = MaryUiAttachment(id = "sample-file", name = "project-notes.md", detail = "Markdown · 4 KB", isImage = false, previewUri = null)

/** Production chat presentation backed by local fixture state instead of picker results or providers. */
@Composable
internal fun MaryChatPreview(importing: Boolean = false, agentMode: Boolean = true) {
    val resources = LocalContext.current.resources
    val sampleImage = remember(resources) {
        val imageId = R.drawable.ic_about_logo_hollow
        MaryUiAttachment(
            id = "sample-image", name = "reference.png", detail = "PNG · 248 KB", isImage = true,
            previewUri = Uri.parse("android.resource://${resources.getResourcePackageName(imageId)}/$imageId"),
        )
    }
    var providers by remember { mutableStateOf(previewProviders) }
    var selected by remember { mutableStateOf(previewModelOptions.first().id) }
    var input by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf(emptyList<MaryUiAttachment>()) }
    var sent by remember { mutableStateOf(emptyList<ChatEntry.UserMessage>()) }
    var showSidebar by remember { mutableStateOf(false) }
    val provider = providers.first { it.model == selected }
    fun attach(file: MaryUiAttachment) {
        if (drafts.none { it.id == file.id }) drafts = drafts + file
    }
    val attachments = MaryComposerAttachments(
        drafts = drafts, importing = importing,
        onChooseFile = { attach(sampleFile) }, onChooseImage = { attach(sampleImage) },
        onRemoveDraft = { id -> drafts = drafts.filterNot { it.id == id } },
    )
    CompactGlassTopBarScene(isContentScrolled = false) { body ->
        Scaffold(topBar = { MaryTopBar(MaryUsageSummary(0, provider.contextWindowTokens, null), { showSidebar = true }) },
            contentWindowInsets = WindowInsets.systemBars) { padding ->
            MaryFloatingChatLayout(padding.calculateBottomPadding(), Modifier.fillMaxSize().then(body),
                content = { bottom ->
                    if (sent.isEmpty()) {
                        MaryLandingTranscript(bottom, padding.calculateTopPadding(), suggestionsEnabled = !importing, onPick = { input = it })
                    } else {
                        MaryConversationList(
                            rememberLazyListState(),
                            PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = bottom, start = 16.dp, end = 16.dp),
                        ) {
                            items(sent, key = { it.id }) { entry -> UserBubble(entry.text, entry.attachments) }
                        }
                    }
                },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    MaryComposerAttachmentStrip(attachments, enabled = true)
                    MaryComposer(
                        value = input, onValueChange = { input = it }, running = false, locked = false, configuring = false,
                        provider = provider, onReasoningSelected = { effort ->
                            providers = providers.map { if (it.model == selected) it.copy(reasoningEffort = effort) else it }
                        },
                        onSend = {
                            sent = sent + ChatEntry.UserMessage(id = "preview-${sent.size}", text = input, attachments = drafts)
                            input = ""
                            drafts = emptyList()
                        },
                        onStop = {},
                        models = MaryComposerModels(selected, maryModelDisplayName(selected), previewModelOptions) { selected = it },
                        attachments = attachments,
                        agentMode = agentMode,
                    )
                }
            }
        }
    }
    MaryConversationSidebar(
        show = showSidebar, revision = 0, currentSessionId = null, hasSession = sent.isNotEmpty(), locked = false,
        load = { emptyList() }, onDismiss = { showSidebar = false }, onNewChat = {},
        onLock = {}, onOpenSettings = {}, onSelect = {}, onDelete = {},
    )
}
