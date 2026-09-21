package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import android.content.ComponentName
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppEmbedSpecTest {
    @Test
    fun parsesValidatedConfigurationAndBuildsExplicitLaunchIntent() {
        val spec = parse(
            """
            <AppEmbed
                package="com.example.player"
                class=".PlayerActivity${'$'}Panel"
                action="com.example.player.OPEN"
                data="content://com.example.player/item/7"
                flags="0x10008000"
                touchable="false"
                bounds="10,20,1010,620"
            />
            """.trimIndent()
        )

        assertEquals("com.example.player", spec.packageName)
        assertEquals("com.example.player.PlayerActivity\$Panel", spec.className)
        assertEquals("com.example.player.OPEN", spec.action)
        assertEquals("content://com.example.player/item/7", spec.data.toString())
        assertEquals(0x10008000, spec.flags)
        assertFalse(spec.touchable)
        assertNull(spec.contentWidthPx)
        assertNull(spec.contentHeightPx)
        assertNull(spec.densityDpi)
        assertEquals(Rect(10, 20, 1010, 620), spec.taskBoundsOverride)

        val intent = spec.buildLaunchIntent()
        assertEquals(
            ComponentName("com.example.player", "com.example.player.PlayerActivity\$Panel"),
            intent.component,
        )
        assertEquals("com.example.player.OPEN", intent.action)
        assertEquals("content://com.example.player/item/7", intent.data.toString())
        assertEquals(0x10008000, intent.flags)
    }

    @Test
    fun parsesAliasesAndQualifiesShortActivityName() {
        val spec = parse(
            """
            <AppEmbed
                packageName="com.example.reader"
                activity="ReaderActivity"
                uri="https://example.com/book"
                density="560"
                touchable="1"
            />
            """.trimIndent()
        )

        assertEquals("com.example.reader.ReaderActivity", spec.className)
        assertFalse(spec.touchable)
        assertEquals("https://example.com/book", spec.data.toString())
        assertEquals(560, spec.densityDpi)
    }

    @Test
    fun parsesExplicitDpiWithoutChangingPhysicalContentSize() {
        val spec = parse(
            "<AppEmbed package=\"com.example.app\" class=\".MainActivity\" dpi=\"420\"/>"
        )

        assertEquals(420, spec.densityDpi)
        assertEquals(AppEmbedContentSize(640, 480), spec.resolveContentSize(640, 480))
    }

    @Test
    fun omittedSizingUsesCurrentSurfaceDimensionsAndBounds() {
        val spec = parse("<AppEmbed package=\"com.example.app\" class=\".MainActivity\"/>")
        val viewBounds = Rect(20, 30, 220, 330)

        assertEquals(AppEmbedContentSize(640, 480), spec.resolveContentSize(640, 480))
        val resolvedBounds = spec.resolveTaskBounds(viewBounds)
        assertEquals(viewBounds, resolvedBounds)
        assertFalse(viewBounds === resolvedBounds)
        resolvedBounds.offset(100, 100)
        assertEquals(Rect(20, 30, 220, 330), spec.resolveTaskBounds(viewBounds))
        assertNull(spec.taskBoundsOverride)
    }

    @Test
    fun explicitBoundsAreCopiedForEveryResolution() {
        val spec = parse(
            "<AppEmbed package=\"com.example.app\" class=\".MainActivity\" bounds=\"1 2 301 402\"/>"
        )

        val first = spec.resolveTaskBounds(Rect(10, 20, 30, 40))
        first.setEmpty()

        assertEquals(Rect(1, 2, 301, 402), spec.resolveTaskBounds(Rect(50, 60, 70, 80)))
    }

    @Test
    fun ratioGravityInsetsAndScaleMatchResolvedGeometry() {
        val view = Rect(-100, -50, 900, 750)
        val center = parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\" ratio=\"1\"/>")
        assertEquals(Rect(0, -50, 800, 750), center.resolveTaskBounds(view))

        val bottomRight = parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\" ratio=\"1\" gravity=\"bottom_right\"/>")
        assertEquals(Rect(100, -50, 900, 750), bottomRight.resolveTaskBounds(view))

        val inset = parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\" insetLeft=\"10\" insetTop=\"20\" insetRight=\"30\" insetBottom=\"40\"/>")
        assertEquals(Rect(-90, -30, 870, 710), inset.resolveTaskBounds(view))

        val scaled = parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\" ratio=\"1\" scale=\"0.5\"/>")
        assertEquals(Rect(200, 150, 600, 550), scaled.resolveTaskBounds(view))
    }

    @Test
    fun geometryInputValidationFailsFastAndPreservesNegativeCoordinates() {
        listOf("oops", "1px").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\" insetLeft=\"$value\"/>")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\" insetTop=\"-1\"/>")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\" gravity=\"diagonal\"/>")
        }
        val spec = parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\" ratio=\"2\" gravity=\"bottom_right\"/>")
        assertEquals(Rect(-100, 250, 900, 750), spec.resolveTaskBounds(Rect(-100, -50, 900, 750)))
    }

    @Test
    fun liveTaskBoundsPreserveSignedOffscreenCoordinates() {
        val spec = parse("<AppEmbed package=\"com.example.app\" class=\".MainActivity\"/>")
        val partiallyOffscreen = Rect(0, -552, 976, 44)
        val fullyOffscreen = Rect(-1200, -800, -224, -204)

        assertEquals(partiallyOffscreen, spec.resolveTaskBounds(partiallyOffscreen))
        assertEquals(fullyOffscreen, spec.resolveTaskBounds(fullyOffscreen))
    }

    @Test
    fun missingOrMalformedComponentFailsFast() {
        val invalidXml = listOf(
            "<AppEmbed class=\".MainActivity\"/>",
            "<AppEmbed package=\"com.example.app\"/>",
            "<AppEmbed package=\"example\" class=\"MainActivity\"/>",
            "<AppEmbed package=\"com.example-app\" class=\"MainActivity\"/>",
            "<AppEmbed package=\"com.example.app\" class=\"Main-Activity\"/>",
        )

        invalidXml.forEach { xml ->
            assertThrows(IllegalArgumentException::class.java) { parse(xml) }
        }
    }

    @Test
    fun malformedFlagsAndTouchableFailFast() {
        listOf("0x", "0xZZ", "2147483648", "12px").forEach { flags ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(
                    "<AppEmbed package=\"com.example.app\" class=\"MainActivity\" flags=\"$flags\"/>"
                )
            }
        }
        listOf("yes", "enabled", "2").forEach { touchable ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(
                    "<AppEmbed package=\"com.example.app\" class=\"MainActivity\" touchable=\"$touchable\"/>"
                )
            }
        }
    }

    @Test
    fun sameDisplayBackendRejectsCustomDimensions() {
        val invalidSizing = listOf(
            "dw=\"100\"",
            "dh=\"100\"",
            "dw=\"100\" dh=\"200\"",
            "dw=\"0\" dh=\"100\"",
            "dw=\"100\" dh=\"-1\"",
            "dw=\"wide\" dh=\"100\"",
            "dw=\"100\" dh=\"100\" dpi=\"420\"",
        )

        invalidSizing.forEach { attributes ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(
                    "<AppEmbed package=\"com.example.app\" class=\"MainActivity\" $attributes/>"
                )
            }
        }
        val spec = parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\"/>")
        assertThrows(IllegalArgumentException::class.java) { spec.resolveContentSize(0, 200) }
        assertThrows(IllegalArgumentException::class.java) { spec.resolveContentSize(100, -1) }
    }

    @Test
    fun omittedDensityInheritsDisplayAndInvalidDensityFailsFast() {
        val inherited = parse(
            "<AppEmbed package=\"com.example.app\" class=\"MainActivity\"/>"
        )
        assertNull(inherited.densityDpi)

        listOf("0", "-1", "dense", "2147483648").forEach { density ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(
                    "<AppEmbed package=\"com.example.app\" class=\"MainActivity\" dpi=\"$density\"/>"
                )
            }
        }
    }

    @Test
    fun boundsRequireExactlyFourIntegersAndPositiveArea() {
        val invalidBounds = listOf(
            "1,2,3",
            "1,2,3,4,5",
            "1,top,100,200",
            "0,0,0,100",
            "0,0,100,0",
            "100,100,10,10",
        )

        invalidBounds.forEach { bounds ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(
                    "<AppEmbed package=\"com.example.app\" class=\"MainActivity\" bounds=\"$bounds\"/>"
                )
            }
        }

        val spec = parse("<AppEmbed package=\"com.example.app\" class=\"MainActivity\"/>")
        assertThrows(IllegalArgumentException::class.java) {
            spec.resolveTaskBounds(Rect(0, 0, 0, 100))
        }
    }

    private fun parse(xml: String): AppEmbedSpec {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        return AppEmbedSpec.parse(document.documentElement)
    }
}
