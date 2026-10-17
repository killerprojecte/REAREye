package hk.uwu.reareye.ui.components.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownA11yLabels
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import hk.uwu.reareye.R
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Library-owned GFM parsing/rendering, with MIUIX styling rather than a Material theme. */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
) {
    // Mary supplies full snapshots, including replacements when changing sessions. An append-only
    // state would duplicate or retain text in that case. Parsing stays off the composition thread;
    // retainState keeps the previous frame visible while streamed snapshots are being parsed.
    val state = rememberMarkdownState(content = markdown, retainState = true)
    val colors = miuixMarkdownColors(textColor)
    val typography = miuixMarkdownTypography(textColor)
    val imageTransformer = rememberMarkdownImageTransformer()
    val uriHandler = LocalUriHandler.current
    val safeUriHandler = remember(uriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                // Unsupported/malformed model-generated links must not crash the conversation.
                runCatching { uriHandler.openUri(uri) }
            }
        }
    }

    val labels = MarkdownA11yLabels(
        copyCode = stringResource(R.string.mary_markdown_copy_code),
        codeFallbackLanguage = stringResource(R.string.mary_markdown_code),
    )
    CompositionLocalProvider(
        LocalUriHandler provides safeUriHandler,
        LocalMarkdownA11yLabels provides labels
    ) {
        Markdown(
            markdownState = state,
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            typography = typography,
            imageTransformer = imageTransformer,
            annotator = remember {
                markdownAnnotator { content, node ->
                    val inCode =
                        generateSequence(node.parent) { it.parent }.any { it.type == MarkdownElementTypes.CODE_SPAN }
                    if (node.type == MarkdownTokenTypes.TEXT && !inCode) {
                        append(decodeMarkdownText(node.getTextInNode(content).toString()))
                        true
                    } else false
                }
            },
            padding = markdownPadding(
                block = 3.dp,
                listIndent = 16.dp,
                codeBlock = PaddingValues(10.dp),
            ),
            dimens = markdownDimens(
                codeBackgroundCornerSize = 10.dp,
                tableCornerSize = 10.dp,
                tableCellPadding = 10.dp,
                blockQuoteThickness = 3.dp,
            ),
            // Layout is already animated/scrolled by the chat; do not animate every token update.
            animations = markdownAnimations(animateTextSize = { this }),
            components = markdownComponents(
                checkbox = { MarkdownTaskCheckbox(it) },
                paragraph = { model ->
                    MarkdownParagraph(
                        model.content,
                        model.node,
                        modifier = Modifier.padding(vertical = 3.dp),
                        style = model.typography.paragraph
                    )
                },
                image = { MarkdownRemoteImage(it) },
                inlineImage = { MarkdownRemoteInlineImage(it, markdown) },
                codeFence = { model ->
                    MarkdownCodeFence(
                        model.content,
                        model.node,
                        model.typography.code
                    ) { code, language, style ->
                        MarkdownCodeContent(
                            fencedCodeSource(model.content, model.node, code),
                            language,
                            style
                        )
                    }
                },
                codeBlock = { model ->
                    MarkdownCodeBlock(
                        model.content,
                        model.node,
                        model.typography.code
                    ) { code, language, style ->
                        MarkdownCodeContent(code, language, style)
                    }
                },
                table = { model ->
                    // Keep the library's scrolling/semantics/inline rendering, but do not silently
                    // truncate AI-generated instructions to the default single line per cell.
                    val alignments = remember(model.content, model.node) {
                        tableAlignments(
                            model.content,
                            model.node
                        )
                    }
                    MarkdownTable(
                        content = model.content,
                        node = model.node,
                        style = model.typography.table,
                        headerBlock = { content, header, width, style ->
                            MarkdownAlignedTableRow(
                                content,
                                header,
                                width,
                                style,
                                alignments,
                                isHeader = true
                            )
                        },
                        rowBlock = { content, row, width, style ->
                            MarkdownAlignedTableRow(content, row, width, style, alignments)
                        },
                    )
                },
            ),
            loading = { fallbackModifier ->
                Text(
                    markdown,
                    modifier = fallbackModifier,
                    style = typography.paragraph,
                    color = textColor
                )
            },
            error = { fallbackModifier ->
                Text(
                    markdown,
                    modifier = fallbackModifier,
                    style = typography.paragraph,
                    color = textColor
                )
            },
        )
    }
}

@Composable
internal fun miuixMarkdownColors(textColor: Color): DefaultMarkdownColors {
    val scheme = MiuixTheme.colorScheme
    return DefaultMarkdownColors(
        text = textColor,
        codeBackground = scheme.surfaceContainerHigh,
        inlineCodeBackground = scheme.primary.copy(alpha = 0.12f),
        dividerColor = textColor.copy(alpha = 0.12f),
        tableBackground = scheme.surfaceContainerHigh,
        highlightText = scheme.onPrimary,
        highlightBackground = scheme.primary,
    )
}

@Composable
internal fun miuixMarkdownTypography(textColor: Color): DefaultMarkdownTypography {
    val styles = MiuixTheme.textStyles
    val body = styles.body1.copy(color = textColor, lineHeight = 24.sp)
    val heading = styles.title4.copy(color = textColor, fontWeight = FontWeight.Bold)
    val code = styles.footnote1.copy(color = textColor, fontFamily = FontFamily.Monospace)
    return DefaultMarkdownTypography(
        h1 = heading,
        h2 = heading,
        h3 = body.copy(fontWeight = FontWeight.Bold),
        h4 = body.copy(fontWeight = FontWeight.Bold),
        h5 = body.copy(fontWeight = FontWeight.Bold),
        h6 = body.copy(fontWeight = FontWeight.Bold),
        text = body,
        code = code,
        inlineCode = body.copy(fontFamily = FontFamily.Monospace),
        quote = body.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = MiuixTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
        ),
        table = styles.footnote1.copy(color = textColor, lineHeight = 22.sp),
    )
}
