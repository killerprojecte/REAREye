package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.media.MediaDataSource
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.util.Collections
import java.util.WeakHashMap

@OptIn(DexKitExperimentalApi::class)
class VideoProgressResumeModule : RoxyHooker() {
    companion object {
        private const val TAG = "REAREye-VideoProgressResume"
        private const val VIDEO_ELEMENT_CLASS_CACHE_KEY = "SSC_VIDEO_PROGRESS_VIDEO_ELEMENT_CLASS"
        private const val TEXTURE_VIDEO_VIEW_CLASS_CACHE_KEY = "SSC_TEXTURE_VIDEO_VIEW_CLASS"
        private const val BASE_VIDEO_VIEW_CLASS_CACHE_KEY = "SSC_BASE_VIDEO_VIEW_CLASS"
        private const val SURFACE_VIDEO_VIEW_CLASS_CACHE_KEY = "SSC_SURFACE_VIDEO_VIEW_CLASS"
        private const val MAML_WIDGET_VIEW_CLASS_CACHE_KEY =
            "SSC_VIDEO_PROGRESS_MAML_WIDGET_VIEW_CLASS"

        private const val RESTORE_SCHEDULE_DEBOUNCE_MS = 900L
        private val restoreDelays = longArrayOf(0L, 120L, 350L, 800L)
    }

    private val restoreHandler by lazy { Handler(Looper.getMainLooper()) }
    private val restoreRunnables = ArrayList<Runnable>(restoreDelays.size)

    @Volatile
    private var lastRestoreScheduleAt = 0L

    override fun onHotReloadQuiesce() {
        check(releaseForReload()) {
            "VideoProgressResumeModule failed to release reload resources"
        }
    }

    private fun releaseForReload(): Boolean {
        var success = true
        val remaining = ArrayList<Runnable>(restoreRunnables.size)
        restoreRunnables.forEach { runnable ->
            val removed = runCatching {
                restoreHandler.removeCallbacks(runnable)
                true
            }.onFailure {
                success = false
                YLog.error("Failed to remove video progress restore task during reload", it)
            }.getOrDefault(false)
            if (!removed) remaining += runnable
        }
        restoreRunnables.clear()
        restoreRunnables += remaining
        lastRestoreScheduleAt = 0L
        VideoProgressStore.clear()
        return success
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val versionCode = resolveHookPackageVersionCode(
                hookSystemContext,
                hookAppInfo.packageName,
                hookAppInfo.sourceDir,
            )
            val bridge = runtime.manage(
                createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = versionCode,
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                )
            )

            resolveVideoElementClassName(bridge)?.let { hookVideoElementClass(it) }
                ?: YLog.warn("$TAG skip VideoElement hooks: DexKit unresolved")
            resolveTextureVideoViewClassName(bridge)?.let { hookVideoHolderClass(it) }
                ?: YLog.warn("$TAG skip TextureVideoView hooks: DexKit unresolved")
            resolveBaseVideoViewClassName(bridge)?.let { hookVideoHolderClass(it) }
                ?: YLog.warn("$TAG skip BaseVideoView hooks: DexKit unresolved")
            resolveSurfaceVideoViewClassName(bridge)?.let { hookSurfaceVideoView(it) }
                ?: YLog.warn("$TAG skip SurfaceVideoView hooks: DexKit unresolved")
            hookRestoreSchedulers(bridge)
        }
    }

    private fun PackageScope.hookVideoElementClass(className: String) {
        runCatching {
            className.toClass().resolve().firstMethod {
                name = "seekTo"
                parameterCount = 1
                returnType = Void.TYPE
                parameters(Int::class.java)
            }.hook {
                replaceUnit {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS,
                            false
                        )
                    ) {
                        callOriginal(*args)
                        return@replaceUnit
                    }

                    val position = args(0).int()
                    val holder = VideoProgressStore.readVideoHolder(instance)
                    if (VideoProgressStore.shouldSkipReset(
                            view = holder,
                            position = position,
                            debugEnabled = isMoreDebugEnabled(),
                            reason = "VideoElement.seekTo",
                        )
                    ) {
                        return@replaceUnit
                    }

                    callOriginal(*args)
                }
            }
        }.onFailure { YLog.warn(it) }
    }

    private fun PackageScope.hookVideoHolderClass(className: String) {
        val classRef = className.toClass().resolve()

        runCatching {
            classRef.firstMethod {
                name = "start"
                parameterCount = 0
            }.hook {
                after {
                    VideoProgressStore.markStarted(instance)
                }
            }
        }.onFailure { YLog.warn(it) }

        runCatching {
            classRef.firstMethod {
                name = "seekTo"
                parameterCount = 1
                returnType = Void.TYPE
                parameters(Int::class.java)
            }.hook {
                replaceUnit {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS,
                            false
                        )
                    ) {
                        callOriginal(*args)
                        return@replaceUnit
                    }

                    val position = args(0).int()
                    if (VideoProgressStore.shouldSkipReset(
                            view = instance,
                            position = position,
                            debugEnabled = isMoreDebugEnabled(),
                            reason = "${instance!!::class.java.name}.seekTo",
                        )
                    ) {
                        return@replaceUnit
                    }

                    callOriginal(*args)
                }
            }
        }.onFailure { YLog.warn(it) }

        listOf("pause", "stopPlayback", "onDetachedFromWindow").forEach { methodName ->
            runCatching {
                classRef.firstMethod {
                    name = methodName
                    parameterCount = 0
                }.hook {
                    before {
                        VideoProgressStore.save(instance, isMoreDebugEnabled())
                    }
                }
            }.onFailure { YLog.warn(it) }
        }

        runCatching {
            classRef.firstMethod {
                name = "releaseMedia"
                parameterCount = 1
                returnType = Void.TYPE
            }.hook {
                before {
                    VideoProgressStore.save(instance, isMoreDebugEnabled())
                }
            }
        }.onFailure { YLog.warn(it) }

        runCatching {
            classRef.firstMethod {
                name = "setVideoPath"
                parameterCount = 2
                returnType = Void.TYPE
            }.hook {
                after {
                    VideoProgressStore.register(instance)
                    if (VideoProgressStore.onSourceReopened(
                            view = instance,
                            debugEnabled = isMoreDebugEnabled(),
                            reason = "setVideoPath",
                        )
                    ) {
                        scheduleRestore("sourceReopen:setVideoPath")
                    }
                }
            }
        }.onFailure { YLog.warn(it) }

        runCatching {
            classRef.firstMethod {
                name = "setVideoDataSource"
                parameterCount = 1
                returnType = Void.TYPE
                parameters(MediaDataSource::class.java)
            }.hook {
                after {
                    VideoProgressStore.register(instance)
                    if (VideoProgressStore.onSourceReopened(
                            view = instance,
                            debugEnabled = isMoreDebugEnabled(),
                            reason = "setVideoDataSource",
                        )
                    ) {
                        scheduleRestore("sourceReopen:setVideoDataSource")
                    }
                }
            }
        }.onFailure {
            runCatching {
                classRef.firstMethod {
                    name = "setVideoDataSource"
                    parameterCount = 1
                    returnType = Void.TYPE
                }.hook {
                    after {
                        VideoProgressStore.register(instance)
                        if (VideoProgressStore.onSourceReopened(
                                view = instance,
                                debugEnabled = isMoreDebugEnabled(),
                                reason = "setVideoDataSource",
                            )
                        ) {
                            scheduleRestore("sourceReopen:setVideoDataSource")
                        }
                    }
                }
            }.onFailure(YLog::warn)
        }

        runCatching {
            classRef.firstMethod {
                name = "updateStateVar"
                parameterCount = 1
                returnType = Void.TYPE
                superclass()
            }.hook {
                after {
                    val state = args(0).int()
                    VideoProgressStore.onStateChanged(instance, state)
                }
            }
        }.onFailure { YLog.warn(it) }
    }

    private fun PackageScope.hookSurfaceVideoView(className: String) {
        runCatching {
            className.toClass().resolve().firstMethod {
                name = "onSurfaceDestroyed"
                parameterCount = 0
            }.hook {
                before {
                    VideoProgressStore.save(instance, isMoreDebugEnabled())
                }
            }
        }.onFailure { YLog.warn(it) }
    }

    private fun PackageScope.hookRestoreSchedulers(bridge: DexKitCacheBridge.RecyclableBridge) {
        runCatching {
            val widgetViewClassName = resolveMamlWidgetViewClassName(bridge)
                ?: return@runCatching YLog.warn("$TAG skip widget view restore hooks: DexKit unresolved")
            val widgetViewRef = widgetViewClassName.toClass().resolve()
            widgetViewRef.firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hook {
                after {
                    if (hookPrefs.getBoolean(
                            ConfigKeys.HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS,
                            false
                        )
                    ) {
                        scheduleRestore("widgetOnResume")
                    }
                }
            }
        }.onFailure { YLog.warn(it) }
    }

    private fun PackageScope.debugLog(message: String) {
        if (isMoreDebugEnabled()) YLog.debug(message)
    }

    private fun PackageScope.isMoreDebugEnabled(): Boolean {
        return hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)
    }

    private fun PackageScope.scheduleRestore(reason: String) {
        if (!hookPrefs.getBoolean(ConfigKeys.HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS, false)) return
        if (!VideoProgressStore.hasSavedProgress()) {
            debugLog("$TAG skip restore schedule reason=$reason saved=false")
            return
        }

        val debugEnabled = isMoreDebugEnabled()
        VideoProgressStore.armResetGuard(debugEnabled, reason)

        val now = System.currentTimeMillis()
        if (now - lastRestoreScheduleAt < RESTORE_SCHEDULE_DEBOUNCE_MS) {
            debugLog("$TAG skip restore schedule reason=$reason debounce=true")
            return
        }
        lastRestoreScheduleAt = now

        debugLog("$TAG schedule restore reason=$reason")
        restoreRunnables.forEach(restoreHandler::removeCallbacks)
        restoreRunnables.clear()
        restoreDelays.forEach { delay ->
            val restoreReason = "$reason+${delay}ms"
            val runnable =
                Runnable { VideoProgressStore.restoreAll(isMoreDebugEnabled(), restoreReason) }
            restoreRunnables += runnable
            restoreHandler.postDelayed(runnable, delay)
        }
    }

    private fun PackageScope.resolveVideoElementClassName(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String? {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = VIDEO_ELEMENT_CLASS_CACHE_KEY,
        ) {
            findClass {
                searchPackages("com.miui.maml.elements.video")
                matcher {
                    usingStrings(
                        "VideoElement",
                        "config: path ",
                        "seekTo ",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveTextureVideoViewClassName(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String? {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = TEXTURE_VIDEO_VIEW_CLASS_CACHE_KEY,
        ) {
            findClass {
                searchPackages("com.miui.maml.elements.video")
                matcher {
                    usingStrings(
                        "TextureVideoView",
                        ".position",
                        ".duration",
                        ".playState",
                        "openVideo failed mPath=",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveBaseVideoViewClassName(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String? {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = BASE_VIDEO_VIEW_CLASS_CACHE_KEY,
        ) {
            findClass {
                searchPackages("com.miui.maml.elements.video")
                matcher {
                    usingStrings(
                        "BaseVideoView",
                        ".position",
                        ".duration",
                        ".playState",
                        "openVideo failedmPath=",
                    )
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveSurfaceVideoViewClassName(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String? {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = SURFACE_VIDEO_VIEW_CLASS_CACHE_KEY,
        ) {
            findClass {
                searchPackages("com.miui.maml.elements.video")
                matcher {
                    usingStrings("SurfaceVideoView", "superwallpaper.SurfaceVideoView")
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveMamlWidgetViewClassName(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String? {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = MAML_WIDGET_VIEW_CLASS_CACHE_KEY,
        ) {
            findClass {
                matcher {
                    usingStrings("MamlWidget", "Send command to ", "selected = ", "resume")
                }
            }.singleOrNull()
        }
    }

    private object VideoProgressStore {
        private const val STATE_PLAYBACK_COMPLETED = 5
        private const val NEAR_END_TOLERANCE_MS = 1000
        private const val RESET_SEEK_GUARD_MS = 15_000L
        private const val RESET_SKIP_LOG_DEBOUNCE_MS = 500L
        private const val RESTORE_POSITION_TOLERANCE_MS = 500

        private data class VideoState(
            var key: String? = null,
            var position: Int = 0,
            var duration: Int = -1,
            var wantResume: Boolean = false,
            var completed: Boolean = false,
            var savedAt: Long = 0L,
            var resetGuardUntil: Long = 0L,
            var lastResetSkipLogAt: Long = 0L,
        )

        private val states = Collections.synchronizedMap(WeakHashMap<Any, VideoState>())
        private val liveViews = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

        fun register(view: Any?) {
            if (view != null) liveViews.add(view)
        }

        fun markStarted(view: Any?) {
            if (view == null) return
            register(view)
            val state = states.getOrPut(view) { VideoState() }
            state.key = readVideoKey(view) ?: state.key
            state.completed = false
        }

        fun onStateChanged(view: Any?, stateCode: Int) {
            if (view == null) return
            register(view)
            val state = states.getOrPut(view) { VideoState() }
            if (stateCode == STATE_PLAYBACK_COMPLETED) {
                state.completed = true
                state.wantResume = false
                state.resetGuardUntil = 0L
            } else if (stateCode != 0) {
                state.completed = false
            }
        }

        fun save(view: Any?, debugEnabled: Boolean) {
            if (view == null) return
            register(view)
            runCatching {
                val position = view.callIntMethod("getCurrentPosition")
                val duration = view.callIntMethod("getDuration")
                if (position <= 0) return@runCatching
                if (duration > 0 && position >= duration - NEAR_END_TOLERANCE_MS) return@runCatching

                val now = System.currentTimeMillis()
                val state = states.getOrPut(view) { VideoState() }
                state.key = readVideoKey(view) ?: state.key
                state.position = position
                state.duration = duration
                state.wantResume = true
                state.completed = false
                state.savedAt = now
                state.resetGuardUntil = maxOf(state.resetGuardUntil, now + RESET_SEEK_GUARD_MS)
                if (debugEnabled) {
                    YLog.debug("$TAG save position=$position duration=$duration key=${state.key}")
                }
            }.onFailure {
                YLog.warn(it)
            }
        }

        fun hasSavedProgress(): Boolean {
            return states.values.any { state ->
                state.position > 0 && !state.completed
            }
        }

        fun clear() {
            states.clear()
            liveViews.clear()
        }

        fun armResetGuard(debugEnabled: Boolean, reason: String): Boolean {
            val now = System.currentTimeMillis()
            var armedCount = 0
            states.values.forEach { state ->
                if (state.position > 0 && !state.completed) {
                    state.resetGuardUntil = maxOf(state.resetGuardUntil, now + RESET_SEEK_GUARD_MS)
                    armedCount++
                }
            }
            if (debugEnabled && armedCount > 0) {
                YLog.debug("$TAG arm reset guard reason=$reason count=$armedCount")
            }
            return armedCount > 0
        }

        fun shouldSkipReset(
            view: Any?,
            position: Int,
            debugEnabled: Boolean,
            reason: String,
        ): Boolean {
            if (view == null || position != 0) return false
            register(view)

            val state = states[view] ?: return false
            val now = System.currentTimeMillis()
            if (!state.isProtectedFromReset(now)) return false
            if (!state.matchesCurrentVideo(view)) return false

            if (debugEnabled && now - state.lastResetSkipLogAt > RESET_SKIP_LOG_DEBOUNCE_MS) {
                state.lastResetSkipLogAt = now
                YLog.debug(
                    "$TAG skip reset seek reason=$reason requested=0 " +
                            "restorePosition=${state.position} duration=${state.duration} key=${state.key}"
                )
            }
            return true
        }

        fun onSourceReopened(view: Any?, debugEnabled: Boolean, reason: String): Boolean {
            if (view == null) return false
            register(view)

            val state = states[view] ?: return false
            val now = System.currentTimeMillis()
            if (!state.isProtectedFromReset(now)) return false
            if (!state.matchesCurrentVideo(view)) return false

            state.wantResume = true
            state.resetGuardUntil = maxOf(state.resetGuardUntil, now + RESET_SEEK_GUARD_MS)
            if (debugEnabled) {
                YLog.debug(
                    "$TAG rearm restore reason=$reason position=${state.position} " +
                            "duration=${state.duration} key=${state.key}"
                )
            }
            return true
        }

        fun restoreAll(debugEnabled: Boolean, reason: String) {
            liveViews.toList().forEach { restoreOne(it, debugEnabled, reason) }
        }

        fun readVideoHolder(videoElement: Any?): Any? {
            if (videoElement == null) return null
            return runCatching {
                videoElement.asResolver().firstField {
                    name = "mView"
                    superclass()
                }.get()
            }.getOrNull()
        }

        private fun restoreOne(view: Any?, debugEnabled: Boolean, reason: String) {
            if (view == null) return
            val state = states[view] ?: return
            if (!state.wantResume || state.completed || state.position <= 0) return
            val savedKey = state.key
            val currentKey = readVideoKey(view)
            if (savedKey != null && currentKey != null && savedKey != currentKey) return

            runCatching {
                val currentPosition =
                    runCatching { view.callIntMethod("getCurrentPosition") }.getOrNull()
                if (currentPosition != null &&
                    currentPosition > 0 &&
                    currentPosition >= state.position - RESTORE_POSITION_TOLERANCE_MS
                ) {
                    view.asResolver().firstMethod {
                        name = "start"
                        parameterCount = 0
                        superclass()
                    }.invoke()
                    state.wantResume = false
                    state.resetGuardUntil = maxOf(
                        state.resetGuardUntil,
                        System.currentTimeMillis() + RESET_SEEK_GUARD_MS
                    )
                    if (debugEnabled) {
                        YLog.debug(
                            "$TAG resume start reason=$reason currentPosition=$currentPosition " +
                                    "savedPosition=${state.position} duration=${state.duration} key=$savedKey"
                        )
                    }
                    return@runCatching
                }

                view.asResolver().firstMethod {
                    name = "seekTo"
                    parameterCount = 1
                    superclass()
                }.invoke(state.position)
                view.asResolver().firstMethod {
                    name = "start"
                    parameterCount = 0
                    superclass()
                }.invoke()
                state.wantResume = false
                state.resetGuardUntil =
                    maxOf(state.resetGuardUntil, System.currentTimeMillis() + RESET_SEEK_GUARD_MS)
                if (debugEnabled) {
                    YLog.debug(
                        "$TAG restore reason=$reason position=${state.position} " +
                                "duration=${state.duration} key=$savedKey"
                    )
                }
            }.onFailure {
                YLog.warn(it)
            }
        }

        private fun VideoState.isProtectedFromReset(now: Long): Boolean {
            return position > 0 && !completed && (wantResume || now <= resetGuardUntil)
        }

        private fun VideoState.matchesCurrentVideo(view: Any): Boolean {
            val currentKey = readVideoKey(view)
            if (key == null && currentKey != null) {
                key = currentKey
            }
            return key == null || currentKey == null || key == currentKey
        }

        private fun readVideoKey(view: Any): String? {
            val path = view.readFieldValue("mPath") as? String
            if (!path.isNullOrBlank()) return "path:$path"

            val dataSource = view.readFieldValue("mDataSource") ?: return null
            val sourcePath = runCatching {
                dataSource.asResolver().firstMethod {
                    name = "getPath"
                    parameterCount = 0
                    superclass()
                }.invoke<String?>()
            }.getOrNull()
            if (!sourcePath.isNullOrBlank()) return "source:$sourcePath"

            return "source:${System.identityHashCode(dataSource)}"
        }

        private fun Any.callIntMethod(name: String): Int {
            return asResolver().firstMethod {
                this.name = name
                parameterCount = 0
                superclass()
            }.invoke<Int>() ?: 0
        }

        private fun Any.readFieldValue(name: String): Any? {
            return runCatching {
                asResolver().firstField {
                    this.name = name
                    superclass()
                }.get()
            }.getOrNull()
        }
    }
}
