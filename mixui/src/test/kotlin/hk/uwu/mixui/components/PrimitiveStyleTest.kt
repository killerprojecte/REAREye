package hk.uwu.mixui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PrimitiveStyleTest {
    @Test fun compactActionKeepsFortyDpMinimum() {
        assertEquals(40.dp, IconButtonDefaults.MinHeight)
        assertEquals(40.dp, IconButtonDefaults.MinWidth)
        assertEquals(40.dp, IconButtonDefaults.CornerRadius)
    }
    @Test fun cardKeepsUnpaddedSixteenDpShape() {
        assertEquals(16.dp, CardDefaults.CornerRadius)
        assertEquals(0.dp, CardDefaults.InsideMargin.calculateTopPadding())
        assertEquals(0.dp, CardDefaults.InsideMargin.calculateBottomPadding())
    }
    @Test fun cardPalettePreservesBothIndependentChannels() {
        val palette = CardColors(Color.Red, Color.White)
        assertEquals(Color.Red, palette.color)
        assertEquals(Color.White, palette.contentColor)
    }
}
