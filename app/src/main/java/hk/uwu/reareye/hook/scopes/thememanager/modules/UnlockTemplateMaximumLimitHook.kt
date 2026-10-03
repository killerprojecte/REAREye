package hk.uwu.reareye.hook.scopes.thememanager.modules

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

@OptIn(DexKitExperimentalApi::class)
class UnlockTemplateMaximumLimitHook : RoxyHooker() {
    companion object {
        private const val REAR_DETAIL_VIEW_MODEL_CLASS_CACHE_KEY = "TM_REAR_DETAIL_VIEW_MODEL_CLASS"
    }

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
            val rsDetailClz = resolveRearDetailViewModelClass(bridge).toClass().resolve()
            rsDetailClz.firstConstructor().hook {
                after {
                    val ref = instance!!.asResolver()
                    ref.field {
                        type = Int::class.java
                        modifiers(Modifiers.PRIVATE, Modifiers.FINAL)
                    }.forEach {
                        it.set(Int.MAX_VALUE)
                    }
                }
            }
        }
    }

    private fun PackageScope.resolveRearDetailViewModelClass(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = REAR_DETAIL_VIEW_MODEL_CLASS_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/thememanager-jadx/sources/com/rearScreen/viewModel/RearScreenDetailViewModel.java:44
            // Constructor stores "rear:RearScreenDetailViewModel" and the NFC/template limits.
            findClass {
                searchPackages("com.rearScreen.viewModel")
                matcher {
                    usingStrings(
                        "RearScreenDetailViewModel",
                        "[换机日志] onResourceImportSuccessful: onlineId="
                    )
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve rear detail view model class")
    }
}
