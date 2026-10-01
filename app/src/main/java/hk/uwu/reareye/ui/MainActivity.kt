package hk.uwu.reareye.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import hk.uwu.reareye.ui.components.PresetPackDialog
import hk.uwu.reareye.ui.components.motion.ArtVisibilityMotion
import hk.uwu.reareye.ui.components.navigation.NavigationQuickTarget
import hk.uwu.reareye.ui.components.navigation.RearNavigationBar
import hk.uwu.reareye.ui.components.navigation.encodeNavigationQuickActionIds
import hk.uwu.reareye.ui.components.navigation.parseNavigationQuickActionIds
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import hk.uwu.reareye.ui.config.ModuleSettingsController
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.screen.AboutScreen
import hk.uwu.reareye.ui.screen.ConfigScreen
import hk.uwu.reareye.ui.screen.HomeScreen
import hk.uwu.reareye.ui.screen.RearStoreScreen
import hk.uwu.reareye.ui.screen.preloadHomeFrameNotice
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val MainScreenOrder = listOf("home", "store", "config", "about")

private data class RemoteUiSettings(
    val themeModeValue: Int,
    val navigationBarModeValue: Int,
    val navigationQuickActionIds: List<String>,
    val launcherHidden: Boolean,
)

class MainActivity : ComponentActivity() {
    private companion object {
        const val OOBE_PREFS = "reareye_oobe"
        const val OOBE_COMPLETED = "completed"
        const val FEATURE_GUIDE_COMPLETED = "feature_guide_completed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val permissionInfo = applicationContext.packageManager
                .getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
            if (permissionInfo != null && permissionInfo.packageName == "com.lbe.security.miui") {
                if (ContextCompat.checkSelfPermission(
                        applicationContext,
                        "com.android.permission.GET_INSTALLED_APPS"
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf("com.android.permission.GET_INSTALLED_APPS"),
                        999,
                    )
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        preloadHomeFrameNotice(applicationContext)

        setContent {
            val onboardingPrefs = remember {
                applicationContext.getSharedPreferences(OOBE_PREFS, MODE_PRIVATE)
            }
            /*onboardingPrefs.edit {
                putBoolean(OOBE_COMPLETED, false)
                putBoolean(FEATURE_GUIDE_COMPLETED, false)
            }*/
            var onboardingCompleted by remember {
                mutableStateOf(onboardingPrefs.getBoolean(OOBE_COMPLETED, false))
            }
            var startFeatureGuideAfterOobe by remember { mutableStateOf(false) }

            if (!onboardingCompleted) {
                AppTheme {
                    OnboardingScreen(
                        onFinished = { startFeatureGuide ->
                            onboardingPrefs.edit {
                                putBoolean(OOBE_COMPLETED, true)
                                putBoolean(FEATURE_GUIDE_COMPLETED, !startFeatureGuide)
                            }
                            startFeatureGuideAfterOobe = startFeatureGuide
                            onboardingCompleted = true
                        },
                    )
                }
                return@setContent
            }

            val remotePrefsManager = remember { applicationContext.getPrefsManager() }
            val remoteRevision = rememberRemotePrefsStatusRevision()
            var remoteUiSettings by remember { mutableStateOf<RemoteUiSettings?>(null) }

            LaunchedEffect(remoteRevision) {
                val loadedRemoteUiSettings = withContext(Dispatchers.IO) {
                    if (!remotePrefsManager.isRemoteReady()) {
                        null
                    } else {
                        val rawQuickActionIds = remotePrefsManager.getString(
                            ConfigKeys.MODULE_NAVIGATION_QUICK_ACTIONS
                        )
                        val normalizedQuickActionIds = parseNavigationQuickActionIds(
                            rawQuickActionIds
                        ).toList()
                        // Drop quick actions removed by a newer configuration model while
                        // preserving the user's remaining order during upgrade.
                        if (rawQuickActionIds.isNotBlank()) {
                            val normalizedValue = encodeNavigationQuickActionIds(
                                normalizedQuickActionIds
                            )
                            if (rawQuickActionIds != normalizedValue) {
                                remotePrefsManager.putString(
                                    ConfigKeys.MODULE_NAVIGATION_QUICK_ACTIONS,
                                    normalizedValue,
                                )
                            }
                        }
                        RemoteUiSettings(
                            themeModeValue = remotePrefsManager.getInt(
                                ConfigKeys.MODULE_THEME_MODE,
                                AppThemeMode.default.value,
                            ),
                            navigationBarModeValue = remotePrefsManager.getInt(
                                ConfigKeys.MODULE_NAVIGATION_BAR_MODE,
                                ModuleNavigationBarMode.default.value,
                            ),
                            navigationQuickActionIds = normalizedQuickActionIds,
                            launcherHidden = remotePrefsManager.getBoolean(
                                ConfigKeys.MODULE_HIDE_LAUNCHER_ENTRY,
                                false,
                            ),
                        )
                    }
                }

                remoteUiSettings = loadedRemoteUiSettings
                loadedRemoteUiSettings?.let { settings ->
                    ModuleSettingsController.syncLauncherEntryVisibility(
                        context = applicationContext,
                        hidden = settings.launcherHidden,
                    )
                }
            }

            val settings = remoteUiSettings
            if (settings == null) {
                Box(modifier = Modifier.fillMaxSize())
                return@setContent
            }

            var themeModeValue by remember {
                mutableIntStateOf(settings.themeModeValue)
            }
            var navigationBarModeValue by remember {
                mutableIntStateOf(settings.navigationBarModeValue)
            }
            var currentScreen by remember { mutableStateOf("home") }
            var showPresetPackDialog by remember { mutableStateOf(false) }
            var presetPackRefreshToken by remember { mutableIntStateOf(0) }
            var navBarVisible by remember { mutableStateOf(false) }
            var configInAppListMode by remember { mutableStateOf(false) }
            var pendingConfigQuickManagerTarget by remember {
                mutableStateOf<ConfigType.ManagerType?>(null)
            }
            var pendingQuickActionTransition by remember { mutableStateOf(false) }
            var pendingRearStoreWidgetId by remember { mutableStateOf<String?>(null) }
            var navigationQuickActionIds by remember {
                mutableStateOf(settings.navigationQuickActionIds)
            }
            var featureGuideVisible by remember {
                mutableStateOf(
                    startFeatureGuideAfterOobe ||
                            !onboardingPrefs.getBoolean(FEATURE_GUIDE_COMPLETED, false),
                )
            }
            var featureGuideStep by remember { mutableIntStateOf(0) }
            var guideAnchors by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }

            fun updateGuideAnchor(key: String, rect: Rect) {
                guideAnchors = guideAnchors + (key to rect)
            }

            LaunchedEffect(featureGuideVisible, featureGuideStep) {
                if (!featureGuideVisible) return@LaunchedEffect
                guideAnchors = emptyMap()
                currentScreen = if (featureGuideStep <= 1) "home" else "config"
                pendingConfigQuickManagerTarget = when (featureGuideStep) {
                    3 -> ConfigType.ManagerType.CARD
                    4 -> ConfigType.ManagerType.REAR_WALLPAPER
                    5 -> ConfigType.ManagerType.SCENE_ROUTE
                    else -> null
                }
                configInAppListMode = featureGuideStep == 5
            }

            LaunchedEffect(Unit) {
                navBarVisible = true
            }

            AppTheme(themeMode = AppThemeMode.fromValue(themeModeValue)) {
                val navigationBarMode = ModuleNavigationBarMode.fromValue(navigationBarModeValue)
                val enableFloatingGlass =
                    navigationBarMode == ModuleNavigationBarMode.FLOATING_GLASS
                val showNavigation =
                    navBarVisible && !(currentScreen == "config" && configInAppListMode)
                val density = LocalDensity.current
                val surfaceColor = MiuixTheme.colorScheme.surface
                val backdrop = rememberLayerBackdrop {
                    drawRect(surfaceColor)
                    drawContent()
                }
                var stableBottomInset by remember { mutableStateOf(0.dp) }

                Scaffold { _ ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { clip = true }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (enableFloatingGlass) {
                                        Modifier.layerBackdrop(backdrop)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            AnimatedContent(
                                targetState = currentScreen,
                                contentKey = { it },
                                transitionSpec = {
                                    if (pendingQuickActionTransition && targetState == "config") {
                                        pendingQuickActionTransition = false
                                        return@AnimatedContent ContentTransform(
                                            targetContentEnter = EnterTransition.None,
                                            initialContentExit = ExitTransition.None,
                                        )
                                    }

                                    val initialIndex =
                                        MainScreenOrder.indexOf(initialState).coerceAtLeast(0)
                                    val targetIndex =
                                        MainScreenOrder.indexOf(targetState).coerceAtLeast(0)
                                    val forward = targetIndex >= initialIndex

                                    fadeIn(
                                        animationSpec = tween(
                                            durationMillis = 210,
                                            delayMillis = 50,
                                            easing = LinearOutSlowInEasing,
                                        )
                                    ) + slideInHorizontally(
                                        animationSpec = tween(
                                            durationMillis = 280,
                                            easing = FastOutSlowInEasing,
                                        )
                                    ) { fullWidth ->
                                        if (forward) fullWidth / 9 else -fullWidth / 9
                                    } togetherWith (
                                            fadeOut(
                                                animationSpec = tween(
                                                    durationMillis = 110,
                                                    easing = FastOutLinearInEasing,
                                                )
                                            ) + slideOutHorizontally(
                                                animationSpec = tween(
                                                    durationMillis = 190,
                                                    easing = FastOutLinearInEasing,
                                                )
                                            ) { fullWidth ->
                                                if (forward) -fullWidth / 12 else fullWidth / 12
                                            }
                                            )
                                },
                                label = "ScreenTransition"
                            ) { screen ->
                                when (screen) {
                                    "home" -> Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onGloballyPositioned { coordinates ->
                                                val origin = coordinates.positionInRoot()
                                                val horizontalInset = with(density) { 16.dp.toPx() }
                                                val topInset = with(density) { 86.dp.toPx() }
                                                val height = with(density) { 168.dp.toPx() }
                                                updateGuideAnchor(
                                                    "home_status",
                                                    Rect(
                                                        origin.x + horizontalInset,
                                                        origin.y + topInset,
                                                        origin.x + coordinates.size.width - horizontalInset,
                                                        origin.y + topInset + height,
                                                    ),
                                                )
                                            },
                                    ) {
                                        HomeScreen(
                                            bottomInnerPadding = stableBottomInset,
                                            onOpenPresetPackDialog = {
                                                showPresetPackDialog = true
                                            },
                                            presetPackRefreshToken = presetPackRefreshToken,
                                        )
                                    }

                                    "store" -> RearStoreScreen(
                                        bottomInnerPadding = stableBottomInset,
                                        initialWidgetId = pendingRearStoreWidgetId,
                                        onInitialWidgetHandled = {
                                            pendingRearStoreWidgetId = null
                                        },
                                    )

                                    "config" -> Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onGloballyPositioned { coordinates ->
                                                val origin = coordinates.positionInRoot()
                                                val horizontalInset = with(density) { 14.dp.toPx() }
                                                val topInset = with(density) { 118.dp.toPx() }
                                                val height = with(density) { 188.dp.toPx() }
                                                updateGuideAnchor(
                                                    "config_dashboard",
                                                    Rect(
                                                        origin.x + horizontalInset,
                                                        origin.y + topInset,
                                                        origin.x + coordinates.size.width - horizontalInset,
                                                        origin.y + topInset + height,
                                                    ),
                                                )
                                            },
                                    ) {
                                        ConfigScreen(
                                            bottomInnerPadding = stableBottomInset,
                                            quickManagerTarget = pendingConfigQuickManagerTarget,
                                            onQuickManagerTargetHandled = {
                                                pendingConfigQuickManagerTarget = null
                                            },
                                            onAppListModeChange = {
                                                configInAppListMode = it
                                            },
                                            onThemeModeChange = { themeModeValue = it },
                                            onNavigationBarModeChange = {
                                                navigationBarModeValue = it
                                            },
                                            onOpenRearStoreDetail = { widgetId ->
                                                pendingRearStoreWidgetId = widgetId
                                                configInAppListMode = false
                                                currentScreen = "store"
                                            },
                                        )
                                    }

                                    "about" -> AboutScreen(
                                        bottomInnerPadding = stableBottomInset,
                                        onOpenPresetPackDialog = { showPresetPackDialog = true },
                                    )
                                }
                            }
                        }

                        val navShadowProgress by animateFloatAsState(
                            targetValue = if (showNavigation) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = if (showNavigation) 380 else 240,
                                easing = if (showNavigation) {
                                    FastOutSlowInEasing
                                } else {
                                    FastOutLinearInEasing
                                },
                            ),
                            label = "NavigationShadowProgress",
                        )
                        if (showNavigation || navShadowProgress > 0.001f) {
                            ArtVisibilityMotion(
                                visible = showNavigation,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .onGloballyPositioned { coordinates ->
                                        val origin = coordinates.positionInRoot()
                                        updateGuideAnchor(
                                            "bottom_navigation",
                                            Rect(
                                                origin.x + with(density) { 8.dp.toPx() },
                                                origin.y + with(density) { 4.dp.toPx() },
                                                origin.x + coordinates.size.width - with(density) { 8.dp.toPx() },
                                                origin.y + coordinates.size.height - with(density) { 4.dp.toPx() },
                                            ),
                                        )
                                        val totalHeight = with(density) {
                                            coordinates.size.height.toDp()
                                        }
                                        if (totalHeight != stableBottomInset) {
                                            stableBottomInset = totalHeight
                                        }
                                    },
                                enterAlphaDurationMillis = 260,
                                enterTransformDurationMillis = 380,
                                exitAlphaDurationMillis = 180,
                                exitTransformDurationMillis = 240,
                                hiddenEnterScale = 1f,
                                hiddenExitScale = 1f,
                                slideDivisor = 3,
                                hiddenOffsetFallback = 28.dp,
                            ) {
                                RearNavigationBar(
                                    currentScreen = currentScreen,
                                    navigationBarMode = navigationBarMode,
                                    backdrop = backdrop,
                                    shadowVisibilityProgress = navShadowProgress,
                                    quickActionIds = navigationQuickActionIds,
                                    onQuickActionIdsChanged = { nextIds ->
                                        val normalizedIds = parseNavigationQuickActionIds(
                                            encodeNavigationQuickActionIds(nextIds)
                                        )
                                        navigationQuickActionIds = normalizedIds
                                        remotePrefsManager.putString(
                                            ConfigKeys.MODULE_NAVIGATION_QUICK_ACTIONS,
                                            encodeNavigationQuickActionIds(normalizedIds),
                                        )
                                    },
                                    onScreenSelected = { currentScreen = it },
                                    onQuickActionSelected = { target ->
                                        when (target) {
                                            is NavigationQuickTarget.ConfigManager -> {
                                                pendingConfigQuickManagerTarget = target.managerType
                                                pendingQuickActionTransition = true
                                                // Dashboard managers stay inside the new configuration
                                                // workbench and keep the navigation bar visible. Only
                                                // dedicated More pages use the full-screen overlay mode.
                                                configInAppListMode = target.managerType in setOf(
                                                    ConfigType.ManagerType.SCENE_ROUTE,
                                                    ConfigType.ManagerType.BOUNDS,
                                                )
                                                currentScreen = "config"
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        PresetPackDialog(
                            show = showPresetPackDialog,
                            onDismissRequest = { showPresetPackDialog = false },
                            onApplied = { presetPackRefreshToken++ },
                        )

                        val currentGuideKey =
                            featureGuideSteps.getOrNull(featureGuideStep)?.anchorKey
                        if (featureGuideVisible && currentGuideKey != null && guideAnchors.containsKey(
                                currentGuideKey
                            )
                        ) {
                            FeatureGuideOverlay(
                                stepIndex = featureGuideStep,
                                anchors = guideAnchors,
                                onNext = {
                                    if (featureGuideStep == featureGuideSteps.lastIndex) {
                                        onboardingPrefs.edit()
                                            .putBoolean(FEATURE_GUIDE_COMPLETED, true).apply()
                                        featureGuideVisible = false
                                    } else {
                                        featureGuideStep += 1
                                    }
                                },
                                onSkip = {
                                    onboardingPrefs.edit().putBoolean(FEATURE_GUIDE_COMPLETED, true)
                                        .apply()
                                    featureGuideVisible = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
