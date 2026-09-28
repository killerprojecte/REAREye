package hk.uwu.reareye.hook.preset

import android.system.Os
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.isRearDevice
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/** 把目标应用访问的系统预置路径重定向到当前已提交 RPP 快照。 */
class PresetPackFilesHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        if (!isRearDevice) return
        val store = PresetPackRuntimeStore(hookPrefs, hookAppInfo)
        if (!store.load()) return

        runCatching {
            hookFileState(store)
            hookFileInputStream(store)
            hookOsOpen(store)
            hookZipFile(store)
        }.onFailure { error ->
            YLog.warn("Preset pack hooks failed for $packageName: ${error.message}")
        }
    }

    private fun PackageScope.hookFileState(store: PresetPackRuntimeStore) {
        File::class.java.resolve().firstMethod {
            name = "exists"
            parameterCount = 0
        }.hook {
            replaceAny {
            val file = instance<File>()
            val local = store.redirect(file.path)
            val directory = store.redirectDir(file.path)
                if (local != null || directory != null) true else callOriginal()
            }
        }
        File::class.java.resolve().firstMethod {
            name = "isFile"
            parameterCount = 0
        }.hook {
            replaceAny {
            val file = instance<File>()
                store.redirect(file.path)?.let { true } ?: callOriginal()
            }
        }
        File::class.java.resolve().firstMethod {
            name = "isDirectory"
            parameterCount = 0
        }.hook {
            replaceAny {
            val file = instance<File>()
                store.redirectDir(file.path)?.let { true } ?: callOriginal()
            }
        }
        File::class.java.resolve().firstMethod {
            name = "length"
            parameterCount = 0
        }.hook {
            replaceAny {
            val file = instance<File>()
                store.redirect(file.path)?.length() ?: callOriginal()
            }
        }
        File::class.java.resolve().firstMethod {
            name = "list"
            parameterCount = 0
        }.hook {
            replaceAny {
            val file = instance<File>()
                store.appendNames(file.path, callOriginal() as? Array<String>)
            }
        }
    }

    private fun PackageScope.hookFileInputStream(store: PresetPackRuntimeStore) {
        FileInputStream::class.java.resolve().firstConstructor {
            parameters(String::class.java)
        }.hook {
            before {
            val path = args.getOrNull(0) as? String
            val local = store.redirect(path)
            local?.absolutePath?.let { args[0] = it }
            }
        }
        FileInputStream::class.java.resolve().firstConstructor {
            parameters(File::class.java)
        }.hook {
            before {
            val file = args.getOrNull(0) as? File
            val local = file?.let { store.redirect(it.path) }
            local?.let { args[0] = it }
            }
        }
    }

    private fun PackageScope.hookOsOpen(store: PresetPackRuntimeStore) {
        runCatching {
            Os::class.java.resolve().firstMethod {
                name = "open"
                parameters(
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
            }.hook {
                before {
                val path = args.getOrNull(0) as? String
                val local = store.redirect(path)
                local?.absolutePath?.let { args[0] = it }
                }
            }
        }
    }

    private fun PackageScope.hookZipFile(store: PresetPackRuntimeStore) {
        ZipFile::class.java.resolve().firstConstructor {
            parameters(String::class.java)
        }.hook {
            before {
            val path = args.getOrNull(0) as? String
            val local = store.redirect(path)
            local?.absolutePath?.let { args[0] = it }
            }
        }
        ZipFile::class.java.resolve().firstConstructor {
            parameters(File::class.java)
        }.hook {
            before {
            val file = args.getOrNull(0) as? File
            val local = file?.let { store.redirect(it.path) }
            local?.let { args[0] = it }
            }
        }
        runCatching {
            ZipFile::class.java.resolve().firstConstructor {
                parameters(File::class.java, Int::class.javaPrimitiveType!!)
            }.hook {
                before {
                val file = args.getOrNull(0) as? File
                val local = file?.let { store.redirect(it.path) }
                local?.let { args[0] = it }
                }
            }
        }
    }

}
