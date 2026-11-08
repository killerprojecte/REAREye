// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.hdr

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * HDR 光效的一组颜色（[MochiEdgeGlow]）：一种颜色就是纯色光，多种颜色沿一圈依次过渡、首尾相接。
 * 最多用前 8 种。
 */
@Immutable
public class MochiHdrPalette(public val colors: List<Color>) {
    /** 单色 */
    public constructor(color: Color) : this(listOf(color))

    init {
        require(colors.isNotEmpty()) { "MochiHdrPalette needs at least one color" }
    }

    override fun equals(other: Any?): Boolean = other is MochiHdrPalette && other.colors == colors

    override fun hashCode(): Int = colors.hashCode()

    public companion object {
        /** Apple Intelligence：紫、粉、蓝、红、橙 */
        public val Intelligence: MochiHdrPalette = MochiHdrPalette(
            listOf(
                Color(0xFFBC82F3),
                Color(0xFFF5B9EA),
                Color(0xFF8D9FFF),
                Color(0xFFAA6EEE),
                Color(0xFFFF6778),
                Color(0xFFFFBA71),
                Color(0xFFC686FF),
            ),
        )

        /** 旧版 Siri：青、蓝、紫、品红 */
        public val Siri: MochiHdrPalette =
            MochiHdrPalette(listOf(Color(0xFF2BD9FE), Color(0xFF3D6BFF), Color(0xFFA34DFF), Color(0xFFFF4DB8)))

        /** 极光：绿、青、蓝、紫 */
        public val Aurora: MochiHdrPalette =
            MochiHdrPalette(listOf(Color(0xFF3DF5A6), Color(0xFF22C6E8), Color(0xFF5468FF), Color(0xFFB66BFF)))

        /** 日落：金、橙、红、玫红 */
        public val Sunset: MochiHdrPalette =
            MochiHdrPalette(listOf(Color(0xFFFFC94D), Color(0xFFFF8A3D), Color(0xFFFF4D5E), Color(0xFFE0479E)))

        /** 海洋：青绿、天蓝、深蓝 */
        public val Ocean: MochiHdrPalette =
            MochiHdrPalette(listOf(Color(0xFF38F0D0), Color(0xFF2FA8FF), Color(0xFF3557FF), Color(0xFF2FA8FF)))

        /** 纯白 */
        public val White: MochiHdrPalette = MochiHdrPalette(Color.White)
    }
}
