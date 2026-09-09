package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppEmbedSessionStateTest {
    @Test
    fun hiddenNativeTaskReturnsInputAndStaleVisibilityCannotTakeItBack() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        state.onServiceConnected()
        val generation = state.surfaceGeneration
        state.onTaskVisibilityChanged(generation, 10L, false)
        state.onTaskCreated(generation, 10L)
        state.onCreateReturned(generation, 10L)
        assertEquals(true, state.taskActive)
        assertEquals(false, state.taskReceivesInput)
        state.onTaskVisibilityChanged(generation - 1, 10L, true)
        state.onTaskVisibilityChanged(generation, 99L, true)
        assertEquals(false, state.taskReceivesInput)
        state.onTaskVisibilityChanged(generation, 10L, true)
        assertEquals(true, state.taskReceivesInput)
        state.onTaskRemovalStarted(generation, 10L)
        assertEquals(false, state.taskReceivesInput)
    }

    @Test
    fun returningToFailedCardCreatesNewGenerationOnlyOnce() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        state.onServiceConnected()
        val generation = state.surfaceGeneration
        state.onCreateReturned(generation, 10L)
        state.onTaskRemovalStarted(generation, 10L)
        assertEquals(
            listOf(AppEmbedSessionState.Action.Create(generation + 1)),
            state.onHostBecameVisible(),
        )
        assertEquals(emptyList<AppEmbedSessionState.Action>(), state.onHostBecameVisible())
        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(10L)),
            state.onTaskCreated(generation, 10L),
        )
    }

    @Test
    fun surfaceAndServiceDriveOneCreateAndAttachSequence() {
        val state = AppEmbedSessionState()

        assertEquals(listOf(AppEmbedSessionState.Action.Connect), state.onSurfaceAvailable())
        assertEquals(AppEmbedSessionState.Phase.WAITING_FOR_SERVICE, state.phase)
        val generation = state.surfaceGeneration
        assertEquals(
            listOf(AppEmbedSessionState.Action.Create(generation)),
            state.onServiceConnected(),
        )
        assertEquals(
            emptyList<AppEmbedSessionState.Action>(),
            state.onCreateReturned(generation, 51L)
        )
        assertEquals(AppEmbedSessionState.Phase.CREATING, state.phase)
        assertEquals(51L, state.sessionId)
        assertEquals(
            listOf(AppEmbedSessionState.Action.AttachSurfacePackage(51L)),
            state.onSurfaceReady(generation, 51L),
        )
        assertEquals(AppEmbedSessionState.Phase.ATTACHED, state.phase)
        assertEquals(false, state.taskActive)
        assertEquals(
            listOf(AppEmbedSessionState.Action.TaskBecameActive(51L)),
            state.onTaskCreated(generation, 51L),
        )
        assertEquals(true, state.taskActive)
    }

    @Test
    fun repeatedServiceCallbackDoesNotIssueDuplicateCreate() {
        val state = AppEmbedSessionState()

        assertEquals(listOf(AppEmbedSessionState.Action.Connect), state.onSurfaceAvailable())
        val generation = state.surfaceGeneration
        assertEquals(
            listOf(AppEmbedSessionState.Action.Create(generation)),
            state.onServiceConnected(),
        )
        assertEquals(emptyList<AppEmbedSessionState.Action>(), state.onServiceConnected())
    }

    @Test
    fun repeatedSurfaceCallbackStartsNewGenerationAndReleasesPendingCreateResult() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        val oldGeneration = state.surfaceGeneration
        state.onServiceConnected()

        assertEquals(
            listOf(AppEmbedSessionState.Action.Create(oldGeneration + 1L)),
            state.onSurfaceAvailable(),
        )
        val replacementGeneration = state.surfaceGeneration
        assertEquals(oldGeneration + 1L, replacementGeneration)
        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(61L)),
            state.onCreateReturned(oldGeneration, createdId = 61L),
        )
    }

    @Test
    fun surfaceDestroyedInvalidatesCreateAndLateReadyCallbacks() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        val generation = state.surfaceGeneration
        state.onServiceConnected()

        assertEquals(
            listOf(AppEmbedSessionState.Action.ClearLocalSurface),
            state.onSurfaceDestroyed(),
        )
        assertEquals(AppEmbedSessionState.Phase.DETACHED, state.phase)
        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(71L)),
            state.onCreateReturned(generation, createdId = 71L),
        )
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.DiscardSurfacePackage,
                AppEmbedSessionState.Action.ReleaseRemote(71L),
            ),
            state.onSurfaceReady(generation, 71L),
        )
    }

    @Test
    fun remoteDeathInvalidatesOutstandingCreateBeforeReconnect() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        val deadGeneration = state.surfaceGeneration
        state.onServiceConnected()

        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.Connect,
            ),
            state.onRemoteDied(),
        )
        assertEquals(AppEmbedSessionState.Phase.WAITING_FOR_SERVICE, state.phase)
        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(81L)),
            state.onCreateReturned(deadGeneration, createdId = 81L),
        )
    }

    @Test
    fun remoteDeathClearsAttachedSessionAndReconnectsForAvailableSurface() {
        val state = attachedState(sessionId = 91L)

        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.Connect,
            ),
            state.onRemoteDied(),
        )

        assertNull(state.sessionId)
        assertEquals(AppEmbedSessionState.Phase.WAITING_FOR_SERVICE, state.phase)
    }

    @Test
    fun staleCreateAndSurfaceReadyCallbacksReleaseTheirRemoteSessions() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        val firstGeneration = state.surfaceGeneration
        state.onServiceConnected()
        state.onSurfaceDestroyed()
        state.onSurfaceAvailable()

        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(101L)),
            state.onCreateReturned(firstGeneration, createdId = 101L),
        )
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.DiscardSurfacePackage,
                AppEmbedSessionState.Action.ReleaseRemote(102L),
            ),
            state.onSurfaceReady(firstGeneration, 102L),
        )
        assertNull(state.sessionId)
    }

    @Test
    fun earlySurfaceReadyAttachesOnlyAfterMatchingCreateReturns() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        val generation = state.surfaceGeneration
        state.onServiceConnected()

        assertEquals(
            emptyList<AppEmbedSessionState.Action>(),
            state.onSurfaceReady(generation, readyId = 103L),
        )
        assertEquals(
            listOf(AppEmbedSessionState.Action.AttachSurfacePackage(103L)),
            state.onCreateReturned(generation, createdId = 103L),
        )
        assertEquals(AppEmbedSessionState.Phase.ATTACHED, state.phase)
    }

    @Test
    fun duplicateSurfaceReadyDiscardsCallbackCopyWithoutReleasingActiveSession() {
        val state = attachedState(sessionId = 104L)
        val generation = state.surfaceGeneration

        assertEquals(
            listOf(AppEmbedSessionState.Action.DiscardSurfacePackage),
            state.onSurfaceReady(generation, readyId = 104L),
        )
        assertEquals(AppEmbedSessionState.Phase.ATTACHED, state.phase)
        assertEquals(104L, state.sessionId)
    }

    @Test
    fun matchingRemoteErrorFailsAndCleansBothSidesWhileLateErrorKeepsCurrentSession() {
        val state = attachedState(sessionId = 111L)
        val generation = state.surfaceGeneration

        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(222L)),
            state.onRemoteError(generation, 222L),
        )
        assertEquals(AppEmbedSessionState.Phase.ATTACHED, state.phase)
        assertEquals(111L, state.sessionId)

        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.ReleaseRemote(111L),
            ),
            state.onRemoteError(generation, 111L),
        )
        assertEquals(AppEmbedSessionState.Phase.FAILED, state.phase)
        assertNull(state.sessionId)
    }

    @Test
    fun errorArrivingBeforeCreateReturnPreventsLaterAttachment() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        val generation = state.surfaceGeneration
        state.onServiceConnected()

        assertEquals(
            emptyList<AppEmbedSessionState.Action>(),
            state.onRemoteError(generation, failedId = 112L),
        )
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.ReleaseRemote(112L),
            ),
            state.onCreateReturned(generation, createdId = 112L),
        )
        assertEquals(AppEmbedSessionState.Phase.FAILED, state.phase)
        assertNull(state.sessionId)
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.DiscardSurfacePackage,
                AppEmbedSessionState.Action.ReleaseRemote(112L),
            ),
            state.onSurfaceReady(generation, readyId = 112L),
        )
    }

    @Test
    fun earlySurfaceAndTaskCallbacksBecomeActiveOnlyAfterMatchingCreateReturns() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        val generation = state.surfaceGeneration
        state.onServiceConnected()

        assertEquals(
            emptyList<AppEmbedSessionState.Action>(),
            state.onSurfaceReady(generation, readyId = 113L),
        )
        assertEquals(
            emptyList<AppEmbedSessionState.Action>(),
            state.onTaskCreated(generation, createdId = 113L),
        )
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.AttachSurfacePackage(113L),
                AppEmbedSessionState.Action.TaskBecameActive(113L),
            ),
            state.onCreateReturned(generation, createdId = 113L),
        )
        assertEquals(AppEmbedSessionState.Phase.ATTACHED, state.phase)
        assertEquals(true, state.taskActive)
    }

    @Test
    fun earlyTaskRemovalOverridesEarlyTaskCreatedAndRejectsCreateResult() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        val generation = state.surfaceGeneration
        state.onServiceConnected()
        state.onTaskCreated(generation, createdId = 114L)

        assertEquals(
            emptyList<AppEmbedSessionState.Action>(),
            state.onTaskRemovalStarted(generation, removedId = 114L),
        )
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.ReleaseRemote(114L),
            ),
            state.onCreateReturned(generation, createdId = 114L),
        )
        assertEquals(AppEmbedSessionState.Phase.FAILED, state.phase)
        assertEquals(false, state.taskActive)
        assertNull(state.sessionId)
    }

    @Test
    fun taskRemovalClearsAttachedSessionAndLateTaskCallbacksReleaseRemote() {
        val state = attachedState(sessionId = 115L)
        val generation = state.surfaceGeneration

        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.ReleaseRemote(115L),
            ),
            state.onTaskRemovalStarted(generation, removedId = 115L),
        )
        assertEquals(AppEmbedSessionState.Phase.FAILED, state.phase)
        assertEquals(false, state.taskActive)
        assertNull(state.sessionId)
        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(115L)),
            state.onTaskCreated(generation, createdId = 115L),
        )
    }

    @Test
    fun connectionAndCreateFailuresTerminateOnlyTheCurrentGeneration() {
        val connectionState = AppEmbedSessionState()
        connectionState.onSurfaceAvailable()
        assertEquals(
            listOf(AppEmbedSessionState.Action.ClearLocalSurface),
            connectionState.onConnectionFailed(),
        )
        assertEquals(AppEmbedSessionState.Phase.FAILED, connectionState.phase)

        val createState = AppEmbedSessionState()
        createState.onSurfaceAvailable()
        val staleGeneration = createState.surfaceGeneration
        createState.onServiceConnected()
        createState.onSurfaceDestroyed()
        createState.onSurfaceAvailable()
        assertEquals(
            emptyList<AppEmbedSessionState.Action>(),
            createState.onCreateFailed(staleGeneration),
        )
        assertEquals(
            listOf(AppEmbedSessionState.Action.ClearLocalSurface),
            createState.onCreateFailed(createState.surfaceGeneration),
        )
        assertEquals(AppEmbedSessionState.Phase.FAILED, createState.phase)
    }

    @Test
    fun surfaceDestroyedAndCloseReleaseOwnedSessionAndCloseIsIdempotent() {
        val state = attachedState(sessionId = 121L)

        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.ReleaseRemote(121L),
            ),
            state.onSurfaceDestroyed(),
        )
        assertNull(state.sessionId)

        state.onSurfaceAvailable()
        state.onServiceConnected()
        state.onCreateReturned(state.surfaceGeneration, createdId = 122L)
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.ReleaseRemote(122L),
            ),
            state.close(),
        )
        assertEquals(AppEmbedSessionState.Phase.CLOSED, state.phase)
        assertEquals(emptyList<AppEmbedSessionState.Action>(), state.close())
        assertThrows(IllegalStateException::class.java) { state.onSurfaceAvailable() }
        assertThrows(IllegalStateException::class.java) { state.onServiceConnected() }
    }

    @Test
    fun invalidCreatedSessionIdFailsFast() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()

        assertThrows(IllegalArgumentException::class.java) {
            state.onCreateReturned(state.surfaceGeneration, createdId = 0L)
        }
    }

    @Test
    fun retainedSurfaceStartsFreshSessionWhenCardReturns() {
        val state = attachedState(201L)
        val oldGeneration = state.surfaceGeneration
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.ClearLocalSurface,
                AppEmbedSessionState.Action.ReleaseRemote(201L)
            ),
            state.onHostHidden(),
        )
        assertEquals(emptyList<AppEmbedSessionState.Action>(), state.onHostHidden())
        assertEquals(AppEmbedSessionState.Phase.DETACHED, state.phase)
        assertEquals(
            listOf(AppEmbedSessionState.Action.Create(state.surfaceGeneration + 1)),
            state.onSurfaceAvailable(),
        )
        val newGeneration = state.surfaceGeneration
        state.onCreateReturned(newGeneration, 202L)
        state.onSurfaceReady(newGeneration, 202L)
        state.onTaskCreated(newGeneration, 202L)
        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(201L)),
            state.onTaskRemovalStarted(oldGeneration, 201L),
        )
        assertEquals(202L, state.sessionId)
        assertEquals(AppEmbedSessionState.Phase.ATTACHED, state.phase)
        assertEquals(true, state.taskActive)
        assertEquals(emptyList<AppEmbedSessionState.Action>(), state.onHostBecameVisible())
    }

    @Test
    fun hidingDuringCreateReleasesLateResultWithoutAffectingReturnedCard() {
        val state = AppEmbedSessionState()
        state.onSurfaceAvailable()
        state.onServiceConnected()
        val oldGeneration = state.surfaceGeneration
        state.onHostHidden()
        state.onSurfaceAvailable()
        val newGeneration = state.surfaceGeneration
        assertEquals(
            listOf(AppEmbedSessionState.Action.ReleaseRemote(203L)),
            state.onCreateReturned(oldGeneration, 203L),
        )
        assertEquals(
            listOf(
                AppEmbedSessionState.Action.DiscardSurfacePackage,
                AppEmbedSessionState.Action.ReleaseRemote(203L)
            ),
            state.onSurfaceReady(oldGeneration, 203L),
        )
        assertEquals(emptyList<AppEmbedSessionState.Action>(), state.onCreateFailed(oldGeneration))
        state.onCreateReturned(newGeneration, 204L)
        state.onSurfaceReady(newGeneration, 204L)
        assertEquals(204L, state.sessionId)
        assertEquals(AppEmbedSessionState.Phase.ATTACHED, state.phase)
    }

    @Test
    fun removedTaskIsRecreatedAfterPauseEvenWhenSurfaceWasRetained() {
        val state = attachedState(205L)
        state.onTaskRemovalStarted(state.surfaceGeneration, 205L)
        state.onHostHidden()
        assertEquals(
            listOf(AppEmbedSessionState.Action.Create(state.surfaceGeneration + 1)),
            state.onSurfaceAvailable(),
        )
        state.onCreateReturned(state.surfaceGeneration, 206L)
        state.onSurfaceReady(state.surfaceGeneration, 206L)
        assertEquals(206L, state.sessionId)
        assertEquals(AppEmbedSessionState.Phase.ATTACHED, state.phase)
    }

    private fun attachedState(sessionId: Long): AppEmbedSessionState =
        AppEmbedSessionState().also { state ->
            state.onSurfaceAvailable()
            state.onServiceConnected()
            state.onCreateReturned(state.surfaceGeneration, sessionId)
            state.onSurfaceReady(state.surfaceGeneration, sessionId)
            state.onTaskCreated(state.surfaceGeneration, sessionId)
        }
}
