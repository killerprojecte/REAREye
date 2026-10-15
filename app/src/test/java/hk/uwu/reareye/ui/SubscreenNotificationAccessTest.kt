package hk.uwu.reareye.ui

import hk.uwu.reareye.utils.RootCommandResult
import hk.uwu.reareye.utils.SubscreenNotificationAccess
import hk.uwu.reareye.utils.SubscreenNotificationAccessState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscreenNotificationAccessTest {
    private val component = SubscreenNotificationAccess.COMPONENT
    private val otherListeners =
        "com.xiaomi.barrage/com.xiaomi.barrage.service.NotificationMonitorService:" +
                "com.mi.health/com.xiaomi.fitness.notify.NotifySyncService"

    @Test
    fun recognizesFullAndShortComponentNames() {
        assertEquals(true, SubscreenNotificationAccess.isGranted("$otherListeners:$component\n"))
        assertEquals(
            true,
            SubscreenNotificationAccess.isGranted(
                "com.xiaomi.subscreencenter/.smartassistant.SmartAssistantNotificationListenerService",
            ),
        )
    }

    @Test
    fun absentPermissionIncludesEmptyAndNullSettings() {
        listOf("", "null\n", otherListeners).forEach {
            assertEquals(false, SubscreenNotificationAccess.isGranted(it))
        }
    }

    @Test
    fun doesNotAcceptSubstringOrMalformedOutput() {
        assertEquals(false, SubscreenNotificationAccess.isGranted("${component}Extra"))
        assertNull(SubscreenNotificationAccess.isGranted("Error: permission denied"))
        assertNull(SubscreenNotificationAccess.isGranted("$component\nUnexpected output"))
    }

    @Test
    fun missingServiceSkipsRootAndSettingsCommands() {
        val shell = FakeShell()
        assertEquals(
            SubscreenNotificationAccessState.UNAVAILABLE,
            shell.access.check(0, listenerAvailable = false),
        )
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun missingRootSkipsSettingsAndDoesNotWrite() {
        val shell = FakeShell(success("2000"))
        assertEquals(
            SubscreenNotificationAccessState.UNAVAILABLE,
            shell.access.check(0, listenerAvailable = true),
        )
        assertEquals(listOf("id -u"), shell.commands)
    }

    @Test
    fun deniedRootSkipsSettings() {
        val shell = FakeShell(RootCommandResult(1, "", "denied"))
        assertEquals(
            SubscreenNotificationAccessState.UNAVAILABLE,
            shell.access.check(0, listenerAvailable = true),
        )
        assertEquals(listOf("id -u"), shell.commands)
    }

    @Test
    fun checksSelectedUserWithoutChangingPermissions() {
        val shell = FakeShell(success("0"), success(otherListeners))
        assertEquals(
            SubscreenNotificationAccessState.MISSING,
            shell.access.check(10, listenerAvailable = true),
        )
        assertEquals(
            listOf("id -u", "settings --user 10 get secure enabled_notification_listeners"),
            shell.commands,
        )
    }

    @Test
    fun settingsFailureIsNotTreatedAsMissingPermission() {
        val shell = FakeShell(success("0"), RootCommandResult(1, "", "unavailable"))
        assertEquals(
            SubscreenNotificationAccessState.UNKNOWN,
            shell.access.check(0, listenerAvailable = true),
        )
    }

    @Test
    fun timedOutOrTruncatedSettingsAreUnknown() {
        listOf(
            success("null").copy(timedOut = true),
            success("null").copy(outputLimitExceeded = true),
        ).forEach { result ->
            val shell = FakeShell(success("0"), result)
            assertEquals(
                SubscreenNotificationAccessState.UNKNOWN,
                shell.access.check(0, listenerAvailable = true),
            )
        }
    }

    @Test
    fun alreadyGrantedDoesNotIssueWriteCommand() = runBlocking {
        val shell = FakeShell(success("0"), success("$otherListeners:$component"))
        assertTrue(shell.access.grant(0, listenerAvailable = true).granted)
        assertFalse(shell.commands.any { it.startsWith("cmd notification") })
    }

    @Test
    fun confirmationGrantsOnlyTargetListenerForSelectedUserAndVerifiesIt() = runBlocking {
        val shell = FakeShell(
            success("0"),
            success(otherListeners),
            success(""),
            success("$otherListeners:$component"),
        )
        assertTrue(shell.access.grant(10, listenerAvailable = true).granted)
        assertEquals(
            listOf(
                "id -u",
                "settings --user 10 get secure enabled_notification_listeners",
                "cmd notification allow_listener $component 10",
                "settings --user 10 get secure enabled_notification_listeners",
            ),
            shell.commands,
        )
        assertFalse(shell.commands.any { it.contains("settings put") })
    }

    @Test
    fun successExitCodeWithoutActualPermissionIsFailure() = runBlocking {
        val shell = FakeShell(
            success("0"), success("null"), success(""),
            *Array(6) { success("null") },
        )
        assertFalse(shell.access.grant(0, listenerAvailable = true).granted)
        assertEquals(listOf(100L, 200L, 400L, 800L, 1500L), shell.waits)
        assertEquals(1, shell.commands.count { it.startsWith("cmd notification") })
    }

    @Test
    fun grantFailureReportsErrorAndDoesNotClaimSuccess() = runBlocking {
        val shell = FakeShell(
            success("0"), success("null"), RootCommandResult(1, "", "SecurityException"),
        )
        val result = shell.access.grant(0, listenerAvailable = true)
        assertFalse(result.granted)
        assertEquals("SecurityException", result.detail)
        assertEquals(3, shell.commands.size)
    }

    @Test
    fun lostRootAfterConfirmationDoesNotWritePermissions() = runBlocking {
        val shell = FakeShell(RootCommandResult(1, "", "denied"))
        assertFalse(shell.access.grant(0, listenerAvailable = true).granted)
        assertEquals(listOf("id -u"), shell.commands)
    }

    @Test
    fun firstGrantWaitsForAsynchronousPermissionPersistence() = runBlocking {
        val shell = FakeShell(
            success("0"), success(otherListeners), success(""),
            success(otherListeners), success(otherListeners), success("$otherListeners:$component"),
        )
        assertTrue(shell.access.grant(0, listenerAvailable = true).granted)
        assertEquals(listOf(100L, 200L), shell.waits)
        assertEquals(1, shell.commands.count { it.startsWith("cmd notification") })
    }

    @Test
    fun temporaryVerificationReadFailureIsRetried() = runBlocking {
        val shell = FakeShell(
            success("0"), success("null"), success(""),
            RootCommandResult(1, "", "temporary failure"), success(component),
        )
        assertTrue(shell.access.grant(0, listenerAvailable = true).granted)
        assertEquals(listOf(100L), shell.waits)
    }

    @Test
    fun permissionPersistedAtLastRetryStillSucceeds() = runBlocking {
        val shell = FakeShell(
            success("0"), success("null"), success(""),
            *Array(5) { success("null") }, success(component),
        )
        assertTrue(shell.access.grant(0, listenerAvailable = true).granted)
        assertEquals(5, shell.waits.size)
    }

    private fun success(stdout: String) = RootCommandResult(0, stdout, "")

    private class FakeShell(vararg responses: RootCommandResult) {
        val commands = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        private val results = ArrayDeque(responses.toList())
        val access = SubscreenNotificationAccess(
            execute = { command ->
                commands += command
                check(results.isNotEmpty()) { "Unexpected command: $command" }
                results.removeFirst()
            },
            waitForUpdate = { waits += it },
        )
    }
}
