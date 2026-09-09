package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEmbedSessionStateMachineTest {
    @Test
    fun activeLifecycleEmitsEffectsInOrderAndSupportsVisibilityAndFocus() {
        val effects = RecordingEffects()
        val resource = RecordingHostResource()
        val session = AppEmbedSessionStateMachine(sessionId = 41L, effects = effects)

        session.beginCreate()
        val generation = session.generation
        session.onHostCreated(generation, resource)
        session.setVisible(false)
        session.onSurfaceDelivered(generation)
        assertEquals(AppEmbedSessionState.SURFACE_DELIVERED, session.state)
        assertFalse(effects.events.contains("requestTaskStart:41:1"))
        session.onNativeInitialized(generation)
        session.onTaskCreated(generation, taskId = 97)
        session.requestFocus()

        assertEquals(AppEmbedSessionState.ACTIVE, session.state)
        assertEquals(1L, generation)
        assertEquals(
            listOf(
                "requestHostCreation:41:1",
                "deliverSurface:41:1",
                "setVisible:41:1:false",
                "requestTaskStart:41:1",
                "onTaskActive:41:1:97",
                "requestFocus:41:1",
            ),
            effects.events,
        )
        assertTrue(resource.releaseReasons.isEmpty())
    }

    @Test
    fun invalidConstructionAndTransitionsFailFast() {
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedSessionStateMachine(sessionId = 0L, effects = RecordingEffects())
        }

        val session = AppEmbedSessionStateMachine(sessionId = 1L, effects = RecordingEffects())
        assertThrows(IllegalStateException::class.java) { session.setVisible(true) }
        assertThrows(IllegalStateException::class.java) { session.requestFocus() }

        session.beginCreate()
        assertThrows(IllegalStateException::class.java) { session.beginCreate() }
        assertThrows(IllegalArgumentException::class.java) {
            session.onTaskCreated(session.generation, taskId = 0)
        }
    }

    @Test
    fun callbacksWithWrongGenerationDisposeOwnedResourcesWithoutAdvancingSession() {
        val effects = RecordingEffects()
        val staleResource = RecordingHostResource()
        val session = AppEmbedSessionStateMachine(sessionId = 5L, effects = effects)
        session.beginCreate()

        session.onHostCreated(session.generation + 1L, staleResource)
        session.onSurfaceDelivered(session.generation + 1L)
        session.onTaskCreated(session.generation + 1L, taskId = 12)

        assertEquals(AppEmbedSessionState.CREATING_HOST, session.state)
        assertEquals(listOf(AppEmbedReleaseReason.LATE_HOST_CALLBACK), staleResource.releaseReasons)
        assertEquals(
            listOf(
                "requestHostCreation:5:1",
                "removeLateTask:5:2:12",
            ),
            effects.events,
        )
    }

    @Test
    fun nativeInitializationBeforeSurfaceDeliveryStillStartsExactlyOnce() {
        val effects = RecordingEffects()
        val session = AppEmbedSessionStateMachine(sessionId = 6L, effects = effects)
        session.beginCreate()
        val generation = session.generation

        session.onNativeInitialized(generation)
        session.onHostCreated(generation, RecordingHostResource())
        assertFalse(effects.events.any { it.startsWith("requestTaskStart:") })

        session.onSurfaceDelivered(generation)
        session.onNativeInitialized(generation)

        assertEquals(AppEmbedSessionState.STARTING_TASK, session.state)
        assertEquals(1, effects.events.count { it == "requestTaskStart:6:1" })
    }

    @Test
    fun surfaceDeliveryBeforeNativeInitializationStillStartsExactlyOnce() {
        val effects = RecordingEffects()
        val session = AppEmbedSessionStateMachine(sessionId = 61L, effects = effects)
        session.beginCreate()
        val generation = session.generation

        session.onHostCreated(generation, RecordingHostResource())
        session.onSurfaceDelivered(generation)
        session.onSurfaceDelivered(generation)
        assertFalse(effects.events.any { it.startsWith("requestTaskStart:") })

        session.onNativeInitialized(generation)
        session.onNativeInitialized(generation)

        assertEquals(AppEmbedSessionState.STARTING_TASK, session.state)
        assertEquals(1, effects.events.count { it == "requestTaskStart:61:1" })
    }

    @Test
    fun releaseBeforeHostCallbackKeepsTerminalStateAndReleasesLateHost() {
        val effects = RecordingEffects()
        val lateResource = RecordingHostResource()
        val session = AppEmbedSessionStateMachine(sessionId = 7L, effects = effects)
        session.beginCreate()
        val generation = session.generation

        assertTrue(session.release(AppEmbedReleaseReason.CLIENT_RELEASE))
        session.onHostCreated(generation, lateResource)

        assertEquals(AppEmbedSessionState.RELEASED, session.state)
        assertEquals(listOf(AppEmbedReleaseReason.LATE_HOST_CALLBACK), lateResource.releaseReasons)
        assertEquals(1, effects.terminals.size)
        assertEquals(AppEmbedReleaseReason.CLIENT_RELEASE, effects.terminals.single().reason)
        assertFalse(session.release(AppEmbedReleaseReason.OWNER_DIED))
        assertEquals(1, effects.terminals.size)
    }

    @Test
    fun timeoutReleasesHostAndRemovesTaskArrivingAfterTerminalTransition() {
        val effects = RecordingEffects()
        val resource = RecordingHostResource()
        val session = AppEmbedSessionStateMachine(sessionId = 9L, effects = effects)
        session.beginCreate()
        val generation = session.generation
        session.onHostCreated(generation, resource)
        session.onSurfaceDelivered(generation)
        session.onNativeInitialized(generation)

        assertTrue(session.onTimeout())
        session.onTaskCreated(generation, taskId = 31)

        assertEquals(AppEmbedSessionState.FAILED, session.state)
        assertEquals(listOf(AppEmbedReleaseReason.TIMEOUT), resource.releaseReasons)
        assertTrue(effects.events.contains("removeLateTask:9:1:31"))
        val terminal = effects.terminals.single()
        assertEquals(AppEmbedReleaseReason.TIMEOUT, terminal.reason)
        assertEquals(AppEmbedErrorCode.TIMEOUT, terminal.error?.code)
        assertEquals("App embed session timed out", terminal.error?.message)
        assertNull(terminal.releaseFailure)
        assertFalse(session.onTimeout())
    }

    @Test
    fun clientReleaseWhileStartingRemovesLateTaskWithoutSecondTerminal() {
        val effects = RecordingEffects()
        val resource = RecordingHostResource()
        val session = AppEmbedSessionStateMachine(sessionId = 62L, effects = effects)
        session.beginCreate()
        val generation = session.generation
        session.onHostCreated(generation, resource)
        session.onSurfaceDelivered(generation)
        session.onNativeInitialized(generation)

        assertTrue(session.release(AppEmbedReleaseReason.CLIENT_RELEASE))
        session.onTaskCreated(generation, taskId = 620)

        assertEquals(AppEmbedSessionState.RELEASED, session.state)
        assertEquals(listOf(AppEmbedReleaseReason.CLIENT_RELEASE), resource.releaseReasons)
        assertEquals(1, effects.terminals.size)
        assertTrue(effects.events.contains("removeLateTask:62:1:620"))
    }

    @Test
    fun duplicateCallbackForActiveTaskDoesNotRemoveTheLiveTask() {
        val effects = RecordingEffects()
        val session = AppEmbedSessionStateMachine(sessionId = 10L, effects = effects)
        session.beginCreate()
        val generation = session.generation
        session.onHostCreated(generation, RecordingHostResource())
        session.onNativeInitialized(generation)
        session.onSurfaceDelivered(generation)
        session.onTaskCreated(generation, taskId = 44)
        val eventsAfterActivation = effects.events.toList()

        session.onTaskCreated(generation, taskId = 44)

        assertEquals(AppEmbedSessionState.ACTIVE, session.state)
        assertEquals(eventsAfterActivation, effects.events)
    }

    @Test
    fun ownerAndRemoteDeathReasonsReleaseExactlyOnce() {
        val deathReasons = listOf(
            AppEmbedReleaseReason.OWNER_DIED,
            AppEmbedReleaseReason.CALLBACK_DIED,
            AppEmbedReleaseReason.CLIENT_CAPABILITY_DIED,
        )

        deathReasons.forEachIndexed { index, reason ->
            val effects = RecordingEffects()
            val resource = RecordingHostResource()
            val session = AppEmbedSessionStateMachine(sessionId = index + 1L, effects = effects)
            session.beginCreate()
            session.onHostCreated(session.generation, resource)

            assertTrue(session.release(reason))
            assertFalse(session.release(reason))
            assertEquals(AppEmbedSessionState.RELEASED, session.state)
            assertEquals(listOf(reason), resource.releaseReasons)
            assertEquals(reason, effects.terminals.single().reason)
        }
    }

    @Test
    fun hostReleaseFailureIsReportedAfterSessionReachesTerminalState() {
        val expectedFailure = IllegalStateException("host release failed")
        val effects = RecordingEffects()
        val resource = RecordingHostResource(releaseFailure = expectedFailure)
        val session = AppEmbedSessionStateMachine(sessionId = 13L, effects = effects)
        session.beginCreate()
        session.onHostCreated(session.generation, resource)

        assertTrue(session.release(AppEmbedReleaseReason.MODULE_RELOAD))

        assertEquals(AppEmbedSessionState.RELEASED, session.state)
        assertEquals(listOf(AppEmbedReleaseReason.MODULE_RELOAD), resource.releaseReasons)
        assertSame(expectedFailure, effects.terminals.single().releaseFailure)
    }

    @Test
    fun resourceReleaseReentryCannotEmitTwoTerminalTransitions() {
        val effects = RecordingEffects()
        lateinit var session: AppEmbedSessionStateMachine
        var nestedReleaseResult: Boolean? = null
        val resource = AppEmbedHostResource {
            nestedReleaseResult = session.release(AppEmbedReleaseReason.OWNER_DIED)
        }
        session = AppEmbedSessionStateMachine(sessionId = 17L, effects = effects)
        session.beginCreate()
        session.onHostCreated(session.generation, resource)

        assertTrue(session.release(AppEmbedReleaseReason.CLIENT_RELEASE))

        assertEquals(false, nestedReleaseResult)
        assertEquals(AppEmbedSessionState.RELEASED, session.state)
        assertEquals(1, effects.terminals.size)
        assertEquals(AppEmbedReleaseReason.CLIENT_RELEASE, effects.terminals.single().reason)
    }

    @Test
    fun explicitFailureValidatesAndForwardsDiagnosticContext() {
        val effects = RecordingEffects()
        val cause = IllegalArgumentException("bad task")
        val session = AppEmbedSessionStateMachine(sessionId = 21L, effects = effects)

        assertThrows(IllegalArgumentException::class.java) {
            session.fail(code = 0, message = "invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            session.fail(code = AppEmbedErrorCode.INTERNAL_ERROR, message = " ")
        }

        assertTrue(
            session.fail(
                code = AppEmbedErrorCode.TASK_LAUNCH_FAILED,
                message = "Task launch failed",
                cause = cause,
            )
        )

        assertEquals(AppEmbedSessionState.FAILED, session.state)
        val terminal = effects.terminals.single()
        assertEquals(AppEmbedReleaseReason.FAILURE, terminal.reason)
        assertEquals(AppEmbedErrorCode.TASK_LAUNCH_FAILED, terminal.error?.code)
        assertEquals("Task launch failed", terminal.error?.message)
        assertSame(cause, terminal.error?.cause)
    }

    private class RecordingHostResource(
        private val releaseFailure: Throwable? = null,
    ) : AppEmbedHostResource {
        val releaseReasons = mutableListOf<AppEmbedReleaseReason>()

        override fun release(reason: AppEmbedReleaseReason) {
            releaseReasons += reason
            releaseFailure?.let { throw it }
        }
    }

    private data class TerminalEvent(
        val state: AppEmbedSessionState,
        val reason: AppEmbedReleaseReason,
        val error: AppEmbedSessionError?,
        val releaseFailure: Throwable?,
    )

    private class RecordingEffects : AppEmbedSessionEffects {
        val events = mutableListOf<String>()
        val terminals = mutableListOf<TerminalEvent>()

        override fun requestHostCreation(sessionId: Long, generation: Long) {
            events += "requestHostCreation:$sessionId:$generation"
        }

        override fun deliverSurface(
            sessionId: Long,
            generation: Long,
            resource: AppEmbedHostResource,
        ) {
            events += "deliverSurface:$sessionId:$generation"
        }

        override fun requestTaskStart(
            sessionId: Long,
            generation: Long,
            resource: AppEmbedHostResource,
        ) {
            events += "requestTaskStart:$sessionId:$generation"
        }

        override fun onTaskActive(sessionId: Long, generation: Long, taskId: Int) {
            events += "onTaskActive:$sessionId:$generation:$taskId"
        }

        override fun removeLateTask(sessionId: Long, generation: Long, taskId: Int) {
            events += "removeLateTask:$sessionId:$generation:$taskId"
        }

        override fun setVisible(
            sessionId: Long,
            generation: Long,
            resource: AppEmbedHostResource,
            visible: Boolean,
        ) {
            events += "setVisible:$sessionId:$generation:$visible"
        }

        override fun requestFocus(
            sessionId: Long,
            generation: Long,
            resource: AppEmbedHostResource,
        ) {
            events += "requestFocus:$sessionId:$generation"
        }

        override fun onTerminal(
            sessionId: Long,
            generation: Long,
            state: AppEmbedSessionState,
            reason: AppEmbedReleaseReason,
            error: AppEmbedSessionError?,
            releaseFailure: Throwable?,
        ) {
            terminals += TerminalEvent(state, reason, error, releaseFailure)
        }
    }
}
