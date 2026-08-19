package hk.uwu.reareye.hook.scopes.thememanager.modules

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.util.Size
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
import hk.uwu.reareye.ui.config.ConfigKeys
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.lang.reflect.Modifier
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(DexKitExperimentalApi::class)
class UnlockVideoRestrictionsHook : YukiBaseHooker() {
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
        private const val VIDEO_OPERATION_CURRENT_TIME_METHOD_CACHE_KEY =
            "TM_VIDEO_OPERATION_CURRENT_TIME_METHOD"
        private const val VIDEO_CLIP_FRAME_LOAD_METHOD_CACHE_KEY =
            "TM_VIDEO_CLIP_FRAME_LOAD_METHOD"
        private const val VIDEO_HASH_STRING_METHOD_CACHE_KEY = "TM_VIDEO_HASH_STRING_METHOD"
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
        private const val FALLBACK_VIDEO_EDIT_ACTIVITY_CLASS =
            "com.android.thememanager.videoedit.VideoEditActivity"
        private const val FALLBACK_VIDEO_EDIT_FPS_RUNNABLE_CLASS =
            "com.android.thememanager.videoedit.VideoEditActivity\$zy"
        private const val FALLBACK_VIDEO_EDITOR_CONFIG_BUILDER_CLASS =
            "com.android.thememanager.videoedit.VideoEditorConfig\$k"
        private const val FALLBACK_VIDEO_DEPTH_CHECK_CLASS =
            "com.personalizedEditor.interceptor.VideoCheckForDepthInterceptor\$checkVideo\$2"
        private const val FALLBACK_VIDEO_TIMELINE_CLASS =
            "com.android.thememanager.videoedit.widget.s"
        private const val FALLBACK_VIDEO_OPERATION_VIEW_CLASS =
            "com.android.thememanager.videoedit.widget.SingleEditOperationView"
        private const val FALLBACK_VIDEO_CLIP_FRAME_VIEW_CLASS =
            "com.android.thememanager.videoedit.widget.ClipFrameView"
        private const val FALLBACK_VIDEO_CODER_UTILS_CLASS =
            "com.android.thememanager.basemodule.utils.CoderUtls"
        private const val FALLBACK_VIDEO_GSON_UTILS_CLASS =
            "com.android.thememanager.library.util.app.GsonUtils"
    }

    @OptIn(ExperimentalAtomicApi::class)
    private val state = AtomicBoolean(false)

    @OptIn(ExperimentalAtomicApi::class)
    @SuppressLint("ResourceType")
    override fun onHook() {
        loadApp("com.android.thememanager") {
            val versionCode = resolveHookPackageVersionCode(
                systemContext,
                appInfo.packageName,
                appInfo.sourceDir,
            )
            val bridge = createDexKitCacheBridge(
                packageName = appInfo.packageName,
                packageVersionCode = versionCode,
                sourceDir = appInfo.sourceDir,
                dataDir = appInfo.dataDir,
            )
            val durationCropCacheKey = "DURATION_CROP_CLZ"
            val historyHelperCacheKey = "HISTORY_HELPER_CLZ"

            val videoEditPoint = resolveVideoEditPlayCreatedMethod(bridge)
            val fpsLimitPoint = resolveVideoEditFpsLimitMethod(bridge)
            val editorConfigBuildPoint = resolveVideoEditorConfigBuildMethod(bridge)
            val checkDepthPoint = resolveVideoDepthCheckMethod(bridge)

            // DYNAMIC RESOLUTION: Get the AttachTexture point first to extract the true class name
            val timelineAttachTexturePoint = resolveVideoTimelineAttachTextureMethod(bridge)
            val dynamicTimelineClass = timelineAttachTexturePoint.className

            // Now use the dynamically found class name for the rest of the methods!
            val timelineGetInstancePoint = resolveVideoTimelineGetInstanceMethod(bridge, dynamicTimelineClass)
            val timelineGetDurationPoint = resolveVideoTimelineGetDurationMethod(bridge, dynamicTimelineClass)
            val timelinePreparePoint = resolveVideoTimelinePrepareMethod(bridge, dynamicTimelineClass)
            val timelineExportPoint = resolveVideoTimelineExportMethod(bridge, dynamicTimelineClass)

            val operationCurrentTimePoint = resolveVideoOperationCurrentTimeMethod(bridge)
            val clipFrameLoadPoint = resolveVideoClipFrameLoadMethod(bridge)
            val coderHashPoint = resolveVideoHashStringMethod(bridge)
            val gsonSerializePoint = resolveVideoGsonSerializeMethod(bridge)

            val videoEditClz = videoEditPoint.className.toClass()
            val videoEditRef = videoEditClz.resolve()
            val fpsLimitClz = fpsLimitPoint.className.toClass().resolve()
            val editorCfgBuilderClz = editorConfigBuildPoint.className.toClass().resolve()
            val checkDepthClz = checkDepthPoint.className.toClass().resolve()
            val timelineClz = dynamicTimelineClass.toClass()
            val timelineRef = timelineClz.resolve()
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

            val exportConfigSetFpsPoint = resolveVideoExportConfigSetFpsMethod(bridge, exportConfigClassName)
            val frameLoaderClz = frameLoaderClassName.toClass().resolve()
            val exportConfigClz = exportConfigClassName.toClass().resolve()

            fun resolveFieldName(
                cacheKey: String,
                fallbackField: String,
                finder: DexKitBridge.() -> org.luckypray.dexkit.result.FieldData?,
            ): String {
                return resolveDexKitFieldValue(
                    bridge = bridge,
                    cacheKey = cacheKey,
                ) {
                    finder()
                } ?: fallbackField
            }

            val playViewFieldName = resolveFieldName(
                VIDEO_EDIT_PLAY_VIEW_FIELD_CACHE_KEY,
                "q",
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
                "s",
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
                "n",
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
            val trimInFieldName = resolveFieldName(
                VIDEO_EDIT_TRIM_IN_FIELD_CACHE_KEY,
                "i",
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
                                declaredClass = fpsLimitPoint.className
                                name = fpsLimitPoint.methodName
                                paramCount(0)
                                returnType = "void"
                                usingStrings("ExportConfig %s", "export videopath is ")
                            }
                        }
                    }
                }.singleOrNull()
            }
            val trimOutFieldName = resolveFieldName(
                VIDEO_EDIT_TRIM_OUT_FIELD_CACHE_KEY,
                "z",
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "long"
                        readMethods {
                            add {
                                declaredClass = fpsLimitPoint.className
                                name = fpsLimitPoint.methodName
                                paramCount(0)
                                returnType = "void"
                                usingStrings("ExportConfig %s", "export videopath is ")
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
            val frameLoaderFieldName = resolveFieldName(
                VIDEO_EDIT_FRAME_LOADER_FIELD_CACHE_KEY,
                "p",
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "com.android.thememanager.videoedit.y"
                    }
                }.singleOrNull()
            }
            val clipFrameFieldName = resolveFieldName(
                VIDEO_EDIT_CLIP_FRAME_FIELD_CACHE_KEY,
                "g",
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
                "j",
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "com.android.thememanager.videoedit.widget.ClipFrameView\$zy"
                    }
                }.singleOrNull()
            }
            val videoUriFieldName = resolveFieldName(
                VIDEO_EDIT_VIDEO_URI_FIELD_CACHE_KEY,
                "y",
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
                            add {
                                declaredClass = fpsLimitPoint.className
                                name = fpsLimitPoint.methodName
                                paramCount(0)
                                returnType = "void"
                                usingStrings("ExportConfig %s", "export videopath is ")
                            }
                        }
                    }
                }.singleOrNull()
            }
            val exportPathFieldName = resolveFieldName(
                VIDEO_EDIT_EXPORT_PATH_FIELD_CACHE_KEY,
                "c",
            ) {
                findField {
                    searchPackages("com.android.thememanager.videoedit")
                    matcher {
                        declaredClass = videoEditPoint.className
                        type = "java.lang.String"
                        writeMethods {
                            add {
                                declaredClass = fpsLimitPoint.className
                                name = fpsLimitPoint.methodName
                                paramCount(0)
                                returnType = "void"
                                usingStrings("ExportConfig %s", "export videopath is ")
                            }
                        }
                    }
                }.singleOrNull()
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
            val durationCropClz = (durationCropMatchResult
                ?: "com.android.thememanager.util.uc\$k\$toq").toClass()

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
            val historyHelperClz =
                (historyHelperResult ?: "com.android.thememanager.settings.a9").toClass()

            checkDepthClz.firstMethod {
                name = checkDepthPoint.methodName
            }.hook().after {
                if (!prefs.getBoolean(
                        ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS,
                        true
                    )
                ) return@after
                val ref = instance.asResolver()
                val videoCfg = ref.firstField {
                    name = "videoConfig"
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

            // 修补视频编辑器
            videoEditRef.firstMethod {
                name = videoEditPoint.methodName
                returnType = Void.TYPE
            }.hook().replaceUnit {
                if (!prefs.getBoolean(ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS, true)) {
                    invokeOriginal()
                    return@replaceUnit
                }
                val iRef = instance.asResolver()
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
                sInstance.asResolver().firstMethod {
                    name = timelineAttachTexturePoint.methodName
                    returnType = Void.TYPE
                }.invoke(playViewRef.firstMethod {
                    name = "getTextureView"
                }.invoke(), videoConfig)
                val duration: Long =
                    sInstance.asResolver().firstMethod {
                        name = timelineGetDurationPoint.methodName
                    }.invoke() as Long
                val activity = instance<Activity>()
                if (duration <= 0) {
                    android.widget.Toast.makeText(activity, activity.resources.getString(2131888794), android.widget.Toast.LENGTH_SHORT).show()
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
                sInstance.asResolver().firstMethod {
                    name = timelinePreparePoint.methodName
                    parameters(Int::class.java)
                }.invoke(currentTrimIn.toInt())
                state.store(true)
            }

            // 修补帧率限制
            fpsLimitClz.firstMethod {
                name = fpsLimitPoint.methodName
            }.hook().replaceUnit {
                if (!prefs.getBoolean(ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS, true)) {
                    invokeOriginal()
                    return@replaceUnit
                }
                val strF7l8 =
                    historyHelperClz.resolve().firstMethod {
                        returnType = String::class.java
                        parameterCount = 0
                    }.invoke() as String
                val iVEA = instance.asResolver().firstField { type = videoEditClz }.get()!!
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

            editorCfgBuilderClz.firstMethod {
                name = editorConfigBuildPoint.methodName
            }.hook().before {
                if (!prefs.getBoolean(
                        ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS,
                        true
                    )
                ) return@before
                val ref = instance.asResolver()
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

    private fun resolveVideoEditPlayCreatedMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_EDIT_PLAY_CREATED_METHOD_CACHE_KEY,
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit")
                matcher {
                    returnType = "void"
                    usingStrings("onPlayViewCreated")
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(FALLBACK_VIDEO_EDIT_ACTIVITY_CLASS, "nsb")
    }

    private fun resolveVideoEditFpsLimitMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_EDIT_FPS_LIMIT_METHOD_CACHE_KEY,
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit")
                matcher {
                    name = "run"
                    paramCount(0)
                    returnType = "void"
                    usingStrings("ExportConfig %s", "export videopath is ")
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(FALLBACK_VIDEO_EDIT_FPS_RUNNABLE_CLASS, "run")
    }

    private fun resolveVideoEditorConfigBuildMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_EDITOR_CONFIG_BUILD_METHOD_CACHE_KEY,
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit")
                matcher {
                    paramCount(0)
                    returnType = "com.android.thememanager.videoedit.VideoEditorConfig"
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(FALLBACK_VIDEO_EDITOR_CONFIG_BUILDER_CLASS, "k")
    }

    private fun resolveVideoDepthCheckMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_DEPTH_CHECK_METHOD_CACHE_KEY,
        ) {
            findMethod {
                searchPackages("com.personalizedEditor.interceptor")
                matcher {
                    name = "invokeSuspend"
                    paramCount(1)
                    usingStrings(
                        "checkVideo: gallery return data is null",
                        "checkVideo: is horizontal video",
                    )
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(FALLBACK_VIDEO_DEPTH_CHECK_CLASS, "invokeSuspend")
    }

    private fun computeExportOutputSize(
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
        fallbackClass: String,
        fallbackMethod: String,
        crossinline finder: DexKitBridge.() -> org.luckypray.dexkit.result.MethodData?,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = cacheKey,
        ) {
            finder()
        } ?: DexKitMethodInjectionPoint(fallbackClass, fallbackMethod)
    }

    private fun resolveVideoTimelineGetInstanceMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
        targetClass: String
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_GET_INSTANCE_METHOD_CACHE_KEY,
            fallbackClass = targetClass,
            fallbackMethod = "q",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass = targetClass
                    modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.SYNCHRONIZED
                    paramCount(0)
                    returnType = targetClass
                }
            }.singleOrNull()
        }
    }

    private fun resolveVideoTimelineAttachTextureMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_ATTACH_TEXTURE_METHOD_CACHE_KEY,
            fallbackClass = FALLBACK_VIDEO_TIMELINE_CLASS,
            fallbackMethod = "k",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    // TRUE DYNAMIC FIX: Removed the strictly hardcoded 'declaredClass'.
                    // DexKit will now correctly trace the class using the strings below!
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

    private fun resolveVideoTimelineGetDurationMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
        targetClass: String
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_GET_DURATION_METHOD_CACHE_KEY,
            fallbackClass = targetClass,
            fallbackMethod = "zy",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass = targetClass
                    paramCount(0)
                    returnType = "long"
                }
            }.singleOrNull()
        }
    }

    private fun resolveVideoTimelinePrepareMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
        targetClass: String
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_PREPARE_METHOD_CACHE_KEY,
            fallbackClass = targetClass,
            fallbackMethod = "s",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass = targetClass
                    paramTypes("int")
                    returnType = "void"
                    usingStrings("prepareTimeline")
                }
            }.singleOrNull()
        }
    }

    private fun resolveVideoTimelineExportMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
        targetClass: String
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_TIMELINE_EXPORT_METHOD_CACHE_KEY,
            fallbackClass = targetClass,
            fallbackMethod = "toq",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass = targetClass
                    paramTypes(
                        "long",
                        "long",
                        "com.android.thememanager.videoedit.entity.toq",
                    )
                    returnType = "void"
                }
            }.singleOrNull()
        }
    }

    private fun resolveVideoOperationCurrentTimeMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_OPERATION_CURRENT_TIME_METHOD_CACHE_KEY,
            fallbackClass = FALLBACK_VIDEO_OPERATION_VIEW_CLASS,
            fallbackMethod = "d2ok",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass = FALLBACK_VIDEO_OPERATION_VIEW_CLASS
                    paramTypes("long")
                    returnType = "void"
                }
            }.singleOrNull()
        }
    }

    private fun resolveVideoClipFrameLoadMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_CLIP_FRAME_LOAD_METHOD_CACHE_KEY,
            fallbackClass = FALLBACK_VIDEO_CLIP_FRAME_VIEW_CLASS,
            fallbackMethod = "x2",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit.widget")
                matcher {
                    declaredClass = FALLBACK_VIDEO_CLIP_FRAME_VIEW_CLASS
                    paramTypes("java.lang.String", "long", "long")
                    returnType = "void"
                }
            }.singleOrNull()
        }
    }

    private fun resolveVideoHashStringMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_HASH_STRING_METHOD_CACHE_KEY,
            fallbackClass = FALLBACK_VIDEO_CODER_UTILS_CLASS,
            fallbackMethod = "zy",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.basemodule.utils")
                matcher {
                    declaredClass = FALLBACK_VIDEO_CODER_UTILS_CLASS
                    modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
                    paramTypes(String::class.java)
                    returnType = "java.lang.String"
                }
            }.singleOrNull()
        }
    }

    private fun resolveVideoExportConfigSetFpsMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
        targetClass: String
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_EXPORT_CONFIG_SET_FPS_METHOD_CACHE_KEY,
            fallbackClass = targetClass,
            fallbackMethod = "kja0",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.videoedit.entity")
                matcher {
                    declaredClass = targetClass
                    paramTypes("int")
                    returnType = "void"
                }
            }.singleOrNull()
        }
    }

    private fun resolveVideoGsonSerializeMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveCachedMethodPoint(
            bridge = bridge,
            cacheKey = VIDEO_GSON_SERIALIZE_METHOD_CACHE_KEY,
            fallbackClass = FALLBACK_VIDEO_GSON_UTILS_CLASS,
            fallbackMethod = "g",
        ) {
            findMethod {
                searchPackages("com.android.thememanager.library.util.app")
                matcher {
                    declaredClass = FALLBACK_VIDEO_GSON_UTILS_CLASS
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    paramTypes(Any::class.java)
                    returnType = "java.lang.String"
                }
            }.singleOrNull()
        }
    }
}