package hk.uwu.reareye.hook.core

import android.content.pm.ApplicationInfo
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookModuleReloadTest {
    @Test
    fun restoresStateBeforeHookAndFreezesOldGeneration() {
        val first = StatefulModule()
        val context = FakeHookContext()
        first.install(context, Bundle())

        assertTrue(first.restoreCalled)
        assertEquals(1, first.hookCalls)
        assertEquals(1, first.reloadedCalls)

        val saved = first.saveReloadState()
        first.freeze()
        assertEquals(1, first.reloadCleanupCalls)
        assertTrue(first.frozenObserved)

        val second = StatefulModule()
        second.install(context, saved)
        assertTrue(second.restoreCalled)
        assertEquals(1, second.hookCalls)
        assertEquals(1, second.reloadedCalls)
    }

    @Test(expected = IllegalStateException::class)
    fun moduleCannotBeInstalledAcrossTwoTargets() {
        val module = StatefulModule()
        module.install(FakeHookContext(), null)
        module.install(FakeHookContext(), null)
    }

    @Test
    fun onHookFailureIsLoggedAndCleanupCanRun() {
        val module = FailingModule(failInHook = true)

        val failure = runCatching { module.install(FakeHookContext(), null) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, module.reloadedCalls)
        assertTrue(module.freeze())
        assertEquals(1, module.cleanupCalls)
    }

    @Test
    fun onReloadedFailureIsLoggedAndCleanupCanRun() {
        val module = FailingModule(failInReloaded = true)

        val failure = runCatching { module.install(FakeHookContext(), null) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, module.hookCalls)
        assertEquals(1, module.reloadedCalls)
        assertTrue(module.freeze())
        assertEquals(1, module.cleanupCalls)
    }

    @Test
    fun cleanupFailureFreezesOldGenerationAndCannotBeRetriedOrReused() {
        val module = FailingModule(failFirstCleanup = true)
        module.install(FakeHookContext(), null)

        assertFalse(module.freeze())
        assertEquals(1, module.cleanupCalls)
        assertFalse(module.gateOpen)
        assertFalse(module.freeze())
        assertEquals(1, module.cleanupCalls)
    }

    @Test
    fun preflightFailureRollsBackPurePreparationWithoutTouchingOldGeneration() {
        val successful = StatefulModule()
        val failing = FailingModule(failPreflight = true)
        val context = FakeHookContext()
        successful.install(context, null)
        failing.install(context, null)

        val result = runReloadTransaction(
            items = listOf<HookModule>(successful, failing),
            preflight = HookModule::preflightFreeze,
            prepare = HookModule::prepareFreeze,
            rollback = HookModule::rollbackFreezePreparation,
            commit = HookModule::commitFreeze,
        )

        assertFalse(result.accepted)
        assertEquals(0, result.commitFailureCount)
        assertTrue(successful.gateOpen)
        assertTrue(failing.gateOpen)
        assertEquals(0, successful.reloadCleanupCalls)
        assertEquals(0, failing.cleanupCalls)
    }

    @Test
    fun multiTargetReloadRollsBackEveryPreparedTargetWhenOneFails() {
        data class Target(
            val shouldFail: Boolean,
            var prepared: Boolean = false,
            var rolledBack: Boolean = false,
            var committed: Boolean = false,
        )

        val first = Target(shouldFail = false)
        val second = Target(shouldFail = true)
        val result = runReloadTransaction(
            items = listOf(first, second),
            prepare = { target ->
                target.prepared = true
                !target.shouldFail
            },
            rollback = { target -> target.rolledBack = true },
            commit = { target -> target.committed = true; true },
        )

        assertFalse(result.accepted)
        assertEquals(0, result.commitFailureCount)
        assertTrue(first.prepared)
        assertTrue(second.prepared)
        assertTrue(first.rolledBack)
        assertTrue(second.rolledBack)
        assertFalse(first.committed)
        assertFalse(second.committed)
    }

    @Test
    fun multiTargetReloadCommitsOnlyAfterEveryTargetPrepared() {
        data class Target(var committed: Boolean = false)

        val first = Target()
        val second = Target()
        val result = runReloadTransaction(
            items = listOf(first, second),
            prepare = { true },
            rollback = {},
            commit = { it.committed = true; true },
        )

        assertTrue(result.accepted)
        assertEquals(0, result.commitFailureCount)
        assertTrue(first.committed)
        assertTrue(second.committed)
    }

    @Test
    fun commitExceptionDoesNotRejectPartiallyCommittedReloadOrSkipOtherTargets() {
        data class Target(var commitCalls: Int = 0)

        val first = Target()
        val failing = Target()
        val following = Target()
        val failures = mutableListOf<String>()
        val result = runReloadTransaction(
            items = listOf(first, failing, following),
            prepare = { true },
            rollback = {},
            commit = { target ->
                target.commitCalls++
                if (target === failing) error("commit failure")
                true
            },
            onFailure = { phase, _, _ -> failures += phase },
        )

        assertTrue(result.accepted)
        assertEquals(1, result.commitFailureCount)
        assertEquals(1, first.commitCalls)
        assertEquals(1, failing.commitCalls)
        assertEquals(1, following.commitCalls)
        assertEquals(listOf("commit"), failures)
    }

    @Test
    fun rollbackReopensGenerationGateAndCommitClosesIt() {
        val module = StatefulModule()
        module.install(FakeHookContext(), null)

        assertTrue(module.gateOpen)
        assertTrue(module.prepareFreeze())
        assertTrue(module.gateOpen)
        module.rollbackFreezePreparation()
        assertTrue(module.gateOpen)
        assertTrue(module.prepareFreeze())
        assertTrue(module.gateOpen)
        assertTrue(module.commitFreeze())
        assertFalse(module.gateOpen)
    }

    @Test
    fun preparationFalseRollsBackTheFailingModuleImmediately() {
        val events = mutableListOf<String>()
        val module = PreparationModule("module", events, prepareResult = false)
        module.install(FakeHookContext(), null)

        assertFalse(module.prepareFreeze())

        assertEquals(listOf("module.prepare", "module.rollback"), events)
        assertTrue(module.gateOpen)
    }

    @Test
    fun preparationExceptionRollsBackTheFailingModuleImmediately() {
        val events = mutableListOf<String>()
        val module = PreparationModule("module", events, throwDuringPrepare = true)
        module.install(FakeHookContext(), null)

        assertFalse(module.prepareFreeze())

        assertEquals(listOf("module.prepare", "module.rollback"), events)
        assertTrue(module.gateOpen)
    }

    @Test
    fun laterPreparationFailureRollsBackPreparedModulesInReverseOrder() {
        val events = mutableListOf<String>()
        val first = PreparationModule("first", events)
        val second = PreparationModule("second", events)
        val failing = PreparationModule("failing", events, prepareResult = false)
        listOf(first, second, failing).forEach { it.install(FakeHookContext(), null) }

        val result = runReloadTransaction(
            items = listOf<HookModule>(first, second, failing),
            prepare = HookModule::prepareFreeze,
            rollback = HookModule::rollbackFreezePreparation,
            commit = HookModule::commitFreeze,
        )

        assertFalse(result.accepted)
        assertEquals(
            listOf(
                "first.prepare",
                "second.prepare",
                "failing.prepare",
                "failing.rollback",
                "second.rollback",
                "first.rollback",
            ),
            events,
        )
        assertTrue(first.gateOpen)
        assertTrue(second.gateOpen)
        assertTrue(failing.gateOpen)
    }

    @Test
    fun successfulCommitDoesNotRunPreparationRollback() {
        val events = mutableListOf<String>()
        val first = PreparationModule("first", events)
        val second = PreparationModule("second", events)
        listOf(first, second).forEach { it.install(FakeHookContext(), null) }

        val result = runReloadTransaction(
            items = listOf<HookModule>(first, second),
            prepare = HookModule::prepareFreeze,
            rollback = HookModule::rollbackFreezePreparation,
            commit = HookModule::commitFreeze,
        )

        assertTrue(result.accepted)
        assertEquals(0, result.commitFailureCount)
        assertEquals(
            listOf("first.prepare", "second.prepare", "first.commit", "second.commit"),
            events,
        )
        assertFalse(first.gateOpen)
        assertFalse(second.gateOpen)
    }

    @Test
    fun businessModuleFailureDoesNotStopFollowingModules() {
        val failed = FailingModule(failInHook = true)
        val following = StatefulModule()

        val attempted = installHookModules(
            modules = listOf(failed, following),
            context = FakeHookContext(),
            moduleStates = null,
            logger = NoopHookLogger(),
            stateKey = { index, _ -> index.toString() },
        )

        assertEquals(2, attempted.size)
        assertEquals(0, failed.reloadedCalls)
        assertEquals(1, following.hookCalls)
        assertEquals(1, following.reloadedCalls)
    }

    private class StatefulModule : HookModule() {
        var hookCalls = 0
        var restoreCalled = false
        var reloadedCalls = 0
        var reloadCleanupCalls = 0
        var frozenObserved = false
        val gateOpen: Boolean
            get() = reloadGenerationGate.isOpen()

        override fun onHook() {
            hookCalls++
        }

        override fun saveReloadState(): Bundle = Bundle()

        override fun restoreReloadState(state: Bundle) {
            restoreCalled = true
        }

        override fun onReloaded() {
            reloadedCalls++
        }

        override fun onReloading(): Boolean {
            reloadCleanupCalls++
            frozenObserved = true
            return true
        }
    }

    private class FailingModule(
        private val failInHook: Boolean = false,
        private val failInReloaded: Boolean = false,
        private val failFirstCleanup: Boolean = false,
        private val failCleanupResult: Boolean = false,
        private val failPreflight: Boolean = false,
    ) : HookModule() {
        var hookCalls = 0
        var reloadedCalls = 0
        var cleanupCalls = 0
        val gateOpen: Boolean
            get() = reloadGenerationGate.isOpen()

        override fun onHook() {
            hookCalls++
            if (failInHook) error("hook failure")
        }

        override fun onReloaded() {
            reloadedCalls++
            if (failInReloaded) error("reloaded failure")
        }

        override fun onReloadingPreflight(): Boolean = !failPreflight

        override fun onReloading(): Boolean {
            cleanupCalls++
            if (failFirstCleanup && cleanupCalls == 1) error("cleanup failure")
            if (failCleanupResult && cleanupCalls == 1) return false
            return true
        }
    }

    private class PreparationModule(
        private val name: String,
        private val events: MutableList<String>,
        private val prepareResult: Boolean = true,
        private val throwDuringPrepare: Boolean = false,
    ) : HookModule() {
        val gateOpen: Boolean
            get() = reloadGenerationGate.isOpen()

        override fun onHook() = Unit

        override fun onReloadingPrepare(): Boolean {
            events += "$name.prepare"
            if (throwDuringPrepare) error("$name prepare failure")
            return prepareResult
        }

        override fun onReloadingPreparationRolledBack() {
            events += "$name.rollback"
        }

        override fun onReloading(): Boolean {
            events += "$name.commit"
            return true
        }
    }

    private class FakeHookContext : HookContext {
        override val packageName: String = "test.package"
        override val processName: String = "test.package"
        override val appInfo: ApplicationInfo = ApplicationInfo().apply {
            packageName = "test.package"
            processName = "test.package"
            uid = 10000
        }
        override val classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
        override val isSystemServer: Boolean = false
        override val appContext = null
        override val systemContext
            get() = throw IllegalStateException("not required by this test")
        override val lifecycle: HookLifecycle = NoopHookLifecycle()
        override val prefs: HookPrefs = NoopHookPrefs()
        override val hooks: HookRegistry = NoopHookRegistry()
        override val logger: HookLogger = NoopHookLogger()
        override val isRearDevice: Boolean = false
        override val targetId: String = "test-target"
    }

    private class NoopHookLifecycle : HookLifecycle {
        override fun register(
            owner: HookModule,
            onAttach: (HookInvocation.() -> Unit)?,
            onCreate: (HookInvocation.() -> Unit)?,
            onTerminate: (HookInvocation.() -> Unit)?,
        ) = Unit

        override fun dispatchAttach(invocation: HookInvocation) = Unit
        override fun dispatchCreate(invocation: HookInvocation) = Unit
        override fun dispatchTerminate(invocation: HookInvocation) = Unit
        override fun freeze() = Unit
    }

    private class NoopHookPrefs : HookPrefs {
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun getStringSet(key: String, defaultValue: Set<String>) = defaultValue
        override fun getInt(key: String, defaultValue: Int) = defaultValue
        override fun getLong(key: String, defaultValue: Long) = defaultValue
        override fun getFloat(key: String, defaultValue: Float) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun contains(key: String) = false
        override fun all(): Map<String, Any?> = emptyMap()
        override fun edit(): HookPrefsEditor = error("not required by this test")
    }

    private class NoopHookRegistry : HookRegistry {
        override fun install(
            id: String,
            executable: java.lang.reflect.Executable,
            hooker: io.github.libxposed.api.XposedInterface.Hooker,
        ): InstalledHook = error("not required by this test")

        override fun remove(id: String, hook: InstalledHook) = Unit
        override fun size(): Int = 0
        override fun unhookAll() = Unit
        override fun unhookAllChecked(): Boolean = true
        override fun finishHotReloadReplacement(): Boolean = true
    }

    private class NoopHookLogger : HookLogger {
        override fun debug(message: String, throwable: Throwable?) = Unit
        override fun info(message: String, throwable: Throwable?) = Unit
        override fun warn(message: String, throwable: Throwable?) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
