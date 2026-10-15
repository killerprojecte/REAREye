package hk.uwu.reareye.utils

import kotlinx.coroutines.delay

internal enum class SubscreenNotificationAccessState {
    UNAVAILABLE,
    UNKNOWN,
    GRANTED,
    MISSING,
}

internal data class SubscreenNotificationGrantResult(
    val granted: Boolean,
    val detail: String = "",
)

/** Only the explicit grant action writes permissions; checking never changes settings. */
internal class SubscreenNotificationAccess(
    private val execute: (String) -> RootCommandResult = {
        RootHelper.executeRootCommandDetailed(it)
    },
    private val waitForUpdate: suspend (Long) -> Unit = { delay(it) },
) {
    companion object {
        const val PACKAGE = "com.xiaomi.subscreencenter"
        const val SERVICE = "$PACKAGE.smartassistant.SmartAssistantNotificationListenerService"
        const val COMPONENT = "$PACKAGE/$SERVICE"
        private val componentPart = Regex("[A-Za-z_][A-Za-z0-9_.$]*")

        internal fun isGranted(listeners: String): Boolean? {
            val value = listeners.trim()
            if (value.isEmpty() || value == "null") return false
            var granted = false
            for (entry in value.split(':')) {
                val parts = entry.trim().split('/')
                if (parts.size != 2 || !componentPart.matches(parts[0])) return null
                val className = if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
                if (!componentPart.matches(className)) return null
                if ("${parts[0]}/$className" == COMPONENT) granted = true
            }
            return granted
        }
    }

    fun check(userId: Int, listenerAvailable: Boolean): SubscreenNotificationAccessState {
        require(userId >= 0)
        if (!listenerAvailable) return SubscreenNotificationAccessState.UNAVAILABLE
        val root = execute("id -u")
        if (!root.succeeded() || root.stdout.trim() != "0") {
            return SubscreenNotificationAccessState.UNAVAILABLE
        }
        return readState(userId)
    }

    suspend fun grant(userId: Int, listenerAvailable: Boolean): SubscreenNotificationGrantResult {
        // Recheck after confirmation: root access or the permission may have changed.
        when (check(userId, listenerAvailable)) {
            SubscreenNotificationAccessState.GRANTED -> return SubscreenNotificationGrantResult(true)
            SubscreenNotificationAccessState.MISSING -> Unit
            else -> return SubscreenNotificationGrantResult(false)
        }
        val result = execute("cmd notification allow_listener $COMPONENT $userId")
        if (!result.succeeded()) {
            return SubscreenNotificationGrantResult(
                granted = false,
                detail = result.stderr.ifBlank { result.stdout }.trim().take(300),
            )
        }
        // NotificationManager may persist the listener list asynchronously after the command
        // returns. Allow it to settle, without issuing the grant again or blocking the UI.
        if (readState(userId) == SubscreenNotificationAccessState.GRANTED) {
            return SubscreenNotificationGrantResult(true)
        }
        for (delayMillis in listOf(100L, 200L, 400L, 800L, 1500L)) {
            waitForUpdate(delayMillis)
            if (readState(userId) == SubscreenNotificationAccessState.GRANTED) {
                return SubscreenNotificationGrantResult(true)
            }
        }
        // Still require observed permission; a successful shell exit alone is insufficient.
        return SubscreenNotificationGrantResult(false)
    }

    private fun readState(userId: Int): SubscreenNotificationAccessState {
        val result = execute("settings --user $userId get secure enabled_notification_listeners")
        if (!result.succeeded()) return SubscreenNotificationAccessState.UNKNOWN
        return when (isGranted(result.stdout)) {
            true -> SubscreenNotificationAccessState.GRANTED
            false -> SubscreenNotificationAccessState.MISSING
            null -> SubscreenNotificationAccessState.UNKNOWN
        }
    }

    private fun RootCommandResult.succeeded(): Boolean =
        exitCode == 0 && !timedOut && !outputLimitExceeded
}
