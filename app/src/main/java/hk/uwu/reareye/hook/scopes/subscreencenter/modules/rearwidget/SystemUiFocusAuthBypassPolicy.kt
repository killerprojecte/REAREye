package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import java.lang.reflect.Method

internal object SystemUiFocusAuthBypassPolicy {
    private const val WHITELIST_METHOD = "canPassXMSPermission"

    fun shouldBypass(
        packageName: String?,
        enabled: Boolean,
        selectedPackages: Set<String>,
    ): Boolean {
        return enabled && packageName != null && packageName in selectedPackages
    }

    fun findWhitelistMethod(type: Class<*>): Method? {
        return runCatching {
            type.declaredMethods.singleOrNull { method ->
                !method.isSynthetic &&
                        method.name == WHITELIST_METHOD &&
                        method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        }.getOrNull()
    }
}
