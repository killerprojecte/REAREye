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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
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
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    ArrowPreference(
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
                    tint = MiuixTheme.colorScheme.onBackground,
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

    ArrowPreference(
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
    val optionEntries = remember(item.key, optionTitles) {
        optionTitles.map { DropdownItem(title = it) }
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

    Box {
        ArrowPreference(
            title = stringResource(item.titleRes),
            summary = summary,
            holdDownState = showModePopup.value,
            onClick = { showModePopup.value = true }
        )

        OverlayListPopup(
            show = showModePopup.value,
            popupModifier = Modifier,
            popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
            alignment = PopupPositionProvider.Align.End,
            enableWindowDim = true,
            onDismissRequest = { showModePopup.value = false },
            maxHeight = null,
            minWidth = 220.dp,
            renderInRootScaffold = true,
        ) {
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        item = optionEntries[index],
                        optionSize = options.size,
                        isSelected = (selectedMask and option.maskValue) != 0,
                        index = index,
                        dropdownColors = DropdownDefaults.dropdownColors(),
                        onSelectedIndexChange = {
                            val nextMask = selectedMask xor option.maskValue
                            showModePopup.value = false
                            popupScope.launch {
                                withFrameNanos { }
                                selectedMask = nextMask
                                prefsManager.putInt(item.key, selectedMask)
                                onPreferenceChanged(item)
                            }
                        },
                    )
                }
            }
        }
    }
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
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val popupScope = rememberCoroutineScope()
    val showEnumPopup = remember(item.key) { mutableStateOf(false) }
    var selectedValue by remember(item.key) {
        mutableIntStateOf(prefsManager.getInt(item.key, defaultValue))
    }
    val optionTitles = remember(item.key, options, configuration) {
        options.map { context.getString(it.titleRes) }
    }
    val optionEntries = remember(item.key, optionTitles) {
        optionTitles.map { DropdownItem(title = it) }
    }

    val selectedLabel = options
        .indexOfFirst { it.value == selectedValue }
        .takeIf { it >= 0 }
        ?.let { optionTitles[it] }
        ?: optionTitles.firstOrNull().orEmpty()
    val description = item.descriptionRes?.let { stringResource(it) }
    val summary = if (description.isNullOrBlank()) {
        selectedLabel
    } else {
        "$description\n$selectedLabel"
    }

    Box {
        ArrowPreference(
            title = stringResource(item.titleRes),
            summary = summary,
            holdDownState = showEnumPopup.value,
            onClick = { showEnumPopup.value = true }
        )

        OverlayListPopup(
            show = showEnumPopup.value,
            popupModifier = Modifier,
            popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
            alignment = PopupPositionProvider.Align.End,
            enableWindowDim = true,
            onDismissRequest = { showEnumPopup.value = false },
            maxHeight = null,
            minWidth = 220.dp,
            renderInRootScaffold = true,
        ) {
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        item = optionEntries[index],
                        optionSize = options.size,
                        isSelected = selectedValue == option.value,
                        index = index,
                        dropdownColors = DropdownDefaults.dropdownColors(),
                        onSelectedIndexChange = {
                            showEnumPopup.value = false
                            popupScope.launch {
                                withFrameNanos { }
                                selectedValue = option.value
                                prefsManager.putInt(item.key, selectedValue)
                                onPreferenceChanged(item)
                            }
                        },
                    )
                }
            }
        }
    }
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
    val showApiPopup = remember(item.key) { mutableStateOf(false) }
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
    val optionEntries = remember(optionTitles) {
        optionTitles.map { DropdownItem(title = it) }
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
        customDomain.isBlank() -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        validation == null -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box {
            ArrowPreference(
                title = stringResource(item.titleRes),
                summary = summary,
                holdDownState = showApiPopup.value,
                onClick = { showApiPopup.value = true },
            )

            OverlayListPopup(
                show = showApiPopup.value,
                popupModifier = Modifier,
                popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                alignment = PopupPositionProvider.Align.End,
                enableWindowDim = true,
                onDismissRequest = { showApiPopup.value = false },
                maxHeight = null,
                minWidth = 220.dp,
                renderInRootScaffold = true,
            ) {
                ListPopupColumn {
                    StoreApiProvider.selectableEntries.forEachIndexed { index, option ->
                        DropdownImpl(
                            item = optionEntries[index],
                            optionSize = StoreApiProvider.selectableEntries.size,
                            isSelected = provider == option,
                            index = index,
                            dropdownColors = DropdownDefaults.dropdownColors(),
                            onSelectedIndexChange = {
                                showApiPopup.value = false
                                popupScope.launch {
                                    withFrameNanos { }
                                    selectedProviderValue = option.value
                                    prefsManager.putInt(item.key, selectedProviderValue)
                                    onPreferenceChanged(item)
                                }
                            },
                        )
                    }
                }
            }
        }

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
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = sliderConfig.formatValue(sliderConfig.maxValue),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
fun ManagerConfigInput(item: ConfigItem, onClick: () -> Unit) {
    ArrowPreference(
        title = stringResource(item.titleRes),
        summary = item.descriptionRes?.let { stringResource(it) },
        onClick = onClick,
    )
}
