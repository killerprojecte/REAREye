package hk.uwu.reareye.ui.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ChatEntry
import hk.uwu.reareye.agent.MaryController
import hk.uwu.reareye.agent.data.AgentSessionSummary
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.tools.ReareyeToolEnvironment
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
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Mic
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

    // Keep the newest content in view while a turn streams in.
    LaunchedEffect(uiState.entries.size, uiState.running) {
        val last = uiState.entries.lastIndex
        if (last >= 0) listState.animateScrollToItem(last)
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    overscrollEffect = null,
                ) {
                    items(uiState.entries, key = { it.id }) { entry ->
                        when (entry) {
                            is ChatEntry.UserMessage -> UserBubble(entry.text)
                            is ChatEntry.AssistantMessage -> AssistantBubble(entry.text, entry.streaming)
                            is ChatEntry.ToolCall -> ToolCallCard(entry)
                            is ChatEntry.Notice -> NoticeCard(entry.text, entry.isError)
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

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            cornerRadius = 20.dp,
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.primary,
                contentColor = MiuixTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String, streaming: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
        ) {
            Text(
                text = text.ifEmpty { if (streaming) "…" else "" },
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ToolCallCard(entry: ChatEntry.ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    val result = entry.result

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        onClick = { expanded = !expanded },
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
                    label = stringResource(R.string.mary_input_hint),
                    useLabelAsPlaceholder = true,
                    maxLines = 4,
                )

                IconButton(
                    onClick = if (running) onStop else onSend,
                    backgroundColor = if (running) {
                        MiuixTheme.colorScheme.secondaryContainer
                    } else if (canSend) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ) {
                    Icon(
                        imageVector = MiuixIcons.Send,
                        contentDescription = stringResource(
                            if (running) R.string.mary_action_stop else R.string.mary_action_send,
                        ),
                        tint = if (canSend) {
                            MiuixTheme.colorScheme.onPrimary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
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
