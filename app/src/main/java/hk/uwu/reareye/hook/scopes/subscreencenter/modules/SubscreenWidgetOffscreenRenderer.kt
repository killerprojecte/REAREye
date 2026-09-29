package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.view.isEmpty
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object SubscreenWidgetRenderHostRegistry {
    data class Host(
        val context: Context,
        val panel: ViewGroup,
        val handler: Handler,
    )

    @Volatile
    private var current: Host? = null

    fun update(context: Context, panel: ViewGroup, handler: Handler) {
        current = Host(context.applicationContext ?: context, panel, handler)
    }

    fun snapshot(): Host? = current

    fun clear(panel: Any? = null) {
        if (panel == null || current?.panel === panel) current = null
    }
}

internal data class SubscreenWidgetRuntimeController(
    val attachHost: (widget: Any, host: ViewGroup) -> Unit,
    val setEditMode: (widget: Any, enabled: Boolean) -> Unit,
    val setPreviewMode: (widget: Any, enabled: Boolean) -> Unit,
    val createView: (widget: Any, context: Context) -> View?,
    val setAodState: (widget: Any, inAod: Boolean) -> Unit,
    val resume: (widget: Any) -> Unit,
    val cleanup: (widget: Any) -> Unit,
)

internal object SubscreenWidgetOffscreenRenderer {
    private const val INITIAL_DELAY_MS = 450L
    private const val RETRY_INTERVAL_MS = 120L
    private const val CAPTURE_TIMEOUT_MS = 4500L

    @Synchronized
    fun renderToFile(
        host: SubscreenWidgetRenderHostRegistry.Host,
        widget: Any,
        targetFile: File,
        controller: SubscreenWidgetRuntimeController,
        renderSize: Point? = null,
        editMode: Boolean = false,
        debug: (String) -> Unit = {},
    ): String {
        check(!host.handler.looper.isCurrentThread) {
            "Offscreen preview capture must not run on the main thread"
        }
        val size = renderSize?.takeIf { it.x > 0 && it.y > 0 }
            ?: Point(host.panel.width, host.panel.height).takeIf { it.x > 0 && it.y > 0 }
            ?: Point(host.panel.measuredWidth, host.panel.measuredHeight)
                .takeIf { it.x > 0 && it.y > 0 }
            ?: error("Offscreen preview render size is invalid")
        val bitmapRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)

        fun complete(bitmap: Bitmap? = null, error: Throwable? = null) {
            if (!finished.compareAndSet(false, true)) {
                bitmap?.recycle()
                return
            }
            bitmapRef.set(bitmap)
            errorRef.set(error)
            latch.countDown()
        }

        host.handler.post {
            runCatching {
                val renderOverlay = FrameLayout(host.context).apply {
                    layoutParams = ViewGroup.LayoutParams(size.x, size.y)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isClickable = false
                    isFocusable = false
                    alpha = 0f
                    clipChildren = false
                    clipToPadding = false
                }
                val renderHost = FrameLayout(host.context).apply {
                    layoutParams = ViewGroup.LayoutParams(size.x, size.y)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isClickable = false
                    isFocusable = false
                    clipChildren = false
                    clipToPadding = false
                }
                renderOverlay.addView(renderHost)
                var cleaned = false

                fun layoutRenderTree() {
                    val widthSpec =
                        View.MeasureSpec.makeMeasureSpec(size.x, View.MeasureSpec.EXACTLY)
                    val heightSpec =
                        View.MeasureSpec.makeMeasureSpec(size.y, View.MeasureSpec.EXACTLY)
                    renderOverlay.measure(widthSpec, heightSpec)
                    renderOverlay.layout(0, 0, size.x, size.y)
                    renderHost.measure(widthSpec, heightSpec)
                    renderHost.layout(0, 0, size.x, size.y)
                }

                fun cleanup() {
                    if (cleaned) return
                    cleaned = true
                    runCatching { controller.cleanup(widget) }
                    runCatching { (renderOverlay.parent as? ViewGroup)?.removeView(renderOverlay) }
                }

                fun finish(bitmap: Bitmap? = null, error: Throwable? = null) {
                    cleanup()
                    complete(bitmap, error)
                }

                runCatching {
                    host.panel.addView(renderOverlay)
                    layoutRenderTree()
                    controller.attachHost(widget, renderHost)
                    controller.setEditMode(widget, editMode)
                    controller.setPreviewMode(widget, true)
                    val createdView = controller.createView(widget, host.context)
                    if (createdView == null && renderHost.isEmpty()) {
                        error("Offscreen widget view was not created")
                    }
                    layoutRenderTree()
                    controller.setAodState(widget, false)
                    controller.resume(widget)
                    val startedAt = System.currentTimeMillis()
                    var attempt = 0

                    fun capture() {
                        runCatching {
                            attempt += 1
                            layoutRenderTree()
                            val bitmap = captureBitmap(renderHost)
                            val visible = bitmap.hasVisiblePixels()
                            val elapsed = System.currentTimeMillis() - startedAt
                            debug("attempt=${attempt} elapsed=${elapsed}ms visible=${visible} size=${size.x}x${size.y}")
                            if (visible) {
                                finish(bitmap = bitmap)
                            } else {
                                bitmap.recycle()
                                if (elapsed >= CAPTURE_TIMEOUT_MS) {
                                    error("Offscreen preview stayed blank")
                                }
                                renderHost.postDelayed({ capture() }, RETRY_INTERVAL_MS)
                            }
                        }.onFailure { finish(error = it) }
                    }

                    renderHost.postDelayed({ capture() }, INITIAL_DELAY_MS)
                }.onFailure { finish(error = it) }
            }.onFailure { complete(error = it) }
        }

        if (!latch.await(CAPTURE_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS)) {
            complete(error = IllegalStateException("Offscreen preview capture timed out"))
        }
        errorRef.get()?.let { throw it }
        val bitmap = bitmapRef.get() ?: error("Offscreen preview capture failed")
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "Failed to encode offscreen preview"
                }
                output.toByteArray()
            }
            writeAtomically(targetFile, bytes)
            targetFile.absolutePath
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureBitmap(view: View): Bitmap {
        check(view.width > 0 && view.height > 0) { "Offscreen render host has invalid size" }
        return createBitmap(view.width, view.height).also { bitmap ->
            view.draw(Canvas(bitmap))
        }
    }

    private fun Bitmap.hasVisiblePixels(): Boolean {
        val stepX = (width / 24).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if ((this[x, y] ushr 24) != 0) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { it.write(bytes) }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.setReadable(true, false)
    }
}
