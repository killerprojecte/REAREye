package hk.uwu.reareye.hook.scopes.system.modules

import com.android.dx.DexMaker
import com.android.dx.TypeId
import dalvik.system.InMemoryDexClassLoader
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.function.Function

/** Direct bytecode calls to public system_server configuration methods unavailable in the app SDK. */
internal object AppEmbedTaskConfigurationAdapter {
    /** Only class construction crosses the injected framework boundary; configuration uses direct calls. */
    @Suppress("UNCHECKED_CAST")
    fun create(loader: ClassLoader): Function<Array<Any>, Any?> {
        val name = "hk.uwu.reareye.generated.AppEmbedTaskConfiguration"
        return InMemoryDexClassLoader(ByteBuffer.wrap(generateDex(name)), loader)
            .loadClass(name).getConstructor().newInstance() as Function<Array<Any>, Any?>
    }

    /** Emits a typed task configuration update and preserves unrelated task override fields. */
    fun generateDex(name: String): ByteArray {
        val dex = DexMaker()
        val generated = TypeId.get<Any>("L${name.replace('.', '/')};")
        val obj = TypeId.get<Any>("Ljava/lang/Object;")
        val array = TypeId.get<Array<Any>>("[Ljava/lang/Object;")
        val function = TypeId.get<Any>("Ljava/util/function/Function;")
        val taskType = TypeId.get<Any>("Lcom/android/server/wm/Task;")
        val configType = TypeId.get<Any>("Landroid/content/res/Configuration;")
        val windowType = TypeId.get<Any>("Landroid/app/WindowConfiguration;")
        val rectType = TypeId.get<Any>("Landroid/graphics/Rect;")
        dex.declare(generated, "$name.generated", Modifier.PUBLIC, obj, function)
        val ctor = dex.declare(generated.getConstructor(), Modifier.PUBLIC)
        ctor.invokeDirect(obj.getConstructor(), null, ctor.getThis(generated))
        ctor.returnVoid()
        val code = dex.declare(generated.getMethod(obj, "apply", obj), Modifier.PUBLIC)
        val input = code.getParameter(0, obj)
        val request = code.newLocal(array)
        val raw = code.newLocal(obj)
        val index = code.newLocal(TypeId.INT)
        val task = code.newLocal(taskType)
        val bounds = code.newLocal(rectType)
        val override = code.newLocal(configType)
        val prior = code.newLocal(configType)
        val config = code.newLocal(configType)
        val window = code.newLocal(windowType)
        val changes = code.newLocal(TypeId.INT)
        val screenWidthDp = code.newLocal(TypeId.INT)
        val screenHeightDp = code.newLocal(TypeId.INT)
        val smallestDp = code.newLocal(TypeId.INT)
        code.cast(request, input)
        code.loadConstant(index, 0)
        code.aget(raw, request, index)
        code.cast(task, raw)
        code.loadConstant(index, 1)
        code.aget(raw, request, index)
        code.cast(bounds, raw)
        code.loadConstant(index, 2)
        code.aget(raw, request, index)
        code.cast(override, raw)
        code.invokeVirtual(
            taskType.getMethod(configType, "getRequestedOverrideConfiguration"),
            prior,
            task
        )
        code.newInstance(config, configType.getConstructor(configType), prior)
        code.invokeVirtual(
            configType.getMethod(TypeId.INT, "updateFrom", configType),
            changes,
            config,
            override
        )
        code.iget(configType.getField(TypeId.INT, "screenWidthDp"), screenWidthDp, config)
        code.iget(configType.getField(TypeId.INT, "screenHeightDp"), screenHeightDp, config)
        code.iget(configType.getField(TypeId.INT, "smallestScreenWidthDp"), smallestDp, config)
        code.iput(configType.getField(TypeId.INT, "compatScreenWidthDp"), config, screenWidthDp)
        code.iput(configType.getField(TypeId.INT, "compatScreenHeightDp"), config, screenHeightDp)
        code.iput(configType.getField(TypeId.INT, "compatSmallestScreenWidthDp"), config, smallestDp)
        code.iget(configType.getField(windowType, "windowConfiguration"), window, config)
        code.invokeVirtual(
            windowType.getMethod(TypeId.VOID, "setBounds", rectType),
            null,
            window,
            bounds
        )
        code.invokeVirtual(
            windowType.getMethod(TypeId.VOID, "setAppBounds", rectType),
            null,
            window,
            bounds
        )
        code.invokeVirtual(
            windowType.getMethod(TypeId.VOID, "setMaxBounds", rectType),
            null,
            window,
            bounds
        )
        code.invokeVirtual(
            taskType.getMethod(
                TypeId.VOID,
                "onRequestedOverrideConfigurationChanged",
                configType
            ), null, task, config
        )
        code.loadConstant(raw, null)
        code.returnValue(raw)
        return dex.generate()
    }
}
