package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.graphics.Rect

/** Validates physical TaskView bounds while allowing WM Shell to clip offscreen coordinates. */
internal object AppEmbedTaskBoundsValidator {
    /**
     * Requires a positive, unscaled physical size that exactly matches the supplied absolute Rect.
     *
     * The Rect origin is intentionally unrestricted because TaskView content may be translated
     * partially or fully outside its display while scrolling. Long arithmetic rejects wrapped Int
     * differences instead of mistaking them for valid dimensions.
     */
    fun requireValid(widthPx: Int, heightPx: Int, bounds: Rect) {
        require(widthPx > 0 && heightPx > 0) { "AppEmbed dimensions must be positive" }
        val boundsWidth = bounds.right.toLong() - bounds.left.toLong()
        val boundsHeight = bounds.bottom.toLong() - bounds.top.toLong()
        require(boundsWidth > 0L && boundsHeight > 0L) {
            "AppEmbed bounds must have positive width and height"
        }
        require(boundsWidth == widthPx.toLong() && boundsHeight == heightPx.toLong()) {
            "ViewHost size must equal task bounds; surface/input scaling is unsupported"
        }
    }
}
