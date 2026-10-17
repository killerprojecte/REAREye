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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/** Exercise the app's composable, including its asynchronous parser, rather than a second parser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownTextTest {
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
                "text-${screenshotName.methodName}"
            )
        } finally {
            compose.runOnUiThread { activity?.close() }
        }
    }

    @Test
    fun chineseEmphasisHasStyledTextWithoutLiteralAsterisks() {
        setContent { MarkdownText("中文**粗体**和*斜体*结束") }
        val rendered = "中文粗体和斜体结束"
        awaitText(rendered)
        val text = layout(rendered).layoutInput.text
        assertFalse(text.text.contains('*'))
        assertTrue(text.spanStyles.any {
            it.start <= 2 && it.end >= 4 && it.item.fontWeight == FontWeight.Bold
        })
        assertTrue(text.spanStyles.any {
            it.start <= 5 && it.end >= 7 && it.item.fontStyle == FontStyle.Italic
        })
    }

    @Test
    fun combinedBoldItalicAndStrikethroughUseGfmSpans() {
        setContent { MarkdownText("***粗斜体***与~~删除线~~") }
        awaitText("粗斜体与删除线")
        val text = layout("粗斜体与删除线").layoutInput.text
        assertTrue(text.spanStyles.any {
            it.start == 0 && it.end >= 3 && it.item.fontWeight == FontWeight.Bold
        })
        assertTrue(text.spanStyles.any {
            it.start == 0 && it.end >= 3 && it.item.fontStyle == FontStyle.Italic
        })
        assertTrue(text.spanStyles.any {
            it.start == 4 && it.end == 7 &&
                    it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough
        })
    }

    @Test
    fun nestedListTextIsIndentedAndSiblingReturnsToParentIndent() {
        setContent {
            MarkdownText("- 一级条目\n  - 二级条目\n    - 三级条目\n- 同级条目")
        }
        listOf("一级条目", "二级条目", "三级条目", "同级条目").forEach(::awaitText)
        fun left(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
            .getUnclippedBoundsInRoot().left.value
        assertTrue(left("二级条目") > left("一级条目"))
        assertTrue(left("三级条目") > left("二级条目"))
        assertEquals(left("一级条目"), left("同级条目"), 0.5f)
    }

    @Test
    fun fourBacktickFenceKeepsInnerTripleBackticksAsCode() {
        val code = "```kotlin\nval 中文 = 1\n```"
        setContent { MarkdownText("````text\n$code\n````\n\n**代码之后**") }
        // The styled following paragraph also rules out the raw loading fallback.
        awaitText("代码之后")
        awaitText(code, substring = true)
        val result = layout(code, substring = true)
        assertTrue(result.layoutInput.text.text.contains(code))
        assertEquals(FontFamily.Monospace, result.layoutInput.style.fontFamily)
    }

    @Test
    fun streamedFenceClosureRapidAppendsAndReplacementNeverDuplicateOldSnapshots() {
        val markdown = mutableStateOf("```text\n流式第一段")
        setContent { MarkdownText(markdown.value) }
        // An exact match excludes the loading fallback, which still contains the opening fence.
        awaitText("流式第一段")
        assertEquals(FontFamily.Monospace, layout("流式第一段").layoutInput.style.fontFamily)

        compose.runOnIdle { markdown.value = "```text\n流式第一段\n```\n\n**闭合标记**" }
        awaitText("闭合标记")
        assertTextCount("流式第一段", 1, substring = true)

        // Several full snapshots in one frame intentionally allow intermediate parses to be skipped.
        compose.runOnIdle {
            repeat(30) { index -> markdown.value = "**快速追加** 第${index}次" }
            markdown.value = "**快速追加** 最终快照"
        }
        awaitText("快速追加 最终快照")
        assertTextCount("快速追加 最终快照", 1)
        assertTextCount("闭合标记", 0)
        assertTextCount("流式第一段", 0, substring = true)

        compose.runOnIdle { markdown.value = "**全量替换** 新会话" }
        awaitText("全量替换 新会话")
        assertTextCount("全量替换 新会话", 1)
        assertTextCount("快速追加", 0, substring = true)
        assertTextCount("流式第一段", 0, substring = true)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wideTableScrollsHorizontallyAndLongHeaderAndBodyWrapWithoutTruncation() {
        val header = "这是不能被单行省略的表头内容".repeat(12)
        val body = "这是必须完整显示并允许换行的单元格内容".repeat(12)
        setContent {
            MarkdownText(
                "| $header | 第二列 | 第三列 | 第四列 |\n" +
                        "| --- | --- | --- | --- |\n| $body | 值二 | 值三 | 值四 |"
            )
        }
        // The upstream table annotator preserves CELL boundary spaces from the source.
        awaitText(header, substring = true)
        awaitText(body, substring = true)
        val horizontal =
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        compose.waitUntil(10_000) {
            compose.onAllNodes(horizontal, useUnmergedTree = true).fetchSemanticsNodes().any {
                it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f
            }
        }
        listOf(header, body).forEach { text ->
            val result = layout(text, substring = true)
            assertEquals(text, result.layoutInput.text.text.trim())
            assertEquals(Int.MAX_VALUE, result.layoutInput.maxLines)
            assertTrue("The long cell should wrap", result.lineCount > 1)
            assertFalse("The cell must not overflow vertically", result.didOverflowHeight)
            assertEquals(result.layoutInput.text.length, result.getLineEnd(result.lineCount - 1))
        }
        val scroll = compose.onAllNodes(horizontal, useUnmergedTree = true)[0]
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(80f, 0f) }
        compose.waitUntil(10_000) {
            scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f
        }
    }

    @Test
    fun lightThemeUsesMiuixColorsAndBodyLineHeight() = assertTheme(ColorSchemeMode.Light)

    @Test
    fun darkThemeUsesMiuixColorsAndBodyLineHeight() = assertTheme(ColorSchemeMode.Dark)

    @Test
    fun linkClicksReachUriHandlerAndUnsupportedUriDoesNotEscape() {
        val opened = mutableListOf<String>()
        var reject = false
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
                if (reject) throw IllegalArgumentException("No activity can open this URI")
            }
        }
        setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                MarkdownText("[打开链接](https://example.com/help)")
            }
        }
        awaitText("打开链接")
        // Click a glyph, not the center of the full-width paragraph (which may be empty space).
        val position = layout("打开链接").getBoundingBox(1).center
        compose.onNodeWithText("打开链接", useUnmergedTree = true)
            .performTouchInput { click(position) }
        compose.runOnIdle {
            assertEquals(listOf("https://example.com/help"), opened)
            reject = true
        }
        compose.onNodeWithText("打开链接", useUnmergedTree = true)
            .performTouchInput { click(position) }
        compose.runOnIdle {
            assertEquals(listOf("https://example.com/help", "https://example.com/help"), opened)
        }
    }

    private fun assertTheme(mode: ColorSchemeMode) {
        var expectedText = Color.Unspecified
        var checkMapping: (() -> Unit)? = null
        setContent(mode) {
            val scheme = MiuixTheme.colorScheme
            val colors = miuixMarkdownColors(scheme.onSurfaceContainer)
            val typography = miuixMarkdownTypography(scheme.onSurfaceContainer)
            val customColor = Color(0xFF123456)
            val customColors = miuixMarkdownColors(customColor)
            val customTypography = miuixMarkdownTypography(customColor)
            SideEffect {
                expectedText = scheme.onSurfaceContainer
                checkMapping = {
                    assertEquals(scheme.onSurfaceContainer, colors.text)
                    assertEquals(scheme.surfaceContainerHigh, colors.codeBackground)
                    assertEquals(scheme.surfaceContainerHigh, colors.tableBackground)
                    assertEquals(scheme.primary.copy(alpha = 0.12f), colors.inlineCodeBackground)
                    assertEquals(scheme.onSurfaceContainer.copy(alpha = 0.12f), colors.dividerColor)
                    assertEquals(scheme.primary, typography.textLink.style?.color)
                    assertEquals(scheme.onSurfaceVariantSummary, typography.quote.color)
                    assertEquals(FontFamily.Monospace, typography.code.fontFamily)
                    assertEquals(customColor, customColors.text)
                    assertEquals(customColor, customTypography.paragraph.color)
                    listOf(
                        typography.text, typography.paragraph, typography.ordered,
                        typography.bullet, typography.list
                    ).forEach { assertEquals(24.sp, it.lineHeight) }
                }
            }
            MarkdownText("**主题正文**")
        }
        awaitText("主题正文")
        compose.runOnIdle { checkNotNull(checkMapping).invoke() }
        val result = layout("主题正文")
        assertNotEquals(Color.Unspecified, expectedText)
        assertEquals(expectedText, result.layoutInput.style.color)
        assertEquals(24.sp, result.layoutInput.style.lineHeight)
    }

    private fun setContent(
        mode: ColorSchemeMode = ColorSchemeMode.Light,
        content: @Composable () -> Unit,
    ) {
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent {
                MiuixTheme(ThemeController(mode)) {
                    // Match a chat item's unbounded vertical constraints, not a fixed-height
                    // screen that would leave zero height for rows after a long table header.
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

    private fun assertTextCount(text: String, count: Int, substring: Boolean = false) {
        assertEquals(
            count, compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
    }

    private fun layout(text: String, substring: Boolean = false): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text, substring = substring), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
        assertEquals("Expected one text layout for $text", 1, results.size)
        return results.single()
    }
}
