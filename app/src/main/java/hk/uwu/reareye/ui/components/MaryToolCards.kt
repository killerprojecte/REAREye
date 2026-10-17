package hk.uwu.reareye.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ChatEntry
import hk.uwu.reareye.agent.PendingApproval
import hk.uwu.reareye.agent.interaction.AskAnswer
import hk.uwu.reareye.agent.interaction.AskOption
import hk.uwu.reareye.agent.interaction.AskRequest
import hk.uwu.reareye.agent.interaction.AskToolEnvironment
import hk.uwu.reareye.agent.interaction.InteractionPurpose
import hk.uwu.reareye.agent.model.ToolRisk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Remember once per session, outside LazyColumn. No request/question bodies are retained here. */
@Stable
internal class ToolCardExpansion {
    private data class Slot(
        val expanded: Boolean,
        val pendingId: String?,
        val submitted: Boolean = false
    )

    private val slots = mutableStateMapOf<String, Slot>()
    private val drafts = mutableMapOf<String, ToolAnswerDraft>()

    fun isExpanded(key: String, pendingId: String? = null): Boolean {
        val slot = slots[key] ?: return pendingId != null
        return if (slot.pendingId == pendingId) slot.expanded else pendingId != null
    }

    /** Call from SideEffect; a pending interaction ending forces collapse exactly once. */
    fun synchronize(key: String, pendingId: String?) {
        val old = slots[key]
        if (old == null || old.pendingId != pendingId) {
            old?.pendingId?.let { drafts.remove(it) }
            slots[key] = Slot(expanded = pendingId != null, pendingId = pendingId)
        }
    }

    fun toggle(key: String, pendingId: String? = null) {
        val expanded = isExpanded(key, pendingId)
        synchronize(key, pendingId)
        slots[key] = slots.getValue(key).copy(expanded = !expanded)
    }

    fun accepted(key: String, pendingId: String) {
        synchronize(key, pendingId)
        slots[key] = slots.getValue(key).copy(expanded = false, submitted = true)
    }

    fun isSubmitted(key: String, pendingId: String?): Boolean = pendingId != null &&
            slots[key]?.let { it.pendingId == pendingId && it.submitted } == true

    internal fun draft(pendingId: String): ToolAnswerDraft =
        drafts.getOrPut(pendingId) { ToolAnswerDraft() }
}

@Stable
internal class ToolAnswerDraft {
    var selected by mutableStateOf(emptyList<String>())
    var custom by mutableStateOf("")
    var failed by mutableStateOf(false)
    fun answer(allowCustom: Boolean): AskAnswer =
        AskAnswer(selected, custom.takeIf { allowCustom && it.isNotBlank() })

    fun select(id: String, multiple: Boolean) {
        failed = false
        selected = if (multiple) {
            if (id in selected) selected - id else selected + id
        } else listOf(id)
        if (!multiple) custom = ""
    }

    fun editCustom(value: String, multiple: Boolean) {
        custom = value
        failed = false
        if (!multiple && value.isNotBlank()) selected = emptyList()
    }

    fun submit(answer: AskAnswer, onSubmit: (List<String>, String?) -> Boolean) {
        failed = !onSubmit(answer.selectedIds, answer.customText)
    }
}

internal data class MaryAskHistory(val request: AskRequest, val answer: AskAnswer?)

/** Strictly use the existing Ask schema. A malformed/legacy payload falls back to raw tool data. */
internal fun parseMaryAskHistory(argumentsJson: String, resultJson: String?): MaryAskHistory? =
    runCatching {
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        require(args.keys.all {
            it in setOf(
                "question",
                "option_ids",
                "option_labels",
                "multiple"
            )
        })
        fun strings(value: JsonElement?): List<String> = value?.jsonArray?.map {
            it.jsonPrimitive.also { primitive -> require(primitive.isString) }.content
        } ?: emptyList()

        val ids = strings(args["option_ids"])
        val labels = strings(args["option_labels"])
        require(ids.size == labels.size)
        val question = args.getValue("question").jsonPrimitive.also { require(it.isString) }.content
        val request = AskRequest(
            id = "history", question = question,
            options = ids.zip(labels) { id, label -> AskOption(id, label) },
            multiple = args["multiple"]?.jsonPrimitive?.boolean ?: false,
            allowCustom = true,
        ).also { it.validate() }
        val answer = resultJson?.let { raw ->
            val result = Json.parseToJsonElement(raw).jsonObject
            require("selected_ids" in result)
            val custom = result["custom_text"]?.let {
                if (it is JsonNull) null else it.jsonPrimitive.also { primitive -> require(primitive.isString) }.content
            }
            AskAnswer(strings(result["selected_ids"]), custom).also { request.validateAnswer(it) }
        }
        MaryAskHistory(request, answer)
    }.getOrNull()

@Composable
internal fun MaryToolCallCard(
    entry: ChatEntry.ToolCall,
    expansion: ToolCardExpansion,
    pendingAsk: AskRequest? = null,
    pendingApproval: PendingApproval? = null,
    onAnswer: (String, List<String>, String?) -> Boolean,
    onApprove: (String, Boolean) -> Boolean,
) {
    // Ownership is resolved by the caller using callId, never by the last Ask in the list.
    val token = pendingAsk?.let { "ask:${it.id}" } ?: pendingApproval?.let { "approval:${it.id}" }
    val submitted = expansion.isSubmitted(entry.id, token)
    val history = remember(entry.name, entry.argumentsJson, entry.result) {
        if (entry.name == AskToolEnvironment.NAME && entry.result?.isError != true) {
            parseMaryAskHistory(entry.argumentsJson, entry.result?.content)
        } else null
    }
    val question = pendingAsk ?: history?.request
    val draft = token?.let { expansion.draft(it) }
    val answer = history?.answer ?: if (submitted && pendingAsk != null) {
        draft?.answer(pendingAsk.allowCustom && pendingAsk.purpose != InteractionPurpose.CONTEXT_COMPACTION)
    } else null
    val error = entry.result?.isError == true
    var showApprovalDetails by remember(entry.id, entry.argumentsJson) { mutableStateOf(false) }
    val detailsRotation by animateFloatAsState(
        if (showApprovalDetails) 180f else 0f,
        tween(MARY_TOOL_MOTION_MILLIS, easing = FastOutSlowInEasing),
        label = "tool-arguments-arrow",
    )
    val status = stringResource(
        when {
            error -> R.string.mary_tool_failed
            token != null && submitted -> R.string.mary_tool_done
            pendingAsk != null -> R.string.mary_tool_waiting_answer
            pendingApproval != null -> R.string.mary_tool_waiting_approval
            entry.running -> R.string.mary_tool_running
            entry.result == null -> R.string.mary_tool_interrupted
            else -> R.string.mary_tool_done
        }
    )
    ToolCardShell(
        key = entry.id, expansion = expansion, pendingId = token,
        title = when {
            question != null -> stringResource(R.string.mary_tool_question_title)
            pendingApproval != null -> stringResource(R.string.mary_confirmation_permissions)
            else -> entry.name
        },
        status = status, isError = error,
        icon = if (question != null) ToolQuestionIcon else if (pendingApproval != null) ToolPermissionIcon else Icons.Outlined.Build,
        summary = question?.let { answerSummary(it, answer) },
        isDanger = pendingApproval?.request?.risk == ToolRisk.DESTRUCTIVE,
    ) {
        if (question != null) {
            QuestionContents(
                question,
                answer,
                pendingAsk != null && !submitted,
                draft
            ) { selected, custom ->
                val accepted = onAnswer(question.id, selected, custom)
                if (accepted && token != null) expansion.accepted(entry.id, token)
                accepted
            }
        } else if (pendingApproval != null) {
            Text(
                stringResource(R.string.mary_approval_body, entry.name), fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Column {
                Text(
                    stringResource(
                        when (pendingApproval.request.risk) {
                            ToolRisk.READ_ONLY -> R.string.mary_confirmation_risk_read_only
                            ToolRisk.MUTATING -> R.string.mary_confirmation_risk_changes
                            ToolRisk.DESTRUCTIVE -> R.string.mary_confirmation_risk_destructive
                        }
                    ), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                // Keep this slot inside the risk group: the shell's spacedBy must not
                // insert/remove an immediate 8dp gap at either end of the transition.
                AnimatedVisibility(
                    showApprovalDetails, enter = maryToolExpand(), exit = maryToolCollapse(),
                    modifier = Modifier.testTag("mary-tool-details-${entry.id}")
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        RawToolContents(entry.argumentsJson, entry.result?.content, error)
                    }
                }
            }
        } else {
            RawToolContents(entry.argumentsJson, entry.result?.content, error)
        }
        if (pendingApproval != null && !submitted && token != null) {
            if (draft?.failed == true) SubmissionFailure()
            MaryConfirmationActions(entry.id) {
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .testTag("mary-tool-arguments-${entry.id}")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) {
                            showApprovalDetails = !showApprovalDetails
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        stringResource(R.string.mary_tool_arguments), fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Icon(
                        Icons.Outlined.KeyboardArrowDown, contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(detailsRotation)
                    )
                }
                MaryConfirmationActionButton(
                    stringResource(R.string.mary_approval_deny),
                    onClick = {
                        if (onApprove(pendingApproval.id, false)) expansion.accepted(
                            entry.id,
                            token
                        )
                        else draft?.failed = true
                    })
                MaryConfirmationActionButton(
                    stringResource(R.string.mary_confirmation_allow_once),
                    primary = true,
                    onClick = {
                        if (onApprove(pendingApproval.id, true)) expansion.accepted(entry.id, token)
                        else draft?.failed = true
                    })
            }
        }
    }
}

@Composable
internal fun MaryQuestionCard(
    entry: ChatEntry.Question,
    expansion: ToolCardExpansion,
    pending: Boolean,
    onAnswer: (String, List<String>, String?) -> Boolean,
) {
    val token =
        if (pending && entry.answer == null && !entry.interrupted) "ask:${entry.request.id}" else null
    val submitted = expansion.isSubmitted(entry.id, token)
    val draft = token?.let { expansion.draft(it) }
    val answer = entry.answer ?: if (submitted) {
        draft?.answer(entry.request.allowCustom && entry.request.purpose != InteractionPurpose.CONTEXT_COMPACTION)
    } else null
    val status = stringResource(
        when {
            entry.interrupted -> R.string.mary_tool_interrupted
            token != null && !submitted -> R.string.mary_tool_waiting_answer
            else -> R.string.mary_tool_done
        }
    )
    ToolCardShell(
        entry.id, expansion, token, stringResource(R.string.mary_tool_question_title),
        status, entry.interrupted, ToolQuestionIcon, answerSummary(entry.request, answer)
    ) {
        QuestionContents(
            entry.request,
            answer,
            token != null && !submitted,
            draft
        ) { selected, custom ->
            val accepted = onAnswer(entry.request.id, selected, custom)
            if (accepted && token != null) expansion.accepted(entry.id, token)
            accepted
        }
    }
}

@Composable
private fun ToolCardShell(
    key: String,
    expansion: ToolCardExpansion,
    pendingId: String?,
    title: String,
    status: String,
    isError: Boolean,
    icon: ImageVector,
    summary: String?,
    isDanger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = expansion.isExpanded(key, pendingId)
    SideEffect { expansion.synchronize(key, pendingId) }
    Box(Modifier
        .fillMaxWidth()
        .testTag("mary-tool-card-$key")) {
        MaryConfirmationCard(
            key = key, category = title, icon = icon, expanded = expanded, status = status,
            toggleLabel = stringResource(if (expanded) R.string.mary_tool_collapse else R.string.mary_tool_expand),
            onToggle = { expansion.toggle(key, pendingId) }, summary = summary,
            isError = isError, isDanger = isDanger, content = content,
        )
    }
}

private fun answerSummary(request: AskRequest, answer: AskAnswer?): String? = answer?.let {
    val labels = request.options.filter { option -> option.id in it.selectedIds }
        .map { option -> option.label }
    (labels + listOfNotNull(it.customText?.takeIf(String::isNotBlank))).joinToString(" · ")
}

@Composable
private fun QuestionContents(
    request: AskRequest,
    answer: AskAnswer?,
    editable: Boolean,
    draft: ToolAnswerDraft?,
    onSubmit: (List<String>, String?) -> Boolean,
) {
    val compaction = request.purpose == InteractionPurpose.CONTEXT_COMPACTION
    val multiple = request.multiple && !compaction
    val allowCustom = request.allowCustom && !compaction
    val options = if (compaction) request.options.take(2) else request.options
    val selection = if (editable) draft?.selected.orEmpty() else answer?.selectedIds.orEmpty()
    val scheme = MiuixTheme.colorScheme
    SelectionContainer {
        Text(
            request.question,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (multiple) Text(
            stringResource(R.string.mary_ask_multiple), style = MiuixTheme.textStyles.footnote2,
            color = scheme.onSurfaceVariantSummary
        )
        options.forEachIndexed { index, option ->
            val selected = option.id in selection
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .testTag("mary-question-option-${request.id}-${option.id}")
                    .clip(shape)
                    .background(if (selected) scheme.onSurfaceVariantSummary.copy(alpha = 0.07f) else Color.Transparent)
                    .then(
                        if (editable && draft != null) Modifier.selectable(
                            selected = selected,
                            role = if (multiple) Role.Checkbox else Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                draft.select(option.id, multiple)
                            },
                        ) else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .testTag("mary-question-number-${request.id}-${option.id}")
                        .clip(RoundedCornerShape(5.dp))
                        .background(scheme.onSurfaceVariantSummary.copy(alpha = if (selected) 0.22f else 0.1f))
                        .border(1.dp, scheme.outline.copy(alpha = 0.22f), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (index + 1).toString(), fontSize = 12.sp,
                        color = scheme.onSurfaceVariantSummary
                    )
                }
                Text(
                    option.label,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mary-question-label-${request.id}-${option.id}"),
                    fontSize = 14.sp,
                    color = scheme.onSurfaceContainer
                )
                Box(
                    Modifier
                        .size(20.dp)
                        .testTag("mary-question-mark-${request.id}-${option.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(
                        Icons.Outlined.Check, contentDescription = null,
                        tint = scheme.onSurfaceVariantSummary, modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
    if (editable && draft != null) {
        if (draft.failed) SubmissionFailure()
        val candidate = draft.answer(allowCustom)
        val valid = runCatching {
            request.copy(options = options, multiple = multiple, allowCustom = allowCustom)
                .validateAnswer(candidate)
        }.isSuccess
        FlowRow(
            Modifier
                .fillMaxWidth()
                .testTag("mary-confirmation-actions-${request.id}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (allowCustom) Row(
                Modifier
                    .weight(1f)
                    .widthIn(min = 120.dp)
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(15.dp)
                )
                BasicTextField(
                    value = draft.custom,
                    onValueChange = { draft.editCustom(it, multiple) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    textStyle = TextStyle(fontSize = 13.sp, color = scheme.onSurfaceContainer),
                    cursorBrush = SolidColor(scheme.onSurfaceContainer),
                    decorationBox = { innerTextField ->
                        Box {
                            if (draft.custom.isEmpty()) Text(
                                stringResource(R.string.mary_ask_custom),
                                fontSize = 13.sp, color = scheme.onSurfaceVariantSummary
                            )
                            innerTextField()
                        }
                    })
            }
            MaryConfirmationActionButton(
                label = stringResource(R.string.mary_ask_submit), primary = true, enabled = valid,
                onClick = { draft.submit(candidate, onSubmit) },
                modifier = Modifier.testTag("mary-tool-submit-${request.id}"),
            )
        }
    } else if (answer != null) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.mary_tool_answer),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(answerSummary(request, answer).orEmpty(), style = MiuixTheme.textStyles.body2)
            }
        }
    }
}

@Composable
private fun SubmissionFailure() {
    Text(
        stringResource(R.string.mary_tool_submit_failed), color = maryErrorColors().content,
        style = MiuixTheme.textStyles.footnote2
    )
}


@Composable
private fun RawToolContents(arguments: String, result: String?, error: Boolean) {
    // Local bounded scrolling, not maxLines/ellipsis: every byte remains accessible and selectable.
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (arguments.isNotBlank()) {
                    Text(
                        stringResource(R.string.mary_tool_arguments),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(arguments, style = MiuixTheme.textStyles.footnote2)
                }
                if (result != null) {
                    Text(
                        stringResource(if (error) R.string.mary_tool_failed else R.string.mary_tool_result),
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (error) maryErrorColors().content else MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        result, style = MiuixTheme.textStyles.footnote2,
                        color = if (error) maryErrorColors().content else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

/** Use the same standard, proportional hand as the approval mode control. */
private val ToolPermissionIcon = Icons.Outlined.FrontHand

/** Standard outlined geometry keeps the frame centered and the question mark optically balanced. */
private val ToolQuestionIcon = Icons.AutoMirrored.Outlined.HelpOutline
