package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.os.Parcel
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

/**
 * Removes Subscreen Center's hard-coded 15-app limit for insertApp.
 *
 * The target method is resolved from the insertApp full-list log. The list-returning method is
 * then resolved from the fact that the target method invokes it and it returns an ArrayList.
 * During transaction 11 only, its first size() call reports a value below 15; all later calls
 * use the real size so persistence and other list operations remain unchanged.
 */
@OptIn(DexKitExperimentalApi::class)
class UnlimitedSubscreenAppListHook : RoxyHooker() {
    companion object {
        private const val TAG = "REAREye-SubscreenAppList"
        private const val INSERT_TRANSACTION = 11
        private val EFFECTIVE_MAX_APP_COUNT = Int.MAX_VALUE
        private const val INSERT_APP_FULL_LOG = "insertApp: app list is full, size = "
        private const val SUBSCREEN_INTERFACE = "com.xiaomi.subscreencenter.service.ISubScreen"
        private const val INSERT_METHOD_CACHE_KEY = "SSC_UNLIMITED_APP_LIST_INSERT_METHOD"
        private const val LIST_METHOD_CACHE_KEY = "SSC_UNLIMITED_APP_LIST_PROVIDER_METHOD"
    }

    private val insertTransactionDepth = ThreadLocal.withInitial { 0 }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val bridge = runCatching {
                val versionCode = resolveHookPackageVersionCode(
                    hookSystemContext,
                    hookAppInfo.packageName,
                    hookAppInfo.sourceDir,
                )
                runtime.manage(
                    createDexKitCacheBridge(
                        packageName = hookAppInfo.packageName,
                        packageVersionCode = versionCode,
                        sourceDir = hookAppInfo.sourceDir,
                        dataDir = hookAppInfo.dataDir,
                    ),
                )
            }.onFailure {
                YLog.warn("[$TAG] DexKit initialization failed: $it")
            }.getOrNull() ?: return@loadApp

            val insertPoint = resolveInsertMethod(bridge)
            if (insertPoint == null) {
                YLog.warn("[$TAG] insertApp transaction method was not found")
                return@loadApp
            }

            val listPoint = resolveListMethod(bridge, insertPoint)
            if (listPoint == null) {
                YLog.warn("[$TAG] app list provider method was not found")
                return@loadApp
            }

            installInsertTransactionMarker(insertPoint)
            installListSizeProbe(listPoint)
            YLog.info(
                "[$TAG] installed insert=${insertPoint.className}->${insertPoint.methodName}, " +
                    "list=${listPoint.className}->${listPoint.methodName}",
            )
        }
    }

    private fun PackageScope.resolveInsertMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint? {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = INSERT_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    paramCount(4)
                    returnType = "boolean"
                    usingStrings(INSERT_APP_FULL_LOG, SUBSCREEN_INTERFACE)
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.resolveListMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
        insertPoint: DexKitMethodInjectionPoint,
    ): DexKitMethodInjectionPoint? {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = LIST_METHOD_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    paramCount(0)
                    returnType = "java.util.ArrayList"
                    callerMethods {
                        add {
                            declaredClass = insertPoint.className
                            name = insertPoint.methodName
                            paramCount(4)
                        }
                    }
                }
            }.singleOrNull()
        }
    }

    private fun PackageScope.installInsertTransactionMarker(
        point: DexKitMethodInjectionPoint,
    ) {
        point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 4
            parameters(
                Int::class.javaPrimitiveType!!,
                Parcel::class.java,
                Parcel::class.java,
                Int::class.javaPrimitiveType!!,
            )
            returnType = Boolean::class.javaPrimitiveType!!
        }.hook {
            before {
                if (args(0).int() != INSERT_TRANSACTION) return@before
                if (!hookPrefs.getBoolean(ConfigKeys.HOOK_UNLIMITED_SUBSCREEN_APP_LIST, true)) {
                    return@before
                }
                insertTransactionDepth.set((insertTransactionDepth.get() ?: 0) + 1)
            }
            after {
                if (args(0).int() != INSERT_TRANSACTION) return@after
                val depth = insertTransactionDepth.get() ?: 0
                if (depth <= 0) return@after
                if (depth == 1) {
                    insertTransactionDepth.remove()
                } else {
                    insertTransactionDepth.set(depth - 1)
                }
            }
        }
    }

    private fun PackageScope.installListSizeProbe(
        point: DexKitMethodInjectionPoint,
    ) {
        point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 0
            returnType = ArrayList::class.java
        }.hook {
            after {
            if ((insertTransactionDepth.get() ?: 0) <= 0) return@after

            @Suppress("UNCHECKED_CAST")
            val source = result as? ArrayList<Any?> ?: return@after
            result = UnlimitedSizeProbe(source)
            }
        }
    }

    private class UnlimitedSizeProbe<E>(source: Collection<E>) : ArrayList<E>(source) {
        private var firstSizeCall = true

        override val size: Int
            get() {
                val actual = super.size
                if (!firstSizeCall) return actual
                firstSizeCall = false

                // The original branch rejects values >= 15. Int.MAX_VALUE is the effective new
                // limit; a practical ArrayList cannot contain that many entries, so report 14 once.
                return if (actual < EFFECTIVE_MAX_APP_COUNT) 14 else 15
            }
    }
}
