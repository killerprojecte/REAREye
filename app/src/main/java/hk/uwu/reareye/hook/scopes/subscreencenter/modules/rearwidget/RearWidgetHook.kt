@file:Suppress("UNCHECKED_CAST")

package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import hk.uwu.reareye.generated.AppProperties
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeBootstrapRegistry
import hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveDexKitFieldValue
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.internal.notification.INotificationRouteBridgeService
import hk.uwu.reareye.repository.rearwidget.REAR_WIDGET_CARD_ONE_CONFIG_JSON_KEY
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository.getShowTimeTipForBusiness
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.widgettemplate.WidgetTemplateConfigRepository
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.widgetapi.IRearWidgetApiConnection
import hk.uwu.reareye.widgetapi.IRearWidgetApiService
import hk.uwu.reareye.widgetapi.RearWidgetActiveNotice
import hk.uwu.reareye.widgetapi.RearWidgetApiContract
import hk.uwu.reareye.widgetapi.RearWidgetNoticeOptions
import hk.uwu.reareye.widgetapi.RearWidgetNoticeTicket
import hk.uwu.reareye.widgetapi.RearWidgetSceneRouteSpec
import hk.uwu.reareye.widgetapi.RearWidgetTemplateConfigState
import hk.uwu.reareye.widgetapi.RearWidgetTemplateImagePreview
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

@OptIn(DexKitExperimentalApi::class)
class RearWidgetHook : YukiBaseHooker() {

    private data class OperationOutcome(
        val injectCompositeKey: String? = null,
        val ejectTicket: RearWidgetNoticeTicket? = null,
        val ejectBusiness: Pair<String, String>? = null,
    )

    private data class SceneRouteInjection(
        val scene: String,
        val business: String,
        val staleCompositeKeys: Set<String> = emptySet(),
    )

    private data class PostRunnableSnapshot(
        val owner: Any?,
        val notificationId: Int,
        val notificationKey: String?,
        val packageName: String,
        val extras: Bundle,
    )

    private data class ChannelRouteSeed(
        val channelId: String,
        val title: String?,
        val text: String?,
        val bigText: String?,
        val subText: String?,
        val shortCriticalText: String?,
        val notificationKey: String? = null,
    )

    companion object {
        private const val TAG = "REAREye-RearWidget"
        private const val PERSISTENCE_MANAGER_CLASS_CACHE_KEY =
            "SSC_PERSISTENCE_MANAGER_CLASS"
        private const val SMART_ASSISTANT_RESTORE_WIDGETS_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_RESTORE_WIDGETS_METHOD"
        private const val SMART_ASSISTANT_POST_RUNNABLE_CLASS_CACHE_KEY =
            "SSC_SMART_ASSISTANT_POST_RUNNABLE_CLASS"
        private const val SMART_ASSISTANT_MANAGER_HANDLER_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_HANDLER_FIELD"
        private const val SMART_ASSISTANT_MANAGER_WIDGET_LIST_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_WIDGET_LIST_FIELD"
        private const val SMART_ASSISTANT_MANAGER_CURRENT_INDEX_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_CURRENT_INDEX_FIELD"
        private const val SMART_ASSISTANT_MANAGER_BEFORE_INACTIVE_INDEX_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_BEFORE_INACTIVE_INDEX_FIELD"
        private const val SMART_ASSISTANT_MANAGER_ALLOWED_MAP_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_ALLOWED_MAP_FIELD"
        private const val SMART_ASSISTANT_MANAGER_ALLOWED_SET_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_ALLOWED_SET_FIELD"
        private const val SMART_ASSISTANT_WIDGET_SPEC_BUSINESS_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_WIDGET_SPEC_BUSINESS_FIELD"
        private const val SMART_ASSISTANT_WIDGET_RECORD_EXTRAS_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_WIDGET_RECORD_EXTRAS_FIELD"
        private const val SMART_ASSISTANT_WIDGET_RECORD_PRIORITY_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_WIDGET_RECORD_PRIORITY_FIELD"
        private const val SMART_ASSISTANT_MANAGER_INIT_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_INIT_METHOD"
        private const val SMART_ASSISTANT_MANAGER_REFRESH_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_REFRESH_METHOD"
        private const val SMART_ASSISTANT_MANAGER_INSERT_WIDGET_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_INSERT_WIDGET_METHOD"
        private const val SMART_ASSISTANT_MANAGER_REMOVE_NOTIFICATION_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_REMOVE_NOTIFICATION_METHOD"
        private const val SMART_ASSISTANT_MANAGER_REMOVE_BUSINESS_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_REMOVE_BUSINESS_METHOD"
        private const val SMART_ASSISTANT_MANAGER_REMOVE_COMPOSITE_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_MANAGER_REMOVE_COMPOSITE_METHOD"
        private const val SMART_ASSISTANT_PARSE_WIDGET_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_PARSE_WIDGET_METHOD"
        private const val SMART_ASSISTANT_RESOLVE_PATH_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_RESOLVE_PATH_METHOD"
        private const val SMART_ASSISTANT_ALLOW_APP_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_ALLOW_APP_METHOD"
        private const val SMART_ASSISTANT_DECORATE_EXTRAS_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_DECORATE_EXTRAS_METHOD"
        private const val SMART_ASSISTANT_PARSE_PARAMS_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_PARSE_PARAMS_METHOD"
        private const val SMART_ASSISTANT_BUILTIN_SUPPORT_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_BUILTIN_SUPPORT_METHOD"
        private const val NOTIFICATION_WIDGET_APPLY_METHOD_CACHE_KEY =
            "SSC_NOTIFICATION_WIDGET_APPLY_METHOD"
        private const val NOTIFICATION_WIDGET_TEMPLATE_PATH_FIELD_CACHE_KEY =
            "SSC_NOTIFICATION_WIDGET_TEMPLATE_PATH_FIELD"
        private const val NOTIFICATION_WIDGET_EXTRAS_FIELD_CACHE_KEY =
            "SSC_NOTIFICATION_WIDGET_EXTRAS_FIELD"
        private const val NOTIFICATION_WIDGET_CHANGED_FLAG_FIELD_CACHE_KEY =
            "SSC_NOTIFICATION_WIDGET_CHANGED_FLAG_FIELD"
        private const val NOTIFICATION_WIDGET_EXTRA_CHANGED_METHOD_CACHE_KEY =
            "SSC_NOTIFICATION_WIDGET_EXTRA_CHANGED_METHOD"
        private const val SMART_ASSISTANT_PANEL_HOLDER_LIST_FIELD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_PANEL_HOLDER_LIST_FIELD"
        private const val SMART_ASSISTANT_PANEL_REFRESH_METHOD_CACHE_KEY =
            "SSC_SMART_ASSISTANT_PANEL_REFRESH_METHOD"
        private const val CHANNEL_SCENE_PREFIX = "CH:"
        private const val TEMPLATE_BASE =
            "/data/system/theme_magic/users/%s/subscreencenter/smart_assistant"
        private const val CARD_CONFIG_BASE =
            "/data/system/theme_magic/users/%s/subscreencenter/reareye_card_config"
        private const val CARD_ASSET_BASE =
            "/data/system/theme_magic/users/%s/subscreencenter/reareye_card_assets"
        private val BUILTIN_TEMPLATE_RELATIVE_PATHS = mapOf(
            "incall" to "phone",
            "alarm" to "alarm",
            "countdown" to "timer",
            "carHailing" to "car_hailing",
            "foodDelivery" to "food_delivery",
            "music" to "music",
            "xiaomiev" to "ev",
            "privacy" to "privacy",
            "stock" to "stock",
            "mihomeCamera" to "miHomeCamera",
        )
        private val INTERNAL_BUSINESSES = listOf(
            "incall",
            "carHailing",
            "foodDelivery",
            "music",
            "xiaomiev",
            "privacy",
            "stock",
            "travel",
            "movie",
            "mishow",
            "mihomeCamera"
        )
    }

    private val appliedOnce = AtomicBoolean(false)
    private val startupBootstrapped = AtomicBoolean(false)
    private val bootstrapReceiverRegistered = AtomicBoolean(false)
    private val deployedBlobMetaCache = ConcurrentHashMap<String, String>()
    private val deployedCardConfigMetaCache = ConcurrentHashMap<String, String>()
    private val bootstrapRetryCount = AtomicInteger(0)
    private val managerEpoch = AtomicInteger(0)
    private val liveNotificationWidgets = ConcurrentHashMap<String, WeakReference<Any>>()
    private val smartAssistantPanels = ConcurrentHashMap<Int, WeakReference<Any>>()

    @Volatile
    private var allowNotificationRoutePayloadJsonLog = false

    private var manager: Any? = null
    private var mainHandler: Handler? = null
    private var hostContext: Context? = null
    private var dexKitBridge: DexKitCacheBridge.RecyclableBridge? = null
    private val postRunnableSnapshots = WeakHashMap<Any, PostRunnableSnapshot>()
    private val ordinaryChannelNoticeIndex = ConcurrentHashMap<String, String>()
    private val ordinaryChannelRouteNoticeOptions = RearWidgetNoticeOptions(
        sticky = false,
        disablePopup = false,
        forcePopup = false,
        enableFloat = false,
        showTimeTip = true,
        index = 0,
        priority = -1,
    )
    private val notificationRouteBridgeBootstrap = HookHostBridgeBootstrapRegistry(
        action = NotificationRouteBridgeContract.Action.REQUEST_BINDER,
        binderProvider = { notificationRouteBridgeBinder },
        logger = ::debugLog,
    )

    override fun onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            allowNotificationRoutePayloadJsonLog =
                AppProperties.BUILD_CHANNEL.equals("dev", ignoreCase = true)
            debugLog("hook process=$processName")
            RearWidgetRuntimeStore.install(packageName)
            debugLog("onHook start")

            val versionCode = resolveHookPackageVersionCode(
                context = systemContext,
                packageName = appInfo.packageName,
                sourceDir = appInfo.sourceDir,
            )
            dexKitBridge = createDexKitCacheBridge(
                packageName = appInfo.packageName,
                packageVersionCode = versionCode,
                sourceDir = appInfo.sourceDir,
                dataDir = appInfo.dataDir,
            )

            val appRef = "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().resolve()


            appRef.firstMethod {
                name = "attachBaseContext"
                parameterCount = 1
            }.hook().after {
                hostContext = (args[0] as? Context)?.applicationContext ?: (args[0] as? Context)
                registerHookBootstrapReceiver()
                hostContext?.let {
                    registerNotificationRouteBridge()
                    debugLog(
                        "notification route bridge installed host=${it.packageName} action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER}"
                    )
                }
                applyRuntimeMaps(force = true)
                debugLog("attachBaseContext applied runtime maps and waiting for preset release")
            }

            val managerInitPoint = resolveSmartAssistantManagerInitMethod()
            val managerRefreshPoint = resolveSmartAssistantManagerRefreshMethod()
            val managerInsertPoint = resolveSmartAssistantManagerInsertWidgetMethod()
            val restoreWidgetsPoint = resolveSmartAssistantRestoreWidgetsMethod()
            val parseWidgetPoint = resolveSmartAssistantParseWidgetMethod()
            val resolvePathPoint = resolveSmartAssistantResolvePathMethod()
            val allowAppPoint = resolveSmartAssistantAllowAppMethod()
            val decorateExtrasPoint = resolveSmartAssistantDecorateExtrasMethod()
            val widgetApplyPoint = resolveNotificationWidgetApplyMethod()
            resolveSmartAssistantManagerHandlerFieldName()
            resolveSmartAssistantManagerWidgetListFieldName()
            resolveSmartAssistantManagerCurrentIndexFieldName()
            resolveSmartAssistantManagerBeforeInactiveIndexFieldName()
            resolveSmartAssistantManagerAllowedMapFieldName()
            resolveSmartAssistantManagerAllowedSetFieldName()
            resolveSmartAssistantWidgetSpecBusinessFieldName()
            resolveSmartAssistantWidgetRecordExtrasFieldName()
            resolveSmartAssistantWidgetRecordPriorityFieldName()
            resolveNotificationWidgetTemplatePathFieldName()
            resolveNotificationWidgetExtrasFieldName()
            val managerRef = managerInitPoint.className.toClass().resolve()
            val persistenceRef = resolvePersistenceManagerClassName().toClass().resolve()
            val postRunnableRef =
                resolveSmartAssistantPostRunnableClassName().toClass().resolve()
            val smartAssistantPanelRef =
                "com.xiaomi.subscreencenter.SmartAssistantPanel".toClass().resolve()

            listOf(1, 2, 3).forEach { constructorParamCount ->
                runCatching {
                    smartAssistantPanelRef.firstConstructor {
                        parameterCount = constructorParamCount
                    }.hook().after {
                        rememberSmartAssistantPanel(instance)
                    }
                }
            }

            runCatching {
                smartAssistantPanelRef.firstMethod {
                    name = "setAssistantVisibleImpl"
                    parameterCount = 1
                }.hook().before {
                    rememberSmartAssistantPanel(instance)
                }
            }

            runCatching {
                val panelRefreshPoint = resolveSmartAssistantPanelRefreshMethod()
                smartAssistantPanelRef.firstMethod {
                    name = panelRefreshPoint.methodName
                    parameterCount = 3
                }.hook().before {
                    rememberSmartAssistantPanel(instance)
                }
            }

            persistenceRef.firstConstructor {
                parameterCount = 0
            }.hook().after {
                schedulePostPresetBootstrap()
                debugLog("PersistenceManager created, scheduled custom widget restore after preset release")
            }

            managerRef.firstMethod {
                name = managerInitPoint.methodName
                parameterCount = 1
            }.hook().after {
                val oldManager = manager
                manager = instance
                mainHandler = runCatching {
                    managerRef.firstField {
                        name = resolveSmartAssistantManagerHandlerFieldName()
                    }.get() as? Handler
                }.getOrNull()
                val managerChanged = oldManager !== manager
                if (managerChanged) {
                    managerEpoch.incrementAndGet()
                    liveNotificationWidgets.clear()
                    smartAssistantPanels.clear()
                    ordinaryChannelNoticeIndex.clear()
                }

                if (!managerChanged && startupBootstrapped.get()) {
                    applyRuntimeMaps(force = true)
                    patchManagerAppGates(manager)
                    scheduleInjectAllActiveNotices()
                    debugLog("captured manager unchanged, skip bootstrap and reinject active notices")
                    return@after
                }

                val bootOk = bootstrapFromPrefsOnInit(force = false)
                if (!bootOk) scheduleBootstrapRetry()
                applyRuntimeMaps(force = true)
                patchManagerAppGates(manager)
                scheduleInjectAllActiveNotices()
                debugLog("captured manager=${manager != null}, handler=${mainHandler != null}")
            }

            managerRefreshPoint.className.toClass().resolve().firstMethod {
                name = managerRefreshPoint.methodName
                parameterCount = 1
            }.hook().after {
                patchManagerAppGates(instance)
            }

            restoreWidgetsPoint.className.toClass().resolve().firstMethod {
                name = restoreWidgetsPoint.methodName
            }.hook().after {
                normalizeRestoredManagerWidgetPriority(manager)
            }

            managerRef.firstMethod {
                name = managerInsertPoint.methodName
                parameterCount = 1
            }.hook().after {
                normalizeInitialManagerWidgetPriority(instance, args.getOrNull(0))
            }

            parseWidgetPoint.className.toClass().resolve().firstMethod {
                name = parseWidgetPoint.methodName
                parameterCount = 2
            }.hook().after {
                val pkg = args[0] as? String ?: return@after
                if (result != null) return@after
                val biz = RearWidgetRuntimeStore.fallbackBusiness(pkg) ?: return@after
                result = createU0b(biz, 0, 600)
                debugLog("smart assistant parse fallback pkg=$pkg -> business=$biz")
            }

            resolvePathPoint.className.toClass().resolve().firstMethod {
                name = resolvePathPoint.methodName
                parameterCount = 2
            }.hook().after {
                val pkg = args[0] as? String ?: return@after
                val biz = args[1] as? String ?: return@after
                // business 文件映射是全局覆盖 只要注册了该 business 文件 就覆盖系统内置路径
                val path = RearWidgetRuntimeStore.getBusinessFile(biz) ?: return@after
                result = path
                debugLog("smart assistant override path pkg=$pkg biz=$biz path=$path")
            }

            allowAppPoint.className.toClass().resolve().firstMethod {
                name = allowAppPoint.methodName
                parameterCount = 3
            }.hook().before {
                val pkg = args[0] as? String ?: return@before
                if (RearWidgetRuntimeStore.hasAnyBusinessForPackage(pkg)) {
                    result = true
                    debugLog("smart assistant allow force pass pkg=$pkg")
                }
            }

            postRunnableRef.firstMethod {
                name = "run"
                parameterCount = 0
            }.hook().before {
                allowSelfDescribedNotificationPackage(instance)
            }

            postRunnableRef.firstMethod {
                name = "run"
                parameterCount = 0
            }.hook().after {
                val snapshot = synchronized(postRunnableSnapshots) {
                    postRunnableSnapshots[instance]
                } ?: return@after
                rememberOriginalNotificationRoute(snapshot)
            }

            resolveSmartAssistantManagerRemoveNotificationMethod().className.toClass()
                .resolve().firstMethod {
                    name = resolveSmartAssistantManagerRemoveNotificationMethod().methodName
                    parameterCount = 3
                }.also {
                    val compat = it.self.parameterTypes[1] == String::class.java
                    it.hook().after {
                        val notificationId = args.getOrNull(0) as? Int ?: return@after
                        val packageName =
                            args.getOrNull(if (compat) 1 else 2) as? String ?: return@after
                        val removeReason = args.getOrNull(if (compat) 2 else 1) as? Int ?: 0
                        handleOriginalNotificationRemoved(
                            packageName = packageName,
                            notificationId = notificationId,
                            notificationKey = null,
                            removeReason = removeReason,
                        )
                    }
                }

            postRunnableRef.firstConstructor {
                parameterCount = 5
            }.hook().after {
                val notificationId = args.getOrNull(1) as? Int ?: return@after
                val packageName = args.getOrNull(2) as? String ?: return@after
                val notificationKey = args.getOrNull(3) as? String
                val extras = args.getOrNull(4) as? Bundle ?: return@after
                val hasChRoute = RearWidgetRuntimeStore.hasSceneRoutePrefix(
                    packageName,
                    CHANNEL_SCENE_PREFIX,
                )
                debugLog(
                    "ordinary notice postRunnable pkg=$packageName id=$notificationId key=${notificationKey.orEmpty()} " +
                            "hasChRoute=$hasChRoute hasFocus=${
                                !extras.getString("miui.focus.param").isNullOrBlank()
                            } " +
                            "hasRear=${
                                !extras.getString("miui.rear.param").isNullOrBlank()
                            }"
                )
                val injected = applySceneRouteBusinessToExtras(
                    packageName = packageName,
                    notificationId = notificationId,
                    notificationKey = notificationKey,
                    extras = extras,
                )
                synchronized(postRunnableSnapshots) {
                    postRunnableSnapshots[instance] = PostRunnableSnapshot(
                        owner = args.getOrNull(0),
                        notificationId = notificationId,
                        notificationKey = notificationKey,
                        packageName = packageName,
                        extras = Bundle(extras),
                    )
                }
                if (injected != null) {
                    injected.staleCompositeKeys.forEach { staleKey ->
                        ejectByCompositeKey(staleKey)
                    }
                    debugLog(
                        "scene route injected pkg=$packageName scene=${injected.scene} business=${injected.business}"
                    )
                }
            }

            widgetApplyPoint.className.toClass().resolve().firstMethod {
                name = widgetApplyPoint.methodName
                parameterCount = 1
            }.hook().after {
                rememberLiveNotificationWidget(instance)
                applyCardOneConfig(
                    instance,
                    args.getOrNull(0),
                    "notificationWidget.${widgetApplyPoint.methodName}"
                )
            }

            decorateExtrasPoint.className.toClass().resolve().firstMethod {
                name = decorateExtrasPoint.methodName
                parameterCount = 10
            }.hook().after {
                applyRuntimeMaps(force = false)
                val out = result as? Bundle ?: return@after
                val key = out.getString("composite_key") ?: (args.getOrNull(1) as? String)
                val notice = key?.let { RearWidgetRuntimeStore.getNotice(it) } ?: return@after
                out.putAll(RearWidgetRuntimeStore.buildDecoratedExtras(notice.ticket))
            }
        }
    }

    private inline fun resolveCachedMethodPoint(
        cacheKey: String,
        crossinline finder: DexKitBridge.() -> MethodData?,
    ): DexKitMethodInjectionPoint {
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for method cache=$cacheKey")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = cacheKey,
        ) {
            finder()
        } ?: DexKitMethodInjectionPoint("", "")
        require(point.className.isNotBlank() && point.methodName.isNotBlank()) {
            "DexKit failed to resolve method cache=$cacheKey"
        }
        return point
    }

    private inline fun resolveCachedClassName(
        cacheKey: String,
        crossinline finder: DexKitBridge.() -> ClassData?,
    ): String {
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for class cache=$cacheKey")
        val className = resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = cacheKey,
        ) {
            finder()
        } ?: ""
        require(className.isNotBlank()) {
            "DexKit failed to resolve class cache=$cacheKey"
        }
        return className
    }

    private inline fun resolveCachedFieldName(
        cacheKey: String,
        crossinline finder: DexKitBridge.() -> FieldData?,
    ): String {
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for field cache=$cacheKey")
        val fieldName = resolveDexKitFieldValue(
            bridge = bridge,
            cacheKey = cacheKey,
        ) {
            finder()
        } ?: ""
        require(fieldName.isNotBlank()) {
            "DexKit failed to resolve field cache=$cacheKey"
        }
        return fieldName
    }

    private fun resolvePersistenceManagerClassName(): String {
        return resolveCachedClassName(
            cacheKey = PERSISTENCE_MANAGER_CLASS_CACHE_KEY
        ) {
            // Original class in jadx: H.d (PersistenceManager)
            findClass {
                matcher {
                    usingStrings(
                        "PersistenceManager",
                        "Widgets loaded success, widgets = ",
                        "Save notification widgets to ",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantRestoreWidgetsMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_RESTORE_WIDGETS_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    usingStrings(
                        "Widget already exists, skipping: %s",
                        "Restored widget: %s with priority=%d",
                        "All widgets initialized: display=%d, beforeInactive=%d",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantPostRunnableClassName(): String {
        return resolveCachedClassName(
            cacheKey = SMART_ASSISTANT_POST_RUNNABLE_CLASS_CACHE_KEY
        ) {
            // Original class in jadx: Z1.m (notification post runnable)
            findClass {
                matcher {
                    usingStrings(
                        "No valid params: %s",
                        "Using compositeKey: %s (business: %s)",
                        "Triggered upside-down check for business: %s, key: %s",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerClassName(): String {
        return resolveSmartAssistantManagerInitMethod().className
    }

    private fun resolveSmartAssistantWidgetRecordClassName(): String {
        val point = resolveSmartAssistantManagerInsertWidgetMethod()
        return runCatching {
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 1
            }.self.parameterTypes.first().name
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: error("DexKit failed to resolve smart assistant widget record class")
    }

    private fun resolveNotificationWidgetHostClassName(): String {
        return resolveNotificationWidgetApplyMethod().className.toClass().superclass?.name
            ?.takeIf { it.isNotBlank() }
            ?: error("DexKit failed to resolve notification widget host class")
    }

    private fun resolveNotificationWidgetBaseClassName(): String {
        return resolveNotificationWidgetHostClassName().toClass().superclass?.name
            ?.takeIf { it.isNotBlank() }
            ?: error("DexKit failed to resolve notification widget base class")
    }

    private fun resolveSmartAssistantUtilsClassName(): String {
        return resolveSmartAssistantParseWidgetMethod().className
    }

    private fun resolveSmartAssistantConfigClassName(): String {
        return resolveSmartAssistantBuiltinSupportMethod().className
    }

    private fun resolveSmartAssistantManagerHandlerFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_MANAGER_HANDLER_FIELD_CACHE_KEY
        ) {
            val managerClass = resolveSmartAssistantManagerClassName()
            findField {
                searchPackages(managerClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = managerClass
                    modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
                    type = "android.os.Handler"
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerWidgetListFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_MANAGER_WIDGET_LIST_FIELD_CACHE_KEY,
        ) {
            val managerClass = resolveSmartAssistantManagerClassName()
            val insertPoint = resolveSmartAssistantManagerInsertWidgetMethod()
            findField {
                searchPackages(managerClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = managerClass
                    type = "java.util.ArrayList"
                    readMethods {
                        add {
                            declaredClass = insertPoint.className
                            name = insertPoint.methodName
                            paramCount(1)
                            returnType = "void"
                            usingStrings(
                                "Inserted widget at position %d, type=%s, new display index=%d",
                            )
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerCurrentIndexFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_MANAGER_CURRENT_INDEX_FIELD_CACHE_KEY,
        ) {
            val managerClass = resolveSmartAssistantManagerClassName()
            val insertPoint = resolveSmartAssistantManagerInsertWidgetMethod()
            findField {
                searchPackages(managerClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = managerClass
                    type = "int"
                    readMethods {
                        add {
                            declaredClass = insertPoint.className
                            name = insertPoint.methodName
                            paramCount(1)
                            returnType = "void"
                            usingStrings(
                                "Inserted widget at position %d, type=%s, new display index=%d",
                            )
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerBeforeInactiveIndexFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_MANAGER_BEFORE_INACTIVE_INDEX_FIELD_CACHE_KEY,
        ) {
            val managerClass = resolveSmartAssistantManagerClassName()
            findField {
                searchPackages(managerClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = managerClass
                    type = "int"
                    readMethods {
                        // FIXED: Removed strict 'declaredClass' requirements so DexKit
                        // can successfully trace Xiaomi's lambda-optimized background tasks.
                        add {
                            usingStrings("All widgets initialized: display=%d, beforeInactive=%d")
                        }
                        add {
                            usingStrings("Pin restored first update, using mIndexBeforeInactive: %d")
                        }
                        add {
                            usingStrings("Using previous index while inactive: %d")
                        }
                        add {
                            usingStrings("Keeping mIndexBeforeInactive at %d")
                        }
                    }
                }
            }.firstOrNull()
        }
    }

    private fun resolveSmartAssistantManagerAllowedMapFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_MANAGER_ALLOWED_MAP_FIELD_CACHE_KEY
        ) {
            val managerClass = resolveSmartAssistantManagerClassName()
            val refreshPoint = resolveSmartAssistantManagerRefreshMethod()
            findField {
                searchPackages(managerClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = managerClass
                    type = "java.util.concurrent.ConcurrentHashMap"
                    readMethods {
                        add {
                            declaredClass = refreshPoint.className
                            name = refreshPoint.methodName
                            paramTypes("boolean")
                            returnType = "java.util.HashSet"
                            usingStrings("Converted travel key: %s -> %s = %s")
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerAllowedSetFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_MANAGER_ALLOWED_SET_FIELD_CACHE_KEY
        ) {
            val managerClass = resolveSmartAssistantManagerClassName()
            val refreshPoint = resolveSmartAssistantManagerRefreshMethod()
            findField {
                searchPackages(managerClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = managerClass
                    // FIXED: Removed the illegal $$ and properly escaped the inner $
                    type = "java.util.concurrent.ConcurrentHashMap\$KeySetView"
                    readMethods {
                        add {
                            declaredClass = refreshPoint.className
                            name = refreshPoint.methodName
                            paramTypes("boolean")
                            returnType = "java.util.HashSet"
                            usingStrings("Converted travel key: %s -> %s = %s")
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantWidgetSpecBusinessFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_WIDGET_SPEC_BUSINESS_FIELD_CACHE_KEY
        ) {
            val specClass = resolveSmartAssistantWidgetSpecClassName()
            YLog.debug(specClass)
            findField {
                searchPackages(specClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = specClass
                    type(Any::class.java)
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantWidgetRecordExtrasFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_WIDGET_RECORD_EXTRAS_FIELD_CACHE_KEY,
        ) {
            val widgetRecordClass = resolveSmartAssistantWidgetRecordClassName()
            findField {
                searchPackages(widgetRecordClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = widgetRecordClass
                    type = "android.os.Bundle"
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantWidgetRecordPriorityFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_WIDGET_RECORD_PRIORITY_FIELD_CACHE_KEY,
        ) {
            val widgetRecordClass = resolveSmartAssistantWidgetRecordClassName()
            val insertPoint = resolveSmartAssistantManagerInsertWidgetMethod()
            findField {
                searchPackages(widgetRecordClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = widgetRecordClass
                    type = "int"
                    readMethods {
                        add {
                            declaredClass = insertPoint.className
                            name = insertPoint.methodName
                            paramCount(1)
                            returnType = "void"
                            usingStrings(
                                "Active: insert after index %d (priority %d < %d)",
                            )
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantWidgetSpecClassName(): String {
        val point = resolveSmartAssistantParseWidgetMethod()
        return runCatching {
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 2
            }.self.returnType.name
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: error("DexKit failed to resolve smart assistant widget spec class")
    }

    private fun resolveSmartAssistantManagerInitMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_MANAGER_INIT_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx/vineflower: Z1.d0.l(Context)
            findMethod {
                matcher {
                    paramTypes(Context::class.java)
                    returnType = "void"
                    usingStrings(
                        "SmartAssistantManager initialized",
                        "SmartAssistant not supported, skip manager initialization",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerRefreshMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_MANAGER_REFRESH_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx/vineflower: Z1.d0.o(boolean)
            findMethod {
                matcher {
                    declaredClass = resolveSmartAssistantManagerClassName()
                    paramTypes("boolean")
                    returnType = "java.util.HashSet"
                    usingStrings("Converted travel key: %s -> %s = %s")
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerInsertWidgetMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_MANAGER_INSERT_WIDGET_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx/vineflower: Z1.d0.n(c0)
            findMethod {
                matcher {
                    declaredClass = resolveSmartAssistantManagerClassName()
                    paramCount(1)
                    returnType = "void"
                    usingStrings(
                        "Active: insert after index %d (priority %d < %d)",
                        "Inserted widget at position %d, type=%s, new display index=%d",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerRemoveNotificationMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_MANAGER_REMOVE_NOTIFICATION_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx/vineflower: Z1.d0.p(int, String, int)
            findMethod {
                matcher {
                    declaredClass = resolveSmartAssistantManagerClassName()
                    paramCount(3)
                    returnType = "void"
                    usingStrings("Widget not found for multi-business app: %s, ID: %d")
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerRemoveBusinessMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_MANAGER_REMOVE_BUSINESS_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx/vineflower: Z1.d0.v(String, String)
            findMethod {
                matcher {
                    declaredClass = resolveSmartAssistantManagerClassName()
                    paramTypes(String::class.java, String::class.java)
                    returnType = "void"
                    usingStrings("Removing widgets for %s:%s")
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantManagerRemoveCompositeMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_MANAGER_REMOVE_COMPOSITE_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx/vineflower: Z1.d0.C(int, String, String)
            findMethod {
                matcher {
                    declaredClass = resolveSmartAssistantManagerClassName()
                    paramTypes("int", "java.lang.String", "java.lang.String")
                    returnType = "boolean"
                    usingStrings("Found widget for compositeKey: %s, removing")
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantParseWidgetMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_PARSE_WIDGET_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx: p2.c.r(String, j2.a)
            findMethod {
                matcher {
                    paramCount(2)
                    usingStrings(
                        "Found business in rear.paramV1: %s",
                        "No business found for %s and not in config",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantResolvePathMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_RESOLVE_PATH_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx: p2.c.i(String, String)
            findMethod {
                matcher {
                    declaredClass = resolveSmartAssistantUtilsClassName()
                    paramTypes(String::class.java, String::class.java)
                    returnType = "java.lang.String"
                    usingStrings("unified.music", "music")
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantAllowAppMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_ALLOW_APP_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx: p2.c.k(String, Set, Map)
            findMethod {
                matcher {
                    declaredClass = resolveSmartAssistantUtilsClassName()
                    paramTypes("java.lang.String", "java.util.Set", "java.util.Map")
                    returnType = "boolean"
                    usingStrings(
                        "Music app %s allowed: %s (music switch: %s)",
                        "Multi-business app %s allowed: false (no business enabled)",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantDecorateExtrasMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_DECORATE_EXTRAS_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx: p2.c.s(Bundle, ... , j2.a)
            findMethod {
                matcher {
                    declaredClass = resolveSmartAssistantUtilsClassName()
                    paramCount(10)
                    returnType = "android.os.Bundle"
                    usingStrings("composite_key", "disable_popup", "is_remote_view")
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantParseParamsMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_PARSE_PARAMS_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx: L1.a.y(Bundle)
            findMethod {
                matcher {
                    paramTypes(Bundle::class.java)
                    usingStrings(
                        "Original params - rearParam: ",
                        "miui.rear.param",
                        "miui.focus.param"
                    )
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantBuiltinSupportMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_BUILTIN_SUPPORT_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx: p2.a.d(String, String)
            findMethod {
                matcher {
                    declaredClass {
                        usingStrings("unified.music", "music", "foodDelivery", "carHailing")
                        methods {
                            add {
                                paramTypes(String::class.java)
                                returnType = "boolean"
                                usingStrings("unified.music", "music")
                            }
                        }
                    }
                    paramTypes(String::class.java, String::class.java)
                    returnType = "boolean"
                }
            }.singleOrNull()
        }
    }

    private fun resolveNotificationWidgetApplyMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = NOTIFICATION_WIDGET_APPLY_METHOD_CACHE_KEY,
        ) {
            // Original method in jadx: t2.j.J(t2.f)
            findMethod {
                matcher {
                    returnType = "void"
                    usingStrings("notification_received", "params_transferred")
                }
            }.singleOrNull()
        }
    }

    private fun resolveNotificationWidgetTemplatePathFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = NOTIFICATION_WIDGET_TEMPLATE_PATH_FIELD_CACHE_KEY,
        ) {
            val hostClass = resolveNotificationWidgetHostClassName()
            findField {
                searchPackages(hostClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = hostClass
                    type = "java.lang.String"
                    readMethods {
                        add {
                            declaredClass = hostClass
                            paramTypes(Context::class.java)
                            returnType = "android.view.View"
                            usingStrings("onCreate path =")
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveNotificationWidgetExtrasFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = NOTIFICATION_WIDGET_EXTRAS_FIELD_CACHE_KEY,
        ) {
            val baseClass = resolveNotificationWidgetBaseClassName()
            val widgetApplyPoint = resolveNotificationWidgetApplyMethod()
            findField {
                searchPackages(baseClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = baseClass
                    type = "android.os.Bundle"
                    readMethods {
                        add {
                            declaredClass = widgetApplyPoint.className
                            name = widgetApplyPoint.methodName
                            paramCount(1)
                            returnType = "void"
                            usingStrings("notification_received", "params_transferred")
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveNotificationWidgetChangedFlagFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = NOTIFICATION_WIDGET_CHANGED_FLAG_FIELD_CACHE_KEY,
        ) {
            val baseClass = resolveNotificationWidgetBaseClassName()
            findField {
                searchPackages(baseClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = baseClass
                    type = "int"
                    writeMethods {
                        add {
                            paramCount(0)
                            returnType = "void"
                            usingStrings("Refresh current widget, because")
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveNotificationWidgetExtraChangedMethodName(): String {
        return resolveCachedMethodPoint(
            cacheKey = NOTIFICATION_WIDGET_EXTRA_CHANGED_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    paramTypes(Bundle::class.java)
                    returnType = "void"
                    usingStrings("extra changed, extra =")
                }
            }.singleOrNull()
        }.methodName
    }

    private fun resolveSmartAssistantPanelHolderListFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SMART_ASSISTANT_PANEL_HOLDER_LIST_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    declaredClass = "com.xiaomi.subscreencenter.SmartAssistantPanel"
                    type = "java.util.ArrayList"
                    readMethods {
                        add {
                            declaredClass = "com.xiaomi.subscreencenter.SmartAssistantPanel"
                            usingStrings("createWidgets: index=")
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveSmartAssistantPanelRefreshMethod(): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            cacheKey = SMART_ASSISTANT_PANEL_REFRESH_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = "com.xiaomi.subscreencenter.SmartAssistantPanel"
                    paramTypes("int", "int", "java.lang.Runnable")
                    returnType = "void"
                }
            }.singleOrNull()
        }
    }

    private fun invokeSmartAssistantManagerRemoveNotification(
        target: Any,
        notificationId: Int,
        packageName: String,
        removeReason: Int,
    ) {
        val point = resolveSmartAssistantManagerRemoveNotificationMethod()
        target.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 3
        }.also {
            val compat = it.self.parameterTypes[1] == String::class.java
            if (compat) {
                it.invoke(notificationId, packageName, removeReason)
            } else {
                it.invoke(notificationId, removeReason, packageName)
            }
        }
    }

    private fun invokeSmartAssistantManagerRemoveBusiness(
        target: Any,
        packageName: String,
        business: String
    ) {
        val point = resolveSmartAssistantManagerRemoveBusinessMethod()
        target.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 2
        }.invoke(packageName, business)
    }

    private fun invokeSmartAssistantManagerRemoveComposite(
        target: Any,
        compositeKey: String,
        packageName: String,
        removeReason: Int,
    ): Boolean {
        val point = resolveSmartAssistantManagerRemoveCompositeMethod()
        return target.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 3
        }.invoke<Boolean>(removeReason, compositeKey, packageName) == true
    }

    private fun invokeSmartAssistantParseParams(bundle: Bundle): Any? {
        val point = resolveSmartAssistantParseParamsMethod()
        return point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 1
        }.invoke(bundle)
    }

    private fun invokeSmartAssistantParseWidget(packageName: String, parsedParams: Any): Any? {
        val point = resolveSmartAssistantParseWidgetMethod()
        return point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 2
        }.invoke(packageName, parsedParams)
    }

    private fun invokeSmartAssistantBuiltinSupport(packageName: String, business: String): Boolean {
        val point = resolveSmartAssistantBuiltinSupportMethod()
        return point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 2
        }.invoke<Boolean>(packageName, business) ?: false
    }

    private fun invokeSmartAssistantResolvePath(packageName: String, business: String): String? {
        val point = resolveSmartAssistantResolvePathMethod()
        return point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 2
        }.invoke<String>(packageName, business)
    }

    private fun readManagerAllowedPackageSet(target: Any): ConcurrentHashMap.KeySetView<String, *> {
        @Suppress("UNCHECKED_CAST")
        return target.asResolver().firstField {
            name = resolveSmartAssistantManagerAllowedSetFieldName()
        }.get() as ConcurrentHashMap.KeySetView<String, *>
    }

    private fun readManagerAllowedPackageMap(target: Any): ConcurrentHashMap<String, Boolean> {
        @Suppress("UNCHECKED_CAST")
        return target.asResolver().firstField {
            name = resolveSmartAssistantManagerAllowedMapFieldName()
        }.get() as ConcurrentHashMap<String, Boolean>
    }

    private val hookBinder = object : IRearWidgetApiService.Stub() {
        override fun registerBusinessFile(business: String?, filePath: String?) {
            enforceCallerPermission()
            val normalizedBusiness = business?.trim().orEmpty()
            val normalizedFilePath = filePath?.trim().orEmpty()
            if (normalizedBusiness.isBlank() || normalizedFilePath.isBlank()) return
            dispatchOperation(
                op = RearWidgetApiContract.Operation.REGISTER_FILE,
                action = {
                    val deployedPath =
                        deployBusinessTemplate(normalizedBusiness, normalizedFilePath)
                            ?: error("deploy template failed for business=$normalizedBusiness source=$normalizedFilePath")
                    RearWidgetRuntimeStore.registerBusinessFile(normalizedBusiness, deployedPath)
                    OperationOutcome()
                }
            )
        }

        override fun unregisterBusinessFile(business: String?) {
            enforceCallerPermission()
            val normalizedBusiness = business?.trim().orEmpty()
            if (normalizedBusiness.isBlank()) return
            dispatchOperation(
                op = RearWidgetApiContract.Operation.UNREGISTER_FILE,
                action = {
                    RearWidgetRuntimeStore.unregisterBusinessFile(normalizedBusiness)
                    removeDeployedBusinessTemplate(normalizedBusiness)
                    OperationOutcome()
                }
            )
        }

        override fun registerBusiness(
            targetPackage: String?,
            business: String?,
            filePath: String?,
            defaultIndex: Int,
            defaultPriority: Int,
        ) {
            enforceCallerPermission()
            val normalizedBusiness = business?.trim().orEmpty()
            val normalizedFilePath = filePath?.trim().orEmpty()
            if (normalizedBusiness.isBlank() || normalizedFilePath.isBlank()) return
            dispatchOperation(
                op = RearWidgetApiContract.Operation.REGISTER,
                action = {
                    val deployedPath =
                        deployBusinessTemplate(normalizedBusiness, normalizedFilePath)
                            ?: error("deploy template failed for business=$normalizedBusiness source=$normalizedFilePath")
                    RearWidgetRuntimeStore.registerBusiness(
                        packageName = normalizeTargetPackage(targetPackage),
                        business = normalizedBusiness,
                        filePath = deployedPath,
                        defaultIndex = defaultIndex,
                        defaultPriority = defaultPriority,
                    )
                    OperationOutcome()
                }
            )
        }

        override fun registerBusinessWithoutFile(
            targetPackage: String?,
            business: String?,
            defaultIndex: Int,
            defaultPriority: Int,
        ) {
            enforceCallerPermission()
            val normalizedBusiness = business?.trim().orEmpty()
            if (normalizedBusiness.isBlank()) return
            dispatchOperation(
                op = RearWidgetApiContract.Operation.REGISTER,
                action = {
                    val registered = RearWidgetRuntimeStore.registerBusinessWithoutFile(
                        packageName = normalizeTargetPackage(targetPackage),
                        business = normalizedBusiness,
                        defaultIndex = defaultIndex,
                        defaultPriority = defaultPriority,
                    )
                    check(registered) { "filePath not found for business: $normalizedBusiness" }
                    OperationOutcome()
                }
            )
        }

        override fun unregisterBusiness(targetPackage: String?, business: String?) {
            enforceCallerPermission()
            val normalizedBusiness = business?.trim().orEmpty()
            if (normalizedBusiness.isBlank()) return
            val packageName = normalizeTargetPackage(targetPackage)
            dispatchOperation(
                op = RearWidgetApiContract.Operation.UNREGISTER,
                action = {
                    RearWidgetRuntimeStore.unregisterBusiness(packageName, normalizedBusiness)
                    OperationOutcome(ejectBusiness = packageName to normalizedBusiness)
                }
            )
        }

        override fun registerSceneRoute(targetPackage: String?, scene: String?, business: String?) {
            enforceCallerPermission()
            val normalizedBusiness = business?.trim().orEmpty()
            val normalizedScene = RearWidgetSceneRouteSpec.normalizeScenePattern(scene.orEmpty())
            if (normalizedBusiness.isBlank() || normalizedScene.isBlank()) return
            dispatchOperation(
                op = RearWidgetApiContract.Operation.REGISTER_SCENE_ROUTE,
                action = {
                    RearWidgetRuntimeStore.registerSceneRoute(
                        packageName = normalizeTargetPackage(targetPackage),
                        scene = normalizedScene,
                        business = normalizedBusiness,
                    )
                    OperationOutcome()
                }
            )
        }

        override fun unregisterSceneRoute(targetPackage: String?, scene: String?) {
            enforceCallerPermission()
            val normalizedScene = RearWidgetSceneRouteSpec.normalizeScenePattern(scene.orEmpty())
            if (normalizedScene.isBlank()) return
            dispatchOperation(
                op = RearWidgetApiContract.Operation.UNREGISTER_SCENE_ROUTE,
                action = {
                    RearWidgetRuntimeStore.unregisterSceneRoute(
                        packageName = normalizeTargetPackage(targetPackage),
                        scene = normalizedScene,
                    )
                    OperationOutcome()
                }
            )
        }

        override fun disableBusinessDisplay(targetPackage: String?, business: String?) {
            enforceCallerPermission()
            val normalizedBusiness = business?.trim().orEmpty()
            if (normalizedBusiness.isBlank()) return
            val packageName = normalizeTargetPackage(targetPackage)
            dispatchOperation(
                op = RearWidgetApiContract.Operation.DISABLE_DISPLAY,
                action = {
                    RearWidgetRuntimeStore.disableBusinessDisplay(packageName, normalizedBusiness)
                    OperationOutcome(ejectBusiness = packageName to normalizedBusiness)
                }
            )
        }

        override fun postNotice(
            targetPackage: String?,
            business: String?,
            payload: Bundle?,
            options: Bundle?,
        ) {
            enforceCallerPermission()
            val normalizedBusiness = business?.trim().orEmpty()
            if (normalizedBusiness.isBlank()) return
            val noticeOptions = RearWidgetNoticeOptions.fromBundle(options)
            dispatchOperation(
                op = RearWidgetApiContract.Operation.POST,
                action = {
                    val ticket = RearWidgetRuntimeStore.postNotice(
                        packageName = normalizeTargetPackage(targetPackage),
                        business = normalizedBusiness,
                        payload = payload ?: Bundle(),
                        options = noticeOptions,
                    )
                    OperationOutcome(injectCompositeKey = ticket.compositeKey)
                }
            )
        }

        override fun updateNotice(
            ticket: Bundle?,
            payload: Bundle?,
            options: Bundle?,
            updatePayload: Boolean,
            updateOptions: Boolean,
        ) {
            enforceCallerPermission()
            val noticeTicket = RearWidgetNoticeTicket.fromBundle(ticket) ?: return
            val payloadArg = if (updatePayload) payload ?: Bundle() else null
            val optionsArg = if (updateOptions) {
                RearWidgetNoticeOptions.fromBundle(options)
            } else {
                null
            }
            dispatchOperation(
                op = RearWidgetApiContract.Operation.UPDATE,
                action = {
                    RearWidgetRuntimeStore.updateNotice(noticeTicket, payloadArg, optionsArg)
                    OperationOutcome(injectCompositeKey = noticeTicket.compositeKey)
                }
            )
        }

        override fun removeNotice(ticket: Bundle?) {
            enforceCallerPermission()
            val noticeTicket = RearWidgetNoticeTicket.fromBundle(ticket) ?: return
            dispatchOperation(
                op = RearWidgetApiContract.Operation.REMOVE,
                action = {
                    RearWidgetRuntimeStore.removeNotice(noticeTicket)
                    OperationOutcome(ejectTicket = noticeTicket)
                }
            )
        }

        override fun syncState() {
            enforceCallerPermission()
            bootstrapFromPrefsOnInit(force = true)
            applyRuntimeMaps(force = true)
            patchManagerAppGates(manager)
            scheduleInjectAllActiveNotices()
        }

        override fun resolveTemplateImagePreview(
            business: String?,
            sourceFilePath: String?,
            imageValue: String?,
        ): Bundle {
            enforceCallerPermission()
            val preview = resolveTemplateImagePreviewModel(
                business = business?.trim().orEmpty(),
                sourcePath = sourceFilePath?.trim().orEmpty(),
                imageValue = imageValue?.trim().orEmpty(),
            )
            return preview?.toBundle() ?: Bundle()
        }

        override fun resolveTemplateConfigState(
            business: String?,
            sourceFilePath: String?,
            currentOneConfigJson: String?,
        ): Bundle {
            enforceCallerPermission()
            val state = resolveTemplateConfigStateModel(
                business = business?.trim().orEmpty(),
                sourcePath = sourceFilePath?.trim().orEmpty(),
                currentOneConfigJson = currentOneConfigJson?.trim(),
            )
            return state?.toBundle() ?: Bundle()
        }

        override fun importCardCustomImage(
            cardKey: String?,
            fieldName: String?,
            sourceUri: String?,
            displayNameHint: String?,
        ): String {
            enforceCallerPermission()
            return importCardCustomImageInternal(
                cardKey = cardKey?.trim().orEmpty(),
                fieldName = fieldName?.trim().orEmpty(),
                sourceUri = sourceUri?.trim().orEmpty(),
                displayNameHint = displayNameHint?.trim().orEmpty(),
            ).orEmpty()
        }
    }

    private val notificationRouteBridgeBinder = object : INotificationRouteBridgeService.Stub() {
        override fun dispatch(subchannel: String?, payload: Bundle?): Boolean {
            enforceNotificationRouteCaller()
            val normalizedSubchannel = subchannel?.trim().orEmpty()
            if (normalizedSubchannel.isBlank()) return false

            val payloadCopy = Bundle(payload ?: Bundle.EMPTY)
            return runCatching {
                when (normalizedSubchannel) {
                    NotificationRouteBridgeContract.Subchannel.NOTIFICATION_POSTED -> {
                        if (shouldLogNotificationRoutePayloadJson()) {
                            debugLog("notification route posted payload=${payloadCopy.toJsonStringByGson()}")
                        }
                        handleNotificationRoutePosted(payloadCopy)
                    }

                    NotificationRouteBridgeContract.Subchannel.NOTIFICATION_REMOVED -> {
                        handleNotificationRouteRemoved(payloadCopy)
                    }

                    else -> return false
                }
                true
            }.onFailure {
                debugLog(
                    "notification route dispatch failed subchannel=$normalizedSubchannel err=${it.message}"
                )
            }.getOrDefault(false)
        }
    }

    private val hookBootstrapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RearWidgetApiContract.ACTION_REQUEST_HOOK_SERVICE) return
            val callbackBinder = intent
                .getBundleExtra(RearWidgetApiContract.Extras.BUNDLE)
                ?.getBinder(RearWidgetApiContract.Extras.BINDER)
            val callback = IRearWidgetApiConnection.Stub.asInterface(callbackBinder)
            val forceSync = intent.getBooleanExtra(RearWidgetApiContract.Extras.FORCE_SYNC, false)
            if (forceSync) {
                bootstrapFromPrefsOnInit(force = true)
            }
            runCatching {
                callback?.onServiceConnected(hookBinder)
            }.onFailure {
                debugLog("reply hook binder failed err=${it.message}")
            }
        }
    }

    private fun registerHookBootstrapReceiver() {
        if (!bootstrapReceiverRegistered.compareAndSet(false, true)) return
        val ctx = hostContext ?: run {
            bootstrapReceiverRegistered.set(false)
            return
        }
        val ok = runCatching {
            val filter = IntentFilter(RearWidgetApiContract.ACTION_REQUEST_HOOK_SERVICE)
            ContextCompat.registerReceiver(
                ctx,
                hookBootstrapReceiver,
                filter,
                RearWidgetApiContract.SERVICE_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            true
        }.onFailure {
            bootstrapReceiverRegistered.set(false)
            debugLog("register bootstrap receiver failed err=${it.message}")
        }.getOrDefault(false)
        if (ok) {
            debugLog("register bootstrap receiver success")
        }
    }

    private fun registerNotificationRouteBridge() {
        val ctx = hostContext ?: return
        notificationRouteBridgeBootstrap.register(ctx)
    }

    private fun shouldLogNotificationRoutePayloadJson(): Boolean {
        return allowNotificationRoutePayloadJsonLog && prefs.getBoolean(
            ConfigKeys.MORE_DEBUG,
            false
        )
    }

    private fun Bundle.toJsonStringByGson(): String {
        val map = LinkedHashMap<String, Any?>(keySet().size)
        keySet().forEach { key ->
            @Suppress("DEPRECATION")
            map[key] = get(key)
        }
        return com.google.gson.Gson().toJson(map)
    }

    private fun dispatchOperation(op: String, action: () -> OperationOutcome) {
        val outcome = action()
        applyRuntimeMaps(force = true)
        patchManagerAppGates(manager)
        if (!RearWidgetRuntimeStore.hasAnySceneRoutePrefix(CHANNEL_SCENE_PREFIX)) {
            clearAllOrdinaryChannelRouteNotices("$op:no_channel_route")
        }
        outcome.injectCompositeKey?.let { applyNoticeDisplayByCompositeKey(it) }
        outcome.ejectTicket?.let { ejectByTicket(it) }
        outcome.ejectBusiness?.let { (pkg, biz) -> ejectBusinessDisplay(pkg, biz) }
    }

    private fun handleNotificationRoutePosted(payload: Bundle) {
        val snapshot = NotificationRouteSnapshot.fromBundle(payload) ?: return
        val cardId = snapshot.cardId()
        if (!RearWidgetRuntimeStore.hasSceneRoutePrefix(
                snapshot.packageName,
                CHANNEL_SCENE_PREFIX
            )
        ) {
            removeOrdinaryChannelRouteNotice(cardId, "event_post:no_route_prefix")
            return
        }

        val scene = buildChannelScene(snapshot.channelId)
        val business = RearWidgetRuntimeStore.resolveBusinessForScene(snapshot.packageName, scene)
            ?: run {
                removeOrdinaryChannelRouteNotice(cardId, "event_post:route_miss")
                return
            }
        if (!RearWidgetRuntimeStore.ensureBusinessRegistered(snapshot.packageName, business)) {
            removeOrdinaryChannelRouteNotice(cardId, "event_post:business_unregistered")
            debugLog(
                "ordinary event skip unregistered pkg=${snapshot.packageName} channel=${snapshot.channelId} business=$business"
            )
            return
        }

        val existingCompositeKey = ordinaryChannelNoticeIndex[cardId]
        val existingNotice = existingCompositeKey?.let(RearWidgetRuntimeStore::getNotice)
        if (existingNotice != null && existingNotice.ticket.business != business) {
            removeOrdinaryChannelRouteNotice(cardId, "event_post:business_changed")
        }

        val noticePayload = buildOrdinaryChannelRoutePayload(snapshot, cardId, scene)
        runCatching {
            val ticket = RearWidgetRuntimeStore.postNotice(
                business = business,
                payload = noticePayload,
                options = ordinaryChannelRouteNoticeOptions,
                packageName = snapshot.packageName,
            )
            ordinaryChannelNoticeIndex[cardId] = ticket.compositeKey
            applyNoticeDisplayByCompositeKey(ticket.compositeKey)
            debugLog(
                "ordinary event inject pkg=${snapshot.packageName} id=${snapshot.notificationId} key=${snapshot.notificationKey.orEmpty()} channel=${snapshot.channelId} scene=$scene business=$business"
            )
        }.onFailure {
            debugLog(
                "ordinary event inject failed pkg=${snapshot.packageName} id=${snapshot.notificationId} channel=${snapshot.channelId} err=${it.message}"
            )
        }
    }

    private fun handleNotificationRouteRemoved(payload: Bundle) {
        val snapshot = NotificationRouteSnapshot.fromBundle(payload) ?: return
        val removeReason = payload.getInt(NotificationRouteBridgeContract.Keys.REMOVE_REASON, 1)
        val removed = removeOrdinaryChannelRouteNotice(
            snapshot.cardId(),
            "event_remove:$removeReason",
        )
        if (removed) {
            debugLog(
                "ordinary event remove pkg=${snapshot.packageName} id=${snapshot.notificationId} key=${snapshot.notificationKey.orEmpty()} channel=${snapshot.channelId} reason=$removeReason"
            )
        }
    }

    private fun buildOrdinaryChannelRoutePayload(
        snapshot: NotificationRouteSnapshot,
        cardId: String,
        scene: String,
    ): Bundle {
        return Bundle().apply {
            putString("__rear_card_id__", cardId)
            putString("title", snapshot.title.orEmpty())
            putString("text", snapshot.text.orEmpty())
            putString("bigText", snapshot.bigText.orEmpty())
            putString("subText", snapshot.subText.orEmpty())
            putString("shortCriticalText", snapshot.shortCriticalText.orEmpty())
            putString("channelId", snapshot.channelId)
            putString("notificationKey", snapshot.notificationKey.orEmpty())
            putString(
                "miui.focus.param",
                buildSyntheticChannelFocusParamJson(
                    packageName = snapshot.packageName,
                    notificationId = snapshot.notificationId,
                    notificationKey = snapshot.notificationKey,
                    scene = scene,
                    seed = ChannelRouteSeed(
                        channelId = snapshot.channelId,
                        title = snapshot.title,
                        text = snapshot.text,
                        bigText = snapshot.bigText,
                        subText = snapshot.subText,
                        shortCriticalText = snapshot.shortCriticalText,
                        notificationKey = snapshot.notificationKey,
                    ),
                ),
            )
        }
    }

    private fun removeOrdinaryChannelRouteNotice(cardId: String, reason: String): Boolean {
        val compositeKey = ordinaryChannelNoticeIndex.remove(cardId)
        if (compositeKey.isNullOrBlank()) return false
        ejectByCompositeKey(compositeKey)
        debugLog("ordinary event eject card=$cardId composite=$compositeKey reason=$reason")
        return true
    }

    private fun enforceCallerPermission() {
        val ctx = hostContext
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (ctx == null) {
            throw SecurityException("context not ready for permission check")
        }
        val granted = ctx.checkPermission(
            RearWidgetApiContract.SERVICE_PERMISSION,
            Binder.getCallingPid(),
            uid,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw SecurityException(
                "caller uid=$uid requires ${RearWidgetApiContract.SERVICE_PERMISSION}"
            )
        }
    }

    private fun enforceNotificationRouteCaller() {
        val ctx = hostContext
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (ctx == null) {
            throw SecurityException("context not ready for notification route bridge")
        }

        val packages = runCatching {
            ctx.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        if (packages.contains(NotificationRouteBridgeContract.SOURCE_HOST_PACKAGE)) {
            return
        }

        throw SecurityException(
            "caller uid=$uid is not allowed for ${NotificationRouteBridgeContract.Action.REQUEST_BINDER}"
        )
    }

    private fun normalizeTargetPackage(targetPackage: String?): String {
        return targetPackage?.trim().takeUnless { it.isNullOrBlank() }
            ?: RearWidgetRuntimeStore.defaultPackageName
    }

    private fun bootstrapFromPrefsOnInit(force: Boolean = false): Boolean {
        if (!force && startupBootstrapped.get()) return true

        val businessRaw = prefs.getString(
            ConfigKeys.REAR_WIDGET_BUSINESS_DATA,
            RearWidgetConfigCodec.EMPTY_ARRAY,
        )
        val sceneRouteRaw = prefs.getString(
            ConfigKeys.REAR_WIDGET_SCENE_ROUTE_DATA,
            RearWidgetConfigCodec.EMPTY_ARRAY,
        )
        val cardRaw = prefs.getString(
            ConfigKeys.REAR_WIDGET_CARD_DATA,
            RearWidgetConfigCodec.EMPTY_ARRAY,
        )
        val businesses = RearWidgetConfigCodec.parseBusinesses(businessRaw)
        val sceneRoutes = RearWidgetConfigCodec.parseSceneRoutes(sceneRouteRaw)
        val cards = RearWidgetConfigCodec.parseCards(cardRaw).filter { it.enabled }
        val stickyCards = cards.filter { it.sticky }
        val prefsManager = prefs.getPrefsManager()
        if (!force && businesses.isEmpty() && sceneRoutes.isEmpty() && cards.isEmpty()) {
            debugLog("bootstrap init skipped: no config yet")
            return false
        }

        RearWidgetRuntimeStore.replaceSceneRoutes(
            sceneRoutes.map { item ->
                RearWidgetSceneRouteSpec(
                    packageName = item.packageName,
                    scene = item.scene,
                    business = item.business,
                )
            }
        )

        val businessPathMap = LinkedHashMap<String, String>()
        businesses.forEach { item ->
            val deployedPath = deployBusinessTemplate(item.business, item.filePath)
                ?: run {
                    debugLog("bootstrap register_file failed business=${item.business} deploy failed")
                    return false
                }
            businessPathMap[item.business] = deployedPath
            RearWidgetRuntimeStore.registerBusinessFile(item.business, deployedPath)
        }

        val uniquePairs = LinkedHashSet<Pair<String, String>>()
        cards.forEach { uniquePairs += (it.packageName to it.business) }

        // 重放前先清掉目标业务的旧展示 保证重复 bootstrap 不会叠加出重复卡片
        uniquePairs.forEach { (pkg, biz) ->
            RearWidgetRuntimeStore.disableBusinessDisplay(pkg, biz)
        }

        uniquePairs.forEach { (pkg, biz) ->
            val ok = businessPathMap[biz]?.let { path ->
                RearWidgetRuntimeStore.registerBusiness(
                    packageName = pkg,
                    business = biz,
                    filePath = path,
                    defaultIndex = 0,
                    defaultPriority = 500,
                )
                true
            } ?: RearWidgetRuntimeStore.registerBusinessWithoutFile(
                packageName = pkg,
                business = biz,
                defaultIndex = 0,
                defaultPriority = 500,
            )
            if (!ok) {
                debugLog("bootstrap register failed pkg=$pkg biz=$biz")
                return false
            }
        }

        stickyCards.forEachIndexed { index, card ->
            val payload = Bundle().apply {
                putString("title", card.title.ifBlank { card.business })
                putString("business", card.business)
                putString("__rear_card_id__", card.id)
                card.oneConfigJson?.takeIf { it.isNotBlank() }?.let {
                    putString(REAR_WIDGET_CARD_ONE_CONFIG_JSON_KEY, it)
                }
            }
            val options = RearWidgetNoticeOptions(
                sticky = card.sticky,
                disablePopup = true,
                showTimeTip = prefsManager.getShowTimeTipForBusiness(card.business),
                index = index,
                priority = card.priority,
            )
            runCatching {
                RearWidgetRuntimeStore.postNotice(
                    business = card.business,
                    packageName = card.packageName,
                    payload = payload,
                    options = options,
                )
            }.onFailure {
                debugLog("bootstrap post failed pkg=${card.packageName} biz=${card.business} cardId=${card.id} err=${it.message}")
                return false
            }
        }

        applyRuntimeMaps(force = true)
        startupBootstrapped.set(true)
        bootstrapRetryCount.set(0)
        debugLog("bootstrap init replay businesses=${businesses.size} sceneRoutes=${sceneRoutes.size} enabledCards=${cards.size} stickyCards=${stickyCards.size} force=$force ok=true")
        return true
    }

    private fun scheduleBootstrapRetry() {
        val handler = mainHandler ?: return
        val retry = bootstrapRetryCount.incrementAndGet()
        if (retry > 5) {
            debugLog("bootstrap retry stop: max reached")
            return
        }

        val delay = 1200L * retry
        handler.postDelayed({
            if (startupBootstrapped.get()) return@postDelayed
            val ok = bootstrapFromPrefsOnInit(force = false)
            if (!ok) scheduleBootstrapRetry()
        }, delay)
        debugLog("bootstrap retry scheduled count=$retry delay=${delay}ms")
    }

    private fun injectAllActiveNotices() {
        RearWidgetRuntimeStore.listNotices().forEach { notice ->
            applyNoticeDisplayByCompositeKey(notice.ticket.compositeKey)
        }
    }

    private fun scheduleInjectAllActiveNotices() {
        val handler = mainHandler ?: return
        val epoch = managerEpoch.get()
        handler.postDelayed({
            if (epoch != managerEpoch.get()) return@postDelayed
            injectAllActiveNotices()
        }, 1200L)
        handler.postDelayed({
            if (epoch != managerEpoch.get()) return@postDelayed
            injectAllActiveNotices()
        }, 2800L)
    }

    private fun schedulePostPresetBootstrap() {
        val handler = mainHandler ?: Handler(Looper.getMainLooper())

        handler.post {
            runCatching {
                bootstrapFromPrefsOnInit(force = true)
                applyRuntimeMaps(force = true)
                patchManagerAppGates(manager)
                debugLog("restored custom widget templates after preset release")
            }.onFailure {
                debugLog("post-preset bootstrap failed err=${it.message}")
            }
        }
    }

    private fun applyNoticeDisplayByCompositeKey(compositeKey: String) {
        val notice = RearWidgetRuntimeStore.getNotice(compositeKey) ?: return
        if (tryUpdateExistingNoticeDisplay(notice)) return
        injectByCompositeKey(compositeKey)
    }

    private fun tryUpdateExistingNoticeDisplay(notice: RearWidgetActiveNotice): Boolean {
        if (notice.payload.containsKey(REAR_WIDGET_CARD_ONE_CONFIG_JSON_KEY)) {
            return false
        }

        val extras = runCatching {
            RearWidgetRuntimeStore.buildDecoratedExtras(notice.ticket)
        }.onFailure {
            debugLog("build decorated extras failed key=${notice.ticket.compositeKey} err=${it.message}")
        }.getOrNull() ?: return false

        val recordUpdated = updateManagerWidgetRecord(notice, extras)
        val liveUpdated = updateLiveNotificationWidget(notice.ticket.compositeKey, extras)
        if (liveUpdated) {
            debugLog("updated live widget key=${notice.ticket.compositeKey} business=${notice.ticket.business}")
            return true
        }
        if (recordUpdated) {
            val panelRefreshQueued =
                queueSmartAssistantPanelRefresh(notice.ticket.compositeKey, extras)
            debugLog(
                "updated widget record key=${notice.ticket.compositeKey} business=${notice.ticket.business} panelRefreshQueued=$panelRefreshQueued"
            )
            if (panelRefreshQueued) return true
        }
        return false
    }

    private fun injectByCompositeKey(compositeKey: String) {
        val notice = RearWidgetRuntimeStore.getNotice(compositeKey) ?: return
        val mgr = manager ?: return
        val handler = mainHandler ?: return

        runCatching {
            val extras = RearWidgetRuntimeStore.buildDecoratedExtras(notice.ticket)
            val runnable =
                resolveSmartAssistantPostRunnableClassName().toClass().resolve().firstConstructor {
                    parameterCount = 5
                }.create(
                    mgr,
                    notice.ticket.notificationId,
                    notice.ticket.packageName,
                    notice.ticket.compositeKey,
                    extras,
                ) as? Runnable ?: return
            handler.post(runnable)
            debugLog("injected ticket key=${notice.ticket.compositeKey} business=${notice.ticket.business}")
        }.onFailure {
            debugLog("inject failed key=$compositeKey err=${it.message}")
        }
    }

    private fun ejectByTicket(ticket: RearWidgetNoticeTicket) {
        val mgr = manager ?: return
        val handler = mainHandler ?: return
        liveNotificationWidgets.remove(ticket.compositeKey)
        runCatching {
            handler.post {
                runCatching {
                    invokeSmartAssistantManagerRemoveNotification(
                        target = mgr,
                        notificationId = ticket.notificationId,
                        packageName = ticket.packageName,
                        removeReason = 1,
                    )
                    debugLog("ejected ticket key=${ticket.compositeKey}")
                }.onFailure {
                    debugLog("eject failed key=${ticket.compositeKey} err=${it.message}")
                }
            }
        }.onFailure {
            debugLog("eject schedule failed key=${ticket.compositeKey} err=${it.message}")
        }
    }

    private fun ejectByCompositeKey(compositeKey: String) {
        liveNotificationWidgets.remove(compositeKey)
        val notice = RearWidgetRuntimeStore.getNotice(compositeKey)
        val ticket = notice?.ticket
        if (ticket != null) {
            runCatching {
                RearWidgetRuntimeStore.removeNotice(ticket)
            }
            ejectByTicket(ticket)
            return
        }

        val parsed = parseCompositeKey(compositeKey) ?: return
        ejectNativeCompositeKey(
            compositeKey = compositeKey,
            packageName = parsed.first,
            removeReason = 1,
        )
    }

    private fun ejectNativeCompositeKey(
        compositeKey: String,
        packageName: String,
        removeReason: Int,
    ) {
        val mgr = manager ?: return
        val handler = mainHandler ?: return
        runCatching {
            handler.post {
                runCatching {
                    val removed = invokeSmartAssistantManagerRemoveComposite(
                        target = mgr,
                        compositeKey = compositeKey,
                        packageName = packageName,
                        removeReason = removeReason,
                    )
                    if (removed) {
                        debugLog("ejected native composite key=$compositeKey")
                    } else {
                        debugLog("native composite not found key=$compositeKey")
                    }
                }.onFailure {
                    debugLog("native composite eject failed key=$compositeKey err=${it.message}")
                }
            }
        }.onFailure {
            debugLog("native composite eject schedule failed key=$compositeKey err=${it.message}")
        }
    }

    private fun parseCompositeKey(compositeKey: String): Pair<String, String?>? {
        val parts = compositeKey.split(':')
        return when (parts.size) {
            2 -> parts[0] to null
            3 -> parts[0] to parts[1]
            else -> null
        }
    }

    private fun ejectBusinessDisplay(packageName: String, business: String) {
        val mgr = manager ?: return
        val handler = mainHandler ?: return
        val prefix = "$packageName:$business:"
        liveNotificationWidgets.keys.removeIf { it.startsWith(prefix) }
        runCatching {
            handler.post {
                runCatching {
                    invokeSmartAssistantManagerRemoveBusiness(mgr, packageName, business)
                    debugLog("ejected business display pkg=$packageName biz=$business")
                }.onFailure {
                    debugLog("eject business display failed pkg=$packageName biz=$business err=${it.message}")
                }
            }
        }.onFailure {
            debugLog("eject schedule failed pkg=$packageName biz=$business err=${it.message}")
        }
    }

    private fun applyRuntimeMaps(force: Boolean) {
        if (!force && !RearWidgetRuntimeStore.mapsDirty.get()) return
        if (!appliedOnce.compareAndSet(
                false,
                true
            ) && !force && !RearWidgetRuntimeStore.mapsDirty.get()
        ) return

        val pkgBiz = RearWidgetRuntimeStore.allPkgBusinesses()
        val pkgPrimary = RearWidgetRuntimeStore.primaryBusinessByPkg()
        val bizPath = RearWidgetRuntimeStore.allBusinessPath()
        val configClassName = resolveSmartAssistantConfigClassName()
        val utilsClassName = resolveSmartAssistantUtilsClassName()

        replaceStaticMap(configClassName, "a") { map ->
            pkgPrimary.forEach { (pkg, biz) -> if (biz.isNotBlank()) map[pkg] = biz }
        }
        replaceStaticMap(configClassName, "c") { map ->
            pkgBiz.forEach { (pkg, set) -> map[pkg] = HashSet(set) }
        }
        replaceStaticMap(configClassName, "d") { map ->
            bizPath.forEach { (biz, path) -> map[biz] = path }
        }
        replaceStaticMap(utilsClassName, "d") { map ->
            pkgBiz.forEach { (pkg, businesses) ->
                val businessSet = businesses.toMutableSet()
                if (businessSet.isNotEmpty()) {
                    map[pkg] = businessSet
                }
            }
        }
        replaceStaticList(utilsClassName, "b") { list ->
            bizPath.keys.forEach { biz -> if (!list.contains(biz)) list.add(biz) }
        }

        RearWidgetRuntimeStore.mapsDirty.set(false)
    }

    private fun patchManagerAppGates(target: Any?) {
        val instance = target ?: return
        val pkgBiz = RearWidgetRuntimeStore.allPkgBusinesses()
        if (pkgBiz.isEmpty()) return

        runCatching {
            val rSet = readManagerAllowedPackageSet(instance)
            val qMap = readManagerAllowedPackageMap(instance)

            pkgBiz.forEach { (pkg, businesses) ->
                rSet.add(pkg)
                qMap[pkg] = true
                businesses.forEach { biz ->
                    qMap["${pkg}_$biz"] = true
                }
            }
            debugLog("patched manager app gates packages=${pkgBiz.keys}")
        }
    }

    private fun allowSelfDescribedNotificationPackage(runnable: Any) {
        if (!prefs.getBoolean(ConfigKeys.HOOK_ALLOW_REAR_FOCUS_NOTICES, false)) return
        val snapshot = synchronized(postRunnableSnapshots) {
            postRunnableSnapshots[runnable]
        } ?: return
        val owner = snapshot.owner ?: return
        if (owner.javaClass.name != resolveSmartAssistantManagerClassName()) return

        val packageName = snapshot.packageName.trim()
        if (packageName.isBlank()) return

        val extras = snapshot.extras
        if (extras.isEmpty) return

        val business = parseBusinessFromParams(packageName, extras) ?: return

        if (INTERNAL_BUSINESSES.contains(business)) return

        logNoWidgetPathIfNeeded(packageName, business, extras)

        runCatching {
            val rSet = readManagerAllowedPackageSet(owner)
            val qMap = readManagerAllowedPackageMap(owner)

            rSet.add(packageName)
            qMap[packageName] = true
            qMap["${packageName}_$business"] = true
            debugLog("dynamic allow pkg=$packageName biz=$business")
        }.onFailure {
            debugLog("dynamic allow failed pkg=$packageName biz=$business err=${it.message}")
        }
    }

    private fun rememberOriginalNotificationRoute(snapshot: PostRunnableSnapshot) {
        val packageName = snapshot.packageName.trim()
        if (packageName.isBlank()) return
        val extras = snapshot.extras
        val notificationId = snapshot.notificationId
        if (notificationId == Int.MIN_VALUE) return

        val business = parseBusinessFromParams(packageName, extras)
            ?: extras.getString("business")?.trim()?.ifBlank { null }
            ?: return
        val notificationKey = snapshot.notificationKey?.trim().orEmpty()
        val stale = RearWidgetRuntimeStore.rememberRoutedNotification(
            packageName = packageName,
            notificationId = notificationId,
            notificationKey = notificationKey,
            business = business,
        )
        stale.forEach { staleKey ->
            if (staleKey != extras.getString("composite_key")) {
                ejectByCompositeKey(staleKey)
            }
        }
    }

    private fun handleOriginalNotificationRemoved(
        packageName: String,
        notificationId: Int,
        notificationKey: String?,
        removeReason: Int,
    ) {
        val composites = RearWidgetRuntimeStore.removeRoutedNotification(
            packageName = packageName,
            notificationId = notificationId,
            notificationKey = notificationKey,
        )
        if (composites.isEmpty()) return

        composites.forEach { compositeKey ->
            ejectNativeCompositeKey(
                compositeKey = compositeKey,
                packageName = packageName,
                removeReason = removeReason.takeIf { it != 0 } ?: 1,
            )
            debugLog(
                "synced original remove pkg=$packageName id=$notificationId reason=$removeReason composite=$compositeKey"
            )
        }
    }

    private fun applySceneRouteBusinessToExtras(
        packageName: String,
        notificationId: Int,
        notificationKey: String?,
        extras: Bundle,
    ): SceneRouteInjection? {
        val focusParam = extras.getString("miui.focus.param")?.trim().orEmpty()
        if (focusParam.isBlank()) return null
        val focusJson = runCatching { JSONObject(focusParam) }.getOrNull() ?: return null

        if (focusJson.has("param_v2")) {
            val paramV2 = focusJson.optJSONObject("param_v2") ?: return null
            if (paramV2.optString("business").trim().isNotBlank()) return null
            val scene = extractSceneCandidate(
                paramV2,
                paramV2.optJSONObject("baseInfo"),
                paramV2.optJSONObject("hintInfo"),
            ) ?: return null
            val business = RearWidgetRuntimeStore.resolveBusinessForScene(packageName, scene)
                ?: return null
            paramV2.put("business", business)
            extras.putString("miui.focus.param", focusJson.toString())
            return SceneRouteInjection(
                scene = scene,
                business = business,
                staleCompositeKeys = staleCompositeKeys(
                    packageName = packageName,
                    notificationId = notificationId,
                    notificationKey = notificationKey,
                    business = business,
                ),
            )
        }

        if (focusJson.optString("business").trim().isNotBlank()) return null
        val scene = extractSceneCandidate(focusJson) ?: return null
        val business = RearWidgetRuntimeStore.resolveBusinessForScene(packageName, scene)
            ?: return null
        focusJson.put("business", business)
        extras.putString("miui.focus.param", focusJson.toString())
        return SceneRouteInjection(
            scene = scene,
            business = business,
            staleCompositeKeys = staleCompositeKeys(
                packageName = packageName,
                notificationId = notificationId,
                notificationKey = notificationKey,
                business = business,
            ),
        )
    }

    private fun normalizeInitialManagerWidgetPriority(target: Any?, widget: Any?) {
        val managerTarget = target ?: return
        val insertedWidget = widget ?: return
        val listFieldName = resolveSmartAssistantManagerWidgetListFieldName()
        val currentIndexFieldName = resolveSmartAssistantManagerCurrentIndexFieldName()
        val list = runCatching {
            @Suppress("UNCHECKED_CAST")
            managerTarget.asResolver().firstField { name = listFieldName }.get() as ArrayList<Any>
        }.getOrNull() ?: return
        val currentIndex = runCatching {
            managerTarget.asResolver().firstField { name = currentIndexFieldName }.get<Int>()
        }.getOrNull() ?: return
        if (currentIndex != -1) return
        if (!isPriorityManagedWidget(insertedWidget)) return

        synchronized(list) {
            if (!list.contains(insertedWidget)) return
            val original = ArrayList(list)
            val normalWidgets = original.filter(::isPriorityManagedWidget)
            if (normalWidgets.size <= 1) return

            val sortedNormalWidgets = normalWidgets.sortedWith(
                compareByDescending<Any> { managerWidgetPriority(it) }
                    .thenByDescending { managerWidgetCreatedAt(it) }
            )
            if (sortedNormalWidgets == normalWidgets) return

            val iterator = sortedNormalWidgets.iterator()
            for (index in original.indices) {
                if (!isPriorityManagedWidget(original[index])) continue
                if (!iterator.hasNext()) break
                list[index] = iterator.next()
            }
            debugLog(
                "manager init priority reorder size=${list.size}"
            )
        }
    }

    private fun normalizeRestoredManagerWidgetPriority(target: Any?) {
        val managerTarget = target ?: return
        val listFieldName = resolveSmartAssistantManagerWidgetListFieldName()
        val beforeInactiveFieldName = resolveSmartAssistantManagerBeforeInactiveIndexFieldName()
        val list = runCatching {
            @Suppress("UNCHECKED_CAST")
            managerTarget.asResolver().firstField { name = listFieldName }.get() as ArrayList<Any>
        }.getOrNull() ?: return

        synchronized(list) {
            if (list.size <= 1) return

            val original = ArrayList(list)
            val normalWidgets = original.filter(::isPriorityManagedWidget)
            if (normalWidgets.isEmpty()) return
            val sortedNormalWidgets = normalWidgets.sortedWith(
                compareByDescending<Any> { managerWidgetPriority(it) }
                    .thenByDescending { managerWidgetCreatedAt(it) }
            )
            val sorted = ArrayList(original)
            val iterator = sortedNormalWidgets.iterator()
            for (index in sorted.indices) {
                if (!isPriorityManagedWidget(sorted[index])) continue
                if (!iterator.hasNext()) break
                sorted[index] = iterator.next()
            }
            val orderChanged = sorted != original
            if (orderChanged) {
                list.clear()
                list.addAll(sorted)
            }

            val currentBeforeInactive = runCatching {
                managerTarget.asResolver().firstField { name = beforeInactiveFieldName }.get<Int>()
            }.getOrNull() ?: -1
            val preferredIndex = list.indexOfFirst(::isPriorityManagedWidget)
                .takeIf { it >= 0 }
                ?: 0
            val indexChanged = currentBeforeInactive != preferredIndex
            if (!orderChanged && !indexChanged) return

            runCatching {
                managerTarget.asResolver().firstField { name = beforeInactiveFieldName }
                    .set(preferredIndex)
            }

            debugLog(
                "manager restore priority normalize size=${list.size} orderChanged=$orderChanged beforeInactive=$currentBeforeInactive->$preferredIndex"
            )
        }
    }

    private fun isPriorityManagedWidget(widget: Any): Boolean {
        val bundle = managerWidgetBundle(widget) ?: return false
        return !bundle.getBoolean("force_popup", false) &&
                !bundle.getBoolean("enableFloat", false) &&
                !bundle.getBoolean("is_pin", false)
    }

    private fun managerWidgetBundle(widget: Any): Bundle? {
        val extrasFieldName = resolveSmartAssistantWidgetRecordExtrasFieldName()
        return runCatching {
            widget.asResolver().firstField { name = extrasFieldName }.get<Bundle?>()
        }.getOrNull()
    }

    private fun setManagerWidgetBundle(widget: Any, extras: Bundle) {
        val extrasFieldName = resolveSmartAssistantWidgetRecordExtrasFieldName()
        widget.asResolver().firstField { name = extrasFieldName }.set(Bundle(extras))
    }

    private fun setManagerWidgetPriority(widget: Any, priority: Int) {
        val priorityFieldName = resolveSmartAssistantWidgetRecordPriorityFieldName()
        widget.asResolver().firstField { name = priorityFieldName }.set(priority)
    }

    private fun updateManagerWidgetRecord(notice: RearWidgetActiveNotice, extras: Bundle): Boolean {
        val managerTarget = manager ?: return false
        val listFieldName = resolveSmartAssistantManagerWidgetListFieldName()
        val list = runCatching {
            @Suppress("UNCHECKED_CAST")
            managerTarget.asResolver().firstField { name = listFieldName }.get() as ArrayList<Any>
        }.getOrNull() ?: return false

        synchronized(list) {
            val record = list.firstOrNull { widget ->
                managerWidgetBundle(widget)?.getString("composite_key") == notice.ticket.compositeKey
            } ?: return false

            runCatching {
                setManagerWidgetBundle(record, extras)
                val priority = notice.options.priority ?: 500
                if (managerWidgetPriority(record) != priority) {
                    setManagerWidgetPriority(record, priority)
                }
            }.onFailure {
                debugLog("update widget record failed key=${notice.ticket.compositeKey} err=${it.message}")
                return false
            }
        }
        return true
    }

    private fun rememberSmartAssistantPanel(panel: Any?) {
        val target = panel ?: return
        smartAssistantPanels[System.identityHashCode(target)] = WeakReference(target)
    }

    private fun forEachRememberedPanel(action: (Any) -> Unit) {
        smartAssistantPanels.entries.toList().forEach { (id, ref) ->
            val panel = ref.get()
            if (panel == null) {
                smartAssistantPanels.remove(id)
                return@forEach
            }
            action(panel)
        }
    }

    private fun queueSmartAssistantPanelRefresh(compositeKey: String, extras: Bundle): Boolean {
        var queued = false
        forEachRememberedPanel { panel ->
            val view = panel as? View
            if (view == null) {
                return@forEachRememberedPanel
            }
            queued = true
            view.post {
                runCatching {
                    refreshSmartAssistantPanelWidget(panel, compositeKey, extras)
                }.onFailure {
                    debugLog("panel refresh failed key=$compositeKey err=${it.message}")
                }
            }
        }
        return queued
    }

    private fun findLiveNotificationWidget(compositeKey: String): Any? {
        liveNotificationWidgets[compositeKey]?.get()?.let { cached ->
            val currentExtras = extractFieldFromHierarchy(
                cached,
                resolveNotificationWidgetExtrasFieldName(),
            ) as? Bundle
            if (currentExtras?.getString("composite_key") == compositeKey) {
                return cached
            }
            liveNotificationWidgets.remove(compositeKey)
        }

        var found: Any? = null
        forEachRememberedPanel { panel ->
            if (found != null) return@forEachRememberedPanel
            val holderList = panel.asResolver().firstField {
                name = resolveSmartAssistantPanelHolderListFieldName()
            }.get<Any>() as? Iterable<*>
            if (holderList == null) {
                return@forEachRememberedPanel
            }
            holderList.forEach { holder ->
                if (found != null) return@forEach
                val widget = resolveSmartAssistantHolderWidget(holder) ?: return@forEach
                val widgetExtras = extractFieldFromHierarchy(
                    widget,
                    resolveNotificationWidgetExtrasFieldName(),
                ) as? Bundle ?: return@forEach
                if (widgetExtras.getString("composite_key") != compositeKey) return@forEach
                found = widget
            }
        }
        found?.let { liveNotificationWidgets[compositeKey] = WeakReference(it) }
        return found
    }

    private fun refreshSmartAssistantPanelWidget(panel: Any, compositeKey: String, extras: Bundle) {
        val holderList = panel.asResolver().firstField {
            name = resolveSmartAssistantPanelHolderListFieldName()
        }.get<Any>() as? Iterable<*>
            ?: return
        var matched = false
        holderList.forEach { holder ->
            val widget = resolveSmartAssistantHolderWidget(holder) ?: return@forEach
            val widgetExtras = extractFieldFromHierarchy(
                widget,
                resolveNotificationWidgetExtrasFieldName(),
            ) as? Bundle ?: return@forEach
            if (widgetExtras.getString("composite_key") != compositeKey) return@forEach

            matched = true
            setFieldInHierarchy(widget, resolveNotificationWidgetExtrasFieldName(), Bundle(extras))
            invokeNotificationWidgetExtraChanged(widget, extras)
            markWidgetExtraChanged(widget)
        }
        if (!matched) return
        val refreshPoint = resolveSmartAssistantPanelRefreshMethod()
        panel.asResolver().firstMethod {
            name = refreshPoint.methodName
            parameterCount = 3
        }.invoke(1, 3, null)
        debugLog("queued panel refresh key=$compositeKey")
    }

    private fun markWidgetExtraChanged(widget: Any) {
        runCatching {
            val fieldName = resolveNotificationWidgetChangedFlagFieldName()
            val currentFlags = (extractFieldFromHierarchy(widget, fieldName) as? Int) ?: 0
            setFieldInHierarchy(widget, fieldName, currentFlags or 2)
        }.onFailure {
            debugLog("mark extra changed skipped widget=${widget.javaClass.name} err=${it.message}")
        }
    }

    private fun rememberLiveNotificationWidget(widget: Any?) {
        val owner = widget ?: return
        val extras = extractFieldFromHierarchy(
            owner,
            resolveNotificationWidgetExtrasFieldName(),
        ) as? Bundle ?: return
        val compositeKey = extras.getString("composite_key")?.trim().orEmpty()
        if (compositeKey.isBlank()) return
        liveNotificationWidgets[compositeKey] = WeakReference(owner)
    }

    private fun updateLiveNotificationWidget(compositeKey: String, extras: Bundle): Boolean {
        val widget = findLiveNotificationWidget(compositeKey)
        if (widget == null) {
            liveNotificationWidgets.remove(compositeKey)
            return false
        }

        val currentExtras = extractFieldFromHierarchy(
            widget,
            resolveNotificationWidgetExtrasFieldName(),
        ) as? Bundle
        val currentCompositeKey = currentExtras?.getString("composite_key")?.trim().orEmpty()
        if (currentCompositeKey != compositeKey) {
            liveNotificationWidgets.remove(compositeKey)
            return false
        }

        return runCatching {
            setFieldInHierarchy(widget, resolveNotificationWidgetExtrasFieldName(), Bundle(extras))
            invokeNotificationWidgetExtraChanged(widget, extras)
            markWidgetExtraChanged(widget)
            true
        }.onFailure {
            liveNotificationWidgets.remove(compositeKey)
            debugLog("update live widget failed key=$compositeKey err=${it.message}")
        }.getOrDefault(false)
    }

    private fun invokeNotificationWidgetExtraChanged(widget: Any, extras: Bundle) {
        val methodName = resolveNotificationWidgetExtraChangedMethodName()
        var current: Class<*>? = widget.javaClass
        while (current != null && current != Any::class.java) {
            val method = current.declaredMethods.firstOrNull {
                it.name == methodName &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0] == Bundle::class.java &&
                        it.returnType == Void.TYPE
            }
            if (method != null) {
                method.isAccessible = true
                method.invoke(widget, Bundle(extras))
                debugLog(
                    "invoked extra changed method=${method.name} widget=${widget.javaClass.name} key=${
                        extras.getString(
                            "composite_key"
                        ).orEmpty()
                    }"
                )
                return
            }
            current = current.superclass
        }
        error("notification widget extra changed method not found: ${widget.javaClass.name}")
    }

    private fun resolveSmartAssistantHolderWidget(holder: Any?): Any? {
        val target = holder ?: return null
        val baseClass = resolveNotificationWidgetBaseClassName().toClass()
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val value = field.get(target) ?: return@runCatching
                    if (baseClass.isAssignableFrom(value.javaClass)) {
                        return value
                    }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun managerWidgetPriority(widget: Any): Int {
        val priorityFieldName = resolveSmartAssistantWidgetRecordPriorityFieldName()
        return runCatching {
            widget.asResolver().firstField { name = priorityFieldName }.get<Int>()
        }.getOrNull() ?: Int.MIN_VALUE
    }

    private fun managerWidgetCreatedAt(widget: Any): Long {
        return managerWidgetBundle(widget)?.getLong("timestamp", Long.MIN_VALUE) ?: Long.MIN_VALUE
    }

    private fun clearAllOrdinaryChannelRouteNotices(reason: String) {
        if (ordinaryChannelNoticeIndex.isEmpty()) return
        ordinaryChannelNoticeIndex.entries.toList().forEach { (_, compositeKey) ->
            ejectByCompositeKey(compositeKey)
            debugLog("ordinary event clear composite=$compositeKey reason=$reason")
        }
        ordinaryChannelNoticeIndex.clear()
    }

    private fun extractSceneCandidate(vararg sources: JSONObject?): String? {
        sources.forEach { source ->
            val scene = source?.optString(RearWidgetApiContract.BundleKeys.SCENE)?.trim().orEmpty()
            if (scene.isNotBlank()) return scene
        }
        return null
    }

    private fun buildChannelScene(channelId: String): String {
        return CHANNEL_SCENE_PREFIX + channelId.trim()
    }

    private fun buildSyntheticChannelFocusParamJson(
        packageName: String,
        notificationId: Int,
        notificationKey: String?,
        scene: String,
        seed: ChannelRouteSeed,
    ): String {
        val baseInfo = JSONObject()
            .put(RearWidgetApiContract.BundleKeys.SCENE, scene)
            .put("channelId", seed.channelId)
            .apply {
                putIfNotBlank("title", seed.title)
                putIfNotBlank("text", seed.text)
                putIfNotBlank("bigText", seed.bigText)
                putIfNotBlank("subText", seed.subText)
                putIfNotBlank("shortCriticalText", seed.shortCriticalText)
            }
        val hintInfo = JSONObject()
            .put(RearWidgetApiContract.BundleKeys.SCENE, scene)
            .apply {
                putIfNotBlank("title", seed.title)
                putIfNotBlank("text", seed.text ?: seed.bigText)
                putIfNotBlank("subText", seed.subText)
                putIfNotBlank("shortCriticalText", seed.shortCriticalText)
            }
        val reareyeInfo = JSONObject()
            .put("source", "channel_route")
            .put("packageName", packageName)
            .put("notificationId", notificationId)
            .put("channelId", seed.channelId)
            .apply {
                putIfNotBlank("notificationKey", notificationKey)
                putIfNotBlank("title", seed.title)
                putIfNotBlank("text", seed.text)
                putIfNotBlank("bigText", seed.bigText)
                putIfNotBlank("subText", seed.subText)
                putIfNotBlank("shortCriticalText", seed.shortCriticalText)
            }
        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("updatable", true)
            .put(RearWidgetApiContract.BundleKeys.SCENE, scene)
            .put("channelId", seed.channelId)
            .put("disable_popup", false)
            .put("show_time_tip", true)
            .put("swipe_out_screen_listener", false)
            .put("enableFloat", false)
            .put("baseInfo", baseInfo)
            .put("hintInfo", hintInfo)
            .put("reareyeInfo", reareyeInfo)
        return JSONObject().put("param_v2", paramV2).toString()
    }

    private fun JSONObject.putIfNotBlank(key: String, value: String?): JSONObject {
        if (!value.isNullOrBlank()) put(key, value)
        return this
    }

    private fun staleCompositeKeys(
        packageName: String,
        notificationId: Int,
        notificationKey: String?,
        business: String,
    ): Set<String> {
        if (notificationId == Int.MIN_VALUE) return emptySet()
        return RearWidgetRuntimeStore.rememberRoutedNotification(
            packageName = packageName,
            notificationId = notificationId,
            notificationKey = notificationKey,
            business = business,
        )
    }

    private fun parseBusinessFromParams(packageName: String, extras: Bundle): String? {
        val parser = runCatching {
            invokeSmartAssistantParseParams(extras)
        }.getOrNull() ?: return null

        val parsed = runCatching {
            invokeSmartAssistantParseWidget(packageName, parser)
        }.getOrNull() ?: return null

        return runCatching {
            parsed.asResolver().firstField {
                name = resolveSmartAssistantWidgetSpecBusinessFieldName()
            }.get<String>()
        }.getOrNull()?.trim()?.ifBlank { null }
    }

    private fun logNoWidgetPathIfNeeded(packageName: String, business: String, extras: Bundle) {
        val hasRemoteView =
            extras.containsKey("miui.rear.rv") || extras.containsKey("miui.rear.rvAOD")
        if (hasRemoteView) return

        val builtInSupported = runCatching {
            invokeSmartAssistantBuiltinSupport(packageName, business)
        }.getOrDefault(false)
        if (builtInSupported) return

        val widgetPath = runCatching {
            invokeSmartAssistantResolvePath(packageName, business)
        }.getOrNull()

        if (widgetPath.isNullOrBlank()) {
            YLog.debug("[$TAG] No widget path pkg=$packageName business=$business")
        }
    }

    @Suppress("SameParameterValue")
    private fun createU0b(business: String, index: Int, priority: Int): Any {
        return resolveSmartAssistantWidgetSpecClassName().toClass().resolve().firstConstructor {
            parameterCount = 3
        }.create(
            business,
            index,
            priority,
        )
    }

    private fun replaceStaticMap(
        className: String,
        fieldName: String,
        mutate: (MutableMap<Any, Any?>) -> Unit,
    ) {
        val field = className.toClass().resolve().firstField { name = fieldName }
        val raw = field.get<Any>() ?: error("$className.$fieldName is null")
        val current = unwrapMutableMap(raw)
        val out = HashMap<Any, Any?>(current.size + 8)
        current.forEach { (k, v) -> if (k != null) out[k] = v }
        mutate(out)
        field.set(out)
    }

    @Suppress("SameParameterValue")
    private fun replaceStaticList(
        className: String,
        fieldName: String,
        mutate: (MutableList<Any>) -> Unit,
    ) {
        val field = className.toClass().resolve().firstField { name = fieldName }
        val raw = field.get<Any>() ?: error("$className.$fieldName is null")
        val current = unwrapMutableList(raw)
        val out = ArrayList<Any>(current.size + 16)
        current.forEach { if (it != null) out.add(it) }
        mutate(out)
        field.set(out)
    }

    private fun unwrapMutableMap(any: Any): MutableMap<*, *> {
        if (any is MutableMap<*, *>) {
            return any
        }
        error("Not a map: ${any.javaClass.name}")
    }

    private fun unwrapMutableList(any: Any): MutableList<*> {
        if (any is MutableList<*>) {
            return any
        }
        error("Not a list: ${any.javaClass.name}")
    }

    private fun debugLog(message: String) {
        if (prefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            YLog.debug("[$TAG] $message")
        }
    }

    private fun deployBusinessTemplate(business: String, sourcePath: String): String? {
        val source = sourcePath.trim()
        val target = resolveTemplatePath(business)
        val targetFile = File(target)

        val blobMeta = prefs.getString(RearWidgetConfigCodec.businessBlobMetaKey(business), "")
        if (blobMeta.isNotBlank() && deployedBlobMetaCache[business] == blobMeta && targetFile.exists()) {
            return target
        }

        if (blobMeta.isNotBlank()) {
            val encoded = prefs.getString(RearWidgetConfigCodec.businessBlobKey(business), "")
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                val ok = runCatching {
                    targetFile.parentFile?.mkdirs()
                    val tmp =
                        File(targetFile.parentFile, "${targetFile.name}.tmp.${Process.myPid()}")
                    tmp.outputStream().use { it.write(bytes) }
                    if (targetFile.exists()) targetFile.delete()
                    val moved = tmp.renameTo(targetFile)
                    if (!moved) {
                        tmp.copyTo(targetFile, overwrite = true)
                        tmp.delete()
                    }
                    ensureReadable(targetFile)
                    true
                }.getOrDefault(false)

                if (ok) {
                    deployedBlobMetaCache[business] = blobMeta
                    debugLog("deployed business template from prefs business=$business -> $target size=${bytes.size}")
                    return target
                }
            }
            debugLog("deploy blob decode/write failed business=$business meta=$blobMeta")
        } else {
            debugLog("deploy blob missing business=$business")
        }

        val sourceFile = File(source)
        if (sourceFile.exists() && sourceFile.isFile) {
            val ok = runCatching {
                targetFile.parentFile?.mkdirs()
                sourceFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                ensureReadable(targetFile)
                true
            }.getOrDefault(false)
            if (ok) {
                debugLog("deployed business template from file business=$business source=$source -> $target")
                return target
            }
        }

        debugLog("deploy failed business=$business source=$source blobMeta=$blobMeta")
        return null
    }

    private fun resolveTemplatePath(business: String): String {
        val userId = Process.myUid() / 100000
        val base = TEMPLATE_BASE.format(userId.toString())
        val safeBiz = business.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return "$base/re_$safeBiz"
    }

    private fun resolveCardConfigPath(cardKey: String): String {
        val userId = Process.myUid() / 100000
        val base = CARD_CONFIG_BASE.format(userId.toString())
        val safeKey = cardKey.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return "$base/$safeKey.json"
    }

    private fun resolveCardAssetDir(cardKey: String): File {
        val userId = Process.myUid() / 100000
        val base = CARD_ASSET_BASE.format(userId.toString())
        val safeKey = cardKey.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(base, safeKey)
    }

    private fun resolveBuiltinTemplatePath(business: String): String? {
        val userId = Process.myUid() / 100000
        val normalizedBusiness = normalizeBusinessName(business)
        val relative = BUILTIN_TEMPLATE_RELATIVE_PATHS[normalizedBusiness] ?: return null
        return "/data/system/theme_magic/users/$userId/subscreencenter/smart_assistant/$relative"
    }

    private fun normalizeBusinessName(raw: String): String {
        return when (raw.trim()) {
            "taxi", "car_hailing", "carHailing" -> "carHailing"
            "food_Delivery", "food_delivery", "foodDelivery" -> "foodDelivery"
            "miHomeCamera", "mihomeCamera" -> "mihomeCamera"
            "xiaomi_ev", "xiaomiev" -> "xiaomiev"
            else -> raw.trim()
        }
    }

    private fun removeDeployedBusinessTemplate(business: String) {
        runCatching {
            deployedBlobMetaCache.remove(business)
            val target = resolveTemplatePath(business)
            val file = File(target)
            if (file.exists() && file.delete()) {
                debugLog("removed stale deployed template business=$business path=$target")
            }
        }.onFailure {
            debugLog("remove stale deployed template failed business=$business err=${it.message}")
        }
    }

    private fun deployCardOneConfig(cardKey: String, json: String): String? {
        val normalizedJson = json.trim()
        if (normalizedJson.isBlank()) return null

        val target = resolveCardConfigPath(cardKey)
        val targetFile = File(target)
        val meta = "${normalizedJson.length}:${normalizedJson.hashCode()}"
        if (deployedCardConfigMetaCache[cardKey] == meta && targetFile.exists()) {
            return target
        }

        val ok = runCatching {
            targetFile.parentFile?.mkdirs()
            val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp.${Process.myPid()}")
            tmp.writeText(normalizedJson)
            if (targetFile.exists()) targetFile.delete()
            val moved = tmp.renameTo(targetFile)
            if (!moved) {
                tmp.copyTo(targetFile, overwrite = true)
                tmp.delete()
            }
            ensureReadable(targetFile)
            true
        }.getOrDefault(false)

        if (!ok) {
            debugLog("deploy card one config failed cardKey=$cardKey")
            return null
        }

        deployedCardConfigMetaCache[cardKey] = meta
        debugLog("deployed card one config cardKey=$cardKey -> $target")
        return target
    }

    private fun removeCardOneConfig(cardKey: String) {
        runCatching {
            deployedCardConfigMetaCache.remove(cardKey)
            val file = File(resolveCardConfigPath(cardKey))
            if (file.exists()) file.delete()
        }.onFailure {
            debugLog("remove card one config failed cardKey=$cardKey err=${it.message}")
        }
    }

    private fun applyCardOneConfig(owner: Any?, mamlView: Any?, hookPoint: String) {
        if (owner == null || mamlView == null) {
            debugLog("applyCardOneConfig skip hook=$hookPoint owner=${owner != null} view=${mamlView != null}")
            return
        }
        val extras = extractCardExtras(owner)
        if (extras == null) {
            debugLog("applyCardOneConfig missing extras hook=$hookPoint owner=${owner.javaClass.name}")
            return
        }

        val json = extras.getString(REAR_WIDGET_CARD_ONE_CONFIG_JSON_KEY)?.trim().orEmpty()
        val cardId = extras.getString("__rear_card_id__")?.trim().orEmpty()
        val packageName = extras.getString("package_name")?.trim().orEmpty()
        val business = extras.getString("business")?.trim().orEmpty()
        val cardKey = listOf(packageName, business, cardId)
            .filter { it.isNotBlank() }
            .joinToString("_")
            .ifBlank { "${packageName}_${business}".trim('_') }
            .ifBlank {
                debugLog("applyCardOneConfig missing key hook=$hookPoint pkg=$packageName biz=$business cardId=$cardId")
                return
            }

        debugLog(
            "applyCardOneConfig hook=$hookPoint cardKey=$cardKey hasJson=${json.isNotBlank()} jsonLength=${json.length} owner=${owner.javaClass.name} view=${mamlView.javaClass.name}"
        )
        val templatePath = extractNotificationWidgetTemplatePath(owner)

        if (json.isBlank()) {
            removeCardOneConfig(cardKey)
            return
        }

        val configPath = deployCardOneConfig(cardKey, json) ?: return
        applyCardOneConfigOnce(
            mamlView,
            json,
            configPath,
            cardKey,
            "$hookPoint/immediate",
            templatePath
        )
        (mamlView as? View)?.postDelayed({
            applyCardOneConfigOnce(
                mamlView,
                json,
                configPath,
                cardKey,
                "$hookPoint/post120",
                templatePath
            )
        }, 120L)
    }

    private fun applyCardOneConfigOnce(
        mamlView: Any,
        json: String,
        configPath: String,
        cardKey: String,
        stage: String,
        templatePath: String?,
    ) {
        runCatching {
            "com.miui.maml.widget.edit.WidgetEditSave".toClass().resolve().firstMethod {
                name = "restoreFromConfigPath"
                parameterCount = 2
            }.invoke(mamlView, configPath)
            applyHostOneConfig(mamlView, json)
            applyCompatOneConfig(mamlView, json)
            applyManifestDerivedVars(mamlView, templatePath)
            requestMamlRefresh(mamlView)
            debugLog("applied card one config cardKey=$cardKey stage=$stage path=$configPath jsonLength=${json.length}")
        }.onFailure {
            debugLog("apply card one config failed cardKey=$cardKey stage=$stage err=${it.message}")
        }
    }

    private fun extractCardExtras(owner: Any): Bundle? {
        extractFieldFromHierarchy(owner, resolveNotificationWidgetExtrasFieldName())?.let { value ->
            val bundle = value as? Bundle
            if (bundle != null && bundle.hasCardConfigMarkers()) return bundle
        }

        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val value = field.get(owner) as? Bundle ?: return@runCatching
                    if (value.hasCardConfigMarkers()) {
                        return value
                    }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun extractNotificationWidgetTemplatePath(owner: Any): String? {
        return extractFieldFromHierarchy(
            owner,
            resolveNotificationWidgetTemplatePathFieldName()
        ) as? String
    }

    private fun extractFieldFromHierarchy(owner: Any, fieldName: String): Any? {
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun setFieldInHierarchy(owner: Any, fieldName: String, value: Any?) {
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.set(owner, value)
                }.getOrThrow()
                return
            }
            current = current.superclass
        }
        error("field not found in hierarchy: ${owner.javaClass.name}#$fieldName")
    }

    private fun Bundle.hasCardConfigMarkers(): Boolean {
        return containsKey(REAR_WIDGET_CARD_ONE_CONFIG_JSON_KEY) || containsKey("__rear_card_id__")
    }

    private fun applyHostOneConfig(mamlView: Any, json: String) {
        runCatching {
            val oneConfigClass = "com.miui.maml.widget.edit.OneConfig".toClass()
            val widgetEditSaveClass = "com.miui.maml.widget.edit.WidgetEditSave".toClass()

            val hostOneConfig = com.google.gson.Gson().fromJson(json, oneConfigClass) ?: return

            mamlView.javaClass.methods.firstOrNull {
                it.name == "setConfig" && it.parameterTypes.size == 1
            }?.invoke(mamlView, hostOneConfig)

            widgetEditSaveClass.methods.firstOrNull {
                it.name == "restoreMamlView" && it.parameterTypes.size == 2
            }?.invoke(null, hostOneConfig, mamlView)

            debugLog("apply host one config success jsonLength=${json.length} oneConfigClass=${oneConfigClass.name}")
        }.onFailure {
            debugLog("apply host one config failed err=${it.message}")
        }
    }

    private fun applyCompatOneConfig(mamlView: Any, json: String) {
        val oneConfig = WidgetTemplateConfigRepository.decodeOneConfig(json) ?: return
        val dropDownValues = oneConfig.dropDownSaveConfig.orEmpty()
        if (dropDownValues.isEmpty()) return

        runCatching {
            val putVariableString = mamlView.javaClass.methods.firstOrNull {
                it.name == "putVariableString" && it.parameterTypes.size == 3
            } ?: return
            val requestUpdate = mamlView.javaClass.methods.firstOrNull {
                it.name == "requestUpdate" && it.parameterTypes.isEmpty()
            }
            val sendCommand = mamlView.javaClass.methods.firstOrNull {
                it.name == "sendCommand" && it.parameterTypes.size == 1
            }

            dropDownValues.forEach { (key, value) ->
                putVariableString.invoke(mamlView, key, value, 1)
            }
            requestUpdate?.invoke(mamlView)
            sendCommand?.invoke(mamlView, "resume")
            sendCommand?.invoke(mamlView, "refresh_after_edit")
        }.onFailure {
            debugLog("apply compat one config failed err=${it.message}")
        }
    }

    private fun requestMamlRefresh(mamlView: Any) {
        runCatching {
            mamlView.javaClass.methods.firstOrNull {
                it.name == "requestUpdate" && it.parameterTypes.isEmpty()
            }?.invoke(mamlView)
            mamlView.javaClass.methods.firstOrNull {
                it.name == "sendCommand" && it.parameterTypes.size == 1
            }?.let { sendCommand ->
                sendCommand.invoke(mamlView, "resume")
                sendCommand.invoke(mamlView, "refresh_after_edit")
            }
        }.onFailure {
            debugLog("request maml refresh failed err=${it.message}")
        }
    }

    private data class ManifestVarDef(
        val name: String,
        val type: String,
        val expression: String,
    )

    private fun applyManifestDerivedVars(mamlView: Any, templatePath: String?) {
        val manifest = templatePath?.let(::readManifestText) ?: return
        val vars = parseManifestVarDefs(manifest)
        if (vars.isEmpty()) return

        val putString = mamlView.javaClass.methods.firstOrNull {
            it.name == "putVariableString" && it.parameterTypes.size == 3
        }
        val putNumber = mamlView.javaClass.methods.firstOrNull {
            it.name == "putVariableNumber" && it.parameterTypes.size == 3
        }
        val getString = mamlView.javaClass.methods.firstOrNull {
            it.name == "getVariableString" && it.parameterTypes.size == 1
        }
        val getNumber = mamlView.javaClass.methods.firstOrNull {
            it.name == "getVariableNumber" && it.parameterTypes.size == 1
        }
        val getObject = mamlView.javaClass.methods.firstOrNull {
            it.name == "getVariableObject" && it.parameterTypes.size == 1
        }
        if (putString == null || putNumber == null || getString == null || getNumber == null) return

        fun putDerivedString(key: String, value: String?) {
            if (value == null) return
            putString.invoke(mamlView, key, value, 1)
        }

        fun putDerivedNumber(key: String, value: Double?) {
            if (value == null) return
            putNumber.invoke(mamlView, key, value, 1)
        }

        val directStringAlias = Regex("^@([A-Za-z0-9_]+)$")
        val directNumberAlias = Regex("^#([A-Za-z0-9_]+)$")
        val alignExpr = Regex(
            """^ifelse\(\(#([A-Za-z0-9_]+)\s*==\s*0\),'left',ifelse\(\(#\1\s*==\s*1\),'center','right'\)\)$"""
        )
        val multiplyExpr = Regex(
            """^\(#([A-Za-z0-9_]+)\s*\*\s*#([A-Za-z0-9_]+)\)$"""
        )

        vars.forEach { varDef ->
            val expr = varDef.expression.trim()
            directStringAlias.matchEntire(expr)?.groupValues?.getOrNull(1)?.let { source ->
                putDerivedString(varDef.name, getString.invoke(mamlView, source) as? String)
            }
            directNumberAlias.matchEntire(expr)?.groupValues?.getOrNull(1)?.let { source ->
                val value = (getNumber.invoke(mamlView, source) as? Number)?.toDouble()
                putDerivedNumber(varDef.name, value)
            }
        }

        vars.forEach { varDef ->
            val expr = varDef.expression.trim()
            alignExpr.matchEntire(expr)?.groupValues?.getOrNull(1)?.let { source ->
                val alignValue = ((getNumber.invoke(mamlView, source) as? Number)?.toInt()) ?: 0
                val textAlign = when (alignValue) {
                    0 -> "left"
                    1 -> "center"
                    else -> "right"
                }
                putDerivedString(varDef.name, textAlign)
            }
            multiplyExpr.matchEntire(expr)?.let { match ->
                val left = (getNumber.invoke(mamlView, match.groupValues[1]) as? Number)?.toDouble()
                val right =
                    (getNumber.invoke(mamlView, match.groupValues[2]) as? Number)?.toDouble()
                if (left != null && right != null) {
                    putDerivedNumber(varDef.name, left * right)
                }
            }
            if (expr.contains("#bgUrl[0]") && expr.contains("@bgPath1")) {
                val selected =
                    (getObject?.invoke(mamlView, "bgUrl") as? DoubleArray) ?: doubleArrayOf()
                val bg1 = getString.invoke(mamlView, "bgPath1") as? String
                val bg2 = getString.invoke(mamlView, "bgPath2") as? String
                val bg3 = getString.invoke(mamlView, "bgPath3") as? String
                val resolved = when {
                    selected.getOrNull(0) == 1.0 -> bg1
                    selected.getOrNull(1) == 1.0 -> bg2
                    selected.getOrNull(2) == 1.0 -> bg3
                    else -> bg1
                }
                putDerivedString(varDef.name, resolved)
            }
        }
    }

    private fun parseManifestVarDefs(text: String): List<ManifestVarDef> {
        val regex =
            Regex("<Var\\s+[^>]*name=\"([^\"]+)\"[^>]*type=\"([^\"]+)\"[^>]*expression=\"([^\"]*)\"[^>]*/?>")
        return regex.findAll(text).map {
            ManifestVarDef(
                name = it.groupValues[1],
                type = it.groupValues[2],
                expression = it.groupValues[3],
            )
        }.toList()
    }

    private fun readManifestText(templatePath: String): String? {
        return runCatching {
            val file = File(templatePath)
            if (!file.exists()) return null
            if (file.isDirectory) {
                val manifest = File(file, "manifest.xml")
                if (!manifest.exists()) return null
                return manifest.readText()
            }
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.xml") ?: return@use null
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }.getOrNull()
    }

    private fun resolveTemplateImagePreviewModel(
        business: String,
        sourcePath: String,
        imageValue: String,
    ): RearWidgetTemplateImagePreview? {
        if (imageValue.isBlank()) return null
        val templatePath = resolveTemplatePreviewPath(business, sourcePath) ?: return null
        debugLog(
            "resolveTemplateImagePreview business=$business normalized=${
                normalizeBusinessName(
                    business
                )
            } source=${sourcePath.ifBlank { "<builtin>" }} template=$templatePath value=$imageValue"
        )
        val imageBytes = loadTemplateImageBytes(templatePath, imageValue) ?: return null
        val previewBytes = compressPreviewBytes(imageBytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size, bounds)
        return RearWidgetTemplateImagePreview(
            imageValue = imageValue,
            templateSourcePath = sourcePath,
            previewBase64 = Base64.encodeToString(previewBytes, Base64.NO_WRAP),
            mimeType = "image/png",
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
        )
    }

    private fun resolveTemplateConfigStateModel(
        business: String,
        sourcePath: String,
        currentOneConfigJson: String?,
    ): RearWidgetTemplateConfigState? {
        val templatePath = resolveTemplatePreviewPath(business, sourcePath)
        if (templatePath == null) {
            debugLog(
                "resolveTemplateConfigState miss-path business=$business normalized=${
                    normalizeBusinessName(
                        business
                    )
                } source=${sourcePath.ifBlank { "<builtin>" }}"
            )
            return null
        }
        val schema = WidgetTemplateConfigRepository.loadSchema(templatePath)
        if (schema == null) {
            debugLog(
                "resolveTemplateConfigState miss-schema business=$business normalized=${
                    normalizeBusinessName(
                        business
                    )
                } template=$templatePath"
            )
            return null
        }
        debugLog(
            "resolveTemplateConfigState business=$business normalized=${
                normalizeBusinessName(
                    business
                )
            } source=${sourcePath.ifBlank { "<builtin>" }} template=$templatePath editable=${schema.editableItemCount}"
        )
        val oneConfig = WidgetTemplateConfigRepository.buildInitialOneConfig(
            schema = schema,
            existingJson = currentOneConfigJson,
        )
        return RearWidgetTemplateConfigState(
            templateSchemaJson = WidgetTemplateConfigRepository.encodeSchema(schema),
            oneConfigJson = WidgetTemplateConfigRepository.encodeOneConfig(oneConfig),
        )
    }

    private fun resolveTemplatePreviewPath(
        business: String,
        sourcePath: String,
    ): String? {
        val normalizedBusiness = normalizeBusinessName(business)
        val deployed = normalizedBusiness.takeIf { it.isNotBlank() }
            ?.let {
                RearWidgetRuntimeStore.getBusinessFile(it)
                    ?: RearWidgetRuntimeStore.getBusinessFile(business)
            }
            ?.takeIf { File(it).exists() }
        if (deployed != null) {
            debugLog("resolveTemplatePreviewPath deployed business=$business normalized=$normalizedBusiness path=$deployed")
            return deployed
        }

        val builtin = normalizedBusiness.takeIf { it.isNotBlank() }
            ?.let(::resolveBuiltinTemplatePath)
            ?.takeIf { File(it).exists() }
        if (builtin != null) {
            debugLog("resolveTemplatePreviewPath builtin business=$business normalized=$normalizedBusiness path=$builtin")
            return builtin
        }

        if (normalizedBusiness.isNotBlank() && sourcePath.isNotBlank()) {
            deployBusinessTemplate(normalizedBusiness, sourcePath)?.takeIf { File(it).exists() }
                ?.let {
                    debugLog("resolveTemplatePreviewPath deployed-from-source business=$business normalized=$normalizedBusiness source=$sourcePath path=$it")
                    return it
                }
        }

        sourcePath.takeIf { it.isNotBlank() && File(it).exists() }?.let {
            debugLog("resolveTemplatePreviewPath source business=$business normalized=$normalizedBusiness path=$it")
            return it
        }
        debugLog("resolveTemplatePreviewPath miss business=$business normalized=$normalizedBusiness source=${sourcePath.ifBlank { "<builtin>" }}")
        return null
    }

    private fun loadTemplateImageBytes(
        templatePath: String,
        imageValue: String,
    ): ByteArray? {
        val normalized = imageValue.trim().removePrefix("file://")
        if (normalized.isBlank()) return null

        val directFile = when {
            imageValue.startsWith("file://", ignoreCase = true) -> imageValue.toUri().path
            normalized.startsWith("/") -> normalized
            else -> null
        }?.let(::File)
        if (directFile != null && directFile.isFile) {
            return runCatching { directFile.readBytes() }.getOrNull()
        }

        val templateFile = File(templatePath)
        if (!templateFile.exists()) return null
        val relativeCandidates = linkedSetOf(
            normalized.removePrefix("/"),
            imageValue.trim().removePrefix("/"),
        ).filter { it.isNotBlank() }

        if (templateFile.isDirectory) {
            relativeCandidates.forEach { candidate ->
                val child = File(templateFile, candidate)
                if (child.isFile) {
                    return runCatching { child.readBytes() }.getOrNull()
                }
            }
            return null
        }

        return runCatching {
            ZipFile(templateFile).use { zip ->
                val entry = relativeCandidates.firstNotNullOfOrNull { candidate ->
                    zip.getEntry(candidate)
                        ?: zip.entries().asSequence().firstOrNull {
                            it.name.equals(candidate, ignoreCase = true)
                        }
                } ?: return@use null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()
    }

    private fun compressPreviewBytes(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        val sampleSize = when {
            maxDimension <= 0 -> 1
            maxDimension <= 320 -> 1
            else -> {
                var sample = 1
                while (maxDimension / sample > 320) sample *= 2
                sample
            }
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        return ByteArrayOutputStream().use { output ->
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            if (!ok) return null
            output.toByteArray()
        }
    }

    private fun importCardCustomImageInternal(
        cardKey: String,
        fieldName: String,
        sourceUri: String,
        displayNameHint: String,
    ): String? {
        if (cardKey.isBlank() || fieldName.isBlank() || sourceUri.isBlank()) return null
        val context = hostContext ?: return null
        val uri = runCatching { sourceUri.toUri() }.getOrNull() ?: return null
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val extension = displayNameHint
            .substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?: "png"

        val assetDir = resolveCardAssetDir(cardKey)
        val safeField = fieldName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        runCatching {
            if (!assetDir.exists()) assetDir.mkdirs()
            assetDir.listFiles()?.forEach { child ->
                if (child.isFile && child.name.startsWith("${safeField}_")) {
                    child.delete()
                }
            }
        }

        val target = File(assetDir, "${safeField}_${System.currentTimeMillis()}.$extension")
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            ensureReadable(target)
            target.absolutePath
        }.getOrNull()
    }

    @SuppressLint("SetWorldReadable")
    private fun ensureReadable(file: File) {
        file.setReadable(true, false)
        file.parentFile?.setReadable(true, false)
        file.parentFile?.setExecutable(true, false)
    }

}
