package hk.uwu.reareye.ui.components.config

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.mixui.MixUiBackend
import hk.uwu.reareye.ui.theme.AppThemeMode
import hk.uwu.reareye.ui.theme.AppThemePreferences
import hk.uwu.reareye.ui.theme.AppMochiThemeStyle
import hk.uwu.reareye.ui.theme.ThemeStyleSheet
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.NonCancellable
import hk.uwu.reareye.ui.components.rememberPackageIconBitmap
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigCategoryIcon
import hk.uwu.reareye.ui.config.ConfigGroup
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigNode
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.ModuleSettingsController
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.StoreApiProvider
import hk.uwu.reareye.ui.config.normalizeRearStoreCustomDomainInput
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.config.validateRearStoreCustomDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.Slider
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.TextField
import hk.uwu.mixui.components.ArrowPreference
import hk.uwu.mixui.components.PreferenceRow
import hk.uwu.mixui.components.GlassSelectPreference
import hk.uwu.mixui.components.GlassChoicePreference
import hk.uwu.mixui.components.ChoiceOption
import hk.uwu.mixui.components.SwitchPreference
import hk.uwu.mixui.theme.MixTheme

@Composable
fun ConfigNodeRow(
    node: ConfigNode,
    prefsManager: PrefsManager,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    when (node) {
        is ConfigCategory -> ConfigCategoryNodeRow(
            category = node,
            onClick = { onOpenCategory(node) }
        )

        is ConfigGroup -> Unit

        is ConfigItem -> ConfigItemNodeRow(
            item = node,
            prefsManager = prefsManager,
            onOpenAppList = onOpenAppList,
            onOpenManager = onOpenManager,
            onPreferenceChanged = onPreferenceChanged,
        )
    }
}

@Composable
fun ConfigCategoryNodeRow(category: ConfigCategory, onClick: () -> Unit) {
    PreferenceRow(
        title = stringResource(category.titleRes),
        summary = category.descriptionRes?.let { stringResource(it) },
        startAction = category.icon?.let { icon ->
            {
                ConfigCategoryStartIcon(icon = icon)
            }
        },
        onClick = onClick
    )
}

@Composable
private fun ConfigCategoryStartIcon(icon: ConfigCategoryIcon) {
    when (icon) {
        is ConfigCategoryIcon.Compose -> {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon.imageVector,
                    contentDescription = null,
                    tint = MixTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        is ConfigCategoryIcon.Package -> {
            val context = LocalContext.current
            val imageBitmap = rememberPackageIconBitmap(
                packageManager = context.packageManager,
                packageName = icon.packageName,
            )

            if (imageBitmap != null) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
fun ConfigItemNodeRow(
    item: ConfigItem,
    prefsManager: PrefsManager,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    item.type.RenderInput(
        item = item,
        prefsManager = prefsManager,
        onOpenAppList = onOpenAppList,
        onOpenManager = onOpenManager,
        onPreferenceChanged = onPreferenceChanged,
    )
}

@Composable
fun BooleanConfigInput(
    item: ConfigItem,
    defaultValue: Boolean,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    val context = LocalContext.current
    val checked = remember(item.key) {
        mutableStateOf(prefsManager.getBoolean(item.key, defaultValue))
    }

    SwitchPreference(
        title = stringResource(item.titleRes),
        summary = item.descriptionRes?.let { stringResource(it) },
        checked = checked.value,
        onCheckedChange = {
            checked.value = it
            prefsManager.putBoolean(item.key, it)
            if (item.key == ConfigKeys.MODULE_HIDE_LAUNCHER_ENTRY) {
                ModuleSettingsController.syncLauncherEntryVisibility(context, hidden = it)
            }
            onPreferenceChanged(item)
        }
    )
}

@Composable
fun AppListConfigInput(
    item: ConfigItem,
    defaultValues: Set<String>,
    prefsManager: PrefsManager,
    onClick: () -> Unit,
) {
    val remotePrefsStatusRevision = rememberRemotePrefsStatusRevision()
    var selectedCount by remember(item.key) { mutableIntStateOf(0) }
    var selectionReady by remember(item.key) { mutableStateOf(false) }

    LaunchedEffect(item.key, remotePrefsStatusRevision) {
        val remoteReady = withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }
        if (!remoteReady) {
            selectionReady = false
            return@LaunchedEffect
        }
        selectedCount = withContext(Dispatchers.IO) {
            prefsManager.getStringSet(item.key, defaultValues).size
        }
        selectionReady = true
    }

    val selectedSummary = if (selectionReady) {
        stringResource(R.string.config_selected_apps, selectedCount)
    } else {
        stringResource(R.string.rear_widget_loading_data)
    }
    val description = item.descriptionRes?.let { stringResource(it) }
    val summary = if (description.isNullOrBlank()) {
        selectedSummary
    } else {
        "$description\n$selectedSummary"
    }

    PreferenceRow(
        title = stringResource(item.titleRes),
        summary = summary,
        onClick = onClick
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun MaskMultiSelectConfigInput(
    item: ConfigItem,
    defaultValue: Int,
    options: List<ConfigType.MaskOption>,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val popupScope = rememberCoroutineScope()
    val showModePopup = remember(item.key) { mutableStateOf(false) }
    var selectedMask by remember(item.key) {
        mutableIntStateOf(prefsManager.getInt(item.key, defaultValue))
    }
    val optionTitles = remember(item.key, options, configuration) {
        options.map { context.getString(it.titleRes) }
    }

    val selectedLabels = options
        .mapIndexedNotNull { index, option ->
            if ((selectedMask and option.maskValue) != 0) optionTitles[index] else null
        }

    val selectedSummary = if (selectedLabels.isEmpty()) {
        stringResource(R.string.lyric_display_mode_none)
    } else {
        selectedLabels.joinToString(separator = " / ")
    }
    val description = item.descriptionRes?.let { stringResource(it) }
    val summary = if (description.isNullOrBlank()) {
        selectedSummary
    } else {
        "$description\n$selectedSummary"
    }

    GlassChoicePreference(
        title = stringResource(item.titleRes),
        summary = summary,
        expanded = showModePopup.value,
        onExpandedChange = { showModePopup.value = it },
        multiple = true,
        options = options.mapIndexed { index, option ->
            ChoiceOption(title = optionTitles[index], selected = (selectedMask and option.maskValue) != 0)
        },
        onOptionClick = { index ->
            val nextMask = selectedMask xor options[index].maskValue
            popupScope.launch {
                withFrameNanos { }
                selectedMask = nextMask
                prefsManager.putInt(item.key, selectedMask)
                onPreferenceChanged(item)
            }
        },
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun EnumSingleSelectConfigInput(
    item: ConfigItem,
    defaultValue: Int,
    options: List<ConfigType.EnumOption>,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    if (item.key == ConfigKeys.MODULE_THEME_MODE) {
        ThemeStyleConfigInput(item, prefsManager, onPreferenceChanged)
        return
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val popupScope = rememberCoroutineScope()
    var selectedValue by remember(item.key) {
        mutableIntStateOf(
            prefsManager.getInt(item.key, defaultValue)
        )
    }
    val selectedOptionValue = selectedValue
    val enabled = true
    val optionTitles = remember(item.key, options, configuration) {
        options.map { context.getString(it.titleRes) }
    }

    val selectedLabel = options
        .indexOfFirst { it.value == selectedOptionValue }
        .takeIf { it >= 0 }
        ?.let { optionTitles[it] }
        ?: optionTitles.firstOrNull().orEmpty()
    val description = item.descriptionRes?.let { stringResource(it) }
    val summary = if (description.isNullOrBlank()) {
        selectedLabel
    } else {
        "$description\n$selectedLabel"
    }

    GlassSelectPreference(
        title = stringResource(item.titleRes),
        summary = summary,
        items = optionTitles,
        selectedIndex = options.indexOfFirst { it.value == selectedOptionValue },
        enabled = enabled,
        onSelectedIndexChange = { index ->
            val option = options[index]
            popupScope.launch {
                withFrameNanos { }
                prefsManager.putInt(item.key, option.value)
                selectedValue = option.value
                onPreferenceChanged(item)
            }
        },
    )
}

/** The production Settings entry. Only a successful atomic save notifies the app host. */
@Composable
internal fun ThemeStyleConfigInput(
    item: ConfigItem,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentOnPreferenceChanged by rememberUpdatedState(onPreferenceChanged)
    var mode by remember(prefsManager) { mutableStateOf(AppThemePreferences.readMode(prefsManager.prefs)) }
    var style by remember(prefsManager) { mutableStateOf(AppMochiThemeStyle.fromValue(
        prefsManager.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, AppMochiThemeStyle.default.value))) }
    var navigationStyle by remember(prefsManager) { mutableStateOf(AppThemePreferences.readNavigationStyle(prefsManager.prefs)) }
    var show by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val themeRevision by AppThemePreferences.changes.collectAsState()
    LaunchedEffect(themeRevision, show) {
        if (!show) {
            navigationStyle = AppThemePreferences.readNavigationStyle(prefsManager.prefs)
            mode = AppThemePreferences.readMode(prefsManager.prefs)
            style = AppMochiThemeStyle.fromValue(prefsManager.getInt(
                ConfigKeys.MODULE_MOCHI_THEME_STYLE, AppMochiThemeStyle.default.value))
        }
    }
    ArrowPreference(
        modifier = Modifier.testTag("theme-style-entry"),
        title = stringResource(R.string.theme_editor_title),
        summary = stringResource(mode.titleRes) + if (mode.backend == MixUiBackend.Mochi) {
            " · " + stringResource(style.titleRes)
        } else "",
        onClick = { if (!saving) {
            navigationStyle = AppThemePreferences.readNavigationStyle(prefsManager.prefs)
            mode = AppThemePreferences.readMode(prefsManager.prefs)
            style = AppMochiThemeStyle.fromValue(prefsManager.getInt(
                ConfigKeys.MODULE_MOCHI_THEME_STYLE, AppMochiThemeStyle.default.value))
            failed = false
            show = true
        } },
    )
    ThemeStyleSheet(
        show = show, appliedMode = mode, appliedStyle = style, appliedNavigationStyle = navigationStyle,
        saving = saving, saveFailed = failed,
        onDismiss = { if (!saving) { show = false; failed = false } },
        onSave = { draft ->
            if (!saving) {
                saving = true
                failed = false
                scope.launch {
                    // Once commit starts, navigation must not cancel delivery of its result.
                    withContext(NonCancellable) {
                        val saved = withContext(Dispatchers.IO) {
                            AppThemePreferences.saveTheme(prefsManager.prefs, draft.mode, draft.mochiStyle, draft.navigationStyle)
                        }
                        if (saved) {
                            mode = draft.mode
                            style = draft.mochiStyle
                            navigationStyle = draft.navigationStyle
                            show = false
                            currentOnPreferenceChanged(item)
                        } else {
                            failed = true
                        }
                        saving = false
                    }
                }
            }
        },
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun RearStoreApiConfigInput(
    item: ConfigItem,
    apiConfig: ConfigType.RearStoreApi,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val popupScope = rememberCoroutineScope()
    var selectedProviderValue by remember(item.key) {
        mutableIntStateOf(prefsManager.getInt(item.key, apiConfig.defaultProviderValue))
    }
    var customDomain by remember(apiConfig.customDomainKey) {
        mutableStateOf(prefsManager.getString(apiConfig.customDomainKey, ""))
    }
    val provider = StoreApiProvider.fromValue(selectedProviderValue)
    val validation = remember(customDomain) { validateRearStoreCustomDomain(customDomain) }
    val optionTitles = remember(configuration) {
        StoreApiProvider.selectableEntries.map { context.getString(it.titleRes) }
    }
    val selectedLabel = optionTitles[StoreApiProvider.selectableEntries.indexOf(provider)]
    val description = item.descriptionRes?.let { stringResource(it) }
    val summary = buildString {
        if (!description.isNullOrBlank()) {
            append(description)
            append('\n')
        }
        append(selectedLabel)
        if (provider == StoreApiProvider.CUSTOM && validation != null) {
            append('\n')
            append(validation.baseUrl)
        }
    }
    val customHelperText = when {
        customDomain.isBlank() -> stringResource(R.string.module_store_api_custom_domain_help)
        validation == null -> stringResource(R.string.module_store_api_custom_domain_invalid)
        else -> validation.baseUrl
    }
    val customHelperColor = when {
        customDomain.isBlank() -> MixTheme.colorScheme.onSurfaceVariantSummary
        validation == null -> MixTheme.colorScheme.error
        else -> MixTheme.colorScheme.onSurfaceVariantSummary
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        GlassSelectPreference(
            title = stringResource(item.titleRes),
            summary = summary,
            items = optionTitles,
            selectedIndex = StoreApiProvider.selectableEntries.indexOf(provider),
            onSelectedIndexChange = { index ->
                val option = StoreApiProvider.selectableEntries[index]
                popupScope.launch {
                    withFrameNanos { }
                    selectedProviderValue = option.value
                    prefsManager.putInt(item.key, selectedProviderValue)
                    onPreferenceChanged(item)
                }
            },
        )

        if (provider == StoreApiProvider.CUSTOM) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp),
            ) {
                TextField(
                    value = customDomain,
                    onValueChange = {
                        customDomain = normalizeRearStoreCustomDomainInput(it)
                        prefsManager.putString(apiConfig.customDomainKey, customDomain)
                        onPreferenceChanged(item)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.module_store_api_custom_domain),
                    singleLine = true,
                )
                Text(
                    text = customHelperText,
                    fontSize = 12.sp,
                    color = customHelperColor,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
fun FloatSliderConfigInput(
    item: ConfigItem,
    sliderConfig: ConfigType.FloatSlider,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    var selectedValue by remember(item.key) {
        mutableFloatStateOf(
            sliderConfig.normalizeValue(
                prefsManager.getFloat(
                    item.key,
                    sliderConfig.defaultValue
                )
            )
        )
    }
    val description = item.descriptionRes?.let { stringResource(it) }
    val valueText = remember(selectedValue, sliderConfig) {
        sliderConfig.formatValue(selectedValue)
    }
    val valueSummary = stringResource(R.string.config_slider_current_value, valueText)
    val summary = if (description.isNullOrBlank()) {
        valueSummary
    } else {
        "$description\n$valueSummary"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = stringResource(item.titleRes),
            fontSize = 17.sp,
        )
        Text(
            text = summary,
            style = MixTheme.textStyles.body2,
            color = MixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Slider(
            value = selectedValue,
            onValueChange = {
                val normalizedValue = sliderConfig.normalizeValue(it)
                selectedValue = normalizedValue
                prefsManager.putFloat(item.key, normalizedValue)
                onPreferenceChanged(item)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            valueRange = sliderConfig.minValue..sliderConfig.maxValue,
            steps = sliderConfig.steps,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = sliderConfig.formatValue(sliderConfig.minValue),
                style = MixTheme.textStyles.body2,
                color = MixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = sliderConfig.formatValue(sliderConfig.maxValue),
                style = MixTheme.textStyles.body2,
                color = MixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
fun ManagerConfigInput(item: ConfigItem, onClick: () -> Unit) {
    PreferenceRow(
        title = stringResource(item.titleRes),
        summary = item.descriptionRes?.let { stringResource(it) },
        onClick = onClick,
    )
}
