package hk.uwu.reareye.ui.components.config

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Deployed_code_update
import com.composables.icons.materialsymbols.rounded.File_export
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.bounds.CustomBoundsCompatAppConfig
import hk.uwu.reareye.repository.bounds.CustomBoundsCompatConfigCodec
import hk.uwu.reareye.repository.bounds.CustomBoundsFillMode
import hk.uwu.reareye.repository.bounds.CustomBoundsMode
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.RearBadgeItem
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.card.SuperCard
import hk.uwu.reareye.ui.components.motion.ArtRevealItem
import hk.uwu.reareye.ui.components.rememberRearAccentBadgePalette
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.util.Locale

private data class CustomBoundsOption(
    val value: Int,
    @param:StringRes val titleRes: Int,
)

private val gravityOptions = listOf(
    CustomBoundsOption(17, R.string.custom_bounds_position_center),
    CustomBoundsOption(48, R.string.custom_bounds_position_top),
    CustomBoundsOption(80, R.string.custom_bounds_position_bottom),
    CustomBoundsOption(3, R.string.custom_bounds_position_left),
    CustomBoundsOption(5, R.string.custom_bounds_position_right),
)

private val rotationOptions = listOf(
    CustomBoundsOption(
        CustomBoundsCompatConfigCodec.ROTATION_FOLLOW_SYSTEM,
        R.string.custom_bounds_rotation_follow,
    ),
    CustomBoundsOption(0, R.string.custom_bounds_rotation_0),
    CustomBoundsOption(90, R.string.custom_bounds_rotation_90),
    CustomBoundsOption(180, R.string.custom_bounds_rotation_180),
    CustomBoundsOption(270, R.string.custom_bounds_rotation_270),
)

private val modeOptions = listOf(
    CustomBoundsOption(0, R.string.custom_bounds_mode_auto_ratio),
    CustomBoundsOption(1, R.string.custom_bounds_mode_custom_ratio),
    CustomBoundsOption(2, R.string.custom_bounds_mode_exact_insets),
)

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun CustomBoundsCompatManagerScreen(
    prefsManager: PrefsManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val scope = rememberCoroutineScope()
    val configs = remember { mutableStateListOf<CustomBoundsCompatAppConfig>() }
    var loaded by remember { mutableStateOf(false) }
    var dataCardsVisible by remember { mutableStateOf(false) }
    var showAppSelector by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var editingPackageName by remember { mutableStateOf<String?>(null) }
    var draftEnabled by remember { mutableStateOf(true) }
    var draftMode by remember { mutableIntStateOf(0) }
    var draftAspectRatio by remember { mutableStateOf("") }
    var draftGravity by remember { mutableIntStateOf(CustomBoundsCompatConfigCodec.DEFAULT_GRAVITY) }
    var draftScale by remember { mutableStateOf("") }
    var draftDensityDpi by remember { mutableStateOf("") }
    var draftRotationDegrees by remember {
        mutableIntStateOf(CustomBoundsCompatConfigCodec.ROTATION_FOLLOW_SYSTEM)
    }
    var draftInsetLeft by remember { mutableStateOf("") }
    var draftInsetTop by remember { mutableStateOf("") }
    var draftInsetRight by remember { mutableStateOf("") }
    var draftInsetBottom by remember { mutableStateOf("") }
    var draftFillEnabled by remember { mutableStateOf(false) }
    var draftFillMode by remember { mutableIntStateOf(0) }
    var draftFillColor by remember { mutableStateOf("#FF000000") }
    var draftFillColorError by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<CustomBoundsCompatAppConfig?>(null) }
    var pendingOverwriteConfig by remember { mutableStateOf<CustomBoundsCompatAppConfig?>(null) }
    var rejectedImportPackage by remember { mutableStateOf<String?>(null) }
    var importErrorMessage by remember { mutableStateOf<String?>(null) }

    val appSelectorItem = remember {
        ConfigItem(
            key = ConfigKeys.CUSTOM_BOUNDS_COMPAT_APPS,
            titleRes = R.string.custom_bounds_pick_apps,
            descriptionRes = R.string.custom_bounds_pick_apps_desc,
            type = ConfigType.AppList(defaultValues = emptySet()),
        )
    }

    fun replaceConfigs(nextConfigs: List<CustomBoundsCompatAppConfig>) {
        configs.clear()
        configs.addAll(nextConfigs.sortedBy { it.packageName.lowercase() })
    }

    fun loadConfigsFromPrefs(): List<CustomBoundsCompatAppConfig> {
        val selectedPackages = prefsManager.getStringSet(
            ConfigKeys.CUSTOM_BOUNDS_COMPAT_APPS,
            emptySet(),
        )
        val storedConfigs = CustomBoundsCompatConfigCodec.parse(
            prefsManager.getString(
                ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA,
                CustomBoundsCompatConfigCodec.EMPTY_ARRAY,
            )
        )
        return CustomBoundsCompatConfigCodec.normalizeForPackages(storedConfigs, selectedPackages)
    }

    fun persist(nextConfigs: List<CustomBoundsCompatAppConfig> = configs.toList()) {
        val packages = nextConfigs.mapTo(linkedSetOf()) { it.packageName }
        val encoded = CustomBoundsCompatConfigCodec.encode(nextConfigs)
        prefsManager.putStringSet(ConfigKeys.CUSTOM_BOUNDS_COMPAT_APPS, packages)
        prefsManager.putString(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA, encoded)
    }

    fun toast(resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    fun applyImportedConfig(config: CustomBoundsCompatAppConfig) {
        val nextConfigs = configs.toMutableList()
        val index = nextConfigs.indexOfFirst { it.packageName == config.packageName }
        if (index >= 0) {
            nextConfigs[index] = config
        } else {
            nextConfigs += config
        }
        replaceConfigs(nextConfigs)
        persist()
        toast(R.string.custom_bounds_rule_import_success)
    }

    fun packageInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    fun handleImportedConfig(config: CustomBoundsCompatAppConfig) {
        if (!packageInstalled(config.packageName)) {
            rejectedImportPackage = config.packageName
            return
        }
        if (configs.any { it.packageName == config.packageName }) {
            pendingOverwriteConfig = config
        } else {
            applyImportedConfig(config)
        }
    }

    fun syncAfterAppSelection() {
        val nextConfigs = loadConfigsFromPrefs()
        replaceConfigs(nextConfigs)
        persist(nextConfigs)
    }

    fun openEditDialog(item: CustomBoundsCompatAppConfig) {
        editingPackageName = item.packageName
        draftEnabled = item.enabled
        draftMode = when (item.mode) {
            CustomBoundsMode.AUTO_RATIO -> 0
            CustomBoundsMode.CUSTOM_RATIO -> 1
            CustomBoundsMode.EXACT_INSETS -> 2
        }
        draftAspectRatio = if (item.aspectRatio > 0f) formatFloat(item.aspectRatio) else ""
        draftGravity = normalizeGravity(item.gravity)
        draftScale = formatFloat(item.scale)
        draftDensityDpi = item.densityDpi.takeIf { it > 0 }?.toString().orEmpty()
        draftRotationDegrees = CustomBoundsCompatConfigCodec.normalizeRotation(item.rotationDegrees)
        draftInsetLeft = item.insetLeft.takeIf { it > 0 }?.toString().orEmpty()
        draftInsetTop = item.insetTop.takeIf { it > 0 }?.toString().orEmpty()
        draftInsetRight = item.insetRight.takeIf { it > 0 }?.toString().orEmpty()
        draftInsetBottom = item.insetBottom.takeIf { it > 0 }?.toString().orEmpty()
        draftFillEnabled = item.fillEnabled
        draftFillMode = when (item.fillMode) {
            CustomBoundsFillMode.AUTO -> 0
            CustomBoundsFillMode.CUSTOM -> 1
        }
        draftFillColor = formatColorInt(item.fillColorArgb.takeIf { it != 0 } ?: 0xFF000000.toInt())
        draftFillColorError = false
        showDialog = true
    }

    fun submitDialog() {
        val packageName = editingPackageName ?: return
        val mode = when (draftMode) {
            1 -> CustomBoundsMode.CUSTOM_RATIO
            2 -> CustomBoundsMode.EXACT_INSETS
            else -> CustomBoundsMode.AUTO_RATIO
        }
        val aspectRatio = draftAspectRatio.trim().toFloatOrNull()
        val scale = draftScale.trim().toFloatOrNull()
        val densityDpi = draftDensityDpi.trim().ifBlank { "0" }.toIntOrNull()
        val gravity = draftGravity
        val rotation = draftRotationDegrees
        val insetLeft = draftInsetLeft.trim().ifBlank { "0" }.toIntOrNull()
        val insetTop = draftInsetTop.trim().ifBlank { "0" }.toIntOrNull()
        val insetRight = draftInsetRight.trim().ifBlank { "0" }.toIntOrNull()
        val insetBottom = draftInsetBottom.trim().ifBlank { "0" }.toIntOrNull()
        val fillMode = when (draftFillMode) {
            1 -> CustomBoundsFillMode.CUSTOM
            else -> CustomBoundsFillMode.AUTO
        }
        val parsedFillColor = parseColorInt(draftFillColor)
        draftFillColorError =
            draftFillEnabled && fillMode == CustomBoundsFillMode.CUSTOM && parsedFillColor == null

        if (scale == null || scale <= 0f || densityDpi == null || densityDpi < 0 ||
            gravityOptions.none { it.value == gravity } ||
            CustomBoundsCompatConfigCodec.normalizeRotation(rotation) != rotation ||
            insetLeft == null || /*insetLeft < 0 ||*/ insetTop == null || /*insetTop < 0 ||*/
            insetRight == null || /*insetRight < 0 ||*/ insetBottom == null ||/* insetBottom < 0 ||*/
            (mode == CustomBoundsMode.CUSTOM_RATIO && (aspectRatio == null || aspectRatio <= 0f)) ||
            (draftFillEnabled && fillMode == CustomBoundsFillMode.CUSTOM && parsedFillColor == null)
        ) {
            Toast.makeText(
                context,
                context.getString(R.string.custom_bounds_form_invalid),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val nextConfig = CustomBoundsCompatAppConfig(
            packageName = packageName,
            enabled = draftEnabled,
            mode = mode,
            aspectRatio = aspectRatio ?: 0f,
            gravity = gravity,
            scale = scale,
            densityDpi = densityDpi,
            rotationDegrees = rotation,
            insetLeft = insetLeft,
            insetTop = insetTop,
            insetRight = insetRight,
            insetBottom = insetBottom,
            fillEnabled = draftFillEnabled,
            fillMode = fillMode,
            fillColorArgb = if (draftFillEnabled && fillMode == CustomBoundsFillMode.CUSTOM) {
                parsedFillColor ?: 0
            } else {
                0
            },
        )
        val index = configs.indexOfFirst { it.packageName == packageName }
        if (index >= 0) {
            configs[index] = nextConfig
        }
        replaceConfigs(configs.toList())
        persist()
        showDialog = false
        Toast.makeText(
            context,
            context.getString(R.string.custom_bounds_saved),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun exportFileName(packageName: String): String {
        val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${safePackage}_${System.currentTimeMillis()}.rbt"
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = CustomBoundsCompatConfigCodec.encodeRuleFile(target)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(raw.toByteArray(Charsets.UTF_8))
                    } ?: error("openOutputStream failed")
                }.isSuccess
            }
            if (success) {
                toast(R.string.custom_bounds_rule_export_success)
            } else {
                toast(R.string.custom_bounds_rule_export_failed)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val importedConfig = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("openInputStream failed")
                    CustomBoundsCompatConfigCodec.decodeRuleFile(raw)
                }.getOrNull()
            }
            if (importedConfig == null) {
                importErrorMessage = context.getString(R.string.custom_bounds_rule_import_invalid)
            } else {
                handleImportedConfig(importedConfig)
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(220)
        replaceConfigs(withContext(Dispatchers.IO) { loadConfigsFromPrefs() })
        loaded = true
        delay(90)
        dataCardsVisible = true
    }

    if (showAppSelector) {
        BackHandler { showAppSelector = false }
        AppListSelectorScreen(
            configItem = appSelectorItem,
            prefsManager = prefsManager,
            onCancel = { showAppSelector = false },
            onSave = {
                syncAfterAppSelection()
                showAppSelector = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                color = Color.Transparent,
                title = stringResource(R.string.custom_bounds_compat_manager),
                navigationIconPadding = 12.dp,
                actionIconPadding = 12.dp,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            modifier = Modifier.graphicsLayer {
                                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                            },
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (loaded) importLauncher.launch(
                            arrayOf(
                                "application/octet-stream",
                                "text/plain",
                                "*/*"
                            )
                        )
                    }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Deployed_code_update,
                            contentDescription = null
                        )
                    }
                    IconButton(onClick = { if (loaded) showAppSelector = true }) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
                .overScrollVertical()
                .rearAcrylicSource(hazeState)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 12.dp,
                bottom = paddingValues.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            overscrollEffect = null,
        ) {
            item {
                Card(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .fillMaxWidth(),
                ) {
                    SuperCard(
                        title = stringResource(R.string.custom_bounds_hint_title),
                        summary = stringResource(R.string.custom_bounds_hint),
                        onClick = {},
                        bottomAction = {
                            Button(
                                onClick = { showAppSelector = true },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                Text(text = stringResource(R.string.custom_bounds_pick_apps))
                            }
                        },
                    )
                }
            }

            if (!dataCardsVisible) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(vertical = 24.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                InfiniteProgressIndicator()
                                Text(text = stringResource(R.string.rear_widget_loading_data))
                            }
                        }
                    }
                }
            }

            if (dataCardsVisible) {
                itemsIndexed(
                    items = configs,
                    key = { _, item -> item.packageName },
                    contentType = { _, _ -> "custom_bounds_item" },
                ) { _, item ->
                    ModuleStyleManagerCard(
                        title = item.packageName,
                        summaryLines = emptyList(),
                        badges = customBoundsBadges(item),
                        onCardClick = { openEditDialog(item) },
                        leftAction = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ModuleStyleIconAction(
                                    icon = Icons.Rounded.EditNote,
                                    onClick = { openEditDialog(item) },
                                )
                                ModuleStyleIconAction(
                                    icon = MaterialSymbols.Rounded.File_export,
                                    onClick = {
                                        exportTarget = item
                                        exportLauncher.launch(exportFileName(item.packageName))
                                    },
                                )
                            }
                        },
                        rightAction = {
                            ModuleStyleDeleteAction(
                                icon = MiuixIcons.Delete,
                                text = stringResource(R.string.rear_widget_action_delete),
                                onClick = {
                                    configs.remove(item)
                                    persist()
                                },
                            )
                        },
                    )
                }
            }

            item {
                if (dataCardsVisible && configs.isEmpty()) {
                    ArtRevealItem(visible = true, delayMillis = 40) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.custom_bounds_empty),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    OverlayDialog(
        show = showDialog,
        title = stringResource(R.string.custom_bounds_edit_app),
        onDismissRequest = { showDialog = false },
    ) {
        DialogFormColumn {
            TextField(
                value = editingPackageName.orEmpty(),
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.rear_widget_target_package),
                enabled = false,
                readOnly = true,
                singleLine = true,
            )
            SwitchPreference(
                title = stringResource(R.string.custom_bounds_enable_app),
                checked = draftEnabled,
                onCheckedChange = { draftEnabled = it },
            )
            CustomBoundsDropdownPreference(
                title = stringResource(R.string.custom_bounds_mode),
                description = stringResource(R.string.custom_bounds_mode_help),
                options = modeOptions,
                selectedValue = draftMode,
                onSelected = { draftMode = it },
            )
            if (draftMode == 1) {
                TextField(
                    value = draftAspectRatio,
                    onValueChange = { draftAspectRatio = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.custom_bounds_aspect_ratio),
                    singleLine = true,
                )
            }
            if (draftMode == 2) {
                TextField(
                    value = draftInsetLeft,
                    onValueChange = { draftInsetLeft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.custom_bounds_inset_left),
                    singleLine = true,
                )
                TextField(
                    value = draftInsetTop,
                    onValueChange = { draftInsetTop = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.custom_bounds_inset_top),
                    singleLine = true,
                )
                TextField(
                    value = draftInsetRight,
                    onValueChange = { draftInsetRight = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.custom_bounds_inset_right),
                    singleLine = true,
                )
                TextField(
                    value = draftInsetBottom,
                    onValueChange = { draftInsetBottom = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.custom_bounds_inset_bottom),
                    singleLine = true,
                )
            }
            if (draftMode == 0) {
                CustomBoundsInfoText(
                    text = stringResource(R.string.custom_bounds_auto_ratio_hint),
                )
            }
            CustomBoundsDropdownPreference(
                title = stringResource(R.string.custom_bounds_gravity),
                description = stringResource(R.string.custom_bounds_gravity_help),
                options = gravityOptions,
                selectedValue = draftGravity,
                onSelected = { draftGravity = it },
            )
            TextField(
                value = draftScale,
                onValueChange = { draftScale = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.custom_bounds_scale),
                singleLine = true,
            )
            TextField(
                value = draftDensityDpi,
                onValueChange = { draftDensityDpi = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.custom_bounds_density_dpi),
                singleLine = true,
            )
            CustomBoundsDropdownPreference(
                title = stringResource(R.string.custom_bounds_rotation_degrees),
                description = stringResource(R.string.custom_bounds_rotation_help),
                options = rotationOptions,
                selectedValue = draftRotationDegrees,
                onSelected = { draftRotationDegrees = it },
            )
            SwitchPreference(
                title = stringResource(R.string.custom_bounds_fill_enable),
                checked = draftFillEnabled,
                onCheckedChange = { draftFillEnabled = it },
            )
            if (draftFillEnabled) {
                CustomBoundsDropdownPreference(
                    title = stringResource(R.string.custom_bounds_fill_mode),
                    description = stringResource(R.string.custom_bounds_fill_mode_help),
                    options = listOf(
                        CustomBoundsOption(0, R.string.custom_bounds_fill_mode_auto),
                        CustomBoundsOption(1, R.string.custom_bounds_fill_mode_custom),
                    ),
                    selectedValue = draftFillMode,
                    onSelected = { draftFillMode = it },
                )
                if (draftFillMode == 0) {
                    CustomBoundsInfoText(
                        text = stringResource(R.string.custom_bounds_fill_auto_hint),
                    )
                } else {
                    CustomBoundsColorEditor(
                        value = draftFillColor,
                        onValueChange = {
                            draftFillColor = it
                            draftFillColorError = parseColorInt(it) == null
                        },
                        error = draftFillColorError,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { submitDialog() },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rear_widget_confirm))
                }
                Button(
                    onClick = { showDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rear_widget_cancel))
                }
            }
        }
    }

    OverlayDialog(
        show = rejectedImportPackage != null,
        title = stringResource(R.string.custom_bounds_rule_import_rejected_title),
        onDismissRequest = { rejectedImportPackage = null },
    ) {
        DialogFormColumn {
            Text(
                text = stringResource(
                    R.string.custom_bounds_rule_import_app_missing,
                    rejectedImportPackage.orEmpty(),
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Button(
                onClick = { rejectedImportPackage = null },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rear_widget_confirm))
            }
        }
    }

    OverlayDialog(
        show = pendingOverwriteConfig != null,
        title = stringResource(R.string.custom_bounds_rule_import_overwrite_title),
        onDismissRequest = { pendingOverwriteConfig = null },
    ) {
        DialogFormColumn {
            Text(
                text = stringResource(
                    R.string.custom_bounds_rule_import_overwrite_message,
                    pendingOverwriteConfig?.packageName.orEmpty(),
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Button(
                onClick = {
                    pendingOverwriteConfig?.let(::applyImportedConfig)
                    pendingOverwriteConfig = null
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.custom_bounds_rule_import_overwrite_confirm))
            }
            Button(
                onClick = { pendingOverwriteConfig = null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rear_widget_cancel))
            }
        }
    }

    OverlayDialog(
        show = importErrorMessage != null,
        title = stringResource(R.string.custom_bounds_rule_import_failed_title),
        onDismissRequest = { importErrorMessage = null },
    ) {
        DialogFormColumn {
            Text(
                text = importErrorMessage.orEmpty(),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Button(
                onClick = { importErrorMessage = null },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rear_widget_confirm))
            }
        }
    }
}

private fun formatFloat(value: Float): String =
    String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

private fun normalizeGravity(gravity: Int): Int {
    return gravityOptions.firstOrNull { it.value == gravity }?.value
        ?: CustomBoundsCompatConfigCodec.DEFAULT_GRAVITY
}

@Composable
private fun CustomBoundsDropdownPreference(
    title: String,
    description: String,
    options: List<CustomBoundsOption>,
    selectedValue: Int,
    onSelected: (Int) -> Unit,
) {
    val optionTitles = options.map { stringResource(it.titleRes) }
    val optionEntries = remember(optionTitles) {
        optionTitles.map { DropdownItem(title = it) }
    }
    val selectedIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
    WindowSpinnerPreference(
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
        items = optionEntries,
        selectedIndex = selectedIndex,
        title = title,
        summary = description,
        onSelectedIndexChange = { index -> onSelected(options[index].value) },
    )
}

@Composable
private fun CustomBoundsInfoText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f))
            .border(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.18f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun CustomBoundsColorEditor(
    value: String,
    onValueChange: (String) -> Unit,
    error: Boolean,
) {
    val parsedColor = remember(value) { parseColorInt(value) }
    val composeColor = parsedColor?.let(::Color) ?: Color.White

    fun commitColor(color: Color) {
        onValueChange(formatColorInt(color.toArgb()))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.custom_bounds_fill_palette),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        ColorPalette(
            color = composeColor,
            onColorChanged = { commitColor(it) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.custom_bounds_fill_color_help),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .border(
                        width = 1.dp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        shape = RoundedCornerShape(999.dp),
                    )
                    .background(parsedColor?.let(::Color) ?: Color.Transparent),
            )
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.custom_bounds_fill_color),
                singleLine = true,
            )
        }
        if (error) {
            Text(
                text = stringResource(R.string.custom_bounds_fill_color_invalid),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun parseColorInt(raw: String): Int? {
    val normalized = raw.trim()
    if (normalized.isBlank()) return null
    return runCatching { normalized.toColorInt() }.getOrNull()
}

private fun formatColorInt(color: Int): String =
    String.format(Locale.US, "#%08X", color)

@Composable
private fun customBoundsBadges(item: CustomBoundsCompatAppConfig): List<RearBadgeItem> {
    val statePalette = rememberRearAccentBadgePalette(
        if (item.enabled) Color(0xFF10B981) else Color(0xFF64748B)
    )
    val ratioPalette = rememberRearAccentBadgePalette(Color(0xFF6366F1))
    val positionPalette = rememberRearAccentBadgePalette(Color(0xFF8B5CF6))
    val densityPalette = rememberRearAccentBadgePalette(Color(0xFF0EA5E9))
    val rotationPalette = rememberRearAccentBadgePalette(Color(0xFFF59E0B))
    val fillPalette = rememberRearAccentBadgePalette(Color(0xFFEF4444))
    return listOf(
        RearBadgeItem(
            text = if (item.enabled) {
                stringResource(R.string.custom_bounds_enabled)
            } else {
                stringResource(R.string.custom_bounds_disabled)
            },
            emphasized = item.enabled,
            palette = statePalette,
        ),
        RearBadgeItem(
            text = stringResource(
                R.string.custom_bounds_badge_mode,
                modeTitle(item.mode),
            ),
            palette = ratioPalette,
        ),
        when (item.mode) {
            CustomBoundsMode.CUSTOM_RATIO -> RearBadgeItem(
                text = stringResource(
                    R.string.custom_bounds_badge_ratio,
                    formatFloat(item.aspectRatio),
                ),
                palette = ratioPalette,
            )

            CustomBoundsMode.EXACT_INSETS -> RearBadgeItem(
                text = stringResource(
                    R.string.custom_bounds_badge_insets,
                    "${item.insetLeft},${item.insetTop},${item.insetRight},${item.insetBottom}",
                ),
                palette = ratioPalette,
            )

            else -> RearBadgeItem(
                text = stringResource(R.string.custom_bounds_auto_ratio_hint),
                palette = ratioPalette,
            )
        },
        RearBadgeItem(
            text = stringResource(
                R.string.custom_bounds_badge_scale,
                formatFloat(item.scale),
            ),
            palette = ratioPalette,
        ),
        RearBadgeItem(
            text = stringResource(
                R.string.custom_bounds_badge_position,
                gravityTitle(item.gravity),
            ),
            palette = positionPalette,
        ),
        RearBadgeItem(
            text = stringResource(
                R.string.custom_bounds_badge_density,
                item.densityDpi.takeIf { it > 0 }?.toString()
                    ?: stringResource(R.string.custom_bounds_follow_system),
            ),
            palette = densityPalette,
        ),
        RearBadgeItem(
            text = stringResource(
                R.string.custom_bounds_badge_rotation,
                formatRotation(item.rotationDegrees),
            ),
            palette = rotationPalette,
        ),
        RearBadgeItem(
            text = if (!item.fillEnabled) {
                stringResource(R.string.custom_bounds_fill_disabled)
            } else if (item.fillMode == CustomBoundsFillMode.AUTO) {
                stringResource(R.string.custom_bounds_fill_mode_auto)
            } else {
                stringResource(
                    R.string.custom_bounds_fill_custom_badge,
                    formatColorInt(item.fillColorArgb),
                )
            },
            emphasized = item.fillEnabled,
            palette = fillPalette,
        ),
    )
}

@Composable
private fun modeTitle(mode: CustomBoundsMode): String {
    return when (mode) {
        CustomBoundsMode.AUTO_RATIO -> stringResource(R.string.custom_bounds_mode_auto_ratio)
        CustomBoundsMode.CUSTOM_RATIO -> stringResource(R.string.custom_bounds_mode_custom_ratio)
        CustomBoundsMode.EXACT_INSETS -> stringResource(R.string.custom_bounds_mode_exact_insets)
    }
}

@Composable
private fun gravityTitle(gravity: Int): String {
    val option = gravityOptions.firstOrNull { it.value == gravity }
    return if (option != null) {
        stringResource(option.titleRes)
    } else {
        stringResource(R.string.custom_bounds_position_custom, gravity)
    }
}

@Composable
private fun formatRotation(rotationDegrees: Int): String {
    return if (rotationDegrees == CustomBoundsCompatConfigCodec.ROTATION_FOLLOW_SYSTEM) {
        stringResource(R.string.custom_bounds_follow_system)
    } else {
        stringResource(R.string.custom_bounds_rotation_value, rotationDegrees)
    }
}
