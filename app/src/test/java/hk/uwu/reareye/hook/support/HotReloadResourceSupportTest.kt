package hk.uwu.reareye.hook.support

import android.app.Application
import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HotReloadResourceSupportTest {

    @Test
    fun lifecycleReplayDoesNotRegisterReceiverTwiceAndQuiesceUsesOriginalContext() {
        val application = RuntimeEnvironment.getApplication() as Application
        val laterAttachContext = ContextWrapper(application)
        val registration = ReloadableReceiverRegistration("test receiver")
        var registerCalls = 0
        var unregisterCalls = 0
        var unregisterContext: Any? = null

        assertTrue(registration.register(application) { registerCalls++ })
        assertTrue(registration.register(laterAttachContext) { registerCalls++ })
        assertTrue(registration.isRegistered())
        assertTrue(registration.isConsistent())
        assertTrue(registration.unregister { context ->
            unregisterCalls++
            unregisterContext = context
        })

        assertTrue(registerCalls == 1)
        assertTrue(unregisterCalls == 1)
        assertSame(application, unregisterContext)
        assertFalse(registration.isRegistered())
        assertTrue(registration.unregister { unregisterCalls++ })
        assertTrue(unregisterCalls == 1)
    }

    @Test
    fun alreadyUnregisteredReceiverIsSuccessfulIdempotentCleanup() {
        val application = RuntimeEnvironment.getApplication() as Application
        val registration = ReloadableReceiverRegistration("test receiver")

        assertTrue(registration.register(application) {})
        assertTrue(registration.unregister { throw IllegalArgumentException("not registered") })
        assertFalse(registration.isRegistered())
        assertTrue(registration.isConsistent())
    }

    @Test
    fun failedUnregisterKeepsRegistrationForRetry() {
        val application = RuntimeEnvironment.getApplication() as Application
        val registration = ReloadableReceiverRegistration("test receiver")
        var attempts = 0

        assertTrue(registration.register(application) {})
        assertFalse(registration.unregister {
            attempts++
            error("temporary failure")
        })
        assertTrue(registration.isRegistered())
        assertTrue(registration.unregister { attempts++ })
        assertTrue(attempts == 2)
        assertFalse(registration.isRegistered())
    }

    @Test
    fun namedSingletonRecoveryPrefersExplicitStaticFieldAndMethod() {
        assertSame(
            StaticFieldManager.INSTANCE,
            resolveNamedStaticInstance(
                StaticFieldManager::class.java,
                fieldNames = listOf("INSTANCE"),
                methodNames = listOf("getInstance"),
            ),
        )
        assertSame(
            StaticMethodManager.singleton,
            resolveNamedStaticInstance(
                StaticMethodManager::class.java,
                fieldNames = listOf("missing"),
                methodNames = listOf("getInstance"),
            ),
        )
    }

    @Test
    fun panelManagerRecoveryUsesOnlyNamedFieldsAcrossHierarchy() {
        val manager = ExpectedManager()
        val panel = DerivedPanel(manager, unrelated = Any())

        assertSame(manager, readNamedInstanceField(panel, listOf("mManager")))
        assertSame(manager, readNamedInstanceField(panel, listOf("missing", "mManager")))
    }

    @Test
    fun managerAdoptionRejectsWrongTypeAndIsStableOnReplay() {
        val manager = ExpectedManager()

        assertFalse(assessInstanceAdoption(null, Any(), ExpectedManager::class.java).accepted)
        val first = assessInstanceAdoption(null, manager, ExpectedManager::class.java)
        assertTrue(first.accepted)
        assertTrue(first.changed)
        val replay = assessInstanceAdoption(manager, manager, ExpectedManager::class.java)
        assertTrue(replay.accepted)
        assertFalse(replay.changed)
    }

    private class StaticFieldManager private constructor() {
        companion object {
            @JvmField
            val INSTANCE = StaticFieldManager()
        }
    }

    private class StaticMethodManager private constructor() {
        companion object {
            val singleton = StaticMethodManager()

            @JvmStatic
            fun getInstance(): StaticMethodManager = singleton
        }
    }

    private class ExpectedManager

    private open class BasePanel(
        @Suppress("unused") private val mManager: ExpectedManager,
    )

    private class DerivedPanel(
        manager: ExpectedManager,
        @Suppress("unused") private val unrelated: Any,
    ) : BasePanel(manager)
}
