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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import hk.uwu.reareye.ui.components.motion.ArtShimmerSweep
import hk.uwu.reareye.ui.components.rememberMaryTailFollowState
import hk.uwu.reareye.ui.components.scrollMaryConversationToBottom
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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
    var showSidebar by rememberSaveable { mutableStateOf(false) }
    val menuFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val sidebarGestures = remember { MarySidebarGestureState() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val edgeGesture = Modifier.marySidebarEdgeGesture(
        sidebarGestures,
        beforeOpen = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            menuFocus.requestFocus()
        },
        onOpen = { showSidebar = true },
    )
    // Bumped after a completed deletion so the drawer re-reads the durable store.
    var historyRevision by remember { mutableIntStateOf(0) }
    // Expansion of the collapsible rows, held here so it survives LazyColumn recycling.
    val blocks = rememberBlockExpansion()
    val toolExpansion = remember(controller.sessionId) { ToolCardExpansion() }
    // A row plays its entrance only when it is genuinely new. Deciding that from the transcript, not
    // from the row's own composition, is what stops history from replaying its entrance every time it
    // is scrolled back into view. Deliberately not snapshot state: rows read it once, and it must not
    // invalidate them when it changes.
    val knownEntryIds = remember(controller.sessionId) { TranscriptEntryIds() }
    LaunchedEffect(controller.sessionId, uiState.entries) {
        knownEntryIds.remember(uiState.entries.asSequence().map { it.id })
    }
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
            MaryTopBar(
                summary = MaryUsageSummary(
                    uiState.estimatedInputTokens.takeIf { uiState.entries.any { it is ChatEntry.UserMessage } },
                    uiState.contextWindowTokens, uiState.usage,
                ),
                onOpenMenu = { showSidebar = true },
                menuFocusRequester = menuFocus,
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
                            .then(edgeGesture)
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
                            .then(edgeGesture)
                            .scrollEndHaptic()
                            .overScrollVertical(),
                        contentPadding = PaddingValues(
                            top = paddingValues.calculateTopPadding() + 8.dp,
                            bottom = contentPaddingBottom,
                            start = 16.dp,
                            end = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaryRowSpacing),
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
                                Spacer(modifier = Modifier.height(if (startsTurn && index > 0) MaryTurnSpacing else 0.dp))
                                EntryAppearance(new = knownEntryIds.isNew(entry.id)) {
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

                        // In-flight chain of thought, shown while the model is still thinking. Also
                        // shown with no text yet, so a run that has produced nothing still says so.
                        //
                        // Once the first answer token arrives the buffer is handed to the
                        // AssistantMessage above, which renders it in the same place — so this row and
                        // that card swap without the text jumping.
                        if (uiState.reasoning.isNotBlank() ||
                            showsPendingThinking(
                                uiState.running,
                                uiState.reasoning,
                                uiState.entries
                            )
                        ) {
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

    MaryConversationSidebar(
        show = showSidebar,
        gestureState = sidebarGestures,
        hasSession = controller.sessionId != null,
        locked = uiState.locked,
        onNewChat = { controller.newSession(); showSidebar = false },
        onLock = { controller.lockSession(); showSidebar = false },
        onOpenSettings = { showSidebar = false; onOpenSettings() },
        revision = historyRevision,
        currentSessionId = controller.sessionId,
        load = { controller.sessions() },
        onDismiss = { showSidebar = false },
        onClosed = { menuFocus.requestFocus() },
        onSelect = { id ->
            controller.loadSession(id)
            showSidebar = false
        },
        onDelete = { id ->
            scope.launch {
                controller.deleteSession(id).join()
                historyRevision++
            }
        },
    )
}

/** Only navigation and context belong in the top bar; session actions live in the drawer. */
@Composable
internal fun MaryTopBar(
    summary: MaryUsageSummary,
    onOpenMenu: () -> Unit,
    menuModifier: Modifier = Modifier,
    menuFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val menuFocus = menuFocusRequester ?: remember { androidx.compose.ui.focus.FocusRequester() }
    SmallTopAppBar(
        title = stringResource(R.string.mary_title),
        navigationIcon = {
            IconButton(onClick = {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                // Restore keyboard navigation here, not to the composer, after the dialog closes.
                menuFocus.requestFocus()
                onOpenMenu()
            }, modifier = menuModifier.focusRequester(menuFocus).size(48.dp).testTag("mary-open-sidebar")) {
                Icon(Icons.Outlined.Menu, stringResource(R.string.mary_sidebar_open))
            }
        },
        actions = { ContextUsageButton(summary) },
    )
}

/** Space between two consecutive rows of one turn. */
private val MaryRowSpacing = 8.dp

/** Extra space above the first row of a new turn, so a speaker change reads without decoration. */
private val MaryTurnSpacing = 16.dp

/**
 * Space between the last row and the composer.
 *
 * Deliberately the same as a turn break: a message in the middle of a conversation gets [MaryRowSpacing]
 * plus [MaryTurnSpacing] on each side of it, so the newest row must get the same room below as above.
 * A smaller gap made a freshly sent message read as crowding the composer.
 */
private val MaryComposerSpacing = MaryRowSpacing + MaryTurnSpacing

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
    val contentPaddingBottom =
        with(density) { footerHeightPx.toDp() } + footerBottom + MaryComposerSpacing

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
 * Ids already present in the transcript.
 *
 * A row plays its entrance animation only when it is genuinely new. Asking the transcript rather than
 * the row's own composition is what keeps scrolling back through history from replaying the entrance:
 * a row revealed by scrolling was already added here, so it starts fully shown.
 */
@Stable
internal class TranscriptEntryIds {
    private val known = mutableSetOf<String>()

    fun isNew(id: String): Boolean = id !in known

    fun remember(ids: Sequence<String>) {
        ids.forEach { known.add(it) }
    }
}

/**
 * Fades and lifts a row as it first appears.
 *
 * Applied per row rather than via `LazyItemScope.animateItem`, because that animates placement and
 * would also fire for every offset shift while a turn streams in.
 *
 * `new` comes from the transcript, not from this composition, and only decides the first frame: a row
 * that was already in the transcript starts fully shown, so scrolling back through history does not
 * replay the entrance. Labels follow the saveable flag afterwards, so recycling a row cannot either.
 */
@Composable
internal fun EntryAppearance(new: Boolean, content: @Composable () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(!new) }
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

        // A row can carry reasoning only: the thought that preceded a tool call keeps its own place
        // in the transcript, and must not draw an empty speech bubble under itself.
        if (text.isNotEmpty() || streaming) {
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
 * The reasoning header's label: "Thinking…" while the model is still working, "Thought process" after.
 *
 * A single-line, hug-width label so the caller can sweep a highlight across exactly the words.
 */
@Composable
private fun ReasoningHeaderLabel(streaming: Boolean) {
    Text(
        text = stringResource(if (streaming) R.string.mary_thinking else R.string.mary_thought),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

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
                // The sweep hugs the label instead of the weighted row, so the shine lands on the words
                // rather than sweeping across empty width.
                if (streaming) {
                    ArtShimmerSweep {
                        ReasoningHeaderLabel(true)
                    }
                } else {
                    ReasoningHeaderLabel(false)
                }
                Spacer(modifier = Modifier.weight(1f))
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

            if (expanded && text.isNotBlank()) {
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
    return remember(page0, page1) { listOf(page0, page1) }
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

/**
 * Whether a running turn has produced nothing to show yet.
 *
 * The transcript must say the agent is working even before the first token, otherwise that window
 * renders nothing at all. It steps aside as soon as the tail already carries live output of its own —
 * reasoning text, a streaming answer, or a tool that is still running — because that row is then the
 * better indicator. A finished row is not live output, so a turn that moves on past one still shows.
 */
internal fun showsPendingThinking(
    running: Boolean,
    reasoning: String,
    entries: List<ChatEntry>,
): Boolean {
    if (!running || reasoning.isNotBlank()) return false
    return when (val last = entries.lastOrNull()) {
        is ChatEntry.AssistantMessage -> !last.streaming
        is ChatEntry.ToolCall -> !last.running
        else -> true
    }
}
