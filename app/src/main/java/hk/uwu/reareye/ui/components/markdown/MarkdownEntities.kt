package hk.uwu.reareye.ui.components.markdown

import com.mikepenz.markdown.utils.EntityConverter

private val entityToken = Regex("&(?:#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]*);")

/** Decode only text nodes, in one pass: entities must not become new Markdown syntax. */
internal fun decodeMarkdownText(raw: String): String = buildString {
    var index = 0
    while (index < raw.length) {
        val ch = raw[index]
        val next = raw.getOrNull(index + 1)
        if (ch == '\\' && next != null && (next in '!'..'/' || next in ':'..'@' || next in '['..'`' || next in '{'..'~')) {
            append(next)
            index += 2
            continue
        }
        val entity = if (ch == '&') entityToken.matchAt(raw, index) else null
        if (entity == null) {
            append(ch); index++; continue
        }
        val token = entity.value
        if (token.startsWith("&#")) {
            val hex = token.getOrNull(2)?.lowercaseChar() == 'x'
            val value = token.substring(if (hex) 3 else 2, token.length - 1)
                .toIntOrNull(if (hex) 16 else 10)
            if (value == null || value == 0 || !Character.isValidCodePoint(value) || value in 0xD800..0xDFFF) append(
                '\uFFFD'
            )
            else append(String(Character.toChars(value)))
        } else append(
            EntityConverter.replaceEntities(
                token,
                processEntities = true,
                processEscapes = false
            )
        )
        index = entity.range.last + 1
    }
}
