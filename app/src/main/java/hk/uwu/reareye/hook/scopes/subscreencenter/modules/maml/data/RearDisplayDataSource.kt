package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display

/** All geometry is in physical pixels. Null insets mean absent cutout, not zero insets. */
data class RearDisplayInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class RearDisplayGeometry(
    val width: Int,
    val height: Int,
    val insets: RearDisplayInsets? = null,
    val densityDpi: Int = 0,
    val rotation: Int = 0,
    val device: String = "",
    val model: String = "",
)

fun interface RearDisplayGeometryProvider {
    fun read(): RearDisplayGeometry?
}

class RearDisplayDataSource(context: Context, provider: RearDisplayGeometryProvider? = null) :
    MamlDataSource {
    private val app = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val manager = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    // Retain only the non-default Display, never the host Activity or main-screen metrics.
    private val hostDisplay = runCatching { context.display }.getOrNull()
        ?.takeIf { it.displayId != Display.DEFAULT_DISPLAY }

    private fun rearDisplay(): Display? =
        hostDisplay?.let { manager?.getDisplay(it.displayId) ?: it }
            ?.takeIf { it.isValid } ?: manager?.getDisplay(1)
            ?.takeIf { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }

    private val geometry = provider ?: RearDisplayGeometryProvider {
        rearDisplay()?.let { display ->
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val width = size.x.takeIf { it > 0 } ?: metrics.widthPixels
            val height = size.y.takeIf { it > 0 } ?: metrics.heightPixels
            val cutout = if (Build.VERSION.SDK_INT >= 29) display.cutout else null
            RearDisplayGeometry(width, height, cutout?.let {
                RearDisplayInsets(
                    it.safeInsetLeft,
                    it.safeInsetTop,
                    it.safeInsetRight,
                    it.safeInsetBottom
                )
            }, metrics.densityDpi, display.rotation, Build.DEVICE, Build.MODEL)
        }
    }
    private val subscriptions = linkedMapOf<Any, () -> Unit>()
    private var paused = false
    private var closed = false
    private var listening = false

    @Volatile
    private var generation = 0
    private var pending = false
    private val delivery = Runnable {
        pending = false
        val epoch = generation
        subscriptions.toList().forEach { (key, callback) ->
            if (!paused && !closed && epoch == generation && subscriptions[key] === callback) {
                try {
                    callback()
                } catch (_: Exception) { /* Keep independent subscribers isolated. */
                }
            }
        }
    }

    private fun invalidate(epoch: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { invalidate(epoch) }; return
        }
        if (epoch == generation && !closed && !paused && listening && !pending) {
            pending = true
            main.post(delivery)
        }
    }

    private var listener: DisplayManager.DisplayListener? = null
    private var configurationListener: ComponentCallbacks? = null
    private fun attach() {
        if (listening || closed || paused || subscriptions.isEmpty()) return
        val epoch = generation
        val displayListener = object : DisplayManager.DisplayListener {
            private fun changed(id: Int) {
                if (id == 1 || id == hostDisplay?.displayId) invalidate(epoch)
            }

            override fun onDisplayAdded(displayId: Int) = changed(displayId)
            override fun onDisplayRemoved(displayId: Int) = changed(displayId)
            override fun onDisplayChanged(displayId: Int) = changed(displayId)
        }
        val configListener = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = invalidate(epoch)
            override fun onLowMemory() {}
        }
        manager?.registerDisplayListener(displayListener, main)
        try {
            app.registerComponentCallbacks(configListener)
        } catch (e: Exception) {
            manager?.unregisterDisplayListener(displayListener); throw e
        }
        listener = displayListener
        configurationListener = configListener
        listening = true
    }

    private fun detach() {
        generation++ // Reject platform callbacks queued by a previous registration, even after resume.
        if (listening) {
            listening = false
            listener?.let { manager?.unregisterDisplayListener(it) }
            configurationListener?.let { app.unregisterComponentCallbacks(it) }
            listener = null
            configurationListener = null
        }
        main.removeCallbacks(delivery)
        pending = false
    }

    override fun query(request: MamlDataRequest, result: (MamlDataResult) -> Unit): MamlDataHandle {
        checkMain()
        if (paused || closed) return MamlDataHandle.NONE
        val currentGeneration = generation
        val output = try {
            geometry.read()?.let(::snapshot) ?: MamlDataResult.Failure("Rear display unavailable")
        } catch (e: Exception) {
            MamlDataResult.Failure(e.message ?: e.javaClass.simpleName)
        }
        // Reading geometry is bounded and main-thread-only; synchronous completion is supported.
        if (!closed && !paused && generation == currentGeneration) result(output)
        return MamlDataHandle.NONE
    }

    override fun subscribeInvalidations(invalidate: () -> Unit): MamlDataHandle {
        checkMain()
        if (closed) return MamlDataHandle.NONE
        val key = Any()
        subscriptions[key] = invalidate
        try {
            attach()
        } catch (e: Exception) {
            subscriptions.remove(key); throw e
        }
        return MamlDataHandle {
            checkMain()
            subscriptions.remove(key)
            if (subscriptions.isEmpty()) detach()
        }
    }

    override fun onPause() {
        checkMain()
        paused = true
        generation++
        detach()
    }

    override fun onResume() {
        checkMain()
        if (closed) return
        paused = false
        attach()
    }

    override fun close() {
        checkMain()
        if (closed) return
        onPause()
        closed = true
        subscriptions.clear()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        const val TAG = "RearDisplayBinder"
        const val FALLBACK_LEFT_PX = 296
        val schema = MamlDataSchema(
            listOf(
                "width",
                "height",
                "safeLeft",
                "safeTop",
                "safeRight",
                "safeBottom",
                "densityDpi"
            )
                .map { MamlDataField(it, MamlDataType.NUMBER) })

        fun definition() = MamlDataSourceDefinition(
            TAG,
            schema,
            emptyList(),
            MamlDataSourceFactory { RearDisplayDataSource(it) })

        /** safe* are clamped physical-pixel inset distances on each side. Empty rectangles fail. */
        internal fun snapshot(geometry: RearDisplayGeometry): MamlDataResult {
            val width = geometry.width
            val height = geometry.height
            if (width <= 0 || height <= 0) return MamlDataResult.Failure("Invalid rear display dimensions")
            // Only this Max landscape pose has a measured fallback. Unknown devices/poses
            // must not inherit a guessed camera boundary; runtime cutout always wins.
            val measuredMax = width == 976 && height == 596 && geometry.rotation == 0 &&
                    geometry.device == "popsicle" && geometry.model == "2509FPN0BC"
            val insets = geometry.insets ?: RearDisplayInsets(
                if (measuredMax) FALLBACK_LEFT_PX else 0, 0, 0, 0
            )
            val left = insets.left.coerceIn(0, width)
            val top = insets.top.coerceIn(0, height)
            val right = width - insets.right.coerceIn(0, width)
            val bottom = height - insets.bottom.coerceIn(0, height)
            if (right <= left || bottom <= top) return MamlDataResult.Failure("Rear display has no safe area")
            return MamlDataResult.Success(
                MamlDataSnapshot(
                    mapOf(
                        "width" to width.toDouble(),
                        "height" to height.toDouble(),
                        // 0 means unavailable for injected/legacy geometry; never substitute main-screen density.
                        "densityDpi" to geometry.densityDpi.coerceAtLeast(0).toDouble(),
                        "safeLeft" to left.toDouble(),
                        "safeTop" to top.toDouble(),
                        "safeRight" to (width - right).toDouble(),
                        "safeBottom" to (height - bottom).toDouble(),
                    ), 1
                )
            )
        }
    }
}
