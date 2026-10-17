package hk.uwu.reareye.ui.screen

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ChatEntry
import hk.uwu.reareye.agent.MaryViewModel
import hk.uwu.reareye.agent.data.AgentSessionSummary
import hk.uwu.reareye.agent.pendingInteractionToolRowId
import hk.uwu.reareye.agent.voice.rememberMaryVoiceInput
import hk.uwu.reareye.ui.components.ContextUsageButton
import hk.uwu.reareye.ui.components.MaryComposer
import hk.uwu.reareye.ui.components.MaryFollowConversationTail
import hk.uwu.reareye.ui.components.MaryQuestionCard
import hk.uwu.reareye.ui.components.MaryScrollToLatest
import hk.uwu.reareye.ui.components.MaryToolCallCard
import hk.uwu.reareye.ui.components.MaryUsageSummary
import hk.uwu.reareye.ui.components.ToolCardExpansion
import hk.uwu.reareye.ui.components.markdown.MarkdownText
import hk.uwu.reareye.ui.components.maryErrorColors
import hk.uwu.reareye.ui.components.rememberMaryTailFollowState
import hk.uwu.reareye.ui.components.scrollMaryConversationToBottom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mary — the in-app agent.
 *
 * Shows the empty-chat landing state until the conversation has content, then a message list with
 * streaming replies, tool-call cards and the approval prompt for anything that mutates data.
 */
@Composable
fun MaryScreen(
    bottomInnerPadding: Dp = 0.dp,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = remember(context) {
        val activity = generateSequence<Context>(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().first()
        ViewModelProvider(activity)[MaryViewModel::class.java]
    }
    val controller = owner.controller
    val uiState by controller.state.collectAsState()

    // Deliberately no MiuixScrollBehavior here. A collapsing large title changes the bar's measured
    // height, which the Scaffold reports as the top padding this screen feeds into the list's
    // contentPadding — so every row would shift by the collapse distance while scrolling. A fixed
    // bar keeps that padding constant.
    LaunchedEffect(controller) { controller.refreshConfiguration() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val voice = rememberMaryVoiceInput()
    LaunchedEffect(voice.error) {
        val message = voice.error ?: return@LaunchedEffect
        Toast.makeText(
            context,
            context.getString(R.string.mary_voice_failed, message),
            Toast.LENGTH_SHORT,
        ).show()
        voice.clearError()
    }
    var showHistory by remember { mutableStateOf(false) }
    // Bumped after any session mutation so the sheet re-reads the store.
    var historyRevision by remember { mutableIntStateOf(0) }
    // Expansion of the collapsible rows, held here so it survives LazyColumn recycling.
    val blocks = rememberBlockExpansion()
    val toolExpansion = remember(controller.sessionId) { ToolCardExpansion() }
    val askToolRowId = pendingInteractionToolRowId(uiState.entries, uiState.pendingAsk?.toolCallId)
    val approvalToolRowId =
        pendingInteractionToolRowId(uiState.entries, uiState.pendingApproval?.request?.id)

    val tailFollow = rememberMaryTailFollowState(listState, controller.sessionId)
    MaryFollowConversationTail(
        listState, following = tailFollow.following, sessionKey = controller.sessionId,
        canFollow = { tailFollow.following },
    )

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.mary_title),
                navigationIcon = {
                    MarySessionLockButton(
                        hasSession = controller.sessionId != null,
                        locked = uiState.locked,
                        onLock = { controller.lockSession() },
                    )
                },
                actions = {
                    TopBarActions(
                        summary = MaryUsageSummary(
                            uiState.estimatedInputTokens.takeIf { uiState.entries.any { it is ChatEntry.UserMessage } },
                            uiState.contextWindowTokens, uiState.usage
                        ),
                        onOpenHistory = { showHistory = true },
                        onOpenSettings = onOpenSettings,
                        onNewChat = if (uiState.entries.isNotEmpty()) ({ controller.newSession() }) else null,
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { paddingValues ->
        MaryFloatingChatLayout(
            bottomInset = maxOf(bottomInnerPadding, paddingValues.calculateBottomPadding()),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(tailFollow.connection),
            content = { contentPaddingBottom ->
                if (uiState.entries.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = contentPaddingBottom),
                    ) {
                        LandingState(
                            appBarInset = paddingValues.calculateTopPadding(),
                            onPick = { controller.send(it) },
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical(),
                        contentPadding = PaddingValues(
                            top = paddingValues.calculateTopPadding() + 8.dp,
                            bottom = contentPaddingBottom,
                            start = 16.dp,
                            end = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        overscrollEffect = null,
                    ) {
                        itemsIndexed(
                            uiState.entries,
                            key = { _, entry -> entry.id }) { index, entry ->
                            Column {
                                // A new speaker gets a wider gap than a continuation, which is what
                                // makes a turn boundary readable without any extra decoration.
                                val previous = uiState.entries.getOrNull(index - 1)
                                val startsTurn = previous == null ||
                                        speakerOf(previous) != speakerOf(entry)
                                Spacer(modifier = Modifier.height(if (startsTurn && index > 0) 16.dp else 0.dp))
                                EntryAppearance {
                                    when (entry) {
                                        is ChatEntry.UserMessage -> UserBubble(entry.text)
                                        is ChatEntry.AssistantMessage -> AssistantBubble(
                                            text = entry.text,
                                            streaming = entry.streaming,
                                            reasoning = entry.reasoning,
                                            blocks = blocks,
                                            stateKey = entry.id,
                                        )

                                        is ChatEntry.ToolCall -> MaryToolCallCard(
                                            entry = entry,
                                            expansion = toolExpansion,
                                            pendingAsk = uiState.pendingAsk?.takeIf { entry.id == askToolRowId },
                                            pendingApproval = uiState.pendingApproval?.takeIf { entry.id == approvalToolRowId },
                                            onAnswer = { id, selected, custom ->
                                                controller.answerAsk(
                                                    id,
                                                    selected,
                                                    custom
                                                )
                                            },
                                            onApprove = { id, allowed ->
                                                controller.answerApproval(
                                                    allowed,
                                                    interactionId = id
                                                )
                                            },
                                        )

                                        is ChatEntry.Question -> MaryQuestionCard(
                                            entry = entry,
                                            expansion = toolExpansion,
                                            pending = uiState.pendingAsk?.id == entry.request.id,
                                            onAnswer = { id, selected, custom ->
                                                controller.answerAsk(
                                                    id,
                                                    selected,
                                                    custom
                                                )
                                            },
                                        )

                                        is ChatEntry.Notice -> NoticeCard(entry.text, entry.isError)
                                    }
                                }
                            }
                        }

                        // In-flight chain of thought, shown while the model is still thinking.
                        //
                        // Once the first answer token arrives the buffer is handed to the
                        // AssistantMessage above, which renders it in the same place — so this row and
                        // that card swap without the text jumping. Without this the thinking phase
                        // renders nothing at all.
                        if (uiState.reasoning.isNotBlank()) {
                            item(key = "reasoning_live") {
                                ReasoningCard(
                                    text = uiState.reasoning,
                                    streaming = true,
                                    stateKey = "reasoning_live",
                                    blocks = blocks,
                                )
                            }
                        }
                    }
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                MaryComposer(
                    value = input,
                    onValueChange = { input = it },
                    running = uiState.running,
                    locked = uiState.locked,
                    configuring = uiState.configuring,
                    provider = uiState.provider,
                    onReasoningSelected = { controller.setReasoningEffort(it) },
                    approvalMode = uiState.approvalMode,
                    onApprovalModeSelected = { controller.setApprovalMode(it) },
                    onSend = {
                        controller.send(input)
                        input = ""
                    },
                    onStop = { controller.cancel() },
                    voicePhase = voice.phase,
                    onStartVoice = { voice.start(input) { input = it } },
                    onInterruptVoice = { voice.interrupt() },
                )

                MaryScrollToLatest(
                    awayFromBottom = !tailFollow.following && uiState.entries.isNotEmpty(),
                    scrolling = listState.isScrollInProgress,
                    sessionKey = controller.sessionId,
                    onClick = {
                        scope.launch {
                            scrollMaryConversationToBottom(listState)
                            tailFollow.followLatest()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-56).dp),
                )
            }
        }
    }

    ConversationHistorySheet(
        show = showHistory,
        revision = historyRevision,
        currentSessionId = controller.sessionId,
        load = { controller.sessions() },
        onDismiss = { showHistory = false },
        onSelect = { id ->
            controller.loadSession(id)
            showHistory = false
        },
        onDelete = { id ->
            controller.deleteSession(id)
            historyRevision++
        },
    )
}

/**
 * The transcript never resizes for the composer or IME: they float over its full viewport.
 * Only the transparent footer avoids the larger of the keyboard and the fixed navigation bar.
 * Scroll padding keeps the final message reachable above the composer, without creating a dock.
 */
@Composable
internal fun MaryFloatingChatLayout(
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (contentPaddingBottom: Dp) -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var footerHeightPx by remember { mutableIntStateOf(0) }
    // Union, not sum: when the keyboard covers navigation the composer only clears the
    // keyboard. Apply this to the footer alone, never the transcript or app navigation.
    val bottomObstruction = WindowInsets.ime.union(WindowInsets(bottom = bottomInset))
    val footerBottom = with(density) { bottomObstruction.getBottom(this).toDp() } + 8.dp
    val contentPaddingBottom = with(density) { footerHeightPx.toDp() } + footerBottom + 12.dp

    Box(modifier = modifier) {
        content(contentPaddingBottom)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(bottomObstruction)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .fillMaxWidth(),
        ) {
            // Keep the measured composer/tools separate from the navigation gap, so scroll
            // padding follows the animated composer height without reserving the gap twice.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { footerHeightPx = it.height },
                content = footer,
            )
        }
    }
}

@Composable
internal fun MarySessionLockButton(hasSession: Boolean, locked: Boolean, onLock: () -> Unit) {
    IconButton(
        onClick = onLock,
        enabled = hasSession && !locked,
        modifier = Modifier
            .size(44.dp)
            .testTag("mary-session-lock")
            .semantics {
                if (!hasSession || locked) disabled()
            },
    ) {
        Icon(
            imageVector = if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            contentDescription = stringResource(if (locked) R.string.mary_session_locked else R.string.mary_session_lock),
            tint = if (hasSession) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .size(24.dp)
                .testTag("mary-session-lock-icon"),
        )
    }
}
/**
 * Shared top-bar actions for both title styles.
 *
 * "New chat" only makes sense once a conversation exists, so the landing page omits it.
 */
@Composable
private fun RowScope.TopBarActions(
    summary: MaryUsageSummary,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: (() -> Unit)? = null,
) {
    ContextUsageButton(summary)
    IconButton(onClick = onOpenHistory) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = stringResource(R.string.mary_history_title),
        )
    }
    IconButton(onClick = onOpenSettings) {
        Icon(
            imageVector = MiuixIcons.Settings,
            contentDescription = stringResource(R.string.mary_settings_title),
        )
    }
    onNewChat?.let { action ->
        IconButton(onClick = action) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = stringResource(R.string.mary_action_new_chat),
            )
        }
    }
}

/**
 * Lists stored conversations so they can be reopened.
 *
 * The store keeps one JSON file per conversation; this sheet is the only way to reach anything
 * other than the most recent one.
 */
@Composable
private fun ConversationHistorySheet(
    show: Boolean,
    revision: Int,
    currentSessionId: String?,
    load: () -> List<AgentSessionSummary>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Re-read whenever the sheet opens or a session is deleted.
    var sessions by remember { mutableStateOf(emptyList<AgentSessionSummary>()) }
    LaunchedEffect(show, revision) {
        sessions = if (show) withContext(Dispatchers.IO) { load() } else emptyList()
    }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.mary_history_title),
        onDismissRequest = onDismiss,
    ) {
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.mary_history_empty),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
            return@OverlayBottomSheet
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sessions, key = { it.id }) { item ->
                val isCurrent = item.id == currentSessionId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = CardDefaults.defaultColors(
                        color = if (isCurrent) {
                            MiuixTheme.colorScheme.primaryContainer
                        } else {
                            MiuixTheme.colorScheme.surfaceContainer
                        },
                        contentColor = if (isCurrent) {
                            MiuixTheme.colorScheme.onPrimaryContainer
                        } else {
                            MiuixTheme.colorScheme.onSurfaceContainer
                        },
                    ),
                    onClick = { onSelect(item.id) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MiuixTheme.textStyles.body1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(
                                    R.string.mary_history_meta,
                                    formatter.format(Date(item.updatedAt)),
                                    item.messageCount,
                                ),
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (isCurrent) {
                                    MiuixTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                        IconButton(onClick = { onDelete(item.id) }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = stringResource(R.string.mary_history_delete),
                                tint = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty-chat state: brand mark, greeting and rotating suggestions.
 *
 * A real scroll container drives the same collapsing title as the conversation and remains
 * usable when the IME leaves too little space for the centred suggestions.
 */
@Composable
private fun ColumnScope.LandingState(
    appBarInset: Dp,
    onPick: (String) -> Unit,
) {
    val suggestionPages = rememberMarySuggestionPages()
    var suggestionPage by remember { mutableIntStateOf(0) }
    val suggestions = suggestionPages[suggestionPage % suggestionPages.size]

    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        // Shrink the gaps on short screens instead of overflowing.
        val tight = maxHeight < 620.dp
        val gapLarge = if (tight) 16.dp else 24.dp
        val gapMedium = if (tight) 8.dp else 12.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = appBarInset, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MaryBrandMark()

            Spacer(modifier = Modifier.height(gapLarge))

            Text(
                text = stringResource(R.string.mary_greeting),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.mary_greeting_subtitle),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(if (tight) 18.dp else 26.dp))

            suggestions.forEach { suggestion ->
                SuggestionCard(text = suggestion, onClick = { onPick(suggestion) })
                Spacer(modifier = Modifier.height(gapMedium))
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { suggestionPage = (suggestionPage + 1) % suggestionPages.size }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.mary_shuffle),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * Fades and lifts a row as it first appears.
 *
 * Applied per row rather than via `LazyItemScope.animateItem`, because that animates placement and
 * would also fire for every offset shift while a turn streams in. An already-present row keeps its
 * saveable state across LazyColumn recycling, so only genuinely new rows play this.
 */
@Composable
internal fun EntryAppearance(content: @Composable () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        label = "entryAppearance",
    )
    LaunchedEffect(Unit) { shown = true }

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 14.dp.toPx()
        },
    ) {
        content()
    }
}

/**
 * A message from the user: right-aligned, capped in width, with the corner nearest the sender
 * squared off so the bubble reads as coming from that side.
 */
@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Card(
            // Capped rather than a fixed 85%: a short "好的" should not become a wide slab.
            modifier = Modifier.widthIn(max = 300.dp),
            cornerRadius = 18.dp,
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.primary,
                contentColor = MiuixTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onPrimary,
                // Dense CJK text at the default line height is what makes a bubble feel cramped.
                lineHeight = 24.sp,
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomEnd = 6.dp,
                            bottomStart = 18.dp
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * A reply from Mary: left-aligned and allowed to use the full width.
 *
 * The chain of thought renders above the text, inside the same bubble, so it reads as part of this
 * reply rather than as a separate item in the transcript.
 */
@Composable
private fun AssistantBubble(
    text: String,
    streaming: Boolean,
    reasoning: String,
    blocks: BlockExpansion,
    stateKey: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (reasoning.isNotBlank()) {
            ReasoningCard(
                text = reasoning,
                streaming = streaming,
                stateKey = stateKey,
                blocks = blocks,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Card(
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
                ),
                cornerRadius = 18.dp,
            ) {
                if (text.isEmpty() && streaming) {
                    Text(
                        text = "…",
                        style = MiuixTheme.textStyles.body1,
                        lineHeight = 24.sp,
                        modifier = Modifier
                            .clip(
                                RoundedCornerShape(
                                    topStart = 6.dp,
                                    topEnd = 18.dp,
                                    bottomEnd = 18.dp,
                                    bottomStart = 18.dp
                                )
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    // Only Mary's replies are parsed as Markdown: the user's own text is shown
                    // exactly as typed, so a stray `*` cannot silently reformat it.
                    MarkdownText(
                        markdown = text,
                        textColor = MiuixTheme.colorScheme.onSurfaceContainer,
                        modifier = Modifier
                            .clip(
                                RoundedCornerShape(
                                    topStart = 6.dp,
                                    topEnd = 18.dp,
                                    bottomEnd = 18.dp,
                                    bottomStart = 18.dp
                                )
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}


/**
 * Collapsible display of the model's chain of thought.
 *
 * Expanded while the model is still thinking, then collapsed once the answer starts arriving so it
 * does not push the reply off screen. Tapping toggles it manually.
 */
/**
 * Expansion state for the collapsible blocks, held outside the list.
 *
 * `remember` inside a LazyColumn item is destroyed when the row is recycled, so a card scrolled out
 * of view and back came back expanded — and the collapse-on-finish could never fire again, because
 * the re-created row had no memory of the block ever having streamed.
 *
 * Both facts live here instead, keyed by row id: the current expansion, and whether the block has
 * been seen streaming. That second one is what makes the automatic collapse work after recycling.
 */
@Stable
internal class BlockExpansion {
    private val expanded = mutableStateMapOf<String, Boolean>()
    private val everStreamed = mutableStateMapOf<String, Boolean>()
    private val handToggled = mutableStateMapOf<String, Boolean>()

    fun isExpanded(key: String, default: Boolean = true): Boolean = expanded[key] ?: default

    fun onStreaming(key: String) {
        everStreamed[key] = true
        expanded[key] = true
    }

    /** Collapses a finished block, unless the user opened it by hand. */
    fun onFinished(key: String) {
        if (everStreamed[key] == true && handToggled[key] != true) expanded[key] = false
    }

    fun toggle(key: String, default: Boolean = true) {
        handToggled[key] = true
        expanded[key] = !isExpanded(key, default)
    }
}

@Composable
private fun rememberBlockExpansion(): BlockExpansion = remember { BlockExpansion() }

/**
 * The reasoning header icon while the model is still thinking.
 *
 * A separate composable so the infinite transition only exists while it is actually needed.
 */
@Composable
private fun ThinkingPulseIcon() {
    val pulse = rememberInfiniteTransition(label = "thinkingPulse")
    val glow by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingGlow",
    )
    Icon(
        imageVector = MiuixIcons.More,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = glow),
        modifier = Modifier.size(15.dp),
    )
}

@Composable
internal fun ReasoningCard(
    text: String,
    streaming: Boolean,
    stateKey: String,
    blocks: BlockExpansion
) {
    val expanded = blocks.isExpanded(stateKey)

    LaunchedEffect(streaming) {
        if (streaming) blocks.onStreaming(stateKey) else blocks.onFinished(stateKey)
    }

    // Re-created rows (scrolled back into view) still need the finish transition applied, because
    // their LaunchedEffect runs again with streaming already false.
    LaunchedEffect(stateKey) {
        if (!streaming) blocks.onFinished(stateKey)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Animates the height when the body is shown or hidden, instead of snapping.
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = 220,
                    easing = LinearOutSlowInEasing
                )
            ),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mary-reasoning-header-$stateKey")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = androidx.compose.ui.semantics.Role.Button
                    ) { blocks.toggle(stateKey) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Split into two branches rather than alpha-ing a running animation: an infinite
                // transition keeps recomposing for as long as it exists, and every collapsed
                // reasoning card in the transcript would otherwise carry one forever.
                if (streaming) {
                    ThinkingPulseIcon()
                } else {
                    Icon(
                        imageVector = MiuixIcons.More,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (streaming) R.string.mary_thinking else R.string.mary_thought,
                    ),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                val chevron by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
                    label = "chevronRotation",
                )
                Icon(
                    imageVector = MiuixIcons.ExpandMore,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = chevron },
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownText(
                    markdown = text,
                    modifier = Modifier.testTag("mary-reasoning-markdown-$stateKey"),
                    textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
internal fun NoticeCard(text: String, isError: Boolean) {
    val errorColors = maryErrorColors()
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = if (isError) {
                errorColors.container
            } else {
                MiuixTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (isError) {
                errorColors.content
            } else {
                MiuixTheme.colorScheme.onSurfaceContainer
            },
        ),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (isError) errorColors.content else MiuixTheme.colorScheme.onSurfaceContainer,
        )
    }
}



@Composable
private fun MaryBrandMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFFFF5C93))
            .semantics { contentDescription = "mary_brand_mark" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun SuggestionCard(text: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        cornerRadius = 20.dp,
        onClick = onClick,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
        )
    }
}


/** Suggestion prompts split into pages; the shuffle control cycles between them. */
@Composable
private fun rememberMarySuggestionPages(): List<List<String>> {
    val page0 = listOf(
        stringResource(R.string.mary_suggestion_0_0),
        stringResource(R.string.mary_suggestion_0_1),
        stringResource(R.string.mary_suggestion_0_2),
    )
    val page1 = listOf(
        stringResource(R.string.mary_suggestion_1_0),
        stringResource(R.string.mary_suggestion_1_1),
        stringResource(R.string.mary_suggestion_1_2),
    )
    val page2 = listOf(
        stringResource(R.string.mary_suggestion_2_0),
        stringResource(R.string.mary_suggestion_2_1),
        stringResource(R.string.mary_suggestion_2_2),
    )
    return remember(page0, page1, page2) { listOf(page0, page1, page2) }
}

/**
 * Groups consecutive rows by who "said" them, so spacing can be tighter inside one turn and wider
 * between turns. Tool cards and notices belong to Mary's side.
 */
private fun speakerOf(entry: ChatEntry): Int = when (entry) {
    is ChatEntry.UserMessage -> 0
    is ChatEntry.AssistantMessage -> 1
    is ChatEntry.ToolCall -> 1
    is ChatEntry.Question -> 1
    is ChatEntry.Notice -> 2
}
