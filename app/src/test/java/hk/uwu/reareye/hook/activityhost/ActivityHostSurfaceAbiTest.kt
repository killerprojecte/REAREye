package hk.uwu.reareye.hook.activityhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class ActivityHostSurfaceAbiTest {
    class Surface
    class Holder
    class Missing

    open class DirectController {
        var attached: Surface? = null
        var destroyed = false
        open fun surfaceCreated(surface: Surface) {
            attached = surface
        }

        fun surfaceDestroyed() {
            destroyed = true; attached = null
        }
    }

    class InheritedController : DirectController()

    class ThrowingController : DirectController() {
        override fun surfaceCreated(surface: Surface) {
            error("native attach failed")
        }
    }

    class PartialController {
        var called = false
        fun surfaceCreated(surface: Surface) {
            called = true
        }
    }

    open class CallbackView {
        private val realHolder = Holder()
        var createdWith: Holder? = null
        var destroyedWith: Holder? = null
        open fun getHolder(): Holder = realHolder
        fun surfaceCreated(holder: Holder) {
            createdWith = holder
        }

        fun surfaceDestroyed(holder: Holder) {
            destroyedWith = holder
        }
    }

    class InheritedView : CallbackView()

    class ThrowingView : CallbackView() {
        override fun getHolder(): Holder = error("holder unavailable")
    }

    class PartialView {
        fun getHolder() = Holder()
        fun surfaceCreated(holder: Holder) = Unit
    }

    private fun resolve(view: Class<*>, controller: Class<*>) =
        ActivityHostSurfaceAbi.resolve(view, controller, Surface::class.java, Holder::class.java)

    @Test
    fun directLifecycleUsesControllerAndExactSurfaceWithoutRequiringHolder() {
        val abi = resolve(Missing::class.java, DirectController::class.java)
        val controller = DirectController()
        val view = Missing()
        val surface = Surface()
        abi.attach(view, controller, surface)
        assertSame(surface, controller.attached)
        abi.detach(view, controller)
        assertTrue(controller.destroyed)
        assertNull(controller.attached)
    }

    @Test
    fun callbackLifecycleUsesViewAndItsRealHolder() {
        val abi = resolve(CallbackView::class.java, Missing::class.java)
        val view = CallbackView()
        val controller = Missing()
        abi.attach(view, controller, Surface())
        assertSame(view.getHolder(), view.createdWith)
        abi.detach(view, controller)
        assertSame(view.getHolder(), view.destroyedWith)
    }

    @Test
    fun completeControllerAbiTakesPriorityOverCallbacks() {
        val abi = resolve(CallbackView::class.java, DirectController::class.java)
        val view = CallbackView()
        val controller = DirectController()
        val surface = Surface()
        abi.attach(view, controller, surface)
        abi.detach(view, controller)
        assertTrue(controller.destroyed)
        assertNull(view.createdWith)
        assertNull(view.destroyedWith)
    }

    @Test
    fun incompleteControllerAbiFallsBackAsPairNotPerMethod() {
        val abi = resolve(CallbackView::class.java, PartialController::class.java)
        val view = CallbackView()
        val controller = PartialController()
        abi.attach(view, controller, Surface())
        abi.detach(view, controller)
        assertFalse(controller.called)
        assertSame(view.getHolder(), view.createdWith)
        assertSame(view.getHolder(), view.destroyedWith)
    }

    @Test
    fun missingDestroyRejectsAbiBeforeAnyLifecycleCall() {
        val error = assertThrows(IllegalStateException::class.java) {
            resolve(PartialView::class.java, PartialController::class.java)
        }
        assertTrue(error.message.orEmpty().contains("surfaceDestroyed"))
    }

    @Test
    fun directInvocationFailureIsNotRetriedOnView() {
        val abi = resolve(CallbackView::class.java, ThrowingController::class.java)
        val view = CallbackView()
        val error = assertThrows(InvocationTargetException::class.java) {
            abi.attach(view, ThrowingController(), Surface())
        }
        assertEquals("native attach failed", error.cause?.message)
        assertNull(view.createdWith)
    }

    @Test
    fun holderFailurePropagatesWithoutCallingCallback() {
        val abi = resolve(ThrowingView::class.java, Missing::class.java)
        val view = ThrowingView()
        val error = assertThrows(InvocationTargetException::class.java) {
            abi.attach(view, Missing(), Surface())
        }
        assertEquals("holder unavailable", error.cause?.message)
        assertNull(view.createdWith)
    }

    @Test
    fun inheritedControllerAndViewMethodsAreResolved() {
        val surface = Surface()
        val controller = InheritedController()
        val view = InheritedView()
        val direct = resolve(InheritedView::class.java, InheritedController::class.java)
        direct.attach(view, controller, surface)
        assertSame(surface, controller.attached)
        direct.detach(view, controller)
        assertTrue(controller.destroyed)
        val callback = resolve(InheritedView::class.java, Missing::class.java)
        callback.attach(view, Missing(), surface)
        callback.detach(view, Missing())
        assertSame(view.getHolder(), view.createdWith)
        assertSame(view.getHolder(), view.destroyedWith)
    }
}
