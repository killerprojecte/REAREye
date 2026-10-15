package hk.uwu.reareye.hook.activityhost

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Hidden ROM ABI is resolved here; no bundled Shell classes or unchecked field casts. */
internal object ActivityHostReflection {
    fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { requireNotNull(current).getDeclaredField(name) }.getOrNull()?.let {
                return it.apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("ActivityHost ABI missing field ${type.name}.$name")
    }

    fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { requireNotNull(current).getDeclaredMethod(name, *parameters) }.getOrNull()
                ?.let {
                    return it.apply { isAccessible = true }
                }
            current = current.superclass
        }
        error("ActivityHost ABI missing method ${type.name}.$name")
    }

    fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            val candidate = current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == args.size &&
                        method.parameterTypes.zip(args).all { (type, value) ->
                            if (value == null) !type.isPrimitive else boxed(type).isInstance(value)
                        }
            }
            if (candidate != null) return candidate.apply { isAccessible = true }
                .invoke(target, *args)
            current = current.superclass
        }
        error("ActivityHost ABI missing call ${target.javaClass.name}.$name")
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }
}
