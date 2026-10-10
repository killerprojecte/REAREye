package hk.uwu.reareye.hook.scopes.thememanager.modules

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.util.Size
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveDexKitFieldValue
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.lang.reflect.Modifier
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(DexKitExperimentalApi::class)
class UnlockVideoRestrictionsHook : RoxyHooker() {
    companion object {
        private const val VIDEO_EDIT_PLAY_CREATED_METHOD_CACHE_KEY =
            "TM_VIDEO_EDIT_PLAY_CREATED_METHOD"
        private const val VIDEO_EDIT_FPS_LIMIT_METHOD_CACHE_KEY = "TM_VIDEO_EDIT_FPS_LIMIT_METHOD"
        private const val VIDEO_EDITOR_CONFIG_BUILD_METHOD_CACHE_KEY =
            "TM_VIDEO_EDITOR_CONFIG_BUILD_METHOD"
        private const val VIDEO_DEPTH_CHECK_METHOD_CACHE_KEY = "TM_VIDEO_DEPTH_CHECK_METHOD"
        private const val VIDEO_TIMELINE_GET_INSTANCE_METHOD_CACHE_KEY =
            "TM_VIDEO_TIMELINE_GET_INSTANCE_METHOD"
        private const val VIDEO_TIMELINE_ATTACH_TEXTURE_METHOD_CACHE_KEY =
            "TM_VIDEO_TIMELINE_ATTACH_TEXTURE_METHOD"
        private const val VIDEO_TIMELINE_GET_DURATION_METHOD_CACHE_KEY =
            "TM_VIDEO_TIMELINE_GET_DURATION_METHOD"
        private const val VIDEO_TIMELINE_PREPARE_METHOD_CACHE_KEY =
            "TM_VIDEO_TIMELINE_PREPARE_METHOD"
        private const val VIDEO_TIMELINE_EXPORT_METHOD_CACHE_KEY =
            "TM_VIDEO_TIMELINE_EXPORT_METHOD"
        private const val VIDEO_TOAST_TEXT_METHOD_CACHE_KEY = "TM_VIDEO_TOAST_TEXT_METHOD"
        private const val VIDEO_OPERATION_CURRENT_TIME_METHOD_CACHE_KEY =
            "TM_VIDEO_OPERATION_CURRENT_TIME_METHOD"
        private const val VIDEO_CLIP_FRAME_LOAD_METHOD_CACHE_KEY =
            "TM_VIDEO_CLIP_FRAME_LOAD_METHOD"

        // The old cache entry was created from a method-only query and can keep a
        // stale miss after ThemeManager changes its dex layout.  Keep this query
        // separate so the semantic DexKit matcher below is evaluated once.
        private const val VIDEO_HASH_STRING_METHOD_CACHE_KEY =
            "TM_VIDEO_HASH_STRING_METHOD_V2"
        private const val VIDEO_EXPORT_CONFIG_SET_FPS_METHOD_CACHE_KEY =
            "TM_VIDEO_EXPORT_CONFIG_SET_FPS_METHOD"
        private const val VIDEO_GSON_SERIALIZE_METHOD_CACHE_KEY =
            "TM_VIDEO_GSON_SERIALIZE_METHOD"
        private const val VIDEO_FRAME_LOADER_CLASS_CACHE_KEY = "TM_VIDEO_FRAME_LOADER_CLASS"
        private const val VIDEO_EXPORT_CONFIG_CLASS_CACHE_KEY = "TM_VIDEO_EXPORT_CONFIG_CLASS"
        private const val VIDEO_EDIT_PLAY_VIEW_FIELD_CACHE_KEY = "TM_VIDEO_EDIT_PLAY_VIEW_FIELD"
        private const val VIDEO_EDIT_CONFIG_FIELD_CACHE_KEY = "TM_VIDEO_EDIT_CONFIG_FIELD"
        private const val VIDEO_EDIT_OPERATION_VIEW_FIELD_CACHE_KEY =
            "TM_VIDEO_EDIT_OPERATION_VIEW_FIELD"
        private const val VIDEO_EDIT_TRIM_IN_FIELD_CACHE_KEY = "TM_VIDEO_EDIT_TRIM_IN_FIELD"
        private const val VIDEO_EDIT_TRIM_OUT_FIELD_CACHE_KEY = "TM_VIDEO_EDIT_TRIM_OUT_FIELD"
        private const val VIDEO_EDIT_FRAME_LOADER_FIELD_CACHE_KEY =
            "TM_VIDEO_EDIT_FRAME_LOADER_FIELD"
        private const val VIDEO_EDIT_CLIP_FRAME_FIELD_CACHE_KEY = "TM_VIDEO_EDIT_CLIP_FRAME_FIELD"
        private const val VIDEO_EDIT_CLIP_LISTENER_FIELD_CACHE_KEY =
            "TM_VIDEO_EDIT_CLIP_LISTENER_FIELD"
        private const val VIDEO_EDIT_VIDEO_URI_FIELD_CACHE_KEY = "TM_VIDEO_EDIT_VIDEO_URI_FIELD"
        private const val VIDEO_EDIT_EXPORT_PATH_FIELD_CACHE_KEY = "TM_VIDEO_EDIT_EXPORT_PATH_FIELD"
    }

    @OptIn(ExperimentalAtomicApi::class)
    private val state = AtomicBoolean(false)

    @OptIn(ExperimentalAtomicApi::class)
    @SuppressLint("ResourceType")
    override fun PackageScope.onHook() {
        loadApp("com.android.thememanager") {
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
            val durationCropCacheKey = "DURATION_CROP_CLZ"
            val historyHelperCacheKey = "HISTORY_HELPER_CLZ"

            val videoEditPoint = resolveVideoEditPlayCreatedMethod(bridge)
            val fpsLimitPoint = resolveVideoEditFpsLimitMethod(bridge)
            YLog.info("fps $fpsLimitPoint")
            val editorConfigBuildPoint =
                resolveVideoEditorConfigBuildMethod(bridge)
            val checkDepthPoint = resolveVideoDepthCheckMethod(bridge)
            val timelineGetInstancePoint =
                resolveVideoTimelineGetInstanceMethod(bridge)
            val timelineAttachTexturePoint =
                resolveVideoTimelineAttachTextureMethod(bridge)
            val timelineGetDurationPoint =
                resolveVideoTimelineGetDurationMethod(timelineGetInstancePoint.className, bridge)
            val timelinePreparePoint =
                resolveVideoTimelinePrepareMethod(bridge)
            val timelineExportPoint =
                resolveVideoTimelineExportMethod(bridge)
            val toastTextPoint = resolveVideoToastTextMethod(bridge)
            val operationCurrentTimePoint =
                resolveVideoOperationCurrentTimeMethod(bridge)
            val clipFrameLoadPoint = resolveVideoClipFrameLoadMethod(bridge)
            val coderHashPoint = resolveVideoHashStringMethod(bridge)
            val exportConfigSetFpsPoint =
                resolveVideoExportConfigSetFpsMethod(bridge)
            val gsonSerializePoint = resolveVideoGsonSerializeMethod(bridge)
            val videoEditClz = videoEditPoint.className.toClass()
            val videoEditRef = videoEditClz.resolve()
            val fpsLimitClz = fpsLimitPoint.className.toClass().resolve()
            val editorCfgBuilderClz = editorConfigBuildPoint.className.toClass().resolve()
            val checkDepthClz = checkDepthPoint.className.toClass().resolve()
            val timelineClz = timelineGetInstancePoint.className.toClass()
            val timelineRef = timelineClz.resolve()
            val toastUtilsRef = toastTextPoint.className.toClass().resolve()
            val coderUtilsRef = coderHashPoint.className.toClass().resolve()
            val gsonUtilsClz = gsonSerializePoint.className.toClass().resolve()
            val frameLoaderClassName = resolveDexKitClassValue(
                bridge = bridge,
                cacheKey = VIDEO_FRAME_LOADER_CLASS_CACHE_KEY,
                selector = { it.className.substringBefore('$') },
            ) {
                findClass {
                    matcher {
                        usingStrings(
                            "MiVideoFrameLoader",
                            "loadFrameTime width=%d height=%d key=%s,timeMicros=%d,cost=%d",
                        )
                    }
                }.singleOrNull()
            } ?: error("DexKit failed to resolve video frame loader class")
            val exportConfigClassName = resolveDexKitClassValue(
                bridge = bridge,
                cacheKey = VIDEO_EXPORT_CONFIG_CLASS_CACHE_KEY,
            ) {
                findClass {
                    searchPackages("com.android.thememanager.videoedit.entity")
                    matcher {
                        fields {
                            addForType(Int::class.java)
                            addForType(Size::class.java)
                            addForType(Boolean::class.java)
                            addForType(String::class.java)
                        }
                    }
                }.singleOrNull()
            } ?: error("DexKit failed to resolve export config class")
            val frameLoaderClz = frameLoaderClassName.toClass().resolve()
            val exportConfigClz = exportConfigClassName.toClass().resolve()

            fun resolveFieldName(
                cacheKey: String,
                finder: DexKitBridge.() -> org.luckypray.dexkit.result.FieldData?,
            ): String {
                return resolveDexKitFieldValue(
                    bridge = bridge,
                    cacheKey = cacheKey,
                ) {
                    finder()
                } ?: error("Failed to find field for $cacheKey")
            }

            val playViewFieldName = resolveFieldName(
                VIDEO_EDIT_PLAY_VIEW_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "com.android.thememanager.videoedit.widget.VlogPlayView"
                    }
                }.singleOrNull()
            }
            val configFieldName = resolveFieldName(
                VIDEO_EDIT_CONFIG_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "com.android.thememanager.videoedit.VideoEditorConfig"
                    }
                }.singleOrNull()
            }
            val operationViewFieldName = resolveFieldName(
                VIDEO_EDIT_OPERATION_VIEW_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type =
                            "com.android.thememanager.videoedit.widget.SingleEditOperationView"
                    }
                }.singleOrNull()
            }
            val trimOutFieldName = resolveFieldName(
                VIDEO_EDIT_TRIM_OUT_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "long"
                        readMethods {
                            add {
                                declaredClass = videoEditPoint.className
                                paramCount(1)
                                returnType = "void"
                                usingStrings("onPlayTimelinePosition")
                            }
                        }
                        writeMethods {
                            add {
                                declaredClass = videoEditPoint.className
                                name = videoEditPoint.methodName
                                paramCount(0)
                                returnType = "void"
                                usingStrings("onPlayViewCreated")
                            }
                        }
                    }
                }.singleOrNull()
            }
            val trimInFieldName = resolveFieldName(
                VIDEO_EDIT_TRIM_IN_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "long"
                        readMethods {
                            add {
                                declaredClass = videoEditPoint.className
                                name = videoEditPoint.methodName
                                paramCount(0)
                                returnType = "void"
                                usingStrings("onPlayViewCreated")
                            }
                            add {
                                declaredClass = videoEditPoint.className
                                paramCount(1)
                                returnType = "void"
                                usingStrings("onPlayTimelinePosition")
                            }
                        }
                    }
                }.singleOrNull { it.name != trimOutFieldName }
            }
            val frameLoaderFieldName = resolveFieldName(
                VIDEO_EDIT_FRAME_LOADER_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type {
                            usingStrings("MiVideoFrameLoader")
                        }
                    }
                }.singleOrNull()
            }
            val clipFrameFieldName = resolveFieldName(
                VIDEO_EDIT_CLIP_FRAME_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "com.android.thememanager.videoedit.widget.ClipFrameView"
                    }
                }.singleOrNull()
            }
            val clipListenerFieldName = resolveFieldName(
                VIDEO_EDIT_CLIP_LISTENER_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type {
                            modifiers = Modifier.INTERFACE
                        }
                    }
                }.singleOrNull()
            }
            val videoUriFieldName = resolveFieldName(
                VIDEO_EDIT_VIDEO_URI_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "java.lang.String"
                        readMethods {
                            add {
                                declaredClass = videoEditPoint.className
                                name = videoEditPoint.methodName
                                paramCount(0)
                                returnType = "void"
                                usingStrings("onPlayViewCreated")
                            }
                        }
                    }
                }.let {
                    it.forEach {
                        YLog.info("Field ${it.className} ${it.name}")
                    }
                    it.singleOrNull()
                }
            }
            val exportPathFieldName = resolveFieldName(
                VIDEO_EDIT_EXPORT_PATH_FIELD_CACHE_KEY,
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "java.lang.String"
                        readMethods {
                            add {
                                declaredClass = videoEditPoint.className
                                paramCount(0)
                                returnType = "void"
                                usingStrings("onExportSuccess")
                            }
                        }
                    }
                }.let {
                    it.forEach {
                        YLog.info("Field ${it.className} ${it.name}")
                    }
                    it.singleOrNull()
                }
            }

            val durationCropMatchResult = resolveDexKitClassValue(
                bridge = bridge,
                cacheKey = durationCropCacheKey,
            ) {
                findClass {
                    searchPackages("com.android.thememanager.util")
                    matcher {
                        modifiers = Modifier.PUBLIC or Modifier.FINAL
                        fieldCount(1)
                        methods {
                            add {
                                name = "toString"
                                returnType(String::class.java)
                                usingStrings("DurationCrop")
                            }
                        }
                    }
                }.singleOrNull()
            }
            val durationCropClz = requireNotNull(durationCropMatchResult) {
                "DexKit failed to resolve the video duration crop class"
            }.toClass()

            val historyHelperResult = resolveDexKitClassValue(
                bridge = bridge,
                cacheKey = historyHelperCacheKey,
            ) {
                findClass {
                    searchPackages("com.android.thememanager.settings")
                    matcher {
                        modifiers = Modifier.PUBLIC
                        fields {
                            addForType(String::class.java)
                            addForType(Any::class.java)
                            count = 2
                        }
                        usingStrings("updateVideoResource")
                    }
                }.singleOrNull()
            }
            val historyHelperClz = requireNotNull(historyHelperResult) {
                "DexKit failed to resolve the video history helper class"
            }.toClass()

            checkDepthClz.firstMethod {
                name = checkDepthPoint.methodName
            }.hook {
                after {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS,
                            true
                        )
                    ) return@after
                    val ref = instance!!.asResolver()
                    val videoCfg = ref.firstField {
                        name = $$"$videoConfig"
                    }.get() ?: return@after
                    if (videoCfg.asResolver().field {
                            type = Boolean::class.java
                        }.all { it.get() == true } && !state.load()) {
                        result = durationCropClz.resolve().firstField {
                            type = durationCropClz
                        }.get()
                    } else {
                        state.store(false)
                    }
                }
            }

            // 修补视频编辑器
            videoEditRef.firstMethod {
                name = videoEditPoint.methodName
                returnType = Void.TYPE
            }.hook {
                replaceUnit {
                    if (!hookPrefs.getBoolean(ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS, true)) {
                        callOriginal()
                        return@replaceUnit
                    }
                    val iRef = instance!!.asResolver()
                    val playViewRef = iRef.firstField {
                        name = playViewFieldName
                    }.get()!!.asResolver()
                    val videoConfig = iRef.firstField {
                        name = configFieldName
                    }.get()
                    val currentTrimIn = iRef.firstField {
                        name = trimInFieldName
                    }.get() as? Long ?: 0L
                    val operationViewRef = iRef.firstField {
                        name = operationViewFieldName
                    }.get()!!.asResolver()
                    val clipFrameRef = iRef.firstField {
                        name = clipFrameFieldName
                    }.get()!!.asResolver()
                    val videoUri = iRef.firstField {
                        name = videoUriFieldName
                    }.get()
                    val sInstance = timelineRef.firstMethod {
                        name = timelineGetInstancePoint.methodName
                        returnType = timelineClz
                    }.invoke()!!
                    sInstance!!.asResolver().firstMethod {
                        name = timelineAttachTexturePoint.methodName
                        returnType = Void.TYPE
                    }.invoke(playViewRef.firstMethod {
                        name = "getTextureView"
                    }.invoke(), videoConfig)
                    val duration: Long =
                        sInstance!!.asResolver().firstMethod {
                            name = timelineGetDurationPoint.methodName
                        }.invoke() as Long
                    val activity = instance<Activity>()
                    if (duration <= 0) {
                        toastUtilsRef.firstMethod { name = toastTextPoint.methodName }
                            .invoke(activity.resources.getString(2131888794))
                        Log.e("VideoEditActivity", "onPlayViewCreated: originDuration = 0")
                        activity.finish()
                        return@replaceUnit
                    }
                    iRef.firstField { name = trimOutFieldName }.set(duration)
                    operationViewRef.firstMethod { name = operationCurrentTimePoint.methodName }
                        .invoke(currentTrimIn)
                    operationViewRef.firstMethod { name = "setTotalTime" }.invoke(duration)
                    val yVar = frameLoaderClz.firstConstructor {
                        parameterCount = 0
                    }.create()
                    iRef.firstField { name = frameLoaderFieldName }.set(yVar)
                    clipFrameRef.firstMethod { name = "setVideoFrameLoader" }.invoke(yVar)
                    clipFrameRef.firstMethod { name = "setClipFrameListener" }
                        .invoke(iRef.firstField { name = clipListenerFieldName }.get())
                    clipFrameRef.firstMethod { name = clipFrameLoadPoint.methodName }.invoke(
                        videoUri,
                        duration,
                        duration
                    )
                    sInstance!!.asResolver().firstMethod {
                        name = timelinePreparePoint.methodName
                        parameters(Int::class.java)
                    }.invoke(currentTrimIn.toInt())
                    state.store(true)
                }
            }

            // 修补帧率限制
            fpsLimitClz.firstMethod {
                name = fpsLimitPoint.methodName
            }.hook {
                replaceUnit {
                    if (!hookPrefs.getBoolean(ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS, true)) {
                        callOriginal()
                        return@replaceUnit
                    }
                    val strF7l8 =
                        historyHelperClz.resolve().firstMethod {
                            returnType = String::class.java
                            parameterCount = 0
                        }.invoke() as String
                    val iVEA = instance!!.asResolver().firstField { type = videoEditClz }.get()!!
                    val iRef = iVEA.asResolver()
                    val yObj = iRef.firstField { name = videoUriFieldName }.get()
                    val cFieldRef = iRef.firstField { name = exportPathFieldName }
                    cFieldRef.set(
                        strF7l8 + (coderUtilsRef.firstMethod {
                            name = coderHashPoint.methodName
                        }.invoke(yObj) as String) + ".mp4"
                    )
                    val frameRetriever =
                        "com.xiaomi.milab.videosdk.FrameRetriever".toClass().resolve()
                            .firstConstructor().create().asResolver()
                    frameRetriever.firstMethod { name = "setDataSource" }.invoke(yObj)
                    val width = frameRetriever.firstMethod { name = "getWidth" }.invoke() as Int
                    val height =
                        frameRetriever.firstMethod { name = "getHeight" }.invoke() as Int
                    val fps = frameRetriever.firstMethod { name = "getFPS" }.invoke() as Float
                    val bitrate =
                        frameRetriever.firstMethod { name = "getBitrate" }.invoke() as Long
                    frameRetriever.firstMethod { name = "release" }.invoke()
                    if (width <= 0 || height <= 0) {
                        iRef.firstMethod { name = "onExportFail" }.invoke()
                        return@replaceUnit
                    }
                    val (outWidth, outHeight) = computeExportOutputSize(width, height, 1080)
                    val toqVar = exportConfigClz.firstConstructor {
                        parameterCount = 5
                    }.create(
                        true,
                        cFieldRef.get(),
                        Size(outWidth, outHeight),
                        (((((bitrate / (width * height)) * outWidth) * outHeight) / fps) * fps).toInt(),
                        0
                    )
                    toqVar.asResolver().firstMethod {
                        name = exportConfigSetFpsPoint.methodName
                    }.invoke(fps.toInt())
                    Log.d(
                        "VideoEditActivity",
                        String.format(
                            "ExportConfig %s",
                            gsonUtilsClz.firstMethod { name = gsonSerializePoint.methodName }
                                .invoke(toqVar)
                        )
                    )
                    Log.d("lollipop", "export videopath is " + cFieldRef.get())
                    val qRef = timelineRef.firstMethod {
                        name = timelineGetInstancePoint.methodName
                    }.invoke()!!.asResolver()
                    qRef.firstMethod {
                        name = timelineExportPoint.methodName
                    }.invoke(
                        iRef.firstField { name = trimInFieldName }.get(),
                        iRef.firstField { name = trimOutFieldName }.get(),
                        toqVar
                    )
                }
            }

            editorCfgBuilderClz.firstMethod {
                name = editorConfigBuildPoint.methodName
            }.hook {
                before {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS,
                            true
                        )
                    ) return@before
                    val ref = instance!!.asResolver()
                    val isCallFromRearScreen = ref.field {
                        type = Boolean::class.java
                    }.all { it.get() == true }
                    if (isCallFromRearScreen) {
                        YLog.debug("Overwriting video editor max duration & frame-rate limitations")
                        ref.firstField {
                            type = Long::class.java
                        }.set(Long.MAX_VALUE)
                        ref.firstField {
                            type = Int::class.java
                        }.set(120)
                    }
                }
            }
        }
    }

    private fun PackageScope.resolveVideoEditPlayCreatedMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_EDIT_PLAY_CREATED_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/VideoEditActivity.java:132
            // VideoEditActivity.ebn()/onPlayViewCreated path logs "onPlayViewCreated".
            findMethod {
                searchPackages("com.android.thememanager.videoedit")
                matcher {
                    returnType = "void"
                    usingStrings("onPlayViewCreated")
                }
            }.singleOrNull()
        } ?: error("Failed to find video edit play created method")
    }

    private fun PackageScope.resolveVideoEditFpsLimitMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_EDIT_FPS_LIMIT_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/VideoEditActivity.java:110
            // VideoEditActivity.zy.run() builds ExportConfig and caps fps at 30.
            findMethod {
                searchPackages("com.android.thememanager.videoedit")
                matcher {
                    name = "run"
                    paramCount(0)
                    returnType = "void"
                    usingStrings("ExportConfig %s", "export videopath is ")
                }
            }.singleOrNull()
        } ?: error("Failed to find edit fps limit method")
    }

    private fun PackageScope.resolveVideoEditorConfigBuildMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_EDITOR_CONFIG_BUILD_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/VideoEditorConfig.java:42
            // VideoEditorConfig.Builder.k() creates the config that carries duration/fps limits.
            findMethod {
                searchPackages("com.android.thememanager.videoedit")
                matcher {
                    paramCount(0)
                    returnType = "com.android.thememanager.videoedit.VideoEditorConfig"
                }
            }.singleOrNull()
        } ?: error("Failed to find video editor config build method")
    }

    private fun PackageScope.resolveVideoDepthCheckMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_DEPTH_CHECK_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/personalizedEditor/interceptor/VideoCheckForDepthInterceptor$checkVideo$2.java:41
            // invokeSuspend() returns duration/ratio/fps validation results for rear video wallpapers.
            findMethod {
                searchPackages("com.personalizedEditor.interceptor")
                matcher {
                    name = "invokeSuspend"
                    paramCount(1)
                    usingStrings(
                        "VideoWallpaperInterceptor",
                        "checkVideo: gallery return data is null",
                        "checkVideo: is horizontal video",
                    )
                }
            }.singleOrNull()
        } ?: error("Failed to find depth check method")
    }

    private fun PackageScope.computeExportOutputSize(
        originWidth: Int,
        originHeight: Int,
        maxWidth: Int
    ): Pair<Int, Int> {
        val rawWidth = if (originWidth > maxWidth) maxWidth else originWidth
        val rawHeight = if (originWidth > maxWidth) {
            kotlin.math.ceil(originHeight / (originWidth.toDouble() / maxWidth)).toInt()
        } else {
            originHeight
        }
        return ((rawWidth / 4) * 4) to ((rawHeight / 4) * 4)
    }

    private inline fun resolveCachedMethodPoint(
        bridge: DexKitCacheBridge.RecyclableBridge,
        cacheKey: String,
        crossinline finder: DexKitBridge.() -> org.luckypray.dexkit.result.MethodData?,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = cacheKey,
        ) {
            finder()
        } ?: error("Failed to find method for $cacheKey")
    }

    private fun PackageScope.resolveVideoTimelineGetInstanceMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_GET_INSTANCE_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/widget/s.java:39
            // Original method in jadx: videoedit.widget.s.q()
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.SYNCHRONIZED
                    paramCount(0)
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveVideoTimelineAttachTextureMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_ATTACH_TEXTURE_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/widget/s.java:72
            // Original method in jadx: videoedit.widget.s.k(XmsTextureView, VideoEditorConfig)
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    paramTypes(
                        "com.xiaomi.milab.videosdk.XmsTextureView",
                        "com.android.thememanager.videoedit.VideoEditorConfig",
                    )
                    returnType = "void"
                    usingStrings("attachTexture", "mVideoTrack is  null", "mVideoClip is  null")
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveVideoTimelineGetDurationMethod(
        clz: String,
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_GET_DURATION_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/widget/s.java:167
            // Original method in jadx: videoedit.widget.s.zy()
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass = clz
                    paramCount(0)
                    returnType = "long"
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveVideoTimelinePrepareMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_PREPARE_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/widget/s.java:148
            // Original method in jadx: videoedit.widget.s.s(int)
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    paramTypes("int")
                    returnType = "void"
                    usingStrings("prepareTimeline")
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveVideoTimelineExportMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_EXPORT_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/widget/s.java:153
            // Original method in jadx: videoedit.widget.s.toq(long, long, entity.toq)
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    paramTypes(
                        "long",
                        "long",
                        null
                    )
                    returnType = "void"
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveVideoToastTextMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TOAST_TEXT_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/basemodule/utils/nmn5.java:33
            // Original method in jadx: basemodule.utils.nmn5.q(String)
            findMethod {
                searchPackages("com.android.thememanager.basemodule.utils")
                matcher {
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    paramTypes(String::class.java)
                    returnType = "void"
                    invokeMethods {
                        add {
                            declaredClass = "android.widget.Toast"
                        }
                        add {
                            declaredClass = "android.text.TextUtils"
                        }
                    }
                }
            }.let {
                it.forEach {
                    YLog.info("method ${it.className} ${it.name}")
                }
                it.singleOrNull()
            }
        }
    }

    private fun PackageScope.resolveVideoOperationCurrentTimeMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_OPERATION_CURRENT_TIME_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/widget/SingleEditOperationView.java:55
            // Original method in jadx: SingleEditOperationView.d2ok(long)
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass =
                        "com.android.thememanager.videoedit.widget.SingleEditOperationView"
                    paramTypes("long")
                    returnType = "void"
                }
            }.singleOrNull { it.name != "setTotalTime" }
        }
    }

    private fun PackageScope.resolveVideoClipFrameLoadMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_CLIP_FRAME_LOAD_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/widget/ClipFrameView.java:138
            // Original method in jadx: ClipFrameView.x2(String, long, long)
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass = "com.android.thememanager.videoedit.widget.ClipFrameView"
                    paramTypes("java.lang.String", "long", "long")
                    returnType = "void"
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveVideoHashStringMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_HASH_STRING_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/basemodule/utils/CoderUtls.java:73
            // Original method in jadx: CoderUtls.zy(String).  Both q(String) and
            // zy(String) have the same signature.  The dex inlines the MD5
            // algorithm constant, so match its actual string and digest call.
            findMethod {
                searchPackages("com.android.thememanager.basemodule.utils")
                matcher {
                    declaredClass = "com.android.thememanager.basemodule.utils.CoderUtls"
                    paramTypes(String::class.java)
                    returnType = "java.lang.String"
                    usingStrings("MD5")
                    invokeMethods {
                        add {
                            declaredClass = "java.security.MessageDigest"
                            name = "getInstance"
                            paramTypes(String::class.java)
                            returnType = "java.security.MessageDigest"
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveVideoExportConfigSetFpsMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_EXPORT_CONFIG_SET_FPS_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/videoedit/entity/toq.java:76
            // Original method in jadx: entity.toq.kja0(int)
            findMethod {
                searchPackages("com.android.thememanager.videoedit.entity")
                matcher {
                    paramTypes("int")
                    returnType = "void"
                    usingFields {
                        add {
                            annotations {
                                add {
                                    type = "com.google.gson.annotations.SerializedName"
                                    addElement {
                                        name = "value"
                                        stringValue("fps")
                                    }
                                }
                            }
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveVideoGsonSerializeMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_GSON_SERIALIZE_METHOD_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/android/thememanager/library/util/app/GsonUtils.java:142
            // Original method in jadx: GsonUtils.g(Object)
            findMethod {
                searchPackages("com.android.thememanager.library.util.app")
                matcher {
                    declaredClass = "com.android.thememanager.library.util.app.GsonUtils"
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    paramTypes(Any::class.java)
                    returnType = "java.lang.String"
                }
            }.singleOrNull()
        }
    }
}
