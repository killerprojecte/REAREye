package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import java.lang.reflect.Proxy

/** No screenshot or event injection. Only the acknowledged visible task region becomes a hole. */
internal class ActivityHostSurfaceView(context: Context) : FrameLayout(context),
    SurfaceHolder.Callback {
    val surface = SurfaceView(context)
    var onSurfaceChanged: (() -> Unit)? = null
    var onSurfaceLost: (() -> Unit)? = null
    var onFrame: (() -> Unit)? = null
    var onNavigation: ((Boolean) -> Unit)? = null
    var onAbiFailure: ((String) -> Unit)? = null
    private var hole: Rect? = null
    private var leftNavigation = Rect()
    private var rightNavigation = Rect()
    private var navigating = false
    private var listener: Any? = null
    private var listenerClass: Class<*>? = null
    private var observer: ViewTreeObserver? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { onFrame?.invoke(); true }
    var surfaceReady = false
        private set
    var inputAbiReady = false
        private set

    init {
        addView(surface, LayoutParams(1, 1))
        surface.holder.addCallback(this)
        isClickable = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true; onSurfaceChanged?.invoke()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true; onSurfaceChanged?.invoke()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false; setInputHole(null); onSurfaceLost?.invoke()
    }

    fun surfaceControl(): SurfaceControl? {
        if (!surfaceReady || !isAttachedToWindow) return null
        return try {
            val value = SurfaceView::class.java.getDeclaredMethod("getSurfaceControl")
                .apply { isAccessible = true }.invoke(surface) as? SurfaceControl
            value?.takeIf { it.isValid } ?: error("SurfaceControl无效")
        } catch (error: Exception) {
            onAbiFailure?.invoke("SurfaceView ABI不可用：${error.javaClass.simpleName}")
            null
        }
    }

    fun positionSurface(local: Rect) {
        val current = surface.layoutParams as LayoutParams
        if (current.width == local.width() && current.height == local.height() && current.leftMargin == local.left && current.topMargin == local.top) return
        surface.layoutParams = LayoutParams(local.width(), local.height()).apply {
            leftMargin = local.left; topMargin = local.top
        }
    }

    fun navigation(left: Rect, right: Rect) {
        leftNavigation = Rect(left); rightNavigation = Rect(right)
    }

    fun setInputHole(screenBounds: Rect?) {
        val next = screenBounds?.let(::Rect)
        if (hole == next) return
        hole = next
        rootView.requestLayout() // causes ViewRootImpl to recompute internal touchable insets
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                navigating = leftNavigation.contains(
                    event.rawX.toInt(),
                    event.rawY.toInt()
                ) || rightNavigation.contains(event.rawX.toInt(), event.rawY.toInt())
                if (navigating) {
                    setInputHole(null); onNavigation?.invoke(true)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (navigating) {
                    navigating = false; onNavigation?.invoke(false)
                }
            }
        }
        // Do not consume navigation DOWN: the original GestureManager/pager remains the owner.
        return super.dispatchTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = rootView.viewTreeObserver
        observer?.addOnPreDrawListener(preDraw)
        try {
            val type =
                Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val proxy =
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(instance)
                        "equals" -> instance === args?.getOrNull(0)
                        "toString" -> "RearActivityHostInsets"
                        "onComputeInternalInsets" -> {
                            args?.getOrNull(0)?.let { subtractHole(it) }; null
                        }

                        else -> null
                    }
                }
            ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", type)
                .invoke(observer, proxy)
            listenerClass = type; listener = proxy; inputAbiReady = true
        } catch (error: Exception) {
            inputAbiReady = false
            onAbiFailure?.invoke("输入区域ABI不可用：${error.javaClass.simpleName}")
        }
    }

    private fun subtractHole(info: Any) {
        val screen = hole ?: return
        if (!isAttachedToWindow || !isShown || windowVisibility != View.VISIBLE) return
        try {
            val type = info.javaClass
            val region = type.getField("touchableRegion").get(info) as Region
            val mode =
                type.getDeclaredField("mTouchableInsets").apply { isAccessible = true }.getInt(info)
            val root = rootView
            // Preserve a previous listener's region; FRAME/CONTENT/VISIBLE must be materialized first.
            if (mode != 3) {
                val inset = when (mode) {
                    1 -> type.getField("contentInsets").get(info) as Rect
                    2 -> type.getField("visibleInsets").get(info) as Rect
                    else -> Rect()
                }
                region.set(
                    inset.left,
                    inset.top,
                    root.width - inset.right,
                    root.height - inset.bottom
                )
            }
            val origin = IntArray(2)
            root.getLocationOnScreen(origin)
            val local = Rect(screen).apply { offset(-origin[0], -origin[1]) }
            region.op(local, Region.Op.DIFFERENCE)
            type.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(info, 3)
        } catch (error: Exception) {
            setInputHole(null); inputAbiReady = false
            onAbiFailure?.invoke("输入区域ABI失败：${error.javaClass.simpleName}")
        }
    }

    override fun onDetachedFromWindow() {
        setInputHole(null)
        observer?.takeIf { it.isAlive }?.let { tree ->
            tree.removeOnPreDrawListener(preDraw)
            listenerClass?.let { type ->
                runCatching {
                    ViewTreeObserver::class.java.getMethod(
                        "removeOnComputeInternalInsetsListener",
                        type
                    ).invoke(tree, listener)
                }
            }
        }
        listener = null; listenerClass = null; observer = null; inputAbiReady = false
        onSurfaceLost?.invoke()
        super.onDetachedFromWindow()
    }
}
