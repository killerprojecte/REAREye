package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import com.android.dex.Dex
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiTaskViewHostAdapterTest {
    @Test
    fun generatedDexDeclaresAdapterListenerAndRequiredRomMethodDescriptors() {
        val adapterName = "hk.uwu.reareye.test.GeneratedTaskViewAdapter"
        val dex = Dex(SystemUiTaskViewHostAdapter.generateDex(adapterName))
        val methods = dex.methodDescriptors()
        val fields = dex.fieldDescriptors()

        assertTrue("generated dex must not be empty", dex.length > 0)
        assertTrue(dex.typeNames().contains("Lhk/uwu/reareye/test/GeneratedTaskViewAdapter;"))
        assertTrue(
            dex.typeNames().contains("Lhk/uwu/reareye/test/GeneratedTaskViewAdapterListener;")
        )

        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lhk/uwu/reareye/test/GeneratedTaskViewAdapter;",
                    name = "apply",
                    parameters = listOf("Ljava/lang/Object;"),
                    returnType = "Ljava/lang/Object;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lcom/android/wm/shell/dagger/HasWMComponent;",
                    name = "getWMComponent",
                    parameters = emptyList(),
                    returnType = "Lcom/android/wm/shell/dagger/WMComponent;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lcom/android/wm/shell/dagger/WMComponent;",
                    name = "getTaskViewFactory",
                    parameters = emptyList(),
                    returnType = "Ljava/util/Optional;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Ljava/util/Optional;",
                    name = "orElse",
                    parameters = listOf("Ljava/lang/Object;"),
                    returnType = "Ljava/lang/Object;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lcom/android/wm/shell/taskview/TaskView;",
                    name = "getController",
                    parameters = emptyList(),
                    returnType = "Lcom/android/wm/shell/taskview/TaskViewTaskController;",
                )
            )
        )
        assertTrue(
            fields.contains(
                DexField(
                    owner = "Lcom/android/wm/shell/taskview/TaskViewTaskController;",
                    name = "mShellExecutor",
                    type = "Ljava/util/concurrent/Executor;",
                )
            )
        )
        assertTrue(
            fields.contains(
                DexField(
                    owner = "Lcom/android/wm/shell/taskview/TaskViewTaskController;",
                    name = "mTaskViewController",
                    type = "Lcom/android/wm/shell/taskview/TaskViewController;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lcom/android/wm/shell/taskview/TaskViewTransitions;",
                    name = "startRootTask",
                    parameters = listOf(
                        "Lcom/android/wm/shell/taskview/TaskViewTaskController;",
                        "Landroid/app/ActivityManager\$RunningTaskInfo;",
                        "Landroid/view/SurfaceControl;",
                        "Landroid/window/WindowContainerTransaction;",
                    ),
                    returnType = "V",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Landroid/view/SurfaceControl;",
                    name = "<init>",
                    parameters = listOf(
                        "Landroid/view/SurfaceControl;",
                        "Ljava/lang/String;",
                    ),
                    returnType = "V",
                )
            )
        )
        assertTrue(
            fields.contains(
                DexField(
                    owner = "Landroid/app/TaskInfo;",
                    name = "displayId",
                    type = "I",
                )
            )
        )
        assertTrue(
            fields.contains(
                DexField(
                    owner = "Landroid/app/TaskInfo;",
                    name = "token",
                    type = "Landroid/window/WindowContainerToken;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Landroid/window/WindowContainerToken;",
                    name = "asBinder",
                    parameters = emptyList(),
                    returnType = "Landroid/os/IBinder;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Landroid/window/WindowContainerTransaction;",
                    name = "setDensityDpi",
                    parameters = listOf("Landroid/window/WindowContainerToken;", "I"),
                    returnType = "Landroid/window/WindowContainerTransaction;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Landroid/window/WindowContainerTransaction;",
                    name = "setScreenSizeDp",
                    parameters = listOf("Landroid/window/WindowContainerToken;", "I", "I"),
                    returnType = "Landroid/window/WindowContainerTransaction;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Landroid/window/WindowContainerTransaction;",
                    name = "setSmallestScreenWidthDp",
                    parameters = listOf("Landroid/window/WindowContainerToken;", "I"),
                    returnType = "Landroid/window/WindowContainerTransaction;",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lcom/android/wm/shell/taskview/TaskViewFactory;",
                    name = "create",
                    parameters = listOf(
                        "Landroid/content/Context;",
                        "Ljava/util/concurrent/Executor;",
                        "Ljava/util/function/Consumer;",
                    ),
                    returnType = "V",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lcom/android/wm/shell/taskview/TaskView;",
                    name = "startActivity",
                    parameters = listOf(
                        "Landroid/app/PendingIntent;",
                        "Landroid/content/Intent;",
                        "Landroid/app/ActivityOptions;",
                        "Landroid/graphics/Rect;",
                    ),
                    returnType = "V",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lcom/android/wm/shell/taskview/TaskView;",
                    name = "setListener",
                    parameters = listOf(
                        "Ljava/util/concurrent/Executor;",
                        "Lcom/android/wm/shell/taskview/TaskView\$Listener;",
                    ),
                    returnType = "V",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lhk/uwu/reareye/test/GeneratedTaskViewAdapterListener;",
                    name = "onTaskCreated",
                    parameters = listOf("I", "Landroid/content/ComponentName;"),
                    returnType = "V",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lhk/uwu/reareye/test/GeneratedTaskViewAdapterListener;",
                    name = "onTaskInfoChanged",
                    parameters = listOf("Landroid/app/ActivityManager\$RunningTaskInfo;"),
                    returnType = "V",
                )
            )
        )
        assertTrue(
            methods.contains(
                DexMethod(
                    owner = "Lhk/uwu/reareye/test/GeneratedTaskViewAdapterListener;",
                    name = "onTaskVisibilityChanged",
                    parameters = listOf("I", "Z"),
                    returnType = "V",
                )
            )
        )
    }

    @Test
    fun blankGeneratedClassNameFailsFast() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemUiTaskViewHostAdapter.generateDex(" ")
        }
    }

    private fun Dex.methodDescriptors(): Set<DexMethod> {
        val names = strings()
        val types = typeNames()
        val prototypes = protoIds()
        return methodIds().mapTo(LinkedHashSet()) { method ->
            val prototype = prototypes[method.protoIndex]
            val parameterTypes = if (prototype.parametersOffset == 0) {
                emptyList()
            } else {
                readTypeList(prototype.parametersOffset).types.map { typeIndex ->
                    types[typeIndex.toInt() and 0xffff]
                }
            }
            DexMethod(
                owner = types[method.declaringClassIndex],
                name = names[method.nameIndex],
                parameters = parameterTypes,
                returnType = types[prototype.returnTypeIndex],
            )
        }
    }

    private data class DexMethod(
        val owner: String,
        val name: String,
        val parameters: List<String>,
        val returnType: String,
    )

    private fun Dex.fieldDescriptors(): Set<DexField> {
        val names = strings()
        val types = typeNames()
        return fieldIds().mapTo(LinkedHashSet()) { field ->
            DexField(
                owner = types[field.declaringClassIndex],
                name = names[field.nameIndex],
                type = types[field.typeIndex],
            )
        }
    }

    private data class DexField(
        val owner: String,
        val name: String,
        val type: String,
    )
}
