package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivityHostElementFactoryTest {
    private fun xml(value: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(value.byteInputStream()).documentElement

    @Test
    fun fixedConfigurationKeepsDensitySeparateFromMamlScale() {
        val input =
            ActivityHostElementFactory.parse("example/.Main", "320", "0,0,100,80", "24", "200")
        assertTrue(input.validationErrors.isEmpty())
        assertEquals(320, input.dpi)
        assertEquals(1f, input.mamlScale, 0f)
        assertEquals(200f, input.carrierWidth!!, 0f)
        assertEquals(listOf(0f, 0f, 100f, 80f), input.bounds)
    }

    @Test
    fun invalidBoundsNeverSilentlyBecomeFullContent() {
        val input = ActivityHostElementFactory.parse("example/.Main", "0", "0,0,NaN,80", "24")
        assertTrue(input.validationErrors.isNotEmpty())
        assertNull(input.bounds)
        assertTrue(
            ActivityHostElementFactory.parse(
                "implicit",
                "abc",
                "",
                "0"
            ).validationErrors.size >= 2
        )
    }

    @Test
    fun controlsAreFixedBooleanAndZeroGutterIsExplicitlyValid() {
        val factory = ActivityHostElementFactory()
        fun sample(attrs: String) = factory.prepare(
            xml("<ActivityHost component='example/.Main' $attrs/>"),
            MamlStringExpressionCompiler { error("fixed controls must not compile expressions") }
        ).evaluateInputs().activityHost!!

        val fullscreen = sample("showControls='false' navigationGutterDp='0'")
        assertFalse(fullscreen.showControls)
        assertEquals(0f, fullscreen.gutterDp, 0f)
        assertTrue(fullscreen.validationErrors.isEmpty())
        assertTrue(sample("").showControls)
        assertEquals(24f, sample("").gutterDp, 0f)
        assertTrue(sample("showControls='0'").validationErrors.isNotEmpty())
        assertTrue(sample("showControlsExp='false'").validationErrors.isNotEmpty())
        assertTrue(sample("navigationGutterDp='-1'").validationErrors.isNotEmpty())
        val element = xml("<ActivityHost component='example/.Main' showControls='false'/>")
        val binding =
            factory.prepare(element, MamlStringExpressionCompiler { error("no expressions") })
        assertFalse(binding.evaluateInputs().activityHost!!.showControls)
        element.setAttribute("showControls", "true")
        assertTrue(binding.evaluateInputs().activityHost!!.showControls)
    }

    @Test
    fun expressionsAreOnlyEvaluatedWhenSampling() {
        val calls = mutableListOf<String>()
        val values = mutableMapOf("c" to "example/.Main", "d" to "240", "b" to "0,0,120,80")
        val binding = ActivityHostElementFactory().prepare(
            xml("<ActivityHost componentExp='c' dpiExp='d' boundsExp='b' x='1' y='2' w='200' h='100'/>"),
            MamlStringExpressionCompiler { source ->
                calls += "compile:$source"
                val evaluator: () -> String? = { calls += "evaluate:$source"; values[source] }
                evaluator
            },
        )
        assertEquals(listOf("compile:c", "compile:d", "compile:b"), calls)
        val sampled = binding.evaluateInputs()
        assertEquals("", sampled.key)
        assertEquals("", sampled.provider)
        assertEquals(240, sampled.activityHost?.dpi)
        assertEquals("example/.Main", sampled.activityHost?.component)
        values["d"] = "360"
        assertEquals(360, binding.evaluateInputs().activityHost?.dpi)
        assertEquals(240, sampled.activityHost?.dpi) // immutable click/input snapshot
    }

    @Test
    fun numericCarrierWidthAndDpiExpressionsRecoverLogicalUnits() {
        val values = mutableMapOf("safeW" to 240.0, "density" to 320.0)
        var evaluations = 0
        val compiler = object : MamlStringExpressionCompiler, MamlNumericExpressionCompiler {
            override fun compile(expression: String): () -> String? =
                { error("numeric expression used as string") }

            override fun compileNumber(expression: String): () -> Double? = {
                evaluations++; values[expression]
            }
        }
        val binding = ActivityHostElementFactory().prepare(
            xml("<ActivityHost component='example/.Main' dpiExp='density' w='safeW' bounds='0,0,100,80'/>"),
            compiler
        )
        assertEquals(0, evaluations)
        val first = binding.evaluateInputs().activityHost!!
        assertEquals(240f, first.carrierWidth)
        assertEquals(320, first.dpi)
        assertTrue(first.validationErrors.isEmpty())
        values["safeW"] = 280.0
        assertEquals(280f, binding.evaluateInputs().activityHost?.carrierWidth)
        values.remove("safeW")
        assertTrue(binding.evaluateInputs().activityHost!!.validationErrors.any { "scale" in it })
        values["safeW"] = Double.NaN
        assertTrue(binding.evaluateInputs().activityHost!!.validationErrors.any { "scale" in it })
    }

    @Test
    fun componentDpiAndBoundsFixedAndExpressionFormsAreMutuallyExclusive() {
        val binding = ActivityHostElementFactory().prepare(
            xml(
                "<ActivityHost component='example/.Main' componentExp='c' dpi='240' dpiExp='d' bounds='0,0,10,10' boundsExp='b'/>"
            ),
            MamlStringExpressionCompiler { source ->
                {
                    when (source) {
                        "c" -> "example/.Main"; "d" -> "320"; else -> "0,0,20,20"
                    }
                }
            })
        val errors = binding.evaluateInputs().activityHost!!.validationErrors
        assertEquals(3, errors.count { "mutually exclusive" in it })
    }

    @Test
    fun ordinaryWidgetInputsRemainUnchanged() {
        val legacy = MamlBackendInputs("key", "provider", true, "click")
        assertNull(legacy.activityHost)
        assertEquals("key", legacy.key)
        assertEquals("provider", legacy.provider)
    }

    @Test
    fun lifecycleAndInputUpdatesDoNotDiscoverBrokerWithoutClick() {
        val context = RuntimeEnvironment.getApplication()
        val before = shadowOf(context).broadcastIntents.size
        val backend =
            ActivityHostElementFactory().create(xml("<ActivityHost component='example/.Main'/>"))
        backend.createView(context)
        backend.onInit(); backend.onResume(); backend.onVisibilityChanged(true)
        backend.onInputsChanged(
            MamlBackendInputs(
                "", "", true,
                activityHost = ActivityHostInputs("example/.Main", 320)
            )
        )
        backend.onPause(); backend.onAttachmentChanged(false); backend.onFinish()
        assertEquals(before, shadowOf(context).broadcastIntents.size)
    }

    @Test
    fun staleGenerationSequenceAndBoundsNeverOpenInput() {
        val gate = ActivityHostInputGate()
        val bounds = Rect(24, 0, 200, 100)
        gate.submit(2, 10, bounds)
        assertFalse(gate.acknowledge(1, 10, bounds))
        assertFalse(gate.acknowledge(2, 9, bounds))
        assertFalse(gate.acknowledge(2, 10, Rect(24, 0, 201, 100)))
        assertFalse(gate.allows(true, true, false))
        assertTrue(gate.acknowledge(2, 10, bounds))
        assertTrue(gate.allows(true, true, false))
        assertFalse(gate.allows(false, true, false))
        assertFalse(gate.allows(true, false, false))
        assertFalse(gate.allows(true, true, true))
        gate.submit(2, 11, bounds)
        assertFalse(gate.allows(true, true, false))
        gate.revoke()
        assertFalse(gate.allows(true, true, false))
    }
}
