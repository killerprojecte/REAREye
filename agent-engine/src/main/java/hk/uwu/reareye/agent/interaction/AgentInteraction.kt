package hk.uwu.reareye.agent.interaction

import hk.uwu.reareye.agent.loop.ToolCallRequest

/** Approval only controls consent; tool implementations retain their own safety checks. */
enum class ApprovalMode { ASK, AUTO, ALLOW_ALL }
enum class ApprovalDecision { ALLOW, DENY, ASK_USER }
typealias AutoApprovalCallback = suspend (ToolCallRequest) -> ApprovalDecision

enum class InteractionPurpose { QUESTION, CONTEXT_COMPACTION }
data class AskOption(val id: String, val label: String)
data class AskRequest(
    val id: String,
    val question: String,
    val options: List<AskOption>,
    val multiple: Boolean = false,
    val allowCustom: Boolean = true,
    val purpose: InteractionPurpose = InteractionPurpose.QUESTION,
    val toolCallId: String? = null,
) {
    fun validate() {
        require(question.isNotBlank()) { "Question must not be blank" }
        require(options.all { it.id.isNotBlank() && it.label.isNotBlank() })
        require(options.map { it.id }.distinct().size == options.size) { "Duplicate option IDs" }
    }

    fun validateAnswer(answer: AskAnswer) {
        validate()
        require(answer.selectedIds.distinct().size == answer.selectedIds.size)
        require(answer.selectedIds.all { id -> options.any { it.id == id } }) { "Unknown option ID" }
        val custom = !answer.customText.isNullOrBlank()
        require(!custom || allowCustom) { "Custom answers are disabled" }
        require(answer.selectedIds.isNotEmpty() || custom) { "An answer is required" }
        require(multiple || answer.selectedIds.size + (if (custom) 1 else 0) == 1) { "Choose one answer" }
    }
}

data class AskAnswer(val selectedIds: List<String> = emptyList(), val customText: String? = null)
typealias AskCallback = suspend (AskRequest) -> AskAnswer

sealed interface AgentInteraction {
    data class Approval(val request: ToolCallRequest) : AgentInteraction
    data class Question(val request: AskRequest) : AgentInteraction
}
