package hk.uwu.mixui.components

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme

@Composable
internal fun currentContentColor(): Color = if (LocalMixUiBackend.current == MixUiBackend.Mochi)
    ui.feice.mochi.theme.LocalContentColor.current else top.yukonga.miuix.kmp.theme.LocalContentColor.current

/** Neutral text contract with renderer-specific simple text and lossless rich-text fallback. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    autoSize: TextAutoSize? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = MixTheme.textStyles.main,
) {
    val contentColor = currentContentColor()
    val resolvedColor = if (color != Color.Unspecified) color else if (style.color != Color.Unspecified) style.color else contentColor
    if (LocalMixUiBackend.current == MixUiBackend.Mochi && autoSize == null && minLines == 1 && onTextLayout == null) {
        ui.feice.mochi.basic.Text(
            text = text, modifier = modifier, color = resolvedColor,
            style = style.merge(fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
                fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
                textAlign = textAlign ?: TextAlign.Unspecified, lineHeight = lineHeight),
            overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        )
    } else {
        top.yukonga.miuix.kmp.basic.Text(
        text = text, modifier = modifier, color = resolvedColor, autoSize = autoSize,
        fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        minLines = minLines, onTextLayout = onTextLayout, style = style,
        )
    }
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    autoSize: TextAutoSize? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = MixTheme.textStyles.main,
) {
    val contentColor = currentContentColor()
    val resolvedColor = if (color != Color.Unspecified) color else if (style.color != Color.Unspecified) style.color else contentColor
    // Mochi does not expose annotated text. Preserve link/layout/inline-content semantics.
    top.yukonga.miuix.kmp.basic.Text(
        text = text, modifier = modifier, color = resolvedColor, autoSize = autoSize,
        fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        minLines = minLines, onTextLayout = onTextLayout, style = style,
        inlineContent = inlineContent,
    )
}
