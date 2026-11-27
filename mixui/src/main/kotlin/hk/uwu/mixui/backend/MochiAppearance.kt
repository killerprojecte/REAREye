package hk.uwu.mixui.backend

import hk.uwu.mixui.MixMochiStyle
import ui.feice.mochi.ios.IosPalette
import ui.feice.mochi.ios.iosColorScheme
import ui.feice.mochi.ios.iosControlPalette
import ui.feice.mochi.nothing.nothingColorScheme
import ui.feice.mochi.nothing.nothingControlPalette
import ui.feice.mochi.nothing.nothingTextStyles
import ui.feice.mochi.theme.MochiColorScheme
import ui.feice.mochi.theme.MochiControlPalette
import ui.feice.mochi.theme.MochiTextStyles
import ui.feice.mochi.theme.defaultTextStyles

internal data class MochiAppearance(
    val colors: MochiColorScheme,
    val textStyles: MochiTextStyles,
    val controls: MochiControlPalette?,
    val iosPalette: IosPalette? = null,
)

/**
 * The same public tokens as MochiTheme, MochiIosTheme and MochiNothingTheme, using
 * Maven artifacts only. One shared host preserves composition state and leaves
 * system bars under AppTheme's control. Monet affects only the default appearance.
 */
internal fun mochiAppearance(style: MixMochiStyle, defaultPalette: MochiColorScheme): MochiAppearance =
    when (style) {
        MixMochiStyle.Default -> MochiAppearance(defaultPalette, defaultTextStyles(), null)
        MixMochiStyle.Ios -> MochiAppearance(
            colors = iosColorScheme(defaultPalette.isDark),
            textStyles = defaultTextStyles(),
            controls = iosControlPalette(defaultPalette.isDark),
            iosPalette = IosPalette.of(defaultPalette.isDark),
        )
        MixMochiStyle.Nothing -> MochiAppearance(
            colors = nothingColorScheme(defaultPalette.isDark),
            textStyles = nothingTextStyles(),
            controls = nothingControlPalette(defaultPalette.isDark),
        )
    }
