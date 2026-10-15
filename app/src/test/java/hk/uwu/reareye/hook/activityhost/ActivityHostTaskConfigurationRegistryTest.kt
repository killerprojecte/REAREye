package hk.uwu.reareye.hook.activityhost

import org.junit.Assert.*
import org.junit.Test

class ActivityHostTaskConfigurationRegistryTest {
    private fun spec(dpi: Int = 320, component: String = "test.app/test.app.Main") =
        ActivityHostTaskConfigurationRegistry.Spec(component, 1, dpi, 0, 0, 480, 800)

    @Test
    fun exactCookieComponentAndDisplayRequired() {
        val registry = ActivityHostTaskConfigurationRegistry<Any, Any>()
        val cookie = Any();
        val task = Any()
        registry.register(cookie, Any(), 1000, spec())
        assertNull(registry.resolve(task, listOf(Any()), spec().component, 1))
        assertNull(registry.resolve(task, listOf(cookie), "test.app/test.app.Other", 1))
        assertNull(registry.resolve(task, listOf(cookie), spec().component, 0))
        assertEquals(320, registry.resolve(task, listOf(cookie), spec().component, 1)?.dpi)
        assertEquals(320, registry.resolve(task, emptyList(), "foreign/Other", 1)?.dpi)
        assertNull(registry.resolve(Any(), listOf(cookie), spec().component, 1))
    }

    @Test
    fun idempotentButStaleCookieReplacementRejected() {
        val registry = ActivityHostTaskConfigurationRegistry<Any, Any>()
        val cookie = Any();
        val lifetime = Any()
        assertTrue(registry.register(cookie, lifetime, 1000, spec()))
        assertFalse(registry.register(cookie, lifetime, 1000, spec()))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(
                cookie,
                Any(),
                1000,
                spec()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(
                cookie,
                lifetime,
                1001,
                spec()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(
                cookie,
                lifetime,
                1000,
                spec(480)
            )
        }
    }

    @Test
    fun taskIdentityNotEqualsAndAmbiguousCookiesFailClosed() {
        data class Task(val id: Int)

        val registry = ActivityHostTaskConfigurationRegistry<Any, Any>()
        val first = Any();
        val second = Any()
        registry.register(first, Any(), 1000, spec())
        registry.register(second, Any(), 1000, spec(480))
        val task = Task(1)
        assertNull(registry.resolve(task, listOf(first, second), spec().component, 1))
        assertEquals(320, registry.resolve(task, listOf(first), spec().component, 1)?.dpi)
        assertNull(registry.resolve(Task(1), emptyList(), spec().component, 1))
    }

    @Test
    fun lifetimeDeathClearsEveryCookieAndTaskButNotOtherDpi() {
        val registry = ActivityHostTaskConfigurationRegistry<Any, Any>()
        val lifetime = Any();
        val a = Any();
        val b = Any();
        val c = Any()
        val taskA = Any();
        val taskB = Any();
        val taskC = Any()
        registry.register(a, lifetime, 1000, spec(240))
        registry.register(b, lifetime, 1000, spec(480))
        registry.register(c, Any(), 1000, spec(0))
        registry.resolve(taskA, listOf(a), spec().component, 1)
        registry.resolve(taskB, listOf(b), spec().component, 1)
        registry.resolve(taskC, listOf(c), spec().component, 1)
        assertEquals(setOf(taskA, taskB), registry.lifetimeDied(lifetime).toSet())
        assertNull(registry.resolve(taskA, listOf(a), spec().component, 1))
        assertEquals(0, registry.resolve(taskC, emptyList(), spec().component, 1)?.dpi)
        assertTrue(registry.lifetimeDied(lifetime).isEmpty())
    }

    @Test
    fun unregisterValidatesOwnerAndReleasesForSystemSemantics() {
        val registry = ActivityHostTaskConfigurationRegistry<Any, Any>()
        val cookie = Any();
        val task = Any()
        registry.register(cookie, Any(), 1000, spec())
        registry.resolve(task, listOf(cookie), spec().component, 1)
        assertThrows(IllegalArgumentException::class.java) { registry.unregister(cookie, 1001) }
        assertEquals(listOf(task), registry.unregister(cookie, 1000))
        assertNull(registry.resolve(task, listOf(cookie), spec().component, 1))
        assertTrue(registry.unregister(cookie, 1000).isEmpty())
        assertTrue(registry.isEmpty())
    }

    @Test
    fun boundedAndDpiRangeValidated() {
        val registry = ActivityHostTaskConfigurationRegistry<Any, Any>(1)
        registry.register(Any(), Any(), 1000, spec())
        assertThrows(IllegalStateException::class.java) {
            registry.register(
                Any(),
                Any(),
                1000,
                spec()
            )
        }
        assertThrows(IllegalArgumentException::class.java) { spec(119) }
        assertThrows(IllegalArgumentException::class.java) { spec(961) }
        assertEquals(120, spec(120).dpi)
        assertEquals(960, spec(960).dpi)
        registry.clear()
        assertTrue(registry.isEmpty())
    }

    @Test
    fun confirmationRequiresBoundTaskAndSuccessfulConfiguration() {
        val registry = ActivityHostTaskConfigurationRegistry<Any, Any>()
        val cookie = Any();
        val lifetime = Any();
        val task = Any()
        assertFalse(registry.isConfigured(cookie, 1000))
        registry.register(cookie, lifetime, 1000, spec())
        assertFalse(registry.isConfigured(cookie, 1000))
        assertFalse(registry.markConfigured(task))
        registry.resolve(task, listOf(cookie), spec().component, 1)
        assertFalse(registry.isConfigured(cookie, 1000))
        assertTrue(registry.markConfigured(task))
        assertTrue(registry.isConfigured(cookie, 1000))
        assertThrows(SecurityException::class.java) { registry.isConfigured(cookie, 1001) }
        registry.unregister(cookie, 1000)
        assertFalse(registry.isConfigured(cookie, 1000))
        assertFalse(registry.markConfigured(task))
        registry.register(cookie, lifetime, 1000, spec())
        assertFalse(registry.isConfigured(cookie, 1000))
    }

    @Test
    fun deathAndClearRemoveConfigurationConfirmation() {
        val registry = ActivityHostTaskConfigurationRegistry<Any, Any>()
        val cookie = Any();
        val lifetime = Any();
        val task = Any()
        registry.register(cookie, lifetime, 1000, spec(0))
        registry.resolve(task, listOf(cookie), spec().component, 1)
        registry.markConfigured(task)
        assertTrue(registry.isConfigured(cookie, 1000))
        registry.lifetimeDied(lifetime)
        assertFalse(registry.isConfigured(cookie, 1000))
        registry.register(cookie, lifetime, 1000, spec())
        registry.resolve(task, listOf(cookie), spec().component, 1)
        registry.markConfigured(task)
        registry.clear()
        assertFalse(registry.isConfigured(cookie, 1000))
    }

    @Test
    fun signedCoordinatesAndOverflowSafeDimensions() {
        val negative = spec().copy(left = -240, top = -400, right = 240, bottom = 400)
        assertEquals(-240, negative.left)
        assertEquals(480, negative.right - negative.left)
        assertThrows(IllegalArgumentException::class.java) {
            spec().copy(left = Int.MIN_VALUE, right = Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) { spec().copy(right = 1) }
        assertThrows(IllegalArgumentException::class.java) { spec().copy(bottom = 8193) }
    }
}
