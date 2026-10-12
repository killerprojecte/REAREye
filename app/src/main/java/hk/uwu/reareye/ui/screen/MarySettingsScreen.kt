package hk.uwu.reareye.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.data.AgentSettingsStore
import hk.uwu.reareye.agent.provider.OpenAiCompatibleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Mary's provider configuration: endpoint, API key, model, limits and approval policy.
 *
 * The API key is the only field with special handling — it is encrypted via the system keystore
 * and never written into the module's shared preferences.
 */
@Composable
fun MarySettingsScreen(
    bottomInnerPadding: Dp = 0.dp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val store = remember { AgentSettingsStore(context) }

    var ready by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var keyUnreadable by remember { mutableStateOf(false) }
    var keySet by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    var baseUrl by remember { mutableStateOf(ProviderConfig.DEFAULT_BASE_URL) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var temperatureText by remember { mutableStateOf("") }
    var maxTokensText by remember { mutableStateOf("") }
    var maxIterationsText by remember {
        mutableStateOf(AgentConfig.DEFAULT_MAX_ITERATIONS.toString())
    }
    var maxToolFailuresText by remember {
        mutableStateOf(AgentConfig.DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES.toString())
    }
    var turnBudgetText by remember {
        mutableStateOf((AgentConfig.DEFAULT_TURN_BUDGET_MS / 1000).toString())
    }
    var maxResultCharsText by remember {
        mutableStateOf(AgentConfig.DEFAULT_MAX_TOOL_RESULT_CHARS.toString())
    }
    var autoApproveReadOnly by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val isReady = store.isReady()
            val config = if (isReady) store.load() else null
            Triple(isReady, config, store.isApiKeyUnreadable())
        }
        ready = loaded.first
        keyUnreadable = loaded.third
        keySet = store.loadApiKey().isNotEmpty()
        loaded.second?.let { config ->
            baseUrl = config.provider.baseUrl
            model = config.provider.model
            temperatureText = config.provider.temperature?.toString().orEmpty()
            maxTokensText = config.provider.maxOutputTokens?.toString().orEmpty()
            maxIterationsText = config.maxIterations.toString()
            maxToolFailuresText = config.maxConsecutiveToolFailures.toString()
            turnBudgetText = (config.turnBudgetMs / 1000).toString()
            maxResultCharsText = config.maxToolResultChars.toString()
            autoApproveReadOnly = config.autoApproveReadOnly
        }
        loading = false
    }

    fun currentConfig() = AgentConfig(
        provider = ProviderConfig(
            baseUrl = baseUrl.trim(),
            apiKey = "",
            model = model.trim(),
            temperature = temperatureText.trim().toDoubleOrNull(),
            maxOutputTokens = maxTokensText.trim().toIntOrNull(),
        ),
        maxIterations = maxIterationsText.trim().toIntOrNull()
            ?.coerceIn(1, 50) ?: AgentConfig.DEFAULT_MAX_ITERATIONS,
        maxConsecutiveToolFailures = maxToolFailuresText.trim().toIntOrNull()
            ?.coerceIn(1, 20) ?: AgentConfig.DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES,
        // Seconds in the UI, milliseconds in the model. 30s..60min.
        turnBudgetMs = (turnBudgetText.trim().toIntOrNull()?.coerceIn(30, 3600)
            ?: (AgentConfig.DEFAULT_TURN_BUDGET_MS / 1000).toInt()).toLong() * 1000,
        // 0 is a meaningful value: it disables the cap. Only negative input falls back.
        maxToolResultChars = maxResultCharsText.trim().toIntOrNull()
            ?.coerceIn(0, 200_000) ?: AgentConfig.DEFAULT_MAX_TOOL_RESULT_CHARS,
        autoApproveReadOnly = autoApproveReadOnly,
    )

    fun save() {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (!store.isReady()) return@withContext SaveOutcome.NOT_READY
                val config = currentConfig()
                store.save(config)
                if (apiKeyDraft.isNotBlank()) {
                    if (!store.saveApiKey(apiKeyDraft)) SaveOutcome.KEY_FAILED else SaveOutcome.OK
                } else {
                    SaveOutcome.OK
                }
            }
            when (outcome) {
                SaveOutcome.OK -> {
                    apiKeyDraft = ""
                    keySet = store.loadApiKey().isNotEmpty()
                    keyUnreadable = false
                    Toast.makeText(context, R.string.mary_settings_saved, Toast.LENGTH_SHORT).show()
                }

                SaveOutcome.KEY_FAILED ->
                    Toast.makeText(context, R.string.mary_settings_save_key_failed, Toast.LENGTH_LONG).show()

                SaveOutcome.NOT_READY ->
                    Toast.makeText(context, R.string.mary_settings_save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun test() {
        scope.launch {
            testing = true
            testResult = null
            val config = currentConfig().provider.copy(
                apiKey = if (apiKeyDraft.isNotBlank()) apiKeyDraft.trim() else store.loadApiKey(),
            )
            val message = withContext(Dispatchers.IO) {
                val provider = OpenAiCompatibleProvider(
                    client = OpenAiCompatibleProvider.defaultClient(),
                    configProvider = { config },
                )
                runCatching { provider.listModels() }
                    .fold(
                        onSuccess = { models ->
                            if (models.isEmpty()) {
                                context.getString(R.string.mary_provider_test_ok_empty)
                            } else {
                                context.getString(R.string.mary_provider_test_ok, models.size)
                            }
                        },
                        onFailure = { error ->
                            context.getString(
                                R.string.mary_provider_test_failed,
                                error.message ?: error.javaClass.simpleName,
                            )
                        },
                    )
            }
            testResult = message
            testing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.mary_settings_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { if (ready && !loading) save() }, enabled = ready && !loading) {
                        Icon(imageVector = Icons.Filled.Save, contentDescription = stringResource(R.string.mary_settings_save))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(
                    top = paddingValues.calculateTopPadding() + 12.dp,
                    // The floating bottom bar is drawn over the content, so reserve its height.
                    bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ready && !loading) {
                    ProviderCard(
                        baseUrl = baseUrl,
                        onBaseUrlChange = { baseUrl = it },
                        apiKeyDraft = apiKeyDraft,
                        onApiKeyChange = { apiKeyDraft = it },
                        keySet = keySet,
                        keyUnreadable = keyUnreadable,
                        model = model,
                        onModelChange = { model = it },
                        testing = testing,
                        testResult = testResult,
                        onTest = { test() },
                    )

                    BehaviourCard(
                        temperatureText = temperatureText,
                        onTemperatureChange = { temperatureText = it },
                        maxTokensText = maxTokensText,
                        onMaxTokensChange = { maxTokensText = it },
                        maxIterationsText = maxIterationsText,
                        onMaxIterationsChange = { maxIterationsText = it },
                        maxToolFailuresText = maxToolFailuresText,
                        onMaxToolFailuresChange = { maxToolFailuresText = it },
                        turnBudgetText = turnBudgetText,
                        onTurnBudgetChange = { turnBudgetText = it },
                        maxResultCharsText = maxResultCharsText,
                        onMaxResultCharsChange = { maxResultCharsText = it },
                        autoApproveReadOnly = autoApproveReadOnly,
                        onAutoApproveChange = { autoApproveReadOnly = it },
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionHeader(
                                title = stringResource(R.string.mary_settings_save),
                                summary = null,
                            )
                            Button(
                                onClick = { save() },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                Text(stringResource(R.string.mary_settings_save))
                            }
                        }
                    }
                } else if (!loading && !ready) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.mary_settings_not_ready),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKeyDraft: String,
    onApiKeyChange: (String) -> Unit,
    keySet: Boolean,
    keyUnreadable: Boolean,
    model: String,
    onModelChange: (String) -> Unit,
    testing: Boolean,
    testResult: String?,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(
                title = stringResource(R.string.mary_settings_provider),
                summary = stringResource(R.string.mary_settings_provider_summary),
            )

            TextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.mary_provider_endpoint),
                singleLine = true,
            )
            Hint(stringResource(R.string.mary_provider_endpoint_desc))

            TextField(
                value = apiKeyDraft,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = if (keySet && apiKeyDraft.isEmpty()) {
                    stringResource(R.string.mary_provider_api_key_set)
                } else {
                    stringResource(R.string.mary_provider_api_key_placeholder)
                },
                singleLine = true,
            )
            if (keyUnreadable) {
                Text(
                    text = stringResource(R.string.mary_provider_api_key_unreadable),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
            Hint(stringResource(R.string.mary_provider_api_key_desc))

            TextField(
                value = model,
                onValueChange = onModelChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.mary_provider_model),
                singleLine = true,
            )
            Hint(stringResource(R.string.mary_provider_model_hint))

            Button(
                onClick = onTest,
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (testing) R.string.mary_provider_testing else R.string.mary_provider_test,
                    ),
                )
            }
            testResult?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun BehaviourCard(
    temperatureText: String,
    onTemperatureChange: (String) -> Unit,
    maxTokensText: String,
    onMaxTokensChange: (String) -> Unit,
    maxIterationsText: String,
    onMaxIterationsChange: (String) -> Unit,
    maxToolFailuresText: String,
    onMaxToolFailuresChange: (String) -> Unit,
    turnBudgetText: String,
    onTurnBudgetChange: (String) -> Unit,
    maxResultCharsText: String,
    onMaxResultCharsChange: (String) -> Unit,
    autoApproveReadOnly: Boolean,
    onAutoApproveChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(
                title = stringResource(R.string.mary_settings_behaviour),
                summary = stringResource(R.string.mary_settings_behaviour_summary),
            )

            TextField(
                value = maxIterationsText,
                onValueChange = onMaxIterationsChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.mary_behaviour_iterations),
                singleLine = true,
            )
            Hint(stringResource(R.string.mary_behaviour_iterations_desc))

            TextField(
                value = maxToolFailuresText,
                onValueChange = onMaxToolFailuresChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.mary_behaviour_tool_failures),
                singleLine = true,
            )
            Hint(stringResource(R.string.mary_behaviour_tool_failures_desc))

            TextField(
                value = turnBudgetText,
                onValueChange = onTurnBudgetChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.mary_behaviour_turn_budget),
                singleLine = true,
            )
            Hint(stringResource(R.string.mary_behaviour_turn_budget_desc))

            TextField(
                value = maxResultCharsText,
                onValueChange = onMaxResultCharsChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.mary_behaviour_result_chars),
                singleLine = true,
            )
            Hint(stringResource(R.string.mary_behaviour_result_chars_desc))

            TextField(
                value = temperatureText,
                onValueChange = onTemperatureChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.mary_behaviour_temperature),
                singleLine = true,
            )
            Hint(stringResource(R.string.mary_behaviour_temperature_desc))

            TextField(
                value = maxTokensText,
                onValueChange = onMaxTokensChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.mary_behaviour_max_tokens),
                singleLine = true,
            )
            Hint(stringResource(R.string.mary_behaviour_max_tokens_desc))

            SwitchPreference(
                title = stringResource(R.string.mary_behaviour_auto_read),
                summary = stringResource(R.string.mary_behaviour_auto_read_desc),
                checked = autoApproveReadOnly,
                onCheckedChange = onAutoApproveChange,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, summary: String?) {
    Column {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
        )
        summary?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

private enum class SaveOutcome { OK, KEY_FAILED, NOT_READY }
