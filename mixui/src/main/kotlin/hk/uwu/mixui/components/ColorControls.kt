package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.R
import hk.uwu.mixui.theme.MixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Inline, caller-owned color selection. Changes are immediate, not a confirm/cancel transaction. */
@Composable
fun ColorPalette(color: Color, onColorChanged: (Color) -> Unit, modifier: Modifier = Modifier) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.basic.ColorPalette(
            color = color, onColorChanged = onColorChanged, modifier = modifier,
        )
        MixUiBackend.Mochi -> InlineColorPalette(color, onColorChanged, modifier)
    }
}

/**
 * Inline HSV + alpha controls. Mochi 0.2 exposes only a color well/sheet publicly,
 * so its inline adapter composes native Mochi sliders without opening a nested sheet.
 * The enclosing dialog and its dismissal/confirmation remain entirely caller-owned.
 */
@Composable
fun ColorPicker(color: Color, onColorChanged: (Color) -> Unit, modifier: Modifier = Modifier) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.basic.ColorPicker(
            color = color, onColorChanged = onColorChanged, modifier = modifier,
        )
        MixUiBackend.Mochi -> InlineColorPicker(color, onColorChanged, modifier)
    }
}

@Composable
private fun InlineColorPalette(color: Color, onColorChanged: (Color) -> Unit, modifier: Modifier) {
    val swatches = remember { paletteSwatches() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorPreview(color)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            swatches.chunked(13).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { swatch ->
                        val isSelected = color.copy(alpha = 1f).toArgb() == swatch.toArgb()
                        val shape = RoundedCornerShape(3.dp)
                        Box(
                            Modifier.weight(1f).height(24.dp).clip(shape)
                                .background(swatch)
                                .border(if (isSelected) 2.dp else 0.dp, MixTheme.colorScheme.onSurface, shape)
                                .semantics {
                                    selected = isSelected
                                    contentDescription = colorHex(swatch)
                                }
                                .clickable(role = Role.Button) {
                                    onColorChanged(paletteSelection(swatch, color))
                                },
                        )
                    }
                }
            }
        }
        ColorChannel(stringResource(R.string.mixui_color_alpha), color.alpha, 1f) { onColorChanged(color.copy(alpha = it)) }
    }
}

@Composable
private fun InlineColorPicker(color: Color, onColorChanged: (Color) -> Unit, modifier: Modifier) {
    var channels by remember { mutableStateOf(HsvChannels.from(color)) }
    var lastExternal by remember { mutableStateOf(color.toArgb()) }
    SideEffect {
        // Ignore echoes of our callback: hue must survive zero saturation/brightness.
        if (lastExternal != color.toArgb()) {
            lastExternal = color.toArgb()
            if (channels.color().toArgb() != color.toArgb()) channels = HsvChannels.from(color)
        }
    }
    fun change(next: HsvChannels) {
        channels = next
        onColorChanged(next.color())
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorPreview(color)
        ColorChannel(stringResource(R.string.mixui_color_hue), channels.hue, 360f) { change(channels.copy(hue = it)) }
        ColorChannel(stringResource(R.string.mixui_color_saturation), channels.saturation, 1f) { change(channels.copy(saturation = it)) }
        ColorChannel(stringResource(R.string.mixui_color_brightness), channels.value, 1f) { change(channels.copy(value = it)) }
        ColorChannel(stringResource(R.string.mixui_color_alpha), channels.alpha, 1f) { change(channels.copy(alpha = it)) }
    }
}

@Composable
private fun ColorChannel(label: String, value: Float, maximum: Float, onChange: (Float) -> Unit) {
    Column {
        val amount = if (maximum == 360f) "${value.roundToInt()}°" else "${(value * 100).roundToInt()}%"
        Text("$label $amount", style = MixTheme.textStyles.body2)
        ui.feice.mochi.basic.MochiSlider(
            value = value, onValueChange = onChange, valueRange = 0f..maximum,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

@Composable
private fun ColorPreview(color: Color) {
    // A split light/dark backing makes transparency visible without changing the supplied color.
    val shape = RoundedCornerShape(13.dp)
    Box(Modifier.fillMaxWidth().height(26.dp).clip(shape).semantics { contentDescription = colorHex(color) }) {
        Row(Modifier.fillMaxWidth().height(26.dp)) {
            Box(Modifier.weight(1f).height(26.dp).background(Color.White))
            Box(Modifier.weight(1f).height(26.dp).background(Color.DarkGray))
        }
        Box(Modifier.fillMaxWidth().height(26.dp).background(color))
    }
}

internal fun paletteSelection(swatch: Color, current: Color): Color = swatch.copy(alpha = current.alpha)

internal fun colorHex(color: Color): String = "#" + color.toArgb().toUInt().toString(16).padStart(8, '0').uppercase()

/** Original mixui swatches, not a copy of either vendor's private grid implementation. */
internal fun paletteSwatches(): List<Color> = (0..6).flatMap { row ->
    (0..12).map { column ->
        if (column == 12) Color.hsv(0f, 0f, 1f - row / 6f)
        else Color.hsv(column * 30f, if (row < 3) (row + 1) / 4f else 1f, if (row <= 3) 1f else 1f - (row - 3) / 4f)
    }
}

internal data class HsvChannels(val hue: Float, val saturation: Float, val value: Float, val alpha: Float) {
    fun color(): Color = Color.hsv(hue, saturation, value, alpha)

    companion object {
        fun from(color: Color): HsvChannels {
            val high = maxOf(color.red, color.green, color.blue)
            val low = minOf(color.red, color.green, color.blue)
            val delta = high - low
            val sector = when {
                abs(delta) < 0.000001f -> 0f
                high == color.red -> ((color.green - color.blue) / delta) % 6f
                high == color.green -> (color.blue - color.red) / delta + 2f
                else -> (color.red - color.green) / delta + 4f
            }
            return HsvChannels((sector * 60f + 360f) % 360f, if (high == 0f) 0f else delta / high, high, color.alpha)
        }
    }
}
