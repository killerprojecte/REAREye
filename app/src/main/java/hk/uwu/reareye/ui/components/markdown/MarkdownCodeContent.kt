package hk.uwu.reareye.ui.components.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Preserve code indentation; upstream replaceIndent() also removes meaningful fenced indentation. */
internal fun fencedCodeSource(content: String, node: ASTNode, fallback: String): String {
    val opening = node.children.firstOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_START }
        ?: return fallback
    val lineStart = content.lastIndexOf('\n', opening.startOffset - 1) + 1
    val prefix = content.substring(lineStart, opening.startOffset)
    // Container prefixes (quotes/lists) are already normalized by the upstream extractor.
    if (prefix.any { !it.isWhitespace() }) return fallback
    val start = node.children.firstOrNull { it.type == MarkdownTokenTypes.EOL }?.endOffset
        ?: return fallback
    val closing = node.children.firstOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_END }
    val end = closing?.let { content.lastIndexOf('\n', it.startOffset - 1) + 1 } ?: node.endOffset
    if (end < start) return ""
    val raw = content.substring(start, end).removeSuffix("\n").removeSuffix("\r")
    val indent = prefix.length.coerceAtMost(3)
    return raw.lines().joinToString("\n") { line ->
        line.drop(
            line.takeWhile { it == ' ' }.length.coerceAtMost(indent))
    }
}

@Composable
internal fun MarkdownCodeContent(code: String, language: String?, style: TextStyle) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val normalized = language?.trim()?.substringBefore(' ')?.lowercase()
    // Upstream's async highlight state is keyed only by code. Recreate it for language/theme
    // changes too, and never share its mutable Highlights.Builder between code blocks/jobs.
    key(code, normalized, dark) {
        if (normalized != null && SyntaxLanguage.getByName(normalized) != null) {
            val builder =
                remember { Highlights.Builder().theme(SyntaxThemes.default(darkMode = dark)) }
            MarkdownHighlightedCode(code, normalized, style, builder, showHeader = true)
        } else {
            MarkdownCodeBackground(
                color = LocalMarkdownColors.current.codeBackground,
                shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                showHeader = true, language = language, code = code,
            ) {
                BasicText(
                    code, style = style, modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(LocalMarkdownPadding.current.codeBlock)
                )
            }
        }
    }
}
