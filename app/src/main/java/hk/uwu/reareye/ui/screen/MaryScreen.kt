package hk.uwu.reareye.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ChatEntry
import hk.uwu.reareye.agent.MaryController
import hk.uwu.reareye.agent.data.AgentSessionSummary
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.tools.ReareyeToolEnvironment
import hk.uwu.reareye.ui.components.markdown.MarkdownText
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Send
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
    val controller = remember {
        MaryController(context, scope, tools = ReareyeToolEnvironment(context))
    }
    val uiState by controller.state.collectAsState()

    // No collapsing large title here: the app bar stays a fixed height so content is laid out
    // below it deterministically.
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    // Bumped after any session mutation so the sheet re-reads the store.
    var historyRevision by remember { mutableIntStateOf(0) }
    // Expansion of the collapsible rows, held here so it survives LazyColumn recycling.
    val blocks = rememberBlockExpansion()

    // Auto-follow, but never steal the scroll from the user.
    //
    // Two traps to avoid here. First, an animated scroll per token never settles, so the follow
    // scroll must be instant while text is arriving. Second, `atBottom` cannot be used as the
    // follow condition: a streaming bubble grows continuously, so "the last item's bottom is inside
    // the viewport" goes false the moment a reply gets taller than the remaining space — the loop
    // then disables itself and the view stops following, which is exactly the bug this replaces.
    // So the follow is only cancelled by a real drag upward while already away from the bottom.
    val isAtBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= listState.layoutInfo.totalItemsCount - 1 &&
                last.offset + last.size <= listState.layoutInfo.viewportEndOffset + 4
        }
    }
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                // Guarded by `isAtBottom` so this only fires for the user's own drag: the follow's
                // own scrollToItem momentarily reports progress too, and must not cancel it.
                if (scrolling && !isAtBottom) followTail = false
            }
    }
    LaunchedEffect(isAtBottom) { if (isAtBottom) followTail = true }
    LaunchedEffect(
        uiState.entries.size,
        uiState.entries.lastOrNull(),
        uiState.running,
        // The in-flight reasoning card is its own row and grows token by token, so the follow has
        // to be driven by it too.
        uiState.reasoning.length,
    ) {
        if (!followTail) return@LaunchedEffect

        if (uiState.running) {
            // Pin the *bottom* edge while text streams in.
            //
            // `scrollToItem(index)` aligns the item's top with the viewport top, so once the
            // streaming bubble grows taller than the screen the scroll amount clamps and the view
            // stops moving entirely. Scrolling by the exact leftover distance below the last item
            // keeps the newest line visible instead.
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last != null && last.index == info.totalItemsCount - 1) {
                val leftover = last.offset + last.size - info.viewportEndOffset
                if (leftover > 0) listState.scrollBy(leftover.toFloat())
            }
        } else {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    Scaffold(
        topBar = {
            // The title style follows the screen state:
            //  - landing: a large title. Nothing is scrollable there, so it simply stays expanded.
            //  - conversation: always the small single-line title.
            if (uiState.entries.isEmpty()) {
                TopAppBar(
                    title = stringResource(R.string.mary_title),
                    actions = {
                        TopBarActions(
                            onOpenHistory = { showHistory = true },
                            onOpenSettings = onOpenSettings,
                        )
                    },
                )
            } else {
                SmallTopAppBar(
                    title = stringResource(R.string.mary_title),
                    actions = {
                        TopBarActions(
                            onOpenHistory = { showHistory = true },
                            onOpenSettings = onOpenSettings,
                            onNewChat = { controller.newSession() },
                        )
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            if (uiState.entries.isEmpty()) {
                LandingState(
                    appBarInset = paddingValues.calculateTopPadding(),
                    onPick = { controller.send(it) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scrollEndHaptic()
                        .overScrollVertical(),
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    overscrollEffect = null,
                ) {
                    itemsIndexed(uiState.entries, key = { _, entry -> entry.id }) { index, entry ->
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
                                    is ChatEntry.ToolCall -> ToolCallCard(entry, blocks)
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

            uiState.pendingApproval?.let { pending ->
                ApprovalCard(
                    toolName = pending.request.name,
                    argumentsJson = pending.request.argumentsJson,
                    risk = pending.request.risk,
                    onAllow = { controller.answerApproval(true) },
                    onDeny = { controller.answerApproval(false) },
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    running = uiState.running,
                    onSend = {
                        controller.send(input)
                        input = ""
                    },
                    onStop = { controller.cancel() },
                    bottomPadding = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                )

                // Shown only once the user has scrolled away from the tail, so the state that
                // paused auto-follow is visible and reversible.
                val jumpVisible = !followTail && uiState.entries.isNotEmpty()
                val jumpProgress by animateFloatAsState(
                    targetValue = if (jumpVisible) 1f else 0f,
                    animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
                    label = "jumpToLatest",
                )
                if (jumpProgress > 0.01f) {
                    Surface(
                        onClick = {
                            followTail = true
                            scope.launch {
                                val last = listState.layoutInfo.totalItemsCount - 1
                                if (last >= 0) listState.animateScrollToItem(last)
                            }
                        },
                        shape = CircleShape,
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 3.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (-46).dp)
                            .size(38.dp)
                            .graphicsLayer {
                                alpha = jumpProgress
                                scaleX = 0.85f + 0.15f * jumpProgress
                                scaleY = 0.85f + 0.15f * jumpProgress
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = stringResource(R.string.mary_scroll_to_bottom),
                                tint = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
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
 * Shared top-bar actions for both title styles.
 *
 * "New chat" only makes sense once a conversation exists, so the landing page omits it.
 */
@Composable
private fun RowScope.TopBarActions(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: (() -> Unit)? = null,
) {
    IconButton(onClick = onOpenHistory) {
        Icon(
            imageVector = MiuixIcons.Recent,
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
    val sessions = remember(show, revision) { if (show) load() else emptyList() }
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
 * Deliberately **not scrollable** — it exists to be read once, and a scrollable landing page can
 * be dragged into a half-empty state that looks broken.
 *
 * The content is vertically centred, but the app bar's inset is subtracted first. Without that,
 * centring ignores the space the large title occupies and pushes the brand mark up behind it.
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
                .padding(top = appBarInset),
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
 * animated state, so only genuinely new rows play this.
 */
@Composable
private fun EntryAppearance(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
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
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp))
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
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    // Only Mary's replies are parsed as Markdown: the user's own text is shown
                    // exactly as typed, so a stray `*` cannot silently reformat it.
                    MarkdownText(
                        markdown = text,
                        textColor = MiuixTheme.colorScheme.onSurfaceContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(entry: ChatEntry.ToolCall, blocks: BlockExpansion) {
    // Defaults to collapsed, unlike the reasoning card.
    val expanded = blocks.isExpanded(entry.id, default = false)
    val result = entry.result

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        onClick = { blocks.toggle(entry.id, default = false) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        entry.running -> stringResource(R.string.mary_tool_running)
                        result?.isError == true -> stringResource(R.string.mary_tool_failed)
                        else -> stringResource(R.string.mary_tool_done)
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (result?.isError == true) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
            if (expanded) {
                if (entry.argumentsJson.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = entry.argumentsJson,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                result?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = it.content,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = if (expanded) 40 else 3,
                        overflow = TextOverflow.Ellipsis,
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
private class BlockExpansion {
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
private fun ReasoningCard(text: String, streaming: Boolean, stateKey: String, blocks: BlockExpansion) {
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
            .animateContentSize(animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing)),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        ),
        onClick = { blocks.toggle(stateKey) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.footnote1,
                    lineHeight = 20.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = if (isError) {
                MiuixTheme.colorScheme.errorContainer
            } else {
                MiuixTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (isError) {
                MiuixTheme.colorScheme.onErrorContainer
            } else {
                MiuixTheme.colorScheme.onSurfaceContainer
            },
        ),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Confirmation for a mutating tool. Anything that changes data always lands here. */
@Composable
private fun ApprovalCard(
    toolName: String,
    argumentsJson: String,
    risk: ToolRisk,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainerHighest,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.mary_approval_title),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.mary_approval_body, toolName),
                style = MiuixTheme.textStyles.footnote1,
            )
            if (argumentsJson.isNotBlank()) {
                Text(
                    text = argumentsJson,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.mary_approval_deny))
                }
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.mary_approval_allow))
                }
            }
        }
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

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    bottomPadding: Dp,
) {
    val canSend = value.isNotBlank() && !running
    // Animated so enabling the button reads as a state change rather than a flicker.
    val sendBackground by animateColorAsState(
        targetValue = when {
            running -> MiuixTheme.colorScheme.secondaryContainer
            canSend -> MiuixTheme.colorScheme.primary
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 200),
        label = "sendBackground",
    )
    val sendTint by animateColorAsState(
        targetValue = if (canSend) {
            MiuixTheme.colorScheme.onPrimary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantActions
        },
        animationSpec = tween(durationMillis = 200),
        label = "sendTint",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 28.dp,
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { }, backgroundColor = Color.Transparent) {
                    Icon(
                        imageVector = MiuixIcons.AddCircle,
                        contentDescription = stringResource(R.string.mary_action_attach),
                    )
                }

                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.textFieldColors(
                        backgroundColor = Color.Transparent,
                        borderColor = Color.Transparent,
                    ),
                    // Must be set explicitly: the default cursor brush is SolidColor(borderColor),
                    // and the border above is transparent to remove the field outline — which also
                    // made the caret invisible.
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    label = stringResource(R.string.mary_input_hint),
                    useLabelAsPlaceholder = true,
                    maxLines = 4,
                )

                IconButton(
                    onClick = if (running) onStop else onSend,
                    backgroundColor = sendBackground,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Send,
                        contentDescription = stringResource(
                            if (running) R.string.mary_action_stop else R.string.mary_action_send,
                        ),
                        tint = sendTint,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp + bottomPadding))
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
    is ChatEntry.Notice -> 2
}
