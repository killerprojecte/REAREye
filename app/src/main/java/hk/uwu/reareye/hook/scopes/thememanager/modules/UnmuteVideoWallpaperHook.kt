package hk.uwu.reareye.hook.scopes.thememanager.modules

import android.util.Pair
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@OptIn(DexKitExperimentalApi::class)
class UnmuteVideoWallpaperHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadApp("com.android.thememanager") {
            val versionCode =
                resolveHookPackageVersionCode(
                    hookSystemContext,
                    hookAppInfo.packageName,
                    hookAppInfo.sourceDir
                )
            val bridge = runtime.manage(
                createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                packageVersionCode = versionCode,
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                )
            )
            val durationCropMatchResult = resolveDemuxerClassName(bridge)
            val ref = requireNotNull(durationCropMatchResult) {
                "DexKit failed to resolve the video audio demuxer class"
            }.toClass().resolve()

            ref.firstMethod {
                parameters(File::class.java, File::class.java, File::class.java)
            }.hook {
                replaceAny {
                val input = args(0).cast<File>()!!
                val output = args(1).cast<File>()!!
                YLog.debug("Input path: ${input.absolutePath} length: ${input.length() / 1024.0}")
                YLog.debug("Output path: $output")
                if (input.absolutePath.contains("rear")) {
                    YLog.debug("Patch rear screen video wallpaper")
                    Files.copy(
                        input.toPath(),
                        output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    return@replaceAny Pair(output, null)
                }
                    return@replaceAny callOriginal(*args)
                }
            }
        }
    }

    private fun PackageScope.resolveDemuxerClassName(
        bridge: DexKitCacheBridge.RecyclableBridge
    ): String? {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = "VIDEO_AUDIO_DEMUXER_CLZ",
        ) {
            findClass {
                searchPackages("com.android.thememanager.util")
                matcher {
                    modifiers = Modifier.PUBLIC
                    fields {
                        addForType(String::class.java)
                        addForType(Int::class.java)
                        count = 4
                    }
                    methods {
                        add {
                            paramTypes(
                                File::class.java,
                                File::class.java,
                                File::class.java,
                            )
                            paramCount(3)
                        }
                    }
                }
            }
                .singleOrNull()
        }
    }
}
