package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import android.content.Context
import android.view.MotionEvent
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppEmbedPresentationTest {
    @Test
    fun nativeAlignmentAndCoordinateExpressionsReachMamlUnchanged() {
        val prepared = prepareAppEmbedXml(
            xml(
                "<AppEmbed align=' RIGHT ' alignV=' BOTTOM ' x='#screen_width-20' y='#screen_height' w='663' h='596'/>"
            )
        )
        assertEquals("right", prepared.getAttribute("align"))
        assertEquals("bottom", prepared.getAttribute("alignV"))
        assertEquals("#screen_width-20", prepared.getAttribute("x"))
        assertEquals("#screen_height", prepared.getAttribute("y"))
        assertEquals(
            "center",
            prepareAppEmbedXml(xml("<AppEmbed alignH='center'/>")).getAttribute("alignH")
        )
        assertThrows(IllegalArgumentException::class.java) {
            prepareAppEmbedXml(xml("<AppEmbed alignV='right'/>"))
        }
    }

    @Test
    fun backgroundLayerIsDefaultWithoutMutatingTheSourceDocument() {
        val xml = xml("<AppEmbed><Text color='#ff0000'/></AppEmbed>")
        val prepared = prepareAppEmbedXml(xml)
        assertEquals("bottom", prepared.getAttribute("layerType"))
        assertEquals("", xml.getAttribute("layerType"))
        assertEquals(1, prepared.getElementsByTagName("Text").length)
        xml.setAttribute("layerType", "top")
        assertEquals("top", prepareAppEmbedXml(xml).getAttribute("layerType"))
    }

    @Test
    fun finishThenReactivateCreatesFreshViewAndRetainsMamlTouchOwnership() {
        val context: Context = RuntimeEnvironment.getApplication()
        val delegate = AppEmbedBridge.create(
            context, xml(
                "<AppEmbed package='com.example.app' class='MainActivity' touchable='true'/>"
            )
        )
        val first = AppEmbedBridge.getView(delegate)
        AppEmbedBridge.onViewRemoved(delegate, first)
        AppEmbedBridge.onFinish(delegate)
        val second = AppEmbedBridge.getView(delegate)
        assertNotSame(first, second)
        assertFalse(second.isFocusable)
        val down = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        try {
            assertFalse(second.onTouchEvent(down))
        } finally {
            down.recycle()
            AppEmbedBridge.onFinish(delegate)
        }
    }

    private fun xml(source: String): Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(InputSource(StringReader(source))).documentElement
}
