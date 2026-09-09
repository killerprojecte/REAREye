package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import android.content.Context
import android.view.View
import dalvik.system.InMemoryDexClassLoader
import hk.uwu.reareye.hook.core.YLog
import org.w3c.dom.Element
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.android.dx.DexMaker
import com.android.dx.TypeId
import com.android.dx.FieldId
import com.android.dx.MethodId
import com.android.dx.Code
import com.android.dx.Local

/**
 * 用 DexMaker 在宿主 ClassLoader 下动态生成 `ViewHolderScreenElement` 子类的工厂。
 *
 * 生成类本身只负责把生命周期调用转发到模块侧的 [AppEmbedBridge]，没有任何业务逻辑。
 * 所有宿主类型（`ViewHolderScreenElement`、`ScreenElementRoot`、`Element`）都通过
 * 类描述符引用，生成后的 dex 由宿主 ClassLoader 作 parent 加载，从而解析到宿主
 * 的 MAML 类，避免模块 ClassLoader 出现第二份 MAML 类导致类型不兼容。
 *
 * 注意：DexMaker 的 `Code` 一旦开始写入指令（`loadConstant`/`invoke`/`aput` 等），
 * 就不能再 `newLocal`。因此本文件在每个方法体开头集中创建所有需要的 `Local`。
 */
object AppEmbedElementFactory {

    private const val TAG = "AppEmbedElementFactory"
    private const val BASE_GENERATED_NAME = "hk.uwu.reareye.maml.RuntimeAppEmbedElement"

    private const val VIEW_HOLDER = "Lcom/miui/maml/elements/ViewHolderScreenElement;"
    private const val ROOT = "Lcom/miui/maml/ScreenElementRoot;"
    private const val SCREEN_CONTEXT = "Lcom/miui/maml/ScreenContext;"
    private const val CONTEXT = "Landroid/content/Context;"
    private const val ELEMENT = "Lorg/w3c/dom/Element;"
    private const val VIEW = "Landroid/view/View;"
    private const val METHOD = "Ljava/lang/reflect/Method;"
    private const val OBJECT = "Ljava/lang/Object;"
    private const val OBJECT_ARRAY = "[Ljava/lang/Object;"

    @Suppress("UNCHECKED_CAST")
    private val VOID_TYPE: TypeId<Any> = TypeId.VOID as TypeId<Any>

    private val counter = AtomicInteger()
    private val cache = ConcurrentHashMap<ClassLoader, ClassCacheEntry>()

    private data class ClassCacheEntry(
        val clazz: Class<*>,
        val bridgeClass: Class<*>,
    )

    /**
     * 元素标签名。当 `ScreenElementFactory.createInstance` 遇到此标签时由 Hook 拦截。
     */
    const val TAG_NAME = "AppEmbed"

    /**
     * 获取或生成一个可用的元素类。
     *
     * 如果同一个宿主 ClassLoader 上已经有当前模块代际生成的类，则复用；
     * 否则生成新的唯一类名并注入当前 [AppEmbedBridge] 的方法引用。
     */
    fun ensureElementClass(hostClassLoader: ClassLoader, bridge: Any): Class<*> {
        val bridgeClass = bridge.javaClass
        cache[hostClassLoader]?.let {
            if (it.bridgeClass == bridgeClass) return it.clazz
        }

        val id = counter.incrementAndGet()
        val className = "${BASE_GENERATED_NAME}_$id"
        val clazz = generateAndInject(hostClassLoader, bridge, className)
        cache[hostClassLoader] = ClassCacheEntry(clazz, bridgeClass)
        YLog.info("[$TAG] generated class $className on host=$hostClassLoader")
        return clazz
    }

    /**
     * 创建一个新的 AppEmbed 元素实例。
     *
     * @param hostClassLoader 宿主 `com.xiaomi.subscreencenter` 的 ClassLoader。
     * @param element XML 节点。
     * @param root 宿主 `ScreenElementRoot` 实例。
     */
    fun createElement(
        hostClassLoader: ClassLoader,
        element: Element,
        root: Any,
    ): Any {
        val bridge = AppEmbedBridge
        val clazz = ensureElementClass(hostClassLoader, bridge)
        val rootClass = Class.forName("com.miui.maml.ScreenElementRoot", false, hostClassLoader)
        val ctor = clazz.getConstructor(Element::class.java, rootClass)
        return ctor.newInstance(prepareAppEmbedXml(element), root)
    }

    private fun generateAndInject(
        hostClassLoader: ClassLoader,
        bridge: Any,
        className: String,
    ): Class<*> {
        val dexMaker = DexMaker()

        // 所有 TypeId 都显式标注为 Any，避免 Kotlin 泛型推断歧义。
        val generatedType: TypeId<Any> = TypeId.get(className.asTypeDescriptor())
        val viewHolderType: TypeId<Any> = TypeId.get(VIEW_HOLDER)
        val elementType: TypeId<Any> = TypeId.get(ELEMENT)
        val rootType: TypeId<Any> = TypeId.get(ROOT)
        val screenContextType: TypeId<Any> = TypeId.get(SCREEN_CONTEXT)
        val contextType: TypeId<Any> = TypeId.get(CONTEXT)
        val viewType: TypeId<Any> = TypeId.get(VIEW)
        val methodType: TypeId<Any> = TypeId.get(METHOD)
        val objType: TypeId<Any> = TypeId.get(OBJECT)
        val objArrayType: TypeId<Any> = TypeId.get(OBJECT_ARRAY)

        // public class RuntimeAppEmbedElement_? extends ViewHolderScreenElement
        dexMaker.declare(
            generatedType,
            "$className.generated",
            Modifier.PUBLIC,
            viewHolderType,
        )

        // static bridge method fields
        val bridgeField = generatedType.getField(objType, "sBridge")
        val mCreateField = generatedType.getField(methodType, "mCreate")
        val mGetViewField = generatedType.getField(methodType, "mGetView")
        val mOnViewAddedField = generatedType.getField(methodType, "mOnViewAdded")
        val mOnViewRemovedField = generatedType.getField(methodType, "mOnViewRemoved")
        val mFinishField = generatedType.getField(methodType, "mFinish")
        val mPauseField = generatedType.getField(methodType, "mPause")
        val mResumeField = generatedType.getField(methodType, "mResume")

        listOf(
            bridgeField,
            mCreateField,
            mGetViewField,
            mOnViewAddedField,
            mOnViewRemovedField,
            mFinishField,
            mPauseField,
            mResumeField
        )
            .forEach { dexMaker.declare(it, Modifier.PUBLIC or Modifier.STATIC, null) }

        val mDelegateField = generatedType.getField(objType, "mDelegate")
        dexMaker.declare(mDelegateField, Modifier.PRIVATE, null)

        val methodInvoke: MethodId<Any, Any> =
            methodType.getMethod(objType, "invoke", objType, objArrayType)

        // --- constructor (Element, ScreenElementRoot) ---
        val ctor = generatedType.getConstructor(elementType, rootType)
        val superCtor = viewHolderType.getConstructor(elementType, rootType)
        val ctorCode = dexMaker.declare(ctor, Modifier.PUBLIC)
        val ctorThis = ctorCode.getThis(generatedType)
        val ctorElement = ctorCode.getParameter(0, elementType)
        val ctorRoot = ctorCode.getParameter(1, rootType)

        // Locals first: DexMaker requires all newLocal before any instructions.
        val createResult = ctorCode.newLocal(objType)
        val createArgs = ctorCode.newLocal(objArrayType)
        val createIdx = ctorCode.newLocal(TypeId.INT)
        val createLen = ctorCode.newLocal(TypeId.INT)
        val createMethod = ctorCode.newLocal(methodType)
        val createBridge = ctorCode.newLocal(objType)
        val screenContext = ctorCode.newLocal(screenContextType)
        val androidContext = ctorCode.newLocal(contextType)

        ctorCode.invokeDirect(superCtor, null, ctorThis, ctorElement, ctorRoot)

        // MAML 类型只存在于宿主 ClassLoader；在生成类中直接读取真实 Context 后再跨桥。
        ctorCode.invokeVirtual(
            rootType.getMethod(screenContextType, "getContext"),
            screenContext,
            ctorRoot,
        )
        ctorCode.iget(
            screenContextType.getField(contextType, "mContext"),
            androidContext,
            screenContext,
        )

        ctorCode.loadConstant(createLen, 2)
        ctorCode.newArray(createArgs, createLen)

        ctorCode.loadConstant(createIdx, 0)
        ctorCode.aput(createArgs, createIdx, androidContext)
        ctorCode.loadConstant(createIdx, 1)
        ctorCode.aput(createArgs, createIdx, ctorElement)

        ctorCode.sget(mCreateField, createMethod)
        ctorCode.sget(bridgeField, createBridge)
        ctorCode.invokeVirtual(methodInvoke, createResult, createMethod, createBridge, createArgs)

        ctorCode.iput(mDelegateField, ctorThis, createResult)
        ctorCode.returnVoid()

        // --- public View getView() ---
        val getViewMethod = generatedType.getMethod(viewType, "getView")
        val getViewCode = dexMaker.declare(getViewMethod, Modifier.PUBLIC)
        emitGeneratedMethod(
            code = getViewCode,
            generatedType = generatedType,
            bridgeField = bridgeField,
            methodField = mGetViewField,
            delegateField = mDelegateField,
            returnType = viewType,
            argCount = 1,
            methodInvoke = methodInvoke,
        ) { code, argsArray, thisLocal, delegate, idx ->
            code.loadConstant(idx, 0)
            code.aput(argsArray, idx, delegate)
        }

        // --- public void onViewAdded(View view) ---
        val onViewAddedMethod = generatedType.getMethod(VOID_TYPE, "onViewAdded", viewType)
        val onViewAddedCode = dexMaker.declare(onViewAddedMethod, Modifier.PUBLIC)
        val onViewAddedParam = onViewAddedCode.getParameter(0, viewType)
        emitGeneratedMethod(
            code = onViewAddedCode,
            generatedType = generatedType,
            bridgeField = bridgeField,
            methodField = mOnViewAddedField,
            delegateField = mDelegateField,
            returnType = VOID_TYPE,
            argCount = 2,
            methodInvoke = methodInvoke,
        ) { code, argsArray, thisLocal, delegate, idx ->
            code.loadConstant(idx, 0)
            code.aput(argsArray, idx, delegate)
            code.loadConstant(idx, 1)
            code.aput(argsArray, idx, onViewAddedParam)
        }

        // --- public void onViewRemoved(View view) ---
        val onViewRemovedMethod = generatedType.getMethod(VOID_TYPE, "onViewRemoved", viewType)
        val onViewRemovedCode = dexMaker.declare(onViewRemovedMethod, Modifier.PUBLIC)
        val onViewRemovedParam = onViewRemovedCode.getParameter(0, viewType)
        emitGeneratedMethod(
            code = onViewRemovedCode,
            generatedType = generatedType,
            bridgeField = bridgeField,
            methodField = mOnViewRemovedField,
            delegateField = mDelegateField,
            returnType = VOID_TYPE,
            argCount = 2,
            methodInvoke = methodInvoke,
        ) { code, argsArray, thisLocal, delegate, idx ->
            code.loadConstant(idx, 0)
            code.aput(argsArray, idx, delegate)
            code.loadConstant(idx, 1)
            code.aput(argsArray, idx, onViewRemovedParam)
        }

        // --- public void finish() ---
        val finishMethod = generatedType.getMethod(VOID_TYPE, "finish")
        val finishCode = dexMaker.declare(finishMethod, Modifier.PUBLIC)
        val finishThis = finishCode.getThis(generatedType)

        // Locals first
        val finishResult = finishCode.newLocal(objType)
        val finishArgs = finishCode.newLocal(objArrayType)
        val finishIdx = finishCode.newLocal(TypeId.INT)
        val finishLen = finishCode.newLocal(TypeId.INT)
        val finishDelegate = finishCode.newLocal(objType)
        val finishMethodLocal = finishCode.newLocal(methodType)
        val finishBridge = finishCode.newLocal(objType)

        // MAML finishView still calls getView and onViewRemoved; tear down its hierarchy first.
        val superFinish = viewHolderType.getMethod(VOID_TYPE, "finish")
        finishCode.invokeSuper(superFinish, null, finishThis)
        finishCode.iget(mDelegateField, finishDelegate, finishThis)

        finishCode.loadConstant(finishLen, 1)
        finishCode.newArray(finishArgs, finishLen)
        finishCode.loadConstant(finishIdx, 0)
        finishCode.aput(finishArgs, finishIdx, finishDelegate)
        finishCode.sget(mFinishField, finishMethodLocal)
        finishCode.sget(bridgeField, finishBridge)
        finishCode.invokeVirtual(
            methodInvoke,
            finishResult,
            finishMethodLocal,
            finishBridge,
            finishArgs
        )

        finishCode.returnVoid()

        // Forward the real MAML activation lifecycle, which is independent of View visibility.
        for ((name, bridgeMethodField) in listOf(
            "pause" to mPauseField,
            "resume" to mResumeField
        )) {
            val lifecycleCode =
                dexMaker.declare(generatedType.getMethod(VOID_TYPE, name), Modifier.PUBLIC)
            emitGeneratedMethod(
                code = lifecycleCode,
                generatedType = generatedType,
                bridgeField = bridgeField,
                methodField = bridgeMethodField,
                delegateField = mDelegateField,
                returnType = VOID_TYPE,
                argCount = 1,
                methodInvoke = methodInvoke,
            ) { code, argsArray, thisLocal, delegate, idx ->
                code.invokeSuper(viewHolderType.getMethod(VOID_TYPE, name), null, thisLocal)
                code.loadConstant(idx, 0)
                code.aput(argsArray, idx, delegate)
            }
        }

        val dexBytes = dexMaker.generate()
        val loader = InMemoryDexClassLoader(
            arrayOf(java.nio.ByteBuffer.wrap(dexBytes)),
            hostClassLoader,
        )
        val clazz = loader.loadClass(className)

        injectBridgeMethods(clazz, bridge)
        return clazz
    }

    private fun injectBridgeMethods(clazz: Class<*>, bridge: Any) {
        val bridgeClass = bridge.javaClass

        val create = bridgeClass.getMethod("create", Context::class.java, Element::class.java)
        val getView = bridgeClass.getMethod("getView", Any::class.java)
        val onViewAdded = bridgeClass.getMethod("onViewAdded", Any::class.java, View::class.java)
        val onViewRemoved =
            bridgeClass.getMethod("onViewRemoved", Any::class.java, View::class.java)
        val onFinish = bridgeClass.getMethod("onFinish", Any::class.java)

        clazz.getField("sBridge").set(null, bridge)
        clazz.getField("mCreate").set(null, create)
        clazz.getField("mGetView").set(null, getView)
        clazz.getField("mOnViewAdded").set(null, onViewAdded)
        clazz.getField("mOnViewRemoved").set(null, onViewRemoved)
        clazz.getField("mFinish").set(null, onFinish)
        clazz.getField("mPause").set(null, bridgeClass.getMethod("onPause", Any::class.java))
        clazz.getField("mResume").set(null, bridgeClass.getMethod("onResume", Any::class.java))
    }

    /**
     * 生成一个非 `finish` 的桥接转发方法体。
     *
     * 所有 Local 先创建再写指令；`fillArgs` 只负责往 argsArray 里填参数。
     */
    private fun emitGeneratedMethod(
        code: Code,
        generatedType: TypeId<Any>,
        bridgeField: FieldId<Any, Any>,
        methodField: FieldId<Any, Any>,
        delegateField: FieldId<Any, Any>,
        returnType: TypeId<Any>,
        argCount: Int,
        methodInvoke: MethodId<Any, Any>,
        fillArgs: (Code, Local<Any>, Local<Any>, Local<Any>, Local<Int>) -> Unit,
    ) {
        // --- all locals first ---
        val thisLocal = code.getThis(generatedType)
        val delegate = code.newLocal(objType())
        val argsArray = code.newLocal(objArrayType())
        val arrLen = code.newLocal(TypeId.INT)
        val result = code.newLocal(objType())
        val typedResult = if (returnType == VOID_TYPE) null else code.newLocal(returnType)
        val method = code.newLocal(methodType())
        val bridge = code.newLocal(objType())
        val idx = code.newLocal(TypeId.INT)

        // --- instructions ---
        code.iget(delegateField, delegate, thisLocal)
        code.loadConstant(arrLen, argCount)
        code.newArray(argsArray, arrLen)

        fillArgs(code, argsArray, thisLocal, delegate, idx)

        code.sget(methodField, method)
        code.sget(bridgeField, bridge)
        code.invokeVirtual(methodInvoke, result, method, bridge, argsArray)

        if (returnType == VOID_TYPE) {
            code.returnVoid()
        } else {
            code.cast(typedResult!!, result)
            code.returnValue(typedResult)
        }
    }

    private fun objType(): TypeId<Any> = TypeId.get(OBJECT)
    private fun objArrayType(): TypeId<Any> = TypeId.get(OBJECT_ARRAY)
    private fun methodType(): TypeId<Any> = TypeId.get(METHOD)

    private fun String.asTypeDescriptor(): String = "L${this.replace('.', '/')};"
}
