package hk.uwu.reareye.agent.markdown

/**
 * Parses the inline spans of a single Markdown line.
 *
 * Deliberately hand-rolled rather than pulled from a library: the app only needs the subset a chat
 * model actually emits, and keeping it here makes the behaviour testable and keeps the module free
 * of Android and of a second design system.
 *
 * Unterminated delimiters are emitted as literal text, which is what makes this safe to run on a
 * partially streamed message: a lone `**` renders as `**` until its partner arrives.
 */
internal object InlineParser {

    fun parse(text: String): List<MarkdownInline> = Builder(text).parse(0, text.length)

    private class Builder(private val text: String) {

        fun parse(from: Int, to: Int): List<MarkdownInline> {
            val out = mutableListOf<MarkdownInline>()
            val literal = StringBuilder()

            fun flush() {
                if (literal.isNotEmpty()) {
                    out += MarkdownInline.Text(literal.toString())
                    literal.setLength(0)
                }
            }

            var i = from
            while (i < to) {
                val c = text[i]

                // A newline is a hard break when the preceding character was a backslash or when
                // the line ended in two spaces. The parser joins paragraph lines with `\n` rather
                // than a space precisely so this information survives.
                if (c == '\n') {
                    val escaped = i > from && text[i - 1] == '\\'
                    val trailingSpaces = i >= 2 && text[i - 1] == ' ' && text[i - 2] == ' '
                    if (escaped || trailingSpaces) {
                        // Drop the marker characters already appended to the literal buffer.
                        if (escaped) {
                            literal.setLength(literal.length - 1)
                        } else {
                            while (literal.isNotEmpty() && literal.last() == ' ') {
                                literal.setLength(literal.length - 1)
                            }
                        }
                        flush()
                        out += MarkdownInline.LineBreak
                    } else {
                        literal.append(' ')
                    }
                    i++
                    continue
                }

                // Backslash escapes any punctuation.
                if (c == '\\' && i + 1 < to && text[i + 1].isPunctuation()) {
                    literal.append(text[i + 1])
                    i += 2
                    continue
                }

                when {
                    c == '`' -> {
                        val ticks = runLength(i, to, '`')
                        val close = findRun(i + ticks, to, '`', ticks)
                        if (close < 0) {
                            literal.append(text, i, i + ticks)
                            i += ticks
                        } else {
                            flush()
                            out += MarkdownInline.Code(
                                text.substring(i + ticks, close).trim(' '),
                            )
                            i = close + ticks
                        }
                    }

                    text.startsWith("**", i) || text.startsWith("__", i) -> {
                        val marker = text.substring(i, i + 2)
                        val close = findClosing(marker, i + 2, to)
                        if (close < 0) {
                            literal.append(marker)
                            i += 2
                        } else {
                            flush()
                            out += MarkdownInline.Bold(parse(i + 2, close))
                            i = close + 2
                        }
                    }

                    text.startsWith("~~", i) -> {
                        val close = findClosing("~~", i + 2, to)
                        if (close < 0) {
                            literal.append("~~")
                            i += 2
                        } else {
                            flush()
                            out += MarkdownInline.Strikethrough(parse(i + 2, close))
                            i = close + 2
                        }
                    }

                    (c == '*' || c == '_') && canOpen(i, to, c) -> {
                        val marker = c.toString()
                        val close = findClosing(marker, i + 1, to)
                        if (close < 0) {
                            literal.append(c)
                            i++
                        } else {
                            flush()
                            out += MarkdownInline.Italic(parse(i + 1, close))
                            i = close + 1
                        }
                    }

                    c == '[' -> {
                        val labelEnd = text.indexOf(']', i + 1)
                        if (labelEnd in (i + 1) until to && labelEnd + 1 < to && text[labelEnd + 1] == '(') {
                            val urlEnd = text.indexOf(')', labelEnd + 2)
                            if (urlEnd in (labelEnd + 2) until to) {
                                flush()
                                out += MarkdownInline.Link(
                                    text = parse(i + 1, labelEnd),
                                    url = text.substring(labelEnd + 2, urlEnd).trim(),
                                )
                                i = urlEnd + 1
                                continue
                            }
                        }
                        literal.append(c)
                        i++
                    }

                    else -> {
                        literal.append(c)
                        i++
                    }
                }
            }

            flush()
            return out
        }

        private fun runLength(from: Int, to: Int, ch: Char): Int {
            var n = 0
            while (from + n < to && text[from + n] == ch) n++
            return n
        }

        private fun findRun(from: Int, to: Int, ch: Char, length: Int): Int {
            var i = from
            while (i < to) {
                if (text[i] == ch && runLength(i, to, ch) >= length) return i
                i++
            }
            return -1
        }

        /**
         * Index of the closing [marker], or -1 when it never closes within this range.
         *
         * The closing delimiter is not required to be followed by a non-space: `**粗**` at the end
         * of a line is a perfectly good bold run. Requiring that check here was why bold text at the
         * end of a line stayed literal.
         */
        private fun findClosing(marker: String, from: Int, to: Int): Int {
            var i = from
            while (i < to) {
                if (text.startsWith(marker, i)) {
                    // The content between the delimiters must not be empty.
                    if (i > from) return i
                }
                i++
            }
            return -1
        }

        /**
         * `_` inside a word is not emphasis in CommonMark, and treating it as such would mangle
         * identifiers like `list_app_cards`.
         */
        private fun canOpen(index: Int, to: Int, marker: Char): Boolean {
            if (index + 1 >= to) return false
            if (text[index + 1].isWhitespace()) return false
            if (marker == '_' && index > 0 && text[index - 1].isLetterOrDigit()) return false
            return true
        }

        private fun Char.isPunctuation(): Boolean =
            this in "\\`*_{}[]()#+-.!|>~\"'"
    }
}
