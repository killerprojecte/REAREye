package hk.uwu.reareye.ui.components.markdown

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Render contracts, not a second parser or an AST snapshot. Every fixture contains a transformation
 * that the temporary raw loading/error fallback cannot satisfy. No math/mermaid/footnote contract.
 *
 * These expectations deliberately retain GFM table alignment even if the upstream renderer does
 * not implement it yet. Run this class with the app's Robolectric suite before claiming support.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownSyntaxRenderTest {
    @get:Rule
    val screenshotName = org.junit.rules.TestName()

    @get:Rule
    val compose = createEmptyComposeRule()

    private var activity: ActivityController<ComponentActivity>? = null

    @After
    fun tearDown() {
        try {
            if (activity != null) exportMarkdownScreenshot(
                compose,
                "syntax-${screenshotName.methodName}"
            )
        } finally {
            compose.runOnUiThread { activity?.close() }
        }
    }

    @Test
    fun atxH1ThroughH6AndSetextHaveHeadingSemanticsAndBoldTypography() {
        val atx = (1..6).joinToString("\n\n") { "${"#".repeat(it)} ATX-$it" }
        render("$atx\n\nSetext-one\n==========\n\nSetext-two\n----------")
        val headings = (1..6).map { "ATX-$it" } + listOf("Setext-one", "Setext-two")
        headings.forEach { text ->
            awaitText(text)
            compose.onNodeWithText(text, useUnmergedTree = true)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            assertEquals(FontWeight.Bold, layout(text).layoutInput.style.fontWeight)
        }
    }

    @Test
    fun softBreakAndBothHardBreakFormsPreserveParagraphStructure() {
        render("soft alpha\nsoft beta\n\nspace alpha  \nspace beta\n\nslash alpha\\\nslash beta\n\n**break sentinel**")
        awaitText("break sentinel")
        // CommonMark permits a soft break to render as either one space or a line ending.
        val soft = layout("soft alpha", substring = true).layoutInput.text.text
        assertTrue(soft == "soft alpha soft beta" || soft == "soft alpha\nsoft beta")
        listOf("space alpha\nspace beta", "slash alpha\nslash beta").forEach { expected ->
            awaitText(expected)
            val result = layout(expected)
            assertEquals(2, result.lineCount)
            assertEquals(1, result.getLineForOffset(expected.indexOf('\n') + 1))
        }
        assertBold(layout("break sentinel").layoutInput.text, "break sentinel")
    }

    @Test
    fun inlineCodeKeepsLiteralMarkupAndUsesMonospaceSpan() {
        render("prefix `a < b &amp; *literal*` suffix\n\n**code sentinel**")
        awaitText("code sentinel")
        val text = layout("prefix", substring = true).layoutInput.text
        val literal = "a < b &amp; *literal*"
        val start = text.text.indexOf(literal)
        assertTrue("Code must not decode entities or interpret emphasis", start >= 0)
        assertTrue(text.spanStyles.any {
            it.start <= start && it.end >= start + literal.length && it.item.fontFamily == FontFamily.Monospace
        })
        assertFalse(text.text.contains('`'))
    }

    @Test
    fun escapesAndNamedNumericEntitiesBecomePlainCharactersNotEmphasis() {
        render("\\*literal\\* &amp; &lt;tag&gt; &#35; &#x41;\n\n**entity sentinel**")
        val expected = "*literal* & <tag> # A"
        awaitText(expected)
        val text = layout(expected).layoutInput.text
        assertEquals(expected, text.text)
        assertFalse(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        awaitText("entity sentinel")
    }

    @Test
    fun unicodeInvalidAndEscapedEntitiesDecodeOnceWithoutChangingMarkdownSyntax() {
        render("&#x1F600; &#128512; &#0; &#xD800; &#x110000; &#38;amp; \\&amp; &bogus;\n\n**unicode sentinel**")
        awaitText("unicode sentinel")
        awaitText("😀 😀 � � � &amp; &amp; &bogus;")
    }

    @Test
    fun referenceAutolinkAndEmailRenderClickableResolvedDestinations() {
        val opened = mutableListOf<String>()
        val links = linkedMapOf(
            "Full reference" to "https://example.com/full",
            "Collapsed" to "https://example.com/collapsed",
            "Shortcut" to "https://example.com/shortcut",
            "https://example.com/angle" to "https://example.com/angle",
            "https://example.com/bare" to "https://example.com/bare",
            "reader@example.com" to "mailto:reader@example.com",
        )
        setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }) {
                MarkdownText(
                    "[Full reference][target]\n\n[Collapsed][]\n\n[Shortcut]\n\n" +
                            "<https://example.com/angle>\n\nhttps://example.com/bare\n\n" +
                            "<reader@example.com>\n\n[target]: https://example.com/full\n" +
                            "[Collapsed]: https://example.com/collapsed\n" +
                            "[Shortcut]: https://example.com/shortcut"
                )
            }
        }
        links.forEach { (label, target) ->
            awaitText(label)
            val result = layout(label)
            val annotations = result.layoutInput.text.getLinkAnnotations(0, label.length)
            assertEquals(listOf(target), annotations.map { (it.item as LinkAnnotation.Url).url })
            assertEquals(0, annotations.single().start)
            assertEquals(label.length, annotations.single().end)
            compose.onNodeWithText(label, useUnmergedTree = true).performTouchInput {
                click(result.getBoundingBox(1).center)
            }
        }
        compose.runOnIdle { assertEquals(links.values.toList(), opened) }
        compose.onAllNodesWithText("[target]:", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun threeThematicBreakSpellingsProduceActualThinFullWidthDividers() {
        render("**above**\n\n---\n\n**middle one**\n\n***\n\n**middle two**\n\n___\n\n**below**")
        listOf("above", "middle one", "middle two", "below").forEach(::awaitText)
        // The upstream divider has clearAndSetSemantics and a real measured thin Box. Count that
        // rendered geometry, not merely the disappearance of the source punctuation.
        val divider = SemanticsMatcher("measured horizontal divider") { node ->
            val bounds = node.boundsInRoot
            !node.config.contains(SemanticsProperties.Text) &&
                    bounds.height > 0f && bounds.height <= 8f && bounds.width > bounds.height * 40f
        }
        compose.onAllNodes(divider, useUnmergedTree = true).assertCountEquals(3)
        assertTrue(bounds("below").top > bounds("above").bottom)
    }

    @Test
    fun nestedQuotesIndentContentAndExitBackToParagraph() {
        render("**outside before**\n\n> **outer quote**\n>\n> > *inner quote*\n\n**outside after**")
        listOf("outside before", "outer quote", "inner quote", "outside after").forEach(::awaitText)
        assertTrue(bounds("outer quote").left > bounds("outside before").left)
        assertTrue(bounds("inner quote").left > bounds("outer quote").left)
        assertEquals(bounds("outside before").left.value, bounds("outside after").left.value, 0.5f)
        assertBold(layout("outer quote").layoutInput.text, "outer quote")
        assertTrue(layout("inner quote").layoutInput.text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        compose.onAllNodesWithContentDescription("Quote", useUnmergedTree = true)
            .assertCountEquals(2)
    }

    @Test
    fun noteAlertRendersTitleBodyAndAccessibilityContainer() = assertAlert("NOTE", "Note")
    @Test
    fun tipAlertRendersTitleBodyAndAccessibilityContainer() = assertAlert("TIP", "Tip")
    @Test
    fun importantAlertRendersTitleBodyAndAccessibilityContainer() =
        assertAlert("IMPORTANT", "Important")

    @Test
    fun warningAlertRendersTitleBodyAndAccessibilityContainer() = assertAlert("WARNING", "Warning")
    @Test
    fun cautionAlertRendersTitleBodyAndAccessibilityContainer() = assertAlert("CAUTION", "Caution")

    @Test
    fun tightOrderedAndUnorderedListsKeepMarkersNumberingAndNestedIndent() {
        render("3. **ordered first**\n4. ordered second\n   - nested dash\n   + nested plus\n   * nested star\n5. ordered last")
        listOf(
            "ordered first",
            "ordered second",
            "nested dash",
            "nested plus",
            "nested star",
            "ordered last"
        )
            .forEach(::awaitText)
        listOf("3.", "4.", "5.").forEach { marker ->
            awaitText(marker, substring = true)
            assertEquals(marker, layout(marker, substring = true).layoutInput.text.text.trim())
            val markerBounds =
                compose.onNode(hasText(marker, substring = true), useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
            assertTrue(markerBounds.left < bounds("ordered first").left)
        }
        listOf("nested dash", "nested plus", "nested star").forEach { nested ->
            assertTrue(bounds(nested).left > bounds("ordered second").left)
        }
        assertEquals(bounds("ordered first").left.value, bounds("ordered last").left.value, 0.5f)
        assertBold(layout("ordered first").layoutInput.text, "ordered first")
    }

    @Test
    fun looseMixedListKeepsSecondParagraphAndNestedOrderedItemInsideParent() {
        render("- **loose first**\n\n  continuation paragraph\n\n  1. inner numbered\n  2. inner next\n\n- loose sibling\n\n**after list**")
        listOf(
            "loose first",
            "continuation paragraph",
            "inner numbered",
            "inner next",
            "loose sibling",
            "after list"
        )
            .forEach(::awaitText)
        assertEquals(
            bounds("loose first").left.value,
            bounds("continuation paragraph").left.value,
            0.5f
        )
        assertEquals(bounds("loose first").left.value, bounds("loose sibling").left.value, 0.5f)
        assertTrue(bounds("inner numbered").left > bounds("loose first").left)
        assertTrue(bounds("continuation paragraph").top > bounds("loose first").bottom)
        assertTrue(bounds("after list").left < bounds("loose sibling").left)
    }

    @Test
    fun tableColonSeparatorsApplyLeftCenterAndRightAlignment() {
        render("| Left | Center | Right |\n| :--- | :---: | ---: |\n| left cell | center cell | right cell |")
        listOf("left cell", "center cell", "right cell").forEach { awaitText(it, substring = true) }
        // Assert glyph placement within each measured cell, not merely a TextAlign enum. This
        // accepts legitimate default/start left alignment, but does not excuse missing center/right
        // alignment or a centered style applied to an intrinsic-width (rather than full-cell) Text.
        fun assertAlignment(text: String, column: Int, alignment: TextAlign) {
            val cellMatcher = SemanticsMatcher("body cell $column") { node ->
                if (!node.config.contains(SemanticsProperties.CollectionItemInfo)) false else {
                    val info = node.config[SemanticsProperties.CollectionItemInfo]
                    info.rowIndex == 1 && info.columnIndex == column
                }
            }
            val cell =
                compose.onNode(cellMatcher, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val textBounds = compose.onNode(hasText(text, substring = true), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val result = layout(text, substring = true)
            val density = result.layoutInput.density.density
            val leftGap = textBounds.left.value + result.getLineLeft(0) / density - cell.left.value
            val rightGap =
                cell.right.value - textBounds.left.value - result.getLineRight(0) / density
            when (alignment) {
                TextAlign.Left -> assertTrue(
                    "Expected left-aligned $text",
                    leftGap <= 1f && rightGap > leftGap
                )

                TextAlign.Center -> {
                    assertTrue("Expected centered $text", leftGap > 1f)
                    assertEquals("Expected equal cell margins for $text", leftGap, rightGap, 1f)
                }

                TextAlign.Right -> assertTrue(
                    "Expected right-aligned $text",
                    rightGap <= 1f && leftGap > rightGap
                )
            }
        }
        assertAlignment("left cell", 0, TextAlign.Left)
        assertAlignment("center cell", 1, TextAlign.Center)
        assertAlignment("right cell", 2, TextAlign.Right)
    }

    @Test
    fun tableEscapedPipeAndInlineFormattingRemainInsideThreeCells() {
        render("| Head A | Head B | Head C |\n| --- | --- | --- |\n| **bold cell** | left\\|right | *italic* and `code` |")
        listOf("bold cell", "left|right", "italic").forEach { awaitText(it, substring = true) }
        val table = SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo)
        val info = compose.onAllNodes(table, useUnmergedTree = true).fetchSemanticsNodes().single()
            .config[SemanticsProperties.CollectionInfo]
        assertEquals(3, info.columnCount)
        assertEquals(2, info.rowCount)
        assertEquals(
            "left|right",
            layout("left|right", substring = true).layoutInput.text.text.trim()
        )
        assertBold(layout("bold cell", substring = true).layoutInput.text, "bold cell")
        val rich = layout("italic", substring = true).layoutInput.text
        assertTrue(rich.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        val codeStart = rich.text.indexOf("code")
        assertTrue(codeStart >= 0)
        assertTrue(rich.spanStyles.any {
            it.start <= codeStart && it.end >= codeStart + 4 && it.item.fontFamily == FontFamily.Monospace
        })
    }

    @Test
    fun tildeIndentedUnknownLanguageAndUnclosedFencesRenderLiteralMonospaceCode() {
        render(
            "~~~text\ntilde <tag> *literal*\n~~~\n\n" +
                    "    indented *literal*\n    next line\n\n" +
                    "```not-a-real-language\nunknown &amp; *literal*\n```\n\n" +
                    "**before unclosed**\n\n```text\nunclosed *literal*"
        )
        awaitText("before unclosed")
        listOf(
            "tilde <tag> *literal*",
            "indented *literal*\nnext line",
            "unknown &amp; *literal*",
            "unclosed *literal*"
        )
            .forEach { expected ->
                awaitText(expected, substring = true)
                val result = layout(expected, substring = true)
                assertEquals(expected, result.layoutInput.text.text.trimEnd('\n'))
                assertEquals(FontFamily.Monospace, result.layoutInput.style.fontFamily)
            }
        assertBold(layout("before unclosed").layoutInput.text, "before unclosed")
    }

    @Test
    fun rawHtmlFallsBackSafelyWithoutHtmlLinksOrExecutableScript() {
        val opened = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }) {
                MarkdownText(
                    "**safe before**\n\n" +
                            "plain <a href=\"javascript:alert(1)\">HTML anchor</a> end\n\n" +
                            "<script>window.location='https://example.com/executed';</script>\n\n" +
                            "<iframe src=\"https://example.com/frame\"></iframe>\n\n**safe after**"
                )
            }
        }
        awaitText("safe after")
        assertBold(layout("safe before").layoutInput.text, "safe before")
        assertBold(layout("safe after").layoutInput.text, "safe after")
        // Dropping raw HTML tags while retaining ordinary inline text is a safe plain-text fallback.
        awaitText("plain HTML anchor end")
        val inline = layout("plain HTML anchor end").layoutInput.text
        assertTrue(inline.getLinkAnnotations(0, inline.length).isEmpty())
        listOf("javascript:", "window.location", "<iframe", "<script").forEach { unsafe ->
            compose.onAllNodesWithText(unsafe, substring = true, useUnmergedTree = true)
                .assertCountEquals(0)
        }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    private fun assertAlert(marker: String, title: String) {
        render("> [!$marker]\n> **alert body**\n>\n> second paragraph\n\n**after alert**")
        listOf(title, "alert body", "second paragraph", "after alert").forEach(::awaitText)
        compose.onAllNodesWithContentDescription("$title alert", useUnmergedTree = true)
            .assertCountEquals(1)
        assertBold(layout("alert body").layoutInput.text, "alert body")
        assertTrue(bounds("alert body").top >= bounds(title).bottom)
        assertTrue(bounds("second paragraph").top > bounds("alert body").bottom)
        compose.onAllNodesWithText("[!$marker]", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private fun assertBold(text: AnnotatedString, target: String) {
        val start = text.text.indexOf(target)
        assertTrue("Missing rendered text: $target", start >= 0)
        assertTrue("Expected a bold span covering $target", text.spanStyles.any {
            it.start <= start && it.end >= start + target.length && it.item.fontWeight == FontWeight.Bold
        })
    }

    private fun render(markdown: String) = setContent { MarkdownText(markdown) }

    private fun setContent(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent {
                MiuixTheme(ThemeController(ColorSchemeMode.Light)) {
                    Box(
                        Modifier
                            .background(MiuixTheme.colorScheme.background)
                            .width(320.dp)
                            .verticalScroll(rememberScrollState())
                    ) { content() }
                }
            }
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
        .getUnclippedBoundsInRoot()

    private fun layout(text: String, substring: Boolean = false): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text, substring = substring), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
        assertEquals("Expected one rendered text layout for $text", 1, results.size)
        return results.single()
    }
}
