package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import hk.uwu.reareye.hook.support.YLog
import org.w3c.dom.Element

/** XML: <RearAppWidget key="stable-key" provider="package/.Provider" /> */
class AppWidgetElementFactory : MamlExpressionBackendFactory {
    override fun prepare(
        element: Element,
        expressions: MamlStringExpressionCompiler
    ): MamlBackendBinding {
        fun input(attribute: String): () -> String {
            val source = element.getAttribute("${attribute}Exp").trim()
            if (source.isNotEmpty()) {
                val evaluate = expressions.compile(source)
                return { evaluate().orEmpty().trim() }
            }
            val fixed = element.getAttribute(attribute).trim()
            require(fixed.isNotEmpty()) { "RearAppWidget requires $attribute or ${attribute}Exp" }
            if (attribute == "provider") requireNotNull(ComponentName.unflattenFromString(fixed)) {
                "RearAppWidget requires a flattened ComponentName provider"
            }
            return { fixed }
        }

        val key = input("key")
        val provider = input("provider")
        val requestSource = element.getAttribute("userRequestExp").trim()
        val request = if (requestSource.isEmpty()) ({ "" }) else {
            val evaluate = expressions.compile(requestSource)
            val sample: () -> String = { evaluate().orEmpty().trim() }
            sample
        }
        return MamlBackendBinding(
            createBackend = { AppWidgetElementBackend("", "") },
            evaluateInputs = { MamlBackendInputs(key(), provider(), userRequest = request()) },
        )
    }

    override fun create(element: Element): MamlViewBackend {
        val key = element.getAttribute("key").trim()
        require(key.isNotEmpty()) { "RearAppWidget requires a stable key" }
        val raw = element.getAttribute("provider").trim()
        val provider = requireNotNull(ComponentName.unflattenFromString(raw)) {
            "RearAppWidget requires a flattened ComponentName provider"
        }
        return AppWidgetElementBackend(key, provider.flattenToString())
    }

    companion object {
        const val TAG = "RearAppWidget"

        /** Main-thread user action; refuses any key currently claimed by a live backend. */
        @JvmStatic
        fun deleteBinding(context: Context, key: String): Boolean {
            AppWidgetActivityBridge.checkMain()
            return AppWidgetPlatform.shared(context).sessions.deleteBinding(key)
        }
    }
}

/** Injection seam for testing selection without creating the Android AppWidgetHost. */
internal interface AppWidgetBackendPlatform {
    fun open(key: String, provider: String, changed: () -> Unit): WidgetSession
    fun createHostView(context: Context, id: Int, provider: String): AppWidgetHostView
    fun updateSize(view: AppWidgetHostView, id: Int, width: Int, height: Int)
    fun releaseHostView(id: Int, view: AppWidgetHostView)
    fun invalidateSize(id: Int)
}

private fun widgetBackendPlatform(context: Context): AppWidgetBackendPlatform {
    val host = AppWidgetPlatform.shared(context)
    return object : AppWidgetBackendPlatform {
        override fun open(key: String, provider: String, changed: () -> Unit) =
            host.sessions.open(key, provider, changed)

        override fun createHostView(context: Context, id: Int, provider: String) =
            host.createHostView(context, id, provider)

        override fun updateSize(view: AppWidgetHostView, id: Int, width: Int, height: Int) =
            host.updateSize(view, id, width, height)

        override fun releaseHostView(id: Int, view: AppWidgetHostView) =
            host.releaseHostView(id, view)

        override fun invalidateSize(id: Int) = host.invalidateSize(id)
    }
}

internal class AppWidgetElementBackend(
    private var key: String,
    private var provider: String,
    private val platformFactory: (Context) -> AppWidgetBackendPlatform = ::widgetBackendPlatform,
    private val enqueueFrame: (() -> Unit) -> (() -> Unit) = { work ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { work() }
        choreographer.postFrameCallback(callback)
        val cancel: () -> Unit = { choreographer.removeFrameCallback(callback) }
        cancel
    },
) : MamlViewBackend {
    private var container: FrameLayout? = null
    private var status: TextView? = null
    private var widget: AppWidgetHostView? = null
    private var widgetId: Int? = null
    private var platform: AppWidgetBackendPlatform? = null
    private val requiresInputs = key.isEmpty() || provider.isEmpty()
    private var confirmedVisible = false
    private var selectionRejected = false
    private var session: WidgetSession? = null
    private var finished = false
    private var resumed = false
    private var visible = false
    private var attached = false
    private var width = 0
    private var height = 0
    private var rendering = false
    private var lastInputs: MamlBackendInputs? = null
    private var sizedView: AppWidgetHostView? = null
    private var sizedWidth = 0
    private var sizedHeight = 0
    private var sizedDensity = 0f
    private var initialized = false
    private var pendingUserRequest: String? = null
    private var lastUserRequest = ""
    private var framePending = false
    private var cancelFrame: (() -> Unit)? = null
    private var attachGeneration = 0
    private var lastDiagnostic = ""

    private fun diagnostic(event: String) {
        if (!Log.isLoggable("REAREye.AppWidget", Log.DEBUG)) return
        val snapshot =
            "$event keyHash=${key.hashCode().toUInt().toString(16)} provider=$provider " +
                    "state=${session?.state} id=${session?.id} visible=$visible confirmed=$confirmedVisible " +
                    "resumed=$resumed attached=$attached initialized=$initialized size=${width}x$height view=$widgetId"
        if (snapshot != lastDiagnostic) {
            lastDiagnostic = snapshot
            Log.d("REAREye.AppWidget", snapshot)
        }
    }

    private fun cancelPendingFrame() {
        ++attachGeneration
        framePending = false
        cancelFrame?.invoke()
        cancelFrame = null
    }

    private fun consumeUserRequest() {
        val request = pendingUserRequest ?: return
        if (!initialized || !resumed || !attached || !visible || !confirmedVisible || selectionRejected) return
        val current = session ?: return
        // Clear first: initialize/bind may synchronously re-enter render().
        pendingUserRequest = null
        if (request.startsWith("$key|$provider|")) {
            diagnostic("user-confirm")
            current.click()
        }
    }

    override fun createView(context: Context): View {
        AppWidgetActivityBridge.checkMain()
        check(!finished) { "Widget backend already finished" }
        container?.let { return it }
        val root = FrameLayout(context)
        root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        container = root
        val text = TextView(context).apply {
            gravity = Gravity.CENTER
            text = "正在恢复小组件"
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                try {
                    ensureSession(context)
                    if (visible && !selectionRejected) session?.click()
                } catch (exception: Exception) {
                    showError(exception, "placeholder-click")
                }
            }
        }
        status = text
        root.addView(text, FrameLayout.LayoutParams(-1, -1))
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = onAttachmentChanged(true)
            override fun onViewDetachedFromWindow(view: View) = onAttachmentChanged(false)
        })
        try {
            ensureSession(context)
        } catch (exception: Exception) {
            showError(exception, "create-view")
        }
        return root
    }

    private fun ensureSession(context: Context) {
        if (finished || !visible || !confirmedVisible || selectionRejected || key.isEmpty() || provider.isEmpty()) return
        if (session != null) return
        val component = requireNotNull(ComponentName.unflattenFromString(provider)) {
            "RearAppWidget requires a flattened ComponentName provider"
        }
        provider = component.flattenToString()
        val host = platformFactory(context)
        platform = host
        session = host.open(key, provider, ::render)
        // WidgetSession publishes state changes from initialize/lifecycle itself. A repeat
        // ensure must not re-validate the provider via Binder or render an unchanged View.
        session?.initialize()
        session?.lifecycle(resumed, visible, attached)
        // open() can return a conflicting session without publishing initialize/lifecycle.
        if (session?.state == WidgetState.CONFLICT) render()
        diagnostic("session-open")
        consumeUserRequest()
    }

    override fun onInputsChanged(inputs: MamlBackendInputs) {
        AppWidgetActivityBridge.checkMain()
        if (finished || lastInputs == inputs) return
        lastInputs = inputs
        val wasVisible = visible && !selectionRejected
        val nextKey = inputs.key.trim()
        val rawProvider = inputs.provider.trim()
        val nextProvider =
            ComponentName.unflattenFromString(rawProvider)?.flattenToString() ?: rawProvider
        confirmedVisible = inputs.visible && nextKey.isNotEmpty() && nextProvider.isNotEmpty()
        visible = confirmedVisible
        val current = session
        selectionRejected = current != null && nextKey.isNotEmpty() && nextProvider.isNotEmpty() &&
                (nextKey != key || nextProvider != provider)
        if (current == null) {
            key = nextKey
            provider = nextProvider
        }
        container?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        val activeVisible = visible && !selectionRejected
        if (!activeVisible) {
            pendingUserRequest = null
            cancelPendingFrame()
        }
        val request = inputs.userRequest.trim()
        if (request.isNotEmpty() && request != lastUserRequest) {
            lastUserRequest = request
            if (activeVisible && request.startsWith("$key|$provider|")) pendingUserRequest = request
        }
        if (wasVisible != activeVisible) current?.lifecycle(visible = activeVisible)
        if (selectionRejected) {
            showError(IllegalStateException("已打开的小组件不能更换 key/provider；请重载卡片"))
            status?.isEnabled = false
            return
        }
        try {
            container?.let { ensureSession(it.context) }
            consumeUserRequest()
            // An accepted sample need not change WidgetSession's lifecycle. Revisit READY
            // mounting explicitly; render's gates and frame/size caches keep this cheap.
            render()
        } catch (exception: Exception) {
            showError(exception, "inputs")
        }
        diagnostic("inputs")
    }

    override fun onInit() {
        AppWidgetActivityBridge.checkMain()
        if (finished || initialized) return
        initialized = true
        try {
            container?.let { ensureSession(it.context) }
            consumeUserRequest()
            render()
        } catch (exception: Exception) {
            showError(exception, "initialize-view")
        }
    }

    override fun onResume() {
        AppWidgetActivityBridge.checkMain()
        if (resumed) return
        resumed = true
        session?.lifecycle(resumed = true)
        consumeUserRequest()
    }

    override fun onPause() {
        AppWidgetActivityBridge.checkMain()
        pendingUserRequest = null
        cancelPendingFrame()
        if (!resumed) return
        resumed = false
        session?.lifecycle(resumed = false)
    }

    override fun onVisibilityChanged(visible: Boolean) {
        AppWidgetActivityBridge.checkMain()
        if (!requiresInputs) confirmedVisible = visible
        val nextVisible = visible && confirmedVisible
        if (this.visible == nextVisible) return
        this.visible = nextVisible
        if (!nextVisible) {
            pendingUserRequest = null
            cancelPendingFrame()
        }
        container?.visibility = if (this.visible) View.VISIBLE else View.INVISIBLE
        session?.lifecycle(visible = this.visible && !selectionRejected)
        try {
            container?.let { ensureSession(it.context) }
        } catch (exception: Exception) {
            showError(exception, "lifecycle")
        }
    }

    override fun onAttachmentChanged(attached: Boolean) {
        AppWidgetActivityBridge.checkMain()
        if (this.attached == attached) return
        this.attached = attached
        if (!attached) cancelPendingFrame()
        session?.lifecycle(attached = attached)
        consumeUserRequest()
    }

    override fun onSizeChanged(widthPx: Int, heightPx: Int) {
        AppWidgetActivityBridge.checkMain()
        val nextWidth = widthPx.coerceAtLeast(0)
        val nextHeight = heightPx.coerceAtLeast(0)
        if (width == nextWidth && height == nextHeight) {
            // Geometry may be repeated after a cancelled mount, not just after a density
            // change. Revisit READY when unmounted; existing views keep the size cache.
            if (session?.state == WidgetState.READY && widget == null) render() else updateSize()
            return
        }
        width = nextWidth
        height = nextHeight
        if (width <= 0 || height <= 0) cancelPendingFrame()
        if (session?.state == WidgetState.READY && widget == null) render() else updateSize()
    }

    override fun onFinish() {
        AppWidgetActivityBridge.checkMain()
        if (finished) return
        finished = true
        pendingUserRequest = null
        cancelPendingFrame()
        session?.finish()
        clearWidget()
        container?.removeAllViews()
        status?.setOnClickListener(null)
        status = null
        container = null
        session = null
        platform = null
    }

    private fun render() {
        if (rendering || finished || !visible || !confirmedVisible || selectionRejected) return
        val root = container ?: return
        val current = session ?: return
        diagnostic("render")
        rendering = true
        try {
            if (current.state == WidgetState.READY) {
                val id = requireNotNull(current.id)
                if (widgetId != id) clearWidget()
                if (widget == null) {
                    status?.text = "正在加载已绑定的小组件"
                    status?.isEnabled = false
                    if (initialized && resumed && attached && width > 0 && height > 0 && !framePending) {
                        framePending = true
                        val token = attachGeneration
                        val cancel = enqueueFrame {
                            if (token == attachGeneration) {
                                framePending = false
                                cancelFrame = null
                                if (!finished && visible && confirmedVisible && !selectionRejected && resumed && attached &&
                                    width > 0 && height > 0 && session === current && current.state == WidgetState.READY && current.id == id
                                ) {
                                    try {
                                        val view = requireNotNull(platform).createHostView(
                                            root.context,
                                            id,
                                            provider
                                        )
                                        widget = view
                                        widgetId = id
                                        root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
                                        view.addOnLayoutChangeListener(object :
                                            View.OnLayoutChangeListener {
                                            override fun onLayoutChange(
                                                v: View, l: Int, t: Int, r: Int, b: Int,
                                                oldL: Int, oldT: Int, oldR: Int, oldB: Int
                                            ) {
                                                if (r > l && b > t) {
                                                    v.removeOnLayoutChangeListener(this)
                                                    if (!finished && widget === v && current.state == WidgetState.READY) {
                                                        status?.visibility = View.GONE
                                                        diagnostic("first-layout")
                                                    }
                                                }
                                            }
                                        })
                                        updateSize()
                                        diagnostic("host-created")
                                    } catch (exception: Exception) {
                                        current.fail(exception.message ?: "Widget unavailable")
                                        showError(exception, "create-host-view")
                                    }
                                }
                            }
                        }
                        // Injected schedulers may execute synchronously.
                        if (framePending && token == attachGeneration) cancelFrame =
                            cancel else cancel()
                    }
                } else {
                    widget?.visibility = View.VISIBLE
                    if ((widget?.width ?: 0) > 0 && (widget?.height ?: 0) > 0) status?.visibility =
                        View.GONE
                    updateSize()
                }
            } else {
                cancelPendingFrame()
                clearWidget()
                status?.visibility = View.VISIBLE
                status?.isEnabled = current.state !in setOf(
                    WidgetState.WAIT_BIND,
                    WidgetState.WAIT_CONFIGURE,
                    WidgetState.CONFLICT,
                    WidgetState.FINISHED
                )
                status?.text = when (current.state) {
                    WidgetState.CONFLICT -> "稳定键重复：请关闭重复的卡片后重试"
                    WidgetState.WAIT_BIND -> "等待小组件授权"
                    WidgetState.WAIT_CONFIGURE -> "等待小组件配置"
                    WidgetState.NEED_CONFIGURE -> "点击继续配置小组件"
                    WidgetState.ERROR -> "小组件暂不可用，点击重试\n${current.error.orEmpty()}"
                    else -> current.error ?: "点击添加桌面小组件"
                }
            }
        } catch (exception: Exception) {
            current.fail(exception.message ?: "Widget unavailable")
            showError(exception, "render")
        } finally {
            rendering = false
        }
    }

    private fun updateSize() {
        if (width <= 0 || height <= 0 || session?.state != WidgetState.READY) return
        val view = widget ?: return
        val id = session?.id ?: return
        if (widgetId != id) {
            render(); return
        }
        val density = view.resources.displayMetrics.density
        if (sizedView === view && sizedWidth == width && sizedHeight == height && sizedDensity == density) return
        // Mark before the platform call: provider size updates can synchronously request
        // another layout/render, and must not recurse into natural-size traversal.
        sizedView = view
        sizedWidth = width
        sizedHeight = height
        sizedDensity = density
        try {
            platform?.updateSize(view, id, width, height)
        } catch (exception: Exception) {
            sizedView = null
            session?.fail(exception.message ?: "Widget size update failed")
            showError(exception, "update-size")
        }
    }

    private fun clearWidget() {
        val oldView = widget
        val oldId = widgetId
        // Clear our references before notifying the platform, even if vendor cleanup fails.
        widget = null
        widgetId = null
        sizedView = null
        oldView?.let { container?.removeView(it) }
        if (oldId != null && oldView != null) platform?.releaseHostView(oldId, oldView)
        else oldId?.let { platform?.invalidateSize(it) }
    }

    private fun showError(exception: Exception, operation: String = "selection") {
        // Keep diagnostics independent of UI recovery; never append preference contents.
        runCatching { YLog.warn("AppWidget backend failed: operation=$operation", exception) }
        // Does not change session state: fail() owns notification, avoiding render/fail recursion.
        clearWidget()
        status?.visibility = View.VISIBLE
        status?.isEnabled = true
        status?.text = "小组件暂不可用，点击重试\n${exception.message.orEmpty()}"
    }
}
