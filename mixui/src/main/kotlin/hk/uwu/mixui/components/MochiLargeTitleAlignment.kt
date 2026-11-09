package hk.uwu.mixui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isSpecified
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.LocalTextStyle
import ui.feice.mochi.theme.MochiTextStyles
import ui.feice.mochi.theme.MochiTheme

/**
 * Mochi 0.2.0 has no title-padding slot. For the app's LTR titles, use its public
 * typography API to align the paragraph to MIUIX's 26dp title inset, without
 * changing the native 16dp body/search padding, scroll state or compact title.
 *
 * A paragraph indent is direction-sensitive. RTL UI/RTL-leading titles retain
 * native paragraph direction rather than forcing textDirection or reordering text.
 * Large titles use MIUIX's 32sp/normal typography in both layout directions.
 * Explicit caller paragraph alignment/indentation is also left intact. Remove
 * this compatibility adapter when Mochi exposes a title layout modifier.
 */
@Composable
internal fun MochiLargeTitleAlignment(
    title: String,
    content: @Composable (MochiTitleContentStyle) -> Unit,
) {
    val styles = MochiTheme.textStyles
    val caller = MochiTitleContentStyle(styles, LocalTextStyle.current, LocalContentColor.current)
    val style = styles.largeTitle.copy(fontSize = 32.sp, fontWeight = FontWeight.Normal)
    val direction = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 1)
    val hasDefaultParagraph = (style.textIndent == null || style.textIndent == TextIndent.None) &&
        (style.textAlign == TextAlign.Unspecified || style.textAlign == TextAlign.Start || style.textAlign == TextAlign.Left)
    val align = direction == LayoutDirection.Ltr && title.isNotEmpty() && hasDefaultParagraph &&
        textMeasurer.measure(title, style).getParagraphDirection(0) == ResolvedTextDirection.Ltr
    if (!align) {
        MochiTypography(styles.copy(largeTitle = style)) { content(caller) }
        return
    }
    val indent = with(LocalDensity.current) {
        // mochiOpticalLeading translates the layer by -0.04 × original font px,
        // even when the native TextAutoSize subsequently shrinks a long title.
        val opticalPx = if (style.fontSize.isSpecified) style.fontSize.toPx() * 0.04f else 0f
        (10.dp.toPx() + opticalPx).toSp()
    }
    MochiTypography(styles.copy(largeTitle = style.copy(textIndent = TextIndent(indent, indent)))) {
        content(caller)
    }
}

/** Restore only the caller's typography; retain native backdrop/shadow/list locals. */
@OptIn(ExperimentalFoundationApi::class)
internal class MochiTitleContentStyle(
    private val styles: MochiTextStyles,
    private val textStyle: TextStyle,
    private val contentColor: Color,
) {
    @Composable
    fun Restore(content: @Composable () -> Unit) {
        MochiTypography(styles) {
            CompositionLocalProvider(
                LocalTextStyle provides textStyle,
                LocalContentColor provides contentColor,
                content = content,
            )
        }
    }

    fun lazyScope(scope: LazyListScope): LazyListScope = object : LazyListScope by scope {
        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            scope.item(key, contentType) {
                val itemScope = this
                Restore { content(itemScope) }
            }
        }

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            scope.items(count, key, contentType) { index ->
                val itemScope = this
                Restore { itemContent(itemScope, index) }
            }
        }

        override fun stickyHeader(
            key: Any?,
            contentType: Any?,
            content: @Composable LazyItemScope.(Int) -> Unit,
        ) {
            scope.stickyHeader(key, contentType) { index ->
                val itemScope = this
                Restore { content(itemScope, index) }
            }
        }
    }
}

@Composable
private fun MochiTypography(styles: MochiTextStyles, content: @Composable () -> Unit) {
    MochiTheme(
        colors = MochiTheme.colorScheme,
        textStyles = styles,
        shapes = MochiTheme.shapes,
        motionScheme = MochiTheme.motionScheme,
        materials = MochiTheme.materials,
        shadows = MochiTheme.shadows,
        systemBarAppearance = false,
        content = content,
    )
}
