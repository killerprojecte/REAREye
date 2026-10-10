package hk.uwu.reareye.hook.support

import android.content.Context
import java.lang.reflect.Modifier

/**
 * Owns one dynamic receiver registration across lifecycle replay and hot-reload quiesce.
 *
 * The exact Context that registered the receiver is retained until unregister succeeds. A failed
 * unregister keeps the state intact so a later quiesce attempt can retry safely.
 */
internal class ReloadableReceiverRegistration(
    private val label: String,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val lock = Any()

    @Volatile
    private var registered = false

    @Volatile
    private var context: Context? = null

    fun isRegistered(): Boolean = registered

    fun isConsistent(): Boolean = !registered || context != null

    fun register(
        context: Context,
        action: (Context) -> Unit,
    ): Boolean = synchronized(lock) {
        if (registered) return@synchronized this.context != null

        runCatching { action(context) }.fold(
            onSuccess = {
                this.context = context
                registered = true
                true
            },
            onFailure = { throwable ->
                this.context = null
                registered = false
                logger("$label register failed", throwable)
                false
            },
        )
    }

    fun unregister(action: (Context) -> Unit): Boolean = synchronized(lock) {
        if (!registered) {
            context = null
            return@synchronized true
        }

        val registeredContext = context
        if (registeredContext == null) {
            logger("$label unregister failed: registration Context is missing", null)
            return@synchronized false
        }

        runCatching { action(registeredContext) }.fold(
            onSuccess = {
                registered = false
                context = null
                true
            },
            onFailure = { throwable ->
                if (throwable is IllegalArgumentException) {
                    registered = false
                    context = null
                    logger("$label was already unregistered", throwable)
                    true
                } else {
                    logger("$label unregister failed", throwable)
                    false
                }
            },
        )
    }
}

internal data class InstanceAdoption(
    val accepted: Boolean,
    val changed: Boolean,
)

internal fun assessInstanceAdoption(
    current: Any?,
    candidate: Any?,
    expectedType: Class<*>,
): InstanceAdoption {
    if (candidate == null || !expectedType.isInstance(candidate)) {
        return InstanceAdoption(accepted = false, changed = false)
    }
    return InstanceAdoption(accepted = true, changed = current !== candidate)
}

internal fun resolveNamedStaticInstance(
    type: Class<*>,
    fieldNames: Iterable<String>,
    methodNames: Iterable<String>,
): Any? {
    fieldNames.forEach { name ->
        val value = runCatching {
            type.getDeclaredField(name)
                .apply { isAccessible = true }
                .takeIf { Modifier.isStatic(it.modifiers) }
                ?.get(null)
        }.getOrNull()
        if (value != null) return value
    }
    methodNames.forEach { name ->
        val value = runCatching {
            type.getDeclaredMethod(name)
                .apply { isAccessible = true }
                .takeIf { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
                ?.invoke(null)
        }.getOrNull()
        if (value != null) return value
    }
    return null
}

internal fun readNamedInstanceField(target: Any, names: Iterable<String>): Any? {
    var type: Class<*>? = target.javaClass
    while (type != null && type != Any::class.java) {
        val currentType = type
        names.forEach { name ->
            val value = runCatching {
                currentType.getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(target)
            }.getOrNull()
            if (value != null) return value
        }
        type = currentType.superclass
    }
    return null
}
