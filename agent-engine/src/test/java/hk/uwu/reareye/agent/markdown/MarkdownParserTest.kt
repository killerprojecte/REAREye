package hk.uwu.reareye.agent.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inputs here are real replies the model produced during development, not invented samples —
 * they are what made Markdown rendering necessary in the first place.
 */
class MarkdownParserTest {

    private fun blocks(markdown: String) = MarkdownParser.parse(markdown).blocks

    private fun plainText(inlines: List<MarkdownInline>): String = inlines.joinToString("") { inline ->
        when (inline) {
            is MarkdownInline.Text -> inline.value
            is MarkdownInline.Code -> inline.value
            is MarkdownInline.Bold -> plainText(inline.content)
            is MarkdownInline.Italic -> plainText(inline.content)
            is MarkdownInline.Strikethrough -> plainText(inline.content)
            is MarkdownInline.Link -> plainText(inline.text)
            MarkdownInline.LineBreak -> "\n"
        }
    }

    /**
     * The reply that prompted this work: a GFM table listing installed components.
     */
    @Test
    fun `parses the component table the model actually produced`() {
        val markdown = """
            背屏里目前装了 **8 个组件**：

            | 组件标识 | 来源 |
            |---|---|
            | calendar（背屏日历Lite） | 组件仓库 |
            | weather（背屏天气） | 组件仓库 |
            | jianzhu | 本地导入 |
        """.trimIndent()

        val parsed = blocks(markdown)
        assertEquals(2, parsed.size)

        val paragraph = parsed[0] as MarkdownBlock.Paragraph
        assertEquals(1, paragraph.content.filterIsInstance<MarkdownInline.Bold>().size)

        val table = parsed[1] as MarkdownBlock.Table
        assertEquals(listOf("组件标识", "来源"), table.header.map { plainText(it) })
        assertEquals(3, table.rows.size)
        assertEquals(listOf("calendar（背屏日历Lite）", "组件仓库"), table.rows[0].map { plainText(it) })
        assertEquals(listOf(TableAlignment.START, TableAlignment.START), table.alignments)
    }

    /**
     * A pipe in ordinary prose must not become a table — this is why table detection requires the
     * separator row to follow.
     */
    @Test
    fun `a stray pipe in prose is not a table`() {
        val parsed = blocks("可以用 A | B 表示二选一。")
        assertEquals(1, parsed.size)
        assertTrue(parsed[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `table alignment markers are honoured`() {
        val table = blocks(
            """
            | 左 | 中 | 右 |
            |:---|:---:|---:|
            | a | b | c |
            """.trimIndent(),
        )[0] as MarkdownBlock.Table

        assertEquals(
            listOf(TableAlignment.START, TableAlignment.CENTER, TableAlignment.END),
            table.alignments,
        )
    }

    @Test
    fun `escaped pipe inside a cell stays literal`() {
        val table = blocks(
            """
            | 表达式 | 说明 |
            |---|---|
            | a \| b | 按位或 |
            """.trimIndent(),
        )[0] as MarkdownBlock.Table

        assertEquals("a | b", plainText(table.rows[0][0]))
    }

    @Test
    fun `inline spans cover bold italic strikethrough code and links`() {
        // Separated by spaces on purpose: `**粗** *斜*` with no gap is one ambiguous run of
        // asterisks, and asserting on it tests the wrong thing.
        val parsed = blocks("**粗** *斜* ~~删~~ `代码` [链接](https://example.com)")[0]
        val content = (parsed as MarkdownBlock.Paragraph).content

        assertTrue("expected bold", content.any { it is MarkdownInline.Bold })
        assertTrue("expected italic", content.any { it is MarkdownInline.Italic })
        assertTrue("expected strikethrough", content.any { it is MarkdownInline.Strikethrough })
        assertEquals("代码", content.filterIsInstance<MarkdownInline.Code>().single().value)
        val link = content.filterIsInstance<MarkdownInline.Link>().single()
        assertEquals("https://example.com", link.url)
        assertEquals("链接", plainText(link.text))
    }

    /** Bold at the very end of a line must still close. */
    @Test
    fun `bold at end of line closes`() {
        val parsed = blocks("这是一段**加粗结尾**")[0] as MarkdownBlock.Paragraph
        val bold = parsed.content.filterIsInstance<MarkdownInline.Bold>().single()
        assertEquals("加粗结尾", plainText(bold.content))
    }

    /** `## 标题 ##` — the closing run is optional syntax and must not become content. */
    @Test
    fun `a closing hash run on a heading is removed`() {
        val parsed = blocks("## 标题 ##")[0] as MarkdownBlock.Heading
        assertEquals("标题", plainText(parsed.content))
    }

    /**
     * Streaming means every construct is seen half-written. The parser must keep the text rather
     * than swallow it, so a lone `**` shows up literally until its partner arrives.
     */
    @Test
    fun `unterminated inline markers are kept as literal text`() {
        val parsed = blocks("这是**没写完的加粗")[0] as MarkdownBlock.Paragraph
        assertEquals("这是**没写完的加粗", plainText(parsed.content))
    }

    @Test
    fun `unterminated code fence renders as a code block to the end`() {
        val parsed = blocks(
            """
            说明：
            ```
            val a = 1
            println(a)
            """.trimIndent(),
        )
        val code = parsed.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("val a = 1\nprintln(a)", code.code)
    }

    @Test
    fun `fenced code keeps its language and inner formatting`() {
        val parsed = blocks(
            """
            ```kotlin
            **not bold**
            ```
            """.trimIndent(),
        )
        val code = parsed.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("**not bold**", code.code)
    }

    @Test
    fun `headings carry their level and inline content`() {
        val parsed = blocks("# 一级\n\n### 三级 **粗**")
        assertEquals(1, (parsed[0] as MarkdownBlock.Heading).level)
        val third = parsed[1] as MarkdownBlock.Heading
        assertEquals(3, third.level)
        assertTrue("expected bold inside the heading", third.content.any { it is MarkdownInline.Bold })
    }

    /** "#hashtag" is not a heading. */
    @Test
    fun `a hash without a space is not a heading`() {
        val parsed = blocks("#标签 不是标题")
        assertTrue(parsed[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `bullet and ordered lists become list blocks`() {
        val bullets = blocks("- 第一项\n- 第二项")[0] as MarkdownBlock.BulletList
        assertEquals(2, bullets.items.size)
        assertEquals("第一项", plainText((bullets.items[0][0] as MarkdownBlock.Paragraph).content))

        val ordered = blocks("3. 甲\n4. 乙")[0] as MarkdownBlock.OrderedList
        assertEquals(3, ordered.start)
        assertEquals(2, ordered.items.size)
    }

    @Test
    fun `quotes contain parsed blocks`() {
        val quote = blocks("> **注意**\n> 第二行")[0] as MarkdownBlock.Quote
        val paragraph = quote.blocks.filterIsInstance<MarkdownBlock.Paragraph>().first()
        assertTrue("expected bold inside the quote", paragraph.content.any { it is MarkdownInline.Bold })
    }

    @Test
    fun `dividers are recognised in all three forms`() {
        assertEquals(3, blocks("---\n\n***\n\n___").count { it is MarkdownBlock.Divider })
    }

    /** A dash-only line of two characters is text, not a divider. */
    @Test
    fun `two dashes are not a divider`() {
        assertTrue(blocks("--")[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `underscores inside an identifier are not emphasis`() {
        val parsed = blocks("调用 list_app_cards 工具")[0] as MarkdownBlock.Paragraph
        assertEquals("调用 list_app_cards 工具", plainText(parsed.content))
        assertTrue(parsed.content.none { it is MarkdownInline.Italic })
    }

    /**
     * The parser is total: arbitrary input must never throw or hang, because it runs against
     * streamed text that can be cut at any byte.
     */
    @Test
    fun `every prefix of a real reply parses without throwing`() {
        val full = """
            背屏里目前装了 **8 个组件**：

            | 组件标识 | 来源 |
            |---|---|
            | calendar | 组件仓库 |

            ```json
            {"ok": true}
            ```

            > 提示：可以让我建一张卡片。
            - 第一项
            - 第二项

            ---
        """.trimIndent()

        for (length in 0..full.length) {
            val prefix = full.take(length)
            // Must not throw; the result is only inspected for the ends of the range.
            MarkdownParser.parse(prefix)
        }
    }

    @Test
    fun `empty and whitespace-only input produce no blocks`() {
        assertTrue(MarkdownParser.parse("").blocks.isEmpty())
        assertTrue(MarkdownParser.parse("\n\n   \n").blocks.isEmpty())
    }

    /** Hard breaks: two trailing spaces, and a trailing backslash. */
    @Test
    fun `hard breaks are preserved`() {
        val parsed = blocks("第一行  \n第二行")[0] as MarkdownBlock.Paragraph
        assertTrue(parsed.content.any { it is MarkdownInline.LineBreak })
    }
}
