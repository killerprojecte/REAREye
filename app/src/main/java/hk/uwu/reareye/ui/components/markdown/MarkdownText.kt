package hk.uwu.reareye.ui.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.agent.markdown.MarkdownBlock
import hk.uwu.reareye.agent.markdown.MarkdownInline
import hk.uwu.reareye.agent.markdown.MarkdownParser
import hk.uwu.reareye.agent.markdown.TableAlignment
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Renders Mary's replies as formatted text instead of raw Markdown.
 *
 * The parsing happens in `:agent-engine` (and is unit tested there); this file only maps the parsed
 * blocks onto MIUIX components, so the app keeps a single design language instead of pulling in a
 * second one. See [MarkdownParser] for the supported syntax.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
) {
    val document = remember(markdown) { MarkdownParser.parse(markdown) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        document.blocks.forEach { block ->
            MarkdownBlockView(block, textColor)
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, textColor: Color) {
    when (block) {
        is MarkdownBlock.Heading -> HeadingView(block, textColor)
        is MarkdownBlock.Paragraph -> ParagraphView(block.content, textColor)
        is MarkdownBlock.BulletList -> ListView(block.items, ordered = false, textColor = textColor)
        is MarkdownBlock.OrderedList ->
            ListView(block.items, ordered = true, start = block.start, textColor = textColor)
        is MarkdownBlock.CodeBlock -> CodeBlockView(block)
        is MarkdownBlock.Table -> TableView(block, textColor)
        is MarkdownBlock.Quote -> QuoteView(block, textColor)
        MarkdownBlock.Divider -> DividerView()
    }
}

@Composable
private fun HeadingView(block: MarkdownBlock.Heading, textColor: Color) {
    // MIUIX exposes a limited set of text styles, so headings are differentiated by size and
    // weight rather than by a full type scale.
    val style = when (block.level) {
        1 -> MiuixTheme.textStyles.title4
        2 -> MiuixTheme.textStyles.title4
        else -> MiuixTheme.textStyles.body1
    }
    Text(
        text = block.content.toAnnotatedString(textColor),
        style = style,
        fontWeight = FontWeight.Bold,
        color = textColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (block.level <= 2) 4.dp else 0.dp),
    )
}

@Composable
private fun ParagraphView(content: List<MarkdownInline>, textColor: Color) {
    if (content.isEmpty()) return
    Text(
        text = content.toAnnotatedString(textColor),
        style = MiuixTheme.textStyles.body1,
        color = textColor,
        lineHeight = 24.sp,
    )
}

@Composable
private fun ListView(
    items: List<List<MarkdownBlock>>,
    ordered: Boolean,
    start: Int = 1,
    textColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, itemBlocks ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (ordered) "${start + index}." else "•",
                    style = MiuixTheme.textStyles.body1,
                    color = textColor,
                    modifier = Modifier.widthIn(min = 20.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemBlocks.forEach { MarkdownBlockView(it, textColor) }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(10.dp),
    ) {
        block.language?.let { language ->
            Text(
                text = language,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        // Code must not wrap: a wrapped JSON line is harder to read than a scrollable one.
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = block.code,
                style = MiuixTheme.textStyles.footnote1,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
        }
    }
}

@Composable
private fun TableView(block: MarkdownBlock.Table, textColor: Color) {
    val columns = maxOf(
        block.header.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (columns == 0) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        TableRow(
            cells = List(columns) { i -> block.header.getOrNull(i).orEmpty() },
            alignments = block.alignments,
            textColor = textColor,
            header = true,
        )
        block.rows.forEach { row ->
            DividerView()
            TableRow(
                cells = List(columns) { i -> row.getOrNull(i).orEmpty() },
                alignments = block.alignments,
                textColor = textColor,
                header = false,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<MarkdownInline>>,
    alignments: List<TableAlignment>,
    textColor: Color,
    header: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEachIndexed { index, cell ->
            val alignment = alignments.getOrNull(index) ?: TableAlignment.START
            Text(
                text = cell.toAnnotatedString(textColor),
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                textAlign = when (alignment) {
                    TableAlignment.START -> TextAlign.Start
                    TableAlignment.CENTER -> TextAlign.Center
                    TableAlignment.END -> TextAlign.End
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun QuoteView(block: MarkdownBlock.Quote, textColor: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 16.dp)
                .background(MiuixTheme.colorScheme.primary),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            block.blocks.forEach { child ->
                MarkdownBlockView(child, MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun DividerView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.12f)),
    )
}

/** Flattens parsed inline spans into an [AnnotatedString], resolving links to the URI handler. */
@Composable
private fun List<MarkdownInline>.toAnnotatedString(textColor: Color): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    val linkColor = MiuixTheme.colorScheme.primary
    // A malformed or unopenable URL must not crash the message list.
    val openLink: (String) -> Unit = rememberUpdatedState<(String) -> Unit> { url ->
        runCatching { uriHandler.openUri(url) }
        Unit
    }.value

    return remember(this, linkColor, textColor) {
        buildAnnotatedString {
            appendInlines(this@toAnnotatedString, linkColor, openLink)
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    linkColor: Color,
    openLink: (String) -> Unit,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.value)
            is MarkdownInline.LineBreak -> append('\n')
            is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(inline.content, linkColor, openLink)
            }
            is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.content, linkColor, openLink)
            }
            is MarkdownInline.Strikethrough ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlines(inline.content, linkColor, openLink)
                }
            is MarkdownInline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = linkColor.copy(alpha = 0.12f),
                ),
            ) {
                append(inline.value)
            }
            is MarkdownInline.Link -> withLink(
                LinkAnnotation.Url(
                    url = inline.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                    linkInteractionListener = { openLink(inline.url) },
                ),
            ) {
                appendInlines(inline.text, linkColor, openLink)
            }
        }
    }
}
