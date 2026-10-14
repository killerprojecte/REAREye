package hk.uwu.reareye.agent.luascript

/**
 * Static checks on a generated Lua script.
 *
 * This is deliberately **not** a Lua parser and not a sandbox. The runtime already validates a
 * script authoritatively when it loads it (`ScriptRuntime.parseDescriptor` checks the returned table,
 * provider ids, modes, config types and ranges). What this adds is a cheap gate *before* anything is
 * written to disk, so an obviously broken generation is reported to the model instead of becoming a
 * file the user then has to debug in the scripts screen.
 *
 * Only checks that cannot produce false positives on valid Lua are errors. Anything interpretation-
 * heavy is a warning, because a wrong error is worse than a missed one: it sends the model chasing a
 * problem that does not exist.
 */
object LuaScriptValidator {

    /**
     * Ids for scripts, providers and config entries.
     *
     * Mirrors `ScriptRuntime.ID`. Note the runtime is slightly more permissive than the project-id
     * rule used for file names, which forbids dots — the tool applies the stricter one to the name it
     * writes and this one to ids declared inside the source.
     */
    private val ID = Regex("^[A-Za-z0-9_.-]+$")

    private val DECLARED_ID = Regex("""\bid\s*=\s*["']([^"']*)["']""")

    fun validate(source: String): LuaScriptValidation {
        val issues = mutableListOf<LuaScriptIssue>()

        if (source.isBlank()) {
            issues.error("empty_source", "脚本内容为空。")
            return LuaScriptValidation(issues)
        }

        // Scans run on the comment-free view: a comment mentioning "providers" would otherwise mask a
        // genuinely missing declaration, and an example id inside a comment would be validated as if
        // it were real. String literals are kept, because declared ids live in them.
        val code = stripComments(source)

        // The entry point must return the descriptor table; without a return the runtime fails with
        // "Entry must return a metadata table".
        if (!Regex("""\breturn\b""").containsMatchIn(code)) {
            issues.error(
                "missing_return",
                "脚本没有 return。入口必须返回一个描述表（至少包含 providers），" +
                    "否则运行时会报「Entry must return a metadata table」。",
            )
        }

        if (!Regex("""\bproviders\b""").containsMatchIn(code)) {
            issues.error(
                "missing_providers",
                "脚本没有声明 providers。描述表里的 providers 是必填项，" +
                    "没有 Provider 的脚本没有任何可调用的功能。",
            )
        }

        // Every `id = "..."` literal, including config entries. Both are required by the runtime to
        // match the same pattern, so validating all of them cannot misfire.
        DECLARED_ID.findAll(code).forEach { match ->
            val id = match.groupValues[1]
            if (!ID.matches(id)) {
                issues.error(
                    "invalid_id",
                    "id \"$id\" 不合法：只允许字母、数字、点、下划线和连字符。",
                )
            }
        }

        // Capability notices. Not errors — the API reference documents both, and a script that needs
        // them is legitimate. They are surfaced so the user is told what the script will be able to
        // do before it runs.
        if (Regex("""context\s*\.\s*root\b""").containsMatchIn(code)) {
            issues.warn(
                "uses_root",
                "脚本使用了 context.root:exec，运行时会以 root 执行命令；" +
                    "这需要用户已在 Root 管理器中授予 REAREye。",
            )
        }
        if (Regex("""context\s*\.\s*http\b""").containsMatchIn(code)) {
            issues.warn("uses_network", "脚本使用了 context.http，运行时会发起网络请求。")
        }

        return LuaScriptValidation(issues)
    }

    /**
     * Returns [source] with Lua comments blanked out, preserving length and line breaks.
     *
     * A full lexer is not needed, but quote-awareness is: `--` inside a string is not a comment, and
     * a string containing `--[[` must not swallow the rest of the file. Long brackets (`[[ ]]`,
     * `[=[ ]=]`) are handled because Lua allows them for both strings and comments.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0

        fun blankUntil(end: Int) {
            // Keep newlines so any line-based reasoning still lines up.
            for (index in i until end) {
                out.append(if (source[index] == '\n') '\n' else ' ')
            }
            i = end
        }

        while (i < source.length) {
            val c = source[i]

            // Quoted string: copy verbatim.
            if (c == '"' || c == '\'') {
                out.append(c)
                i++
                while (i < source.length) {
                    val ch = source[i]
                    out.append(ch)
                    i++
                    if (ch == '\\' && i < source.length) {
                        out.append(source[i])
                        i++
                        continue
                    }
                    if (ch == c) break
                }
                continue
            }

            if (c == '[') {
                val level = longBracketLevel(source, i)
                if (level >= 0) {
                    val end = longBracketEnd(source, i + level + 2, level)
                    out.append(source, i, end)
                    i = end
                    continue
                }
            }

            if (c == '-' && i + 1 < source.length && source[i + 1] == '-') {
                val level = longBracketLevel(source, i + 2)
                if (level >= 0) {
                    blankUntil(longBracketEnd(source, i + level + 4, level))
                } else {
                    var end = source.indexOf('\n', i)
                    if (end < 0) end = source.length
                    blankUntil(end)
                }
                continue
            }

            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Number of `=` in a long bracket opening at [index], or -1 when there is none. */
    private fun longBracketLevel(source: String, index: Int): Int {
        if (index >= source.length || source[index] != '[') return -1
        var cursor = index + 1
        var equals = 0
        while (cursor < source.length && source[cursor] == '=') {
            equals++
            cursor++
        }
        return if (cursor < source.length && source[cursor] == '[') equals else -1
    }

    /** Index just past the long bracket opened with [level] `=` signs, starting the search at [from]. */
    private fun longBracketEnd(source: String, from: Int, level: Int): Int {
        val closing = "]" + "=".repeat(level) + "]"
        val found = source.indexOf(closing, from)
        return if (found < 0) source.length else found + closing.length
    }
}

data class LuaScriptIssue(
    val code: String,
    val message: String,
    val severity: Severity,
) {
    enum class Severity { ERROR, WARNING }
}

class LuaScriptValidation(val issues: List<LuaScriptIssue>) {
    val errors: List<LuaScriptIssue> get() = issues.filter { it.severity == LuaScriptIssue.Severity.ERROR }
    val warnings: List<LuaScriptIssue> get() = issues.filter { it.severity == LuaScriptIssue.Severity.WARNING }
    val passed: Boolean get() = errors.isEmpty()

    /** Report fed back to the model when a generation is rejected. */
    fun report(): String = buildString {
        if (passed) {
            append("校验通过")
            if (warnings.isNotEmpty()) append("（有 ${warnings.size} 条提示）")
            return@buildString
        }
        append("脚本校验未通过，发现 ${errors.size} 个问题：\n")
        errors.forEachIndexed { index, issue ->
            append("${index + 1}. [${issue.code}] ${issue.message}\n")
        }
        if (warnings.isNotEmpty()) {
            append("\n另需注意：\n")
            warnings.forEach { append("- [${it.code}] ${it.message}\n") }
        }
    }
}

private fun MutableList<LuaScriptIssue>.error(code: String, message: String) {
    add(LuaScriptIssue(code, message, LuaScriptIssue.Severity.ERROR))
}

private fun MutableList<LuaScriptIssue>.warn(code: String, message: String) {
    add(LuaScriptIssue(code, message, LuaScriptIssue.Severity.WARNING))
}
