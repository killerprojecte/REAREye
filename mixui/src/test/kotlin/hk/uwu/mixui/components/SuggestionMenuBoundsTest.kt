package hk.uwu.mixui.components

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class SuggestionMenuBoundsTest {
    @Test fun dialogAndImeIntersectionBoundsSuggestions() {
        assertEquals(IntRect(28, 108, 372, 472), suggestionViewport(
            IntRect(0, 0, 400, 480), IntRect(20, 100, 380, 700), 8))
    }
    @Test fun nearImeMovesAboveAndRtlAnchorsAtEnd() {
        val viewport = IntRect(28, 108, 372, 472)
        val anchor = IntRect(50, 390, 350, 440)
        assertEquals(IntOffset(50, 240), suggestionPosition(anchor, viewport,
            IntSize(288, 150), LayoutDirection.Ltr))
        assertEquals(IntOffset(62, 240), suggestionPosition(anchor, viewport,
            IntSize(288, 150), LayoutDirection.Rtl))
    }
    @Test fun belowAndNarrowWindowsClampHorizontally() {
        assertEquals(IntOffset(8, 90), suggestionPosition(IntRect(30, 40, 180, 90),
            IntRect(8, 8, 192, 400), IntSize(184, 150), LayoutDirection.Ltr))
    }
    @Test fun clippedOrOffscreenEditorDoesNotLeaveDetachedPopup() {
        val viewport = IntRect(28, 108, 372, 472)
        assertFalse(suggestionAnchorVisible(IntRect(50, 108, 350, 108), viewport))
        assertFalse(suggestionAnchorVisible(IntRect(50, 500, 350, 550), viewport))
        assertTrue(suggestionAnchorVisible(IntRect(50, 90, 350, 130), viewport))
    }
    @Test fun obscuredDialogHasNoNegativeAvailableSize() {
        val bounds = suggestionViewport(IntRect(0, 0, 400, 300), IntRect(20, 400, 380, 700), 8)
        assertTrue(bounds.width >= 0)
        assertEquals(0, bounds.height)
    }
}
