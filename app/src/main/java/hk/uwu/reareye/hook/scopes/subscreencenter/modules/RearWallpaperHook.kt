@file:Suppress("UNCHECKED_CAST")

package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Point
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.net.toUri
import androidx.core.view.isEmpty
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveDexKitFieldValue
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.repository.widgettemplate.WidgetTemplateConfigRepository
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.widgetapi.IRearWallpaperApiConnection
import hk.uwu.reareye.widgetapi.IRearWallpaperApiService
import hk.uwu.reareye.widgetapi.RearWallpaperApiContract
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleCodec
import hk.uwu.reareye.widgetapi.RearWidgetTemplateConfigState
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import org.luckypray.dexkit.result.FieldData
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@OptIn(DexKitExperimentalApi::class)
class RearWallpaperHook : YukiBaseHooker() {

    companion object {
        private const val TAG = "REAREye-RearWallpaper"
        private const val RETRY_SWITCH_DELAY_MS = 350L
        private const val IMPORT_RES_PREFIX = "reareye_import_"
        private const val IMPORT_RES_TYPE = "REAREye"
        private const val DEFAULT_RES_SUB_TYPE = "reareye_import"
        private const val SELECTED_WALLPAPER_ID_CACHE_KEY = "REAR_WALLPAPER_SELECTED_ID"
        private const val MAX_IMPORT_BYTES = 200L * 1024L * 1024L
        private const val MAX_PREVIEW_BYTES = 20L * 1024L * 1024L
        private const val ZIP_PREVIEW_PREFIX = "zip-preview://"
        private const val ZIP_PREVIEW_SEPARATOR = "!/"
        private const val PREVIEW_CAPTURE_DELAY_MS = 700L
        private const val PREVIEW_CAPTURE_TIMEOUT_MS = 3000L
        private const val OFFSCREEN_CAPTURE_INITIAL_DELAY_MS = 450L
        private const val OFFSCREEN_CAPTURE_RETRY_INTERVAL_MS = 120L
        private const val OFFSCREEN_CAPTURE_TIMEOUT_MS = 4500L
        private const val MAIN_PANEL_SAVE_SELECTION_METHOD_CACHE_KEY =
            "SSC_MAIN_PANEL_SAVE_SELECTION_METHOD"
        private const val MAIN_PANEL_SELECT_METHOD_CACHE_KEY = "SSC_MAIN_PANEL_SELECT_METHOD"
        private const val LAUNCHER_MAIN_PANEL_FIELD_CACHE_KEY = "SSC_LAUNCHER_MAIN_PANEL_FIELD"
        private const val LAUNCHER_MAIN_HANDLER_FIELD_CACHE_KEY = "SSC_LAUNCHER_MAIN_HANDLER_FIELD"
        private const val MAIN_PANEL_EDIT_MODE_FIELD_CACHE_KEY = "SSC_MAIN_PANEL_EDIT_MODE_FIELD"
        private const val MAIN_PANEL_RESUMED_FIELD_CACHE_KEY = "SSC_MAIN_PANEL_RESUMED_FIELD"
        private const val MAIN_PANEL_AOD_FIELD_CACHE_KEY = "SSC_MAIN_PANEL_AOD_FIELD"
        private const val MAIN_PANEL_SELECTED_INDEX_FIELD_CACHE_KEY =
            "SSC_MAIN_PANEL_SELECTED_INDEX_FIELD"
        private const val MAIN_PANEL_WIDGET_LIST_FIELD_CACHE_KEY =
            "SSC_MAIN_PANEL_WIDGET_LIST_FIELD"
        private const val SUBSCREEN_WIDGET_FACTORY_METHOD_CACHE_KEY = "SSC_WIDGET_FACTORY_METHOD"
        private const val SUBSCREEN_WIDGET_ID_FIELD_CACHE_KEY = "SSC_WIDGET_ID_FIELD"
        private const val SUBSCREEN_WIDGET_SPEC_FIELD_CACHE_KEY = "SSC_WIDGET_SPEC_FIELD"
        private const val SUBSCREEN_WIDGET_EXTRAS_FIELD_CACHE_KEY = "SSC_WIDGET_EXTRAS_FIELD"
        private const val SUBSCREEN_WIDGET_HOST_FIELD_CACHE_KEY = "SSC_WIDGET_HOST_FIELD"
        private const val SUBSCREEN_WIDGET_PREVIEW_MODE_FIELD_CACHE_KEY =
            "SSC_WIDGET_PREVIEW_MODE_FIELD"
        private const val SUBSCREEN_WIDGET_CLEANUP_METHOD_CACHE_KEY =
            "SSC_WIDGET_CLEANUP_METHOD"
        private const val SUBSCREEN_WIDGET_SET_EDIT_MODE_METHOD_CACHE_KEY =
            "SSC_WIDGET_SET_EDIT_MODE_METHOD"
        private const val SUBSCREEN_WIDGET_CREATE_VIEW_METHOD_CACHE_KEY =
            "SSC_WIDGET_CREATE_VIEW_METHOD"
        private const val SUBSCREEN_WIDGET_SET_AOD_METHOD_CACHE_KEY =
            "SSC_WIDGET_SET_AOD_METHOD"
        private const val SUBSCREEN_WIDGET_RESUME_METHOD_CACHE_KEY =
            "SSC_WIDGET_RESUME_METHOD"
        private const val WALLPAPER_SPEC_ID_FIELD_CACHE_KEY = "SSC_WALLPAPER_SPEC_ID_FIELD"
        private const val WALLPAPER_SPEC_EXTRAS_FIELD_CACHE_KEY = "SSC_WALLPAPER_SPEC_EXTRAS_FIELD"
        private const val PREF_STORE_CLASS_CACHE_KEY = "SSC_PREF_STORE_CLASS"
        private const val PREF_STORE_INSTANCE_FIELD_CACHE_KEY = "SSC_PREF_STORE_INSTANCE_FIELD"
        private const val PREF_STORE_LOAD_SPECS_METHOD_CACHE_KEY =
            "SSC_PREF_STORE_LOAD_SPECS_METHOD"
        private const val PREF_STORE_READ_VALUE_METHOD_CACHE_KEY =
            "SSC_PREF_STORE_READ_VALUE_METHOD"
        private const val PREF_STORE_WRITE_VALUE_METHOD_CACHE_KEY =
            "SSC_PREF_STORE_WRITE_VALUE_METHOD"
        private const val WALLPAPER_RUNTIME_LIST_METHOD_CACHE_KEY =
            "SSC_WALLPAPER_RUNTIME_LIST_METHOD"
        private const val DEVICE_CONFIG_CLASS_CACHE_KEY = "SSC_DEVICE_CONFIG_CLASS"
        private const val DEVICE_CONFIG_RENDER_SIZE_FIELD_CACHE_KEY =
            "SSC_DEVICE_CONFIG_RENDER_SIZE_FIELD"
        private const val DEVICE_CONFIG_LOCALE_SUFFIX_FIELD_CACHE_KEY =
            "SSC_DEVICE_CONFIG_LOCALE_SUFFIX_FIELD"
        private const val FALLBACK_MAIN_PANEL_CLASS = "com.xiaomi.subscreencenter.MainPanel"

        @Volatile
        private var cachedNextSwitchAtMillis: Long = Long.MIN_VALUE

        @Volatile
        private var cachedScheduleConfig: ScheduleConfig? = null
    }

    private data class WallpaperEntry(
        val wallpaperId: Int,
        val title: String,
        val name: String,
        val description: String,
        val author: String,
        val designer: String,
        val resSubType: String,
        val imported: Boolean,
        val editable: Boolean,
        val thirdParties: Boolean,
        val supportAon: Boolean,
        val templatePath: String?,
        val templateConfigPath: String?,
        val templateConfigAvailable: Boolean,
        val templateConfigCustomized: Boolean,
        val previewPath: String?,
        val previewSignature: String,
        val widget: Any,
    )

    private data class RuntimeWallpaperRecord(
        val item: JSONObject,
        val resId: String,
        val applyId: String,
        val wallpaperId: Int,
        val resLocalPath: String?,
        val metaPath: String?,
        val metaSnapshotPath: String?,
        val mamlEditConfigPath: String?,
        val previewPath: String?,
        val imported: Boolean,
        val position: Int,
    )

    private data class MetadataValues(
        val titleFallback: String,
        val titleZhCn: String,
        val descriptionFallback: String,
        val descriptionZhCn: String,
        val author: String,
        val designer: String,
        val category: String,
        val resSubType: String,
        val editable: Boolean,
        val thirdParties: Boolean,
        val supportAon: Boolean,
    )

    private data class ResolvedScheduleItem(
        val wallpaperId: Int,
        val runtimeIndex: Int,
        val delayMs: Long,
    )

    private data class ScheduleConfig(
        val enabled: Boolean,
        val scheduleData: String,
    )

    private data class SwitchResult(
        val exists: Boolean,
        val applied: Boolean,
    )

    private val bootstrapReceiverRegistered = AtomicBoolean(false)
    private var hostContext: Context? = null
    private var mainPanel: Any? = null
    private var mainHandler: Handler? = null
    private var dexKitBridge: DexKitCacheBridge.RecyclableBridge? = null
    private var schedulerTask: Runnable? = null
    private val runtimeLock = Any()

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
        require(fieldName.isNotBlank()) { "DexKit failed to resolve field cache=$cacheKey" }
        return fieldName
    }

    override fun onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val versionCode = resolveHookPackageVersionCode(
                context = systemContext,
                packageName = appInfo.packageName,
                sourceDir = appInfo.sourceDir,
            )
            val bridge = createDexKitCacheBridge(
                packageName = appInfo.packageName,
                packageVersionCode = versionCode,
                sourceDir = appInfo.sourceDir,
                dataDir = appInfo.dataDir,
            )
            dexKitBridge = bridge
            val appRef = "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().resolve()
            val launcherRef = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass().resolve()

            appRef.firstMethod {
                name = "attachBaseContext"
                parameterCount = 1
            }.hook().after {
                hostContext = (args[0] as? Context)?.applicationContext ?: (args[0] as? Context)
                registerHookBootstrapReceiver()
            }

            launcherRef.firstMethod {
                name = "onCreate"
                parameterCount = 1
            }.hook().after {
                runCatching {
                    capturePanels(instance)
                    refreshSchedule(forceApply = true)
                }.onFailure {
                    YLog.warn(it)
                }
            }
            val saveSelectionPoint =
                resolveMainPanelSaveSelectionMethod(bridge)
            resolveLauncherMainPanelFieldName()
            resolveLauncherMainHandlerFieldName()
            resolveMainPanelSelectMethod()
            resolveMainPanelEditModeFieldName()
            resolveMainPanelResumedFieldName()
            resolveMainPanelAodFieldName()
            resolveMainPanelSelectedIndexFieldName()
            resolveMainPanelWidgetListFieldName()
            resolveWidgetFactoryMethod()
            resolveWallpaperSpecIdFieldName()
            resolveWallpaperSpecExtrasFieldName()
            resolveWidgetIdFieldName()
            resolveWidgetSpecFieldName()
            resolveWidgetExtrasFieldName()
            resolveWidgetHostFieldName()
            resolveWidgetPreviewModeFieldName()
            resolveWidgetSetEditModeMethod()
            resolveWidgetCreateViewMethod()
            resolveWidgetSetAodMethod()
            resolveWidgetResumeMethod()
            resolveWidgetCleanupMethod()
            resolvePrefStoreClass()
            resolvePrefStoreInstanceFieldName()
            resolvePrefStoreLoadSpecsMethod()
            resolvePrefStoreReadValueMethod()
            resolvePrefStoreWriteValueMethod()
            resolveWallpaperRuntimeListMethod()
            resolveDeviceConfigClass()
            resolveDeviceConfigRenderSizeFieldName()
            resolveDeviceConfigLocaleSuffixFieldName()

            launcherRef.firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hook().after {
                runCatching {
                    capturePanels(instance)
                    refreshSchedule(forceApply = true)
                }.onFailure {
                    YLog.warn(it)
                }
            }

            launcherRef.firstMethod {
                name = "onPause"
                parameterCount = 0
            }.hook().before {
                debugLog("launcher onPause keep scheduler nextAt=${readNextSwitchAt()}")
            }

            launcherRef.firstMethod {
                name = "onDestroy"
                parameterCount = 0
            }.hook().before {
                stopScheduler()
                mainPanel = null
                mainHandler = null
            }

            saveSelectionPoint.className.toClass().resolve().firstMethod {
                name = saveSelectionPoint.methodName
                parameterCount = 0
            }.hook().after {
                updateSelectedWallpaperIdFromPanel(instance)
            }
        }
    }

    private val hookBinder = object : IRearWallpaperApiService.Stub() {
        override fun getCatalog(): Bundle {
            enforceCallerPermission()
            return buildCatalogBundle()
        }

        override fun getPreview(wallpaperId: Int): ByteArray? {
            enforceCallerPermission()
            val entry =
                loadWallpaperEntries().firstOrNull { it.wallpaperId == wallpaperId } ?: return null
            return loadPreviewBytes(entry.previewPath)
        }

        override fun switchWallpaper(wallpaperId: Int): Boolean {
            enforceCallerPermission()
            return switchWallpaperInternal(wallpaperId).exists
        }

        override fun syncSchedule(enabled: Boolean, scheduleData: String?): Boolean {
            enforceCallerPermission()
            updateScheduleConfig(enabled, scheduleData)
            persistNextSwitchAt(0L)
            refreshSchedule(forceApply = true)
            return true
        }

        override fun importWallpaperPackage(
            packageFd: ParcelFileDescriptor?,
            displayNameHint: String?,
            previewUri: String?,
            options: Bundle?,
        ): Bundle {
            enforceCallerPermission()
            return importWallpaperPackageInternal(
                packageFd = packageFd,
                displayNameHint = displayNameHint,
                previewUri = previewUri,
                options = options,
            )
        }

        override fun updateWallpaperMetadata(
            wallpaperId: Int,
            previewUri: String?,
            options: Bundle?,
        ): Bundle {
            enforceCallerPermission()
            return updateWallpaperMetadataInternal(wallpaperId, previewUri, options)
        }

        override fun updateWallpaperPackage(
            wallpaperId: Int,
            packageFd: ParcelFileDescriptor?,
            displayNameHint: String?,
            previewUri: String?,
            options: Bundle?,
        ): Bundle {
            enforceCallerPermission()
            return updateWallpaperPackageInternal(
                wallpaperId = wallpaperId,
                packageFd = packageFd,
                displayNameHint = displayNameHint,
                previewUri = previewUri,
                options = options,
            )
        }

        override fun generateWallpaperPreview(wallpaperId: Int): Bundle {
            enforceCallerPermission()
            return generateWallpaperPreviewInternal(wallpaperId)
        }

        override fun deleteWallpaper(wallpaperId: Int): Bundle {
            enforceCallerPermission()
            return deleteWallpaperInternal(wallpaperId)
        }

        override fun resolveTemplateConfigState(
            wallpaperId: Int,
            currentOneConfigJson: String?,
        ): Bundle {
            enforceCallerPermission()
            val state = resolveWallpaperTemplateConfigStateModel(
                wallpaperId = wallpaperId,
                currentOneConfigJson = currentOneConfigJson?.trim(),
            )
            return state?.toBundle() ?: Bundle()
        }

        override fun saveTemplateConfig(wallpaperId: Int, oneConfigJson: String?): Bundle {
            enforceCallerPermission()
            return saveWallpaperTemplateConfigInternal(
                wallpaperId = wallpaperId,
                oneConfigJson = oneConfigJson?.trim(),
            )
        }
    }

    private fun resolveMainPanelSaveSelectionMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = MAIN_PANEL_SAVE_SELECTION_METHOD_CACHE_KEY,
        ) {
            findMethod {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    paramCount(0)
                    returnType = "void"
                    usingStrings("Save user select, new index = ", "user_select")
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(FALLBACK_MAIN_PANEL_CLASS, "")
            .also { require(it.methodName.isNotBlank()) { "DexKit failed to resolve save selection method" } }
    }

    private val hookBootstrapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RearWallpaperApiContract.ACTION_REQUEST_HOOK_SERVICE) return
            val callbackBinder = intent
                .getBundleExtra(RearWallpaperApiContract.Extras.BUNDLE)
                ?.getBinder(RearWallpaperApiContract.Extras.BINDER)
            val callback = IRearWallpaperApiConnection.Stub.asInterface(callbackBinder)
            val forceSync =
                intent.getBooleanExtra(RearWallpaperApiContract.Extras.FORCE_SYNC, false)
            if (forceSync) {
                refreshSchedule(forceApply = true)
            }
            runCatching {
                callback?.onServiceConnected(hookBinder)
            }.onFailure(YLog::error)
        }
    }

    private fun registerHookBootstrapReceiver() {
        if (!bootstrapReceiverRegistered.compareAndSet(false, true)) return
        val ctx = hostContext ?: run {
            bootstrapReceiverRegistered.set(false)
            return
        }
        runCatching {
            ContextCompat.registerReceiver(
                ctx,
                hookBootstrapReceiver,
                IntentFilter(RearWallpaperApiContract.ACTION_REQUEST_HOOK_SERVICE),
                RearWallpaperApiContract.SERVICE_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure {
            bootstrapReceiverRegistered.set(false)
            YLog.error(it)
        }
    }

    private fun enforceCallerPermission() {
        val ctx = hostContext
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (ctx == null) {
            throw SecurityException("context not ready for permission check")
        }
        val granted = ctx.checkPermission(
            RearWallpaperApiContract.SERVICE_PERMISSION,
            Binder.getCallingPid(),
            uid,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw SecurityException(
                "caller uid=$uid requires ${RearWallpaperApiContract.SERVICE_PERMISSION}"
            )
        }
    }

    private fun buildCatalogBundle(): Bundle {
        val entries = loadWallpaperEntries()
        val currentIndex = readCurrentSelectionIndex(entries.lastIndex)
        val currentWallpaperId = entries.getOrNull(currentIndex)?.wallpaperId
        val itemBundles = ArrayList<Bundle>(entries.size)
        entries.forEach { entry ->
            itemBundles += Bundle().apply {
                putInt(RearWallpaperApiContract.BundleKeys.WALLPAPER_ID, entry.wallpaperId)
                putString(RearWallpaperApiContract.BundleKeys.TITLE, entry.title)
                putString(RearWallpaperApiContract.BundleKeys.NAME, entry.name)
                putString(RearWallpaperApiContract.BundleKeys.DESCRIPTION, entry.description)
                putString(RearWallpaperApiContract.BundleKeys.AUTHOR, entry.author)
                putString(RearWallpaperApiContract.BundleKeys.DESIGNER, entry.designer)
                putString(RearWallpaperApiContract.BundleKeys.RES_SUB_TYPE, entry.resSubType)
                putBoolean(RearWallpaperApiContract.BundleKeys.IMPORTED, entry.imported)
                putBoolean(RearWallpaperApiContract.BundleKeys.CAN_EDIT_METADATA, entry.imported)
                putBoolean(RearWallpaperApiContract.BundleKeys.CAN_DELETE, entry.imported)
                putBoolean(RearWallpaperApiContract.BundleKeys.EDITABLE, entry.editable)
                putBoolean(RearWallpaperApiContract.BundleKeys.THIRD_PARTIES, entry.thirdParties)
                putBoolean(RearWallpaperApiContract.BundleKeys.SUPPORT_AON, entry.supportAon)
                putBoolean(
                    RearWallpaperApiContract.BundleKeys.TEMPLATE_CONFIG_AVAILABLE,
                    entry.templateConfigAvailable,
                )
                putBoolean(
                    RearWallpaperApiContract.BundleKeys.TEMPLATE_CONFIG_CUSTOMIZED,
                    entry.templateConfigCustomized,
                )
                putBoolean(
                    RearWallpaperApiContract.BundleKeys.PREVIEW_AVAILABLE,
                    !entry.previewPath.isNullOrBlank(),
                )
                putString(
                    RearWallpaperApiContract.BundleKeys.PREVIEW_SIGNATURE,
                    entry.previewSignature
                )
            }
        }
        return Bundle().apply {
            putParcelableArrayList(RearWallpaperApiContract.BundleKeys.ITEMS, itemBundles)
            putInt(RearWallpaperApiContract.BundleKeys.CURRENT_INDEX, currentIndex)
            if (currentWallpaperId != null) {
                putInt(RearWallpaperApiContract.BundleKeys.CURRENT_WALLPAPER_ID, currentWallpaperId)
            }
        }
    }

    private fun capturePanels(launcherInstance: Any?) {
        val resolver = launcherInstance?.asResolver() ?: return
        mainPanel = runCatching {
            resolver.firstField { name = resolveLauncherMainPanelFieldName() }.get()
        }.getOrNull()
        mainHandler = runCatching {
            resolver.firstField { name = resolveLauncherMainHandlerFieldName() }.get() as? Handler
        }.getOrNull()
    }

    private fun refreshSchedule(forceApply: Boolean) {
        stopScheduler()
        val scheduleConfig = readScheduleConfig()
        if (!scheduleConfig.enabled) {
            persistNextSwitchAt(0L)
            debugLog("refreshSchedule disabled force=$forceApply")
            return
        }

        val entries = loadWallpaperEntries()
        if (entries.isEmpty()) {
            persistNextSwitchAt(0L)
            debugLog("refreshSchedule skipped: no wallpaper entries force=$forceApply")
            return
        }

        val resolvedSchedule = loadResolvedSchedule(entries, scheduleConfig)
        if (resolvedSchedule.isEmpty()) {
            persistNextSwitchAt(0L)
            debugLog("refreshSchedule skipped: resolved schedule empty force=$forceApply")
            return
        }

        val now = System.currentTimeMillis()
        val currentIndex = readCurrentSelectionIndex(entries.lastIndex)
        val currentId = entries.getOrNull(currentIndex)?.wallpaperId
        val currentPos = resolvedSchedule.indexOfFirst { it.wallpaperId == currentId }
        val nextAt = readNextSwitchAt()
        debugLog(
            "refreshSchedule force=$forceApply currentIndex=$currentIndex currentId=$currentId currentPos=$currentPos nextAt=$nextAt schedule=${resolvedSchedule.joinToString { "${it.wallpaperId}:${it.delayMs}" }}"
        )

        if (currentPos < 0) {
            val first = resolvedSchedule.first()
            val result = switchToResolved(first, entries)
            val nextAt = now + if (result.applied) first.delayMs else RETRY_SWITCH_DELAY_MS
            persistNextSwitchAt(nextAt)
            debugLog("refreshSchedule no current match -> switch first=${first.wallpaperId} applied=${result.applied} nextAt=$nextAt")
            scheduleAt(nextAt)
            return
        }

        val currentItem = resolvedSchedule[currentPos]
        if (nextAt <= 0L) {
            val dueAt = now + currentItem.delayMs
            persistNextSwitchAt(dueAt)
            debugLog("refreshSchedule initialized nextAt=$dueAt current=${currentItem.wallpaperId} delay=${currentItem.delayMs}")
            scheduleAt(dueAt)
            return
        }

        if (nextAt <= now) {
            val nextPos = (currentPos + 1).floorMod(resolvedSchedule.size)
            val result = switchToResolved(resolvedSchedule[nextPos], entries)
            val dueAt = now + if (result.applied) {
                resolvedSchedule[nextPos].delayMs
            } else {
                RETRY_SWITCH_DELAY_MS
            }
            persistNextSwitchAt(dueAt)
            debugLog("refreshSchedule due -> switch next=${resolvedSchedule[nextPos].wallpaperId} applied=${result.applied} dueAt=$dueAt")
            scheduleAt(dueAt)
            return
        }

        if (forceApply) {
            debugLog("refreshSchedule force keep existing nextAt=$nextAt")
            scheduleAt(nextAt)
            return
        }

        debugLog("refreshSchedule waiting nextAt=$nextAt delay=${nextAt - now}")
        scheduleAt(nextAt)
    }

    private fun scheduleAt(triggerAt: Long) {
        stopScheduler()
        val handler = mainHandler ?: return
        val delayMs = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
        debugLog("scheduleAt triggerAt=$triggerAt delayMs=$delayMs")
        schedulerTask = Runnable {
            debugLog("scheduleAt fired triggerAt=$triggerAt now=${System.currentTimeMillis()}")
            refreshSchedule(forceApply = false)
        }
        handler.postDelayed(schedulerTask!!, delayMs)
    }

    private fun stopScheduler() {
        schedulerTask?.let { task -> mainHandler?.removeCallbacks(task) }
        if (schedulerTask != null) debugLog("stopScheduler removed pending task")
        schedulerTask = null
    }

    private fun loadResolvedSchedule(
        entries: List<WallpaperEntry> = loadWallpaperEntries(),
        scheduleConfig: ScheduleConfig = readScheduleConfig(),
    ): List<ResolvedScheduleItem> {
        val byId = entries.associateBy { it.wallpaperId }
        return RearWallpaperScheduleCodec.parse(
            scheduleConfig.scheduleData
        ).mapNotNull { item ->
            val entry = byId[item.wallpaperId] ?: return@mapNotNull null
            ResolvedScheduleItem(
                wallpaperId = item.wallpaperId,
                runtimeIndex = entries.indexOf(entry),
                delayMs = item.delayMs,
            )
        }
    }

    private fun switchWallpaperInternal(wallpaperId: Int): SwitchResult {
        val entries = loadWallpaperEntries()
        val target = entries.firstOrNull { it.wallpaperId == wallpaperId }
            ?: return SwitchResult(exists = false, applied = false)
        val result = switchToResolved(
            item = ResolvedScheduleItem(
                wallpaperId = wallpaperId,
                runtimeIndex = entries.indexOf(target),
                delayMs = RearWallpaperScheduleCodec.DEFAULT_DELAY_MS,
            ),
            entries = entries,
        )
        resetNextSwitchAtForCurrent(wallpaperId, entries)
        debugLog("switchWallpaperInternal wallpaperId=$wallpaperId exists=true applied=${result.applied}")
        return result
    }

    private fun switchToResolved(
        item: ResolvedScheduleItem,
        entries: List<WallpaperEntry>
    ): SwitchResult {
        if (entries.isEmpty()) return SwitchResult(exists = false, applied = false)
        if (isMainPanelEditing()) {
            debugLog("switchToResolved blocked by editMode wallpaperId=${item.wallpaperId}")
            return SwitchResult(exists = true, applied = false)
        }

        val targetIndex = item.runtimeIndex.coerceIn(0, entries.lastIndex)
        persistSelectionIndex(targetIndex)
        persistSelectedWallpaperId(item.wallpaperId)
        debugLog("switchToResolved wallpaperId=${item.wallpaperId} runtimeIndex=${item.runtimeIndex} targetIndex=$targetIndex")

        val widgets = entries.map { it.widget }
        var applied = false
        mainPanel?.let { panel ->
            applied = dispatchSelection(panel, widgets, targetIndex) || false
        }
        debugLog("switchToResolved result wallpaperId=${item.wallpaperId} applied=$applied main=${mainPanel != null}")
        return SwitchResult(exists = true, applied = applied)
    }

    private fun isMainPanelEditing(): Boolean {
        val panel = mainPanel ?: return false
        return runCatching {
            panel.asResolver().firstField {
                name = resolveMainPanelEditModeFieldName()
            }.get() as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun dispatchSelection(panel: Any, widgets: List<Any>, index: Int): Boolean {
        val action = Runnable {
            runCatching {
                val selectPoint = resolveMainPanelSelectMethod()
                panel.asResolver().firstMethod {
                    name = selectPoint.methodName
                    parameterCount = 2
                }.invoke(widgets, index)
                debugLog("dispatchSelection success panel=${panel.javaClass.name} index=$index widgets=${widgets.size}")
            }.onFailure(YLog::error)
        }
        val handler = mainHandler
        return if (handler != null) {
            handler.post(action)
        } else {
            action.run()
            true
        }
    }

    private fun updateSelectedWallpaperIdFromPanel(panel: Any?) {
        val resolver = panel?.asResolver() ?: return
        runCatching {
            val index = resolver.firstField {
                name = resolveMainPanelSelectedIndexFieldName()
            }.get() as? Int ?: return
            val specs = resolver.firstField {
                name = resolveMainPanelWidgetListFieldName()
            }.get() as? List<*> ?: return
            val selectedId = specs.getOrNull(index)?.wallpaperSpecId() ?: return
            persistSelectedWallpaperId(selectedId)
            debugLog("updateSelectedWallpaperIdFromPanel index=$index wallpaperId=$selectedId")
        }.onFailure(YLog::warn)
    }

    private fun loadWallpaperEntries(): List<WallpaperEntry> {
        val specList = loadWallpaperSpecs()
        if (specList.isEmpty()) return emptyList()

        val localeSuffix = readLocalePreviewSuffix()
        val runtimeRecords = readRuntimeRecords().associateBy { it.wallpaperId }
        return buildList {
            specList.forEach { spec ->
                val widget = createWallpaperWidget(spec) ?: return@forEach

                val wallpaperId = spec.wallpaperSpecId()
                if (wallpaperId == null) {
                    debugLog("loadWallpaperEntries skip spec with unresolved wallpaperId class=${spec.javaClass.name}")
                    return@forEach
                }
                val extras = spec.wallpaperSpecExtras()
                val runtimeRecord = runtimeRecords[wallpaperId]
                val templatePath = runtimeRecord?.resLocalPath
                    ?.takeIf { hasEditableTemplateConfig(it) }
                    ?: extractMamlWidgetTemplatePath(spec)
                    ?: extractMamlWidgetTemplatePath(widget)
                val previewPath = resolveWallpaperPreviewPath(
                    widget = widget,
                    extras = extras,
                    localeSuffix = localeSuffix,
                    runtimeRecord = runtimeRecord,
                    templatePath = templatePath,
                )
                val metadata = runtimeRecord?.readMetadataValues()
                val templateConfigPath = runtimeRecord?.mamlEditConfigPath
                    ?.takeIf { it.isNotBlank() }
                    ?: extractMamlWidgetConfigPath(widget)
                val templateConfigAvailable = hasEditableTemplateConfig(templatePath)
                add(
                    WallpaperEntry(
                        wallpaperId = wallpaperId,
                        title = metadata?.category
                            ?: extras?.getString("title").orEmpty().ifBlank { "Wallpaper" },
                        name = metadata?.preferredTitle()
                            ?: extras?.getString("resName").orEmpty().ifBlank { "unknown" },
                        description = metadata?.preferredDescription().orEmpty(),
                        author = metadata?.author.orEmpty(),
                        designer = metadata?.designer.orEmpty(),
                        resSubType = metadata?.resSubType.orEmpty(),
                        imported = runtimeRecord?.imported ?: false,
                        editable = metadata?.editable ?: false,
                        thirdParties = metadata?.thirdParties ?: false,
                        supportAon = metadata?.supportAon ?: false,
                        templatePath = templatePath,
                        templateConfigPath = templateConfigPath,
                        templateConfigAvailable = templateConfigAvailable,
                        templateConfigCustomized = templateConfigAvailable && !templateConfigPath.isNullOrBlank(),
                        previewPath = previewPath,
                        previewSignature = buildPreviewSignature(previewPath),
                        widget = widget,
                    )
                )
            }
        }
    }

    private fun createWallpaperWidget(spec: Any): Any? {
        return runCatching {
            val factoryPoint = resolveWidgetFactoryMethod()
            factoryPoint.className.toClass().resolve().firstMethod {
                name = factoryPoint.methodName
                parameterCount = 1
            }.invoke(spec)
        }.onFailure(YLog::warn).getOrNull()
    }

    private fun hasEditableTemplateConfig(templatePath: String?): Boolean {
        val normalized = templatePath?.takeIf { it.isNotBlank() } ?: return false
        return WidgetTemplateConfigRepository.loadSchema(normalized)
            ?.items
            ?.isNotEmpty()
            ?: false
    }

    private fun resolveWallpaperPreviewPath(
        widget: Any,
        extras: Bundle?,
        localeSuffix: String?,
        runtimeRecord: RuntimeWallpaperRecord?,
        templatePath: String?,
    ): String? {
        val preferredCandidates = buildList {
            addAll(extras.previewPathCandidates(localeSuffix))
            runtimeRecord?.previewPath?.let(::add)
        }
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        preferredCandidates.firstOrNull(::isReadablePreviewPath)?.let { return it }
        extractStringFields(widget).firstOrNull(::isReadablePreviewPath)?.let { return it }

        val packagePath = templatePath?.takeIf { it.isNotBlank() }
            ?: extractMamlWidgetTemplatePath(widget)
        if (packagePath != null) {
            preferredCandidates.firstNotNullOfOrNull { candidate ->
                resolvePackagePreviewPath(packagePath, candidate)
            }?.let { return it }
            explicitTemplatePreviewPaths(packagePath).firstNotNullOfOrNull { candidate ->
                resolvePackagePreviewPath(packagePath, candidate)
            }?.let { return it }
        }

        return null
    }

    private fun explicitTemplatePreviewPaths(templatePath: String): List<String> {
        val schema = WidgetTemplateConfigRepository.loadSchema(templatePath) ?: return emptyList()
        return WidgetTemplateConfigRepository.imagePreviewValues(schema)
    }

    private fun extractMamlWidgetTemplatePath(widget: Any): String? {
        return extractStringFields(widget).firstOrNull { candidate ->
            val file = File(candidate)
            file.isFile && WidgetTemplateConfigRepository.loadSchema(file.absolutePath) != null
        }
    }

    private fun resolveWallpaperTemplatePath(wallpaperId: Int): String? {
        readRuntimeRecords()
            .firstOrNull { it.wallpaperId == wallpaperId }
            ?.resLocalPath
            ?.takeIf { hasEditableTemplateConfig(it) }
            ?.let { return it }

        loadWallpaperSpecs().forEach { spec ->
            if (spec.wallpaperSpecId() != wallpaperId) return@forEach
            extractMamlWidgetTemplatePath(spec)?.let { return it }
            createWallpaperWidget(spec)
                ?.let(::extractMamlWidgetTemplatePath)
                ?.let { return it }
        }
        return null
    }

    private fun extractMamlWidgetConfigPath(widget: Any): String? {
        return extractStringFields(widget).firstOrNull { candidate ->
            val file = File(candidate)
            file.isFile && file.name.endsWith(".json", ignoreCase = true) &&
                    readOneConfigJson(file.absolutePath) != null
        }
    }

    private fun extractStringFields(target: Any): List<String> {
        val values = ArrayList<String>()
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    (field.get(target) as? String)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(values::add)
                }
            }
            current.declaredMethods.forEach { method ->
                if (method.parameterTypes.isNotEmpty() || method.returnType != String::class.java) {
                    return@forEach
                }
                runCatching {
                    method.isAccessible = true
                    (method.invoke(target) as? String)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(values::add)
                }
            }
            current = current.superclass
        }
        return values.distinct()
    }

    private fun resolveWidgetFactoryMethod(): DexKitMethodInjectionPoint {
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for widget factory")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = SUBSCREEN_WIDGET_FACTORY_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    paramCount(1)
                    usingStrings("snapshotPath_", "snapshotPath", "__PIN_CONTENT_TEXT__")
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint("", "")
        require(point.className.isNotBlank() && point.methodName.isNotBlank()) {
            "DexKit failed to resolve widget factory method"
        }
        return point
    }

    private fun resolveWidgetClassName(): String {
        return resolveWidgetFactoryMethod().className
    }

    private fun resolveWallpaperSpecClassName(): String {
        val point = resolveWidgetFactoryMethod()
        return runCatching {
            point.className.toClass().resolve().firstMethod {
                name = point.methodName
                parameterCount = 1
            }.self.parameterTypes.firstOrNull()?.name
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: error("DexKit failed to resolve wallpaper spec class")
    }

    private fun resolveWallpaperSpecIdFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = WALLPAPER_SPEC_ID_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveWallpaperSpecClassName().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveWallpaperSpecClassName()
                    // FIXED: Removed strict Modifier.PUBLIC or Modifier.FINAL check
                    type = "int"
                    readMethods {
                        add {
                            usingStrings("Widget{mId=", ", mType=", ", mChangedFlag=")
                        }
                    }
                }
            }.firstOrNull() // FIXED: Relaxed finder to prevent crashes on strict matching
        }
    }

    private fun resolveWallpaperSpecExtrasFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = WALLPAPER_SPEC_EXTRAS_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveWallpaperSpecClassName().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveWallpaperSpecClassName()
                    type = "android.os.Bundle"
                    readMethods {
                        add {
                            usingStrings("Widget{mId=", ", mType=", ", mExtra=")
                        }
                        add {
                            usingStrings("__PIN_CONTENT_IMAGE_COMPRESS__")
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveMainPanelClassName(): String {
        return resolveMainPanelSelectMethod().className.ifBlank { FALLBACK_MAIN_PANEL_CLASS }
    }

    private fun resolveLauncherMainPanelFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = LAUNCHER_MAIN_PANEL_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    declaredClass = "com.xiaomi.subscreencenter.SubScreenLauncher"
                    type = resolveMainPanelClassName()
                }
            }.singleOrNull()
        }
    }

    private fun resolveLauncherMainHandlerFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = LAUNCHER_MAIN_HANDLER_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    declaredClass = "com.xiaomi.subscreencenter.SubScreenLauncher"
                    type = "android.os.Handler"
                }
            }.singleOrNull()
        }
    }

    private fun resolveMainPanelEditModeFieldName(): String {
        val mainPanelClass = resolveMainPanelClassName()
        return resolveCachedFieldName(
            cacheKey = MAIN_PANEL_EDIT_MODE_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    declaredClass = mainPanelClass
                    type = "boolean"
                    writeMethods {
                        add {
                            usingStrings("enterEditingMode")
                            paramCount(1)
                            returnType = "void"
                        }
                        add {
                            declaredClass = mainPanelClass
                            usingStrings("exitEditingMode")
                            paramCount(3)
                            returnType = "void"
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveMainPanelResumedFieldName(): String {
        val mainPanelClass = resolveMainPanelClassName()
        return resolveCachedFieldName(
            cacheKey = MAIN_PANEL_RESUMED_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(mainPanelClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = mainPanelClass
                    type = "boolean"
                    writeMethods {
                        add {
                            usingStrings("onActivityVisibleChangedImpl resume = ")
                            paramCount(0)
                            returnType = "void"
                        }
                    }
                    readMethods {
                        add {
                            name = "onConfigurationChanged"
                            paramCount(1)
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveMainPanelAodFieldName(): String {
        val mainPanelClass = resolveMainPanelClassName()
        return resolveCachedFieldName(
            cacheKey = MAIN_PANEL_AOD_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(mainPanelClass.substringBeforeLast('.'))
                matcher {
                    declaredClass = mainPanelClass
                    type = "boolean"
                    writeMethods {
                        add {
                            usingStrings("MainPanel", "onAodStateChangedImpl: ")
                            paramCount(0)
                            returnType = "void"
                        }
                    }
                }
            }.filterNot { it.fieldName == resolveMainPanelResumedFieldName() }.singleOrNull()
        }
    }

    private fun resolveMainPanelSelectedIndexFieldName(): String {
        val mainPanelClass = resolveMainPanelClassName()
        return resolveCachedFieldName(
            cacheKey = MAIN_PANEL_SELECTED_INDEX_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    declaredClass = mainPanelClass
                    type = "int"
                    readMethods {
                        add {
                            declaredClass = mainPanelClass
                            usingStrings("Save user select, new index = ", "user_select")
                            paramCount(0)
                            returnType = "void"
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveMainPanelWidgetListFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = MAIN_PANEL_WIDGET_LIST_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    declaredClass = resolveMainPanelClassName()
                    type = "java.util.List"
                }
            }.singleOrNull()
        }
    }

    private fun resolveWidgetIdFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SUBSCREEN_WIDGET_ID_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveWidgetClassName().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveWidgetClassName()
                    modifiers = Modifier.FINAL
                    type = "int"
                }
            }.singleOrNull()
        }
    }

    private fun resolveWidgetSpecFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SUBSCREEN_WIDGET_SPEC_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveWidgetClassName().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveWidgetClassName()
                    type = resolveWallpaperSpecClassName()
                }
            }.singleOrNull()
        }
    }

    private fun resolveWidgetExtrasFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SUBSCREEN_WIDGET_EXTRAS_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveWidgetClassName().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveWidgetClassName()
                    type = "android.os.Bundle"
                }
            }.singleOrNull()
        }
    }

    private fun resolveWidgetHostFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SUBSCREEN_WIDGET_HOST_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveWidgetClassName().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveWidgetClassName()
                    type = "android.widget.FrameLayout"
                }
            }.singleOrNull()
        }
    }

    private fun resolveWidgetPreviewModeFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = SUBSCREEN_WIDGET_PREVIEW_MODE_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveWidgetClassName().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveWidgetClassName()
                    type = "boolean"
                    writeMethods {
                        add {
                            declaredClass = resolveMainPanelClassName()
                            usingStrings("createWidgets: index=", ", targetIndex=", ", new = ")
                            paramCount(5)
                            returnType = "void"
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolvePrefStoreInstanceFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = PREF_STORE_INSTANCE_FIELD_CACHE_KEY,
        ) {
            findMethod {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    declaredClass = resolveMainPanelClassName()
                    usingStrings("Save user select, new index = ", "user_select")
                    paramCount(0)
                    returnType = "void"
                }
            }.singleOrNull()
                ?.usingFields
                ?.firstOrNull { it.field.className == resolvePrefStoreClass() }
                ?.field
        }
    }

    private fun resolveDeviceConfigRenderSizeFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = DEVICE_CONFIG_RENDER_SIZE_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveDeviceConfigClass().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveDeviceConfigClass()
                    modifiers = Modifier.STATIC or Modifier.FINAL
                    type = "android.graphics.Point"
                }
            }.singleOrNull()
        }
    }

    private fun resolveDeviceConfigLocaleSuffixFieldName(): String {
        return resolveCachedFieldName(
            cacheKey = DEVICE_CONFIG_LOCALE_SUFFIX_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(resolveDeviceConfigClass().substringBeforeLast('.'))
                matcher {
                    declaredClass = resolveDeviceConfigClass()
                    modifiers = Modifier.STATIC or Modifier.FINAL
                    type = "java.lang.String"
                    readMethods {
                        add {
                            usingStrings("wallpaper color flag = ")
                        }
                        add {
                            usingStrings("snapshotPath_")
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun resolveWidgetSetEditModeMethod(): DexKitMethodInjectionPoint {
        val widgetClass = resolveWidgetClassName()
        YLog.debug("Widget class $widgetClass")
        val mainPanelClass = resolveMainPanelClassName()
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for widget edit mode")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = SUBSCREEN_WIDGET_SET_EDIT_MODE_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = widgetClass
                    paramTypes("boolean")
                    returnType = "void"
                    callerMethods {
                        add {
                            declaredClass = mainPanelClass
                            returnType = "void"
                            usingStrings("enterEditingMode", "enterEditingModeImpl")
                        }
                    }
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint("", "")
        require(point.className.isNotBlank() && point.methodName.isNotBlank()) {
            "DexKit failed to resolve widget edit mode method"
        }
        return point
    }

    private fun resolveWidgetCreateViewMethod(): DexKitMethodInjectionPoint {
        val widgetClass = resolveWidgetClassName()
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for widget create view")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = SUBSCREEN_WIDGET_CREATE_VIEW_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = widgetClass
                    modifiers(
                        AccessFlagsMatcher(
                            matchType = MatchType.Equals,
                            modifiers = Modifier.PUBLIC or Modifier.FINAL
                        )
                    )
                    paramTypes(Context::class.java)
                    returnType = "android.view.View"
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint("", "")
        require(point.className.isNotBlank() && point.methodName.isNotBlank()) {
            "DexKit failed to resolve widget create view method"
        }
        return point
    }

    private fun resolveWidgetSetAodMethod(): DexKitMethodInjectionPoint {
        val widgetClass = resolveWidgetClassName()
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for widget aod method")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = SUBSCREEN_WIDGET_SET_AOD_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = widgetClass
                    paramTypes("boolean")
                    returnType = "void"
                    usingStrings(
                        "Skipping AOD state for just-woken widget (first time only)",
                        "force_non_aod_state",
                    )
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint("", "")
        require(point.className.isNotBlank() && point.methodName.isNotBlank()) {
            "DexKit failed to resolve widget AOD method"
        }
        return point
    }

    private fun resolveWidgetResumeMethod(): DexKitMethodInjectionPoint {
        val widgetClass = resolveWidgetClassName()
        val mainPanelClass = resolveMainPanelClassName()
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for widget resume method")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = SUBSCREEN_WIDGET_RESUME_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = widgetClass
                    paramTypes()
                    returnType = "void"
                    callerMethods {
                        add {
                            declaredClass = mainPanelClass
                            paramCount(5)
                            returnType = "void"
                            usingStrings("createWidgets: index=", ", targetIndex=", ", new = ")
                        }
                    }
                    invokeMethods {
                        add {
                            declaredClass = widgetClass
                            paramTypes()
                            returnType = "void"
                            usingStrings("trackAssistExpose bundle = ")
                        }
                    }
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint("", "")
        require(point.className.isNotBlank() && point.methodName.isNotBlank()) {
            "DexKit failed to resolve widget resume method"
        }
        return point
    }

    private fun resolveWidgetCleanupMethod(): DexKitMethodInjectionPoint {
        val widgetClass = resolveWidgetClassName()
        val mainPanelClass = resolveMainPanelClassName()
        val setEditModePoint = resolveWidgetSetEditModeMethod()
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for widget cleanup method")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = SUBSCREEN_WIDGET_CLEANUP_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = widgetClass
                    paramTypes()
                    returnType = "void"
                    callerMethods {
                        add {
                            declaredClass = mainPanelClass
                            paramCount(5)
                            returnType = "void"
                            usingStrings("createWidgets: index=", ", targetIndex=", ", new = ")
                        }
                    }
                    invokeMethods {
                        add {
                            declaredClass = setEditModePoint.className
                            name = setEditModePoint.methodName
                            paramTypes("boolean")
                            returnType = "void"
                        }
                    }
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint("", "")
        require(point.className.isNotBlank() && point.methodName.isNotBlank()) {
            "DexKit failed to resolve widget cleanup method"
        }
        return point
    }

    private fun invokeWidgetCleanup(targetWidget: Any) {
        val point = resolveWidgetCleanupMethod()
        targetWidget.asResolver().firstMethod {
            superclass()
            name = point.methodName
            parameterCount = 0
        }.invoke()
    }

    private fun invokeWidgetSetEditMode(targetWidget: Any, editMode: Boolean) {
        val point = resolveWidgetSetEditModeMethod()
        targetWidget.asResolver().firstMethod {
            superclass()
            name = point.methodName
            parameterCount = 1
        }.invoke(editMode)
    }

    private fun invokeWidgetCreateView(targetWidget: Any, context: Context): View? {
        val point = resolveWidgetCreateViewMethod()
        return targetWidget.asResolver().firstMethod {
            superclass()
            name = point.methodName
            parameterCount = 1
        }.invoke<View?>(context)
    }

    private fun invokeWidgetSetAodState(targetWidget: Any, inAod: Boolean) {
        val point = resolveWidgetSetAodMethod()
        targetWidget.asResolver().firstMethod {
            superclass()
            name = point.methodName
            parameterCount = 1
        }.invoke(inAod)
    }

    private fun invokeWidgetResume(targetWidget: Any) {
        val point = resolveWidgetResumeMethod()
        targetWidget.asResolver().firstMethod {
            superclass()
            name = point.methodName
            parameterCount = 0
        }.invoke()
    }

    private fun resolveMainPanelSelectMethod(): DexKitMethodInjectionPoint {
        val bridge =
            dexKitBridge ?: error("DexKit bridge is not ready for main panel select method")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = MAIN_PANEL_SELECT_METHOD_CACHE_KEY,
        ) {
            findMethod {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    paramCount(2)
                    returnType = "void"
                    usingStrings(
                        "SubScreenWidgets is empty, at least one needs to be provided !!!",
                        "onSubScreenWidgetChanged, new widgets size = ",
                    )
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(FALLBACK_MAIN_PANEL_CLASS, "")
        require(point.methodName.isNotBlank()) { "DexKit failed to resolve main panel select method" }
        return point
    }

    private fun resolvePrefStore(): Any? {
        return runCatching {
            resolvePrefStoreClass().toClass().resolve().firstField {
                name = resolvePrefStoreInstanceFieldName()
            }.get()
        }.getOrNull()
    }

    private fun resolvePrefStoreLoadSpecsMethod(): DexKitMethodInjectionPoint {
        val prefStoreClass = resolvePrefStoreClass()
        val bridge = dexKitBridge ?: return DexKitMethodInjectionPoint(prefStoreClass, "e")
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = PREF_STORE_LOAD_SPECS_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = prefStoreClass
                    paramTypes("boolean")
                    returnType = "java.util.List"
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(prefStoreClass, "e")
    }

    private fun resolvePrefStoreReadValueMethod(): DexKitMethodInjectionPoint {
        val prefStoreClass = resolvePrefStoreClass()
        val bridge = dexKitBridge ?: return DexKitMethodInjectionPoint(prefStoreClass, "c")
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = PREF_STORE_READ_VALUE_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = prefStoreClass
                    paramTypes(
                        Class::class.java,
                        Any::class.java,
                        String::class.java,
                    )
                    returnType = "java.lang.Object"
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(prefStoreClass, "c")
    }

    private fun resolvePrefStoreWriteValueMethod(): DexKitMethodInjectionPoint {
        val prefStoreClass = resolvePrefStoreClass()
        val bridge = dexKitBridge ?: return DexKitMethodInjectionPoint(prefStoreClass, "j")
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = PREF_STORE_WRITE_VALUE_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    declaredClass = prefStoreClass
                    paramCount(2)
                    returnType = "void"
                    callerMethods {
                        add {
                            declaredClass = resolveMainPanelClassName()
                            paramTypes()
                            returnType = "void"
                            usingStrings("Save user select, new index = ", "user_select")
                        }
                    }
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(prefStoreClass, "j")
    }

    private fun readPrefStoreWallpaperSpecs(store: Any): List<Any> {
        val point = resolvePrefStoreLoadSpecsMethod()
        return store.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 1
        }.invoke(false) as? List<Any> ?: emptyList()
    }

    private fun readPrefStoreValue(
        store: Any,
        type: Class<*>,
        defaultValue: Any?,
        key: String
    ): Any? {
        val point = resolvePrefStoreReadValueMethod()
        return store.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 3
        }.invoke(type, defaultValue, key)
    }

    private fun writePrefStoreValue(store: Any, value: Any, key: String) {
        val point = resolvePrefStoreWriteValueMethod()
        store.asResolver().firstMethod {
            name = point.methodName
            parameterCount = 2
        }.invoke(value, key)
    }

    private fun resolvePrefStoreClass(): String {
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for pref store class")
        val className = resolveDexKitFieldValue(
            bridge = bridge,
            cacheKey = PREF_STORE_CLASS_CACHE_KEY,
            selector = { it.className },
        ) {
            findMethod {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    paramCount(0)
                    usingStrings("Save user select, new index = ", "user_select")
                }
            }.singleOrNull()
                ?.usingFields
                ?.firstOrNull { field -> field.field.typeName.endsWith(".d") || field.field.name == "a" }
                ?.field
        } ?: ""
        require(className.isNotBlank()) { "DexKit failed to resolve pref store class" }
        return className
    }

    private fun resolveWallpaperRuntimeListMethod(): DexKitMethodInjectionPoint {
        val bridge =
            dexKitBridge ?: error("DexKit bridge is not ready for wallpaper runtime method")
        val point = resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = WALLPAPER_RUNTIME_LIST_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    paramCount(1)
                    returnType = "java.util.List"
                    usingStrings(
                        "/data/system/theme_magic/users/\$user_id/rearScreen/runtime.json",
                        "/system/media/rearscreen/template/default/rearScreen.json",
                    )
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint("", "")
        require(point.className.isNotBlank() && point.methodName.isNotBlank()) {
            "DexKit failed to resolve wallpaper runtime list method"
        }
        return point
    }

    private fun resolveDeviceConfigClass(): String {
        val bridge = dexKitBridge ?: error("DexKit bridge is not ready for device config class")
        val className = resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = DEVICE_CONFIG_CLASS_CACHE_KEY,
        ) {
            findClass {
                matcher {
                    usingStrings("vendor.wallpaper.color.flag")
                }
            }.singleOrNull()
        } ?: ""
        require(className.isNotBlank()) { "DexKit failed to resolve device config class" }
        return className
    }

    private fun loadWallpaperSpecs(): List<Any> {
        val prefStore = resolvePrefStore()

        val persisted = runCatching {
            prefStore?.let(::readPrefStoreWallpaperSpecs)
        }.getOrNull().orEmpty()

        val runtime = runCatching {
            val runtimeListPoint = resolveWallpaperRuntimeListMethod()
            runtimeListPoint.className.toClass().resolve().firstMethod {
                name = runtimeListPoint.methodName
                parameterCount = 1
            }.invoke(true) as? List<Any>
        }.getOrNull().orEmpty()

        if (persisted.isEmpty()) {
            normalizeSelectedWallpaperIndex(
                merged = runtime,
                persisted = emptyList(),
                runtime = runtime,
            )
            return runtime
        }
        if (runtime.isEmpty()) {
            normalizeSelectedWallpaperIndex(
                merged = persisted,
                persisted = persisted,
                runtime = emptyList(),
            )
            return persisted
        }

        val runtimeById = runtime.mapNotNull { spec ->
            spec.wallpaperSpecId()?.let { id -> id to spec }
        }.toMap()
        val importedRuntimeIds = readRuntimeRecords()
            .asSequence()
            .filter { it.imported }
            .sortedByDescending { it.position }
            .map { it.wallpaperId }
            .toList()

        val seenIds = HashSet<Int>()
        val merged = buildList {
            importedRuntimeIds.forEach { id ->
                val spec = runtimeById[id] ?: return@forEach
                if (seenIds.add(id)) add(spec)
            }
            persisted.forEach { spec ->
                val id = spec.wallpaperSpecId() ?: return@forEach
                if (seenIds.add(id)) add(spec)
            }
            runtime.forEach { spec ->
                val id = spec.wallpaperSpecId() ?: return@forEach
                if (seenIds.add(id)) add(spec)
            }
        }
        normalizeSelectedWallpaperIndex(
            merged = merged,
            persisted = persisted,
            runtime = runtime,
        )
        return merged
    }

    private fun normalizeSelectedWallpaperIndex(
        merged: List<Any>,
        persisted: List<Any>,
        runtime: List<Any>,
    ) {
        if (merged.isEmpty()) return
        val rawIndex = readRawSelectionIndex()
        val cachedSelectedId = readSelectedWallpaperId()
        val selectedId = cachedSelectedId
            ?: persisted.getOrNull(rawIndex)?.wallpaperSpecId()
            ?: runtime.getOrNull(rawIndex)?.wallpaperSpecId()
            ?: merged.getOrNull(rawIndex.coerceIn(0, merged.lastIndex))?.wallpaperSpecId()
            ?: return
        val normalizedIndex = merged.indexOfFirst { it.wallpaperSpecId() == selectedId }
        if (normalizedIndex < 0) {
            if (cachedSelectedId == selectedId) clearSelectedWallpaperId()
            return
        }
        if (normalizedIndex != rawIndex.coerceIn(0, merged.lastIndex)) {
            persistSelectionIndex(normalizedIndex)
            debugLog("normalizeSelectedWallpaperIndex raw=$rawIndex normalized=$normalizedIndex wallpaperId=$selectedId")
        }
        persistSelectedWallpaperId(selectedId)
    }

    private fun readRawSelectionIndex(): Int {
        return runCatching {
            val store = resolvePrefStore() ?: return@runCatching 0
            readPrefStoreValue(
                store = store,
                type = Int::class.javaPrimitiveType!!,
                defaultValue = 0,
                key = "user_select",
            ) as? Int ?: 0
        }.getOrDefault(0)
    }

    private fun readCurrentSelectionIndex(maxIndex: Int): Int {
        val index = readRawSelectionIndex()
        if (maxIndex < 0) return -1
        return index.coerceIn(0, maxIndex)
    }

    private fun persistSelectionIndex(index: Int) {
        runCatching {
            val store = resolvePrefStore() ?: return
            writePrefStoreValue(store, index, "user_select")
        }.onFailure(YLog::error)
    }

    private fun readSelectedWallpaperId(): Int? {
        val value = prefs.native().getInt(SELECTED_WALLPAPER_ID_CACHE_KEY, Int.MIN_VALUE)
        return value.takeIf { it != Int.MIN_VALUE }
    }

    private fun persistSelectedWallpaperId(wallpaperId: Int) {
        prefs.native().edit {
            putInt(SELECTED_WALLPAPER_ID_CACHE_KEY, wallpaperId)
            apply()
        }
    }

    private fun clearSelectedWallpaperId() {
        prefs.native().edit {
            remove(SELECTED_WALLPAPER_ID_CACHE_KEY)
            apply()
        }
    }

    private fun resetNextSwitchAtForCurrent(wallpaperId: Int, entries: List<WallpaperEntry>) {
        if (!readScheduleConfig().enabled) {
            persistNextSwitchAt(0L)
            debugLog("resetNextSwitchAtForCurrent disabled wallpaperId=$wallpaperId")
            return
        }
        val resolved = loadResolvedSchedule(entries)
        val current = resolved.firstOrNull { it.wallpaperId == wallpaperId }
        if (current == null) {
            persistNextSwitchAt(0L)
            debugLog("resetNextSwitchAtForCurrent missing wallpaperId=$wallpaperId")
            return
        }
        val nextAt = System.currentTimeMillis() + current.delayMs
        persistNextSwitchAt(nextAt)
        debugLog("resetNextSwitchAtForCurrent wallpaperId=$wallpaperId nextAt=$nextAt delay=${current.delayMs}")
        scheduleAt(nextAt)
    }

    private fun readNextSwitchAt(): Long {
        val cached = cachedNextSwitchAtMillis
        if (cached != Long.MIN_VALUE) return cached
        val persisted = prefs.native().getLong(ConfigKeys.REAR_WALLPAPER_SCHEDULE_NEXT_AT, 0L)
        cachedNextSwitchAtMillis = persisted
        return persisted
    }

    private fun persistNextSwitchAt(timestamp: Long) {
        cachedNextSwitchAtMillis = timestamp
        prefs.native().edit {
            putLong(ConfigKeys.REAR_WALLPAPER_SCHEDULE_NEXT_AT, timestamp)
            apply()
        }
        debugLog("persistNextSwitchAt=$timestamp")
    }

    private fun readScheduleConfig(): ScheduleConfig {
        cachedScheduleConfig?.let { return it }
        val config = ScheduleConfig(
            enabled = prefs.getBoolean(ConfigKeys.REAR_WALLPAPER_SCHEDULE_ENABLED, false),
            scheduleData = prefs.getString(
                ConfigKeys.REAR_WALLPAPER_SCHEDULE_DATA,
                RearWallpaperScheduleCodec.EMPTY_ARRAY,
            ).ifBlank { RearWallpaperScheduleCodec.EMPTY_ARRAY },
        )
        cachedScheduleConfig = config
        return config
    }

    private fun updateScheduleConfig(enabled: Boolean, scheduleData: String?) {
        cachedScheduleConfig = ScheduleConfig(
            enabled = enabled,
            scheduleData = scheduleData?.takeIf { it.isNotBlank() }
                ?: RearWallpaperScheduleCodec.EMPTY_ARRAY,
        )
    }

    private fun importWallpaperPackageInternal(
        packageFd: ParcelFileDescriptor?,
        displayNameHint: String?,
        previewUri: String?,
        options: Bundle?,
    ): Bundle {
        val context =
            hostContext ?: return operationResult(false, error = "host context is not ready")
        val sourceFd = packageFd ?: return operationResult(false, error = "package fd is empty")
        val sourceName = displayNameHint?.takeIf { it.isNotBlank() } ?: "wallpaper.mrc"
        if (!sourceName.endsWith(".mrc", ignoreCase = true) &&
            !sourceName.endsWith(".zip", ignoreCase = true)
        ) {
            return operationResult(false, error = "only .mrc or .zip packages are supported")
        }

        debugLog(
            "importWallpaperPackageInternal start displayName=$sourceName previewUri=$previewUri"
        )

        return runCatching {
            synchronized(runtimeLock) {
                val now = System.currentTimeMillis()
                val resId = "$IMPORT_RES_PREFIX${now}_${UUID.randomUUID().shortId()}"
                val applyId = UUID.randomUUID().shortId()
                val targetDir = File(resolveRuntimeRoot(), "${resId}_${applyId}")
                val packageFile = File(targetDir, "rearscreen_${resId}_${applyId}.mrc")
                val metadataFile = File(targetDir, "$resId.mrm")

                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    return@synchronized operationResult(
                        false,
                        error = "failed to create runtime dir"
                    )
                }

                val packageSize = copyParcelFileDescriptorToFileLimited(sourceFd, packageFile)
                validateMamlPackage(packageFile)
                val extractedPreviewPath = extractPreviewFromPackage(packageFile, targetDir)
                val previewPath = previewUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Uri::parse)
                    ?.let { copyPreviewImageFromUri(context, it, targetDir, "preview_imported") }
                    ?: extractedPreviewPath

                val packageMetadata = readPackageMetadata(packageFile)
                val metadataValues = resolveMetadataValues(
                    options = options,
                    source = packageMetadata,
                    displayNameHint = sourceName,
                )
                val metadataJson = buildMetadataJson(
                    base = null,
                    resId = resId,
                    packageFile = packageFile,
                    metadataFile = metadataFile,
                    previewPath = previewPath,
                    packageSize = packageSize,
                    updatedAt = now,
                    values = metadataValues,
                )
                writeTextAtomically(metadataFile, metadataJson.toString(2))

                val runtimeArray = readRuntimeArray()
                val position = maxRuntimePosition(runtimeArray) + 1
                val item = buildRuntimeItem(
                    resId = resId,
                    applyId = applyId,
                    packagePath = packageFile.absolutePath,
                    metadataPath = metadataFile.absolutePath,
                    previewPath = previewPath,
                    position = position,
                    updatedAt = now,
                    values = metadataValues,
                )
                runtimeArray.put(item)
                writeRuntimeArray(runtimeArray)
                ensureReadableRecursive(targetDir)
                refreshRuntimePanels()
                debugLog(
                    "importWallpaperPackageInternal success resId=$resId applyId=$applyId targetDir=${targetDir.absolutePath} previewPath=$previewPath"
                )
                operationResult(true, wallpaperId = (resId + applyId).hashCode())
            }
        }.getOrElse {
            debugFailure(
                message = "importWallpaperPackageInternal failed displayName=$sourceName err=${it.message}",
                error = it,
            )
            YLog.error(it)
            operationResult(false, error = throwableMessage(it, "import failed"))
        }
    }

    private fun updateWallpaperMetadataInternal(
        wallpaperId: Int,
        previewUri: String?,
        options: Bundle?,
    ): Bundle {
        return runCatching {
            synchronized(runtimeLock) {
                val context = hostContext ?: return@synchronized operationResult(
                    false,
                    error = "host context is not ready",
                )
                val runtimeArray = readRuntimeArray()
                val index = findRuntimeItemIndex(runtimeArray, wallpaperId)
                if (index < 0) {
                    return@synchronized operationResult(
                        false,
                        error = "wallpaper is not in runtime list"
                    )
                }
                val item = runtimeArray.getJSONObject(index)
                val record = item.toRuntimeRecord() ?: return@synchronized operationResult(
                    false,
                    error = "runtime item is invalid",
                )
                if (!record.imported) {
                    return@synchronized operationResult(
                        false,
                        error = "only REAREye imported wallpapers can be edited",
                    )
                }

                val packageFile = record.resLocalPath?.let(::File)
                    ?: return@synchronized operationResult(false, error = "package path is missing")
                val metadataFile = record.metaPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?: File(packageFile.parentFile, "${record.resId}.mrm")
                val previewPath = previewUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Uri::parse)
                    ?.let {
                        copyPreviewImageFromUri(
                            context = context,
                            uri = it,
                            targetDir = metadataFile.parentFile ?: packageFile.parentFile,
                            fileNamePrefix = "preview_imported_${System.currentTimeMillis()}",
                        )
                    }
                    ?: record.previewPath
                val sourceMetadata = readJsonFile(metadataFile)
                val currentValues = record.readMetadataValues()
                val values = resolveMetadataValues(
                    options = options,
                    source = currentValues,
                    displayNameHint = record.resId,
                )
                val metadataJson = buildMetadataJson(
                    base = sourceMetadata,
                    resId = record.resId,
                    packageFile = packageFile,
                    metadataFile = metadataFile,
                    previewPath = previewPath,
                    packageSize = packageFile.length(),
                    updatedAt = System.currentTimeMillis(),
                    values = values,
                )
                writeTextAtomically(metadataFile, metadataJson.toString(2))
                applyMetadataToRuntimeItem(
                    item = item,
                    metadataPath = metadataFile.absolutePath,
                    previewPath = previewPath,
                    values = values,
                )
                writeRuntimeArray(runtimeArray)
                ensureReadableRecursive(metadataFile.parentFile ?: metadataFile)
                refreshRuntimePanels()
                operationResult(true, wallpaperId = wallpaperId)
            }
        }.getOrElse {
            YLog.error(it)
            operationResult(false, error = it.message ?: "metadata update failed")
        }
    }

    private fun updateWallpaperPackageInternal(
        wallpaperId: Int,
        packageFd: ParcelFileDescriptor?,
        displayNameHint: String?,
        previewUri: String?,
        options: Bundle?,
    ): Bundle {
        val context =
            hostContext ?: return operationResult(false, error = "host context is not ready")
        val sourceFd = packageFd ?: return operationResult(false, error = "package fd is empty")
        val sourceName = displayNameHint?.takeIf { it.isNotBlank() } ?: "wallpaper.mrc"
        if (!sourceName.endsWith(".mrc", ignoreCase = true) &&
            !sourceName.endsWith(".zip", ignoreCase = true)
        ) {
            return operationResult(false, error = "only .mrc or .zip packages are supported")
        }

        return runCatching {
            synchronized(runtimeLock) {
                val runtimeArray = readRuntimeArray()
                val index = findRuntimeItemIndex(runtimeArray, wallpaperId)
                if (index < 0) {
                    return@synchronized operationResult(
                        false,
                        error = "wallpaper is not in runtime list"
                    )
                }
                val item = runtimeArray.getJSONObject(index)
                val record = item.toRuntimeRecord() ?: return@synchronized operationResult(
                    false,
                    error = "runtime item is invalid",
                )
                if (!record.imported) {
                    return@synchronized operationResult(
                        false,
                        error = "only REAREye imported wallpapers can be updated",
                    )
                }

                val packageFile = record.resLocalPath?.let(::File)
                    ?: return@synchronized operationResult(false, error = "package path is missing")
                val metadataFile = record.metaPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?: File(packageFile.parentFile, "${record.resId}.mrm")
                val targetDir = metadataFile.parentFile
                    ?: packageFile.parentFile
                    ?: return@synchronized operationResult(false, error = "runtime dir is missing")
                val updatePackageFile = File(targetDir, "${packageFile.name}.update")

                try {
                    val now = System.currentTimeMillis()
                    val packageSize = copyParcelFileDescriptorToFileLimited(
                        descriptor = sourceFd,
                        target = updatePackageFile,
                    )
                    validateMamlPackage(updatePackageFile)
                    if (packageFile.exists()) packageFile.delete()
                    if (!updatePackageFile.renameTo(packageFile)) {
                        updatePackageFile.copyTo(packageFile, overwrite = true)
                        updatePackageFile.delete()
                    }
                    ensureReadable(packageFile)

                    val extractedPreviewPath = extractPreviewFromPackage(packageFile, targetDir)
                    val previewPath = previewUri
                        ?.takeIf { it.isNotBlank() }
                        ?.let(Uri::parse)
                        ?.let {
                            copyPreviewImageFromUri(
                                context,
                                it,
                                targetDir,
                                "preview_imported_${now}"
                            )
                        }
                        ?: extractedPreviewPath
                        ?: record.previewPath
                    val sourceMetadata = readJsonFile(metadataFile)
                    val currentValues = record.readMetadataValues()
                    val packageMetadata = readEmbeddedMetadata(packageFile)
                    val values = resolveMetadataValues(
                        options = options,
                        source = packageMetadata ?: currentValues,
                        displayNameHint = sourceName,
                    )
                    val metadataJson = buildMetadataJson(
                        base = if (packageMetadata == null) sourceMetadata else null,
                        resId = record.resId,
                        packageFile = packageFile,
                        metadataFile = metadataFile,
                        previewPath = previewPath,
                        packageSize = packageSize,
                        updatedAt = now,
                        values = values,
                    )
                    writeTextAtomically(metadataFile, metadataJson.toString(2))
                    applyMetadataToRuntimeItem(
                        item = item,
                        metadataPath = metadataFile.absolutePath,
                        previewPath = previewPath,
                        values = values,
                    )
                    item.put("resLocalPath", packageFile.absolutePath)
                    item.put("resSnapshotPath", packageFile.absolutePath)
                    writeRuntimeArray(runtimeArray)
                    ensureReadableRecursive(targetDir)
                    refreshRuntimePanels()
                    operationResult(true, wallpaperId = wallpaperId)
                } finally {
                    runCatching { updatePackageFile.delete() }
                }
            }
        }.getOrElse {
            YLog.error(it)
            operationResult(false, error = it.message ?: "package update failed")
        }
    }

    private fun generateWallpaperPreviewInternal(wallpaperId: Int): Bundle {
        return runCatching {
            synchronized(runtimeLock) {
                val runtimeArray = readRuntimeArray()
                val index = findRuntimeItemIndex(runtimeArray, wallpaperId)
                if (index < 0) {
                    return@synchronized operationResult(
                        false,
                        error = "wallpaper is not in runtime list"
                    )
                }
                val item = runtimeArray.getJSONObject(index)
                val record = item.toRuntimeRecord() ?: return@synchronized operationResult(
                    false,
                    error = "runtime item is invalid",
                )
                if (!record.imported) {
                    return@synchronized operationResult(
                        false,
                        error = "only REAREye imported wallpapers can generate previews",
                    )
                }

                val packageFile = record.resLocalPath?.let(::File)
                    ?: return@synchronized operationResult(false, error = "package path is missing")
                val metadataFile = record.metaPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?: File(packageFile.parentFile, "${record.resId}.mrm")
                val previewFile = File(
                    metadataFile.parentFile ?: packageFile.parentFile,
                    "preview_generated_${System.currentTimeMillis()}.jpg",
                )
                debugLog(
                    "generateWallpaperPreviewInternal start wallpaperId=$wallpaperId package=${packageFile.absolutePath} metadata=${metadataFile.absolutePath} currentPreview=${record.previewPath}"
                )
                val previewPath = captureWallpaperPreviewToFile(wallpaperId, previewFile)
                val sourceMetadata = readJsonFile(metadataFile)
                val values = record.readMetadataValues()
                val metadataJson = buildMetadataJson(
                    base = sourceMetadata,
                    resId = record.resId,
                    packageFile = packageFile,
                    metadataFile = metadataFile,
                    previewPath = previewPath,
                    packageSize = packageFile.length(),
                    updatedAt = System.currentTimeMillis(),
                    values = values,
                )
                writeTextAtomically(metadataFile, metadataJson.toString(2))
                applyMetadataToRuntimeItem(
                    item = item,
                    metadataPath = metadataFile.absolutePath,
                    previewPath = previewPath,
                    values = values,
                )
                writeRuntimeArray(runtimeArray)
                ensureReadableRecursive(metadataFile.parentFile ?: metadataFile)
                refreshRuntimePanels()
                debugLog(
                    "generateWallpaperPreviewInternal success wallpaperId=$wallpaperId previewPath=$previewPath metadata=${metadataFile.absolutePath}"
                )
                operationResult(true, wallpaperId = wallpaperId)
            }
        }.getOrElse {
            YLog.error(it)
            operationResult(false, error = it.message ?: "preview generation failed")
        }
    }

    private fun deleteWallpaperInternal(wallpaperId: Int): Bundle {
        return runCatching {
            synchronized(runtimeLock) {
                val runtimeArray = readRuntimeArray()
                val index = findRuntimeItemIndex(runtimeArray, wallpaperId)
                if (index < 0) {
                    return@synchronized operationResult(
                        false,
                        error = "wallpaper is not in runtime list"
                    )
                }
                val item = runtimeArray.getJSONObject(index)
                val record = item.toRuntimeRecord() ?: return@synchronized operationResult(
                    false,
                    error = "runtime item is invalid",
                )
                if (!record.imported) {
                    return@synchronized operationResult(
                        false,
                        error = "only REAREye imported wallpapers can be deleted",
                    )
                }

                val nextArray = JSONArray()
                for (i in 0 until runtimeArray.length()) {
                    if (i != index) nextArray.put(runtimeArray.getJSONObject(i))
                }
                writeRuntimeArray(nextArray)
                deleteImportedFiles(record)
                refreshRuntimePanels()
                operationResult(true, wallpaperId = wallpaperId)
            }
        }.getOrElse {
            YLog.error(it)
            operationResult(false, error = it.message ?: "delete failed")
        }
    }

    private fun resolveWallpaperTemplateConfigStateModel(
        wallpaperId: Int,
        currentOneConfigJson: String?,
    ): RearWidgetTemplateConfigState? {
        val entry = loadWallpaperEntries().firstOrNull { it.wallpaperId == wallpaperId }
        val templatePath = entry
            ?.templatePath
            ?.takeIf { hasEditableTemplateConfig(it) }
            ?: resolveWallpaperTemplatePath(wallpaperId)
            ?: return null
        val schema = WidgetTemplateConfigRepository.loadSchema(templatePath) ?: return null
        val existingConfigJson = currentOneConfigJson
            ?.takeIf { it.isNotBlank() }
            ?: readOneConfigJson(entry?.templateConfigPath)
        val oneConfig = WidgetTemplateConfigRepository.buildInitialOneConfig(
            schema = schema,
            existingJson = existingConfigJson,
        )
        debugLog(
            "resolveWallpaperTemplateConfigState wallpaperId=$wallpaperId template=$templatePath config=${entry?.templateConfigPath.orEmpty()} items=${schema.items.size} hasCurrent=${
                currentOneConfigJson.isNullOrBlank().not()
            }"
        )
        return RearWidgetTemplateConfigState(
            templateSchemaJson = WidgetTemplateConfigRepository.encodeSchema(schema),
            oneConfigJson = WidgetTemplateConfigRepository.encodeOneConfig(oneConfig),
        )
    }

    private fun saveWallpaperTemplateConfigInternal(
        wallpaperId: Int,
        oneConfigJson: String?,
    ): Bundle {
        return runCatching {
            synchronized(runtimeLock) {
                val runtimeArray = readRuntimeArray()
                val index = findRuntimeItemIndex(runtimeArray, wallpaperId)
                val entry = loadWallpaperEntries().firstOrNull { it.wallpaperId == wallpaperId }
                if (index < 0) {
                    return@synchronized saveExistingWallpaperTemplateConfig(entry, oneConfigJson)
                }

                val item = runtimeArray.getJSONObject(index)
                val record = item.toRuntimeRecord() ?: return@synchronized operationResult(
                    false,
                    error = "runtime item is invalid",
                )
                val normalizedJson = oneConfigJson?.trim().orEmpty()
                if (normalizedJson.isBlank()) {
                    item.remove("mamlEditConfigPath")
                    writeRuntimeArray(runtimeArray)
                    refreshRuntimePanels()
                    debugLog("saveWallpaperTemplateConfig reset wallpaperId=$wallpaperId")
                    return@synchronized operationResult(true, wallpaperId = wallpaperId)
                }

                val templatePath = record.resLocalPath
                    ?.takeIf { it.isNotBlank() }
                    ?: entry?.templatePath
                    ?: return@synchronized operationResult(
                        false,
                        error = "template path is missing"
                    )
                val schema = WidgetTemplateConfigRepository.loadSchema(templatePath)
                    ?: return@synchronized operationResult(
                        false,
                        error = "template config is unavailable"
                    )
                val normalizedConfig = WidgetTemplateConfigRepository.encodeOneConfig(
                    WidgetTemplateConfigRepository.buildInitialOneConfig(
                        schema = schema,
                        existingJson = normalizedJson,
                    )
                )
                val configFile = resolveTemplateConfigFile(record)
                writeTextAtomically(configFile, normalizedConfig)
                item.put("mamlEditConfigPath", configFile.absolutePath)
                writeRuntimeArray(runtimeArray)
                ensureReadableRecursive(configFile.parentFile ?: configFile)
                refreshRuntimePanels()
                debugLog(
                    "saveWallpaperTemplateConfig success wallpaperId=$wallpaperId template=$templatePath config=${configFile.absolutePath} size=${normalizedConfig.length}"
                )
                operationResult(true, wallpaperId = wallpaperId)
            }
        }.getOrElse {
            YLog.error(it)
            operationResult(false, error = it.message ?: "template config save failed")
        }
    }

    private fun saveExistingWallpaperTemplateConfig(
        entry: WallpaperEntry?,
        oneConfigJson: String?,
    ): Bundle {
        val target =
            entry ?: return operationResult(false, error = "wallpaper is not in current list")
        val normalizedJson = oneConfigJson?.trim().orEmpty()
        if (normalizedJson.isBlank()) {
            return operationResult(
                false,
                error = "only runtime wallpaper template config can be reset"
            )
        }
        val configPath = target.templateConfigPath?.takeIf { it.isNotBlank() }
            ?: return operationResult(false, error = "wallpaper has no writable config path")
        val schema = WidgetTemplateConfigRepository.loadSchema(
            target.templatePath ?: return operationResult(false, error = "template path is missing")
        ) ?: return operationResult(false, error = "template config is unavailable")
        val normalizedConfig = WidgetTemplateConfigRepository.encodeOneConfig(
            WidgetTemplateConfigRepository.buildInitialOneConfig(
                schema = schema,
                existingJson = normalizedJson,
            )
        )
        writeTextAtomically(File(configPath), normalizedConfig)
        refreshRuntimePanels()
        debugLog(
            "saveWallpaperTemplateConfig existing wallpaperId=${target.wallpaperId} config=$configPath size=${normalizedConfig.length}"
        )
        return operationResult(true, wallpaperId = target.wallpaperId)
    }

    private fun operationResult(
        success: Boolean,
        error: String? = null,
        wallpaperId: Int? = null,
    ): Bundle {
        return Bundle().apply {
            putBoolean(RearWallpaperApiContract.BundleKeys.SUCCESS, success)
            if (error != null) putString(RearWallpaperApiContract.BundleKeys.ERROR, error)
            if (wallpaperId != null) putInt(
                RearWallpaperApiContract.BundleKeys.WALLPAPER_ID,
                wallpaperId
            )
        }
    }

    private fun copyParcelFileDescriptorToFileLimited(
        descriptor: ParcelFileDescriptor,
        target: File,
    ): Long {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read.toLong()
                    if (total > MAX_IMPORT_BYTES) {
                        throw IllegalArgumentException("package is larger than ${MAX_IMPORT_BYTES / 1024 / 1024} MB")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }

        if (target.exists()) target.delete()
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
        return total
    }

    private fun copyPreviewImageFromUri(
        context: Context,
        uri: Uri,
        targetDir: File,
        fileNamePrefix: String,
    ): String {
        targetDir.mkdirs()
        val tempFile = File(targetDir, "$fileNamePrefix.source")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read.toLong()
                    if (total > MAX_PREVIEW_BYTES) {
                        throw IllegalArgumentException("preview image is larger than ${MAX_PREVIEW_BYTES / 1024 / 1024} MB")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalArgumentException("failed to open preview image uri")

        val previewBytes = loadPreviewBytes(tempFile.absolutePath)
            ?: throw IllegalArgumentException("preview image is invalid")
        val target = File(targetDir, "$fileNamePrefix.jpg")
        writeBytesAtomically(target, previewBytes)
        tempFile.delete()
        ensureReadable(target)
        return target.absolutePath
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        tempFile.outputStream().use { output -> output.write(bytes) }
        if (target.exists()) target.delete()
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
        ensureReadable(target)
    }

    private fun captureWallpaperPreviewToFile(wallpaperId: Int, targetFile: File): String {
        debugLog("captureWallpaperPreviewToFile start wallpaperId=$wallpaperId target=${targetFile.absolutePath}")
        return runCatching {
            captureWallpaperPreviewOffscreenToFile(wallpaperId, targetFile)
        }.onSuccess {
            debugLog("captureWallpaperPreviewToFile offscreen success wallpaperId=$wallpaperId output=$it")
        }.onFailure {
            debugFailure(
                message = "offscreen preview capture failed wallpaperId=$wallpaperId err=${it.message}",
                error = it,
            )
        }.getOrElse {
            debugLog("captureWallpaperPreviewToFile fallback switch-capture wallpaperId=$wallpaperId")
            captureWallpaperPreviewBySwitchToFile(wallpaperId, targetFile)
        }
    }

    private fun captureWallpaperPreviewOffscreenToFile(wallpaperId: Int, targetFile: File): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("preview capture must not run on the main thread")
        }
        val context = hostContext ?: throw IllegalStateException("host context is not ready")
        val panel = mainPanel as? View ?: throw IllegalStateException("main panel is not ready")
        val panelContainer = panel as? ViewGroup
            ?: throw IllegalStateException("main panel is not a ViewGroup")
        val handler = mainHandler ?: throw IllegalStateException("main handler is not ready")
        val entry = loadWallpaperEntries().firstOrNull { it.wallpaperId == wallpaperId }
            ?: throw IllegalArgumentException("wallpaper is not in current list")
        val bitmapRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)

        handler.post {
            runCatching {
                val size = resolvePreviewRenderSize(panel)
                val panelEditMode = readMainPanelEditMode(panel)
                val panelInAod = readMainPanelAodState(panel)
                val renderInAod = false
                val panelResumed = readMainPanelResumedState(panel)
                val renderOverlay = FrameLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(size.x, size.y)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isClickable = false
                    isFocusable = false
                    alpha = 0f
                    clipChildren = false
                    clipToPadding = false
                }
                val renderHost = FrameLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(size.x, size.y)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isClickable = false
                    isFocusable = false
                    clipChildren = false
                    clipToPadding = false
                }
                renderOverlay.addView(renderHost)
                val targetWidget = cloneWallpaperWidgetForPreview(entry.widget)
                var cleaned = false

                fun layoutRenderTree() {
                    renderOverlay.measure(
                        View.MeasureSpec.makeMeasureSpec(size.x, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size.y, View.MeasureSpec.EXACTLY),
                    )
                    renderOverlay.layout(0, 0, size.x, size.y)
                    renderHost.measure(
                        View.MeasureSpec.makeMeasureSpec(size.x, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size.y, View.MeasureSpec.EXACTLY),
                    )
                    renderHost.layout(0, 0, size.x, size.y)
                }

                fun cleanup() {
                    if (cleaned) return
                    cleaned = true
                    runCatching {
                        invokeWidgetCleanup(targetWidget)
                    }.onFailure(YLog::warn)
                    runCatching {
                        (renderOverlay.parent as? ViewGroup)?.removeView(renderOverlay)
                    }.onFailure(YLog::warn)
                }

                fun finishWithBitmap(bitmap: Bitmap) {
                    bitmapRef.set(bitmap)
                    cleanup()
                    latch.countDown()
                }

                fun finishWithError(error: Throwable) {
                    errorRef.set(error)
                    cleanup()
                    latch.countDown()
                }

                runCatching {
                    panelContainer.addView(renderOverlay)
                    layoutRenderTree()
                    debugLog(
                        "offscreen preview start wallpaperId=$wallpaperId size=${size.x}x${size.y} states(edit=$panelEditMode,panelAod=$panelInAod,renderAod=$renderInAod,resumed=$panelResumed) panel=${
                            describeViewState(
                                panel
                            )
                        } overlay=${describeViewState(renderOverlay)} host=${
                            describeViewState(
                                renderHost
                            )
                        }"
                    )

                    val targetResolver = targetWidget.asResolver()
                    targetResolver.firstField {
                        superclass()
                        name = resolveWidgetHostFieldName()
                    }.set(renderHost)
                    invokeWidgetSetEditMode(targetWidget, panelEditMode)
                    runCatching {
                        targetResolver.firstField {
                            superclass()
                            name = resolveWidgetPreviewModeFieldName()
                        }.set(true)
                    }.onFailure {
                        debugFailure(
                            message = "offscreen preview failed to set widget.u wallpaperId=$wallpaperId err=${it.message}",
                            error = it,
                        )
                    }

                    // Decompiled source: .tmp-ref/decompiled-jadx/sources/t2/r.java:216
                    // t2.r.z(Context) creates the actual View and adds it to r.p.
                    val createdView = invokeWidgetCreateView(targetWidget, context)
                    if (createdView == null && renderHost.isEmpty()) {
                        finishWithError(IllegalStateException("offscreen widget view was not created"))
                        return@runCatching
                    }
                    layoutRenderTree()
                    debugLog(
                        "offscreen preview widget created wallpaperId=$wallpaperId created=${
                            describeViewState(
                                createdView
                            )
                        } host=${describeViewState(renderHost)} hostChildren=${renderHost.childCount}"
                    )
                    // Preview rendering should use the normal rear-screen state even if the panel is in AOD.
                    invokeWidgetSetAodState(targetWidget, renderInAod)
                    invokeWidgetResume(targetWidget)

                    val startedAt = System.currentTimeMillis()
                    var attempt = 0
                    fun tryCapture() {
                        runCatching {
                            attempt += 1
                            layoutRenderTree()
                            val bitmap = captureViewBitmap(renderHost)
                            val hasVisiblePixels = bitmap.hasVisiblePixels()
                            val elapsed = System.currentTimeMillis() - startedAt
                            debugLog(
                                "offscreen preview attempt=$attempt wallpaperId=$wallpaperId elapsed=${elapsed}ms visible=$hasVisiblePixels overlay=${
                                    describeViewState(
                                        renderOverlay
                                    )
                                } host=${describeViewState(renderHost)} created=${
                                    describeViewState(
                                        createdView
                                    )
                                }"
                            )
                            if (hasVisiblePixels) {
                                finishWithBitmap(bitmap)
                                return
                            }
                            bitmap.recycle()
                            if (elapsed >= OFFSCREEN_CAPTURE_TIMEOUT_MS) {
                                throw IllegalStateException("offscreen preview stayed blank")
                            }
                            renderHost.postDelayed(
                                { tryCapture() },
                                OFFSCREEN_CAPTURE_RETRY_INTERVAL_MS
                            )
                        }.onFailure(::finishWithError)
                    }
                    renderHost.postDelayed({ tryCapture() }, OFFSCREEN_CAPTURE_INITIAL_DELAY_MS)
                }.onFailure {
                    finishWithError(it)
                }
            }.onFailure {
                errorRef.set(it)
                latch.countDown()
            }
        }

        if (!latch.await(OFFSCREEN_CAPTURE_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("offscreen preview capture timed out")
        }
        errorRef.get()?.let { throw it }
        val bitmap =
            bitmapRef.get() ?: throw IllegalStateException("offscreen preview capture failed")
        return try {
            val output = ByteArrayOutputStream()
            output.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) {
                    throw IllegalStateException("failed to encode offscreen preview")
                }
            }
            writeBytesAtomically(targetFile, output.toByteArray())
            debugLog(
                "offscreen preview encoded wallpaperId=$wallpaperId bytes=${targetFile.length()} output=${targetFile.absolutePath}"
            )
            targetFile.absolutePath
        } finally {
            bitmap.recycle()
        }
    }

    private fun cloneWallpaperWidgetForPreview(sourceWidget: Any): Any {
        val spec = sourceWidget.asResolver().firstField {
            superclass()
            name = resolveWidgetSpecFieldName()
        }.get()
            ?: throw IllegalStateException("wallpaper spec is missing")
        return createWallpaperWidget(spec)
            ?: throw IllegalStateException("failed to create offscreen wallpaper widget")
    }

    private fun resolvePreviewRenderSize(panel: View): Point {
        val panelWidth = panel.width
        val panelHeight = panel.height
        if (panelWidth > 0 && panelHeight > 0) return Point(panelWidth, panelHeight)
        val devicePoint = runCatching {
            resolveDeviceConfigClass().toClass().resolve().firstField {
                name = resolveDeviceConfigRenderSizeFieldName()
            }.get() as? Point
        }.getOrNull()
        val width = devicePoint?.x?.takeIf { it > 0 } ?: panel.measuredWidth
        val height = devicePoint?.y?.takeIf { it > 0 } ?: panel.measuredHeight
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("preview render size is invalid")
        }
        return Point(width, height)
    }

    private fun captureWallpaperPreviewBySwitchToFile(wallpaperId: Int, targetFile: File): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("preview capture must not run on the main thread")
        }
        val panel = mainPanel as? View
            ?: throw IllegalStateException("main panel is not ready")
        val handler = mainHandler
            ?: throw IllegalStateException("main handler is not ready")
        val entries = loadWallpaperEntries()
        val targetIndex = entries.indexOfFirst { it.wallpaperId == wallpaperId }
        if (targetIndex < 0) throw IllegalArgumentException("wallpaper is not in current list")
        val currentIndex = readCurrentSelectionIndex(entries.lastIndex)
        val currentWallpaperId = entries.getOrNull(currentIndex)?.wallpaperId
        val widgets = entries.map { it.widget }
        val bitmapRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)

        debugLog(
            "switch preview fallback start wallpaperId=$wallpaperId targetIndex=$targetIndex currentIndex=$currentIndex currentWallpaperId=$currentWallpaperId panel=${
                describeViewState(
                    panel
                )
            }"
        )

        handler.post {
            runCatching {
                invokePanelSelection(panel, widgets, targetIndex)
                debugLog("switch preview fallback selected wallpaperId=$wallpaperId targetIndex=$targetIndex")
                panel.postDelayed(
                    {
                        runCatching {
                            val bitmap = captureViewBitmap(panel)
                            bitmapRef.set(bitmap)
                            debugLog(
                                "switch preview fallback captured wallpaperId=$wallpaperId bitmap=${bitmap.width}x${bitmap.height}"
                            )
                        }.onFailure(errorRef::set)

                        runCatching {
                            if (currentIndex >= 0 && currentIndex != targetIndex) {
                                invokePanelSelection(panel, widgets, currentIndex)
                                debugLog(
                                    "switch preview fallback restored selection wallpaperId=$wallpaperId restoreIndex=$currentIndex restoreWallpaperId=$currentWallpaperId"
                                )
                            }
                            if (currentIndex >= 0) persistSelectionIndex(currentIndex)
                            currentWallpaperId?.let(::persistSelectedWallpaperId)
                        }.onFailure(YLog::warn)
                        latch.countDown()
                    },
                    PREVIEW_CAPTURE_DELAY_MS,
                )
            }.onFailure {
                errorRef.set(it)
                latch.countDown()
            }
        }

        if (!latch.await(PREVIEW_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("preview capture timed out")
        }
        errorRef.get()?.let { throw it }
        val bitmap = bitmapRef.get() ?: throw IllegalStateException("preview capture failed")
        return try {
            val output = ByteArrayOutputStream()
            output.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) {
                    throw IllegalStateException("failed to encode preview")
                }
            }
            writeBytesAtomically(targetFile, output.toByteArray())
            targetFile.absolutePath
        } finally {
            bitmap.recycle()
        }
    }

    private fun invokePanelSelection(panel: Any, widgets: List<Any>, index: Int) {
        val selectPoint = resolveMainPanelSelectMethod()
        panel.asResolver().firstMethod {
            name = selectPoint.methodName
            parameterCount = 2
        }.invoke(widgets, index)
    }

    private fun captureViewBitmap(view: View): Bitmap {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("main panel has invalid size")
        }
        return createBitmap(width, height).also { bitmap ->
            view.draw(Canvas(bitmap))
        }
    }

    private fun Bitmap.hasVisiblePixels(): Boolean {
        val stepX = (width / 24).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if ((this[x, y] ushr 24) != 0) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun validateMamlPackage(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw IllegalArgumentException("package file is empty")
        }
        ZipFile(file).use { zip ->
            val hasManifest = zip.getEntry("manifest.xml") != null
            val hasConfig = zip.getEntry("config.xml") != null
            if (!hasManifest && !hasConfig) {
                throw IllegalArgumentException("package is missing manifest.xml or config.xml")
            }
        }
    }

    private fun extractPreviewFromPackage(packageFile: File, targetDir: File): String? {
        return runCatching {
            val previewPath = explicitTemplatePreviewPaths(packageFile.absolutePath)
                .firstNotNullOfOrNull { resolvePackagePreviewPath(packageFile.absolutePath, it) }
                ?: return null
            val previewBytes = loadPreviewBytes(previewPath) ?: return null
            val target = File(targetDir, "preview.jpg")
            target.outputStream().use { it.write(previewBytes) }
            if (loadPreviewBytes(target.absolutePath) == null) {
                target.delete()
                null
            } else {
                ensureReadable(target)
                target.absolutePath
            }
        }.getOrNull()
    }

    private fun resolvePackagePreviewPath(packagePath: String, previewPath: String): String? {
        val packageFile = File(packagePath)
        if (!packageFile.exists()) return null

        val rawPreview = previewPath.trim()
        if (rawPreview.isBlank()) return null

        val directPath = when {
            rawPreview.startsWith("file://", ignoreCase = true) -> rawPreview.toUri().path
            rawPreview.startsWith("/") -> rawPreview
            else -> null
        }
        directPath?.let { path ->
            if (isReadablePreviewPath(path)) return File(path).absolutePath
        }

        val normalizedPreview = rawPreview
            .removePrefix("file://")
            .replace('\\', '/')
            .removePrefix("/")
            .takeIf { it.isNotBlank() }
            ?: return null

        if (packageFile.isDirectory) {
            val child = File(packageFile, normalizedPreview)
            return child.takeIf { isReadablePreviewPath(it.absolutePath) }?.absolutePath
        }

        return runCatching {
            ZipFile(packageFile).use { zip ->
                val entry = findZipEntry(zip, normalizedPreview)
                    ?: return@use null
                if (entry.isDirectory || entry.size > MAX_PREVIEW_BYTES) return@use null
                encodeZipPreviewPath(packageFile.absolutePath, entry.name)
                    .takeIf { loadPreviewBytes(it) != null }
            }
        }.getOrNull()
    }

    private fun findZipEntry(zip: ZipFile, name: String): ZipEntry? {
        val normalized = name.trim().replace('\\', '/').removePrefix("/")
        if (normalized.isBlank()) return null
        return zip.getEntry(normalized)
            ?: zip.entries().asSequence().firstOrNull {
                it.name.equals(normalized, ignoreCase = true)
            }
    }

    private fun readJsonFile(file: File?): JSONObject? {
        if (file == null || !file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun readPackageMetadata(packageFile: File): MetadataValues? {
        return readEmbeddedMetadata(packageFile) ?: readDescriptionMetadata(packageFile)
    }

    private fun readEmbeddedMetadata(packageFile: File): MetadataValues? {
        return runCatching {
            ZipFile(packageFile).use { zip ->
                val entry = zip.getEntry("metadata.mrm") ?: return null
                zip.getInputStream(entry).bufferedReader().use { reader ->
                    JSONObject(reader.readText()).toMetadataValues(packageFile.nameWithoutExtension)
                }
            }
        }.getOrNull()
    }

    private fun readDescriptionMetadata(packageFile: File): MetadataValues? {
        return runCatching {
            ZipFile(packageFile).use { zip ->
                val entry = zip.getEntry("description.xml") ?: return null
                zip.getInputStream(entry).use { input ->
                    val factory = DocumentBuilderFactory.newInstance()
                    runCatching {
                        factory.setFeature(
                            "http://apache.org/xml/features/disallow-doctype-decl",
                            true,
                        )
                    }
                    val root = factory.newDocumentBuilder().parse(input).documentElement
                    val titles = readLocaleXmlValues(root, "title")
                    val descriptions = readLocaleXmlValues(root, "description")
                    val authors = readLocaleXmlValues(root, "author")
                    val designers = readLocaleXmlValues(root, "designer")
                    val title = titles["fallback"] ?: titles.values.firstOrNull()
                    ?: packageFile.nameWithoutExtension
                    MetadataValues(
                        titleFallback = title,
                        titleZhCn = titles["zh_CN"] ?: title,
                        descriptionFallback = descriptions["fallback"]
                            ?: descriptions.values.firstOrNull().orEmpty(),
                        descriptionZhCn = descriptions["zh_CN"]
                            ?: descriptions["fallback"]
                            ?: descriptions.values.firstOrNull().orEmpty(),
                        author = authors["fallback"] ?: authors.values.firstOrNull().orEmpty(),
                        designer = designers["fallback"] ?: designers.values.firstOrNull()
                            .orEmpty(),
                        category = readFirstXmlText(root, "widgetCategory")
                            ?: readFirstXmlText(root, "typeTag")
                            ?: IMPORT_RES_TYPE,
                        resSubType = readFirstXmlText(root, "typeTag") ?: DEFAULT_RES_SUB_TYPE,
                        editable = readFirstXmlText(root, "editable") == "true",
                        thirdParties = true,
                        supportAon = false,
                    )
                }
            }
        }.getOrNull()
    }

    private fun readLocaleXmlValues(root: org.w3c.dom.Element, tag: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val nodes = root.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val value = element.textContent?.trim().orEmpty()
            if (value.isBlank()) continue
            val locale = element.getAttribute("locale").takeIf { it.isNotBlank() } ?: "fallback"
            result[locale] = value
        }
        return result
    }

    private fun readFirstXmlText(root: org.w3c.dom.Element, tag: String): String? {
        val nodes = root.getElementsByTagName(tag)
        if (nodes.length <= 0) return null
        return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun resolveMetadataValues(
        options: Bundle?,
        source: MetadataValues?,
        displayNameHint: String,
    ): MetadataValues {
        val defaultTitle = displayNameHint
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { "Imported Wallpaper" }
        val titleFallback = options.optionString(
            RearWallpaperApiContract.BundleKeys.META_TITLE_FALLBACK,
        ) ?: source?.titleFallback ?: defaultTitle
        val titleZhCn = options.optionString(
            RearWallpaperApiContract.BundleKeys.META_TITLE_ZH_CN,
        ) ?: source?.titleZhCn ?: titleFallback
        val descriptionFallback = options.optionString(
            RearWallpaperApiContract.BundleKeys.META_DESCRIPTION_FALLBACK,
        ) ?: source?.descriptionFallback ?: "Imported by REAREye"
        val descriptionZhCn = options.optionString(
            RearWallpaperApiContract.BundleKeys.META_DESCRIPTION_ZH_CN,
        ) ?: source?.descriptionZhCn ?: descriptionFallback

        return MetadataValues(
            titleFallback = titleFallback,
            titleZhCn = titleZhCn,
            descriptionFallback = descriptionFallback,
            descriptionZhCn = descriptionZhCn,
            author = options.optionString(RearWallpaperApiContract.BundleKeys.META_AUTHOR)
                ?: source?.author.orEmpty(),
            designer = options.optionString(RearWallpaperApiContract.BundleKeys.META_DESIGNER)
                ?: source?.designer.orEmpty(),
            category = options.optionString(RearWallpaperApiContract.BundleKeys.META_CATEGORY)
                ?: source?.category ?: IMPORT_RES_TYPE,
            resSubType = options.optionString(RearWallpaperApiContract.BundleKeys.META_RES_SUB_TYPE)
                ?: source?.resSubType ?: DEFAULT_RES_SUB_TYPE,
            editable = options?.getBoolean(
                RearWallpaperApiContract.BundleKeys.META_EDITABLE,
                source?.editable ?: false,
            ) ?: source?.editable ?: false,
            thirdParties = options?.getBoolean(
                RearWallpaperApiContract.BundleKeys.META_THIRD_PARTIES,
                source?.thirdParties ?: true,
            ) ?: source?.thirdParties ?: true,
            supportAon = options?.getBoolean(
                RearWallpaperApiContract.BundleKeys.META_SUPPORT_AON,
                source?.supportAon ?: false,
            ) ?: source?.supportAon ?: false,
        )
    }

    private fun buildMetadataJson(
        base: JSONObject?,
        resId: String,
        packageFile: File,
        metadataFile: File,
        previewPath: String?,
        packageSize: Long,
        updatedAt: Long,
        values: MetadataValues,
    ): JSONObject {
        val json = base?.let { JSONObject(it.toString()) } ?: JSONObject()
        json.put("localId", resId)
        json.put("productId", resId)
        json.put("hash", sha256(packageFile))
        json.put("platform", json.optInt("platform", 0))
        json.put("size", packageSize)
        json.put("updatedTime", updatedAt)
        json.put("version", json.optString("version", "1").ifBlank { "1" })
        json.put("authors", localeObject(values.author, values.author))
        json.put("designers", localeObject(values.designer, values.designer))
        json.put("titles", localeObject(values.titleFallback, values.titleZhCn))
        json.put(
            "descriptions",
            localeObject(values.descriptionFallback, values.descriptionZhCn),
        )
        json.put("builtInPreviews", previewMap(previewPath))
        json.put("thumbnails", previewEntries(previewPath))
        json.put("previews", previewEntries(previewPath))
        json.put("extraMeta", json.optJSONObject("extraMeta") ?: JSONObject())
        json.put("metaPath", metadataFile.absolutePath)
        json.put("contentPath", packageFile.absolutePath)
        json.put("rightsPath", json.optString("rightsPath", ""))
        json.put("screenRatio", json.optString("screenRatio", ""))
        json.put("packageName", json.optString("packageName", "hk.uwu.reareye"))
        json.put("subResourceType", values.category)
        json.put("resSubType", values.resSubType)
        json.put("isRearScreenEditable", values.editable)
        json.put("isThirdParties", values.thirdParties)
        json.put("supportAon", values.supportAon)
        json.put("wallpaperStyle", json.optInt("wallpaperStyle", 0))
        json.put("isSingleResource", true)
        json.put("isRearScreenNeedLogin", false)
        return json
    }

    private fun buildRuntimeItem(
        resId: String,
        applyId: String,
        packagePath: String,
        metadataPath: String,
        previewPath: String?,
        position: Int,
        updatedAt: Long,
        values: MetadataValues,
    ): JSONObject {
        return JSONObject().apply {
            put("resType", values.category)
            put("resId", resId)
            put("resSubType", values.resSubType)
            put("resTypeName", localeObject(values.category, values.category).toString())
            put("applyId", applyId)
            put("resName", localeObject(values.titleFallback, values.titleZhCn).toString())
            put(
                "resDescription",
                localeObject(values.descriptionFallback, values.descriptionZhCn).toString(),
            )
            put("resPreviewPath", previewPath ?: "")
            put("resDesigner", localeObject(values.designer, values.designer).toString())
            put("resLocalPath", packagePath)
            put("resSnapshotPath", packagePath)
            put("metaPath", metadataPath)
            put("metaSnapshotPath", metadataPath)
            put("isDownload", false)
            put("downloadUrl", "")
            put("applyTime", updatedAt)
            put("updateTime", updatedAt)
            put("isNFC", false)
            put("snapshotPreviewPath", previewPath ?: "")
            put("position", position)
            put("editable", values.editable)
            put("isThirdParties", values.thirdParties)
            put("supportAon", values.supportAon)
            put("packageName", "hk.uwu.reareye")
            put("isOnlineResource", false)
            put("onlineId", "")
        }
    }

    private fun applyMetadataToRuntimeItem(
        item: JSONObject,
        metadataPath: String,
        previewPath: String?,
        values: MetadataValues,
    ) {
        item.put("resType", values.category)
        item.put("resSubType", values.resSubType)
        item.put("resTypeName", localeObject(values.category, values.category).toString())
        item.put("resName", localeObject(values.titleFallback, values.titleZhCn).toString())
        item.put(
            "resDescription",
            localeObject(values.descriptionFallback, values.descriptionZhCn).toString(),
        )
        item.put("resDesigner", localeObject(values.designer, values.designer).toString())
        item.put("metaPath", metadataPath)
        item.put("metaSnapshotPath", metadataPath)
        item.put("editable", values.editable)
        item.put("isThirdParties", values.thirdParties)
        item.put("supportAon", values.supportAon)
        if (!previewPath.isNullOrBlank()) {
            item.put("resPreviewPath", previewPath)
            item.put("snapshotPreviewPath", previewPath)
        }
        item.put("updateTime", System.currentTimeMillis())
    }

    private fun localeObject(fallback: String, zhCn: String): JSONObject {
        return JSONObject().apply {
            put("fallback", fallback)
            put("zh_CN", zhCn.ifBlank { fallback })
        }
    }

    private fun previewMap(previewPath: String?): JSONObject {
        val map = JSONObject()
        if (!previewPath.isNullOrBlank()) {
            map.put("fallback", JSONArray().put(previewPath))
            map.put("zh_CN", JSONArray().put(previewPath))
        }
        return map
    }

    private fun previewEntries(previewPath: String?): JSONArray {
        val array = JSONArray()
        if (!previewPath.isNullOrBlank()) {
            array.put(
                JSONObject().apply {
                    put("localPath", previewPath)
                    put("onlinePath", "")
                }
            )
        }
        return array
    }

    private fun readRuntimeRecords(): List<RuntimeWallpaperRecord> {
        val array = readRuntimeArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                item.toRuntimeRecord()?.let(::add)
            }
        }
    }

    private fun readRuntimeArray(): JSONArray {
        val file = resolveRuntimeFile()
        if (!file.isFile) return JSONArray()
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.isBlank()) return JSONArray()
        return runCatching { JSONArray(text) }.getOrElse {
            YLog.warn(it)
            JSONArray()
        }
    }

    private fun writeRuntimeArray(array: JSONArray) {
        val file = resolveRuntimeFile()
        writeTextAtomically(file, array.toString(2))
        ensureReadable(file)
    }

    private fun maxRuntimePosition(array: JSONArray): Int {
        var max = -1
        for (i in 0 until array.length()) {
            max = maxOf(max, array.optJSONObject(i)?.optInt("position", -1) ?: -1)
        }
        return max
    }

    private fun findRuntimeItemIndex(array: JSONArray, wallpaperId: Int): Int {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val resId = item.optNonBlankString("resId") ?: continue
            val applyId = item.optNonBlankString("applyId") ?: continue
            if ((resId + applyId).hashCode() == wallpaperId) return i
        }
        return -1
    }

    private fun JSONObject.toRuntimeRecord(): RuntimeWallpaperRecord? {
        val resId = optNonBlankString("resId") ?: return null
        val applyId = optNonBlankString("applyId") ?: return null
        val resLocalPath = optNonBlankString("resLocalPath")
        val metaPath = optNonBlankString("metaPath")
        val metaSnapshotPath = optNonBlankString("metaSnapshotPath")
        val mamlEditConfigPath = optNonBlankString("mamlEditConfigPath")
        val previewPath = optNonBlankString("snapshotPreviewPath")
            ?: optNonBlankString("resPreviewPath")
        val imported = isReareyeRuntimeItem(resId, resLocalPath, metaPath)
        return RuntimeWallpaperRecord(
            item = this,
            resId = resId,
            applyId = applyId,
            wallpaperId = (resId + applyId).hashCode(),
            resLocalPath = resLocalPath,
            metaPath = metaPath,
            metaSnapshotPath = metaSnapshotPath,
            mamlEditConfigPath = mamlEditConfigPath,
            previewPath = previewPath,
            imported = imported,
            position = optInt("position", -1),
        )
    }

    private fun RuntimeWallpaperRecord.readMetadataValues(): MetadataValues {
        val metaJson = readJsonFile(metaPath?.let(::File))
            ?: readJsonFile(metaSnapshotPath?.let(::File))
        val metaValues = metaJson?.toMetadataValues(item.optNonBlankString("resName") ?: resId)
        val runtimeTitle = item.optJsonLocale("resName")
        val runtimeDescription = item.optJsonLocale("resDescription")
        val runtimeDesigner = item.optJsonLocale("resDesigner")
        val titleFallback = runtimeTitle["fallback"]
            ?: runtimeTitle["zh_CN"]
            ?: metaValues?.titleFallback
            ?: item.optNonBlankString("resName")
            ?: resId
        return MetadataValues(
            titleFallback = titleFallback,
            titleZhCn = runtimeTitle["zh_CN"]
                ?: metaValues?.titleZhCn
                ?: titleFallback,
            descriptionFallback = runtimeDescription["fallback"]
                ?: runtimeDescription["zh_CN"]
                ?: metaValues?.descriptionFallback.orEmpty(),
            descriptionZhCn = runtimeDescription["zh_CN"]
                ?: metaValues?.descriptionZhCn
                ?: runtimeDescription["fallback"].orEmpty(),
            author = metaValues?.author.orEmpty(),
            designer = runtimeDesigner["fallback"]
                ?: runtimeDesigner["zh_CN"]
                ?: metaValues?.designer.orEmpty(),
            category = item.optNonBlankString("resType")
                ?: metaValues?.category
                ?: IMPORT_RES_TYPE,
            resSubType = item.optNonBlankString("resSubType")
                ?: metaValues?.resSubType
                ?: DEFAULT_RES_SUB_TYPE,
            editable = item.optBoolean("editable", metaValues?.editable ?: false),
            thirdParties = item.optBoolean("isThirdParties", metaValues?.thirdParties ?: imported),
            supportAon = item.optBoolean("supportAon", metaValues?.supportAon ?: false),
        )
    }

    private fun JSONObject.toMetadataValues(defaultTitle: String): MetadataValues {
        val titles = optLocaleObject("titles")
        val descriptions = optLocaleObject("descriptions")
        val authors = optLocaleObject("authors")
        val designers = optLocaleObject("designers")
        val titleFallback = titles["fallback"] ?: titles["zh_CN"] ?: defaultTitle
        val descriptionFallback = descriptions["fallback"] ?: descriptions["zh_CN"].orEmpty()
        return MetadataValues(
            titleFallback = titleFallback,
            titleZhCn = titles["zh_CN"] ?: titleFallback,
            descriptionFallback = descriptionFallback,
            descriptionZhCn = descriptions["zh_CN"] ?: descriptionFallback,
            author = authors["fallback"] ?: authors["zh_CN"].orEmpty(),
            designer = designers["fallback"] ?: designers["zh_CN"].orEmpty(),
            category = optNonBlankString("subResourceType")
                ?: optNonBlankString("widgetCategory")
                ?: IMPORT_RES_TYPE,
            resSubType = optNonBlankString("resSubType") ?: DEFAULT_RES_SUB_TYPE,
            editable = optBoolean("isRearScreenEditable", false),
            thirdParties = optBoolean("isThirdParties", true),
            supportAon = optBoolean("supportAon", false),
        )
    }

    private fun isReareyeRuntimeItem(
        resId: String,
        resLocalPath: String?,
        metaPath: String?,
    ): Boolean {
        if (resId.startsWith(IMPORT_RES_PREFIX)) return true
        return listOfNotNull(resLocalPath, metaPath).any { path ->
            path.replace('\\', '/').contains("/$IMPORT_RES_PREFIX")
        }
    }

    private fun deleteImportedFiles(record: RuntimeWallpaperRecord) {
        val root = resolveRuntimeRoot().canonicalFile
        val candidates = listOfNotNull(
            record.resLocalPath?.let(::File)?.parentFile,
            record.metaPath?.let(::File)?.parentFile,
            record.metaSnapshotPath?.let(::File)?.parentFile,
        ).distinctBy { it.absolutePath }

        candidates.forEach { dir ->
            runCatching {
                val canonical = dir.canonicalFile
                val isInsideRoot = canonical.path.startsWith(root.path + File.separator)
                if (isInsideRoot && canonical.name.startsWith(record.resId)) {
                    canonical.deleteRecursively()
                }
            }.onFailure(YLog::warn)
        }
    }

    private fun refreshRuntimePanels() {
        val entries = loadWallpaperEntries()
        if (entries.isEmpty()) return
        val currentIndex = readCurrentSelectionIndex(entries.lastIndex).coerceAtLeast(0)
        mainPanel?.let { panel ->
            dispatchSelection(panel, entries.map { it.widget }, currentIndex)
        }
        refreshSchedule(forceApply = true)
    }

    private fun resolveRuntimeRoot(): File {
        return File("/data/system/theme_magic/users/${currentUserId()}/rearScreen")
    }

    private fun resolveRuntimeFile(): File {
        return File(resolveRuntimeRoot(), "runtime.json")
    }

    private fun resolveTemplateConfigFile(record: RuntimeWallpaperRecord): File {
        return File(File(resolveRuntimeRoot(), "${record.resId}_${record.applyId}"), "editConfig")
    }

    private fun currentUserId(): Int {
        return (Process.myUid() / 100000).coerceAtLeast(0)
    }

    private fun readOneConfigJson(path: String?): String? {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { WidgetTemplateConfigRepository.decodeOneConfig(it) != null }
    }

    private fun writeTextAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        tempFile.writeText(text)
        if (target.exists()) target.delete()
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
        ensureReadable(target)
    }

    private fun sha256(file: File): String {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("${file.length()}_${file.lastModified()}")
    }

    private fun ensureReadableRecursive(file: File) {
        if (file.isDirectory) {
            ensureReadable(file)
            file.listFiles()?.forEach(::ensureReadableRecursive)
            return
        }
        ensureReadable(file)
    }

    @SuppressLint("SetWorldReadable")
    private fun ensureReadable(file: File) {
        file.setReadable(true, false)
        file.parentFile?.setReadable(true, false)
        file.parentFile?.setExecutable(true, false)
    }

    private fun Bundle?.optionString(key: String): String? {
        return this?.getString(key)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNonBlankString(key: String): String? {
        val value = optString(key, "").trim()
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optLocaleObject(key: String): Map<String, String> {
        val json = optJSONObject(key) ?: return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val locale = keys.next()
                val value = json.optString(locale, "").takeIf { it.isNotBlank() } ?: continue
                put(locale, value)
            }
        }
    }

    private fun JSONObject.optJsonLocale(key: String): Map<String, String> {
        val raw = optNonBlankString(key) ?: return emptyMap()
        return runCatching { JSONObject(raw).optLocaleObjectFromSelf() }
            .getOrDefault(mapOf("fallback" to raw))
    }

    private fun JSONObject.optLocaleObjectFromSelf(): Map<String, String> {
        return buildMap {
            val keys = keys()
            while (keys.hasNext()) {
                val locale = keys.next()
                val value = optString(locale, "").takeIf { it.isNotBlank() } ?: continue
                put(locale, value)
            }
        }
    }

    private fun MetadataValues.preferredTitle(): String {
        return titleZhCn.ifBlank { titleFallback }
    }

    private fun MetadataValues.preferredDescription(): String {
        return descriptionZhCn.ifBlank { descriptionFallback }
    }

    private fun UUID.shortId(): String {
        return toString().replace("-", "").take(12)
    }

    private fun Any.wallpaperSpecId(): Int? {
        val spec = wallpaperSpecObject() ?: return null
        return runCatching {
            spec.asResolver().firstField {
                name = resolveWallpaperSpecIdFieldName()
            }.get() as? Int
        }.getOrNull()
    }

    private fun Any.wallpaperSpecExtras(): Bundle? {
        val spec = wallpaperSpecObject() ?: return null
        return runCatching {
            spec.asResolver().firstField {
                name = resolveWallpaperSpecExtrasFieldName()
            }.get() as? Bundle
        }.getOrNull()
    }

    private fun Any.wallpaperSpecObject(): Any? {
        if (javaClass.name == resolveWallpaperSpecClassName()) return this
        return runCatching {
            asResolver().firstField {
                superclass()
                name = resolveWidgetSpecFieldName()
            }.get<Any?>()
        }.getOrNull()
    }

    private fun readMainPanelEditMode(panel: Any): Boolean {
        return runCatching {
            panel.asResolver().firstField { name = resolveMainPanelEditModeFieldName() }
                .get() as? Boolean
        }.getOrNull() ?: false
    }

    private fun readMainPanelResumedState(panel: Any): Boolean {
        return runCatching {
            panel.asResolver().firstField { name = resolveMainPanelResumedFieldName() }
                .get() as? Boolean
        }.getOrNull() ?: false
    }

    private fun readMainPanelAodState(panel: Any): Boolean {
        return runCatching {
            panel.asResolver().firstField { name = resolveMainPanelAodFieldName() }
                .get() as? Boolean
        }.getOrNull() ?: false
    }

    private fun debugLog(message: String) {
        if (prefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            YLog.debug("[$TAG] $message")
        }
    }

    private fun debugFailure(message: String, error: Throwable) {
        if (prefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            YLog.debug("[$TAG] $message")
            YLog.warn(error)
        }
    }

    private fun describeViewState(view: View?): String {
        if (view == null) return "<null>"
        return buildString {
            append(view.javaClass.simpleName)
            append("{attached=")
            append(view.isAttachedToWindow)
            append(", shown=")
            append(view.isShown)
            append(", visibility=")
            append(view.visibility)
            append(", alpha=")
            append(view.alpha)
            append(", size=")
            append(view.width)
            append('x')
            append(view.height)
            append(", measured=")
            append(view.measuredWidth)
            append('x')
            append(view.measuredHeight)
            (view as? ViewGroup)?.let {
                append(", children=")
                append(it.childCount)
            }
            append('}')
        }
    }

    private fun throwableMessage(error: Throwable, fallback: String): String {
        return error.message?.trim()?.takeIf { it.isNotBlank() }
            ?: error.cause?.message?.trim()?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName.takeIf { it.isNotBlank() }
            ?: fallback
    }

    private fun loadPreviewBytes(previewPath: String?): ByteArray? {
        val path = previewPath?.takeIf { it.isNotBlank() } ?: return null
        decodeZipPreviewPath(path)?.let { (packagePath, entryName) ->
            val packageFile = File(packagePath)
            if (!packageFile.isFile) return null
            return runCatching {
                ZipFile(packageFile).use { zip ->
                    val entry = findZipEntry(zip, entryName) ?: return@use null
                    if (entry.isDirectory || entry.size > MAX_PREVIEW_BYTES) return@use null
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    compressPreviewBytes(bytes)
                }
            }.getOrNull()
        }

        val file = File(path)
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, 640)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null
        return bitmap.toCompressedPreviewBytes(Bitmap.CompressFormat.JPEG, 90)
    }

    private fun compressPreviewBytes(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > MAX_PREVIEW_BYTES) return null
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, 640)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return bitmap.toCompressedPreviewBytes(Bitmap.CompressFormat.JPEG, 90)
    }

    private fun Bitmap.toCompressedPreviewBytes(
        format: Bitmap.CompressFormat,
        quality: Int,
    ): ByteArray? {
        return ByteArrayOutputStream().use { output ->
            val ok = compress(format, quality, output)
            recycle()
            if (!ok) return null
            output.toByteArray()
        }
    }

    private fun isReadablePreviewPath(path: String): Boolean {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return false
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun computeInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        var targetWidth = width
        var targetHeight = height
        while (targetWidth > maxSize || targetHeight > maxSize) {
            sample *= 2
            targetWidth /= 2
            targetHeight /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun buildPreviewSignature(previewPath: String?): String {
        decodeZipPreviewPath(previewPath.orEmpty())?.let { (packagePath, entryName) ->
            val file = File(packagePath)
            if (file.isFile) {
                return "zip_${file.absolutePath.hashCode()}_${entryName.hashCode()}_${file.length()}_${file.lastModified()}"
            }
        }
        val file = previewPath?.let(::File)
        if (file != null && file.isFile) {
            return "${file.absolutePath.hashCode()}_${file.length()}_${file.lastModified()}"
        }
        return "missing"
    }

    private fun encodeZipPreviewPath(packagePath: String, entryName: String): String {
        return ZIP_PREVIEW_PREFIX + packagePath + ZIP_PREVIEW_SEPARATOR + entryName
    }

    private fun decodeZipPreviewPath(path: String): Pair<String, String>? {
        if (!path.startsWith(ZIP_PREVIEW_PREFIX)) return null
        val payload = path.removePrefix(ZIP_PREVIEW_PREFIX)
        val separatorIndex = payload.lastIndexOf(ZIP_PREVIEW_SEPARATOR)
        if (separatorIndex <= 0) return null
        val packagePath = payload.substring(0, separatorIndex)
        val entryName = payload.substring(separatorIndex + ZIP_PREVIEW_SEPARATOR.length)
        if (packagePath.isBlank() || entryName.isBlank()) return null
        return packagePath to entryName
    }

    private fun readLocalePreviewSuffix(): String? {
        return runCatching {
            resolveDeviceConfigClass().toClass().resolve().firstField {
                name = resolveDeviceConfigLocaleSuffixFieldName()
            }
                .get() as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /*private fun Bundle?.resolvePreviewPath(localeSuffix: String?): String? {
        return previewPathCandidates(localeSuffix).firstOrNull()
    }*/

    private fun Bundle?.previewPathCandidates(localeSuffix: String?): List<String> {
        if (this == null) return emptyList()
        return buildList {
            localeSuffix?.let { getString("snapshotPath_$it") }?.let(::add)
            getString("snapshotPath")?.let(::add)
            getString("resPreviewPath")?.let(::add)
            getString("snapshotPreviewPath")?.let(::add)
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun Int.floorMod(size: Int): Int {
        if (size <= 0) return 0
        val mod = this % size
        return if (mod < 0) mod + size else mod
    }
}