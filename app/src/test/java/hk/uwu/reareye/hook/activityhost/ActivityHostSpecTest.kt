package hk.uwu.reareye.hook.activityhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ActivityHostSpecTest {
    @Test
    fun defaultsAndExplicitComponentsAreValid() {
        val spec = ActivityHostSpec("com.example/.MainActivity")
        assertTrue(spec.validate().isEmpty())
        assertEquals(24f, spec.navigationGutterDp, 0f)
        assertEquals(
            ActivityHostComponent("com.example", "com.example.MainActivity"),
            ActivityHostSpec.parseComponent(spec.component)
        )
        assertNotNull(ActivityHostSpec.parseComponent("com.example/com.other.Main\$Inner"))
    }

    @Test
    fun actionsMalformedAndImplicitComponentsAreRejected() {
        listOf(
            "", "android.intent.action.MAIN", "com.example/", "/.Main", "com..example/.Main",
            "com.example/.Main/Other", " com.example/.Main", "com.example/.123", "com.example/a b"
        )
            .forEach { assertNull(it, ActivityHostSpec.parseComponent(it)) }
    }

    @Test
    fun dpiAndGuttersHaveStrictLimits() {
        listOf(0, 120, 960).forEach {
            assertTrue(
                ActivityHostSpec("a/.B", it).validate().isEmpty()
            )
        }
        listOf(-1, 1, 119, 961, Int.MAX_VALUE).forEach {
            assertFalse(ActivityHostSpec("a/.B", it).validate().isEmpty())
        }
        assertTrue(ActivityHostSpec("a/.B", navigationGutterDp = 0f).validate().isEmpty())
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertFalse(ActivityHostSpec("a/.B", navigationGutterDp = it).validate().isEmpty())
        }
    }

    @Test
    fun numericBoundsParseAndValidate() {
        assertEquals(listOf(0f, 2.5f, 100f, 40f), ActivityHostSpec.parseBounds(" 0, 2.5,1e2,40 "))
        assertTrue(ActivityHostSpec("a/.B", bounds = "0,2.5,100,40").validate().isEmpty())
        assertNull(ActivityHostSpec("a/.B").bounds)
    }

    @Test
    fun invalidBoundsNeverBecomeUnboundedFallback() {
        listOf(
            "", "0,0,1", "0,0,1,1,2", "-1,0,2,2", "0,-1,2,2", "1,0,1,2",
            "0,2,1,1", "0,0,NaN,1", "0,0,Infinity,1", "0,0,1e100,1", "0,0,#w,2",
            "0,0,,2"
        ).forEach { value ->
            assertFalse(value, ActivityHostSpec("a/.B", bounds = value).validate().isEmpty())
            try {
                ActivityHostSpec.parseBounds(value)
                fail("accepted invalid bounds: $value")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
