package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import org.w3c.dom.Element
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolve every required ABI before installing a single hook. No bundled MAML classes. */
internal class MamlHostBridge(loader: ClassLoader) {
    val factoryClass = loader.loadClass("com.miui.maml.elements.ScreenElementFactory")
    val rootClass = loader.loadClass("com.miui.maml.ScreenElementRoot")
    val layerClass = loader.loadClass("com.miui.maml.elements.LayerScreenElement")
    private val holderClass = loader.loadClass("com.miui.maml.elements.ViewHolderScreenElement")
    private val screenClass = loader.loadClass("com.miui.maml.elements.ScreenElement")
    private val groupClass = loader.loadClass("com.miui.maml.elements.ElementGroup")
    val create = method(factoryClass, "createInstance", Element::class.java, rootClass)
    private val constructor = layerClass.getConstructor(Element::class.java, rootClass)
    val getView = method(layerClass, "getView")
    val init = method(layerClass, "init")
    val tick = method(layerClass, "doTick", Long::class.javaPrimitiveType!!)
    val render = method(layerClass, "doRender", Canvas::class.java)
    val finish = method(holderClass, "finish")
    val resume = method(screenClass, "resume")
    val pause = method(screenClass, "pause")
    val visibility = method(holderClass, "onVisibilityChange", Boolean::class.javaPrimitiveType!!)
    val updateView = method(holderClass, "onUpdateView", View::class.java)
    val holderInit = method(holderClass, "init")

    // ElementGroupRC inherits this body. It advances AnimatedScreenElement/children but,
    // unlike ViewHolder.doTick, never postInvalidates the Android View every MAML frame.
    val animationTick = method(groupClass, "doTick", Long::class.javaPrimitiveType!!)
    private val outlineConfig = field(holderClass, "mOutlineProviderConfig")
    private val outlineProvider = field(holderClass, "mOutlineProvider")
    private val hasOutlineConfig = method(outlineConfig.type, "hasConfig")
    private val nativeUpdateOutline = method(holderClass, "updateOutlineProvider")
    private val nativeNeedsOutline = method(holderClass, "shouldApplyOutlineProvider")
    private val nativeApplyOutline = method(holderClass, "applyOutlineProvider")
    private val layout = field(holderClass, "mLayoutParams")
    private val updatePosition = field(holderClass, "mUpdatePosition")
    private val updateSize = field(holderClass, "mUpdateSize")
    private val updateTranslation = field(holderClass, "mUpdateTranslation")
    private val isVisible = method(screenClass, "isVisible")
    private val rootContext = method(rootClass, "getContext")
    private val getVariables = method(rootClass, "getVariables")
    private val expressionClass = loader.loadClass("com.miui.maml.data.Expression")
    private val expressionBuild =
        method(expressionClass, "build", getVariables.returnType, String::class.java)
    private val evaluateString = method(expressionClass, "evaluateStr")
    private val evaluateNumber = runCatching { method(expressionClass, "evaluate") }.getOrNull()
    private val androidContext = field(rootContext.returnType, "mContext")
    private val getters = listOf(
        "getAbsoluteLeft", "getAbsoluteTop", "getWidth", "getHeight", "getX", "getY",
        "getPivotX", "getPivotY", "getRotation", "getRotationX", "getRotationY",
        "getAlpha", "getScaleX", "getScaleY",
    ).associateWith { method(layerClass, it) }

    init {
        check(screenClass.isAssignableFrom(create.returnType))
        check(holderClass.isAssignableFrom(layerClass))
        check(groupClass.isAssignableFrom(holderClass))
        check(animationTick.declaringClass == groupClass)
        check(View::class.java.isAssignableFrom(getView.returnType))
        check(ViewGroup.LayoutParams::class.java.isAssignableFrom(layout.type))
        check(Context::class.java.isAssignableFrom(androidContext.type))
        check(Modifier.isStatic(expressionBuild.modifiers))
        check(expressionClass.isAssignableFrom(expressionBuild.returnType))
        check(evaluateString.returnType == String::class.java)
    }

    fun createCarrier(element: Element, root: Any): Any = constructor.newInstance(element, root)
    fun context(root: Any): Context = androidContext.get(rootContext.invoke(root)) as Context
    fun visible(element: Any): Boolean = isVisible.invoke(element) as Boolean

    /** Native-thread metadata evaluation: independent outline expressions may animate without
     * any geometry change. Plain rectangular layer metadata only needs changed geometry. */
    fun updateOutline(element: Any, geometryChanged: Boolean): Boolean {
        val config = outlineConfig.get(element)
        val configured = config != null && hasOutlineConfig.invoke(config) == true
        if (geometryChanged || configured) nativeUpdateOutline.invoke(element)
        return configured && requiresOutlineApplication(element)
    }

    private fun requiresOutlineApplication(element: Any): Boolean {
        if (nativeNeedsOutline.invoke(element) == true) return true
        val provider = outlineProvider.get(element) ?: return false
        val view = getView.invoke(element) as? View ?: return false
        // finish -> init replaces the Android View, not the native provider. Identical
        // bounds must still attach that provider to the new View once.
        return view.outlineProvider !== provider
    }

    /** Main only. Native provider deduplicates bounds; never render an empty ElementGroup canvas. */
    fun applyOutline(element: Any) {
        if (requiresOutlineApplication(element)) nativeApplyOutline.invoke(element)
    }

    fun expressions(root: Any): MamlStringExpressionCompiler {
        val weakRoot = WeakReference(root)
        fun <T> evaluator(source: String, evaluate: (Any) -> T?): () -> T? {
            var expression: WeakReference<Any>? = null
            return {
                val owner = weakRoot.get()
                if (owner == null) {
                    expression = null
                    null
                } else {
                    val built = expression?.get() ?: requireNotNull(
                        expressionBuild.invoke(null, getVariables.invoke(owner), source),
                    ) { "Invalid MAML expression: $source" }.also {
                        expression = WeakReference(it)
                    }
                    evaluate(built)
                }
            }
        }
        return object : MamlStringExpressionCompiler, MamlNumericExpressionCompiler {
            override fun activityLaunchPermit(
                instance: String,
                component: String,
                dpi: Int,
                request: String
            ): ActivityHostLaunchPermit? =
                weakRoot.get()?.let {
                    MamlActivityCardBridge.getIssuedLaunch(
                        it,
                        instance,
                        component,
                        dpi,
                        request
                    )
                }

            override fun compile(expression: String): () -> String? =
                evaluator(expression) { evaluateString.invoke(it) as String? }

            override fun compileNumber(expression: String): () -> Double? =
                evaluator(expression) {
                    (requireNotNull(evaluateNumber) { "Numeric MAML expression ABI unavailable" }
                        .invoke(it) as? Number)?.toDouble()
                }
        }
    }

    fun layoutParams(element: Any): ViewGroup.LayoutParams =
        layout.get(element) as ViewGroup.LayoutParams

    fun geometry(element: Any): MamlViewGeometry {
        fun number(name: String) = (getters.getValue(name).invoke(element) as Number).toFloat()
        return MamlViewGeometry(
            number("getAbsoluteLeft"), number("getAbsoluteTop"),
            number("getWidth").toInt(), number("getHeight").toInt(),
            number("getPivotX"), number("getPivotY"),
            number("getRotation"), number("getRotationX"), number("getRotationY"),
            number("getAlpha") / 255f, number("getScaleX"), number("getScaleY"),
            updatePosition.getBoolean(element), updateSize.getBoolean(element),
            updateTranslation.getBoolean(element), number("getX"), number("getY"),
        )
    }

    companion object {
        fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
            var cursor: Class<*>? = type
            while (cursor != null) {
                val found =
                    runCatching { cursor!!.getDeclaredMethod(name, *parameters) }.getOrNull()
                if (found != null) return found.apply { isAccessible = true }
                cursor = cursor.superclass
            }
            error("Required host method missing: ${type.name}.$name")
        }

        private fun field(type: Class<*>, name: String): Field {
            var cursor: Class<*>? = type
            while (cursor != null) {
                val found = runCatching { cursor!!.getDeclaredField(name) }.getOrNull()
                if (found != null) return found.apply { isAccessible = true }
                cursor = cursor.superclass
            }
            error("Required host field missing: ${type.name}.$name")
        }
    }
}
