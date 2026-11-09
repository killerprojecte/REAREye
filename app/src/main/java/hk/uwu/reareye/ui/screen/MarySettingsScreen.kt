package hk.uwu.reareye.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderPreset
import hk.uwu.reareye.agent.data.AgentSettingsStore
import hk.uwu.reareye.agent.data.MarySettings
import hk.uwu.reareye.ui.components.MaryApprovalModePreference
import hk.uwu.reareye.ui.components.MarySettingsField
import hk.uwu.reareye.ui.components.MarySettingsHint
import hk.uwu.reareye.ui.components.MarySettingsSectionTitle
import hk.uwu.reareye.ui.components.ModelConfigurationCard
import hk.uwu.reareye.ui.components.ModelDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.CircularProgressIndicator
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.GlassAnchoredMenu
import hk.uwu.mixui.components.GlassMenuOption
import hk.uwu.mixui.components.IconButton
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.LargeTitleColumnScaffold
import hk.uwu.mixui.components.SwitchPreference


@Composable
fun MarySettingsScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPresets by remember { mutableStateOf(false) }
    val store = remember { AgentSettingsStore(context) }
    var settings by remember { mutableStateOf(MarySettings()) }
    var dialogue by remember { mutableStateOf(ModelDraft()) }
    var approval by remember { mutableStateOf(ModelDraft()) }
    var ready by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var dialogueUnreadable by remember { mutableStateOf(false) }
    var approvalUnreadable by remember { mutableStateOf(false) }
    var iterations by remember { mutableStateOf(AgentConfig.DEFAULT_MAX_ITERATIONS.toString()) }
    var failures by remember { mutableStateOf(AgentConfig.DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES.toString()) }
    var budget by remember { mutableStateOf((AgentConfig.DEFAULT_TURN_BUDGET_MS / 1000).toString()) }
    var resultChars by remember { mutableStateOf(AgentConfig.DEFAULT_MAX_TOOL_RESULT_CHARS.toString()) }

    LaunchedEffect(store) {
        val loaded = withContext(Dispatchers.IO) {
            if (store.isReady()) store.loadMarySettings() else null
        }
        loaded?.let {
            settings = it
            dialogue = ModelDraft(it.configuration.provider)
            approval = ModelDraft(it.approvalProvider)
            iterations = it.configuration.maxIterations.toString()
            failures = it.configuration.maxConsecutiveToolFailures.toString()
            budget = (it.configuration.turnBudgetMs / 1000).toString()
            resultChars = it.configuration.maxToolResultChars.toString()
            dialogueUnreadable = store.isApiKeyUnreadable()
            approvalUnreadable = store.isApprovalApiKeyUnreadable()
        }
        ready = loaded != null
        loading = false
    }
    val valid = dialogue.valid() && (settings.inheritApprovalModel || approval.valid()) &&
            iterations.toIntOrNull()?.let { it in 1..50 } == true &&
            failures.toIntOrNull()?.let { it in 1..20 } == true &&
            budget.toIntOrNull()?.let { it in 30..3600 } == true &&
            resultChars.toIntOrNull()?.let { it in 0..200000 } == true

    LargeTitleColumnScaffold(
        title = stringResource(R.string.mary_settings_title),
        bottomObstruction = bottomInnerPadding,
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        },
        actions = {
            MaryProviderPresetMenu(
                expanded = showPresets,
                onExpandedChange = { showPresets = it },
                enabled = ready && !saving,
                independentApproval = !settings.inheritApprovalModel,
                onPreset = { forApproval, preset ->
                    if (forApproval) approval = approval.withPreset(preset)
                    else dialogue = dialogue.withPreset(preset)
                },
            )
            IconButton(
                modifier = Modifier.testTag("mary-settings-save"),
                enabled = ready && valid && !saving,
                onClick = {
                    val next = settings.copy(
                        configuration = settings.configuration.copy(
                            provider = dialogue.build(),
                            maxIterations = iterations.toInt(),
                            maxConsecutiveToolFailures = failures.toInt(),
                            turnBudgetMs = budget.toLong() * 1000,
                            maxToolResultChars = resultChars.toInt(),
                        ),
                        approvalProvider = if (approval.valid()) approval.build() else settings.approvalProvider
                    )
                    val dialogueKey =
                        if (dialogue.clearKey) "" else dialogue.keyDraft.takeIf { it.isNotBlank() }
                    val approvalKey =
                        if (approval.clearKey) "" else approval.keyDraft.takeIf { it.isNotBlank() }
                    saving = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            runCatching {
                                store.saveMarySettings(
                                    next,
                                    dialogueKey,
                                    approvalKey
                                )
                            }.getOrDefault(false)
                        }
                        saving = false
                        if (saved) {
                            settings = next
                            dialogue = ModelDraft(next.configuration.provider)
                            approval = ModelDraft(next.approvalProvider)
                            dialogueUnreadable = store.isApiKeyUnreadable()
                            approvalUnreadable = store.isApprovalApiKeyUnreadable()
                        }
                        Toast.makeText(
                            context,
                            if (saved) R.string.mary_settings_saved else R.string.mary_settings_save_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }) {
                if (saving) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Check, stringResource(R.string.mary_settings_save))
            }
        },
    ) {
        if (!loading && !ready) Text(stringResource(R.string.mary_settings_not_ready))
        if (ready) {
            ModelConfigurationCard(
                R.string.mary_settings_provider,
                dialogue,
                { dialogue = it },
                dialogueUnreadable
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarySettingsSectionTitle(R.string.mary_approval_policy)
                Card(Modifier.fillMaxWidth()) {
                    MaryApprovalModePreference(
                        mode = settings.approvalMode,
                        onSelected = { settings = settings.copy(approvalMode = it) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.mary_approval_inherit),
                        checked = settings.inheritApprovalModel,
                        onCheckedChange = {
                            settings = settings.copy(inheritApprovalModel = it)
                        })
                }
            }
            if (!settings.inheritApprovalModel) {
                ModelConfigurationCard(
                    R.string.mary_approval_model,
                    approval,
                    { approval = it },
                    approvalUnreadable
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarySettingsSectionTitle(R.string.mary_settings_behaviour)
                Card(Modifier.fillMaxWidth()) {
                    MarySettingsField(
                        iterations,
                        { iterations = it },
                        R.string.mary_behaviour_iterations
                    )
                    MarySettingsField(
                        failures,
                        { failures = it },
                        R.string.mary_behaviour_tool_failures
                    )
                    MarySettingsField(
                        budget,
                        { budget = it },
                        R.string.mary_behaviour_turn_budget
                    )
                    MarySettingsField(
                        resultChars,
                        { resultChars = it },
                        R.string.mary_behaviour_result_chars
                    )
                }
            }
            if (!valid) MarySettingsHint(stringResource(R.string.mary_settings_invalid))
        }
    }
}

/** The same production menu is hosted by settings and by offline interaction tests. */
@Composable
internal fun MaryProviderPresetMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    independentApproval: Boolean,
    onPreset: (forApproval: Boolean, preset: ProviderPreset) -> Unit,
) {
    val targets = if (independentApproval) listOf(false, true) else listOf(false)
    val choices = targets.flatMap { forApproval -> ProviderPreset.entries.map { forApproval to it } }
    GlassAnchoredMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        enabled = enabled,
        options = choices.map { (forApproval, preset) ->
            val target = stringResource(if (forApproval) R.string.mary_settings_ui_approval_draft
                else R.string.mary_settings_ui_dialogue_draft)
            GlassMenuOption(title = if (independentApproval)
                stringResource(R.string.mary_settings_ui_preset_target, preset.label, target)
                else preset.label)
        },
        onOptionClick = { index -> choices[index].let { (forApproval, preset) -> onPreset(forApproval, preset) } },
    ) {
        IconButton(
            modifier = Modifier.testTag("mary-settings-presets"),
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            holdDownState = expanded,
        ) { Icon(Icons.Outlined.Tune, stringResource(R.string.mary_provider_preset)) }
    }
}
