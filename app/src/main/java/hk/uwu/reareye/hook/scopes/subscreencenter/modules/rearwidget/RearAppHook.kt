@file:Suppress("UNCHECKED_CAST")

package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.util.Base64
import android.view.View
import androidx.core.content.ContextCompat
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.SubscreenWidgetOffscreenRenderer
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.SubscreenWidgetRenderHostRegistry
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.SubscreenWidgetRuntimeController
import hk.uwu.reareye.hook.support.ReloadableReceiverRegistration
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveDexKitFieldValue
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.widgetapi.IRearAppApiConnection
import hk.uwu.reareye.widgetapi.IRearAppApiService
import hk.uwu.reareye.widgetapi.RearAppApiContract
import hk.uwu.reareye.widgetapi.RearAppCardInfo
import hk.uwu.reareye.widgetapi.RearAppOperationResult
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import org.json.JSONObject
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

@OptIn(DexKitExperimentalApi::class)
class RearAppHook : RoxyHooker() {
    companion object {
        private const val TAG = "REAREye-RearApp"
        private const val REAREYE_PACKAGE = "hk.uwu.reareye"
        private const val OWNED_ID_PREFIX = "reareye_app_v1_"
        private const val REMOVED_ACTION = "miui.intent.action.APP_CARD_REMOVED"
        private const val LOAD_TIMEOUT_MS = 2500L
        private const val SAVE_TIMEOUT_MS = 3000L

        private const val MANAGER_CLASS_CACHE_KEY = "SSC_APP_CARD_MANAGER_CLASS_V1"
        private const val MODEL_CLASS_CACHE_KEY = "SSC_APP_CARD_MODEL_CLASS_V1"
        private const val WIDGET_CLASS_CACHE_KEY = "SSC_APP_CARD_WIDGET_CLASS_V1"
        private const val MANAGER_INSTANCE_FIELD_CACHE_KEY = "SSC_APP_CARD_MANAGER_INSTANCE_V1"
        private const val SNAPSHOT_METHOD_CACHE_KEY = "SSC_APP_CARD_SNAPSHOT_METHOD_V1"
        private const val LOAD_METHOD_CACHE_KEY = "SSC_APP_CARD_LOAD_METHOD_V1"
        private const val SAVE_METHOD_CACHE_KEY = "SSC_APP_CARD_SAVE_METHOD_V1"
        private const val WIDGET_FACTORY_METHOD_CACHE_KEY = "SSC_APP_CARD_WIDGET_FACTORY_V1"
        private const val WIDGET_TO_MODEL_METHOD_CACHE_KEY = "SSC_APP_CARD_WIDGET_TO_MODEL_V1"
        private const val MODEL_TO_WIDGET_METHOD_CACHE_KEY = "SSC_APP_CARD_MODEL_TO_WIDGET_V1"
        private const val RUNTIME_FACTORY_METHOD_CACHE_KEY = "SSC_APP_CARD_RUNTIME_FACTORY_V1"
        private const val RUNTIME_HOST_FIELD_CACHE_KEY = "SSC_APP_CARD_RUNTIME_HOST_FIELD_V1"
        private const val RUNTIME_PREVIEW_FIELD_CACHE_KEY =
            "SSC_APP_CARD_RUNTIME_PREVIEW_FIELD_V1"
        private const val RUNTIME_SET_EDIT_METHOD_CACHE_KEY =
            "SSC_APP_CARD_RUNTIME_SET_EDIT_V1"
        private const val RUNTIME_CREATE_VIEW_METHOD_CACHE_KEY =
            "SSC_APP_CARD_RUNTIME_CREATE_VIEW_V1"
        private const val RUNTIME_SET_AOD_METHOD_CACHE_KEY =
            "SSC_APP_CARD_RUNTIME_SET_AOD_V1"
        private const val RUNTIME_RESUME_METHOD_CACHE_KEY = "SSC_APP_CARD_RUNTIME_RESUME_V1"
        private const val RUNTIME_CLEANUP_METHOD_CACHE_KEY = "SSC_APP_CARD_RUNTIME_CLEANUP_V1"
        private const val DEVICE_CONFIG_CLASS_CACHE_KEY = "SSC_APP_CARD_DEVICE_CONFIG_CLASS_V1"
        private const val DEVICE_RENDER_SIZE_FIELD_CACHE_KEY =
            "SSC_APP_CARD_DEVICE_RENDER_SIZE_FIELD_V1"
        private const val PREVIEW_DIRECTORY = "reareye/app-card-previews"
    }

    private data class HostCard(
        val model: Any,
        val appId: String,
        val appNameJson: String,
        val title: String,
        val templatePath: String,
        val bindPackage: String?,
        val appIconPath: String?,
        val previewLightPath: String?,
        val previewDarkPath: String?,
        val isGame: Boolean,
        val isPreset: Boolean,
        val appCardType: Int,
    )

    private data class HostAccess(
        val manager: Any,
        val modelClass: Class<*>,
        val snapshotMethod: Method,
        val loadMethod: Method,
        val saveMethod: Method,
        val widgetFactoryMethod: Method,
        val widgetToModelMethod: Method,
        val modelToWidgetMethod: Method,
        val runtimeFactoryMethod: Method,
        val runtimeHostField: Field,
        val runtimePreviewField: Field,
        val runtimeSetEditMethod: Method,
        val runtimeCreateViewMethod: Method,
        val runtimeSetAodMethod: Method,
        val runtimeResumeMethod: Method,
        val runtimeCleanupMethod: Method,
        val deviceRenderSizeField: Field,
    )

    private val bootstrapReceiverRegistration = ReloadableReceiverRegistration(
        label = "rear app bootstrap receiver",
        logger = { message, throwable ->
            if (throwable == null) YLog.error(message) else YLog.error(message, throwable)
        },
    )
    private val apiConnections = ConcurrentHashMap.newKeySet<IRearAppApiConnection>()
    private var hostContext: Context? = null
    private var hostAccess: HostAccess? = null
    private var dexKitBridge: DexKitCacheBridge.RecyclableBridge? = null
    private var hookBinder: IRearAppApiService.Stub? = null
    private lateinit var hookBootstrapReceiver: BroadcastReceiver

    override fun onHotReloadPreflight(): Boolean {
        if (!bootstrapReceiverRegistration.isConsistent()) {
            YLog.error("Rear app reload preflight failed: bootstrap receiver has no host context")
            return false
        }
        return true
    }

    override fun onHotReloadQuiesce() {
        invalidateApiConnections()
        if (::hookBootstrapReceiver.isInitialized) {
            check(bootstrapReceiverRegistration.unregister { context ->
                context.unregisterReceiver(hookBootstrapReceiver)
            }) { "$TAG failed to unregister bootstrap receiver" }
        }
        hostContext = null
        hostAccess = null
        dexKitBridge = null
        hookBinder = null
    }

    override fun PackageScope.onHook() {
        loadApp(RearAppApiContract.HOOK_HOST_PACKAGE) {
            val versionCode = resolveHookPackageVersionCode(
                context = hookSystemContext,
                packageName = hookAppInfo.packageName,
                sourceDir = hookAppInfo.sourceDir,
            )
            val bridge = runtime.manage(
                createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = versionCode,
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                )
            )
            dexKitBridge = bridge
            val access = runCatching { resolveHostAccess(bridge) }
                .onFailure { YLog.error("[$TAG] DexKit resolution failed", it) }
                .getOrNull()
                ?: return@loadApp
            hostAccess = access
            hookBinder = createHookBinder()
            hookBootstrapReceiver = createBootstrapReceiver()

            this.lifecycle {
                onAttach(replay = true) {
                    hostContext = application.applicationContext ?: application
                    registerHookBootstrapReceiver()
                    runCatching { loadHostModels() }
                        .onFailure { YLog.warn("[$TAG] initial app-card load failed", it) }
                }
            }
            YLog.info("[$TAG] installed with DexKit-resolved app-card access")
        }
    }

    private fun PackageScope.resolveHostAccess(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): HostAccess {
        val managerClassName = resolveDexKitClassValue(bridge, MANAGER_CLASS_CACHE_KEY) {
            findClass {
                matcher {
                    usingStrings(
                        "LauncherDataManager",
                        "convertWidgetToLauncherInfo: widget = ",
                        "saveAppInfoList: from: ",
                    )
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve app-card manager class")

        val modelClassName = resolveDexKitClassValue(bridge, MODEL_CLASS_CACHE_KEY) {
            findClass {
                matcher {
                    usingStrings(
                        "LauncherAppInfo{mAppName='",
                        "mMamlPath='",
                        "mAppCardId='",
                        "mBindApp='",
                    )
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve app-card model class")

        val widgetClassName = resolveDexKitClassValue(bridge, WIDGET_CLASS_CACHE_KEY) {
            findClass {
                matcher {
                    usingStrings("__PIN_CONTENT_IMAGE__", "__PIN_CONTENT_TEXT__", "mtzSnapshotPath")
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve host widget parcelable class")

        val instanceFieldName = resolveDexKitFieldValue(
            bridge,
            MANAGER_INSTANCE_FIELD_CACHE_KEY,
        ) {
            findField {
                matcher {
                    declaredClass = managerClassName
                    type = managerClassName
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve app-card manager singleton")

        val snapshotPoint = resolveDexKitMethodInjectionPoint(bridge, SNAPSHOT_METHOD_CACHE_KEY) {
            findMethod {
                matcher {
                    declaredClass = managerClassName
                    paramTypes()
                    returnType = "java.util.ArrayList"
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve app-card snapshot method")

        val loadPoint = resolveDexKitMethodInjectionPoint(bridge, LOAD_METHOD_CACHE_KEY) {
            findMethod {
                matcher {
                    declaredClass = managerClassName
                    paramTypes("java.util.function.Consumer")
                    returnType = "void"
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve app-card load method")

        val savePoint = resolveDexKitMethodInjectionPoint(bridge, SAVE_METHOD_CACHE_KEY) {
            findMethod {
                matcher {
                    declaredClass = managerClassName
                    paramTypes("java.util.ArrayList", "java.lang.String", modelClassName)
                    returnType = "void"
                    usingStrings("saveAppInfoList: from: ", "saveAppInfoList: appInfoList is empty")
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve app-card save method")

        val widgetFactoryPoint = resolveDexKitMethodInjectionPoint(
            bridge,
            WIDGET_FACTORY_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = widgetClassName
                    paramTypes(
                        "java.lang.String",
                        "java.lang.String",
                        "int",
                        "java.lang.String",
                        "java.lang.String",
                        "java.lang.String",
                        "android.os.Bundle",
                    )
                    returnType = widgetClassName
                    usingStrings(
                        "resId",
                        "appName",
                        "appCardType",
                        "appIconPath",
                        "previewLightPath"
                    )
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve app-card widget factory")

        val widgetToModelPoint = resolveDexKitMethodInjectionPoint(
            bridge,
            WIDGET_TO_MODEL_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = managerClassName
                    paramTypes(widgetClassName, modelClassName)
                    returnType = modelClassName
                    usingStrings(
                        "appName",
                        "appIconPath",
                        "previewLightPath",
                        "previewDarkPath",
                        "resId",
                        "bindApp",
                        "isGame",
                        "isPreset",
                        "appCardType",
                    )
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve widget-to-app-card conversion")

        val modelToWidgetPoint = resolveDexKitMethodInjectionPoint(
            bridge,
            MODEL_TO_WIDGET_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = managerClassName
                    paramTypes(modelClassName)
                    returnType = widgetClassName
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve app-card-to-widget conversion")

        val runtimeFactoryPoint = resolveDexKitMethodInjectionPoint(
            bridge,
            RUNTIME_FACTORY_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    paramTypes(widgetClassName)
                    usingStrings("snapshotPath_", "snapshotPath")
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve generic widget runtime factory")
        val runtimeClassName = runtimeFactoryPoint.className

        val runtimeHostFieldName = resolveDexKitFieldValue(
            bridge,
            RUNTIME_HOST_FIELD_CACHE_KEY,
        ) {
            findField {
                matcher {
                    declaredClass = runtimeClassName
                    type = "android.view.ViewGroup"
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve widget runtime host field")

        val runtimePreviewFieldName = resolveDexKitFieldValue(
            bridge,
            RUNTIME_PREVIEW_FIELD_CACHE_KEY,
        ) {
            findField {
                matcher {
                    declaredClass = runtimeClassName
                    type = "boolean"
                    writeMethods {
                        add {
                            usingStrings("createWidgets: index=", ", targetIndex=", ", new = ")
                            paramCount(5)
                            returnType = "void"
                        }
                    }
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve widget runtime preview field")

        val runtimeSetEditPoint = resolveDexKitMethodInjectionPoint(
            bridge,
            RUNTIME_SET_EDIT_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = runtimeClassName
                    paramTypes("boolean")
                    returnType = "void"
                    callerMethods {
                        add {
                            usingStrings("enterEditingMode", "enterEditingModeImpl")
                            returnType = "void"
                        }
                    }
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve widget runtime edit-mode method")

        val runtimeCreateViewPoint = resolveDexKitMethodInjectionPoint(
            bridge,
            RUNTIME_CREATE_VIEW_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = runtimeClassName
                    modifiers(
                        AccessFlagsMatcher(
                            matchType = MatchType.Equals,
                            modifiers = Modifier.PUBLIC or Modifier.FINAL,
                        )
                    )
                    paramTypes(Context::class.java)
                    returnType = "android.view.View"
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve widget runtime create-view method")

        val runtimeSetAodPoint = resolveDexKitMethodInjectionPoint(
            bridge,
            RUNTIME_SET_AOD_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = runtimeClassName
                    paramTypes("boolean")
                    returnType = "void"
                    usingStrings(
                        "Skipping AOD state for just-woken widget (first time only)",
                        "force_non_aod_state",
                    )
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve widget runtime AOD method")

        val runtimeResumePoint = resolveDexKitMethodInjectionPoint(
            bridge,
            RUNTIME_RESUME_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = runtimeClassName
                    paramTypes()
                    returnType = "void"
                    callerMethods {
                        add {
                            usingStrings("createWidgets: index=", ", targetIndex=", ", new = ")
                            paramCount(5)
                            returnType = "void"
                        }
                    }
                    invokeMethods {
                        add {
                            declaredClass = runtimeClassName
                            paramTypes()
                            returnType = "void"
                            usingStrings("trackAssistExpose bundle = ")
                        }
                    }
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve widget runtime resume method")

        val runtimeCleanupPoint = resolveDexKitMethodInjectionPoint(
            bridge,
            RUNTIME_CLEANUP_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = runtimeClassName
                    paramTypes()
                    returnType = "void"
                    callerMethods {
                        add {
                            usingStrings("createWidgets: index=", ", targetIndex=", ", new = ")
                            paramCount(5)
                            returnType = "void"
                        }
                    }
                    invokeMethods {
                        add {
                            declaredClass = runtimeClassName
                            name = runtimeSetEditPoint.methodName
                            paramTypes("boolean")
                            returnType = "void"
                        }
                    }
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve widget runtime cleanup method")

        val deviceConfigClassName = resolveDexKitClassValue(
            bridge,
            DEVICE_CONFIG_CLASS_CACHE_KEY,
        ) {
            findClass { matcher { usingStrings("vendor.wallpaper.color.flag") } }.singleOrNull()
        } ?: error("DexKit failed to resolve rear-screen device configuration")
        val deviceRenderSizeFieldName = resolveDexKitFieldValue(
            bridge,
            DEVICE_RENDER_SIZE_FIELD_CACHE_KEY,
        ) {
            findField {
                matcher {
                    declaredClass = deviceConfigClassName
                    modifiers = Modifier.STATIC or Modifier.FINAL
                    type = "android.graphics.Point"
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve rear-screen render size")

        val managerClass = managerClassName.toClass()
        val modelClass = modelClassName.toClass()
        val widgetClass = widgetClassName.toClass()
        val runtimeClass = runtimeClassName.toClass()
        val deviceConfigClass = deviceConfigClassName.toClass()
        val manager = managerClass.getDeclaredField(instanceFieldName).apply {
            isAccessible = true
        }.get(null) ?: error("DexKit-resolved app-card manager singleton is null")

        fun Method.open(): Method = apply { isAccessible = true }
        fun Field.open(): Field = apply { isAccessible = true }
        return HostAccess(
            manager = manager,
            modelClass = modelClass,
            snapshotMethod = managerClass.getDeclaredMethod(snapshotPoint.methodName).open(),
            loadMethod = managerClass.getDeclaredMethod(
                loadPoint.methodName,
                Consumer::class.java,
            ).open(),
            saveMethod = managerClass.getDeclaredMethod(
                savePoint.methodName,
                ArrayList::class.java,
                String::class.java,
                modelClass,
            ).open(),
            widgetFactoryMethod = widgetClass.getDeclaredMethod(
                widgetFactoryPoint.methodName,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java,
            ).open(),
            widgetToModelMethod = managerClass.getDeclaredMethod(
                widgetToModelPoint.methodName,
                widgetClass,
                modelClass,
            ).open(),
            modelToWidgetMethod = managerClass.getDeclaredMethod(
                modelToWidgetPoint.methodName,
                modelClass,
            ).open(),
            runtimeFactoryMethod = runtimeClass.getDeclaredMethod(
                runtimeFactoryPoint.methodName,
                widgetClass,
            ).open(),
            runtimeHostField = runtimeClass.getDeclaredField(runtimeHostFieldName).open(),
            runtimePreviewField = runtimeClass.getDeclaredField(runtimePreviewFieldName).open(),
            runtimeSetEditMethod = runtimeClass.getDeclaredMethod(
                runtimeSetEditPoint.methodName,
                Boolean::class.javaPrimitiveType,
            ).open(),
            runtimeCreateViewMethod = runtimeClass.getDeclaredMethod(
                runtimeCreateViewPoint.methodName,
                Context::class.java,
            ).open(),
            runtimeSetAodMethod = runtimeClass.getDeclaredMethod(
                runtimeSetAodPoint.methodName,
                Boolean::class.javaPrimitiveType,
            ).open(),
            runtimeResumeMethod = runtimeClass.getDeclaredMethod(
                runtimeResumePoint.methodName,
            ).open(),
            runtimeCleanupMethod = runtimeClass.getDeclaredMethod(
                runtimeCleanupPoint.methodName,
            ).open(),
            deviceRenderSizeField = deviceConfigClass
                .getDeclaredField(deviceRenderSizeFieldName)
                .open(),
        )
    }

    private fun PackageScope.createHookBinder() = object : IRearAppApiService.Stub() {
        override fun getCatalog(): Bundle {
            enforceCallerPermission()
            return buildCatalogBundle()
        }

        override fun registerAppCard(title: String?, componentBusiness: String?): Bundle {
            enforceCallerPermission()
            return registerAppCardInternal(title, componentBusiness).toBundle()
        }

        override fun renameAppCard(appId: String?, title: String?): Bundle {
            enforceCallerPermission()
            return renameAppCardInternal(appId, title).toBundle()
        }

        override fun renderAppCardPreview(appId: String?): Bundle {
            enforceCallerPermission()
            return renderAppCardPreviewInternal(appId).toBundle()
        }

        override fun deleteAppCard(appId: String?): Bundle {
            enforceCallerPermission()
            return deleteAppCardInternal(appId).toBundle()
        }

        override fun reorderAppCards(request: Bundle?): Bundle {
            enforceCallerPermission()
            val orderedAppIds = request
                ?.getStringArrayList(RearAppApiContract.BundleKeys.ORDERED_APP_IDS)
                .orEmpty()
            return reorderAppCardsInternal(orderedAppIds).toBundle()
        }
    }

    private fun PackageScope.createBootstrapReceiver() = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (runtime.isClosed) return
            if (intent?.action != RearAppApiContract.ACTION_REQUEST_HOOK_SERVICE) return
            val callbackBinder = intent
                .getBundleExtra(RearAppApiContract.Extras.BUNDLE)
                ?.getBinder(RearAppApiContract.Extras.BINDER)
            val callback = IRearAppApiConnection.Stub.asInterface(callbackBinder)
            callback?.let(apiConnections::add)
            if (intent.getBooleanExtra(RearAppApiContract.Extras.FORCE_SYNC, false)) {
                runCatching { loadHostModels(forceReload = true) }
                    .onFailure { YLog.warn("[$TAG] forced app-card sync failed", it) }
            }
            runCatching { callback?.onServiceConnected(hookBinder) }
                .onFailure { throwable ->
                    callback?.let(apiConnections::remove)
                    YLog.error(throwable)
                }
        }
    }

    private fun invalidateApiConnections() {
        apiConnections.forEach { connection ->
            runCatching { connection.onServiceConnected(null) }
                .onFailure { YLog.warn("Failed to invalidate rear app client", it) }
        }
        apiConnections.clear()
    }

    private fun PackageScope.registerHookBootstrapReceiver() {
        val context = hostContext ?: return
        bootstrapReceiverRegistration.register(context) { registrationContext ->
            ContextCompat.registerReceiver(
                registrationContext,
                hookBootstrapReceiver,
                IntentFilter(RearAppApiContract.ACTION_REQUEST_HOOK_SERVICE),
                RearAppApiContract.SERVICE_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    private fun PackageScope.enforceCallerPermission() {
        check(!runtime.isClosed) { "Roxy runtime is closed" }
        val context = hostContext
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (context == null) throw SecurityException("context not ready for permission check")
        val granted = context.checkPermission(
            RearAppApiContract.SERVICE_PERMISSION,
            Binder.getCallingPid(),
            uid,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw SecurityException(
                "caller uid=$uid requires ${RearAppApiContract.SERVICE_PERMISSION}"
            )
        }
    }

    private fun buildCatalogBundle(): Bundle {
        val items = ArrayList<Bundle>()
        loadHostCards(forceReload = true).forEach { card ->
            val owned = isOwned(card)
            items += RearAppCardInfo(
                appId = card.appId,
                title = card.title,
                componentBusiness = if (owned) decodeBusiness(card.appId) else null,
                templatePath = card.templatePath,
                bindPackage = card.bindPackage,
                ownedByRearEye = owned,
                canRename = owned || card.appCardType == 2,
                canDelete = owned,
                canRenderPreview = owned,
                appCardType = card.appCardType,
            ).toBundle()
        }
        return Bundle().apply {
            putParcelableArrayList(RearAppApiContract.BundleKeys.ITEMS, items)
        }
    }

    private fun registerAppCardInternal(
        rawTitle: String?,
        rawBusiness: String?,
    ): RearAppOperationResult {
        val title = rawTitle?.trim().orEmpty()
        val business = rawBusiness?.trim().orEmpty()
        if (title.isBlank()) return failure("App card name cannot be empty")
        if (business.isBlank()) return failure("Bound component cannot be empty")
        val templatePath = RearWidgetRuntimeStore.getBusinessFile(business)
            ?.takeIf { File(it).isFile || File(it).isDirectory }
            ?: return failure("The bound component is not registered in SubScreenCenter")
        val cards = loadHostCards(forceReload = true).toMutableList()
        val appId = newOwnedId(business)
        val previewWidget = createHostWidget(
            appId = appId,
            appNameJson = encodeTitle(title),
            templatePath = templatePath,
            bindPackage = REAREYE_PACKAGE,
            appIconPath = null,
            previewLightPath = null,
            previewDarkPath = null,
            isGame = false,
            isPreset = false,
            appCardType = 0,
        )
        val previewPath = runCatching { renderPreview(appId, previewWidget) }
            .getOrElse { error ->
                return failure(
                    "Failed to render the app-card preview: " +
                            error.message.orEmpty().ifBlank { error.javaClass.simpleName }
                )
            }
        val model = createHostModel(
            appId = appId,
            appNameJson = encodeTitle(title),
            templatePath = templatePath,
            bindPackage = REAREYE_PACKAGE,
            appIconPath = previewPath,
            previewLightPath = previewPath,
            previewDarkPath = previewPath,
            isGame = false,
            isPreset = false,
            appCardType = 0,
        )
        val next = ArrayList(cards.map { it.model }).apply { add(model) }
        saveHostModels(next, "reareye_register", model)
        if (!awaitCardState { it.any { card -> card.appId == appId } }) {
            previewFile(appId).delete()
            return failure("Timed out while saving the app card")
        }
        return RearAppOperationResult(success = true, appId = appId)
    }

    private fun renameAppCardInternal(
        rawAppId: String?,
        rawTitle: String?,
    ): RearAppOperationResult {
        val appId = rawAppId?.trim().orEmpty()
        val title = rawTitle?.trim().orEmpty()
        if (appId.isBlank()) return failure("App card ID cannot be empty")
        if (title.isBlank()) return failure("App card name cannot be empty")
        val cards = loadHostCards(forceReload = true).toMutableList()
        val index = cards.indexOfFirst { it.appId == appId }
        if (index < 0) return failure("App card not found")
        val current = cards[index]
        if (!isOwned(current) && current.appCardType != 2) {
            return failure("Only REAREye app cards or appCardType=2 cards can be renamed")
        }
        val replacement = createHostModel(
            appId = current.appId,
            appNameJson = encodeTitle(title),
            templatePath = current.templatePath,
            bindPackage = current.bindPackage,
            appIconPath = current.appIconPath,
            previewLightPath = current.previewLightPath,
            previewDarkPath = current.previewDarkPath,
            isGame = current.isGame,
            isPreset = current.isPreset,
            appCardType = current.appCardType,
        )
        val next = ArrayList(cards.map { it.model })
        next[index] = replacement
        saveHostModels(next, "reareye_rename", replacement)
        if (!awaitCardState { items ->
                items.any { card -> card.appId == appId && card.title == title }
            }
        ) {
            return failure("Timed out while saving the app card name")
        }
        return RearAppOperationResult(success = true, appId = appId)
    }

    private fun renderAppCardPreviewInternal(rawAppId: String?): RearAppOperationResult {
        val appId = rawAppId?.trim().orEmpty()
        if (appId.isBlank()) return failure("App card ID cannot be empty")
        val cards = loadHostCards(forceReload = true).toMutableList()
        val index = cards.indexOfFirst { it.appId == appId }
        if (index < 0) return failure("App card not found")
        val current = cards[index]
        if (!isOwned(current)) {
            return failure("Only app cards registered by REAREye can render previews")
        }
        val descriptor = hostAccess?.modelToWidgetMethod?.invoke(null, current.model)
            ?: return failure("Failed to create the app-card preview descriptor")
        val previewPath = runCatching { renderPreview(appId, descriptor) }
            .getOrElse { error ->
                return failure(
                    "Failed to render the app-card preview: " +
                            error.message.orEmpty().ifBlank { error.javaClass.simpleName }
                )
            }
        val replacement = createHostModel(
            appId = current.appId,
            appNameJson = current.appNameJson,
            templatePath = current.templatePath,
            bindPackage = current.bindPackage,
            appIconPath = previewPath,
            previewLightPath = previewPath,
            previewDarkPath = previewPath,
            isGame = current.isGame,
            isPreset = current.isPreset,
            appCardType = current.appCardType,
        )
        val next = ArrayList(cards.map { it.model })
        next[index] = replacement
        saveHostModels(next, "reareye_render_preview", replacement)
        if (!awaitCardState { items ->
                items.any { card ->
                    card.appId == appId &&
                            card.appIconPath == previewPath &&
                            card.previewLightPath == previewPath &&
                            card.previewDarkPath == previewPath
                }
            }
        ) {
            return failure("Timed out while saving the app-card preview")
        }
        return RearAppOperationResult(success = true, appId = appId)
    }

    private fun deleteAppCardInternal(rawAppId: String?): RearAppOperationResult {
        val appId = rawAppId?.trim().orEmpty()
        if (appId.isBlank()) return failure("App card ID cannot be empty")
        val cards = loadHostCards(forceReload = true).toMutableList()
        val index = cards.indexOfFirst { it.appId == appId }
        if (index < 0) return failure("App card not found")
        val current = cards[index]
        if (!isOwned(current)) return failure("Only app cards registered by REAREye can be deleted")
        if (cards.size <= 1) return failure("At least one app card must be kept")
        cards.removeAt(index)
        saveHostModels(ArrayList(cards.map { it.model }), "reareye_delete", null)
        if (!awaitCardState { items -> items.none { card -> card.appId == appId } }) {
            return failure("Timed out while deleting the app card")
        }
        previewFile(appId).delete()
        sendRemovedBroadcast(current)
        return RearAppOperationResult(success = true, appId = appId)
    }

    private fun reorderAppCardsInternal(
        orderedAppIds: List<String>,
    ): RearAppOperationResult {
        val cards = loadHostCards(forceReload = true)
        if (orderedAppIds.size != cards.size || orderedAppIds.distinct().size != cards.size) {
            return failure("The requested order does not match the host app-card list")
        }
        val currentById = cards.associateBy { it.appId }
        if (currentById.size != cards.size || orderedAppIds.any { it !in currentById }) {
            return failure("The requested order contains an invalid app card")
        }
        val currentExternalOrder = cards.filterNot(::isOwned).map { it.appId }
        val requestedExternalOrder = orderedAppIds.filter { appId ->
            currentById[appId]?.let(::isOwned) == false
        }
        if (requestedExternalOrder != currentExternalOrder) {
            return failure("Only app cards registered by REAREye can be repositioned")
        }
        val currentOrder = cards.map { it.appId }
        if (orderedAppIds == currentOrder) {
            return RearAppOperationResult(success = true)
        }
        val movedOwnedCard = orderedAppIds
            .firstNotNullOfOrNull { appId -> currentById[appId]?.takeIf(::isOwned) }
            ?: return failure("No REAREye app card is available to reposition")
        val next = ArrayList(orderedAppIds.map { appId -> currentById.getValue(appId).model })
        saveHostModels(next, "reareye_reorder", movedOwnedCard.model)
        if (!awaitCardState { items -> items.map { it.appId } == orderedAppIds }) {
            return failure("Timed out while saving the app-card order")
        }
        return RearAppOperationResult(success = true)
    }

    private fun loadHostModels(forceReload: Boolean = false): ArrayList<Any> {
        val access = hostAccess ?: error("DexKit host access is unavailable")
        if (!forceReload) {
            val snapshot = snapshotHostModels(access)
            if (snapshot.isNotEmpty()) return snapshot
        }

        val loaded = AtomicReference<ArrayList<Any>?>()
        val latch = CountDownLatch(1)
        val consumer = Consumer<Any?> { value ->
            loaded.set(ArrayList((value as? Collection<*>)?.filterNotNull().orEmpty()))
            latch.countDown()
        }
        access.loadMethod.invoke(access.manager, consumer)
        check(latch.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "DexKit-resolved app-card load method timed out"
        }
        return requireNotNull(loaded.get()) {
            "DexKit-resolved app-card load method returned no list"
        }
    }

    private fun snapshotHostModels(
        access: HostAccess = hostAccess ?: error("DexKit host access is unavailable"),
    ): ArrayList<Any> {
        val value = access.snapshotMethod.invoke(access.manager) as? Collection<*>
        return ArrayList(value?.filterNotNull().orEmpty())
    }

    private fun loadHostCards(forceReload: Boolean = false): List<HostCard> {
        return loadHostModels(forceReload).mapNotNull { model ->
            runCatching { decodeHostCard(model) }
                .onFailure { YLog.warn("[$TAG] failed to decode host app card", it) }
                .getOrNull()
        }
    }

    private fun decodeHostCard(model: Any): HostCard {
        val access = hostAccess ?: error("DexKit host access is unavailable")
        val widget = access.modelToWidgetMethod.invoke(null, model) as? Parcelable
            ?: error("app-card conversion returned null")
        val parcel = Parcel.obtain()
        try {
            widget.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            parcel.readInt()
            parcel.readInt()
            parcel.readBoolean()
            val extras = parcel.readBundle(widget.javaClass.classLoader) ?: Bundle()
            parcel.readBoolean()
            val templatePath = parcel.readString().orEmpty()
            val appId = extras.getString("resId").orEmpty()
            val appNameJson = extras.getString("appName").orEmpty()
            return HostCard(
                model = model,
                appId = appId,
                appNameJson = appNameJson,
                title = decodeTitle(appNameJson),
                templatePath = templatePath,
                bindPackage = extras.getString("bindApp"),
                appIconPath = extras.getString("appIconPath"),
                previewLightPath = extras.getString("previewLightPath"),
                previewDarkPath = extras.getString("previewDarkPath"),
                isGame = extras.getBoolean("isGame", false),
                isPreset = extras.getBoolean("isPreset", false),
                appCardType = extras.getInt("appCardType", 0),
            )
        } finally {
            parcel.recycle()
        }
    }

    private fun createHostModel(
        appId: String,
        appNameJson: String,
        templatePath: String,
        bindPackage: String?,
        appIconPath: String?,
        previewLightPath: String?,
        previewDarkPath: String?,
        isGame: Boolean,
        isPreset: Boolean,
        appCardType: Int,
    ): Any {
        val access = hostAccess ?: error("DexKit host access is unavailable")
        val widget = createHostWidget(
            appId = appId,
            appNameJson = appNameJson,
            templatePath = templatePath,
            bindPackage = bindPackage,
            appIconPath = appIconPath,
            previewLightPath = previewLightPath,
            previewDarkPath = previewDarkPath,
            isGame = isGame,
            isPreset = isPreset,
            appCardType = appCardType,
        )
        val emptyModel = access.modelClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()
        return access.widgetToModelMethod.invoke(null, widget, emptyModel)
            ?: error("widget-to-app-card conversion returned null")
    }

    private fun createHostWidget(
        appId: String,
        appNameJson: String,
        templatePath: String,
        bindPackage: String?,
        appIconPath: String?,
        previewLightPath: String?,
        previewDarkPath: String?,
        isGame: Boolean,
        isPreset: Boolean,
        appCardType: Int,
    ): Any {
        val access = hostAccess ?: error("DexKit host access is unavailable")
        val extras = Bundle().apply {
            putString("previewDarkPath", previewDarkPath)
            putString("bindApp", bindPackage)
            putBoolean("isGame", isGame)
            putBoolean("isPreset", isPreset)
        }
        val widget = access.widgetFactoryMethod.invoke(
            null,
            appId,
            appNameJson,
            appCardType,
            templatePath,
            appIconPath,
            previewLightPath,
            extras,
        ) ?: error("app-card widget factory returned null")
        return widget
    }

    private fun renderPreview(appId: String, descriptor: Any): String {
        val access = hostAccess ?: error("DexKit host access is unavailable")
        val host = SubscreenWidgetRenderHostRegistry.snapshot()
            ?: error("SubScreenCenter render host is not ready")
        val runtimeWidget = access.runtimeFactoryMethod.invoke(null, descriptor)
            ?: error("Generic widget runtime factory returned null")
        return SubscreenWidgetOffscreenRenderer.renderToFile(
            host = host,
            widget = runtimeWidget,
            targetFile = previewFile(appId),
            renderSize = access.deviceRenderSizeField.get(null) as? Point,
            controller = SubscreenWidgetRuntimeController(
                attachHost = { widget, renderHost ->
                    access.runtimeHostField.set(widget, renderHost)
                },
                setEditMode = { widget, enabled ->
                    access.runtimeSetEditMethod.invoke(widget, enabled)
                },
                setPreviewMode = { widget, enabled ->
                    access.runtimePreviewField.setBoolean(widget, enabled)
                },
                createView = { widget, context ->
                    access.runtimeCreateViewMethod.invoke(widget, context) as? View
                },
                setAodState = { widget, inAod ->
                    access.runtimeSetAodMethod.invoke(widget, inAod)
                },
                resume = { widget -> access.runtimeResumeMethod.invoke(widget) },
                cleanup = { widget -> access.runtimeCleanupMethod.invoke(widget) },
            ),
            debug = { message -> YLog.debug("[$TAG] app-card preview appId=$appId $message") },
        )
    }

    private fun previewFile(appId: String): File {
        val context = hostContext ?: error("host context is not ready")
        val safeName = appId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.filesDir, PREVIEW_DIRECTORY), "$safeName.jpg")
    }

    private fun saveHostModels(models: ArrayList<Any>, source: String, changed: Any?) {
        val access = hostAccess ?: error("DexKit host access is unavailable")
        access.saveMethod.invoke(access.manager, models, source, changed)
    }

    private fun awaitCardState(predicate: (List<HostCard>) -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAVE_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            val cards = snapshotHostModels().mapNotNull { model ->
                runCatching { decodeHostCard(model) }.getOrNull()
            }
            if (predicate(cards)) return true
            Thread.sleep(50L)
        }
        return false
    }

    @SuppressLint("WrongConstant")
    private fun sendRemovedBroadcast(card: HostCard) {
        val context = hostContext ?: return
        val base = Intent(REMOVED_ACTION)
            .addFlags(0x01000000)
            .putExtra("packageName", card.bindPackage)
            .putExtra("resId", card.appId)
        context.sendBroadcast(base)
        card.bindPackage?.takeIf { it.isNotBlank() }?.let { packageName ->
            context.sendBroadcast(Intent(base).setPackage(packageName))
        }
    }

    private fun isOwned(card: HostCard): Boolean {
        return card.bindPackage == REAREYE_PACKAGE && decodeBusiness(card.appId) != null
    }

    private fun newOwnedId(business: String): String {
        val encoded = Base64.encodeToString(
            business.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$OWNED_ID_PREFIX${encoded}_${UUID.randomUUID()}"
    }

    private fun decodeBusiness(appId: String): String? {
        if (!appId.startsWith(OWNED_ID_PREFIX)) return null
        val encodedAndSuffix = appId.removePrefix(OWNED_ID_PREFIX)
        val separator = encodedAndSuffix.lastIndexOf('_')
        if (separator <= 0 || separator >= encodedAndSuffix.lastIndex) return null
        return runCatching {
            String(
                Base64.decode(
                    encodedAndSuffix.substring(0, separator),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                ),
                Charsets.UTF_8,
            ).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun encodeTitle(title: String): String = JSONObject()
        .put("default", title)
        .put("zh_CN", title)
        .put("zh_TW", title)
        .toString()

    private fun decodeTitle(raw: String): String {
        if (raw.isBlank()) return raw
        return runCatching {
            val json = JSONObject(raw)
            val locale = Locale.getDefault()
            val exact = "${locale.language}_${locale.country}"
            json.optString(exact)
                .ifBlank { json.optString(locale.language) }
                .ifBlank { json.optString("default") }
                .ifBlank { raw }
        }.getOrDefault(raw)
    }

    private fun failure(message: String) = RearAppOperationResult(
        success = false,
        error = message,
    )
}
