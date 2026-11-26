package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.glassBottomSheetSurface
import top.yukonga.miuix.kmp.layout.BottomSheetSurfaceScope
import kotlin.math.roundToInt

/** Actual native panel geometry/state, not an independently animated approximation. */
internal class NativeSheetMaterialSource(val backdrop: LayerBackdrop) {
    var scope by mutableStateOf<BottomSheetSurfaceScope?>(null)
    private var panel: LayoutCoordinates? = null
    private var anchor: LayoutCoordinates? = null
    var origin by mutableStateOf<IntOffset?>(null)
        private set
    var windowOrigin by mutableStateOf<Offset?>(null)
        private set

    fun updatePanel(value: LayoutCoordinates?) {
        panel = value
        windowOrigin = value?.takeIf { it.isAttached }?.positionInWindow()
        updateOrigin()
    }
    fun updateAnchor(value: LayoutCoordinates?) { anchor = value; updateOrigin() }

    private fun updateOrigin() {
        val panel = panel
        val anchor = anchor
        val updated = if (panel != null && anchor != null && panel.isAttached && anchor.isAttached) {
            val offset = anchor.localPositionOf(panel)
            IntOffset(offset.x.roundToInt(), offset.y.roundToInt())
        } else null
        // Relative integer placement is the actual contract. A shared drag
        // translation cancels out, and unchanged callbacks cannot cause a loop.
        if (origin != updated) origin = updated
    }
    var size by mutableStateOf(IntSize.Zero)
}

/** Full-window source for a popup crossing the sheet edge; sheet controls still use the local layer. */
internal class NativeSheetPopupBackdrop(
    private val page: Backdrop,
    private val sheet: NativeSheetMaterialSource,
) : Backdrop {
    override val isCoordinatesDependent = true
    override val backdropCoordinates: LayoutCoordinates? get() = page.backdropCoordinates

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Float,
    ) {
        with(page) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        val consumer = coordinates?.takeIf { it.isAttached } ?: return
        val frame = backdropCoordinates ?: return
        val window = consumer.positionInWindow()
        val scrim = sheet.scope?.scrimColor?.value
        val scale = 1f / downscaleFactor
        if (scrim != null && scrim.alpha > 0f) {
            val origin = (frame.positionInWindow() - window) * scale
            drawRect(scrim, topLeft = origin, size = Size(frame.size.width * scale, frame.size.height * scale))
        }
        val panel = sheet.windowOrigin ?: return
        val layer = sheet.backdrop.graphicsLayer
        if (layer.size != sheet.size) return
        val offset = (panel - window) * scale
        // Use the actual panel origin, not the offscreen recording's position under
        // the header action. Popup glass has no separate backdrop layer transform.
        withTransform({
            translate(offset.x, offset.y)
            scale(scale, scale, Offset.Zero)
        }) { drawLayer(layer) }
    }
}

@Composable
internal fun rememberNativeSheetMaterialSource(): NativeSheetMaterialSource {
    val backdrop = rememberLayerBackdrop()
    return remember(backdrop) { NativeSheetMaterialSource(backdrop) }
}

/** The visible native surface always draws its foreground normally, exactly once. */
@Composable
internal fun rememberNativeSheetMaterialSurface(
    page: Backdrop?,
    source: NativeSheetMaterialSource,
): @Composable BottomSheetSurfaceScope.() -> Modifier {
    val nativeSurface = glassBottomSheetSurface(page, material = modalGlassMaterial())
    return {
        val panelScope = this
        val material = nativeSurface()
        if (isOverscroll) {
            // The extension must not replace the panel's scope, size or coordinates.
            material
        } else {
            SideEffect { source.scope = panelScope }
            DisposableEffect(source) { onDispose { source.updatePanel(null) } }
            Modifier.onSizeChanged { source.size = it }
                .onGloballyPositioned { source.updatePanel(it) }
                .then(material)
        }
    }
}

/**
 * A real, foreground-free material subtree, drawn before both native header
 * actions and body tabs. It occupies zero layout size and has no semantics,
 * pointer, focus or accessibility target. Its child is positioned at the actual
 * native panel origin within the SAME transformed panel; it follows drag/open/
 * predictive-Back translation without maintaining a second animation.
 *
 * Only the source tree is recorded offscreen. No visible foreground is captured,
 * suppressed or re-run, so graphics-layer caching cannot erase the header/body.
 */
@Composable
internal fun NativeSheetMaterialRecorder(page: Backdrop?, source: NativeSheetMaterialSource) {
    val nativeSurface = glassBottomSheetSurface(page, material = modalGlassMaterial())
    val scope = source.scope
    val size = source.size
    val discard = rememberGraphicsLayer()
    DisposableEffect(source) { onDispose { source.updateAnchor(null) } }
    Layout(
        modifier = Modifier.onGloballyPositioned { source.updateAnchor(it) },
        content = {
            if (scope != null && size.width > 0 && size.height > 0) {
                Box(Modifier
                    .drawWithContent {
                        // Do not composite this duplicate surface into the visible
                        // Sheet. The original native surface remains the only one
                        // seen; layerBackdrop records this empty material subtree.
                        discard.record { this@drawWithContent.drawContent() }
                    }
                    .layerBackdrop(source.backdrop)
                    .then(nativeSurface(scope)))
            }
        },
    ) { measurables, _ ->
        val child = measurables.singleOrNull()?.measure(Constraints.fixed(size.width, size.height))
        layout(0, 0) {
            val origin = source.origin
            if (child != null && origin != null) {
                child.place(origin.x, origin.y)
            }
        }
    }
}
