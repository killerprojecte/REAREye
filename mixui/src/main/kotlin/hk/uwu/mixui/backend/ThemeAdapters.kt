package hk.uwu.mixui.backend

import androidx.compose.ui.graphics.Color

import hk.uwu.mixui.theme.MixColors
import hk.uwu.mixui.theme.MixTextStyles

internal class MiuixColors(private val source: top.yukonga.miuix.kmp.theme.Colors) : MixColors {
    override val primary get() = source.primary
    override val onPrimary get() = source.onPrimary
    override val primaryVariant get() = source.primaryVariant
    override val onPrimaryVariant get() = source.onPrimaryVariant
    override val error get() = source.error
    override val onError get() = source.onError
    override val errorContainer get() = source.errorContainer
    override val onErrorContainer get() = source.onErrorContainer
    override val disabledPrimary get() = source.disabledPrimary
    override val disabledOnPrimary get() = source.disabledOnPrimary
    override val disabledPrimaryButton get() = source.disabledPrimaryButton
    override val disabledOnPrimaryButton get() = source.disabledOnPrimaryButton
    override val disabledPrimarySlider get() = source.disabledPrimarySlider
    override val primaryContainer get() = source.primaryContainer
    override val onPrimaryContainer get() = source.onPrimaryContainer
    override val secondary get() = source.secondary
    override val onSecondary get() = source.onSecondary
    override val secondaryVariant get() = source.secondaryVariant
    override val onSecondaryVariant get() = source.onSecondaryVariant
    override val disabledSecondary get() = source.disabledSecondary
    override val disabledOnSecondary get() = source.disabledOnSecondary
    override val disabledSecondaryVariant get() = source.disabledSecondaryVariant
    override val disabledOnSecondaryVariant get() = source.disabledOnSecondaryVariant
    override val secondaryContainer get() = source.secondaryContainer
    override val onSecondaryContainer get() = source.onSecondaryContainer
    override val secondaryContainerVariant get() = source.secondaryContainerVariant
    override val onSecondaryContainerVariant get() = source.onSecondaryContainerVariant
    override val tertiaryContainer get() = source.tertiaryContainer
    override val onTertiaryContainer get() = source.onTertiaryContainer
    override val tertiaryContainerVariant get() = source.tertiaryContainerVariant
    override val background get() = source.background
    override val onBackground get() = source.onBackground
    override val onBackgroundVariant get() = source.onBackgroundVariant
    override val surface get() = source.surface
    override val onSurface get() = source.onSurface
    override val surfaceVariant get() = source.surfaceVariant
    override val onSurfaceSecondary get() = source.onSurfaceSecondary
    override val onSurfaceVariantSummary get() = source.onSurfaceVariantSummary
    override val onSurfaceVariantActions get() = source.onSurfaceVariantActions
    override val disabledOnSurface get() = source.disabledOnSurface
    override val surfaceContainer get() = source.surfaceContainer
    override val onSurfaceContainer get() = source.onSurfaceContainer
    override val onSurfaceContainerVariant get() = source.onSurfaceContainerVariant
    override val surfaceContainerHigh get() = source.surfaceContainerHigh
    override val onSurfaceContainerHigh get() = source.onSurfaceContainerHigh
    override val surfaceContainerHighest get() = source.surfaceContainerHighest
    override val onSurfaceContainerHighest get() = source.onSurfaceContainerHighest
    override val outline get() = source.outline
    override val dividerLine get() = source.dividerLine
    override val windowDimming get() = source.windowDimming
    override val sliderKeyPoint get() = source.sliderKeyPoint
    override val sliderKeyPointForeground get() = source.sliderKeyPointForeground
    override val sliderBackground get() = source.sliderBackground
}

internal class MochiColors(
    private val source: ui.feice.mochi.theme.MochiColorScheme,
    // MochiColorScheme.scrim is an opaque pigment, not an overlay opacity.
    // Resolve MochiDefaults.scrim() under the active vendor theme.
    override val windowDimming: Color,
) : MixColors {
    override val primary get() = source.primary
    override val onPrimary get() = source.onPrimary
    override val primaryVariant get() = source.primaryContainer
    override val onPrimaryVariant get() = source.onPrimaryContainer
    override val error get() = source.error
    override val onError get() = source.onError
    override val errorContainer get() = source.errorContainer
    override val onErrorContainer get() = source.onErrorContainer
    override val disabledPrimary get() = source.onSurface.copy(alpha = 0.12f)
    override val disabledOnPrimary get() = source.onSurface.copy(alpha = 0.38f)
    override val disabledPrimaryButton get() = source.onSurface.copy(alpha = 0.12f)
    override val disabledOnPrimaryButton get() = source.onSurface.copy(alpha = 0.38f)
    override val disabledPrimarySlider get() = source.onSurface.copy(alpha = 0.12f)
    override val primaryContainer get() = source.primaryContainer
    override val onPrimaryContainer get() = source.onPrimaryContainer
    override val secondary get() = source.secondary
    override val onSecondary get() = source.onSecondary
    override val secondaryVariant get() = source.secondaryContainer
    override val onSecondaryVariant get() = source.onSecondaryContainer
    override val disabledSecondary get() = source.onSurface.copy(alpha = 0.12f)
    override val disabledOnSecondary get() = source.onSurface.copy(alpha = 0.38f)
    override val disabledSecondaryVariant get() = source.onSurface.copy(alpha = 0.12f)
    override val disabledOnSecondaryVariant get() = source.onSurface.copy(alpha = 0.38f)
    override val secondaryContainer get() = source.secondaryContainer
    override val onSecondaryContainer get() = source.onSecondaryContainer
    override val secondaryContainerVariant get() = source.surfaceContainerHigh
    override val onSecondaryContainerVariant get() = source.onSurfaceVariant
    override val tertiaryContainer get() = source.tertiaryContainer
    override val onTertiaryContainer get() = source.onTertiaryContainer
    override val tertiaryContainerVariant get() = source.tertiaryContainer
    override val background get() = source.background
    override val onBackground get() = source.onBackground
    override val onBackgroundVariant get() = source.onSurfaceVariant
    override val surface get() = source.surface
    override val onSurface get() = source.onSurface
    override val surfaceVariant get() = source.surfaceVariant
    override val onSurfaceSecondary get() = source.onSurfaceVariant
    override val onSurfaceVariantSummary get() = source.onSurfaceVariant
    override val onSurfaceVariantActions get() = source.primary
    override val disabledOnSurface get() = source.onSurface.copy(alpha = 0.38f)
    override val surfaceContainer get() = source.surfaceContainer
    override val onSurfaceContainer get() = source.onSurface
    override val onSurfaceContainerVariant get() = source.onSurfaceVariant
    override val surfaceContainerHigh get() = source.surfaceContainerHigh
    override val onSurfaceContainerHigh get() = source.onSurface
    override val surfaceContainerHighest get() = source.surfaceContainerHighest
    override val onSurfaceContainerHighest get() = source.onSurface
    override val outline get() = source.outline
    override val dividerLine get() = source.outlineVariant
    override val sliderKeyPoint get() = source.primary
    override val sliderKeyPointForeground get() = source.onPrimary
    override val sliderBackground get() = source.surfaceContainerHighest
}

internal class MiuixTextStyles(private val source: top.yukonga.miuix.kmp.theme.TextStyles) : MixTextStyles {
    override val main get() = source.main
    override val paragraph get() = source.paragraph
    override val body1 get() = source.body1
    override val body2 get() = source.body2
    override val button get() = source.button
    override val footnote1 get() = source.footnote1
    override val footnote2 get() = source.footnote2
    override val headline1 get() = source.headline1
    override val headline2 get() = source.headline2
    override val subtitle get() = source.subtitle
    override val title1 get() = source.title1
    override val title2 get() = source.title2
    override val title3 get() = source.title3
    override val title4 get() = source.title4
}

internal class MochiTextStyles(private val source: ui.feice.mochi.theme.MochiTextStyles) : MixTextStyles {
    override val main get() = source.body
    override val paragraph get() = source.bodyLarge
    override val body1 get() = source.body
    override val body2 get() = source.subhead
    override val button get() = source.labelLarge
    override val footnote1 get() = source.footnote
    override val footnote2 get() = source.labelSmall
    // These application roles are compact information labels (MIUIX 17/16sp),
    // not MD3 page headlines (32/28sp). Preserve each native family without doubling size.
    override val headline1 get() = source.navTitle
    override val headline2 get() = source.titleMedium
    override val subtitle get() = source.titleSmall
    override val title1 get() = source.largeTitle
    override val title2 get() = source.titleLarge
    override val title3 get() = source.titleMedium
    override val title4 get() = source.navTitle
}

