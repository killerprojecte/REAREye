package hk.uwu.reareye.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Mary — the on-device agent landing page.
 *
 * UI only for now: it renders the empty-chat state (brand mark, greeting, rotating
 * suggestion prompts and the composer) and deliberately wires no backend yet.
 */
@Composable
fun MaryScreen(
    bottomInnerPadding: Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val suggestionPages = rememberMarySuggestionPages()

    var suggestionPage by remember { mutableIntStateOf(0) }
    val suggestions = suggestionPages[suggestionPage % suggestionPages.size]

    var input by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.mary_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { }) {
                        Icon(
                            imageVector = MiuixIcons.Recent,
                            contentDescription = stringResource(R.string.mary_action_history),
                        )
                    }
                    IconButton(onClick = { }) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = stringResource(R.string.mary_action_new_chat),
                        )
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(
                    modifier = Modifier.height(
                        (paddingValues.calculateTopPadding() * 0.5f).coerceAtLeast(20.dp)
                    )
                )

                MaryBrandMark()

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.mary_greeting),
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.mary_greeting_subtitle),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(28.dp))

                suggestions.forEach { suggestion ->
                    SuggestionCard(
                        text = suggestion,
                        onClick = { input = suggestion },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            suggestionPage = (suggestionPage + 1) % suggestionPages.size
                        }
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

                Spacer(modifier = Modifier.height(20.dp))
            }

            CapabilityRow()

            Spacer(modifier = Modifier.height(10.dp))

            Composer(
                value = input,
                onValueChange = { input = it },
                onSend = { input = "" },
                bottomPadding = paddingValues.calculateBottomPadding() + bottomInnerPadding,
            )
        }
    }
}

/**
 * The Mary brand mark: a gradient squircle with the sparkle glyph knocked out of it in the
 * page colour, matching the reference design. A plain Box avoids shipping a bespoke drawable.
 */
@Composable
private fun MaryBrandMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFFFF5C93)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "M",
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SuggestionCard(
    text: String,
    onClick: () -> Unit,
) {
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
private fun CapabilityRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapabilityChip(
            icon = MiuixIcons.Search,
            label = stringResource(R.string.mary_capability_scope),
        )
        CapabilityChip(
            icon = MiuixIcons.Recent,
            label = stringResource(R.string.mary_capability_insight),
        )
        CapabilityChip(
            icon = MiuixIcons.Mic,
            label = stringResource(R.string.mary_capability_reminder),
        )
        CapabilityChip(
            icon = MiuixIcons.Image,
            label = stringResource(R.string.mary_capability_review),
        )
    }
}

@Composable
private fun CapabilityChip(
    icon: ImageVector,
    label: String,
) {
    Card(
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceContainer,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    bottomPadding: Dp,
) {
    val canSend = value.isNotBlank()

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
                IconButton(
                    onClick = { },
                    backgroundColor = Color.Transparent,
                ) {
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
                    onClick = onSend,
                    enabled = canSend,
                    backgroundColor = if (canSend) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ) {
                    Icon(
                        imageVector = MiuixIcons.Send,
                        contentDescription = stringResource(R.string.mary_action_send),
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

/**
 * Suggestion prompts split into pages; the shuffle control cycles between them.
 */
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
