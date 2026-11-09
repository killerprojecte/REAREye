// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.reareye.utils.blend

import androidx.compose.ui.graphics.Color
import hk.uwu.mixui.effects.TextureBlendLayer
import hk.uwu.mixui.effects.TextureBlendOperation

/** App branding recipes; rendering and vendor conversion belong to mixui. */
internal object ColorBlendToken {
    val Pured_Regular_Light = listOf(
        TextureBlendLayer(Color(0x340034F9), TextureBlendOperation.Overlay),
        TextureBlendLayer(Color(0xB3FFFFFF), TextureBlendOperation.HardLight),
    )

    val Overlay_Thin_Light = listOf(
        TextureBlendLayer(Color(0x4DA9A9A9), TextureBlendOperation.Luminosity),
        TextureBlendLayer(Color(0x1A9C9C9C), TextureBlendOperation.PlusDarker),
    )
}
