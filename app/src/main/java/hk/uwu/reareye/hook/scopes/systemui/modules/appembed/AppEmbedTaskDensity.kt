package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.graphics.Rect
import java.util.IdentityHashMap
import kotlin.math.min

/** Task-scoped resource configuration derived from physical AppEmbed bounds and an explicit DPI. */
internal data class AppEmbedTaskDensity(
    /** Configuration.densityDpi applied only to the embedded child task. */
    val densityDpi: Int,
    /** Physical task width converted with Android's default 160 dpi baseline. */
    val screenWidthDp: Int,
    /** Physical task height converted with Android's default 160 dpi baseline. */
    val screenHeightDp: Int,
    /** Smallest logical dimension for resource qualifier selection. */
    val smallestScreenWidthDp: Int,
)

/** Computes one internally consistent task density override without changing physical bounds. */
internal object AppEmbedTaskDensityCalculator {
    private const val DEFAULT_DENSITY_DPI = 160L

    fun calculate(bounds: Rect, densityDpi: Int): AppEmbedTaskDensity {
        require(!bounds.isEmpty) { "Task density bounds must be non-empty: $bounds" }
        require(densityDpi > 0) { "Task density must be positive: $densityDpi" }
        val widthDp = pixelsToDp(bounds.width(), densityDpi)
        val heightDp = pixelsToDp(bounds.height(), densityDpi)
        return AppEmbedTaskDensity(
            densityDpi = densityDpi,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            smallestScreenWidthDp = min(widthDp, heightDp),
        )
    }

    /** Matches Configuration's nearest-integer pixel-to-dp conversion without float drift. */
    private fun pixelsToDp(pixels: Int, densityDpi: Int): Int {
        require(pixels > 0) { "Task dimension must be positive: $pixels" }
        val rounded = (pixels.toLong() * DEFAULT_DENSITY_DPI + densityDpi / 2L) / densityDpi
        require(rounded <= Int.MAX_VALUE.toLong()) {
            "Task dimension overflows dp range: pixels=$pixels densityDpi=$densityDpi"
        }
        // Configuration uses zero as "undefined", so a real sub-dp task must quantize to 1dp.
        return rounded.coerceAtLeast(1L).toInt()
    }
}

/** One verified child-task token associated with an owned TaskView controller. */
internal data class AppEmbedTaskDensityBinding(
    val taskId: Int,
    val controller: Any,
    val token: Any,
    val densityDpi: Int,
)

/**
 * Restricts density transactions to the child token first observed with an owned controller.
 * Binder token equality survives new WindowContainerToken wrapper instances; controllers use identity.
 */
internal class AppEmbedTaskDensityRegistry {
    private val tokenByController = IdentityHashMap<Any, Any>()
    private val bindingByToken = HashMap<Any, AppEmbedTaskDensityBinding>()

    @Synchronized
    fun register(
        controller: Any,
        taskId: Int,
        token: Any,
        densityDpi: Int,
    ): AppEmbedTaskDensityBinding {
        require(taskId > 0) { "Task id must be positive: $taskId" }
        require(densityDpi > 0) { "Task density must be positive: $densityDpi" }
        val priorToken = tokenByController[controller]
        check(priorToken == null || priorToken == token) {
            "TaskView controller changed child task token"
        }
        val priorBinding = bindingByToken[token]
        check(
            priorBinding == null ||
                    priorBinding.controller === controller &&
                    priorBinding.taskId == taskId &&
                    priorBinding.densityDpi == densityDpi
        ) {
            "Child task token is already bound to another AppEmbed task"
        }
        return priorBinding ?: AppEmbedTaskDensityBinding(
            taskId = taskId,
            controller = controller,
            token = token,
            densityDpi = densityDpi,
        ).also { binding ->
            tokenByController[controller] = token
            bindingByToken[token] = binding
        }
    }

    @Synchronized
    fun find(token: Any): AppEmbedTaskDensityBinding? = bindingByToken[token]

    @Synchronized
    fun remove(controller: Any) {
        val token = tokenByController.remove(controller) ?: return
        val binding = bindingByToken[token]
        if (binding?.controller === controller) bindingByToken.remove(token)
    }
}
