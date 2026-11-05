package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WidgetSessionTest {
    private class Fake : WidgetPlatform {
        val records = mutableMapOf<String, WidgetBinding>()
        val owned = mutableSetOf<Int>()
        val providers = mutableMapOf<Int, String>()
        val deleted = mutableListOf<Int>()
        var installed = true
        var allowed = true
        var configure = false
        var configurationQueryFailure = false
        var launcher = true
        var nextId = 1
        var starts = 0
        var stops = 0
        var requests = 0
        var binds = 0
        var observations = 0
        val diagnostics = mutableListOf<WidgetState>()
        val failures = mutableMapOf<String, Exception>()
        val reportedFailures = mutableListOf<Pair<String, Throwable>>()
        var loggerFailure: Throwable? = null
        var callback: ((WidgetResult) -> Unit)? = null
        var readFailure = false
        override fun read(key: String): WidgetBinding? {
            failures["read"]?.let { throw it }
            check(!readFailure) { "Temporary service failure" }
            return records[key]
        }

        override fun write(key: String, binding: WidgetBinding?) {
            if (binding == null) records.remove(key) else records[key] = binding
        }

        override fun ownedIds() = owned.toSet().also { observations++ }
        override fun diagnose(
            key: String, state: WidgetState, id: Int?, complete: Boolean, phase: WidgetPhase?,
            owned: Boolean?, providerMatches: Boolean?, providerExists: Boolean?
        ) {
            diagnostics.add(state)
        }

        override fun reportFailure(operation: String, throwable: Throwable) {
            reportedFailures.add(operation to throwable)
            loggerFailure?.let { throw it }
        }

        override fun providerOf(id: Int) = providers[id]
        override fun providerExists(provider: String) = installed
        override fun needsConfiguration(provider: String): Boolean {
            failures["configuration"]?.let { throw it }
            check(!configurationQueryFailure) { "Widget provider metadata unavailable" }
            return configure
        }

        override fun allocate() = nextId++.also { owned.add(it) }
        override fun delete(id: Int) {
            deleted.add(id); owned.remove(id); providers.remove(id)
        }

        override fun bind(id: Int, provider: String): Boolean {
            failures["bind"]?.let { throw it }
            binds++; if (allowed) providers[id] = provider; return allowed
        }

        override fun request(
            kind: WidgetRequest,
            id: Int,
            provider: String,
            result: (WidgetResult) -> Unit
        ): (() -> Unit)? {
            if (!launcher) return null
            requests++
            callback = result
            return {
                failures["cancel"]?.let { throw it }
                /* retain callback deliberately, to simulate a late OS result */
            }
        }

        override fun startListening() {
            failures["start"]?.let { throw it }
            starts++
        }

        override fun stopListening() {
            failures["stop"]?.let { throw it }
            stops++
        }

        fun saved(
            id: Int,
            provider: String = "pkg/Provider",
            complete: Boolean = true,
            owned: Boolean = true
        ) {
            records["key"] = WidgetBinding(id, provider, complete)
            providers[id] = provider
            if (owned) this.owned.add(id)
            nextId = id + 1
        }
    }

    private fun WidgetSessions.session(key: String = "key", provider: String = "pkg/Provider") =
        open(key, provider) {}.also { it.initialize() }

    private fun failure(message: String = "Value hijacked_bound of type java.lang.String cannot be converted to JSONObject") =
        IllegalStateException(message, IllegalArgumentException("original platform cause")).apply {
            addSuppressed(IllegalStateException("original suppressed failure"))
        }

    private fun assertReported(fake: Fake, operation: String, failure: Throwable) {
        val report = fake.reportedFailures.single()
        assertEquals(operation, report.first)
        assertSame(failure, report.second)
        assertSame(failure.cause, report.second.cause)
        assertEquals(failure.message, report.second.message)
        assertSame(failure.suppressed.single(), report.second.suppressed.single())
    }

    @Test
    fun restorationReportsOriginalThrowableAndKeepsSavedBindingAndErrorUiState() {
        val failure = failure()
        val fake = Fake().apply { saved(7); failures["read"] = failure }
        val saved = fake.records.getValue("key")
        var notifications = 0
        val session = WidgetSessions(fake).open("key", "pkg/Provider") { notifications++ }
        session.initialize()
        assertReported(fake, "initialize", failure)
        assertEquals(WidgetState.ERROR, session.state)
        assertEquals(failure.message, session.error)
        assertNull(session.id)
        assertSame(saved, fake.records.getValue("key"))
        assertEquals(1, notifications)
        assertEquals(8, fake.nextId)
        assertEquals(0, fake.binds)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun unreadableBindingNeverAllocatesOnRepeatedClicksAndCanRecoverAfterRepair() {
        val fake = Fake().apply { saved(7); failures["read"] = failure() }
        val session = WidgetSessions(fake).session()
        repeat(3) { session.click() }
        assertEquals(WidgetState.ERROR, session.state)
        assertNull(session.id)
        assertEquals(8, fake.nextId)
        assertEquals(0, fake.binds)
        assertEquals(0, fake.requests)
        assertTrue(fake.deleted.isEmpty())
        fake.failures.clear()
        session.click()
        assertEquals(WidgetState.READY, session.state)
        assertEquals(7, session.id)
        assertEquals(8, fake.nextId)
    }

    @Test
    fun unreadableBindingRefusesExplicitRemovalAndPreservesEveryOwnedId() {
        val failure = failure()
        val fake = Fake().apply { saved(7); failures["read"] = failure; owned.add(8) }
        val sessions = WidgetSessions(fake)
        val session = sessions.session()
        fake.reportedFailures.clear()
        assertFalse(sessions.deleteCardBinding("key"))
        assertEquals(WidgetState.FINISHED, session.state)
        assertReported(fake, "delete-binding", failure)
        assertEquals(setOf(7, 8), fake.owned)
        assertTrue(fake.records.containsKey("key"))
        assertTrue(fake.deleted.isEmpty())
        fake.failures.clear()
        assertTrue(sessions.deleteCardBinding("key"))
        assertEquals(listOf(7), fake.deleted)
        assertEquals(setOf(8), fake.owned)
    }

    @Test
    fun clickFailureReportsStageWithoutDeletingOrRebindingAllocatedId() {
        val failure = failure("bind failed")
        val fake = Fake().apply { failures["bind"] = failure }
        val session = WidgetSessions(fake).session()
        session.click()
        assertReported(fake, "click", failure)
        assertEquals(WidgetState.ERROR, session.state)
        assertEquals(failure.message, session.error)
        assertEquals(1, session.id)
        assertEquals(WidgetBinding(1, "pkg/Provider", false, WidgetPhase.ALLOCATED), fake.records["key"])
        assertEquals(2, fake.nextId)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun activityResultFailureReportsStageAndRetainsBoundIdForRetry() {
        val failure = failure("configuration metadata failed")
        val fake = Fake().apply { allowed = false }
        val session = WidgetSessions(fake).session()
        session.click()
        fake.providers[1] = "pkg/Provider"
        fake.failures["configuration"] = failure
        fake.callback!!(WidgetResult(true, 1))
        assertReported(fake, "activity-result", failure)
        assertEquals(WidgetState.ERROR, session.state)
        assertEquals(failure.message, session.error)
        assertEquals(1, session.id)
        assertEquals(WidgetPhase.BOUND, fake.records.getValue("key").phase)
        assertEquals(1, fake.requests)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun lifecycleFailureReportsStageAndKeepsCompletedBinding() {
        val failure = failure("host listening failed")
        val fake = Fake().apply { saved(7) }
        val session = WidgetSessions(fake).session()
        val saved = fake.records.getValue("key")
        fake.failures["start"] = failure
        session.lifecycle(resumed = true, attached = true)
        assertReported(fake, "lifecycle", failure)
        assertEquals(WidgetState.ERROR, session.state)
        assertEquals(failure.message, session.error)
        assertEquals(7, session.id)
        assertSame(saved, fake.records.getValue("key"))
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun loggerFailureCannotReplaceOriginalErrorOrPreventRetryAndUiNotification() {
        val failure = failure()
        val fake = Fake().apply {
            saved(7)
            failures["read"] = failure
            loggerFailure = AssertionError("logger unavailable")
        }
        val saved = fake.records.getValue("key")
        var notifications = 0
        val session = WidgetSessions(fake).open("key", "pkg/Provider") { notifications++ }
        session.initialize()
        assertReported(fake, "initialize", failure)
        assertEquals(WidgetState.ERROR, session.state)
        assertEquals(failure.message, session.error)
        assertEquals(1, notifications)
        assertSame(saved, fake.records.getValue("key"))

        fake.failures.clear()
        session.click()
        assertEquals(WidgetState.READY, session.state)
        assertNull(session.error)
        assertEquals(7, session.id)
        assertEquals(2, notifications)
        assertEquals(1, fake.reportedFailures.size)
        assertEquals(0, fake.binds)
        assertEquals(8, fake.nextId)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun finishAndCloseFailuresKeepOriginalThrowablesEvenWhenLoggerFails() {
        val finishFailure = failure("cancel failed")
        val closeFailure = failure("stop listening failed")
        val fake = Fake().apply { allowed = false }
        val sessions = WidgetSessions(fake)
        var notifications = 0
        val session = sessions.open("key", "pkg/Provider") { notifications++ }
        session.initialize()
        session.click()
        session.lifecycle(resumed = true, attached = true)
        val saved = fake.records.getValue("key")
        val beforeFinish = notifications
        fake.failures["cancel"] = finishFailure
        fake.failures["stop"] = closeFailure
        fake.loggerFailure = AssertionError("logger unavailable")
        session.finish()

        assertEquals(listOf("finish", "close"), fake.reportedFailures.map { it.first })
        assertSame(finishFailure, fake.reportedFailures[0].second)
        assertSame(finishFailure.cause, fake.reportedFailures[0].second.cause)
        assertSame(closeFailure, fake.reportedFailures[1].second)
        assertSame(closeFailure.cause, fake.reportedFailures[1].second.cause)
        assertEquals(WidgetState.FINISHED, session.state)
        assertEquals(closeFailure.message, session.error)
        assertEquals(beforeFinish + 1, notifications)
        assertSame(saved, fake.records.getValue("key"))
        assertTrue(fake.deleted.isEmpty())
        session.finish()
        assertEquals(2, fake.reportedFailures.size)
        // Closing still releases the key claim even when both cleanup and diagnostic logging fail.
        val replacement = sessions.session()
        assertEquals(WidgetState.NEED_BIND, replacement.state)
        assertEquals(1, replacement.id)
    }

    @Test
    fun explicitCardRemovalFinishesOnlyItsSessionAndDeletesItsCompletedBinding() {
        val fake = Fake()
        val sessions = WidgetSessions(fake)
        val first = sessions.session("card-one").also { it.click() }
        val second = sessions.session("card-two").also { it.click() }
        val firstId = first.id!!
        val secondId = second.id!!
        assertTrue(sessions.deleteCardBinding("card-one"))
        assertEquals(WidgetState.FINISHED, first.state)
        assertEquals(WidgetState.READY, second.state)
        assertEquals(listOf(firstId), fake.deleted)
        assertFalse(fake.records.containsKey("card-one"))
        assertEquals(secondId, fake.records.getValue("card-two").id)
        assertTrue(sessions.deleteCardBinding("card-one"))
        assertEquals(listOf(firstId), fake.deleted)
    }

    @Test
    fun removalDuringAuthorizationRejectsLateActivityResult() {
        val fake = Fake().apply { allowed = false }
        val sessions = WidgetSessions(fake)
        val session = sessions.session("removed").also { it.click() }
        val id = session.id!!
        val callback = fake.callback!!
        assertTrue(sessions.deleteCardBinding("removed"))
        callback(WidgetResult(true, id))
        assertEquals(WidgetState.FINISHED, session.state)
        assertFalse(fake.records.containsKey("removed"))
        assertEquals(listOf(id), fake.deleted)
    }

    @Test
    fun restoresOnlyOwnedCompletedMatchingProvider() {
        val fake = Fake().apply { saved(7) }
        val session = WidgetSessions(fake).session()
        assertEquals(WidgetState.READY, session.state)
        assertEquals(7, session.id)
        session.finish()
        assertTrue(fake.deleted.isEmpty())
        assertTrue(fake.records.getValue("key").complete)
    }

    @Test
    fun missingOwnershipPreservesSavedIdWithoutRestoringOrReallocating() {
        val fake = Fake().apply { saved(8, owned = false) }
        val session = WidgetSessions(fake).session()
        assertEquals(WidgetState.ERROR, session.state)
        assertEquals(8, session.id)
        session.click()
        session.finish()
        assertTrue(fake.deleted.isEmpty())
        assertEquals(8, fake.records.getValue("key").id)
        assertEquals(9, fake.nextId)
        assertEquals(0, fake.requests)
    }

    @Test
    fun selectedProviderMismatchReportsErrorAndPreservesSavedBinding() {
        val fake = Fake().apply { saved(3) }
        val session = WidgetSessions(fake).session(provider = "other/Provider")
        session.click()
        session.finish()
        assertTrue(fake.deleted.isEmpty())
        assertEquals(3, fake.records.getValue("key").id)
        assertEquals("pkg/Provider", fake.records.getValue("key").provider)
        assertEquals(0, fake.binds)
        assertEquals(0, fake.requests)
    }

    @Test
    fun legacyBoundWithoutConfigurationCompletesAfterPositiveValidation() {
        val fake = Fake().apply { saved(2, complete = false) }
        val session = WidgetSessions(fake).session()
        assertEquals(WidgetState.READY, session.state)
        assertEquals(2, session.id)
        assertTrue(fake.records.getValue("key").complete)
        assertEquals(WidgetPhase.COMPLETE, fake.records.getValue("key").phase)
        assertEquals(3, fake.nextId)
        assertEquals(0, fake.binds)
        assertEquals(0, fake.requests)
    }

    @Test
    fun completedBindingSurvivesEachTemporaryQueryAndRecoversSameIdOnResume() {
        for (missing in listOf("owned", "provider", "installed")) {
            val fake = Fake().apply {
                saved(31)
                when (missing) {
                    "owned" -> owned.clear()
                    "provider" -> providers.clear()
                    else -> installed = false
                }
            }
            val sessions = WidgetSessions(fake)
            val first = sessions.session()
            assertEquals(WidgetState.ERROR, first.state)
            first.click()
            first.finish()
            assertEquals(31, fake.records.getValue("key").id)
            fake.owned.add(31)
            fake.providers[31] = "pkg/Provider"
            fake.installed = true
            val restored = sessions.session()
            restored.lifecycle(resumed = true, attached = true)
            assertEquals(WidgetState.READY, restored.state)
            assertEquals(31, restored.id)
            assertEquals(32, fake.nextId)
            assertEquals(0, fake.binds)
            assertEquals(0, fake.requests)
            assertTrue(fake.deleted.isEmpty())
        }
    }

    @Test
    fun legacyBoundNeedsConfigurationSurvivesFinishAndProcessStyleReopen() {
        val fake = Fake().apply { saved(42, complete = false); configure = true }
        val first = WidgetSessions(fake).session()
        assertEquals(WidgetState.NEED_CONFIGURE, first.state)
        assertEquals(WidgetPhase.BOUND, fake.records.getValue("key").phase)
        first.lifecycle(resumed = true, attached = true)
        first.finish()
        val second = WidgetSessions(fake).session()
        assertEquals(WidgetState.NEED_CONFIGURE, second.state)
        assertEquals(42, second.id)
        assertEquals(0, fake.requests)
        second.click()
        assertEquals(WidgetState.WAIT_CONFIGURE, second.state)
        assertEquals(WidgetPhase.CONFIGURING, fake.records.getValue("key").phase)
        assertEquals(0, fake.binds)
        second.finish()
        val third = WidgetSessions(fake).session()
        assertEquals(WidgetState.NEED_CONFIGURE, third.state)
        assertFalse(fake.records.getValue("key").complete)
        assertEquals(43, fake.nextId)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun missingConfigurationMetadataCannotPromoteLegacyPendingBindingToReady() {
        val fake = Fake().apply { saved(42, complete = false); configurationQueryFailure = true }
        val session = WidgetSessions(fake).session()
        assertEquals(WidgetState.ERROR, session.state)
        assertFalse(fake.records.getValue("key").complete)
        session.click()
        session.finish()
        assertEquals(42, fake.records.getValue("key").id)
        assertTrue(fake.deleted.isEmpty())
        assertEquals(0, fake.requests)
    }

    @Test
    fun restoredConfigurationWithNoLauncherSurvivesFinishUntilExplicitCancel() {
        val fake = Fake().apply { saved(42, complete = false); configure = true; launcher = false }
        val sessions = WidgetSessions(fake)
        val first = sessions.session()
        first.click()
        assertEquals(WidgetState.NEED_CONFIGURE, first.state)
        assertEquals(0, fake.requests)
        first.finish()
        assertEquals(42, fake.records.getValue("key").id)
        fake.launcher = true
        val second = sessions.session()
        second.click()
        fake.callback!!(WidgetResult(false, null))
        assertEquals(WidgetState.NEED_BIND, second.state)
        assertNull(second.id)
        assertEquals(listOf(42), fake.deleted)
        assertFalse(fake.records.containsKey("key"))
    }

    @Test
    fun actualProviderMismatchNeverDeletesOrRebindsSavedId() {
        val fake = Fake().apply { saved(42, complete = false); providers[42] = "other/Provider" }
        val session = WidgetSessions(fake).session()
        assertEquals(WidgetState.ERROR, session.state)
        session.click()
        session.finish()
        assertEquals(42, fake.records.getValue("key").id)
        assertEquals(0, fake.binds)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun positiveProviderObservationRetainsBoundPhaseEvenWhenInstalledQueryIsMissing() {
        val fake = Fake().apply { saved(42, complete = false); installed = false; configure = true }
        val first = WidgetSessions(fake).session()
        assertEquals(WidgetState.ERROR, first.state)
        assertEquals(WidgetPhase.BOUND, fake.records.getValue("key").phase)
        first.finish()
        fake.installed = true
        fake.providers.clear()
        val restored = WidgetSessions(fake).session()
        restored.click()
        assertEquals(WidgetState.ERROR, restored.state)
        assertEquals(42, restored.id)
        assertEquals(0, fake.binds)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun boundPhaseWithMissingProviderCannotRebindOrAllocate() {
        val fake = Fake().apply {
            saved(42, complete = false)
            records["key"] = records.getValue("key").copy(phase = WidgetPhase.CONFIGURING)
            providers.clear()
        }
        val session = WidgetSessions(fake).session()
        session.click()
        session.finish()
        assertEquals(42, fake.records.getValue("key").id)
        assertEquals(0, fake.binds)
        assertEquals(0, fake.requests)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun restoredAllocatedIdWaitsForExplicitClickAndCancellationRecyclesIt() {
        val fake = Fake().apply {
            saved(9, complete = false)
            providers.clear()
            records["key"] = records.getValue("key").copy(phase = WidgetPhase.ALLOCATED)
            allowed = false
        }
        val sessions = WidgetSessions(fake)
        val first = sessions.session()
        assertEquals(WidgetState.NEED_BIND, first.state)
        first.lifecycle(resumed = true, attached = true)
        first.finish()
        assertTrue(fake.deleted.isEmpty())
        val second = sessions.session()
        second.click()
        assertEquals(9, second.id)
        assertEquals(10, fake.nextId)
        fake.callback!!(WidgetResult(false, 9))
        assertEquals(listOf(9), fake.deleted)
        assertFalse(fake.records.containsKey("key"))
    }

    @Test
    fun resumeRetriesOncePerEdgeAndStateDiagnosticsDoNotLogRepeatedVisibility() {
        val fake = Fake().apply { saved(7); providers.clear() }
        val session = WidgetSessions(fake).session()
        session.lifecycle(resumed = true)
        val queries = fake.observations
        repeat(20) { session.lifecycle(resumed = true, visible = true, attached = true) }
        assertEquals(queries, fake.observations)
        assertEquals(listOf(WidgetState.ERROR), fake.diagnostics)
        fake.providers[7] = "pkg/Provider"
        session.lifecycle(resumed = false)
        session.lifecycle(resumed = true)
        assertEquals(WidgetState.READY, session.state)
        assertEquals(listOf(WidgetState.ERROR, WidgetState.READY), fake.diagnostics)
        assertEquals(0, fake.requests)
    }

    @Test
    fun independentCardCannotBorrowAnotherCardsMatchingProviderId() {
        val fake = Fake().apply { saved(31) }
        val sessions = WidgetSessions(fake)
        val restored = sessions.session()
        val other = sessions.session("different-key")
        assertEquals(WidgetState.NEED_BIND, other.state)
        assertNull(other.id)
        other.click()
        assertEquals(32, other.id)
        assertEquals(31, restored.id)
        assertEquals(31, fake.records.getValue("key").id)
    }

    @Test
    fun duplicateKeyCannotAllocateOrDeleteFirstSession() {
        val fake = Fake()
        val sessions = WidgetSessions(fake)
        val first = sessions.session()
        first.click()
        val second = sessions.session()
        assertEquals(WidgetState.CONFLICT, second.state)
        second.click()
        second.finish()
        assertEquals(1, fake.owned.size)
        assertFalse(sessions.deleteBinding("key"))
        first.finish()
        assertTrue(sessions.deleteBinding("key"))
        assertEquals(listOf(1), fake.deleted)
    }

    @Test
    fun authorizationCancelReclaimsAndRepeatedClicksDoNotLaunchAgain() {
        val fake = Fake().apply { allowed = false }
        val session = WidgetSessions(fake).session()
        session.click()
        session.click()
        assertEquals(1, fake.requests)
        fake.callback!!(WidgetResult(false, 1))
        assertEquals(listOf(1), fake.deleted)
        assertNull(session.id)
        assertEquals(WidgetState.NEED_BIND, session.state)
    }

    @Test
    fun authorizationChecksReturnedIdAndActualProvider() {
        val fake = Fake().apply { allowed = false }
        val session = WidgetSessions(fake).session()
        session.click()
        fake.providers[1] = "pkg/Provider"
        fake.callback!!(WidgetResult(true, 99))
        assertNull(session.id)
        session.click()
        fake.providers[2] = "unexpected/Provider"
        fake.callback!!(WidgetResult(true, 2))
        assertEquals(WidgetState.ERROR, session.state)
        assertEquals(2, session.id)
        assertEquals(listOf(1), fake.deleted)
        assertEquals(2, fake.records.getValue("key").id)
    }

    @Test
    fun authorizationCannotAcceptMissingId() {
        val fake = Fake().apply { allowed = false }
        val session = WidgetSessions(fake).session()
        session.click()
        fake.providers[1] = "pkg/Provider"
        fake.callback!!(WidgetResult(true, null))
        assertNull(session.id)
        assertFalse(fake.records.containsKey("key"))
    }

    @Test
    fun configurationRequiresClickAndCancelReclaims() {
        val fake = Fake().apply { allowed = false; configure = true }
        val session = WidgetSessions(fake).session()
        session.click()
        fake.providers[1] = "pkg/Provider"
        fake.callback!!(WidgetResult(true, 1))
        assertEquals(WidgetState.NEED_CONFIGURE, session.state)
        assertEquals(1, fake.requests)
        session.click()
        assertEquals(WidgetState.WAIT_CONFIGURE, session.state)
        fake.callback!!(WidgetResult(false, null))
        assertEquals(listOf(1), fake.deleted)
        assertNull(session.id)
    }

    @Test
    fun configurationResultRevalidatesProviderAndHostOwnership() {
        for (removeOwnership in listOf(false, true)) {
            val fake = Fake().apply { configure = true }
            val session = WidgetSessions(fake).session()
            session.click()
            if (removeOwnership) fake.owned.remove(1) else fake.providers[1] = "other/Provider"
            fake.callback!!(WidgetResult(true, null))
            assertEquals(WidgetState.ERROR, session.state)
            assertEquals(1, session.id)
            assertTrue(fake.records.containsKey("key"))
            assertTrue(fake.deleted.isEmpty())
        }
    }

    @Test
    fun staleConfigurationCannotWriteIntoNewSession() {
        val fake = Fake().apply { configure = true }
        val sessions = WidgetSessions(fake)
        val old = sessions.session()
        old.click()
        val callback = fake.callback!!
        old.finish()
        val fresh = sessions.session()
        fresh.click()
        callback(WidgetResult(true, 1))
        assertEquals(WidgetState.WAIT_CONFIGURE, fresh.state)
        assertFalse(fake.records.getValue("key").complete)
        assertEquals(1, fresh.id)
        assertEquals(2, fake.nextId)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun successfulConfigurationMayReturnNoIntentAndKeepsCompletedId() {
        val fake = Fake().apply { configure = true }
        val session = WidgetSessions(fake).session()
        session.click()
        fake.callback!!(WidgetResult(true, null))
        assertEquals(WidgetState.READY, session.state)
        session.finish()
        assertTrue(fake.deleted.isEmpty())
        assertTrue(fake.records.getValue("key").complete)
    }

    @Test
    fun noLauncherLeavesRetriablePendingAndFinishReclaims() {
        val fake = Fake().apply { allowed = false; launcher = false }
        val session = WidgetSessions(fake).session()
        session.click()
        assertEquals(WidgetState.NEED_BIND, session.state)
        assertEquals(0, fake.requests)
        session.finish()
        assertEquals(listOf(1), fake.deleted)
    }

    @Test
    fun listeningIsReferenceCountedByThreeLifecycleFlags() {
        val fake = Fake()
        val sessions = WidgetSessions(fake)
        val a = sessions.session("a")
        val b = sessions.session("b")
        a.lifecycle(resumed = true, visible = true, attached = false)
        assertEquals(0, fake.starts)
        a.lifecycle(attached = true)
        b.lifecycle(resumed = true, visible = true, attached = true)
        assertEquals(1, fake.starts)
        a.lifecycle(visible = false)
        a.finish()
        assertEquals(0, fake.stops)
        b.lifecycle(resumed = false)
        assertEquals(1, fake.stops)
        b.lifecycle(resumed = true)
        assertEquals(2, fake.starts)
        b.finish()
        assertEquals(2, fake.stops)
    }

    @Test
    fun restorationServiceFailureRetriesWithoutOverwritingSavedId() {
        val fake = Fake().apply { saved(7); readFailure = true }
        val session = WidgetSessions(fake).session()
        assertEquals(WidgetState.ERROR, session.state)
        session.click()
        assertEquals(8, fake.nextId)
        fake.readFailure = false
        session.click()
        assertEquals(WidgetState.READY, session.state)
        assertEquals(7, session.id)
        assertEquals(8, fake.nextId)
    }

    @Test
    fun providerRemovalDuringAuthorizationPreservesPendingIdForRetry() {
        val fake = Fake().apply { allowed = false }
        val session = WidgetSessions(fake).session()
        session.click()
        fake.providers[1] = "pkg/Provider"
        fake.installed = false
        fake.callback!!(WidgetResult(true, 1))
        assertEquals(WidgetState.ERROR, session.state)
        assertTrue(fake.deleted.isEmpty())
        assertEquals(1, session.id)
        fake.installed = true
        fake.allowed = true
        session.click()
        assertEquals(WidgetState.READY, session.state)
    }

    @Test
    fun liveProviderRemovalShowsRetryWithoutDeletingCompletedBinding() {
        val fake = Fake()
        val session = WidgetSessions(fake).session()
        session.click()
        fake.installed = false
        session.lifecycle(resumed = true, visible = true, attached = true)
        assertEquals(WidgetState.ERROR, session.state)
        assertTrue(fake.records.getValue("key").complete)
        fake.installed = true
        session.click()
        assertEquals(WidgetState.READY, session.state)
        session.finish()
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun sizeCacheCoalescesRepeatedDpAndKeepsWidgetsIndependent() {
        val cache = WidgetSizeCache()
        var ipcCalls = 0
        val apply: (Map<String, Int>) -> Unit = { ipcCalls++ }
        cache.update(1, 201, 99, 2f, apply)
        cache.update(1, 202, 100, 2f, apply) // same rounded dp despite different pixels
        cache.update(1, 202, 100, 2f, apply)
        assertEquals(1, ipcCalls)
        cache.update(2, 202, 100, 2f, apply)
        assertEquals(2, ipcCalls)
        cache.update(1, 204, 100, 2f, apply)
        assertEquals(3, ipcCalls)
        cache.update(1, 204, 100, 1f, apply) // display density changed
        assertEquals(4, ipcCalls)
    }

    @Test
    fun failedSizeUpdateDoesNotPoisonCache() {
        val cache = WidgetSizeCache()
        var ipcCalls = 0
        try {
            cache.update(1, 200, 100, 2f) { ipcCalls++; error("Binder failure") }
            fail("Expected failure")
        } catch (_: IllegalStateException) {
        }
        cache.update(1, 200, 100, 2f) { ipcCalls++ }
        cache.update(1, 200, 100, 2f) { ipcCalls++ }
        assertEquals(2, ipcCalls)
    }

    @Test
    fun deletedOrRecreatedViewClearsOnlyItsSizeCache() {
        val cache = WidgetSizeCache()
        var ipcCalls = 0
        val apply: (Map<String, Int>) -> Unit = { ipcCalls++ }
        cache.update(1, 200, 100, 2f, apply)
        cache.update(2, 200, 100, 2f, apply)
        cache.clear(1)
        cache.update(2, 200, 100, 2f, apply)
        cache.update(1, 200, 100, 2f, apply)
        assertEquals(3, ipcCalls)
    }

    @Test
    fun hostReleaseRemovesOnlySelectedViewWithoutDeletingPersistentId() {
        val registry = WidgetViewRegistry()
        val a = Any()
        val b = Any()
        registry.register(1, a)
        registry.register(2, b)
        var removed = 0
        var cleared = 0
        assertTrue(registry.release(1, a, { removed++; true }, { cleared++ }))
        assertEquals(1, removed)
        assertEquals(0, cleared)
        assertTrue(registry.release(2, b, { removed++; true }, { cleared++ }))
        assertEquals(2, removed)
        assertEquals(0, cleared)
    }

    @Test
    fun missingRemoveListenerDefersWhileAnotherViewLivesThenSafelyClears() {
        val registry = WidgetViewRegistry()
        val a = Any()
        val b = Any()
        registry.register(1, a)
        registry.register(2, b)
        var cleared = 0
        assertFalse(registry.release(1, a, { false }, { cleared++ }))
        assertEquals(0, cleared)
        assertTrue(registry.release(2, b, { false }, { cleared++ }))
        assertEquals(1, cleared)
    }

    @Test
    fun missingRemoveListenerClearsImmediatelyWhenOnlyOneViewExists() {
        val registry = WidgetViewRegistry()
        val view = Any()
        registry.register(7, view)
        var cleared = 0
        assertTrue(registry.release(7, view, { false }, { cleared++ }))
        assertEquals(1, cleared)
    }

    @Test
    fun staleViewCannotRemoveNewListenerForReusedId() {
        val registry = WidgetViewRegistry()
        val old = Any()
        val fresh = Any()
        registry.register(1, old)
        registry.register(1, fresh)
        var removed = 0
        assertTrue(registry.release(1, old, { removed++; true }, {}))
        assertEquals(0, removed)
        assertTrue(registry.release(1, fresh, { removed++; true }, {}))
        assertEquals(1, removed)
    }

    @Test
    fun naturalSizeUsesProviderDefaultAndDisplayDensityNotRearViewport() {
        val size = WidgetNaturalGeometry.naturalSize(600, 300, 2f, 3f, 12, 18)
        assertEquals(WidgetNaturalSize(912, 468), size)
        val cache = WidgetSizeCache()
        var updates = 0
        // Many viewport changes all notify the same independent provider canvas.
        for (viewport in listOf(100 to 300, 300 to 100, 160 to 160)) {
            WidgetNaturalGeometry.fit(size, viewport.first, viewport.second)
            cache.update(7, size.widthPx, size.heightPx, 3f) { updates++ }
        }
        assertEquals(1, updates)
    }

    @Test
    fun missingProviderDimensionsHaveStableDpDefaultsAndFixedContentLowerBound() {
        assertEquals(WidgetNaturalSize(480, 240), WidgetNaturalGeometry.naturalSize(0, 0, 2f, 2f))
        assertEquals(
            WidgetNaturalSize(650, 360),
            WidgetNaturalGeometry.naturalSize(300, 150, 1f, 1f, 10, 10, 640, 350)
        )
    }

    @Test
    fun horizontalAndVerticalWidgetsFitCenterWithoutCropOrIndependentStretch() {
        val landscape = WidgetNaturalGeometry.fit(WidgetNaturalSize(600, 200), 200, 200)
        assertEquals(1f / 3f, landscape.scale, 0.0001f)
        assertEquals(0f, landscape.left, 0.0001f)
        assertEquals(200f / 3f, landscape.top, 0.0001f)
        val portrait = WidgetNaturalGeometry.fit(WidgetNaturalSize(200, 600), 200, 200)
        assertEquals(landscape.scale, portrait.scale, 0.0001f)
        assertEquals(landscape.top, portrait.left, 0.0001f)
        assertEquals(0f, portrait.top, 0.0001f)
        val point =
            portrait.naturalPoint(portrait.left + 100 * portrait.scale, 300 * portrait.scale)!!
        assertEquals(100f, point.first, 0.0001f)
        assertEquals(300f, point.second, 0.0001f)
        assertNull(
            WidgetNaturalGeometry.fit(WidgetNaturalSize(200, 600), 0, 100).naturalPoint(1f, 1f)
        )
    }

    @Test
    fun sizeUsesDpAndPreservesProviderOptions() {
        val merged = WidgetSizeOptions.merge(mapOf("providerOption" to 17), 201, 99, 2f)
        assertEquals(17, merged["providerOption"])
        assertEquals(101, merged["appWidgetMinWidth"])
        assertEquals(101, merged["appWidgetMaxWidth"])
        assertEquals(50, merged["appWidgetMinHeight"])
        assertEquals(50, merged["appWidgetMaxHeight"])
    }
}
