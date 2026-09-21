package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import android.view.SurfaceControl
import com.android.dx.Code
import com.android.dx.Comparison
import com.android.dx.DexMaker
import com.android.dx.Label
import com.android.dx.Local
import com.android.dx.TypeId
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.function.Consumer
import java.util.function.Function

/** Events emitted by the ROM TaskView.Listener adapter. */
internal interface SystemUiTaskViewListener {
    /** Contains adapter decoding or session callback failures before they reach SystemUI's Looper. */
    fun onAdapterFailure(error: Throwable)

    fun onInitialized()

    fun onSurfaceAlreadyCreated()

    fun onReleased()

    fun onTaskCreated(taskId: Int, componentName: ComponentName?)

    fun onTaskInfoChanged(taskInfo: ActivityManager.RunningTaskInfo)

    fun onTaskRemovalStarted(taskId: Int)

    fun onTaskVisibilityChanged(taskId: Int, visible: Boolean)

    fun onBackPressedOnTaskRoot(taskId: Int)
}

/**
 * Typed module facade over a generated host-loader [Function].
 *
 * The generated class directly invokes ROM TaskView methods. Its only cross-loader protocol is a
 * boot-class-loader `Object[]`, and its generated TaskView.Listener forwards event arrays through a
 * boot-class-loader [Consumer]. Reflection is limited to the one generated constructor call.
 */
internal class SystemUiTaskViewHostAdapter private constructor(
    private val dispatcher: Function<Any, Any?>,
) {
    /** Resolves the current ROM-owned factory from SystemUI's public WMComponent graph. */
    fun getFactoryFromApplication(applicationContext: Context): Any =
        dispatch(Operation.GET_APPLICATION_FACTORY, applicationContext)
            ?: error("SystemUI WMComponent has no TaskViewFactory")

    fun createTaskView(
        factory: Any,
        context: Context,
        executor: Executor,
        consumer: Consumer<Any>,
    ) {
        dispatch(Operation.CREATE, factory, context, executor, consumer)
    }

    fun newListener(callback: SystemUiTaskViewListener): Any {
        val eventConsumer = Consumer<Any> { raw ->
            try {
                val event = raw as? Array<*>
                    ?: error("TaskView listener event is not an Object[]")
                when (event.requireInt(0, "event")) {
                    ListenerEvent.INITIALIZED -> callback.onInitialized()
                    ListenerEvent.SURFACE_ALREADY_CREATED -> callback.onSurfaceAlreadyCreated()
                    ListenerEvent.RELEASED -> callback.onReleased()
                    ListenerEvent.TASK_CREATED -> callback.onTaskCreated(
                        event.requireInt(1, "taskId"),
                        event.getOrNull(2) as? ComponentName,
                    )

                    ListenerEvent.TASK_INFO_CHANGED -> callback.onTaskInfoChanged(
                        event.getOrNull(1) as? ActivityManager.RunningTaskInfo
                            ?: error("TaskView event has no RunningTaskInfo"),
                    )

                    ListenerEvent.TASK_REMOVAL_STARTED ->
                        callback.onTaskRemovalStarted(event.requireInt(1, "taskId"))

                    ListenerEvent.TASK_VISIBILITY_CHANGED -> callback.onTaskVisibilityChanged(
                        event.requireInt(1, "taskId"),
                        event.getOrNull(2) as? Boolean
                            ?: error("TaskView event has no visibility"),
                    )

                    ListenerEvent.BACK_PRESSED ->
                        callback.onBackPressedOnTaskRoot(event.requireInt(1, "taskId"))

                    else -> error("Unknown TaskView listener event=${event.getOrNull(0)}")
                }
            } catch (error: Throwable) {
                try {
                    callback.onAdapterFailure(error)
                } catch (reportError: Throwable) {
                    Log.e(
                        "REAREye-AppEmbed",
                        "TaskView callback failure could not be contained",
                        reportError,
                    )
                }
            }
        }
        return dispatch(Operation.NEW_LISTENER, eventConsumer)
            ?: error("Generated TaskView listener creation returned null")
    }

    fun setListener(taskView: Any, executor: Executor, listener: Any) {
        dispatch(Operation.SET_LISTENER, taskView, executor, listener)
    }

    fun startActivity(
        taskView: Any,
        pendingIntent: PendingIntent,
        fillInIntent: Intent,
        options: ActivityOptions,
        bounds: Rect,
    ) {
        dispatch(
            Operation.START_ACTIVITY,
            taskView,
            pendingIntent,
            fillInIntent,
            options,
            bounds,
        )
    }

    fun onLocationChanged(taskView: Any, bounds: Rect) {
        dispatch(Operation.LOCATION_CHANGED, taskView, bounds)
    }

    fun getTaskInfo(taskView: Any): ActivityManager.RunningTaskInfo? =
        dispatch(Operation.GET_TASK_INFO, taskView) as? ActivityManager.RunningTaskInfo

    /** Returns TaskView's native controller through generated host-loader bytecode. */
    fun getController(taskView: Any): Any =
        dispatch(Operation.GET_CONTROLLER, taskView)
            ?: error("TaskView returned a null controller")

    /** Returns the controller's public Shell executor; the caller owns all task-adoption ordering. */
    fun getShellExecutor(controller: Any): Executor =
        dispatch(Operation.GET_SHELL_EXECUTOR, controller) as? Executor
            ?: error("TaskView controller returned a null Shell executor")

    /** Adopts one organizer task through TaskViewTransitions on the caller-selected Shell thread. */
    fun adoptTask(
        controller: Any,
        taskInfo: ActivityManager.RunningTaskInfo,
        taskLeash: SurfaceControl,
    ) {
        dispatch(Operation.ADOPT_TASK, controller, taskInfo, taskLeash)
    }

    /** Retains an independent leash reference through the ROM-public SurfaceControl copy API. */
    fun retainTaskLeash(taskLeash: SurfaceControl): SurfaceControl =
        dispatch(Operation.RETAIN_TASK_LEASH, taskLeash) as? SurfaceControl
            ?: error("TaskView adapter returned no retained task leash")

    /** Reads TaskInfo.displayId directly in generated host-loader bytecode. */
    fun getTaskDisplayId(taskInfo: ActivityManager.RunningTaskInfo): Int =
        dispatch(Operation.GET_TASK_DISPLAY_ID, taskInfo) as? Int
            ?: error("TaskView adapter returned no task displayId")

    /** Returns the stable Binder identity behind TaskInfo.token. */
    fun getTaskTokenBinder(taskInfo: ActivityManager.RunningTaskInfo): IBinder =
        dispatch(Operation.GET_TASK_TOKEN_BINDER, taskInfo) as? IBinder
            ?: error("TaskView adapter returned no task token Binder")

    /** Appends a complete child-task density override to an existing native bounds transaction. */
    fun applyTaskDensity(
        transaction: Any,
        taskInfo: ActivityManager.RunningTaskInfo,
        density: AppEmbedTaskDensity,
    ) {
        dispatch(
            Operation.APPLY_TASK_DENSITY,
            transaction,
            taskInfo,
            density.densityDpi,
            density.screenWidthDp,
            density.screenHeightDp,
            density.smallestScreenWidthDp,
        )
    }

    fun removeTask(taskView: Any) {
        dispatch(Operation.REMOVE_TASK, taskView)
    }

    fun release(taskView: Any) {
        dispatch(Operation.RELEASE, taskView)
    }

    private fun dispatch(operation: Int, vararg arguments: Any): Any? =
        dispatcher.apply(arrayOf(operation, *arguments))

    companion object {
        private const val FACTORY = "Lcom/android/wm/shell/taskview/TaskViewFactory;"
        private const val HAS_WM_COMPONENT = "Lcom/android/wm/shell/dagger/HasWMComponent;"
        private const val WM_COMPONENT = "Lcom/android/wm/shell/dagger/WMComponent;"
        private const val OPTIONAL = "Ljava/util/Optional;"
        private const val TASK_VIEW = "Lcom/android/wm/shell/taskview/TaskView;"
        private const val TASK_VIEW_CONTROLLER =
            "Lcom/android/wm/shell/taskview/TaskViewTaskController;"
        private const val TASK_VIEW_CONTROLLER_INTERFACE =
            "Lcom/android/wm/shell/taskview/TaskViewController;"
        private const val TASK_VIEW_TRANSITIONS =
            "Lcom/android/wm/shell/taskview/TaskViewTransitions;"
        private const val TASK_VIEW_LISTENER =
            "Lcom/android/wm/shell/taskview/TaskView\u0024Listener;"
        private const val OBJECT = "Ljava/lang/Object;"
        private const val OBJECT_ARRAY = "[Ljava/lang/Object;"
        private const val FUNCTION = "Ljava/util/function/Function;"
        private const val CONSUMER = "Ljava/util/function/Consumer;"
        private const val CONTEXT = "Landroid/content/Context;"
        private const val EXECUTOR = "Ljava/util/concurrent/Executor;"
        private const val PENDING_INTENT = "Landroid/app/PendingIntent;"
        private const val INTENT = "Landroid/content/Intent;"
        private const val ACTIVITY_OPTIONS = "Landroid/app/ActivityOptions;"
        private const val RECT = "Landroid/graphics/Rect;"
        private const val SURFACE_CONTROL = "Landroid/view/SurfaceControl;"
        private const val COMPONENT_NAME = "Landroid/content/ComponentName;"
        private const val TASK_INFO = "Landroid/app/ActivityManager\u0024RunningTaskInfo;"
        private const val ANDROID_TASK_INFO = "Landroid/app/TaskInfo;"
        private const val WINDOW_CONTAINER_TOKEN = "Landroid/window/WindowContainerToken;"
        private const val WINDOW_CONTAINER_TRANSACTION =
            "Landroid/window/WindowContainerTransaction;"
        private const val IBINDER = "Landroid/os/IBinder;"
        private const val INTEGER = "Ljava/lang/Integer;"
        private const val BOOLEAN = "Ljava/lang/Boolean;"
        private const val ILLEGAL_ARGUMENT = "Ljava/lang/IllegalArgumentException;"
        private const val STRING = "Ljava/lang/String;"

        @Suppress("UNCHECKED_CAST")
        private val VOID: TypeId<Any> = TypeId.VOID as TypeId<Any>

        fun create(
            hostClassLoader: ClassLoader,
            dexDirectory: File,
            generationName: String,
        ): SystemUiTaskViewHostAdapter {
            require(generationName.isNotBlank()) { "generationName must not be blank" }
            val className =
                "hk.uwu.reareye.systemui.RuntimeTaskViewAdapter_$generationName"
            val dex = generateDex(className)
            // Android 17 is the only supported AppEmbed backend target. An in-memory child loader
            // keeps generated code off disk and resolves Shell types from the SystemUI loader.
            val loader = InMemoryDexClassLoader(
                arrayOf(ByteBuffer.wrap(dex)),
                hostClassLoader,
            )

            @Suppress("UNCHECKED_CAST")
            val dispatcher = loader.loadClass(className)
                .getConstructor()
                .newInstance() as? Function<Any, Any?>
                ?: error("Generated TaskView adapter does not implement Function")
            // Resolve now so a malformed ROM fails during hook installation, before any session.
            loader.loadClass("${className}Listener")
            check(dexDirectory.exists() || dexDirectory.mkdirs()) {
                "Unable to prepare AppEmbed diagnostics directory: $dexDirectory"
            }
            return SystemUiTaskViewHostAdapter(dispatcher)
        }

        /** Generates the complete adapter dex without loading ROM classes. */
        internal fun generateDex(className: String): ByteArray {
            require(className.isNotBlank()) { "className must not be blank" }
            val maker = DexMaker()
            val adapterType: TypeId<Any> = TypeId.get(className.asDescriptor())
            val listenerType: TypeId<Any> = TypeId.get("${className}Listener".asDescriptor())
            val types = AdapterTypes()
            declareAdapter(maker, adapterType, listenerType, types)
            declareListener(maker, listenerType, types)
            return maker.generate()
        }

        private fun declareAdapter(
            maker: DexMaker,
            adapterType: TypeId<Any>,
            listenerType: TypeId<Any>,
            types: AdapterTypes,
        ) {
            maker.declare(
                adapterType,
                "SystemUiTaskViewHostAdapter.generated",
                Modifier.PUBLIC or Modifier.FINAL,
                TypeId.OBJECT,
                types.function,
            )
            declareDefaultConstructor(maker, adapterType)

            val apply = adapterType.getMethod(types.obj, "apply", types.obj)
            val code = maker.declare(apply, Modifier.PUBLIC)
            val requestObject = code.getParameter(0, types.obj)

            // DexMaker requires every local before the first instruction.
            val request = code.newLocal(types.objArray)
            val index = code.newLocal(TypeId.INT)
            val operationObject = code.newLocal(types.obj)
            val operationInteger = code.newLocal(types.integer)
            val operation = code.newLocal(TypeId.INT)
            val expectedOperation = code.newLocal(TypeId.INT)
            val arg1 = code.newLocal(types.obj)
            val arg2 = code.newLocal(types.obj)
            val arg3 = code.newLocal(types.obj)
            val arg4 = code.newLocal(types.obj)
            val arg5 = code.newLocal(types.obj)
            val arg6 = code.newLocal(types.obj)
            val factory = code.newLocal(types.factory)
            val hasWmComponent = code.newLocal(types.hasWmComponent)
            val wmComponent = code.newLocal(types.wmComponent)
            val optional = code.newLocal(types.optional)
            val taskView = code.newLocal(types.taskView)
            val taskViewController = code.newLocal(types.taskViewController)
            val taskViewControllerInterface = code.newLocal(types.taskViewControllerInterface)
            val taskViewTransitions = code.newLocal(types.taskViewTransitions)
            val taskViewListener = code.newLocal(types.taskViewListener)
            val generatedListener = code.newLocal(listenerType)
            val context = code.newLocal(types.context)
            val executor = code.newLocal(types.executor)
            val consumer = code.newLocal(types.consumer)
            val pendingIntent = code.newLocal(types.pendingIntent)
            val intent = code.newLocal(types.intent)
            val options = code.newLocal(types.activityOptions)
            val rect = code.newLocal(types.rect)
            val surfaceControl = code.newLocal(types.surfaceControl)
            val retainedSurfaceControl = code.newLocal(types.surfaceControl)
            val taskInfo = code.newLocal(types.taskInfo)
            val windowContainerToken = code.newLocal(types.windowContainerToken)
            val windowContainerTransaction = code.newLocal(types.windowContainerTransaction)
            val transactionResult = code.newLocal(types.windowContainerTransaction)
            val taskTokenBinder = code.newLocal(types.iBinder)
            val displayId = code.newLocal(TypeId.INT)
            val boxedDisplayId = code.newLocal(types.integer)
            val densityDpi = code.newLocal(TypeId.INT)
            val screenWidthDp = code.newLocal(TypeId.INT)
            val screenHeightDp = code.newLocal(TypeId.INT)
            val smallestScreenWidthDp = code.newLocal(TypeId.INT)
            val result = code.newLocal(types.obj)
            val nullResult = code.newLocal(types.obj)
            val message = code.newLocal(types.string)
            val error = code.newLocal(types.illegalArgument)

            val labels = Array(Operation.COUNT) { Label() }

            code.cast(request, requestObject)
            code.loadConstant(index, 0)
            code.aget(operationObject, request, index)
            code.cast(operationInteger, operationObject)
            code.invokeVirtual(types.integerIntValue, operation, operationInteger)
            labels.forEachIndexed { value, label ->
                code.loadConstant(expectedOperation, value)
                code.compare(Comparison.EQ, label, operation, expectedOperation)
            }
            code.loadConstant(message, "Unknown TaskView adapter operation")
            code.newInstance(error, types.illegalArgumentConstructor, message)
            code.throwValue(error)

            code.mark(labels[Operation.GET_APPLICATION_FACTORY])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(hasWmComponent, arg1)
            code.invokeInterface(types.getWmComponent, wmComponent, hasWmComponent)
            code.invokeInterface(types.getTaskViewFactory, optional, wmComponent)
            code.loadConstant(nullResult, null)
            code.invokeVirtual(types.optionalOrElse, result, optional, nullResult)
            code.returnValue(result)

            code.mark(labels[Operation.CREATE])
            loadObjectArg(code, request, index, arg1, 1)
            loadObjectArg(code, request, index, arg2, 2)
            loadObjectArg(code, request, index, arg3, 3)
            loadObjectArg(code, request, index, arg4, 4)
            code.cast(factory, arg1)
            code.cast(context, arg2)
            code.cast(executor, arg3)
            code.cast(consumer, arg4)
            code.invokeInterface(types.createTaskView, null, factory, context, executor, consumer)
            returnNull(code, nullResult)

            code.mark(labels[Operation.NEW_LISTENER])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(consumer, arg1)
            code.newInstance(
                generatedListener,
                listenerType.getConstructor(types.consumer),
                consumer
            )
            code.cast(result, generatedListener)
            code.returnValue(result)

            code.mark(labels[Operation.SET_LISTENER])
            loadObjectArg(code, request, index, arg1, 1)
            loadObjectArg(code, request, index, arg2, 2)
            loadObjectArg(code, request, index, arg3, 3)
            code.cast(taskView, arg1)
            code.cast(executor, arg2)
            code.cast(taskViewListener, arg3)
            code.invokeVirtual(types.setListener, null, taskView, executor, taskViewListener)
            returnNull(code, nullResult)

            code.mark(labels[Operation.START_ACTIVITY])
            loadObjectArg(code, request, index, arg1, 1)
            loadObjectArg(code, request, index, arg2, 2)
            loadObjectArg(code, request, index, arg3, 3)
            loadObjectArg(code, request, index, arg4, 4)
            loadObjectArg(code, request, index, arg5, 5)
            code.cast(taskView, arg1)
            code.cast(pendingIntent, arg2)
            code.cast(intent, arg3)
            code.cast(options, arg4)
            code.cast(rect, arg5)
            code.invokeVirtual(
                types.startActivity,
                null,
                taskView,
                pendingIntent,
                intent,
                options,
                rect,
            )
            returnNull(code, nullResult)

            code.mark(labels[Operation.LOCATION_CHANGED])
            loadObjectArg(code, request, index, arg1, 1)
            loadObjectArg(code, request, index, arg2, 2)
            code.cast(taskView, arg1)
            code.cast(rect, arg2)
            code.invokeVirtual(types.onLocationChanged, null, taskView, rect)
            returnNull(code, nullResult)

            code.mark(labels[Operation.GET_TASK_INFO])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(taskView, arg1)
            code.invokeVirtual(types.getTaskInfo, taskInfo, taskView)
            code.cast(result, taskInfo)
            code.returnValue(result)

            code.mark(labels[Operation.GET_CONTROLLER])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(taskView, arg1)
            code.invokeVirtual(types.getController, taskViewController, taskView)
            code.cast(result, taskViewController)
            code.returnValue(result)

            code.mark(labels[Operation.GET_SHELL_EXECUTOR])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(taskViewController, arg1)
            code.iget(types.shellExecutor, executor, taskViewController)
            code.cast(result, executor)
            code.returnValue(result)

            code.mark(labels[Operation.ADOPT_TASK])
            loadObjectArg(code, request, index, arg1, 1)
            loadObjectArg(code, request, index, arg2, 2)
            loadObjectArg(code, request, index, arg3, 3)
            code.cast(taskViewController, arg1)
            code.cast(taskInfo, arg2)
            code.cast(surfaceControl, arg3)
            code.iget(
                types.taskViewControllerOwner,
                taskViewControllerInterface,
                taskViewController,
            )
            code.cast(taskViewTransitions, taskViewControllerInterface)
            code.loadConstant(windowContainerTransaction, null)
            code.invokeVirtual(
                types.startRootTask,
                null,
                taskViewTransitions,
                taskViewController,
                taskInfo,
                surfaceControl,
                windowContainerTransaction,
            )
            returnNull(code, nullResult)

            code.mark(labels[Operation.RETAIN_TASK_LEASH])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(surfaceControl, arg1)
            code.loadConstant(message, "REAREye.AppEmbed.late-task")
            code.newInstance(
                retainedSurfaceControl,
                types.surfaceControlCopyConstructor,
                surfaceControl,
                message,
            )
            code.cast(result, retainedSurfaceControl)
            code.returnValue(result)

            code.mark(labels[Operation.GET_TASK_DISPLAY_ID])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(taskInfo, arg1)
            code.iget(types.displayId, displayId, taskInfo)
            code.invokeStatic(types.integerValueOf, boxedDisplayId, displayId)
            code.cast(result, boxedDisplayId)
            code.returnValue(result)

            code.mark(labels[Operation.GET_TASK_TOKEN_BINDER])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(taskInfo, arg1)
            code.iget(types.taskToken, windowContainerToken, taskInfo)
            code.invokeVirtual(types.tokenAsBinder, taskTokenBinder, windowContainerToken)
            code.cast(result, taskTokenBinder)
            code.returnValue(result)

            code.mark(labels[Operation.APPLY_TASK_DENSITY])
            // Deliberately keep this operation a no-op: density and logical configuration are
            // injected once before Activity attachment. Rewriting them from TaskView callbacks
            // causes duplicate configuration dispatches and can crash the embedded Activity.
            returnNull(code, nullResult)

            code.mark(labels[Operation.REMOVE_TASK])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(taskView, arg1)
            code.invokeVirtual(types.removeTask, null, taskView)
            returnNull(code, nullResult)

            code.mark(labels[Operation.RELEASE])
            loadObjectArg(code, request, index, arg1, 1)
            code.cast(taskView, arg1)
            code.invokeVirtual(types.release, null, taskView)
            returnNull(code, nullResult)
        }

        private fun declareListener(
            maker: DexMaker,
            listenerType: TypeId<Any>,
            types: AdapterTypes,
        ) {
            maker.declare(
                listenerType,
                "SystemUiTaskViewListener.generated",
                Modifier.PUBLIC or Modifier.FINAL,
                TypeId.OBJECT,
                types.taskViewListener,
            )
            val events = listenerType.getField(types.consumer, "events")
            maker.declare(events, Modifier.PRIVATE or Modifier.FINAL, null)

            val constructor = listenerType.getConstructor(types.consumer)
            val constructorCode = maker.declare(constructor, Modifier.PUBLIC)
            val constructorThis = constructorCode.getThis(listenerType)
            val constructorEvents = constructorCode.getParameter(0, types.consumer)
            constructorCode.invokeDirect(TypeId.OBJECT.getConstructor(), null, constructorThis)
            constructorCode.iput(events, constructorThis, constructorEvents)
            constructorCode.returnVoid()

            val emit = listenerType.getMethod(
                VOID,
                "emit",
                TypeId.INT,
                types.obj,
                types.obj,
            )
            val emitCode = maker.declare(emit, Modifier.PRIVATE)
            val emitThis = emitCode.getThis(listenerType)
            val eventCode = emitCode.getParameter(0, TypeId.INT)
            val value1 = emitCode.getParameter(1, types.obj)
            val value2 = emitCode.getParameter(2, types.obj)
            val eventConsumer = emitCode.newLocal(types.consumer)
            val values = emitCode.newLocal(types.objArray)
            val arrayLength = emitCode.newLocal(TypeId.INT)
            val arrayIndex = emitCode.newLocal(TypeId.INT)
            val boxedEvent = emitCode.newLocal(types.integer)
            emitCode.iget(events, eventConsumer, emitThis)
            emitCode.loadConstant(arrayLength, 3)
            emitCode.newArray(values, arrayLength)
            emitCode.invokeStatic(types.integerValueOf, boxedEvent, eventCode)
            emitCode.loadConstant(arrayIndex, 0)
            emitCode.aput(values, arrayIndex, boxedEvent)
            emitCode.loadConstant(arrayIndex, 1)
            emitCode.aput(values, arrayIndex, value1)
            emitCode.loadConstant(arrayIndex, 2)
            emitCode.aput(values, arrayIndex, value2)
            emitCode.invokeInterface(types.consumerAccept, null, eventConsumer, values)
            emitCode.returnVoid()

            declareNoArgEvent(
                maker,
                listenerType,
                types,
                emit,
                "onInitialized",
                ListenerEvent.INITIALIZED
            )
            declareNoArgEvent(
                maker,
                listenerType,
                types,
                emit,
                "onSurfaceAlreadyCreated",
                ListenerEvent.SURFACE_ALREADY_CREATED,
            )
            declareNoArgEvent(
                maker,
                listenerType,
                types,
                emit,
                "onReleased",
                ListenerEvent.RELEASED
            )
            declareIntObjectEvent(
                maker,
                listenerType,
                types,
                emit,
                "onTaskCreated",
                types.componentName,
                ListenerEvent.TASK_CREATED,
            )
            declareObjectEvent(
                maker,
                listenerType,
                types,
                emit,
                "onTaskInfoChanged",
                types.taskInfo,
                ListenerEvent.TASK_INFO_CHANGED,
            )
            declareIntEvent(
                maker,
                listenerType,
                types,
                emit,
                "onTaskRemovalStarted",
                ListenerEvent.TASK_REMOVAL_STARTED,
            )
            declareIntBooleanEvent(
                maker,
                listenerType,
                types,
                emit,
                "onTaskVisibilityChanged",
                ListenerEvent.TASK_VISIBILITY_CHANGED,
            )
            declareIntEvent(
                maker,
                listenerType,
                types,
                emit,
                "onBackPressedOnTaskRoot",
                ListenerEvent.BACK_PRESSED,
            )
        }

        private fun declareDefaultConstructor(maker: DexMaker, type: TypeId<Any>) {
            val constructor = type.getConstructor()
            val code = maker.declare(constructor, Modifier.PUBLIC)
            val thisLocal = code.getThis(type)
            code.invokeDirect(TypeId.OBJECT.getConstructor(), null, thisLocal)
            code.returnVoid()
        }

        private fun declareNoArgEvent(
            maker: DexMaker,
            listenerType: TypeId<Any>,
            types: AdapterTypes,
            emit: com.android.dx.MethodId<Any, Any>,
            name: String,
            event: Int,
        ) {
            val method = listenerType.getMethod(VOID, name)
            val code = maker.declare(method, Modifier.PUBLIC)
            val self = code.getThis(listenerType)
            val eventLocal = code.newLocal(TypeId.INT)
            val nullValue = code.newLocal(types.obj)
            code.loadConstant(eventLocal, event)
            code.loadConstant(nullValue, null)
            code.invokeDirect(emit, null, self, eventLocal, nullValue, nullValue)
            code.returnVoid()
        }

        private fun declareIntEvent(
            maker: DexMaker,
            listenerType: TypeId<Any>,
            types: AdapterTypes,
            emit: com.android.dx.MethodId<Any, Any>,
            name: String,
            event: Int,
        ) {
            val method = listenerType.getMethod(VOID, name, TypeId.INT)
            val code = maker.declare(method, Modifier.PUBLIC)
            val self = code.getThis(listenerType)
            val value = code.getParameter(0, TypeId.INT)
            val eventLocal = code.newLocal(TypeId.INT)
            val boxedValue = code.newLocal(types.integer)
            val objectValue = code.newLocal(types.obj)
            val nullValue = code.newLocal(types.obj)
            code.loadConstant(eventLocal, event)
            code.invokeStatic(types.integerValueOf, boxedValue, value)
            code.cast(objectValue, boxedValue)
            code.loadConstant(nullValue, null)
            code.invokeDirect(emit, null, self, eventLocal, objectValue, nullValue)
            code.returnVoid()
        }

        private fun declareIntObjectEvent(
            maker: DexMaker,
            listenerType: TypeId<Any>,
            types: AdapterTypes,
            emit: com.android.dx.MethodId<Any, Any>,
            name: String,
            objectParameterType: TypeId<Any>,
            event: Int,
        ) {
            val method = listenerType.getMethod(VOID, name, TypeId.INT, objectParameterType)
            val code = maker.declare(method, Modifier.PUBLIC)
            val self = code.getThis(listenerType)
            val value = code.getParameter(0, TypeId.INT)
            val objectParameter = code.getParameter(1, objectParameterType)
            val eventLocal = code.newLocal(TypeId.INT)
            val boxedValue = code.newLocal(types.integer)
            val objectValue = code.newLocal(types.obj)
            val secondValue = code.newLocal(types.obj)
            code.loadConstant(eventLocal, event)
            code.invokeStatic(types.integerValueOf, boxedValue, value)
            code.cast(objectValue, boxedValue)
            code.cast(secondValue, objectParameter)
            code.invokeDirect(emit, null, self, eventLocal, objectValue, secondValue)
            code.returnVoid()
        }

        private fun declareObjectEvent(
            maker: DexMaker,
            listenerType: TypeId<Any>,
            types: AdapterTypes,
            emit: com.android.dx.MethodId<Any, Any>,
            name: String,
            objectParameterType: TypeId<Any>,
            event: Int,
        ) {
            val method = listenerType.getMethod(VOID, name, objectParameterType)
            val code = maker.declare(method, Modifier.PUBLIC)
            val self = code.getThis(listenerType)
            val objectParameter = code.getParameter(0, objectParameterType)
            val eventLocal = code.newLocal(TypeId.INT)
            val objectValue = code.newLocal(types.obj)
            val nullValue = code.newLocal(types.obj)
            code.loadConstant(eventLocal, event)
            code.cast(objectValue, objectParameter)
            code.loadConstant(nullValue, null)
            code.invokeDirect(emit, null, self, eventLocal, objectValue, nullValue)
            code.returnVoid()
        }

        private fun declareIntBooleanEvent(
            maker: DexMaker,
            listenerType: TypeId<Any>,
            types: AdapterTypes,
            emit: com.android.dx.MethodId<Any, Any>,
            name: String,
            event: Int,
        ) {
            val method = listenerType.getMethod(VOID, name, TypeId.INT, TypeId.BOOLEAN)
            val code = maker.declare(method, Modifier.PUBLIC)
            val self = code.getThis(listenerType)
            val intValue = code.getParameter(0, TypeId.INT)
            val booleanValue = code.getParameter(1, TypeId.BOOLEAN)
            val eventLocal = code.newLocal(TypeId.INT)
            val boxedInt = code.newLocal(types.integer)
            val boxedBoolean = code.newLocal(types.booleanType)
            val firstObject = code.newLocal(types.obj)
            val secondObject = code.newLocal(types.obj)
            code.loadConstant(eventLocal, event)
            code.invokeStatic(types.integerValueOf, boxedInt, intValue)
            code.invokeStatic(types.booleanValueOf, boxedBoolean, booleanValue)
            code.cast(firstObject, boxedInt)
            code.cast(secondObject, boxedBoolean)
            code.invokeDirect(emit, null, self, eventLocal, firstObject, secondObject)
            code.returnVoid()
        }

        private fun loadObjectArg(
            code: Code,
            request: Local<Any>,
            index: Local<Int>,
            target: Local<Any>,
            value: Int,
        ) {
            code.loadConstant(index, value)
            code.aget(target, request, index)
        }

        private fun returnNull(code: Code, nullResult: Local<Any>) {
            code.loadConstant(nullResult, null)
            code.returnValue(nullResult)
        }

        private fun String.asDescriptor(): String = "L${replace('.', '/')};"
    }

    private object Operation {
        const val GET_APPLICATION_FACTORY = 0
        const val CREATE = 1
        const val NEW_LISTENER = 2
        const val SET_LISTENER = 3
        const val START_ACTIVITY = 4
        const val LOCATION_CHANGED = 5
        const val GET_TASK_INFO = 6
        const val GET_CONTROLLER = 7
        const val GET_TASK_DISPLAY_ID = 8
        const val REMOVE_TASK = 9
        const val RELEASE = 10
        const val GET_TASK_TOKEN_BINDER = 11
        const val APPLY_TASK_DENSITY = 12
        const val GET_SHELL_EXECUTOR = 13
        const val ADOPT_TASK = 14
        const val RETAIN_TASK_LEASH = 15
        const val COUNT = 16
    }

    private object ListenerEvent {
        const val INITIALIZED = 0
        const val SURFACE_ALREADY_CREATED = 1
        const val RELEASED = 2
        const val TASK_CREATED = 3
        const val TASK_INFO_CHANGED = 4
        const val TASK_REMOVAL_STARTED = 5
        const val TASK_VISIBILITY_CHANGED = 6
        const val BACK_PRESSED = 7
    }

    private class AdapterTypes {
        val obj: TypeId<Any> = TypeId.get(OBJECT)
        val objArray: TypeId<Any> = TypeId.get(OBJECT_ARRAY)
        val function: TypeId<Any> = TypeId.get(FUNCTION)
        val consumer: TypeId<Any> = TypeId.get(CONSUMER)
        val factory: TypeId<Any> = TypeId.get(FACTORY)
        val hasWmComponent: TypeId<Any> = TypeId.get(HAS_WM_COMPONENT)
        val wmComponent: TypeId<Any> = TypeId.get(WM_COMPONENT)
        val optional: TypeId<Any> = TypeId.get(OPTIONAL)
        val taskView: TypeId<Any> = TypeId.get(TASK_VIEW)
        val taskViewController: TypeId<Any> = TypeId.get(TASK_VIEW_CONTROLLER)
        val taskViewControllerInterface: TypeId<Any> = TypeId.get(TASK_VIEW_CONTROLLER_INTERFACE)
        val taskViewTransitions: TypeId<Any> = TypeId.get(TASK_VIEW_TRANSITIONS)
        val taskViewListener: TypeId<Any> = TypeId.get(TASK_VIEW_LISTENER)
        val context: TypeId<Any> = TypeId.get(CONTEXT)
        val executor: TypeId<Any> = TypeId.get(EXECUTOR)
        val pendingIntent: TypeId<Any> = TypeId.get(PENDING_INTENT)
        val intent: TypeId<Any> = TypeId.get(INTENT)
        val activityOptions: TypeId<Any> = TypeId.get(ACTIVITY_OPTIONS)
        val rect: TypeId<Any> = TypeId.get(RECT)
        val surfaceControl: TypeId<Any> = TypeId.get(SURFACE_CONTROL)
        val componentName: TypeId<Any> = TypeId.get(COMPONENT_NAME)
        val taskInfo: TypeId<Any> = TypeId.get(TASK_INFO)
        val androidTaskInfo: TypeId<Any> = TypeId.get(ANDROID_TASK_INFO)
        val windowContainerToken: TypeId<Any> = TypeId.get(WINDOW_CONTAINER_TOKEN)
        val windowContainerTransaction: TypeId<Any> = TypeId.get(WINDOW_CONTAINER_TRANSACTION)
        val iBinder: TypeId<Any> = TypeId.get(IBINDER)
        val integer: TypeId<Any> = TypeId.get(INTEGER)
        val booleanType: TypeId<Any> = TypeId.get(BOOLEAN)
        val illegalArgument: TypeId<IllegalArgumentException> =
            TypeId.get(IllegalArgumentException::class.java)
        val string: TypeId<String> = TypeId.STRING

        val integerIntValue = integer.getMethod(TypeId.INT, "intValue")
        val integerValueOf = integer.getMethod(integer, "valueOf", TypeId.INT)
        val booleanValueOf = booleanType.getMethod(booleanType, "valueOf", TypeId.BOOLEAN)
        val illegalArgumentConstructor = illegalArgument.getConstructor(string)
        val getWmComponent = hasWmComponent.getMethod(wmComponent, "getWMComponent")
        val getTaskViewFactory = wmComponent.getMethod(optional, "getTaskViewFactory")
        val optionalOrElse = optional.getMethod(obj, "orElse", obj)
        val createTaskView = factory.getMethod(
            VOID,
            "create",
            context,
            executor,
            consumer,
        )
        val setListener = taskView.getMethod(
            VOID,
            "setListener",
            executor,
            taskViewListener,
        )
        val startActivity = taskView.getMethod(
            VOID,
            "startActivity",
            pendingIntent,
            intent,
            activityOptions,
            rect,
        )
        val onLocationChanged = taskView.getMethod(VOID, "onLocationChanged", rect)
        val getTaskInfo = taskView.getMethod(taskInfo, "getTaskInfo")
        val getController = taskView.getMethod(taskViewController, "getController")
        val shellExecutor = taskViewController.getField(executor, "mShellExecutor")
        val taskViewControllerOwner = taskViewController.getField(
            taskViewControllerInterface,
            "mTaskViewController",
        )
        val startRootTask = taskViewTransitions.getMethod(
            VOID,
            "startRootTask",
            taskViewController,
            taskInfo,
            surfaceControl,
            windowContainerTransaction,
        )
        val surfaceControlCopyConstructor = surfaceControl.getConstructor(surfaceControl, string)
        val displayId = androidTaskInfo.getField(TypeId.INT, "displayId")
        val taskToken = androidTaskInfo.getField(windowContainerToken, "token")
        val tokenAsBinder = windowContainerToken.getMethod(iBinder, "asBinder")
        val removeTask = taskView.getMethod(VOID, "removeTask")
        val release = taskView.getMethod(VOID, "release")
        val consumerAccept = consumer.getMethod(VOID, "accept", obj)
    }
}

private fun Array<*>.requireInt(index: Int, label: String): Int =
    getOrNull(index) as? Int ?: error("TaskView event has no $label")
