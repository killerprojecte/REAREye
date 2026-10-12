package hk.uwu.reareye.agent.markdown

/**
 * A parsed Markdown document, as a flat list of [MarkdownBlock]s.
 *
 * Parsing lives in the engine rather than the UI so it can be unit tested without an Android
 * runtime, and so a rendering mistake cannot silently change what the model's text meant.
 */
data class MarkdownDocument(val blocks: List<MarkdownBlock>)

/**
 * One top-level Markdown construct.
 *
 * The app renders these with MIUIX components. Anything the parser does not recognise becomes a
 * [Paragraph] with plain inline text, so unknown syntax degrades to readable text rather than
 * disappearing.
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock

    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock

    data class BulletList(val items: List<List<MarkdownBlock>>) : MarkdownBlock

    data class OrderedList(
        val start: Int,
        val items: List<List<MarkdownBlock>>,
    ) : MarkdownBlock

    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock

    data class Table(
        val header: List<List<MarkdownInline>>,
        val alignments: List<TableAlignment>,
        val rows: List<List<List<MarkdownInline>>>,
    ) : MarkdownBlock

    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    data object Divider : MarkdownBlock
}

enum class TableAlignment { START, CENTER, END }

/** An inline span within a block of text. */
sealed interface MarkdownInline {
    data class Text(val value: String) : MarkdownInline

    data class Bold(val content: List<MarkdownInline>) : MarkdownInline

    data class Italic(val content: List<MarkdownInline>) : MarkdownInline

    data class Strikethrough(val content: List<MarkdownInline>) : MarkdownInline

    data class Code(val value: String) : MarkdownInline

    data class Link(val text: List<MarkdownInline>, val url: String) : MarkdownInline

    /** A `\n` produced by a hard line break (two trailing spaces or a backslash). */
    data object LineBreak : MarkdownInline
}
