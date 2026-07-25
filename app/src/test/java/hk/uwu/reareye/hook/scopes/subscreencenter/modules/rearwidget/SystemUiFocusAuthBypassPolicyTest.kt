package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiFocusAuthBypassPolicyTest {

    private class ExpectedSettings {
        @Suppress("unused")
        fun canPassXMSPermission(packageName: String): Boolean = false
    }

    private class WrongSettings {
        @Suppress("unused")
        fun canPassXMSPermission(packageName: String) = Unit

        @Suppress("unused")
        fun canPassXMSPermission(key: String, packageName: String): Boolean = true
    }

    @Test
    fun bypassesOnlyExactPackagesSelectedByUser() {
        val selectedPackages = setOf(
            "com.example.notes",
            "com.example.music",
        )

        assertTrue(
            SystemUiFocusAuthBypassPolicy.shouldBypass(
                packageName = "com.example.notes",
                enabled = true,
                selectedPackages = selectedPackages,
            ),
        )
        assertFalse(
            SystemUiFocusAuthBypassPolicy.shouldBypass(
                packageName = "com.example.Notes",
                enabled = true,
                selectedPackages = selectedPackages,
            ),
        )
        assertFalse(
            SystemUiFocusAuthBypassPolicy.shouldBypass(
                packageName = "com.example.notes.debug",
                enabled = true,
                selectedPackages = selectedPackages,
            ),
        )
        assertFalse(
            SystemUiFocusAuthBypassPolicy.shouldBypass(
                packageName = null,
                enabled = true,
                selectedPackages = selectedPackages,
            ),
        )
    }

    @Test
    fun bypassIsDisabledBySwitchOrEmptySelection() {
        assertFalse(
            SystemUiFocusAuthBypassPolicy.shouldBypass(
                packageName = "com.example.notes",
                enabled = false,
                selectedPackages = setOf("com.example.notes"),
            ),
        )
        assertFalse(
            SystemUiFocusAuthBypassPolicy.shouldBypass(
                packageName = "com.example.notes",
                enabled = true,
                selectedPackages = emptySet(),
            ),
        )
    }

    @Test
    fun resolvesOnlyExactOneStringBooleanWhitelistMethod() {
        val method = SystemUiFocusAuthBypassPolicy.findWhitelistMethod(
            ExpectedSettings::class.java,
        )

        assertSame(Boolean::class.javaPrimitiveType, method?.returnType)
        assertNull(
            SystemUiFocusAuthBypassPolicy.findWhitelistMethod(
                WrongSettings::class.java,
            ),
        )
    }
}
