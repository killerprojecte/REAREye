package hk.uwu.mixui.components

import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.VisualTransformation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFieldRoutingTest {
    @Test fun ordinaryAndPasswordFieldsUseNativeMochi() {
        assertTrue(supportsNativeMochiTextField(false, 1, VisualTransformation.None))
        assertTrue(supportsNativeMochiTextField(false, 1, PasswordVisualTransformation()))
    }

    @Test fun readOnlyAndMinimumLineRequestsKeepCompatibilitySemantics() {
        assertFalse(supportsNativeMochiTextField(true, 1, VisualTransformation.None))
        assertFalse(supportsNativeMochiTextField(false, 3, VisualTransformation.None))
    }

    @Test fun customPasswordMaskKeepsCompatibilityRenderer() {
        assertFalse(supportsNativeMochiTextField(false, 1, PasswordVisualTransformation('*')))
    }

    @Test fun arbitraryVisualTransformationIsNeverDropped() {
        val custom = VisualTransformation { text -> TransformedText(text, OffsetMapping.Identity) }
        assertFalse(supportsNativeMochiTextField(false, 1, custom))
    }
}
