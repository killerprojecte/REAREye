package hk.uwu.reareye.hook.scopes.system.modules

import android.annotation.SuppressLint
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import java.io.File
import java.util.zip.ZipFile

/**
 * HyperOS Runtime 应用（天气/相册）的 native hook 配置写入器。
 *
 * 注入本身由 LSPosed Native Hook 完成（assets/native_init + native_init，进程无 ART，
 * 不经过 Java）；本 hook 只在 system_server 启动目标进程前把 UI 开关写入
 * reareye_<module>.env，供 native 端读取。不再改写启动二进制、不再提取模块 wrapper so。
 */
class NativeEnvWriterHook : RoxyHooker() {
    val reloadable: Boolean
        get() = false

    override fun PackageScope.onHook() {
        loadSystem {
            runCatching {
                YLog.debug("Native env writer hook installing")
                val rustProcessClass = "android.os.RustProcessImpl".toClass().resolve()
                rustProcessClass.firstMethod {
                    name = "startRustProcess"
                    parameters(
                        String::class.java, // processName
                        String::class.java, // packageName
                        Int::class.javaPrimitiveType!!, // uid
                        IntArray::class.java, // gids
                        Int::class.javaPrimitiveType!!, // runtimeFlags
                        Int::class.javaPrimitiveType!!, // mountExternal
                        Int::class.javaPrimitiveType!!, // targetSdkVersion
                        String::class.java, // seInfo
                        String::class.java, // abi
                        String::class.java, // instructionSet
                        String::class.java, // appDataDir
                        String::class.java, // invokeWith
                        Int::class.javaPrimitiveType!!, // zygotePolicyFlags
                        Boolean::class.javaPrimitiveType!!, // isTopApp
                        LongArray::class.java, // disabledCompatChanges
                        Map::class.java, // pkgDataInfoMap
                        Map::class.java, // allowlistedDataInfoList
                        Boolean::class.javaPrimitiveType!!, // bindMountAppsData
                        Boolean::class.javaPrimitiveType!!, // bindMountAppStorageDirs
                        Boolean::class.javaPrimitiveType!!, // bindMountOverrideSysprops
                        String::class.java, // binary
                        String::class.java, // searchPath
                        String::class.java, // libraryPermittedPath
                        String::class.java, // envs
                        Long::class.javaPrimitiveType!! // seq
                    )
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.KEEP
                    before {
                        YLog.debug("Native env writer startRustProcess called args=${args.size}")
                        if (args.size <= BINARY_INDEX) {
                            YLog.debug("Native env writer skip reason=args_size size=${args.size}")
                            return@before
                        }
                        val packageName = args[PACKAGE_INDEX] as? String ?: run {
                            YLog.debug("Native env writer skip reason=package_null")
                            return@before
                        }
                        val originalBinary = args[BINARY_INDEX] as? String ?: run {
                            YLog.debug("Native env writer skip package=$packageName reason=binary_null")
                            return@before
                        }
                        val abi = args[ABI_INDEX] as? String ?: ABI_ARM64
                        val originalEnv = args[ENVS_INDEX] as? String
                        YLog.debug(
                            "Native env writer candidate package=$packageName abi=$abi binary=$originalBinary " +
                                    "env=${originalEnv.orEmpty()}"
                        )
                        val spec = WrapperRegistry.find(packageName, originalBinary) ?: run {
                            YLog.debug("Native env writer skip package=$packageName reason=no_spec")
                            return@before
                        }
                        val envDir = resolveEnvTargetDir(originalBinary, abi, spec) ?: run {
                            YLog.debug(
                                "Native env writer skip package=$packageName reason=env_dir_unresolved"
                            )
                            return@before
                        }
                        val envValues =
                            spec.envProvider(hookPrefs, originalBinary, originalBinary)
                        writeModuleEnv(envDir, spec.moduleId, envValues)

                        YLog.debug(
                            "Native env writer wrote package=$packageName dir=${envDir.absolutePath} " +
                                    "moduleEnv=true envUnchanged=${originalEnv.orEmpty()}"
                        )
                    }
                }
                YLog.debug("Native env writer hook installed")
            }.onFailure {
                YLog.warn(it)
            }
        }
    }

    /**
     * 解析 env 文件落盘目录。
     *
     * - zip 型 binary（xxx.apk!/lib/<abi>/libxxx.so）：提取一份原始 so 到真实文件路径，
     *   保证目标进程 /proc/self/maps 中出现可解析的真实库路径（native 端据此锚定 env 目录）。
     * - 普通文件型 binary：直接使用其父目录（现行为）。
     */
    private fun PackageScope.resolveEnvTargetDir(
        originalBinary: String,
        abi: String,
        spec: EnvSpec,
    ): File? {
        val originalArchiveOrFile = originalBinary.substringBefore('!').let(::File)
        val isZipBinary = originalBinary.contains("!/")
        return if (isZipBinary) {
            val targetDir = File(
                File(originalArchiveOrFile.parentFile ?: return null, "lib"),
                abi.toInstalledLibDirName()
            )
            YLog.debug(
                "Native env writer targetDir=${targetDir.absolutePath} zipBinary=true"
            )
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                YLog.debug("Native env writer mkdir failed dir=${targetDir.absolutePath}")
                return null
            }
            val extractedOriginal = File(targetDir, spec.originalLibName)
            if (!extractEntry(
                    originalArchiveOrFile,
                    "lib/$abi/${spec.originalLibName}",
                    extractedOriginal,
                )
            ) {
                return null
            }
            targetDir
        } else {
            val targetDir = originalArchiveOrFile.parentFile ?: return null
            YLog.debug(
                "Native env writer targetDir=${targetDir.absolutePath} zipBinary=false"
            )
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                YLog.debug("Native env writer mkdir failed dir=${targetDir.absolutePath}")
                return null
            }
            targetDir
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun PackageScope.extractEntry(
        apk: File,
        entryName: String,
        outFile: File,
    ): Boolean {
        return runCatching {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(entryName) ?: run {
                    YLog.debug("Native env writer entry not found apk=${apk.absolutePath} entry=$entryName")
                    return false
                }
                val shouldWrite = !outFile.exists() || outFile.length() != entry.size ||
                        outFile.lastModified() < apk.lastModified()
                YLog.debug(
                    "Native env writer extract entry=$entryName out=${outFile.absolutePath} " +
                            "shouldWrite=$shouldWrite size=${entry.size} exists=${outFile.exists()}"
                )
                if (shouldWrite) {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    outFile.setReadable(true, false)
                    outFile.setWritable(true, true)
                }
                true
            }
        }.onFailure {
            YLog.warn(it)
        }.getOrDefault(false)
    }

    @SuppressLint("SetWorldReadable")
    private fun PackageScope.writeModuleEnv(
        moduleDir: File?,
        moduleId: String,
        values: Map<String, String>
    ) {
        if (moduleDir == null) return
        runCatching {
            if (!moduleDir.exists()) moduleDir.mkdirs()
            val configFile = File(moduleDir, MODULE_ENV_FILE.format(moduleId))
            val content =
                values.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }
            if (!configFile.exists() || configFile.readText() != content) {
                configFile.writeText(content)
                configFile.setReadable(true, false)
                configFile.setWritable(true, true)
            }
            YLog.debug("Native env writer module env path=${configFile.absolutePath} content=$content")
        }.onFailure {
            YLog.warn(it)
        }
    }

    private fun String.toInstalledLibDirName(): String {
        return when (this) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            else -> this
        }
    }

    private data class EnvSpec(
        val moduleId: String,
        val packageName: String,
        val originalLibName: String,
        val envProvider: (HookPrefs, String, String) -> Map<String, String>,
    ) {
        fun matches(packageName: String, originalBinary: String): Boolean {
            return this.packageName == packageName && originalBinary.substringBefore('!')
                .endsWith(originalLibName)
        }
    }

    private object WrapperRegistry {
        private val specs = listOf(
            EnvSpec(
                moduleId = MODULE_ID_WEATHER,
                packageName = "com.miui.weather2",
                originalLibName = "libweather_app.so",
            ) { hookPrefs, _, _ ->
                mapOf(
                    ENV_DEVICE_LEVEL to hookPrefs.getInt(ConfigKeys.WEATHER_DEVICE_LEVEL, 0)
                        .toString(),
                    ENV_UNLOCK_SUPER_BLUR to if (hookPrefs.getBoolean(
                            ConfigKeys.WEATHER_UNLOCK_SUPER_BLUR,
                            false
                        )
                    ) "1" else "0"
                )
            },
            EnvSpec(
                moduleId = MODULE_ID_GALLERY,
                packageName = "com.miui.gallery",
                originalLibName = "libapp_gallery.so",
            ) { hookPrefs, _, _ ->
                mapOf(
                    ENV_GALLERY_BACKUP_SERVER to hookPrefs.getInt(
                        ConfigKeys.GALLERY_BACKUP_SERVER,
                        0
                    )
                        .toString(),
                    ENV_GALLERY_ENABLE_HDR_ENHANCED to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_HDR_ENHANCED
                    ),
                    ENV_GALLERY_ENABLE_PDF to booleanFlag(hookPrefs, ConfigKeys.GALLERY_ENABLE_PDF),
                    ENV_GALLERY_ENABLE_OCR to booleanFlag(hookPrefs, ConfigKeys.GALLERY_ENABLE_OCR),
                    ENV_GALLERY_ENABLE_OCR_FORM to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_OCR_FORM
                    ),
                    ENV_GALLERY_LONGER_TRASHBIN_TIME to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_LONGER_TRASHBIN_TIME
                    ),
                    ENV_GALLERY_TRASH_RETENTION_DAYS to hookPrefs.getInt(
                        ConfigKeys.GALLERY_TRASH_RETENTION_DAYS,
                        365
                    ).toString(),
                    ENV_GALLERY_ENABLE_ID_PHOTO to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_ID_PHOTO
                    ),
                    ENV_GALLERY_ENABLE_PHOTO_MOVIE to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_PHOTO_MOVIE
                    ),
                    ENV_GALLERY_ENABLE_VIDEO_POST to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_VIDEO_POST
                    ),
                    ENV_GALLERY_ENABLE_VIDEO_EDITOR to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_VIDEO_EDITOR
                    ),
                    ENV_GALLERY_ENABLE_MAGIC_MATTING to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_MAGIC_MATTING
                    ),
                    ENV_GALLERY_ENABLE_PRINT to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_PRINT
                    ),
                    ENV_GALLERY_ENABLE_PRIVACY_WATERMARK to booleanFlag(
                        hookPrefs,
                        ConfigKeys.GALLERY_ENABLE_PRIVACY_WATERMARK
                    ),
                )
            }
        )

        fun find(packageName: String, originalBinary: String): EnvSpec? {
            return specs.firstOrNull { it.matches(packageName, originalBinary) }
        }
    }

    companion object {
        private const val ABI_ARM64 = "arm64-v8a"

        private const val PACKAGE_INDEX = 1
        private const val ABI_INDEX = 8
        private const val BINARY_INDEX = 20
        private const val ENVS_INDEX = 23

        private const val MODULE_ENV_FILE = "reareye_%s.env"
        private const val MODULE_ID_WEATHER = "weather"
        private const val MODULE_ID_GALLERY = "gallery"

        private const val ENV_DEVICE_LEVEL = "REAREYE_WEATHER_DEVICE_LEVEL"
        private const val ENV_UNLOCK_SUPER_BLUR = "REAREYE_WEATHER_UNLOCK_SUPER_BLUR"
        private const val ENV_GALLERY_BACKUP_SERVER = "REAREYE_GALLERY_BACKUP_SERVER"
        private const val ENV_GALLERY_ENABLE_HDR_ENHANCED = "REAREYE_GALLERY_ENABLE_HDR_ENHANCED"
        private const val ENV_GALLERY_ENABLE_PDF = "REAREYE_GALLERY_ENABLE_PDF"
        private const val ENV_GALLERY_ENABLE_OCR = "REAREYE_GALLERY_ENABLE_OCR"
        private const val ENV_GALLERY_ENABLE_OCR_FORM = "REAREYE_GALLERY_ENABLE_OCR_FORM"
        private const val ENV_GALLERY_LONGER_TRASHBIN_TIME = "REAREYE_GALLERY_LONGER_TRASHBIN_TIME"
        private const val ENV_GALLERY_TRASH_RETENTION_DAYS = "REAREYE_GALLERY_TRASH_RETENTION_DAYS"
        private const val ENV_GALLERY_ENABLE_ID_PHOTO = "REAREYE_GALLERY_ENABLE_ID_PHOTO"
        private const val ENV_GALLERY_ENABLE_PHOTO_MOVIE = "REAREYE_GALLERY_ENABLE_PHOTO_MOVIE"
        private const val ENV_GALLERY_ENABLE_VIDEO_POST = "REAREYE_GALLERY_ENABLE_VIDEO_POST"
        private const val ENV_GALLERY_ENABLE_VIDEO_EDITOR = "REAREYE_GALLERY_ENABLE_VIDEO_EDITOR"
        private const val ENV_GALLERY_ENABLE_MAGIC_MATTING = "REAREYE_GALLERY_ENABLE_MAGIC_MATTING"
        private const val ENV_GALLERY_ENABLE_PRINT = "REAREYE_GALLERY_ENABLE_PRINT"
        private const val ENV_GALLERY_ENABLE_PRIVACY_WATERMARK =
            "REAREYE_GALLERY_ENABLE_PRIVACY_WATERMARK"

        private fun booleanFlag(
            hookPrefs: HookPrefs,
            key: String,
        ): String {
            return if (hookPrefs.getBoolean(key, false)) "1" else "0"
        }
    }
}
