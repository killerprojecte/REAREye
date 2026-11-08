package hk.uwu.mixui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonStyleTest {
    @Test fun layoutDefaultsPreserveExistingMiuixButtonFootprint() {
        assertEquals(58.dp, ButtonDefaults.MinWidth)
        assertEquals(40.dp, ButtonDefaults.MinHeight)
        assertEquals(16.dp, ButtonDefaults.CornerRadius)
        assertEquals(16.dp, ButtonDefaults.InsideMargin.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, ButtonDefaults.InsideMargin.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(13.dp, ButtonDefaults.InsideMargin.calculateTopPadding())
        assertEquals(13.dp, ButtonDefaults.InsideMargin.calculateBottomPadding())
    }

    @Test fun colorsKeepEnabledAndDisabledChannelsDistinct() {
        val colors = ButtonColors(Color.Red, Color.Gray, Color.White, Color.Black)
        assertEquals(Color.Red, colors.color)
        assertEquals(Color.Gray, colors.disabledColor)
        assertEquals(Color.White, colors.contentColor)
        assertEquals(Color.Black, colors.disabledContentColor)
    }
}
