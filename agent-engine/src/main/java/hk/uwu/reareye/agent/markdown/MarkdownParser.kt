package hk.uwu.reareye.agent.markdown

/**
 * Parses Markdown into [MarkdownBlock]s.
 *
 * Scope is the subset a chat model actually emits — headings, paragraphs, lists, fenced code,
 * GFM tables, quotes, dividers, and the common inline spans. Anything unrecognised degrades to
 * plain text rather than being dropped.
 *
 * The parser is total: it never throws and never loops, which matters because it runs against
 * **partially streamed** text where every construct can be half-written. An unterminated code
 * fence is treated as a code block running to the end, and an unterminated table is just text.
 */
object MarkdownParser {

    private const val MAX_NESTING = 4

    fun parse(markdown: String): MarkdownDocument {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return MarkdownDocument(parseBlocks(lines, 0, lines.size, depth = 0))
    }

    private fun parseBlocks(
        lines: List<String>,
        from: Int,
        to: Int,
        depth: Int,
    ): List<MarkdownBlock> {
        val out = mutableListOf<MarkdownBlock>()
        var i = from

        while (i < to) {
            val line = lines[i]

            if (line.isBlank()) {
                i++
                continue
            }

            // Fenced code. An unclosed fence runs to the end of the range, so a half-streamed
            // reply still renders as code instead of collapsing into a paragraph.
            val fence = fenceInfo(line)
            if (fence != null) {
                val body = mutableListOf<String>()
                var j = i + 1
                while (j < to && !isFenceClose(lines[j], fence.marker)) {
                    body.add(lines[j])
                    j++
                }
                out += MarkdownBlock.CodeBlock(
                    language = fence.language,
                    code = body.joinToString("\n"),
                )
                i = if (j < to) j + 1 else to
                continue
            }

            if (depth < MAX_NESTING) {
                val quote = parseQuote(lines, i, to, depth)
                if (quote != null) {
                    out += quote.block
                    i = quote.next
                    continue
                }

                val list = parseList(lines, i, to, depth)
                if (list != null) {
                    out += list.block
                    i = list.next
                    continue
                }
            }

            val heading = headingLevel(line)
            if (heading != null) {
                val body = line.trimStart().drop(heading).trimStart()
                out += MarkdownBlock.Heading(
                    level = heading,
                    // A closing hash run is optional syntax (`## Title ##`). Only a run of
                    // whitespace followed by hashes at the very end is removed — trimming every
                    // trailing '#' would also eat content and inline markers.
                    content = InlineParser.parse(stripClosingHashes(body)),
                )
                i++
                continue
            }

            if (isDivider(line)) {
                out += MarkdownBlock.Divider
                i++
                continue
            }

            val table = parseTable(lines, i, to)
            if (table != null) {
                out += table.block
                i = table.next
                continue
            }

            // Paragraph: consecutive plain lines, joined with a space.
            val paragraph = mutableListOf<String>()
            while (i < to) {
                val current = lines[i]
                if (current.isBlank() ||
                    fenceInfo(current) != null ||
                    headingLevel(current) != null ||
                    isDivider(current) ||
                    (depth < MAX_NESTING && (isQuoteStart(current) || listMarker(current) != null))
                ) {
                    break
                }
                // Stop before a table that starts here, unless this paragraph already began.
                if (paragraph.isNotEmpty() && parseTable(lines, i, to) != null) break
                paragraph.add(current)
                i++
            }
            if (paragraph.isEmpty()) {
                // A construct began here that every branch declined; consume it as plain text so
                // the loop always makes progress.
                out += MarkdownBlock.Paragraph(InlineParser.parse(lines[i]))
                i++
            } else {
                // Joined with a newline rather than a space: a space would erase the two-space and
                // backslash hard breaks, and those are the only signal the inline parser has.
                out += MarkdownBlock.Paragraph(InlineParser.parse(paragraph.joinToString("\n")))
            }
        }

        return out
    }

    private fun parseQuote(lines: List<String>, from: Int, to: Int, depth: Int): Result? {
        if (!isQuoteStart(lines[from])) return null
        val inner = mutableListOf<String>()
        var i = from
        while (i < to && isQuoteStart(lines[i])) {
            inner.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
            i++
        }
        return Result(MarkdownBlock.Quote(parseBlocks(inner, 0, inner.size, depth + 1)), i)
    }

    private fun parseList(lines: List<String>, from: Int, to: Int, depth: Int): Result? {
        val first = listMarker(lines[from]) ?: return null
        val items = mutableListOf<MutableList<String>>()
        var i = from
        var current = mutableListOf<String>()

        while (i < to) {
            val line = lines[i]
            val marker = listMarker(line)
            when {
                marker != null -> {
                    if (marker.ordered != first.ordered) break
                    if (current.isNotEmpty()) items.add(current)
                    current = mutableListOf(line.substring(marker.contentStart))
                    i++
                }
                // A continuation line is indented under the current item.
                line.isNotBlank() && line.first().isWhitespace() && current.isNotEmpty() -> {
                    current.add(line.trim())
                    i++
                }
                line.isBlank() -> {
                    // A blank line only ends the list if the next significant line is not a
                    // continuation; treating every blank as the end would split loose lists.
                    val next = lines.getOrNull(i + 1)
                    if (next == null || listMarker(next) == null) break
                    i++
                }
                else -> break
            }
        }
        if (current.isNotEmpty()) items.add(current)

        val blocks = items.map { item ->
            parseBlocks(item, 0, item.size, depth + 1).ifEmpty {
                listOf(MarkdownBlock.Paragraph(emptyList()))
            }
        }
        val start = first.number
        return if (first.ordered) {
            Result(MarkdownBlock.OrderedList(start = start, items = blocks), i)
        } else {
            Result(MarkdownBlock.BulletList(items = blocks), i)
        }
    }

    /**
     * GFM table.
     *
     * A line of pipes is only a table when the *next* line is a separator row. Without that check
     * any sentence containing `|` would be pulled into a one-column table.
     */
    private fun parseTable(lines: List<String>, from: Int, to: Int): Result? {
        if (!lines[from].contains('|')) return null
        val separator = lines.getOrNull(from + 1) ?: return null
        val alignments = parseSeparator(separator) ?: return null

        val header = splitRow(lines[from])
        if (header.isEmpty()) return null

        val rows = mutableListOf<List<List<MarkdownInline>>>()
        var i = from + 2
        while (i < to && lines[i].contains('|') && lines[i].isNotBlank()) {
            val cells = splitRow(lines[i])
            if (cells.isEmpty()) break
            rows.add(cells.map { InlineParser.parse(it) })
            i++
        }

        return Result(
            MarkdownBlock.Table(
                header = header.map { InlineParser.parse(it) },
                alignments = alignments,
                rows = rows,
            ),
            i,
        )
    }

    /** Returns one alignment per column, or null when [line] is not a separator row. */
    private fun parseSeparator(line: String): List<TableAlignment>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.contains('-')) return null
        val cells = splitRow(trimmed)
        if (cells.isEmpty()) return null
        return cells.map { cell ->
            val text = cell.trim()
            if (text.isEmpty() || text.any { it != '-' && it != ':' }) return null
            val left = text.startsWith(":")
            val right = text.endsWith(":")
            when {
                left && right -> TableAlignment.CENTER
                right -> TableAlignment.END
                else -> TableAlignment.START
            }
        }
    }

    private fun splitRow(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|")) body = body.dropLast(1)
        if (body.isEmpty()) return emptyList()
        // Split on unescaped pipes only, so `\|` inside a cell stays literal.
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length && body[i + 1] == '|') {
                current.append('|')
                i += 2
                continue
            }
            if (c == '|') {
                cells.add(current.toString().trim())
                current.setLength(0)
                i++
                continue
            }
            current.append(c)
            i++
        }
        cells.add(current.toString().trim())
        return cells
    }

    private data class Fence(val marker: Char, val length: Int, val language: String?)

    private fun fenceInfo(line: String): Fence? {
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull() ?: return null
        if (marker != '`' && marker != '~') return null
        var n = 0
        while (n < trimmed.length && trimmed[n] == marker) n++
        if (n < 3) return null
        val language = trimmed.substring(n).trim().takeWhile { !it.isWhitespace() }
        return Fence(marker, n, language.ifEmpty { null })
    }

    private fun isFenceClose(line: String, marker: Char): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        return trimmed.all { it == marker }
    }

    private fun headingLevel(line: String): Int? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("#")) return null
        var n = 0
        while (n < trimmed.length && trimmed[n] == '#') n++
        if (n > 6) return null
        // "#hashtag" is not a heading; a heading needs a space (or to be only hashes).
        if (n < trimmed.length && !trimmed[n].isWhitespace()) return null
        return n
    }

    /**
     * Removes a trailing `##`-style closing run, which is optional heading syntax.
     *
     * Only a run sitting at the very end after whitespace is removed: truncating every trailing '#'
     * would also destroy content and inline markers such as `**bold**`.
     */
    private fun stripClosingHashes(body: String): String {
        val trimmed = body.trimEnd()
        if (!trimmed.endsWith("#")) return trimmed
        var i = trimmed.length
        while (i > 0 && trimmed[i - 1] == '#') i--
        // A closing run must be preceded by whitespace (or be the whole body).
        if (i > 0 && !trimmed[i - 1].isWhitespace()) return trimmed
        return trimmed.substring(0, i).trimEnd()
    }

    private fun isDivider(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val stripped = trimmed.filterNot { it == ' ' }
        if (stripped.length < 3) return false
        return stripped.all { it == '-' } || stripped.all { it == '*' } || stripped.all { it == '_' }
    }

    private fun isQuoteStart(line: String): Boolean = line.trimStart().startsWith(">")

    private data class ListMarker(
        val ordered: Boolean,
        val number: Int,
        val contentStart: Int,
    )

    private fun listMarker(line: String): ListMarker? {
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) return null
        val body = line.substring(indent)
        // Bullets.
        for (bullet in listOf("- ", "* ", "+ ")) {
            if (body.startsWith(bullet)) {
                return ListMarker(ordered = false, number = 0, contentStart = indent + bullet.length)
            }
        }
        // "1. " or "1) ".
        var i = 0
        while (i < body.length && body[i].isDigit()) i++
        if (i in 1..9 && i + 1 < body.length && (body[i] == '.' || body[i] == ')') && body[i + 1] == ' ') {
            return ListMarker(
                ordered = true,
                number = body.substring(0, i).toIntOrNull() ?: 1,
                contentStart = indent + i + 2,
            )
        }
        return null
    }

    private data class Result(val block: MarkdownBlock, val next: Int)
}
