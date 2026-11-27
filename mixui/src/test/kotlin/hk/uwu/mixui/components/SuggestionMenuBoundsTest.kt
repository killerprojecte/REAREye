package hk.uwu.mixui.components

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class SuggestionMenuBoundsTest {
    @Test fun dialogAndImeIntersectionBoundsSuggestions() {
        assertEquals(IntRect(20, 100, 380, 472), suggestionViewport(
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
    @Test fun shorterScrollablePanelPrefersBelowEvenWhenMoreSpaceExistsAbove() {
        val viewport = IntRect(24, 100, 376, 450)
        val anchor = IntRect(24, 300, 376, 350)
        val height = suggestionAvailableHeight(anchor, viewport, desired = 200, minimumRow = 64, gap = 6)
        assertEquals(94, height)
        assertEquals(IntOffset(24, 356), suggestionPosition(anchor, viewport,
            IntSize(352, height), LayoutDirection.Ltr, gap = 6))
    }
    @Test fun insufficientRoomForOneCandidateFallsBackAboveWithAGap() {
        val viewport = IntRect(24, 100, 376, 450)
        val anchor = IntRect(24, 340, 376, 400)
        val height = suggestionAvailableHeight(anchor, viewport, desired = 200, minimumRow = 64, gap = 6)
        assertEquals(200, height)
        assertEquals(IntOffset(24, 134), suggestionPosition(anchor, viewport,
            IntSize(352, height), LayoutDirection.Ltr, gap = 6))
    }
    @Test fun formEdgesDoNotShrinkAnAlreadyInsetEditor() {
        assertEquals(IntRect(24, 100, 376, 700), suggestionViewport(
            IntRect(0, 0, 400, 800), IntRect(24, 100, 376, 700), 8))
    }
    @Test fun fullyObscuredOrTinyViewportDoesNotOfferNegativePanelHeight() {
        assertEquals(0, suggestionAvailableHeight(IntRect(24, 400, 376, 450),
            IntRect(24, 400, 376, 400), desired = 200, minimumRow = 64, gap = 6))
        assertEquals(12, suggestionAvailableHeight(IntRect(24, 100, 376, 150),
            IntRect(24, 100, 376, 168), desired = 200, minimumRow = 64, gap = 6))
    }
    @Test fun obscuredDialogHasNoNegativeAvailableSize() {
        val bounds = suggestionViewport(IntRect(0, 0, 400, 300), IntRect(20, 400, 380, 700), 8)
        assertTrue(bounds.width >= 0)
        assertEquals(0, bounds.height)
    }
}
