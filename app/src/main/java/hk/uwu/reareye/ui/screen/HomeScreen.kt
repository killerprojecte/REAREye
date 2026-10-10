package hk.uwu.reareye.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CrueltyFree
import androidx.compose.material.icons.outlined.DoNotDisturb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Frame_bug
import hk.uwu.reareye.R
import hk.uwu.reareye.generated.AppProperties
import hk.uwu.reareye.hook.support.ModuleActivationState
import hk.uwu.reareye.hook.support.XposedModuleStatus
import hk.uwu.reareye.ui.components.PresetPackLocalStatus
import hk.uwu.reareye.ui.components.PresetPackStatusCard
import hk.uwu.reareye.ui.components.isNewerThan
import hk.uwu.reareye.ui.components.motion.ArtVisibilityMotion
import hk.uwu.reareye.ui.components.rememberPresetPackHomeSnapshot
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.ui.easteregg.EasterEggManager
import hk.uwu.reareye.ui.easteregg.EasterEggType
import hk.uwu.reareye.ui.featureGuideAnchor
import hk.uwu.reareye.ui.theme.AppThemeMode
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import hk.uwu.reareye.utils.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val updateInfoHttpClient = OkHttpClient()

private object UpdateInfoCache {
    val lock = Mutex()
    var latestCommitHash: String? = null
}

private class ToastHolder {
    var toast: Toast? = null
}

private data class FrameNotice(
    val title: String,
    val summary: String,
)

private object FrameNoticeCache {
    var loaded = false
    var value: FrameNotice? = null
    var pending = false
    val callbacks = mutableListOf<(FrameNotice?) -> Unit>()
}

fun preloadHomeFrameNotice(context: Context) {
    resolveFrameNotice(context) {}
}

@Immutable
private data class StatusCardPalette(
    val container: Color,
    val icon: Color,
    val title: Color,
    val summary: Color,
)

private fun customStatusCardPalette(
    container: Color,
    icon: Color,
    title: Color,
    summary: Color,
): StatusCardPalette {
    return StatusCardPalette(
        container = container,
        icon = icon,
        title = title,
        summary = summary,
    )
}

@Composable
private fun rememberStatusCardPalette(
    accent: Color,
    containerStrength: Float = 0.22f,
    iconStrength: Float = 0.86f,
    titleStrength: Float = 0.46f,
    summaryStrength: Float = 0.28f,
): StatusCardPalette {
    val colorScheme = MiuixTheme.colorScheme

    return remember(
        accent,
        colorScheme.surface,
        colorScheme.onSurface,
        colorScheme.onSurfaceVariantSummary,
        containerStrength,
        iconStrength,
        titleStrength,
        summaryStrength,
    ) {
        StatusCardPalette(
            container = lerp(colorScheme.surface, accent, containerStrength),
            icon = lerp(colorScheme.onSurfaceVariantSummary, accent, iconStrength),
            title = lerp(colorScheme.onSurface, accent, titleStrength),
            summary = lerp(colorScheme.onSurfaceVariantSummary, accent, summaryStrength),
        )
    }
}

private fun showSingleToast(context: Context, holder: ToastHolder, message: String) {
    holder.toast?.cancel()
    holder.toast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT)
    holder.toast?.show()
}

private fun resolveFrameNotice(context: Context, onResolved: (FrameNotice?) -> Unit) {
    synchronized(FrameNoticeCache) {
        if (FrameNoticeCache.loaded) {
            onResolved(FrameNoticeCache.value)
            return
        }

        FrameNoticeCache.callbacks += onResolved
        if (FrameNoticeCache.pending) return
        FrameNoticeCache.pending = true
    }

    val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val lines = resultData?.getStringArray(decodeFrameToken(3)).orEmpty()
            val notice = if (resultCode == 2 && lines.size >= 2 &&
                lines[0].isNotBlank() && lines[1].isNotBlank()
            ) {
                FrameNotice(title = lines[0], summary = lines[1])
            } else {
                null
            }
            val callbacks = synchronized(FrameNoticeCache) {
                FrameNoticeCache.value = notice
                FrameNoticeCache.loaded = true
                FrameNoticeCache.pending = false
                FrameNoticeCache.callbacks.toList().also {
                    FrameNoticeCache.callbacks.clear()
                }
            }
            callbacks.forEach { it(notice) }
        }
    }

    context.applicationContext.sendBroadcast(
        Intent(decodeFrameToken(0)).apply {
            setPackage(context.packageName)
            putExtra(decodeFrameToken(1), receiver)
        }
    )
}

private fun decodeFrameToken(index: Int): String {
    val value = when (index) {
        0 -> "aGsudXd1LnJlYXJleWUuYWN0aW9uLlBVTFNF"
        1 -> "aGsudXd1LnJlYXJleWUuZXh0cmEuQ0FMTEJBQ0s="
        2 -> "aGsudXd1LnJlYXJleWUuZXh0cmEuQ09ERQ=="
        3 -> "aGsudXd1LnJlYXJleWUuZXh0cmEuTElORVM="
        else -> ""
    }
    return String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}

private suspend fun fetchLatestCommitHashFromNetwork(): String? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val branchParts = AppProperties.GIT_BRANCH.split("/")
            val owner = branchParts.getOrNull(0) ?: "killerprojecte"
            val repo = branchParts.getOrNull(1) ?: "REAREye"
            val branch = branchParts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "unknown" }
                ?: "master"
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/commits/$branch")
                .build()
            val response = updateInfoHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                JSONObject(response.body.string()).optString("sha", "").take(7).ifBlank { null }
            } else {
                null
            }
        }.getOrNull()
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun HomeScreen(
    bottomInnerPadding: Dp = 0.dp,
    onOpenPresetPackDialog: () -> Unit = {},
    presetPackRefreshToken: Any? = Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activationState by remember { mutableStateOf(XposedModuleStatus.current()) }
    val isActivated = activationState == ModuleActivationState.ACTIVE
    val showTopMenu = remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    val prefsManager = remember { context.getPrefsManager() }
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val coroutineScope = rememberCoroutineScope()
    val easterEggToastHolder = remember { ToastHolder() }
    val presetPackHomeSnapshot = rememberPresetPackHomeSnapshot(presetPackRefreshToken)
    val presetPackSnapshot = presetPackHomeSnapshot.local
    val presetPackUpdateAvailable = presetPackHomeSnapshot.latest?.isNewerThan(
        presetPackSnapshot.installed?.manifest?.packVersion,
    ) == true

    var latestCommitHash by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var hasRootAccess by remember { mutableStateOf<Boolean?>(null) }
    val frameNotice = FrameNoticeCache.value
    var easterEggType by remember {
        mutableStateOf(EasterEggManager.getCurrentEasterEggType(context))
    }
    val themeMode = remember(prefsManager) {
        AppThemeMode.fromValue(
            prefsManager.getInt(
                ConfigKeys.MODULE_THEME_MODE,
                AppThemeMode.default.value,
            )
        )
    }
    val useMonetStatusColors = remember(themeMode) {
        themeMode == AppThemeMode.MONET_SYSTEM ||
                themeMode == AppThemeMode.MONET_LIGHT ||
                themeMode == AppThemeMode.MONET_DARK
    }

    DisposableEffect(lifecycleOwner) {
        val statusObserver: (ModuleActivationState) -> Unit = { activationState = it }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) XposedModuleStatus.refresh()
        }
        XposedModuleStatus.observe(statusObserver)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        XposedModuleStatus.refresh()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            XposedModuleStatus.removeObserver(statusObserver)
        }
    }

    LaunchedEffect(Unit) {
        if (!UpdateInfoCache.latestCommitHash.isNullOrBlank()) {
            latestCommitHash = UpdateInfoCache.latestCommitHash
            return@LaunchedEffect
        }

        isCheckingUpdate = true
        latestCommitHash = UpdateInfoCache.lock.withLock {
            if (UpdateInfoCache.latestCommitHash.isNullOrBlank()) {
                val fetchedHash = fetchLatestCommitHashFromNetwork()
                if (!fetchedHash.isNullOrBlank()) {
                    UpdateInfoCache.latestCommitHash = fetchedHash
                }
            }
            UpdateInfoCache.latestCommitHash
        }
        isCheckingUpdate = false
    }

    LaunchedEffect(Unit) {
        hasRootAccess = withContext(Dispatchers.IO) {
            RootHelper.hasRootAccess()
        }
    }

    val statusTitle = if (isActivated) {
        when (easterEggType) {
            EasterEggType.APRIL_FOOLS -> androidx.compose.ui.res.stringResource(R.string.home_easter_egg_april_fools_working)
            EasterEggType.EASTER -> androidx.compose.ui.res.stringResource(R.string.home_easter_egg_easter_working)
            EasterEggType.MI_FANS -> androidx.compose.ui.res.stringResource(R.string.home_easter_egg_mifans_working)
            EasterEggType.HUAWEI_ULTIMATE_DESIGN -> androidx.compose.ui.res.stringResource(R.string.home_easter_egg_huawei_ultimate_design_working)
            else -> androidx.compose.ui.res.stringResource(R.string.home_status_working)
        }
    } else {
        androidx.compose.ui.res.stringResource(
            when (activationState) {
                ModuleActivationState.SERVICE_UNAVAILABLE -> R.string.home_status_service_unavailable
                ModuleActivationState.SCOPE_NOT_AUTHORIZED -> R.string.home_status_scope_not_authorized
                ModuleActivationState.NO_RUNNING_TARGET -> R.string.home_status_no_running_target
                ModuleActivationState.ACTIVE -> R.string.home_status_working
            }
        )
    }
    val normalizedCurrentHash = AppProperties.GIT_HASH.take(7).lowercase()
    val normalizedLatestHash = latestCommitHash?.take(7)?.lowercase()
    val showUpdateWarning =
        !isCheckingUpdate && !normalizedLatestHash.isNullOrBlank() && normalizedLatestHash != normalizedCurrentHash
    val showFrameNotice = frameNotice != null
    val showRootWarning = hasRootAccess == false
    val rootWarningDelay = if (showFrameNotice) 150 else 100
    val updateInfoDelay = when {
        showFrameNotice && showRootWarning -> 200
        showFrameNotice || showRootWarning -> 150
        else -> 100
    }

    val appTitle = when (easterEggType) {
        EasterEggType.APRIL_FOOLS -> "FOOLEye"
        EasterEggType.EASTER -> "BUNNYEgg"
        EasterEggType.MI_FANS -> "JINFan"
        EasterEggType.HUAWEI_ULTIMATE_DESIGN -> "Rate XT 非凡大师"
        else -> "REAREye"
    }
    val moduleVersion = when (easterEggType) {
        EasterEggType.APRIL_FOOLS -> "4.1.0-41f001u-r${AppProperties.BUILD_NUMBER}-fool"
        EasterEggType.EASTER -> "7.7.7-holyegg-r${AppProperties.BUILD_NUMBER}-rebirth"
        EasterEggType.MI_FANS -> "本彩蛋仅为娱乐用途\n不代表开发者或任何组织的立场或观点"
        EasterEggType.HUAWEI_ULTIMATE_DESIGN -> "38.0-harmony-r${AppProperties.BUILD_NUMBER}-ultimate"
        else -> "${AppProperties.PROJECT_APP_VERSION_NAME}-${AppProperties.GIT_HASH}-r${AppProperties.BUILD_NUMBER}-${AppProperties.BUILD_CHANNEL}"
    }
    val releaseChannel = when (easterEggType) {
        EasterEggType.APRIL_FOOLS -> "Oops"
        EasterEggType.EASTER -> "Respawn Entertainment"
        EasterEggType.MI_FANS -> "HyperOS Beta"
        EasterEggType.HUAWEI_ULTIMATE_DESIGN -> "ULTIMATE DESIGN"
        else -> AppProperties.BUILD_CHANNEL
    }
    val versionCodename = AppProperties.PROJECT_APP_VERSION_CODENAME

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                color = Color.Transparent,
                title = appTitle,
                actions = {
                    Box {
                        IconButton(
                            onClick = { showTopMenu.value = true },
                            holdDownState = showTopMenu.value,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Regular.MoreCircle,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }

                        OverlayListPopup(
                            show = showTopMenu.value,
                            popupModifier = Modifier,
                            popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                            alignment = PopupPositionProvider.Align.End,
                            enableWindowDim = true,
                            onDismissRequest = { showTopMenu.value = false },
                            maxHeight = null,
                            minWidth = 220.dp,
                            renderInRootScaffold = true,
                        ) {
                            @SuppressLint("LocalContextGetResourceValueCall")
                            ListPopupColumn {
                                Spacer(modifier = Modifier.height(4.dp))

                                DropdownImpl(
                                    item = DropdownItem(
                                        title = androidx.compose.ui.res.stringResource(
                                            R.string.quick_stop_subscreencenter,
                                        ),
                                    ),
                                    optionSize = 4,
                                    isSelected = false,
                                    index = 0,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showTopMenu.value = false

                                        coroutineScope.launch {
                                            forceStopPackageByRoot(
                                                context = context,
                                                packageName = "com.xiaomi.subscreencenter",
                                                appName = context.getString(R.string.category_subscreencenter),
                                            )
                                        }
                                    },
                                )

                                DropdownImpl(
                                    item = DropdownItem(
                                        title = androidx.compose.ui.res.stringResource(
                                            R.string.quick_stop_thememanager,
                                        ),
                                    ),
                                    optionSize = 4,
                                    isSelected = false,
                                    index = 1,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showTopMenu.value = false
                                        coroutineScope.launch {
                                            forceStopPackageByRoot(
                                                context = context,
                                                packageName = "com.android.thememanager",
                                                appName = context.getString(R.string.category_thememanager),
                                            )
                                        }
                                    },
                                )

                                DropdownImpl(
                                    item = DropdownItem(
                                        title = androidx.compose.ui.res.stringResource(
                                            R.string.quick_stop_personalassistant,
                                        ),
                                    ),
                                    optionSize = 4,
                                    isSelected = false,
                                    index = 2,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showTopMenu.value = false
                                        coroutineScope.launch {
                                            forceStopPackageByRoot(
                                                context = context,
                                                packageName = "com.miui.personalassistant",
                                                appName = context.getString(R.string.category_personalassistant),
                                            )
                                        }
                                    },
                                )

                                DropdownImpl(
                                    item = DropdownItem(
                                        title = androidx.compose.ui.res.stringResource(
                                            R.string.quick_stop_systemui,
                                        ),
                                    ),
                                    optionSize = 4,
                                    isSelected = false,
                                    index = 3,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showTopMenu.value = false
                                        coroutineScope.launch {
                                            forceKillSystemUI(context)
                                        }
                                    },
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { paddingValues ->
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .rearAcrylicSource(hazeState)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 12.dp,
                bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding + 12.dp,
            ),
            overscrollEffect = null,
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RevealItem(visible = visible, delayMillis = 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            WorkingStatusCard(
                                statusTitle = statusTitle,
                                statusVersion = AppProperties.BUILD_NUMBER.toString(),
                                activated = isActivated,
                                easterEggType = easterEggType,
                                useMonetColors = useMonetStatusColors,
                                onLongPress = {
                                    val result =
                                        EasterEggManager.toggleTodayEasterEggEnabled(context)
                                    if (!result.matchedToday) {
                                        showSingleToast(
                                            context = context,
                                            holder = easterEggToastHolder,
                                            message = context.getString(R.string.home_easter_egg_no_today),
                                        )
                                        return@WorkingStatusCard
                                    }

                                    easterEggType =
                                        EasterEggManager.getCurrentEasterEggType(context)
                                    val eggName = context.getString(result.type.toTitleRes())
                                    val messageRes = when {
                                        result.isEnabled -> R.string.home_easter_egg_enabled
                                        else -> R.string.home_easter_egg_disabled
                                    }
                                    showSingleToast(
                                        context = context,
                                        holder = easterEggToastHolder,
                                        message = context.getString(messageRes, eggName),
                                    )
                                },
                            )

                            frameNotice?.let { notice ->
                                FrameNoticeCard(
                                    title = notice.title,
                                    summary = notice.summary,
                                    useMonetColors = useMonetStatusColors,
                                )
                            }

                            if (showRootWarning) {
                                RevealItem(visible = visible, delayMillis = rootWarningDelay) {
                                    RootWarningCard(useMonetColors = useMonetStatusColors)
                                }
                            }

                            if (showUpdateWarning) {
                                UpdateWarningCard(
                                    currentHash = AppProperties.GIT_HASH.take(7),
                                    latestHash = latestCommitHash?.take(7).orEmpty(),
                                    useMonetColors = useMonetStatusColors,
                                )
                            }

                            if (presetPackSnapshot.status == PresetPackLocalStatus.NOT_INSTALLED ||
                                presetPackSnapshot.status == PresetPackLocalStatus.INVALID ||
                                presetPackUpdateAvailable
                            ) {
                                PresetPackStatusCard(
                                    snapshot = presetPackSnapshot,
                                    updateAvailable = presetPackUpdateAvailable,
                                    latestVersion = presetPackHomeSnapshot.latest?.version,
                                    useMonetColors = useMonetStatusColors,
                                    onClick = onOpenPresetPackDialog,
                                )
                            }
                        }
                    }

                    RevealItem(visible = visible, delayMillis = 50) {
                        ModuleInfoCard(
                            activationState = activationState,
                            moduleVersion = moduleVersion,
                            versionCodename = versionCodename,
                            releaseChannel = releaseChannel,
                            easterEggType = easterEggType
                        )
                    }

                    RevealItem(visible = visible, delayMillis = updateInfoDelay) {
                        UpdateInfoCard(
                            currentHash = when (easterEggType) {
                                EasterEggType.APRIL_FOOLS -> "41f001u"
                                EasterEggType.EASTER -> "holyegg"
                                EasterEggType.MI_FANS -> "leijun"
                                EasterEggType.HUAWEI_ULTIMATE_DESIGN -> "ultimate"
                                else -> AppProperties.GIT_HASH
                            },
                            latestHash = when (easterEggType) {
                                EasterEggType.APRIL_FOOLS if AppProperties.GIT_HASH == latestCommitHash -> "41f001u"
                                EasterEggType.EASTER if AppProperties.GIT_HASH == latestCommitHash -> "candies"
                                EasterEggType.MI_FANS if AppProperties.GIT_HASH == latestCommitHash -> "jinfan"
                                EasterEggType.HUAWEI_ULTIMATE_DESIGN if AppProperties.GIT_HASH == latestCommitHash -> "design"
                                else -> latestCommitHash
                            },
                            checking = isCheckingUpdate,
                        )
                    }
                }
            }
        }

    }
}

private suspend fun forceStopPackageByRoot(
    context: Context,
    packageName: String,
    appName: String,
) {
    withContext(Dispatchers.IO) {
        if (!RootHelper.hasRootAccess()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_need_root_permission),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return@withContext
        }

        val success = RootHelper.executeRootCommandSuccess("am force-stop $packageName")
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(
                    if (success) R.string.quick_stop_success else R.string.quick_stop_failed,
                    appName,
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

private suspend fun forceKillSystemUI(context: Context) {
    withContext(Dispatchers.IO) {
        if (!RootHelper.hasRootAccess()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_need_root_permission),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return@withContext
        }

        RootHelper.executeRootCommandSuccess("kill -9 $(pgrep systemui)")
    }
}

@Composable
private fun RevealItem(
    visible: Boolean,
    delayMillis: Int,
    content: @Composable () -> Unit,
) {
    ArtVisibilityMotion(
        visible = visible,
        delayMillis = delayMillis,
        enterAlphaDurationMillis = 320,
        enterTransformDurationMillis = 420,
        exitAlphaDurationMillis = 120,
        exitTransformDurationMillis = 120,
        hiddenEnterScale = 1f,
        hiddenExitScale = 1f,
        slideDivisor = 8,
    ) {
        content()
    }
}

@Composable
private fun WorkingStatusCard(
    statusTitle: String,
    statusVersion: String,
    activated: Boolean,
    easterEggType: EasterEggType,
    useMonetColors: Boolean,
    onLongPress: () -> Unit,
) {
    val palette = when {
        useMonetColors && !activated -> rememberStatusCardPalette(
            accent = MiuixTheme.colorScheme.error,
            containerStrength = 0.24f,
        )

        !activated -> customStatusCardPalette(
            container = Color(0xFFF8E2E2),
            icon = Color(0xFFE06767),
            title = Color(0xFF7A2A2A),
            summary = Color(0xFF9A4D4D),
        )

        easterEggType == EasterEggType.NEW_YEAR -> customStatusCardPalette(
            container = Color(0xFFFFECEC),
            icon = Color(0xFFE64B4B),
            title = Color(0xFF7F1E1E),
            summary = Color(0xFF9A3939),
        )

        easterEggType == EasterEggType.APRIL_FOOLS -> customStatusCardPalette(
            container = Color(0xFFFFF6D8),
            icon = Color(0xFFEBB027),
            title = Color(0xFF7A5100),
            summary = Color(0xFF8E6900),
        )

        easterEggType == EasterEggType.EASTER -> customStatusCardPalette(
            container = Color(0xFFFFF4E6),
            icon = Color(0xFFED9A9A),
            title = Color(0xFF6A4C93),
            summary = Color(0xFF4CA66B),
        )

        easterEggType == EasterEggType.MI_FANS -> customStatusCardPalette(
            container = Color(0xFFFFF2E0),
            icon = Color(0xFFFF6900),
            title = Color(0xFFB34700),
            summary = Color(0xFF8C6239),
        )

        easterEggType == EasterEggType.HUAWEI_ULTIMATE_DESIGN -> customStatusCardPalette(
            container = Color(0xFF5C2325),
            icon = Color(0xFFE3B86A),
            title = Color(0xFFF8E2B3),
            summary = Color(0xFFD7B08F),
        )

        useMonetColors -> rememberStatusCardPalette(
            accent = MiuixTheme.colorScheme.primary,
            containerStrength = 0.22f,
        )

        else -> customStatusCardPalette(
            container = Color(0xFFDFFAE4),
            icon = Color(0xFF36D167),
            title = Color(0xFF1E5A31),
            summary = Color(0xFF2C7D45),
        )
    }

    val statusIcon = when {
        !activated -> Icons.Outlined.Warning

        easterEggType == EasterEggType.APRIL_FOOLS -> Icons.Outlined.BugReport
        easterEggType == EasterEggType.EASTER -> Icons.Outlined.CrueltyFree
        easterEggType == EasterEggType.MI_FANS -> Icons.Outlined.Lock

        else -> Icons.Outlined.CheckCircle
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .featureGuideAnchor("home_status"),
        colors = CardDefaults.defaultColors(color = palette.container),
        insideMargin = PaddingValues(14.dp),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false,
        onLongPress = {
            onLongPress()
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .size(198.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 72.dp, y = 56.dp),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = statusTitle,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.title,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.home_working_version,
                        statusVersion
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = palette.summary,
                )
            }
        }
    }
}

private fun EasterEggType.toTitleRes(): Int {
    return when (this) {
        EasterEggType.NONE -> R.string.home_easter_egg_none
        EasterEggType.NEW_YEAR -> R.string.home_easter_egg_new_year
        EasterEggType.APRIL_FOOLS -> R.string.home_easter_egg_april_fools
        EasterEggType.EASTER -> R.string.home_easter_egg_easter
        EasterEggType.MI_FANS -> R.string.home_easter_egg_mifans
        EasterEggType.HUAWEI_ULTIMATE_DESIGN -> R.string.home_easter_egg_huawei_ultimate_design
    }
}

@Composable
private fun RootWarningCard(useMonetColors: Boolean) {
    val palette = if (useMonetColors) rememberStatusCardPalette(
        accent = MiuixTheme.colorScheme.error,
        containerStrength = 0.2f,
        iconStrength = 0.82f,
        titleStrength = 0.42f,
        summaryStrength = 0.26f,
    ) else customStatusCardPalette(
        container = Color(0xFFFDE9E9),
        icon = Color(0xFFD94B4B),
        title = Color(0xFF8C1F1F),
        summary = Color(0xFFA63737),
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        colors = CardDefaults.defaultColors(color = palette.container),
        insideMargin = PaddingValues(14.dp),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Outlined.DoNotDisturb,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .size(108.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 50.dp, y = 42.dp),
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.home_root_warning_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.title,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.home_root_warning_desc),
                    style = MiuixTheme.textStyles.body2,
                    color = palette.summary,
                )
            }
        }
    }
}

@Composable
private fun FrameNoticeCard(title: String, summary: String, useMonetColors: Boolean) {
    val colorScheme = MiuixTheme.colorScheme
    val palette = if (useMonetColors) rememberStatusCardPalette(
        accent = lerp(colorScheme.primary, colorScheme.secondary, 0.35f),
        containerStrength = 0.2f,
        iconStrength = 0.8f,
        titleStrength = 0.4f,
        summaryStrength = 0.24f,
    ) else customStatusCardPalette(
        container = Color(0xFFFFF3CD),
        icon = Color(0xFFE0A100),
        title = Color(0xFF7A5A00),
        summary = Color(0xFF8A6B00),
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        colors = CardDefaults.defaultColors(color = palette.container),
        insideMargin = PaddingValues(14.dp),
        onClick = {},
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Frame_bug,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .size(108.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 50.dp, y = 44.dp),
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.title,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = palette.summary,
                )
            }
        }
    }
}

@Composable
private fun UpdateWarningCard(currentHash: String, latestHash: String, useMonetColors: Boolean) {
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val palette = if (useMonetColors) rememberStatusCardPalette(
        accent = lerp(colorScheme.primary, colorScheme.secondary, 0.35f),
        containerStrength = 0.2f,
        iconStrength = 0.8f,
        titleStrength = 0.4f,
        summaryStrength = 0.24f,
    ) else customStatusCardPalette(
        container = Color(0xFFFFF3CD),
        icon = Color(0xFFE0A100),
        title = Color(0xFF7A5A00),
        summary = Color(0xFF8A6B00),
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        colors = CardDefaults.defaultColors(color = palette.container),
        insideMargin = PaddingValues(14.dp),
        onClick = {
            @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    (if (AppProperties.BUILD_CHANNEL == "canary") "https://updates.uwu.hk" else "https://github.com/killerprojecte/REAREye/releases")
                        .toUri()
                )
            )
        },
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Outlined.Sync,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .size(108.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 50.dp, y = 44.dp),
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.home_update_warning_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.title,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.home_update_warning_desc,
                        currentHash,
                        latestHash,
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = palette.summary,
                )
            }
        }
    }
}

@Composable
private fun ModuleInfoCard(
    activationState: ModuleActivationState,
    moduleVersion: String,
    versionCodename: String,
    releaseChannel: String,
    easterEggType: EasterEggType
) {
    val activated = activationState == ModuleActivationState.ACTIVE
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        InfoLine(
            title = androidx.compose.ui.res.stringResource(R.string.status_card),
            value = when {
                activationState == ModuleActivationState.SERVICE_UNAVAILABLE ->
                    androidx.compose.ui.res.stringResource(R.string.home_status_service_unavailable)

                activationState == ModuleActivationState.SCOPE_NOT_AUTHORIZED ->
                    androidx.compose.ui.res.stringResource(R.string.home_status_scope_not_authorized)

                activationState == ModuleActivationState.NO_RUNNING_TARGET ->
                    androidx.compose.ui.res.stringResource(R.string.home_status_no_running_target)

                easterEggType == EasterEggType.APRIL_FOOLS -> androidx.compose.ui.res.stringResource(
                    R.string.home_easter_egg_april_fools_activated
                )

                easterEggType == EasterEggType.EASTER -> androidx.compose.ui.res.stringResource(
                    R.string.home_easter_egg_easter_activated
                )

                easterEggType == EasterEggType.MI_FANS -> androidx.compose.ui.res.stringResource(
                    R.string.home_easter_egg_mifans_activated
                )

                easterEggType == EasterEggType.HUAWEI_ULTIMATE_DESIGN -> androidx.compose.ui.res.stringResource(
                    R.string.home_easter_egg_huawei_ultimate_design_activated
                )

                else -> androidx.compose.ui.res.stringResource(R.string.module_is_activated)
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoLine(
            title = androidx.compose.ui.res.stringResource(R.string.module_version_label),
            value = moduleVersion,
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoLine(
            title = androidx.compose.ui.res.stringResource(R.string.version_codename_label),
            value = versionCodename,
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoLine(
            title = androidx.compose.ui.res.stringResource(R.string.home_status_channel),
            value = releaseChannel,
        )
    }
}

@Composable
private fun UpdateInfoCard(currentHash: String, latestHash: String?, checking: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.home_update_title),
            style = MiuixTheme.textStyles.title3,
        )
        Spacer(modifier = Modifier.height(8.dp))
        InfoLine(
            title = androidx.compose.ui.res.stringResource(R.string.home_update_current),
            value = currentHash,
        )
        Spacer(modifier = Modifier.height(8.dp))
        InfoLine(
            title = androidx.compose.ui.res.stringResource(R.string.home_update_latest),
            value = when {
                checking -> androidx.compose.ui.res.stringResource(R.string.home_update_checking)
                latestHash.isNullOrBlank() -> androidx.compose.ui.res.stringResource(R.string.home_update_unknown)
                else -> latestHash
            },
        )
    }
}

@Composable
fun InfoLine(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = title, style = MiuixTheme.textStyles.headline1)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
