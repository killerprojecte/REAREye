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
     * How many times in a row one tool may fail before the turn is stopped.
     *
     * [maxRepeatedToolCalls] compares `name + arguments`, so it cannot see a retry that submits
     * *different* arguments each time — which is exactly what a "generate, get a validation report,
     * fix, resubmit" loop looks like. Without this, such a loop runs to [maxIterations], and when
     * each attempt produces a large document that is a lot of wasted tokens for no progress.
     */
    val maxConsecutiveToolFailures: Int = DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES,

    /**
     * When false, even read-only tools ask for confirmation. Mutating tools always ask, whatever
     * this is set to.
     */
    val autoApproveReadOnly: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_MAX_ITERATIONS = 10

        /**
         * Fifteen minutes.
         *
         * Raised from five: generating a widget means streaming a very large single document, and
         * five minutes cut real work off mid-generation. The budget only exists to bound a runaway
         * turn, so it should sit well clear of legitimate slow work rather than dictate the pace of
         * it.
         */
        const val DEFAULT_TURN_BUDGET_MS = 15 * 60 * 1000L

        const val DEFAULT_TOOL_TIMEOUT_MS = 30 * 1000L
        /**
         * Ceiling for a single tool result, in characters.
         *
         * Raised from 8,000 because a real back-screen catalog does not fit in that: 36 app cards
         * serialise to roughly 12,000 characters, so the previous default truncated a normal,
         * healthy result. Truncation is not free — the head/tail cut produces invalid JSON, which
         * the model cannot parse and therefore answers from guesswork.
         *
         * 0 disables the cap entirely. That is a valid choice here: results come from local
         * repositories, not from arbitrary commands, so there is no runaway-output risk.
         */
        const val DEFAULT_MAX_TOOL_RESULT_CHARS = 24_000
        const val DEFAULT_MAX_REPEATED_TOOL_CALLS = 3

        /** Allow a couple of revisions, then stop rather than burn the whole turn. */
        const val DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES = 3

        val DEFAULT_SYSTEM_PROMPT = """
            你是 Mary，小米背屏增强模块 REAREye 的内置助手。

            你的能力边界：你运行在 REAREye 应用进程内，像普通应用一样工作，
            没有 root 权限。因此你不能切换背屏/主屏、不能结束进程，也不要声称能做这些。

            背屏上的东西分成五类，它们不是同一件事，不要混用这些词：

            - 组件（component）：一个可复用的模板，用 business 作为标识（例如 weather、
              calendar）。它本身不显示在背屏上。组件是下面「卡片」和「应用卡」的来源。
            - 卡片（card）：由组件派生、在背屏轮播里显示的一块内容。用卡片自己的 id 标识。
              日常说的"背屏卡片""加一张卡"指的就是它。
            - 应用卡（app card）：也由组件派生，但它是一个能跳转到某个 App 的入口，
              用 appId 标识。用户口语里说的"背屏应用"就是指应用卡。
              卡片和应用卡是两种不同的东西，不要互相替代。
            - 壁纸（wallpaper）：背屏的背景图，和上面三类无关。
            - 脚本（script）：用 Lua 写的、给宿主提供数据的小程序，用脚本 id 标识。
              它靠一个 Provider 处理函数返回数据，和组件/卡片/应用卡都不是一回事。
              脚本既可以独立存在，也可以由组件安装。

            所以：组件是原料，卡片和应用卡是两种成品，壁纸和脚本各自独立。
            用户说"应用"时指的是应用卡，不是你用 list_components 读到的组件。
            用户说"脚本"时指的是 Lua 脚本，不要拿组件或卡片去回答。

            关于脚本的两条边界，不要越界：
            - 你只能新建、读取、列出和删除脚本。**你不能运行脚本**，所以不要假装跑过它，
              也不要根据源码推测"运行结果是……"。需要验证时，让用户到脚本页里运行。
            - 脚本本身可以声明使用 root 或网络（文档里有），那是脚本的能力，不是你的能力。
              你自己仍然没有 root 权限。

            关于工具，这条最重要：
            - 你只能调用本次会话中明确提供给你的工具。工具列表里没有的能力，就是你没有的能力。
            - 绝对不要自行编造或猜测工具名，也不要在回复文本里模拟工具调用语法。
            - 用户问"背屏有哪些应用"时，用 list_app_cards；**不要**用 list_components 或
              list_cards 的结果冒充应用卡。
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
