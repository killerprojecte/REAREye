package hk.uwu.reareye.hook.scopes.thememanager.modules

import android.os.Process
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveDexKitMethodValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.io.File

@OptIn(DexKitExperimentalApi::class)
class RearWallpaperThemeManagerSyncHook : RoxyHooker() {

    companion object {
        private const val TAG = "REAREye-RearWallpaper-TM"
        private const val IMPORT_RES_PREFIX = "reareye_import_"
        private const val REAR_LIST_MANAGER_CLASS_CACHE_KEY = "REAR_LIST_MANAGER_CLASS"
        private const val REAR_LIST_FILTER_METHOD_CACHE_KEY = "REAR_LIST_FILTER_METHOD"
        private const val REAR_LIST_ITEM_BEAN_CLASS_CACHE_KEY = "REAR_LIST_ITEM_BEAN_CLASS"
    }

    private data class RuntimeRecord(
        val item: JSONObject,
        val resId: String,
        val applyId: String,
        val position: Int,
    )

    override fun PackageScope.onHook() {
        loadApp("com.android.thememanager") {
            runCatching {
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

                val managerClassName = resolveRearListManagerClass(bridge)
                val filterMethodName = resolveRearListFilterMethod(bridge)
                val itemBeanClassName = resolveRearListItemBeanClass(bridge)

                // RearListDataManager.f7l8(List<RearScreenListItemBean>)
                managerClassName.toClass().resolve().firstMethod {
                    name = filterMethodName
                    parameterCount = 1
                    returnType = List::class.java
                }.hook {
                    after {
                    val original = result as? List<*> ?: return@after
                    result = mergeImportedWallpapers(original, itemBeanClassName)
                    }
                }

                YLog.debug(
                    "[$TAG] hook manager=$managerClassName method=$filterMethodName bean=$itemBeanClassName"
                )
            }.onFailure(YLog::error)
        }
    }

    private fun PackageScope.resolveRearListManagerClass(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = REAR_LIST_MANAGER_CLASS_CACHE_KEY,
        ) {
            // DexKit class anchor in the current decompiled source:
            // .tmp-ref/thememanager-jadx/sources/com/rearScreen/manager/RearListDataManager.java:47
            // public static final String p = "rear:RearListDataManager";
            findClass {
                searchPackages("com.rearScreen.manager")
                matcher {
                    usingStrings("rear:RearListDataManager")
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve rear list manager class")
    }

    private fun PackageScope.resolveRearListFilterMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String {
        return resolveDexKitMethodValue(
            bridge = bridge,
            cacheKey = REAR_LIST_FILTER_METHOD_CACHE_KEY,
        ) {
            // RearListDataManager.f7l8(List<RearScreenListItemBean>)
            findMethod {
                searchPackages("com.rearScreen.manager")
                matcher {
                    paramCount(1)
                    returnType = "java.util.List"
                    usingStrings("getRearListFromDB invalidMtzList")
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve rear list filter method")
    }

    private fun PackageScope.resolveRearListItemBeanClass(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = REAR_LIST_ITEM_BEAN_CLASS_CACHE_KEY,
        ) {
            // RearScreenListItemBean.convertToResource()
            findClass {
                searchPackages("com.rearScreen.bean")
                matcher {
                    methods {
                        add {
                            name = "convertToResource"
                            usingStrings("rearscreen")
                        }
                    }
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve rear list item bean class")
    }

    private fun PackageScope.mergeImportedWallpapers(
        original: List<*>,
        itemBeanClassName: String,
    ): List<Any?> {
        val imported = readImportedRuntimeRecords()
            .sortedByDescending { it.position }
            .mapNotNull { record ->
                runCatching { toRearScreenListItemBean(record, itemBeanClassName) }
                    .onFailure(YLog::warn)
                    .getOrNull()
            }
        if (imported.isEmpty()) return original

        val importedKeys = imported.mapNotNullTo(HashSet(), ::readBeanKey)
        val merged = ArrayList<Any?>(imported.size + original.size)
        merged.addAll(imported)
        original.forEach { bean ->
            val key = bean?.let(::readBeanKey)
            if (key == null || key !in importedKeys) merged.add(bean)
        }
        return merged
    }

    private fun PackageScope.readImportedRuntimeRecords(): List<RuntimeRecord> {
        val file = resolveRuntimeFile()
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.isBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrElse {
            YLog.warn(it)
            return emptyList()
        }

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val resId = item.optNonBlankString("resId") ?: continue
                val applyId = item.optNonBlankString("applyId") ?: continue
                if (!item.isReareyeImported(resId)) continue
                add(
                    RuntimeRecord(
                        item = item,
                        resId = resId,
                        applyId = applyId,
                        position = item.optInt("position", -1),
                    )
                )
            }
        }
    }

    private fun PackageScope.toRearScreenListItemBean(
        record: RuntimeRecord,
        itemBeanClassName: String
    ): Any {
        val (item, resId, applyId, position) = record
        val json = item
        val resType = json.optNonBlankString("resType") ?: "REAREye"
        val packagePath = json.optNonBlankString("resLocalPath")
        val metadataPath = json.optNonBlankString("metaPath")
        val previewPath = json.optNonBlankString("resPreviewPath")
            ?: json.optNonBlankString("snapshotPreviewPath")
            ?: ""
        val now = System.currentTimeMillis()

        // RearScreenListItemBean(String resType, String resId, ..., boolean supportAon)
        return itemBeanClassName.toClass().resolve().firstConstructor {
            parameterCount = 31
        }.create(
            resType,
            resId,
            json.optNonBlankString("resSubType") ?: "reareye_import",
            json.optNonBlankString("resTypeName") ?: localeJson(resType),
            applyId,
            json.optNonBlankString("resName") ?: localeJson(resId),
            json.optNullableString("resDescription"),
            previewPath,
            json.optNullableString("resDesigner"),
            packagePath,
            json.optNonBlankString("resSnapshotPath") ?: packagePath,
            json.optNullableString("rightPath"),
            metadataPath,
            json.optNonBlankString("metaSnapshotPath") ?: metadataPath,
            json.optBoolean("isDownload", false),
            json.optNullableString("downloadUrl") ?: "",
            json.optLong("applyTime", now),
            json.optLong("updateTime", now),
            json.optBoolean("isNFC", false),
            json.optNullableString("mamlEditConfigPath"),
            json.optNonBlankString("snapshotPreviewPath") ?: previewPath,
            null,
            null,
            null,
            position,
            null,
            json.optBoolean("editable", false),
            json.optBoolean("isThirdParties", true),
            null,
            json.optNullableString("etcPath"),
            json.optBoolean("supportAon", false),
        )
    }

    private fun readBeanKey(bean: Any): String? {
        val resId = runCatching {
            bean.asResolver().firstMethod {
                name = "getResId"
                parameterCount = 0
                returnType = String::class.java
            }.invoke<String>()
        }.getOrNull() ?: return null
        val applyId = runCatching {
            bean.asResolver().firstMethod {
                name = "getApplyId"
                parameterCount = 0
                returnType = String::class.java
            }.invoke<String>()
        }.getOrNull() ?: return null
        return "$resId::$applyId"
    }

    private fun PackageScope.resolveRuntimeFile(): File {
        return File(
            "/data/system/theme_magic/users/${currentUserId()}/rearScreen",
            "runtime.json",
        )
    }

    private fun PackageScope.currentUserId(): Int {
        return (Process.myUid() / 100000).coerceAtLeast(0)
    }

    private fun JSONObject.isReareyeImported(resId: String): Boolean {
        if (resId.startsWith(IMPORT_RES_PREFIX)) return true
        return listOfNotNull(
            optNullableString("resLocalPath"),
            optNullableString("metaPath"),
            optNullableString("metaSnapshotPath"),
        ).any { path ->
            path.replace('\\', '/').contains("/$IMPORT_RES_PREFIX")
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)?.toString()?.trim().orEmpty()
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optNonBlankString(key: String): String? {
        return optNullableString(key)
    }

    private fun localeJson(value: String): String {
        return JSONObject()
            .put("fallback", value)
            .put("zh_CN", value)
            .toString()
    }
}
