package hk.uwu.reareye.hook.scopes.subscreencenter.modules.lyrics

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.support.instanceClass
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.lyrics.LyricParser
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.LyricProvider
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(DexKitExperimentalApi::class)
class LyriconHook : RoxyHooker() {
    private val lyricParser = LyricParser()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope by lazy(LazyThreadSafetyMode.NONE) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @Volatile
    private var latestLyricLrc: String = ""

    @Volatile
    private var lastLyricId: String? = null

    @Volatile
    private var lastLyricLrc: String = ""

    @Volatile
    private var currentProvider: ProviderInfo? = null
    private val elements: CopyOnWriteArrayList<Any> = CopyOnWriteArrayList<Any>()
    private val managedElementStates =
        Collections.synchronizedMap(WeakHashMap<Any, ManagedElementState>())

    @Volatile
    var monitor: LyriconSubscriber? = null

    @Volatile
    var superLyricStub: ISuperLyricReceiver.Stub? = null

    override fun onHotReloadQuiesce() {
        check(releaseForReload()) {
            "LyriconHook failed to release reload resources"
        }
    }

    private fun releaseForReload(): Boolean {
        var success = true
        monitor?.let { subscriber ->
            var subscriberSuccess = true
            runCatching { subscriber.unregister() }
                .onFailure {
                    subscriberSuccess = false
                    YLog.error("Failed to unregister Lyricon subscriber during reload", it)
                }
            runCatching { subscriber.destroy() }
                .onFailure {
                    subscriberSuccess = false
                    YLog.error("Failed to destroy Lyricon subscriber during reload", it)
                }
            if (subscriberSuccess) {
                monitor = null
            } else {
                success = false
            }
        }

        superLyricStub?.let { receiver ->
            val receiverSuccess = runCatching {
                SuperLyricHelper.unregisterReceiver(receiver)
                true
            }.onFailure {
                YLog.error("Failed to unregister SuperLyric receiver during reload", it)
            }.getOrDefault(false)
            if (receiverSuccess) {
                superLyricStub = null
            } else {
                success = false
            }
        }

        val jobsToCancel = ArrayList<Job>()
        scope.coroutineContext[Job]?.let { jobsToCancel += it }
        scope.cancel()
        mainScope.coroutineContext[Job]?.let { jobsToCancel += it }
        mainScope.cancel()

        val elementsToClean = synchronized(managedElementStates) {
            managedElementStates.keys.toList()
        }
        var tickerCleanupSucceeded = true
        elementsToClean.forEach { element ->
            if (!unregisterPreTicker(element)) tickerCleanupSucceeded = false
        }
        if (!tickerCleanupSucceeded) success = false

        synchronized(managedElementStates) {
            managedElementStates.values.forEach { state ->
                state.managedProgressJob?.let { job ->
                    job.cancel()
                    jobsToCancel += job
                }
            }
        }
        val cancellationBarrierSucceeded = awaitCancellationBarrier(jobsToCancel)
        if (!cancellationBarrierSucceeded) success = false

        synchronized(managedElementStates) {
            if (cancellationBarrierSucceeded) {
                managedElementStates.values.forEach { state ->
                    state.managedProgressJob = null
                }
            }
            if (tickerCleanupSucceeded && cancellationBarrierSucceeded) {
                managedElementStates.clear()
            }
        }
        if (tickerCleanupSucceeded && cancellationBarrierSucceeded) elements.clear()
        latestLyricLrc = ""
        lastLyricId = null
        lastLyricLrc = ""
        currentProvider = null
        return success
    }

    private fun awaitCancellationBarrier(jobs: List<Job>): Boolean {
        val distinctJobs = jobs.distinct()
        if (distinctJobs.isEmpty()) return true
        return runCatching {
            runBlocking {
                withTimeoutOrNull(CANCELLATION_BARRIER_TIMEOUT_MS.milliseconds) {
                    distinctJobs.joinAll()
                    true
                } ?: false
            }
        }.onFailure {
            YLog.error("Lyricon cancellation barrier failed", it)
        }.getOrDefault(false).also { completed ->
            if (!completed) {
                YLog.error(
                    "Lyricon cancellation barrier timed out: jobs=${distinctJobs.size} " +
                            "timeoutMs=$CANCELLATION_BARRIER_TIMEOUT_MS"
                )
            }
        }
    }

    override fun PackageScope.onHook() {
        loadApp("com.android.systemui") {
            this.lifecycle {
                onCreate {
                    val context = hookAppContext ?: this.context
                    if (LyricProvider.fromValue(
                            hookPrefs.getInt(
                                ConfigKeys.LYRIC_PROVIDER,
                                ConfigKeys.LYRIC_PROVIDER_DEFAULT
                            )
                        ) == LyricProvider.LYRICON
                    ) {
                        if (!(isPackageInstalled(
                                context,
                                TARGET_LYRICON_PACKAGE
                            ) || isPackageInstalled(context, LYRICON_CORE_PACKAGE))
                        ) {
                            YLog.info("Lyricon is not found, starting bundled central")
                            BridgeCentral.initialize(context)
                            BridgeCentral.sendBootCompleted()
                        } else {
                            YLog.info("Lyricon is found, skip to start central")
                        }
                    }
                }
            }
        }

        loadApp("com.xiaomi.subscreencenter") {
            this.lifecycle {
                // Hot reload restores the live Application attach event into the new runtime.
                // Application.onCreate is not synthesized across module generations.
                onAttach(replay = true) {
                    val context = hookAppContext ?: this.context
                    when (LyricProvider.fromValue(
                        hookPrefs.getInt(
                            ConfigKeys.LYRIC_PROVIDER,
                            ConfigKeys.LYRIC_PROVIDER_DEFAULT
                        )
                    )) {
                        LyricProvider.LYRICON -> {
                            val listener = createLyricListener()
                            val lyriconMonitor =
                                io.github.proify.lyricon.subscriber.LyriconFactory.createSubscriber(
                                    context
                                )
                            lyriconMonitor.subscribeActivePlayer(listener)
                            lyriconMonitor.register()
                            monitor = lyriconMonitor
                            YLog.info("Registered lyricon player monitor")
                        }

                        LyricProvider.SUPER_LYRIC -> {
                            if (!SuperLyricHelper.isAvailable()) {
                                YLog.warn("SuperLyric is not available, it must be exists or higher than version 3.1")
                                return@onAttach
                            }
                            superLyricStub = object : ISuperLyricReceiver.Stub() {
                                override fun onStop(publisher: String, data: SuperLyricData) {
                                }

                                override fun onLyric(publisher: String, data: SuperLyricData) {
                                    scope.launch {
                                        runCatching {
                                            if (hookPrefs.getBoolean(
                                                    ConfigKeys.MORE_DEBUG,
                                                    false
                                                )
                                            ) {
                                                YLog.debug("onSuperLyric ${data.lyric} ${data.translation}")
                                            }
                                            if (data.hasLyric()) {
                                                val mode = hookPrefs.getInt(
                                                    ConfigKeys.SUPER_LYRIC_DISPLAY_MODE,
                                                    ConfigKeys.SUPER_LYRIC_DISPLAY_MODE_DEFAULT
                                                )
                                                val rawLyric = data.lyric!!
                                                val originalLines = rawLyric.text.split("\n")
                                                val lyric = when {
                                                    LyricParser.DisplayMode.shouldShowTranslation(
                                                        mode
                                                    ) -> {
                                                        if (data.hasTranslation()) {
                                                            val translation = data.translation!!
                                                            translation.text
                                                        } else if (originalLines.size > 1) {
                                                            originalLines[1]
                                                        } else {
                                                            rawLyric.text
                                                        }
                                                    }

                                                    else -> {
                                                        originalLines[0]
                                                    }
                                                }

                                                if (lyric.isNotEmpty()) {
                                                    updateFallbackLyric(lyric)
                                                }
                                            }
                                        }.onFailure {
                                            YLog.error(it)
                                        }
                                    }
                                }
                            }
                            SuperLyricHelper.registerReceiver(superLyricStub!!)
                            YLog.info("Registered super-lyric listener")
                        }
                    }
                }

                onTerminate {
                    monitor?.also {
                        it.unregister()
                        it.destroy()
                    }
                    superLyricStub?.also {
                        SuperLyricHelper.unregisterReceiver(it)
                    }
                    YLog.debug("Terminated music lyric services")
                }
            }

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
            val progressUpdateClz =
                resolveMusicControlScreenElementProgressUpdateRunnableClassName(bridge)

            val clz = "com.miui.maml.elements.MusicControlScreenElement".toClass()
            val ref = clz.resolve()
            ref.constructor().build().hook {
                after {
                    elements.addIfAbsent(instance)
                    if (latestLyricLrc.isNotEmpty()) {
                        elements.forEach {
                            updateLyric(it, latestLyricLrc)
                        }
                    }
                }
            }

            ref.firstMethod {
                name = "resetLyric"
            }.hook {
                replaceUnit {
                    val iRef = instance!!.asResolver()
                    val mMetadata = iRef.firstField { name = "mMetadata" }.get<MediaMetadata>()
                    if (mMetadata != null && stateOf(instance!!).oldMediaId == mMetadata.description.mediaId) {
                        YLog.debug("Reject reset lyric while media id is not changed")
                        return@replaceUnit
                    } else {
                        clearManagedLyricState(instance!!)
                        callOriginal()
                    }
                }
            }

            ref.firstMethod {
                name = "updateLyricVar"
                parameters(Long::class.java)
            }.hook {
                replaceUnit {
                    if (!isManagedFullLyric(instance!!)) {
                        callOriginal(*args)
                        return@replaceUnit
                    }
                    updateLyricVarsDiff(instance!!, args(0).cast<Long>() ?: 0L)
                }
            }

            ref.firstMethod {
                name = "startProgressUpdate"
                parameters(Boolean::class.java, Long::class.java)
            }.hook {
                replaceUnit {
                    if (!isManagedFullLyric(instance!!)) {
                        callOriginal(*args)
                        return@replaceUnit
                    }
                    val isPlaying = args(0).boolean()
                    if (isPlaying) {
                        val state = stateOf(instance!!)
                        state.lastLineIndex = Int.MIN_VALUE
                        state.pendingSnapshot = null
                        ensurePreTickerRegistered(instance!!)
                        if (queueCurrentLyricSnapshot(instance!!)) {
                            instance!!.asResolver().firstMethod {
                                name = "requestUpdate"
                                superclass()
                            }.invoke()
                        }
                    }
                    scheduleManagedProgressTick(
                        element = instance!!,
                        isPlaying = isPlaying,
                        delayMs = args(1).cast<Long>() ?: 0L
                    )
                }
            }

            val seClz = "com.miui.maml.elements.ScreenElement".toClass().resolve()
            seClz.firstMethod {
                name = "show"
                parameters(Boolean::class.java)
            }.hook {
                after {
                    if (instanceClass == clz && !args(0).boolean()) {
                        YLog.debug("Release music control instance: $instance")
                        clearManagedLyricState(instance!!)
                        elements.remove(instance!!)
                        removeStateOf(instance!!)
                    }
                }
            }


            progressUpdateClz.toClass().resolve().firstMethod {
                name = "run"
            }.hook {
                replaceUnit {
                    val element = instance!!.readFieldValue("this$0") ?: run {
                        callOriginal()
                        return@replaceUnit
                    }
                    if (!isManagedFullLyric(element)) {
                        callOriginal()
                        return@replaceUnit
                    }
                    runManagedProgressTick(element)
                }
            }

            val musicControlListenerClz =
                "com.miui.maml.elements.MusicControlScreenElement$1".toClass().resolve()
            musicControlListenerClz.firstMethod {
                name = "onClientMetadataUpdate"
                returnType = Void.TYPE
                parameters(MediaMetadata::class.java)
            }.hook {
                replaceUnit {
                    val metadata = args(0).cast<MediaMetadata>()
                    val i = instance!!.asResolver().firstField {
                        name = "this$0"
                    }.get()
                    if (i == null) {
                        callOriginal(metadata)
                        return@replaceUnit
                    }
                    elements.addIfAbsent(i)
                    val moreDebug = hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)
                    val removeNativeLyric =
                        hookPrefs.getBoolean(ConfigKeys.HOOK_REMOVE_NATIVE_LYRIC_SUPPORT, false)
                    val skipTitleOnlyUpdate =
                        hookPrefs.getBoolean(
                            ConfigKeys.HOOK_SKIP_UNCHANGED_MEDIA_TITLE_UPDATE,
                            false
                        )
                    if (!removeNativeLyric && !skipTitleOnlyUpdate) {
                        callOriginal(metadata)
                        return@replaceUnit
                    }
                    val metadataForUpdate = if (
                        removeNativeLyric && metadata != null
                    ) {
                        val builder = MediaMetadata.Builder(metadata)
                        if (moreDebug) {
                            YLog.debug("Native lyric: ${metadata.getString(XIAOMI_LYRIC_METADATA)}")
                        }
                        builder.putString(XIAOMI_LYRIC_METADATA, null)
                        if (moreDebug) {
                            YLog.debug("Force removed native lyric data")
                        }
                        builder.build()
                    } else {
                        metadata
                    }
                    if (skipTitleOnlyUpdate) {
                        val previousMetadata = i.asResolver().firstField {
                            name = "mMetadata"
                        }.get<MediaMetadata>()
                        if (shouldSkipTitleOnlyMetadataUpdate(
                                previousMetadata,
                                metadataForUpdate
                            )
                        ) {
                            if (moreDebug) {
                                YLog.debug("Skip metadata update: mediaId unchanged and only title changed")
                            }
                            return@replaceUnit
                        }
                    }
                    callOriginal(metadataForUpdate)
                }
            }

            musicControlListenerClz.firstMethod {
                name = "onClientMetadataUpdate"
                returnType = Void.TYPE
                parameters(MediaMetadata::class.java)
            }.hook {
                after {
                    val metadata = args(0).cast<MediaMetadata>()
                    val i = instance!!.asResolver().firstField {
                        name = "this$0"
                    }.get() ?: return@after
                    scope.launch {
                        checkLyricState(metadata, i)
                    }
                }
            }
        }
    }

    private fun PackageScope.checkLyricState(metadata: MediaMetadata?, instance: Any) {
        val moreDebug = hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)
        val iRef = instance!!.asResolver()
        val mLyric = iRef.firstField { name = "mLyric" }.get()
        val state = stateOf(instance!!)
        val lrc = state.tempLrc
        if (mLyric == null) {
            if (lrc != null || latestLyricLrc.isNotEmpty()) {
                if (moreDebug) {
                    YLog.debug("onUpdateLrc $mLyric ${lrc == null} ${latestLyricLrc.isEmpty()}")
                }
                updateLyric(instance, lrc ?: latestLyricLrc)
                return
            }
            val currentId = metadata?.description?.mediaId
            if (currentId != null && currentId == lastLyricId) {
                val lastLrc = lastLyricLrc
                if (moreDebug) {
                    YLog.debug("onUseLastLrc $lastLrc")
                }
                updateLyric(instance, lastLrc)
            }
            val line = state.tempLyricLine
            val mLyricCurrentVar =
                iRef.firstField { name = "mLyricCurrentVar" }.get() ?: return
            val currentLyric =
                mLyricCurrentVar.asResolver().firstMethod { name = "get" }.invoke()
            if (line != null && currentLyric == null) {
                if (moreDebug) {
                    YLog.debug("onUpdateLine $line")
                }
                updateFallbackLine(instance, line)
            }
        }
    }

    private fun PackageScope.shouldSkipTitleOnlyMetadataUpdate(
        previousMetadata: MediaMetadata?,
        nextMetadata: MediaMetadata?
    ): Boolean {
        if (previousMetadata == null || nextMetadata == null) {
            return false
        }
        val previousToken = buildMetadataCompareToken(previousMetadata)
        val nextToken = buildMetadataCompareToken(nextMetadata)
        if (previousToken.mediaId.isNullOrEmpty() || previousToken.mediaId != nextToken.mediaId) {
            return false
        }
        if (previousToken.title == nextToken.title) {
            return false
        }
        return true
    }

    private fun PackageScope.buildMetadataCompareToken(metadata: MediaMetadata): MetadataCompareToken {
        return MetadataCompareToken(
            mediaId = metadata.description.mediaId,
            title = resolveTrackTitle(metadata)
        )
    }

    private fun PackageScope.resolveTrackTitle(metadata: MediaMetadata): String? {
        val customTitle = metadata.getString(METADATA_CUSTOM_TITLE).normalizedMetadataText()
        return if (customTitle.isNullOrEmpty()) {
            metadata.getString(METADATA_TITLE).normalizedMetadataText()
        } else {
            customTitle
        }
    }

    private fun String?.normalizedMetadataText(): String? {
        return this?.trim()
    }

    private fun PackageScope.isPackageInstalled(context: Context, pkg: String): Boolean {
        return runCatching {
            val pm = context.packageManager
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        }.isSuccess
    }

    private fun PackageScope.createLyricListener(): ActivePlayerListener {
        return object : ActivePlayerListener {
            override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
                currentProvider = providerInfo
                if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                    YLog.debug("onProviderChanged $currentProvider")
                }
            }

            override fun onSongChanged(song: Song?) {
                if (song == null) return
                scope.launch {
                    runCatching {
                        song.id
                        val lrc = lyricParser.toLrc(
                            song = song,
                            displayMode = hookPrefs.getInt(
                                ConfigKeys.LYRIC_DISPLAY_MODE,
                                ConfigKeys.LYRIC_DISPLAY_MODE_DEFAULT,
                            ),
                            showArtistBeforeFirstLine = hookPrefs.getBoolean(
                                ConfigKeys.LYRIC_SHOW_ARTIST_BEFORE_FIRST_LINE,
                                false,
                            ),
                        )
                        latestLyricLrc = normalizeForMiuiParser(lrc)
                        if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                            YLog.debug("REAREye getSongLRC $latestLyricLrc")
                            YLog.debug("onSongChanged converted LRC length=${latestLyricLrc.length}")
                            YLog.debug("current instance size ${elements.size}")
                        }
                        lastLyricId = song.id
                        lastLyricLrc = latestLyricLrc
                        if (elements.isNotEmpty()) {
                            elements.forEach {
                                updateLyric(it, latestLyricLrc)
                            }
                            latestLyricLrc = ""
                        }
                        delay(2.seconds)
                        elements.forEach {
                            updateLyric(it, latestLyricLrc, force = false, checkId = true)
                        }
                    }.onFailure {
                        YLog.error(it)
                    }
                }
            }

            override fun onPlaybackStateChanged(isPlaying: Boolean) = Unit

            override fun onPositionChanged(position: Long) = Unit

            override fun onSeekTo(position: Long) = Unit

            override fun onReceiveText(text: String?) {
                scope.launch {
                    runCatching {
                        if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                            YLog.debug("onSendText $text")
                        }
                        val mode = hookPrefs.getInt(
                            ConfigKeys.SUPER_LYRIC_DISPLAY_MODE,
                            ConfigKeys.SUPER_LYRIC_DISPLAY_MODE_DEFAULT
                        )
                        if (text != null) {
                            val originalLines = text.split("\n")
                            val lyric = when {
                                LyricParser.DisplayMode.shouldShowTranslation(mode) -> {
                                    if (originalLines.size > 1) {
                                        originalLines[1]
                                    } else {
                                        text
                                    }
                                }

                                else -> {
                                    originalLines[0]
                                }
                            }
                            if (lyric.isNotEmpty()) {
                                updateFallbackLyric(lyric)
                            }
                        }
                    }.onFailure {
                        YLog.error(it)
                    }
                }
            }

            override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

            override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
        }
    }

    private fun PackageScope.updateFallbackLyric(text: String) {
        elements.forEach { element ->
            stateOf(element).tempLyricLine = text
            updateFallbackLine(element, text)
        }
    }

    private fun PackageScope.updateFallbackLine(element: Any, text: String) {
        clearManagedLyricState(element)
        val ref = element.asResolver()
        val mLyric = ref.firstField { name = "mLyric" }.get()
        if (mLyric != null) return
        val mLyricCurrentVar =
            ref.firstField { name = "mLyricCurrentVar" }.get() ?: return
        mLyricCurrentVar.asResolver().firstMethod {
            name = "set"
            parameters(Any::class.java)
        }.invoke(text)
    }

    private fun PackageScope.updateLyric(
        element: Any,
        lrc: String,
        force: Boolean = true,
        checkId: Boolean = false
    ) {
        val moreDebug = hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)
        if (moreDebug) {
            YLog.debug("handle instance: $element")
        }
        val ref = element.asResolver()
        val mLyric = ref.firstField { name = "mLyric" }
        val metadata = ref.firstField { name = "mMetadata" }.get<MediaMetadata>()
        if (!force && mLyric.get() != null) {
            if (moreDebug) {
                YLog.debug("Skip to update, in non-force mode")
            }
            return
        }
        var vLrc = lrc
        if (checkId) {
            if (metadata == null) return
            if (metadata.description.mediaId != lastLyricId) {
                return
            }
            vLrc = lastLyricLrc
        }
        val parserClz = runCatching {
            "com.miui.maml.elements.MusicLyricParser".toClass().resolve()
        }.getOrNull() ?: "com.miui.maml.music.MusicLyricParser".toClass().resolve()
        val nLyric = parserClz.firstMethod {
            name = "parseLyric"
            parameters(String::class.java)
        }.invoke(vLrc)
        if (moreDebug) YLog.debug("parsed $nLyric")
        if (nLyric != null) {
            clearManagedLyricState(element)
            nLyric.asResolver().firstMethod { name = "decorate" }.invoke()
            mLyric.set(nLyric)
            ref.firstMethod { name = "updateLyric" }.invoke(nLyric)
            if (moreDebug) {
                YLog.debug("Force Update Lyric")
            }
            metadata?.also {
                stateOf(element).oldMediaId = it.description.mediaId
            }
            val state = stateOf(element)
            state.tempLrc = vLrc
            if (isTakeOverBuiltinLyricHandlingEnabled()) {
                state.managedFullLyric = true
                ensurePreTickerRegistered(element)
                if (queueCurrentLyricSnapshot(element)) {
                    element.asResolver().firstMethod {
                        name = "requestUpdate"
                        superclass()
                    }.invoke()
                }
            }
        } else {
            clearManagedLyricState(element)
        }
    }

    private fun PackageScope.runManagedProgressTick(element: Any) {
        if (!isTakeOverBuiltinLyricHandlingEnabled()) {
            clearManagedLyricState(element)
            return
        }
        val isPlaying = element.readField<Boolean>("mPlaying") == true
        if (!isPlaying) return
        val metadata = element.readField<MediaMetadata>("mMetadata")
        if (metadata == null) {
            scheduleManagedProgressTick(element, true, MIN_PROGRESS_INTERVAL_MS)
            return
        }
        val duration = metadata.getLong(DURATION_METADATA)
        val musicController = element.readFieldValue("mMusicController")
        if (musicController == null) {
            scheduleManagedProgressTick(element, true, MIN_PROGRESS_INTERVAL_MS)
            return
        }
        val position = musicController.invokeMethod("getPosition") as? Long
        if (position == null || duration <= 0 || position < 0) {
            scheduleManagedProgressTick(element, true, MIN_PROGRESS_INTERVAL_MS)
            return
        }

        setIndexedVariable(
            element.readFieldValue("mDurationVar"),
            duration.toDouble()
        )
        setIndexedVariable(
            element.readFieldValue("mPositionVar"),
            position.toDouble()
        )

        val lyricChanged = updateLyricVarsDiff(element, position)
        if (lyricChanged) {
            element.asResolver().firstMethod {
                name = "requestUpdate"
                superclass()
            }.invoke()
        }

        val interval = (element.readFieldOrNull<Int>("mUpdateProgressInterval")
            ?: element.readFieldOrNull<Int>("mProgressInterval") ?: 0).toLong()
            .coerceAtLeast(MIN_PROGRESS_INTERVAL_MS)
        val lyric = element.readFieldValue("mLyric")
        val cache = lyric?.let { getOrBuildLyricCache(element, it) }
        val delay = cache?.let { computeNextTickDelay(it.times, position, interval) } ?: interval
        scheduleManagedProgressTick(element, true, delay)
    }

    private fun PackageScope.scheduleManagedProgressTick(
        element: Any,
        isPlaying: Boolean,
        delayMs: Long
    ) {
        cancelManagedProgressJob(element)
        if (!isPlaying) return
        val safeDelay = delayMs.coerceAtLeast(0L)
        val job = mainScope.launch {
            if (safeDelay > 0L) {
                delay(safeDelay.milliseconds)
            }
            runManagedProgressTick(element)
        }
        stateOf(element).managedProgressJob = job
    }

    private fun PackageScope.updateLyricVarsDiff(element: Any, position: Long): Boolean {
        val lyric = element.readFieldValue("mLyric") ?: return false
        val cache = getOrBuildLyricCache(element, lyric) ?: return false
        val currentIndex = findLineIndex(cache.times, position)
        val state = stateOf(element)
        val previousIndex = state.pendingSnapshot?.lineIndex ?: state.lastLineIndex

        if (currentIndex == previousIndex) {
            return false
        }

        state.pendingSnapshot = buildLyricSnapshot(lyric, cache, currentIndex, position)
        return true
    }

    private fun PackageScope.buildLyricSnapshot(
        lyric: Any,
        cache: LyricCache,
        currentIndex: Int,
        position: Long
    ): PendingLyricSnapshot {
        val currentText = cache.lines.getOrNull(currentIndex)?.toString()
        val lastLine = lyric.invokeMethod("getLastLine", position)
        return PendingLyricSnapshot(
            lineIndex = currentIndex,
            currentText = currentText,
            beforeText = lyric.invokeMethod("getBeforeLines", position),
            afterText = lyric.invokeMethod("getAfterLines", position),
            lastText = lastLine,
            nextText = lyric.invokeMethod("getNextLine", position),
            lineProgress = computeLineProgress(cache.times, currentIndex, position)
        )
    }

    private fun PackageScope.applyPendingLyricSnapshot(element: Any) {
        val state = stateOf(element)
        val snapshot = state.pendingSnapshot ?: return
        setIndexedVariable(element.readFieldValue("mLyricCurrentVar"), snapshot.currentText)
        setIndexedVariable(element.readFieldValue("mLyricBeforeVar"), snapshot.beforeText)
        setIndexedVariable(element.readFieldValue("mLyricAfterVar"), snapshot.afterText)
        setIndexedVariable(element.readFieldValue("mLyricLastVar"), snapshot.lastText)
        setIndexedVariable(element.readFieldValue("mLyricPrevVar"), snapshot.lastText)
        setIndexedVariable(element.readFieldValue("mLyricNextVar"), snapshot.nextText)
        setIndexedVariable(
            element.readFieldOrNull("mLyricCurrentLineProgressVar")
                ?: element.readFieldOrNull("mLyricProgressVar"),
            snapshot.lineProgress
        )
        state.lastLineIndex = snapshot.lineIndex
        state.pendingSnapshot = null
    }

    private fun PackageScope.getOrBuildLyricCache(element: Any, lyric: Any): LyricCache? {
        val state = stateOf(element)
        val cachedLyric = state.cachedLyric
        if (cachedLyric === lyric) {
            val times = state.cachedTimes
            val lines = state.cachedLines
            if (times != null && lines != null) {
                return LyricCache(times, lines)
            }
        }

        val times = (lyric.invokeMethod("getTimeArr") as? IntArray) ?: return null

        @Suppress("UNCHECKED_CAST")
        val lines = (lyric.invokeMethod("getStringArr") as? List<CharSequence>) ?: return null
        state.cachedLyric = lyric
        state.cachedTimes = times
        state.cachedLines = lines
        state.lastLineIndex = Int.MIN_VALUE
        return LyricCache(times, lines)
    }

    private fun PackageScope.isManagedFullLyric(element: Any): Boolean {
        val state = stateOf(element)
        if (!isTakeOverBuiltinLyricHandlingEnabled()) {
            if (state.managedFullLyric) {
                clearManagedLyricState(element)
            }
            return false
        }
        return state.managedFullLyric
    }

    private fun PackageScope.isTakeOverBuiltinLyricHandlingEnabled(): Boolean {
        return hookPrefs.getBoolean(ConfigKeys.HOOK_TAKE_OVER_BUILTIN_LYRIC_HANDLING, true)
    }

    private fun PackageScope.clearManagedLyricState(element: Any) {
        cancelManagedProgressJob(element)
        stateOf(element).apply {
            managedFullLyric = false
            cachedLyric = null
            cachedTimes = null
            cachedLines = null
            lastLineIndex = Int.MIN_VALUE
            pendingSnapshot = null
        }
        unregisterPreTicker(element)
    }

    private fun PackageScope.cancelManagedProgressJob(element: Any) {
        val state = stateOf(element)
        state.managedProgressJob?.cancel()
        state.managedProgressJob = null
    }

    private fun PackageScope.setIndexedVariable(target: Any?, value: Any?) {
        if (target == null) return
        val ref = target.asResolver()
        when (value) {
            is Number -> ref.firstMethod {
                name = "set"
                parameters(Double::class.javaPrimitiveType!!)
            }.invoke(value.toDouble())

            else -> ref.firstMethod {
                name = "set"
                parameters(Any::class.java)
            }.invoke(value)
        }
    }

    private fun stateOf(element: Any): ManagedElementState {
        return synchronized(managedElementStates) {
            managedElementStates.getOrPut(element) { ManagedElementState() }
        }
    }

    private fun PackageScope.ensurePreTickerRegistered(element: Any) {
        val state = stateOf(element)
        if (state.preTicker != null) return
        val root = element.readSuperFieldValue("mRoot") ?: return
        val tickerClass = "com.miui.maml.elements.ITicker".toClass()
        val weakElement = WeakReference(element)
        var proxyInstance: Any? = null
        proxyInstance = Proxy.newProxyInstance(
            tickerClass.classLoader,
            arrayOf(tickerClass)
        ) { _, method, args ->
            when (method.name) {
                "tick" -> {
                    weakElement.get()?.let { applyPendingLyricSnapshot(it) }
                    null
                }

                "hashCode" -> System.identityHashCode(proxyInstance)
                "equals" -> proxyInstance === args?.firstOrNull()
                "toString" -> "REAREyePreTicker($proxyInstance)"
                else -> null
            }
        }
        root.asResolver().firstMethod {
            name = "addPreTicker"
            parameters(tickerClass)
        }.invoke(proxyInstance)
        state.preTicker = proxyInstance
    }

    private fun unregisterPreTicker(element: Any): Boolean {
        val state = stateOf(element)
        val ticker = state.preTicker ?: return true
        val root = element.readSuperFieldValue("mRoot") ?: run {
            YLog.error("Failed to remove Lyricon pre-ticker: root is unavailable")
            return false
        }
        val removed = runCatching {
            root.asResolver().firstMethod {
                name = "removePreTicker"
                parameters(
                    Class.forName(
                        "com.miui.maml.elements.ITicker",
                        false,
                        root.javaClass.classLoader
                    )
                )
            }.invoke(ticker)
            true
        }.onFailure {
            YLog.error("Failed to remove Lyricon pre-ticker during reload", it)
        }.getOrDefault(false)
        if (removed) state.preTicker = null
        return removed
    }

    private fun PackageScope.queueCurrentLyricSnapshot(element: Any): Boolean {
        val musicController = element.readFieldValue("mMusicController") ?: return false
        val position = (musicController.invokeMethod("getPosition") as? Long) ?: return false
        if (position < 0) return false
        return updateLyricVarsDiff(element, position)
    }

    private inline fun <reified T> Any.readField(name: String): T? {
        return asResolver().firstField { this.name = name }.get<T>()
    }

    private inline fun <reified T> Any.readFieldOrNull(name: String): T? {
        return asResolver().optional(silent = true).firstFieldOrNull { this.name = name }?.get<T>()
    }

    private fun Any.readSuperFieldValue(name: String): Any? {
        return asResolver().firstField {
            this.name = name
            superclass()
        }.get()
    }

    private fun PackageScope.removeStateOf(element: Any) {
        synchronized(managedElementStates) {
            managedElementStates.remove(element)
        }
    }

    private fun Any.readFieldValue(name: String): Any? {
        return asResolver().firstField { this.name = name }.get()
    }

    private fun Any.invokeMethod(name: String, vararg args: Any?): Any? {
        return asResolver().firstMethod { this.name = name }.invoke(*args)
    }

    private fun PackageScope.computeNextTickDelay(
        times: IntArray,
        position: Long,
        fallbackInterval: Long
    ): Long {
        if (times.isEmpty()) return fallbackInterval
        val currentIndex = findLineIndex(times, position)
        val nextIndex = if (currentIndex < 0) 0 else currentIndex + 1
        if (nextIndex !in times.indices) return fallbackInterval
        val nextLineDelay =
            (times[nextIndex].toLong() - position).coerceAtLeast(MIN_PROGRESS_INTERVAL_MS)
        return minOf(fallbackInterval, nextLineDelay)
    }

    private fun PackageScope.computeLineProgress(
        times: IntArray,
        currentIndex: Int,
        position: Long
    ): Double {
        if (times.isEmpty() || currentIndex < 0) return 0.0
        if (currentIndex >= times.lastIndex) {
            return ((position - times.last().toLong()) / LAST_LINE_DURATION_MS.toDouble())
                .coerceIn(0.0, 1.0)
        }
        val lineStart = times[currentIndex].toLong()
        val lineEnd = times[currentIndex + 1].toLong()
        if (lineEnd <= lineStart) return 0.0
        return ((position - lineStart).toDouble() / (lineEnd - lineStart).toDouble())
            .coerceIn(0.0, 1.0)
    }

    private fun PackageScope.findLineIndex(times: IntArray, position: Long): Int {
        if (times.isEmpty() || position < times.first().toLong()) return -1
        var left = 0
        var right = times.lastIndex
        while (left <= right) {
            val middle = (left + right) ushr 1
            if (times[middle].toLong() <= position) {
                left = middle + 1
            } else {
                right = middle - 1
            }
        }
        return right
    }

    private fun PackageScope.normalizeForMiuiParser(rawLrc: String): String {
        if (rawLrc.isEmpty()) return rawLrc
        return rawLrc
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "\r\n")
    }

    private data class LyricCache(
        val times: IntArray,
        val lines: List<CharSequence>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as LyricCache

            if (!times.contentEquals(other.times)) return false
            if (lines != other.lines) return false

            return true
        }

        override fun hashCode(): Int {
            var result = times.contentHashCode()
            result = 31 * result + lines.hashCode()
            return result
        }
    }

    private data class PendingLyricSnapshot(
        val lineIndex: Int,
        val currentText: String?,
        val beforeText: Any?,
        val afterText: Any?,
        val lastText: Any?,
        val nextText: Any?,
        val lineProgress: Double,
    )

    private data class MetadataCompareToken(
        val mediaId: String?,
        val title: String?
    )

    private data class ManagedElementState(
        var tempLrc: String? = null,
        var tempLyricLine: String? = null,
        var oldMediaId: String? = null,
        var managedFullLyric: Boolean = false,
        var cachedLyric: Any? = null,
        var cachedTimes: IntArray? = null,
        var cachedLines: List<CharSequence>? = null,
        var lastLineIndex: Int = Int.MIN_VALUE,
        var managedProgressJob: Job? = null,
        var pendingSnapshot: PendingLyricSnapshot? = null,
        var preTicker: Any? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ManagedElementState

            if (managedFullLyric != other.managedFullLyric) return false
            if (lastLineIndex != other.lastLineIndex) return false
            if (tempLrc != other.tempLrc) return false
            if (tempLyricLine != other.tempLyricLine) return false
            if (oldMediaId != other.oldMediaId) return false
            if (cachedLyric != other.cachedLyric) return false
            if (!cachedTimes.contentEquals(other.cachedTimes)) return false
            if (cachedLines != other.cachedLines) return false
            if (managedProgressJob != other.managedProgressJob) return false
            if (pendingSnapshot != other.pendingSnapshot) return false
            if (preTicker != other.preTicker) return false

            return true
        }

        override fun hashCode(): Int {
            var result = managedFullLyric.hashCode()
            result = 31 * result + lastLineIndex
            result = 31 * result + (tempLrc?.hashCode() ?: 0)
            result = 31 * result + (tempLyricLine?.hashCode() ?: 0)
            result = 31 * result + (oldMediaId?.hashCode() ?: 0)
            result = 31 * result + (cachedLyric?.hashCode() ?: 0)
            result = 31 * result + (cachedTimes?.contentHashCode() ?: 0)
            result = 31 * result + (cachedLines?.hashCode() ?: 0)
            result = 31 * result + (managedProgressJob?.hashCode() ?: 0)
            result = 31 * result + (pendingSnapshot?.hashCode() ?: 0)
            result = 31 * result + (preTicker?.hashCode() ?: 0)
            return result
        }
    }


    private fun PackageScope.resolveMusicControlScreenElementProgressUpdateRunnableClassName(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = LYRICON_PROGRESS_UPDATE_CACHE_KEY,
        ) {
            findClass {
                searchPackages("com.miui.maml.elements")
                matcher {
                    usingStrings(
                        "android.media.metadata.DURATION",
                    )
                }
            }.let {
                val r = {
                    it.forEach { d ->
                        YLog.debug("Found ${d.name}")
                    }
                    null
                }
                it.singleOrNull { data -> data.name.matches("com.miui.maml.elements.MusicControlScreenElement\\$\\d+".toRegex()) }
                    ?: r()
            }
        } ?: error("DexKit failed to resolve $LYRICON_PROGRESS_UPDATE_CACHE_KEY")
    }

    private companion object {
        private const val CANCELLATION_BARRIER_TIMEOUT_MS = 1500L
        private const val LYRICON_PROGRESS_UPDATE_CACHE_KEY =
            "LYRICON_PROGRESS_UPDATE_RUNNABLE_CLASS"
        private const val TARGET_LYRICON_PACKAGE = "io.github.proify.lyricon"
        private const val LYRICON_CORE_PACKAGE = "io.github.proify.lyricon.core"
        private const val METADATA_CUSTOM_TITLE = "android.media.metadata.CUSTOM_FIELD_TITLE"
        private const val METADATA_TITLE = "android.media.metadata.TITLE"
        private const val XIAOMI_LYRIC_METADATA = "android.media.metadata.LYRIC"
        private const val DURATION_METADATA = "android.media.metadata.DURATION"
        private const val MIN_PROGRESS_INTERVAL_MS = 100L
        private const val LAST_LINE_DURATION_MS = 8000L
    }
}
