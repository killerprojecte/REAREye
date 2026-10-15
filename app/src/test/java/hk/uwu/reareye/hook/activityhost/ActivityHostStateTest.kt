package hk.uwu.reareye.hook.activityhost

import org.junit.Assert.*
import org.junit.Test

class ActivityHostStateTest {
    @Test
    fun initialStateAndSessionDpiAreImmutable() {
        val state = ActivityHostState()
        assertEquals(ActivityHostPhase.IDLE, state.snapshot().phase)
        assertNull(state.begin(" "))
        assertNull(state.begin("owner", 119))
        val session = state.begin("owner", 320)!!
        assertEquals(1L, session.generation)
        assertEquals(ActivityHostPhase.STARTING, state.snapshot().phase)
        assertNull(state.begin("owner", 640, explicit = true))
        assertFalse(state.activate(session.copy(dpi = 640), 0))
        assertTrue(state.activate(session, 0))
        assertEquals(320, state.snapshot().session!!.dpi)
        assertEquals(ActivityHostPhase.ACTIVE, state.snapshot().phase)
    }

    @Test
    fun wrongOwnersStaleGenerationsAndOldSequencesAreRejectedWithoutMutation() {
        val state = ActivityHostState()
        val first = state.begin("owner")!!
        assertFalse(state.activate(first.copy(owner = "other"), 100))
        assertFalse(state.activate(first, -1))
        assertTrue(state.activate(first, 0))
        assertFalse(state.replaceSurface(first, 0, Any()))
        assertTrue(state.replaceSurface(first, 2, Any()))
        assertFalse(state.hide(first, 1))
        assertEquals(ActivityHostPhase.ACTIVE, state.snapshot().phase)
        assertTrue(state.release(first, 3))
        val second = state.begin("owner", explicit = true)!!
        assertEquals(2L, second.generation)
        assertFalse(state.replaceSurface(first, 100, Any()))
        assertFalse(state.release(first, 101))
        assertTrue(state.activate(second, 0))
    }

    @Test
    fun surfaceCanBeReplacedAndDetachedWithoutRestart() {
        val state = ActivityHostState()
        val session = state.begin("owner")!!
        val first = Any()
        val second = Any()
        assertTrue(state.replaceSurface(session, 0, first))
        assertSame(first, state.snapshot().surface)
        assertTrue(state.activate(session, 1))
        assertTrue(state.replaceSurface(session, 2, second))
        assertSame(second, state.snapshot().surface)
        assertTrue(state.replaceSurface(session, 3, null))
        assertNull(state.snapshot().surface)
        assertEquals(ActivityHostPhase.ACTIVE, state.snapshot().phase)
    }

    @Test
    fun hiddenFullscreenAndFailureBlockAutomaticRestartAndLateCallbacks() {
        listOf(ActivityHostPhase.HIDDEN, ActivityHostPhase.FULLSCREEN, ActivityHostPhase.FAILED)
            .forEach { terminal ->
                val state = ActivityHostState()
                val session = state.begin("owner")!!
                assertTrue(state.replaceSurface(session, 0, Any()))
                val stopped = when (terminal) {
                    ActivityHostPhase.HIDDEN -> state.hide(session, 1)
                    ActivityHostPhase.FULLSCREEN -> state.fullscreen(session, 1)
                    else -> state.fail(session, 1, "permission denied")
                }
                assertTrue(stopped)
                assertEquals(terminal, state.snapshot().phase)
                assertNull(state.snapshot().surface)
                assertNull(state.begin("owner"))
                assertNull(state.begin("other"))
                assertFalse(state.activate(session, 2))
                assertFalse(state.replaceSurface(session, 3, Any()))
                if (terminal == ActivityHostPhase.FAILED) {
                    assertEquals("permission denied", state.snapshot().failure)
                }
                val replacement = state.begin("owner", 480, explicit = true)!!
                assertEquals(480, replacement.dpi)
                assertNull(state.snapshot().failure)
                assertFalse(state.release(session, 100))
                assertTrue(state.activate(replacement, 0))
            }
    }

    @Test
    fun releaseIsIdempotentButDoesNotAcceptOlderCommands() {
        val state = ActivityHostState()
        val session = state.begin("owner")!!
        assertTrue(state.replaceSurface(session, 0, Any()))
        assertTrue(state.release(session, 1))
        assertTrue(state.release(session, 1))
        assertTrue(state.release(session, 2))
        assertFalse(state.release(session, 1))
        assertEquals(ActivityHostPhase.RELEASED, state.snapshot().phase)
        assertNull(state.snapshot().surface)
        assertNull(state.begin("owner"))
        assertFalse(state.fail(session, 3, "late error"))
        assertNull(state.snapshot().failure)
    }

    @Test
    fun sequenceAtLongMaxDoesNotWrapAndExplicitRetryResetsIt() {
        val state = ActivityHostState()
        val session = state.begin("owner")!!
        assertTrue(state.fail(session, Long.MAX_VALUE, "failed"))
        assertFalse(state.release(session, Long.MIN_VALUE))
        assertFalse(state.release(session, Long.MAX_VALUE))
        val retry = state.begin("owner", explicit = true)!!
        assertTrue(state.activate(retry, 0))
    }
}
