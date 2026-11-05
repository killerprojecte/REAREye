package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import hk.uwu.reareye.hook.support.YLog

/** Uses the hooked application's Context, package and UID, never the module application's identity. */
internal class AppWidgetPlatform(context: Context) : WidgetPlatform {
    private val context = context.applicationContext ?: context
    private val preferences =
        this.context.getSharedPreferences("reareye.maml.appwidget.v1", Context.MODE_PRIVATE)
    private val bindings = WidgetBindingStore(preferences) { failure ->
        YLog.warn("AppWidget typed preference read failed; restored binding from validated snapshot", failure)
    }
    private val manager = AppWidgetManager.getInstance(this.context)
    private val hostId: Int = preferences.getInt("host_id", 0x52455747).also {
        check(preferences.edit().putInt("host_id", it).commit()) { "Cannot persist widget host ID" }
    }

    private class SharedHost(context: Context, id: Int) : AppWidgetHost(context, id) {
        private val providerDensity = context.resources.displayMetrics.density
        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo
        ): AppWidgetHostView =
            AppWidgetFitHostView(context, providerDensity)

        fun releaseAllViews() {
            clearViews()
        }
    }

    private val host = SharedHost(this.context, hostId)
    private val sizeCache = WidgetSizeCache()
    private val viewRegistry = WidgetViewRegistry()
    private val removeListener = runCatching {
        AppWidgetHost::class.java.getMethod("removeListener", Int::class.javaPrimitiveType)
    }.getOrNull()
    val sessions = WidgetSessions(this)

    override fun reportFailure(operation: String, throwable: Throwable) {
        runCatching { YLog.warn("AppWidget platform failed: operation=$operation", throwable) }
    }

    override fun read(key: String): WidgetBinding? = bindings.read(key)

    override fun write(key: String, binding: WidgetBinding?) = bindings.write(key, binding)

    /** Disabled by default; preference switch is shared with the hooked host, not module prefs. */
    override fun diagnose(
        key: String, state: WidgetState, id: Int?, complete: Boolean, phase: WidgetPhase?,
        owned: Boolean?, providerMatches: Boolean?, providerExists: Boolean?
    ) {
        if (!preferences.getBoolean("diagnostics_enabled", false)) return
        val keyHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        Log.d(
            "REAREye.AppWidget",
            "key=$keyHash host=${context.packageName}/${context.applicationInfo.uid}/$hostId " +
                    "state=$state id=$id complete=$complete phase=$phase owned=$owned " +
                    "providerMatches=$providerMatches providerExists=$providerExists"
        )
    }

    override fun ownedIds(): Set<Int> = host.appWidgetIds.toSet()
    override fun providerOf(id: Int): String? =
        manager.getAppWidgetInfo(id)?.provider?.flattenToString()

    override fun providerExists(provider: String): Boolean =
        manager.installedProviders.any { it.provider.flattenToString() == provider }

    override fun needsConfiguration(provider: String): Boolean = checkNotNull(
        manager.installedProviders
            .firstOrNull { it.provider.flattenToString() == provider }) {
        "Widget provider metadata unavailable"
    }.configure != null

    override fun allocate(): Int = host.allocateAppWidgetId()
    override fun delete(id: Int) {
        sizeCache.clear(id); host.deleteAppWidgetId(id)
    }

    override fun bind(id: Int, provider: String): Boolean =
        manager.bindAppWidgetIdIfAllowed(id, component(provider))

    override fun startListening() {
        host.startListening()
    }

    override fun stopListening() {
        host.stopListening()
    }

    override fun request(
        kind: WidgetRequest,
        id: Int,
        provider: String,
        result: (WidgetResult) -> Unit
    ): (() -> Unit)? {
        return when (kind) {
            WidgetRequest.BIND -> AppWidgetActivityBridge.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, component(provider))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), result
            )

            WidgetRequest.CONFIGURE -> {
                val info = manager.getAppWidgetInfo(id)
                check(info?.provider == component(provider)) { "Widget provider changed before configuration" }
                checkNotNull(info.configure) { "Widget configuration unavailable" }
                AppWidgetActivityBridge.launch(result) { activity, code ->
                    host.startAppWidgetConfigureActivityForResult(activity, id, 0, code, null)
                }
            }
        }
    }

    fun createHostView(context: Context, id: Int, provider: String): AppWidgetHostView {
        sizeCache.clear(id)
        check(id in ownedIds()) { "Widget ID is not owned by this host" }
        val info = checkNotNull(manager.getAppWidgetInfo(id)) { "Widget provider unavailable" }
        check(info.provider == component(provider)) { "Widget provider changed" }
        // The shared host can retain a listener on vendor builds without removeListener().
        // Use an application-derived visual/configuration Context, never retain the launcher Activity.
        val display = runCatching { context.display }.getOrNull()
        val displayContext =
            if (display != null) this.context.createDisplayContext(display) else this.context
        val viewContext = displayContext.createConfigurationContext(context.resources.configuration)
        return host.createView(viewContext, id, info).also { view ->
            viewRegistry.register(id, view)
            (view as? AppWidgetFitHostView)?.onNaturalSizeChanged = { size ->
                updateNaturalSize(view, id, size)
            }
        }
    }

    fun releaseHostView(id: Int, view: AppWidgetHostView) {
        (view as? AppWidgetFitHostView)?.onNaturalSizeChanged = null
        sizeCache.clear(id)
        val released = runCatching {
            viewRegistry.release(id, view, removeSingle = {
                val method = removeListener
                method != null && runCatching { method.invoke(host, id) }
                    .onFailure { reportFailure("remove-listener", it) }.isSuccess
            }, clearAll = host::releaseAllViews)
        }.getOrElse {
            reportFailure("release-host-view", it)
            false
        }
        if (!released) Log.w(
            "REAREye.AppWidget",
            "Listener $id release deferred until remaining live views close"
        )
    }

    fun invalidateSize(id: Int) {
        sizeCache.clear(id)
    }

    fun updateSize(view: AppWidgetHostView, id: Int, widthPx: Int, heightPx: Int) {
        // The viewport is NOT a provider layout size. Changing it only changes the View transform.
        val natural =
            (view as? AppWidgetFitHostView)?.naturalSize() ?: WidgetNaturalGeometry.naturalSize(
                view.appWidgetInfo?.minWidth ?: 0, view.appWidgetInfo?.minHeight ?: 0,
                context.resources.displayMetrics.density, view.resources.displayMetrics.density,
                view.paddingLeft + view.paddingRight, view.paddingTop + view.paddingBottom
            )
        updateNaturalSize(view, id, natural)
    }

    private fun updateNaturalSize(view: AppWidgetHostView, id: Int, natural: WidgetNaturalSize) {
        val density = view.resources.displayMetrics.density
        sizeCache.update(id, natural.widthPx, natural.heightPx, density) { sizes ->
            // Deduplicate before both Binder calls, not merely before the final update.
            val options = Bundle(manager.getAppWidgetOptions(id))
            sizes.forEach { (key, value) -> options.putInt(key, value) }
            view.updateAppWidgetSize(
                options, sizes.getValue(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                sizes.getValue(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                sizes.getValue(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                sizes.getValue(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            )
        }
    }

    private fun component(value: String): ComponentName =
        requireNotNull(ComponentName.unflattenFromString(value))

    companion object {
        private val hosts = mutableMapOf<String, AppWidgetPlatform>()
        fun shared(context: Context): AppWidgetPlatform {
            AppWidgetActivityBridge.checkMain()
            val identity = "${context.packageName}:${context.applicationInfo.uid}"
            return hosts.getOrPut(identity) { AppWidgetPlatform(context) }
        }
    }
}

/** Only our real widgets route clicks to the main screen; native cards retain their options. */
internal object WidgetLaunchPolicy {
    fun routeToMainDisplay(view: View, options: ActivityOptions): Boolean {
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor is AppWidgetHostView) {
                if (ancestor !is AppWidgetFitHostView) return false
                // RemoteViews uses the clicked View's display for both activity and broadcast
                // PendingIntents. Preserve the original Intent, flags, animation and BAL options.
                options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
                return true
            }
            ancestor = ancestor.parent as? View
        }
        return false
    }
}

/**
 * FrameLayout's own bounds are the safe viewport; its RemoteViews child is measured and laid out
 * on a separate natural canvas. Child matrices implement fit-center and Android automatically
 * applies their inverse during touch dispatch. Never resize/re-inflate a widget to the viewport.
 */
internal class AppWidgetFitHostView(context: Context, private val providerDensity: Float) :
    AppWidgetHostView(context) {
    init {
        // Padding belongs to the scaled natural canvas, not an unscaled viewport clipping inset.
        clipToPadding = false
        clipChildren = true
    }

    private var measuredNatural = WidgetNaturalSize(1, 1)
    private var lastNotified: WidgetNaturalSize? = null
    private var notificationPosted = false
    var onNaturalSizeChanged: ((WidgetNaturalSize) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) {
                removeCallbacks(notifyNaturalSize)
                notificationPosted = false
                lastNotified = null
            }
        }
    internal var fitTransform = WidgetFitTransform(0f, 0f, 0f)
        private set

    private val notifyNaturalSize = Runnable {
        notificationPosted = false
        val callback = onNaturalSizeChanged
        val size = naturalSize()
        if (callback != null && size != lastNotified) {
            runCatching { callback(size) }
                .onSuccess { lastNotified = size }
                .onFailure { error ->
                    runCatching { YLog.warn("AppWidget natural size update failed", error) }
                }
        }
    }

    fun naturalSize(): WidgetNaturalSize {
        var fixedWidth = 0
        var fixedHeight = 0
        for (index in 0 until childCount) {
            val fixed = fixedExtent(getChildAt(index))
            fixedWidth = maxOf(fixedWidth, fixed.widthPx)
            fixedHeight = maxOf(fixedHeight, fixed.heightPx)
        }
        val info = appWidgetInfo
        return WidgetNaturalGeometry.naturalSize(
            info?.minWidth ?: 0, info?.minHeight ?: 0,
            providerDensity, resources.displayMetrics.density,
            paddingLeft + paddingRight, paddingTop + paddingBottom, fixedWidth, fixedHeight
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measuredNatural = naturalSize()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(measuredNatural.widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredNatural.heightPx, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(
            resolveSize(measuredNatural.widthPx, widthMeasureSpec),
            resolveSize(measuredNatural.heightPx, heightMeasureSpec)
        )
        if (onNaturalSizeChanged != null && measuredNatural != lastNotified && !notificationPosted) {
            notificationPosted = true
            post(notifyNaturalSize) // coalesced, outside measure/layout; never requestLayout here
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val viewportWidth = measuredWidth
        val viewportHeight = measuredHeight
        try {
            // AppWidgetHostView may select/reinflate a responsive RemoteViews variant here and
            // measures that new root using getMeasuredWidth/Height. It must see the natural canvas.
            setMeasuredDimension(measuredNatural.widthPx, measuredNatural.heightPx)
            super.onLayout(changed, 0, 0, measuredNatural.widthPx, measuredNatural.heightPx)
            layoutNaturalChildren()
        } finally {
            setMeasuredDimension(viewportWidth, viewportHeight)
        }
        fitTransform = WidgetNaturalGeometry.fit(measuredNatural, right - left, bottom - top)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val scale = fitTransform.scale
            val tx = fitTransform.left + child.left * scale - child.left
            val ty = fitTransform.top + child.top * scale - child.top
            if (child.pivotX != 0f) child.pivotX = 0f
            if (child.pivotY != 0f) child.pivotY = 0f
            if (child.scaleX != scale) child.scaleX = scale
            if (child.scaleY != scale) child.scaleY = scale
            if (child.translationX != tx) child.translationX = tx
            if (child.translationY != ty) child.translationY = ty
        }
    }

    private fun layoutNaturalChildren() {
        val parentLeft = paddingLeft
        val parentTop = paddingTop
        val parentRight = measuredNatural.widthPx - paddingRight
        val parentBottom = measuredNatural.heightPx - paddingBottom
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val params = child.layoutParams as LayoutParams
            val gravity = if (params.gravity == -1) Gravity.TOP or Gravity.START else params.gravity
            val absolute = Gravity.getAbsoluteGravity(gravity, layoutDirection)
            val width = child.measuredWidth
            val height = child.measuredHeight
            val x = when (absolute and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.CENTER_HORIZONTAL -> parentLeft + (parentRight - parentLeft - width) / 2 + params.leftMargin - params.rightMargin
                Gravity.RIGHT -> parentRight - width - params.rightMargin
                else -> parentLeft + params.leftMargin
            }
            val y = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.CENTER_VERTICAL -> parentTop + (parentBottom - parentTop - height) / 2 + params.topMargin - params.bottomMargin
                Gravity.BOTTOM -> parentBottom - height - params.bottomMargin
                else -> parentTop + params.topMargin
            }
            child.layout(x, y, x + width, y + height)
        }
    }

    /** Fixed children can exceed provider metadata. Honor their lower bounds without treating
     * wrap-content text as an infinitely wide line, or expanding intentionally scrolling lists. */
    private fun fixedExtent(view: View): WidgetNaturalSize {
        if (view.visibility == GONE) return WidgetNaturalSize(0, 0)
        val params = view.layoutParams
        var width = maxOf(params?.width?.coerceAtLeast(0) ?: 0, view.minimumWidth)
        var height = maxOf(params?.height?.coerceAtLeast(0) ?: 0, view.minimumHeight)
        if (view is ViewGroup && view !is ScrollView && view !is HorizontalScrollView && view !is AdapterView<*>) {
            var childrenWidth = 0
            var childrenHeight = 0
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.visibility == GONE) continue
                val fixed = fixedExtent(child)
                if (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL)
                    childrenWidth += fixed.widthPx else childrenWidth =
                    maxOf(childrenWidth, fixed.widthPx)
                if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL)
                    childrenHeight += fixed.heightPx else childrenHeight =
                    maxOf(childrenHeight, fixed.heightPx)
            }
            width = maxOf(width, childrenWidth + view.paddingLeft + view.paddingRight)
            height = maxOf(height, childrenHeight + view.paddingTop + view.paddingBottom)
        }
        val margins = params as? MarginLayoutParams
        return WidgetNaturalSize(
            width + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0),
            height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
        )
    }
}
