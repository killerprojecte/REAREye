package hk.uwu.reareye.hook.scopes.system.modules

import android.app.ActivityOptions
import android.content.Intent
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.core.YLog
import hk.uwu.reareye.hook.core.YukiBaseHooker
import hk.uwu.reareye.internal.appembed.AppEmbedInitialLayout
import java.util.Collections
import java.util.WeakHashMap

/** Applies broker-owned geometry before ActivityStarter attaches the Activity to its new task. */
internal class AppEmbedInitialLayoutHook : YukiBaseHooker() {
    /** Constructor provenance is supplied by Android, not by extras from the target application. */
    private val systemUiActivities = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    override fun onHook() {
        loadSystem {
            val taskClass = "com.android.server.wm.Task".toClass()
            val adapter =
                AppEmbedTaskConfigurationAdapter.create(checkNotNull(taskClass.classLoader))
            "com.android.server.wm.ActivityRecord".toClass().resolve().firstConstructor {
                parameterCount = 21
            }.hook().after {
                if (!isGenerationActive || throwable != null) return@after
                if (args(4).string() == "com.android.systemui") systemUiActivities[instance] = true
            }
            taskClass.resolve().firstMethod {
                name = "reuseOrCreateTask"
                parameterCount = 8
            }.hook().after {
                if (!isGenerationActive || throwable != null) return@after
                val activity = args(5).any() ?: return@after
                if (systemUiActivities[activity] != true) return@after
                val options = args(7).cast<ActivityOptions>() ?: return@after
                if (options.launchDisplayId != 1 ||
                    options.toBundle().getInt("android.activity.windowingMode") != 6
                ) return@after
                val intent = args(1).cast<Intent>() ?: return@after
                try {
                    val layout = AppEmbedInitialLayout.readFrom(intent) ?: return@after
                    val task = result ?: error("AppEmbed task creation returned null")
                    adapter.apply(arrayOf(task, layout.bounds, layout.densityOverride()))
                    YLog.info("[AppEmbedInitialLayout] configured before Activity attachment bounds=${layout.bounds} dpi=${layout.densityDpi}")
                } catch (error: Throwable) {
                    YLog.error("[AppEmbedInitialLayout] initial task configuration failed", error)
                }
            }
        }
    }
}
