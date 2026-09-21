package hk.uwu.reareye.hook.scopes.system.modules

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.content.Intent
import hk.uwu.reareye.internal.appembed.AppEmbedInitialLayout
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.BuildConfig
import hk.uwu.reareye.hook.core.YLog
import hk.uwu.reareye.hook.core.YukiBaseHooker
import hk.uwu.reareye.repository.bounds.CustomBoundsCompatAppConfig
import hk.uwu.reareye.repository.bounds.CustomBoundsCompatConfigCodec
import hk.uwu.reareye.repository.bounds.CustomBoundsFillMode
import hk.uwu.reareye.repository.bounds.CustomBoundsMode
import hk.uwu.reareye.ui.config.ConfigKeys
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CustomBoundsCompatModule : YukiBaseHooker() {
    override fun onHook() {
        loadSystem {
            val activityRecordImplClass = runCatching {
                "com.android.server.wm.ActivityRecordImpl".toClass()
            }.getOrElse {
                YLog.warn("ActivityRecordImpl class not found, skip custom rear bounds hook")
                return@loadSystem
            }
            val activityRecordImplRef = runCatching {
                activityRecordImplClass.resolve()
            }.getOrElse {
                YLog.warn("ActivityRecordImpl not found, skip custom rear bounds hook")
                return@loadSystem
            }

            val themeColorUtilsClass = "com.android.server.wm.ThemeColorUtils".toClass()

            activityRecordImplRef.firstMethod {
                name = "resolveOverrideConfiguration"
                parameterCount = 2
            }.hook().after {
                val moreDebug = prefs.getBoolean(ConfigKeys.MORE_DEBUG, false)
                val parentConfig = args(0).cast<Configuration>() ?: return@after
                val resolvedConfig = args(1).cast<Configuration>() ?: return@after
                val activityRecord = instance.field<Any>("mAr") ?: run {
                    if (moreDebug) YLog.debug("[$TAG] skip reason=no_activity_record")
                    return@after
                }
                val packageName = activityRecord.field<String>("packageName") ?: run {
                    if (moreDebug) YLog.debug("[$TAG] skip reason=no_package")
                    return@after
                }
                val launchIntent = activityRecord.field<Intent>("intent")
                if (launchIntent?.getBooleanExtra(
                        AppEmbedInitialLayout.EXTRA_CUSTOM_BOUNDS_OWNERSHIP,
                        false,
                    ) == true
                ) {
                    if (moreDebug) YLog.debug("[$TAG] skip package=$packageName reason=appembed_owns_configuration")
                    return@after
                }
                val config = CustomBoundsCompatHookConfig.find(
                    raw = prefs.getString(
                        ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA,
                        CustomBoundsCompatConfigCodec.EMPTY_ARRAY,
                    ),
                    packageName = packageName,
                ) ?: run {
                    if (moreDebug) YLog.debug("[$TAG] skip package=$packageName reason=no_config")
                    return@after
                }
                if (!config.enabled) {
                    if (moreDebug) YLog.debug("[$TAG] skip package=$packageName reason=disabled")
                    return@after
                }

                val displayId = activityRecord.call<Int>("getDisplayId") ?: -1
                if (displayId != TARGET_DISPLAY_ID) {
                    if (moreDebug) YLog.debug("[$TAG] skip package=$packageName reason=display_id displayId=$displayId")
                    return@after
                }
                if (activityRecord.call<Boolean>("inMultiWindowMode") == true) {
                    if (moreDebug) YLog.debug("[$TAG] skip package=$packageName reason=multi_window")
                    return@after
                }

                val parentBounds = getWindowConfigurationBounds(parentConfig)
                if (parentBounds == null || parentBounds.isEmpty) {
                    if (moreDebug) YLog.debug("[$TAG] skip package=$packageName reason=empty_parent_bounds bounds=$parentBounds")
                    return@after
                }

                val compatBounds = when (config.mode) {
                    CustomBoundsMode.EXACT_INSETS -> computeInsetsBounds(parentBounds, config)
                    CustomBoundsMode.CUSTOM_RATIO -> computeCompatBounds(
                        parentBounds = parentBounds,
                        aspectRatio = config.aspectRatio,
                        gravity = config.gravity,
                        scale = config.scale,
                    )

                    CustomBoundsMode.AUTO_RATIO -> computeCompatBounds(
                        parentBounds = parentBounds,
                        aspectRatio = CustomBoundsCompatConfigCodec.defaultAutoRatio(
                            parentBounds.width(),
                            parentBounds.height(),
                        ),
                        gravity = config.gravity,
                        scale = config.scale,
                    )
                }
                applyResolvedConfiguration(
                    config = resolvedConfig,
                    bounds = compatBounds,
                    densityDpi = config.densityDpi.takeIf { it > 0 } ?: parentConfig.densityDpi,
                    rotation = config.rotationDegrees,
                )
                @Suppress("SimplifyBooleanWithConstants", "KotlinConstantConditions")
                prepareFlipSplashColor(
                    activityRecordImpl = instance,
                    activityRecord = activityRecord,
                    bounds = compatBounds,
                    moreDebug = moreDebug,
                    collectDetails = moreDebug && SHOULD_LOG_DETAILS,
                    themeColorUtilsClass = themeColorUtilsClass
                )
                applyTaskFillColor(
                    activityRecordImpl = instance,
                    activityRecord = activityRecord,
                    config = config,
                    moreDebug = moreDebug,
                )
                if (moreDebug) {
                    YLog.debug(
                        "[$TAG] apply package=$packageName displayId=$displayId parent=$parentBounds bounds=$compatBounds mode=${config.mode} ratio=${config.aspectRatio} insets=${config.insetLeft},${config.insetTop},${config.insetRight},${config.insetBottom} gravity=${config.gravity} scale=${config.scale} dpi=${config.densityDpi} rotation=${config.rotationDegrees} fill=${config.fillEnabled}/${config.fillMode}"
                    )
                }
            }
        }
    }

    private fun prepareFlipSplashColor(
        activityRecordImpl: Any?,
        activityRecord: Any?,
        bounds: Rect,
        moreDebug: Boolean,
        collectDetails: Boolean,
        themeColorUtilsClass: Class<*>?
    ) {
        val activityInfo = activityRecord.field<ActivityInfo>("info") ?: return
        val theme = activityRecord.call<Int>("getTheme")
            ?: activityInfo.themeResource
        val splashColor = computeSplashColorDirect(
            activityRecordImpl = activityRecordImpl,
            activityRecord = activityRecord,
            activityInfo = activityInfo,
            theme = theme,
            collectDetails = collectDetails,
            themeColorUtilsClass = themeColorUtilsClass
        )
        if (moreDebug) {
            val detailsSuffix = if (collectDetails && splashColor.details != null) {
                " details=${splashColor.details}"
            } else {
                ""
            }
            YLog.debug(
                "[$TAG] prepare flip splash color source=${splashColor.source} package=${activityInfo.packageName} " +
                        "theme=$theme color=${splashColor.color?.toArgbHex() ?: "null"} bounds=$bounds$detailsSuffix"
            )
        }
    }

    private fun applyTaskFillColor(
        activityRecordImpl: Any?,
        activityRecord: Any?,
        config: CustomBoundsCompatAppConfig,
        moreDebug: Boolean,
    ) {
        val task = activityRecord.call<Any>("getTask")
            ?: activityRecord.call<Any>("getParent")
            ?: return
        val surfaceControl = task.field<Any>("mSurfaceControl")
            ?: task.call<Any>("getSurfaceControl")
            ?: return
        val transaction = task.call<Any>("getSyncTransaction")
            ?: activityRecord.call<Any>("getSyncTransaction")
            ?: return

        if (!config.fillEnabled) {
            unsetTaskSurfaceColor(transaction, surfaceControl)
            requestTraversal(activityRecord)
            if (moreDebug) {
                YLog.debug("[$TAG] unset fill package=${config.packageName}")
            }
            return
        }

        val fillColor = resolveFillColor(
            activityRecordImpl = activityRecordImpl,
            config = config,
        ) ?: run {
            if (moreDebug) {
                YLog.debug(
                    "[$TAG] skip fill package=${config.packageName} reason=no_flip_color " +
                            describeFlipColorState(activityRecordImpl, activityRecord)
                )
            }
            return
        }
        setTaskSurfaceColor(transaction, surfaceControl, fillColor)
        requestTraversal(activityRecord)
        if (moreDebug) {
            YLog.debug(
                "[$TAG] set fill package=${config.packageName} mode=${config.fillMode} color=${fillColor.toArgbHex()}"
            )
        }
    }

    private fun resolveFillColor(
        activityRecordImpl: Any?,
        config: CustomBoundsCompatAppConfig,
    ): Int? {
        return when (config.fillMode) {
            CustomBoundsFillMode.CUSTOM -> config.fillColorArgb.takeIf(::isUsableFillColor)
            CustomBoundsFillMode.AUTO -> resolveBySystemFlipLogic(activityRecordImpl)
        }
    }

    private fun resolveBySystemFlipLogic(activityRecordImpl: Any?): Int? = runCatching {
        activityRecordImpl ?: return@runCatching null
        activityRecordImpl.field<Int>("mSplashBgColor")?.takeIf(::isUsableFillColor)
    }.getOrNull()

    private fun computeSplashColorDirect(
        activityRecordImpl: Any?,
        activityRecord: Any?,
        activityInfo: ActivityInfo,
        theme: Int,
        collectDetails: Boolean,
        themeColorUtilsClass: Class<*>?
    ): SplashColorResult {
        val details = if (collectDetails) mutableListOf<String>() else null
        fun detailsText(): String? = details?.joinToString(";")
        val systemContext = activityRecord.systemContext()
            ?: activityRecordImpl.systemContext()
            ?: return SplashColorResult(null, "none", detailsText())
        val packageContext = createPackageContextForActivity(systemContext, activityInfo)
            ?: return SplashColorResult(null, "none", detailsText())
        val resolvedTheme = when {
            theme != 0 -> theme
            activityInfo.themeResource != 0 -> activityInfo.themeResource
            else -> android.R.style.Theme_DeviceDefault_DayNight
        }
        packageContext.setTheme(resolvedTheme)
        details?.add("resolvedTheme=$resolvedTheme")

        val miuiCandidate = resolveThemeColorByMiuiUtils(
            context = packageContext,
            packageName = activityInfo.packageName,
            activityRecordImpl = activityRecordImpl,
            themeColorUtilsClass = themeColorUtilsClass,
        )
        if (miuiCandidate != null) {
            details?.add("${miuiCandidate.source}=${miuiCandidate.color.toArgbHex()}")
            if (isUsableFillColor(miuiCandidate.color)) {
                return SplashColorResult(miuiCandidate.color, miuiCandidate.source, detailsText())
            }
        }

        return SplashColorResult(null, "none", detailsText())
    }

    private data class SplashColorResult(
        val color: Int?,
        val source: String,
        val details: String?,
    )

    private data class ColorCandidate(
        val color: Int,
        val source: String,
    )

    private fun resolveThemeColorByMiuiUtils(
        context: Context,
        packageName: String,
        activityRecordImpl: Any?,
        themeColorUtilsClass: Class<*>?,
    ): ColorCandidate? = runCatching {
        themeColorUtilsClass ?: return@runCatching null
        val attrsClass = $$"com.android.server.wm.ThemeColorUtils$SplashScreenWindowAttrs".toClass(
            requireNotNull(themeColorUtilsClass.classLoader) {
                "ThemeColorUtils class loader is unavailable"
            }
        )
        val attrs = attrsClass.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        themeColorUtilsClass.getDeclaredMethod("getWindowAttrs", Context::class.java, attrsClass)
            .apply { isAccessible = true }
            .invoke(null, context, attrs)
        val color = themeColorUtilsClass.getDeclaredMethod(
            "peekWindowBGColor",
            Context::class.java,
            attrsClass
        )
            .apply { isAccessible = true }
            .invoke(null, context, attrs) as? Int
            ?: return@runCatching null
        if (isUsableFillColor(color)) {
            activityRecordImpl.setField("mSplashBgColor", color)
            setThemeColorCache(themeColorUtilsClass, packageName, color)
        }
        ColorCandidate(color, "miuiThemeColorUtils")
    }.getOrNull()

    private fun createPackageContextForActivity(
        systemContext: Context,
        activityInfo: ActivityInfo,
    ): Context? {
        return runCatching {
            val userHandleClass = "android.os.UserHandle".toClass()
            val userId =
                userHandleClass.getDeclaredMethod("getUserId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(null, activityInfo.applicationInfo.uid) as Int
            val userHandle = userHandleClass.getDeclaredMethod("of", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(null, userId)
            systemContext.javaClass.getMethod(
                "createPackageContextAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                userHandleClass,
            ).invoke(
                systemContext,
                activityInfo.packageName,
                Context.CONTEXT_RESTRICTED,
                userHandle
            ) as? Context
        }.getOrElse {
            runCatching {
                systemContext.createPackageContext(
                    activityInfo.packageName,
                    Context.CONTEXT_RESTRICTED
                )
            }.getOrNull()
        }
    }

    private fun setThemeColorCache(
        themeColorUtilsClass: Class<*>,
        packageName: String,
        color: Int,
    ) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val cache = themeColorUtilsClass.getDeclaredField("mAppColorCache")
                .apply { isAccessible = true }
                .get(null) as? MutableMap<String, Int>
            cache?.put(packageName, color)
        }
    }

    private fun describeFlipColorState(activityRecordImpl: Any?, activityRecord: Any?): String {
        val splashBgColor = activityRecordImpl.field<Int>("mSplashBgColor")
        val flipCutoutColor = activityRecordImpl.field<Int>("mFlipCutoutColor")
        val taskDescription = activityRecord.field<Any>("taskDescription")
        val navigationBarColor = taskDescription.call<Int>("getNavigationBarColor")
        val systemContext = activityRecord.systemContext()
        val nightMode = systemContext?.resources?.configuration?.isNightModeActive
        return "splash=${splashBgColor?.toArgbHex() ?: "null"} " +
                "flip=${flipCutoutColor?.toArgbHex() ?: "null"} " +
                "taskDescription=${taskDescription != null} " +
                "nav=${navigationBarColor?.toArgbHex() ?: "null"} " +
                "night=$nightMode"
    }

    private fun setTaskSurfaceColor(transaction: Any?, surfaceControl: Any?, colorInt: Int) {
        val color = Color.valueOf(colorInt)
        transaction.call<Unit>(
            "setColor",
            surfaceControl,
            floatArrayOf(color.red(), color.green(), color.blue()),
        )
    }

    private fun unsetTaskSurfaceColor(transaction: Any?, surfaceControl: Any?) {
        transaction.call<Unit>("unsetColor", surfaceControl)
    }

    private fun requestTraversal(activityRecord: Any?) {
        activityRecord
            .field<Any>("mWmService")
            ?.field<Any>("mWindowPlacerLocked")
            ?.call<Unit>("requestTraversal")
    }

    private fun isUsableFillColor(color: Int): Boolean =
        color != 0 && (color ushr 24) != 0

    private fun Int.toArgbHex(): String =
        "#" + Integer.toHexString(this).padStart(8, '0').uppercase()

    private fun computeInsetsBounds(parentBounds: Rect, config: CustomBoundsCompatAppConfig): Rect {
        val left = parentBounds.left + config.insetLeft
        val top = parentBounds.top + config.insetTop
        val right = (parentBounds.right - config.insetRight).coerceAtLeast(left + 1)
        val bottom = (parentBounds.bottom - config.insetBottom).coerceAtLeast(top + 1)
        return Rect(left, top, right, bottom)
    }

    private fun computeCompatBounds(
        parentBounds: Rect,
        aspectRatio: Float,
        gravity: Int,
        scale: Float,
    ): Rect {
        val displaySize = Point(parentBounds.width(), parentBounds.height())
        val width = displaySize.x
        val height = displaySize.y
        val containerRatio = if (height > 0) width.toFloat() / height else 1f
        val targetRatio = aspectRatio.takeIf { it > 0f } ?: 1f

        val unscaledWidth: Int
        val unscaledHeight: Int
        if (targetRatio >= containerRatio) {
            unscaledWidth = width
            unscaledHeight = (width / targetRatio).roundToInt().coerceAtLeast(1)
        } else {
            unscaledHeight = height
            unscaledWidth = (height * targetRatio).roundToInt().coerceAtLeast(1)
        }

        val scaledWidth = (unscaledWidth * scale).roundToInt().coerceIn(1, width)
        val scaledHeight = (unscaledHeight * scale).roundToInt().coerceIn(1, height)
        val left = when (gravity and HORIZONTAL_GRAVITY_MASK) {
            LEFT -> 0
            RIGHT -> width - scaledWidth
            else -> ((width - scaledWidth) / 2f).roundToInt()
        }
        val top = when (gravity and VERTICAL_GRAVITY_MASK) {
            TOP -> 0
            BOTTOM -> height - scaledHeight
            else -> ((height - scaledHeight) / 2f).roundToInt()
        }
        return Rect(
            parentBounds.left + left,
            parentBounds.top + top,
            parentBounds.left + left + scaledWidth,
            parentBounds.top + top + scaledHeight,
        )
    }

    private fun applyResolvedConfiguration(
        config: Configuration,
        bounds: Rect,
        densityDpi: Int,
        rotation: Int,
    ) {
        config.densityDpi = densityDpi
        val windowConfiguration = config.asResolver()
            .firstField { name = "windowConfiguration" }
            .get<Any>() ?: return
        windowConfiguration.call<Unit>("setBounds", bounds)
        windowConfiguration.call<Unit>("setAppBounds", bounds)
        windowConfiguration.call<Unit>("setMaxBounds", bounds)
        if (rotation != CustomBoundsCompatConfigCodec.ROTATION_FOLLOW_SYSTEM) {
            val surfaceRotation = rotation.toSurfaceRotation()
            windowConfiguration.call<Unit>("setRotation", surfaceRotation)
            windowConfiguration.call<Unit>("setDisplayRotation", surfaceRotation)
        }
        updateScreenDp(config, bounds)
    }

    private fun updateScreenDp(config: Configuration, bounds: Rect) {
        if (bounds.isEmpty || config.densityDpi <= 0) return

        val density = config.densityDpi / 160f
        val widthDp = (bounds.width() / density).roundToInt()
        val heightDp = (bounds.height() / density).roundToInt()
        config.setHiddenIntField("compatScreenWidthDp", widthDp)
        config.screenWidthDp = widthDp
        config.setHiddenIntField("compatScreenHeightDp", heightDp)
        config.screenHeightDp = heightDp
        config.orientation = if (bounds.width() <= bounds.height()) {
            Configuration.ORIENTATION_PORTRAIT
        } else {
            Configuration.ORIENTATION_LANDSCAPE
        }
        val shortSizeDp = min(widthDp, heightDp)
        val longSizeDp = max(widthDp, heightDp)
        config.setHiddenIntField("compatSmallestScreenWidthDp", shortSizeDp)
        config.smallestScreenWidthDp = shortSizeDp
        reduceScreenLayout(config.screenLayout, longSizeDp, shortSizeDp)?.let {
            config.screenLayout = it
        }
    }

    private fun Configuration.setHiddenIntField(name: String, value: Int) {
        runCatching {
            asResolver().firstField { this.name = name }.set(value)
        }
    }

    private fun reduceScreenLayout(screenLayout: Int, longSizeDp: Int, shortSizeDp: Int): Int? {
        return runCatching {
            val resolver = Configuration::class.java.resolve()
            val reset = resolver.firstMethod {
                name = "resetScreenLayout"
                parameterCount = 1
            }.invoke<Int>(screenLayout)
            resolver.firstMethod {
                name = "reduceScreenLayout"
                parameterCount = 3
            }.invoke<Int>(reset, longSizeDp, shortSizeDp)
        }.getOrNull()
    }

    private fun getWindowConfigurationBounds(config: Configuration): Rect? = runCatching {
        config.asResolver()
            .firstField { name = "windowConfiguration" }
            .get<Any>()
            ?.call<Rect>("getBounds")
    }.getOrNull()

    private fun Int.toSurfaceRotation(): Int = when (this) {
        90 -> 1
        180 -> 2
        270 -> 3
        else -> 0
    }

    private fun <T> Any?.field(name: String): T? = runCatching {
        this?.asResolver()?.firstField { this.name = name }?.get<T>()
    }.getOrNull()

    private fun Any?.setField(name: String, value: Any?) {
        runCatching {
            this?.asResolver()?.firstField { this.name = name }?.set(value)
        }
    }

    private fun Any?.systemContext(): Context? =
        field<Any>("mAtmService")?.field("mContext")
            ?: field<Any>("mWmService")?.field("mContext")

    private fun <T> Any?.call(name: String, vararg args: Any?): T? = runCatching {
        this?.asResolver()?.firstMethod {
            this.name = name
            parameterCount = args.size
        }?.invoke<T>(*args)
    }.getOrNull()

    private object CustomBoundsCompatHookConfig {
        @Volatile
        private var lastRaw: String? = null

        @Volatile
        private var lastConfigs: Map<String, CustomBoundsCompatAppConfig> = emptyMap()

        fun find(raw: String, packageName: String?): CustomBoundsCompatAppConfig? {
            if (packageName.isNullOrBlank()) return null
            if (raw != lastRaw) {
                lastConfigs = CustomBoundsCompatConfigCodec.parse(raw)
                    .associateBy { it.packageName }
                lastRaw = raw
            }
            return lastConfigs[packageName]
        }
    }

    private companion object {
        const val TAG = "CustomRearBounds"
        const val TARGET_DISPLAY_ID = 1
        const val HORIZONTAL_GRAVITY_MASK = 7
        const val VERTICAL_GRAVITY_MASK = 112
        const val LEFT = 3
        const val RIGHT = 5
        const val TOP = 48
        const val BOTTOM = 80

        @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
        const val SHOULD_LOG_DETAILS = BuildConfig.BUILD_CHANNEL == "dev"
    }
}
