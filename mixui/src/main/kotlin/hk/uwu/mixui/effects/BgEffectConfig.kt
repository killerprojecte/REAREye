// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.mixui.effects

internal object BgEffectConfig {

    internal class Config(
        val points: FloatArray,
        val colors: FloatArray,
        val translateY: Float,
        val alphaMulti: Float,
        val noiseScale: Float,
        val pointOffset: Float,
        val pointRadiusMulti: Float,
        val lightOffset: Float,
        val saturateOffset: Float,
    )

    private val HYPERCEILER_POINTS = floatArrayOf(
        0.8f, 0.2f, 1.0f,
        0.8f, 0.9f, 1.0f,
        0.2f, 0.9f, 1.0f,
        0.2f, 0.2f, 1.0f,
    )

    /*
     * PHONE LIGHT
     */
    private val PHONE_LIGHT = Config(
        points = HYPERCEILER_POINTS,
        colors = floatArrayOf(
            0.58f, 0.74f, 1.0f, 1.0f,
            1.0f, 0.90f, 0.93f, 1.0f,
            0.74f, 0.76f, 1.0f, 1.0f,
            0.97f, 0.77f, 0.84f, 1.0f,
        ),
        translateY = 0f,
        alphaMulti = 1f,
        noiseScale = 1.5f,
        pointOffset = 0.2f,
        pointRadiusMulti = 1f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
    )

    /*
     * PHONE DARK
     */
    private val PHONE_DARK = Config(
        points = HYPERCEILER_POINTS,
        colors = floatArrayOf(
            0.07f, 0.15f, 0.79f, 0.5f,
            0.62f, 0.21f, 0.67f, 0.5f,
            0.06f, 0.25f, 0.84f, 0.5f,
            0.00f, 0.20f, 0.78f, 0.5f,
        ),
        translateY = 0f,
        alphaMulti = 1f,
        noiseScale = 1.5f,
        pointOffset = 0.4f,
        pointRadiusMulti = 1f,
        lightOffset = 0f,
        saturateOffset = 0.17f,
    )

    /*
     * PAD LIGHT
     */
    private val PAD_LIGHT = Config(
        points = HYPERCEILER_POINTS,
        colors = floatArrayOf(
            0.66f, 0.75f, 1.0f, 1.0f,
            1.0f, 0.86f, 0.91f, 1.0f,
            0.74f, 0.76f, 1.0f, 1.0f,
            0.97f, 0.77f, 0.84f, 1.0f,
        ),
        translateY = 0f,
        alphaMulti = 1f,
        noiseScale = 1.5f,
        pointOffset = 0.2f,
        pointRadiusMulti = 1f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
    )

    /*
     * PAD DARK
     */
    private val PAD_DARK = Config(
        points = HYPERCEILER_POINTS,
        colors = floatArrayOf(
            0.07f, 0.15f, 0.79f, 0.5f,
            0.11f, 0.16f, 0.83f, 0.5f,
            0.06f, 0.25f, 0.84f, 0.5f,
            0.66f, 0.26f, 0.62f, 0.5f,
        ),
        translateY = 0f,
        alphaMulti = 1f,
        noiseScale = 1.5f,
        pointOffset = 0.2f,
        pointRadiusMulti = 1f,
        lightOffset = 0f,
        saturateOffset = 0f,
    )

    /**
     * 获取当前配置
     */
    internal fun get(
        deviceType: DeviceType,
        isDark: Boolean,
    ): Config = when (deviceType) {
        DeviceType.PHONE if !isDark ->
            PHONE_LIGHT

        DeviceType.PHONE if isDark ->
            PHONE_DARK

        DeviceType.PAD if !isDark ->
            PAD_LIGHT

        else -> PAD_DARK
    }
}
