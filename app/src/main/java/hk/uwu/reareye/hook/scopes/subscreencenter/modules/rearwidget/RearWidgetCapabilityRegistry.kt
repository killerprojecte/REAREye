package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import java.util.concurrent.ConcurrentHashMap

internal enum class RearWidgetCapability(
    val logName: String,
) {
    NOTIFICATION_ENTRY("notificationEntry"),
    FOCUS_NOTIFICATION_ALLOW("focusNotificationAllow"),
    ORDINARY_NOTIFICATION_ROUTING("ordinaryNotificationRouting"),
    BUSINESS_RESOLUTION("businessResolution"),
    WIDGET_PATH_OVERRIDE("widgetPathOverride"),
    SMART_ASSISTANT_INSERTION("smartAssistantInsertion"),
    WIDGET_LIFECYCLE_TRACKING("widgetLifecycleTracking"),
    INACTIVE_INDEX_RESTORE("inactiveIndexRestore"),
}

internal class RearWidgetCapabilityRegistry(
    private val logger: (String) -> Unit = {},
    private val errorLogger: (Throwable) -> Unit = {},
) {
    private data class State(
        val enabled: Boolean,
        val reason: String?,
    )

    private val states = ConcurrentHashMap<RearWidgetCapability, State>().apply {
        RearWidgetCapability.entries.forEach { capability ->
            put(capability, State(enabled = false, reason = "not_initialized"))
        }
    }

    fun install(
        capability: RearWidgetCapability,
        installer: () -> Unit,
    ): Boolean = runCatching(installer)
        .fold(
            onSuccess = {
                states[capability] = State(enabled = true, reason = null)
                logger("capability ${capability.logName} enabled")
                true
            },
            onFailure = { error ->
                errorLogger(error)
                disable(capability, error.toCapabilityReason())
                false
            },
        )

    fun installWhenDependenciesEnabled(
        capability: RearWidgetCapability,
        dependencies: Collection<RearWidgetCapability>,
        installer: () -> Unit,
    ): Boolean {
        val missing = dependencies.filterNot(::isEnabled)
        if (missing.isNotEmpty()) {
            disable(
                capability,
                "missing_dependencies:${missing.joinToString(",") { it.logName }}",
            )
            return false
        }
        return install(capability, installer)
    }

    fun disable(
        capability: RearWidgetCapability,
        reason: String,
    ) {
        states[capability] = State(enabled = false, reason = reason)
        logger("capability ${capability.logName} disabled reason=$reason")
    }

    fun isEnabled(capability: RearWidgetCapability): Boolean =
        states[capability]?.enabled == true

    fun summary(): String = RearWidgetCapability.entries.joinToString(separator = "\n") { capability ->
        val state = states.getValue(capability)
        buildString {
            append(capability.logName)
            append('=')
            append(state.enabled)
            if (!state.enabled && state.reason != null) {
                append(" reason=")
                append(state.reason)
            }
        }
    }
}

private fun Throwable.toCapabilityReason(): String = buildString {
    append(this@toCapabilityReason::class.java.simpleName)
    message?.takeIf(String::isNotBlank)?.let { detail ->
        append(':')
        append(detail)
    }
}
