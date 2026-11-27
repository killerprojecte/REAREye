package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.glass.GlassDialogDefaults
import top.yukonga.miuix.kmp.material.GlassMaterial

/**
 * MIUIX 0.9.4 shares its official theme-aware dialog material with bottom sheets.
 * Keep that grey glass recipe: additional PlusLighter/ColorBurn layers would
 * wash light panels white and crush dark panels black. Vendor surfaces still
 * own the scrim, stroke, blur and opaque theme-background fallback.
 */
@Composable
internal fun modalGlassMaterial(): GlassMaterial = GlassDialogDefaults.material()
