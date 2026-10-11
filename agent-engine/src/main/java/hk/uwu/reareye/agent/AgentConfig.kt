package hk.uwu.reareye.agent

import hk.uwu.reareye.agent.model.ToolRisk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Provider connection settings, as entered on the Mary settings page. */
@Serializable
data class ProviderConfig(
    /** Base URL including the API version segment, e.g. `https://api.deepseek.com/v1`. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Stored separately (Android Keystore); never written to plain preferences. */
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
) {
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    /** Normalised endpoint for chat completions, tolerant of a trailing slash. */
    fun chatCompletionsUrl(): String = baseUrl.trimEnd('/') + "/chat/completions"

    fun modelsUrl(): String = baseUrl.trimEnd('/') + "/models"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    }
}

/** Everything configurable about the local agent runtime. */
@Serializable
data class AgentConfig(
    val provider: ProviderConfig = ProviderConfig(),
    /** Hard ceiling on model calls per user turn. Guards against tool-call loops. */
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /**
     * Wall-clock ceiling for one user turn, across every step and every tool.
     *
     * A step ceiling alone is not enough: a model that keeps choosing *different* tools can run
     * far longer than [maxIterations] suggests, and a single slow tool can outlast the whole turn.
     */
    val turnBudgetMs: Long = DEFAULT_TURN_BUDGET_MS,
    /** Ceiling for one tool execution, so a hung tool cannot consume the turn. */
    val toolTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
    /** Individual tool results longer than this are truncated before entering the context. */
    val maxToolResultChars: Int = DEFAULT_MAX_TOOL_RESULT_CHARS,
    /**
     * How many times the same tool may be called with identical arguments in one turn before the
     * turn is ended. Repeating a call that already produced a result is never progress.
     */
    val maxRepeatedToolCalls: Int = DEFAULT_MAX_REPEATED_TOOL_CALLS,
    /**
     * When false, even read-only tools ask for confirmation. Mutating tools always ask, whatever
     * this is set to.
     */
    val autoApproveReadOnly: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_MAX_ITERATIONS = 10

        /** Five minutes. Long enough for real work, short enough to bound a runaway turn. */
        const val DEFAULT_TURN_BUDGET_MS = 5 * 60 * 1000L

        const val DEFAULT_TOOL_TIMEOUT_MS = 30 * 1000L
        const val DEFAULT_MAX_TOOL_RESULT_CHARS = 8_000
        const val DEFAULT_MAX_REPEATED_TOOL_CALLS = 3

        val DEFAULT_SYSTEM_PROMPT = """
            你是 Mary，小米背屏增强模块 REAREye 的内置助手。

            你的能力边界：你运行在 REAREye 应用进程内，像普通应用一样工作，
            没有 root 权限。因此你不能切换背屏/主屏、不能结束进程，也不要声称能做这些。

            你能做的是管理 REAREye 自身的配置：背屏卡片与组件、背屏壁纸、场景路由、应用卡片。

            关于工具，这条最重要：
            - 你只能调用本次会话中明确提供给你的工具。工具列表里没有的能力，就是你没有的能力。
            - 绝对不要自行编造或猜测工具名，也不要在回复文本里模拟工具调用语法。
            - 如果用户的要求需要你没有的工具，就直说"我现在还做不到这件事"，
              并说明你需要什么能力，而不是假装已经做了或尝试调用一个不存在的工具。
            - 需要调用工具时，通过正常的工具调用机制发起；不需要工具时，直接用自然语言回答。

            工作原则：
            - 先读后写。改动配置前先用查询类工具确认当前状态，不要把猜测当作事实。
            - 需要用户确认的操作会由界面弹出确认，你只需提出请求。
            - 工具返回失败时，如实转述错误，不要编造成功。
            - 回答简洁，用中文，避免罗列无关信息。
        """.trimIndent()
    }
}

/** Per-turn knobs the UI may override. */
@Serializable
data class AgentFlags(
    @SerialName("allow_mutating") val allowMutating: Boolean = false,
)
