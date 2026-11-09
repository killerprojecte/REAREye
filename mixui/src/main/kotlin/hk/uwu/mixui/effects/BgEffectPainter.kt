// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.mixui.effects

import top.yukonga.miuix.kmp.blur.RuntimeShader

internal class BgEffectPainter {

    val runtimeShader by lazy {
        RuntimeShader(OS2_BG_FRAG).also {
            initStaticUniforms(it)
        }
    }

    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)

    private var animTime = Float.NaN
    private var boundLogoHeight = Float.NaN
    private var boundHeight = Float.NaN
    private var boundWidth = Float.NaN
    private var isDarkCached: Boolean? = null
    private var deviceTypeCached: DeviceType? = null

    private var presetApplied = false

    private var deviceType = DeviceType.PHONE

    companion object {

        private const val U_TRANSLATE_Y = 0f
        private const val U_ALPHA_MULTI = 1f
        private const val U_NOISE_SCALE = 1.5f
        private const val U_POINT_OFFSET = 0.1f
        private const val U_POINT_RADIUS_MULTI = 1f
    }

    private fun initStaticUniforms(shader: RuntimeShader) {
        shader.setFloatUniform("uTranslateY", U_TRANSLATE_Y)
        shader.setFloatUniform("uNoiseScale", U_NOISE_SCALE)
        shader.setFloatUniform("uPointOffset", U_POINT_OFFSET)
        shader.setFloatUniform("uPointRadiusMulti", U_POINT_RADIUS_MULTI)
        shader.setFloatUniform("uAlphaMulti", U_ALPHA_MULTI)
    }

    /*fun setDeviceType(type: DeviceType) {
        if (deviceType == type) return

        deviceType = type
        presetApplied = false
    }*/

    fun updateResolution(width: Float, height: Float) {
        if (
            resolution[0] == width &&
            resolution[1] == height
        ) {
            return
        }

        resolution[0] = width
        resolution[1] = height

        runtimeShader.setFloatUniform(
            "uResolution",
            resolution,
        )
    }

    fun updateAnimTime(time: Float) {
        if (animTime == time) return

        animTime = time

        runtimeShader.setFloatUniform(
            "uAnimTime",
            animTime,
        )
    }

    fun updatePresetIfNeeded(
        logoHeight: Float,
        height: Float,
        width: Float,
        isDark: Boolean,
    ) {
        if (
            presetApplied &&
            isDarkCached == isDark &&
            deviceTypeCached == deviceType &&
            boundLogoHeight == logoHeight &&
            boundHeight == height &&
            boundWidth == width
        ) {
            return
        }

        updateBound(
            logoHeight,
            height,
            width,
        )

        applyPreset(isDark)

        isDarkCached = isDark
        deviceTypeCached = deviceType
        boundLogoHeight = logoHeight
        boundHeight = height
        boundWidth = width
        presetApplied = true
    }

    private fun applyPreset(isDark: Boolean) {
        val preset = BgEffectConfig.get(deviceType, isDark)

        runtimeShader.setFloatUniform(
            "uPoints",
            preset.points,
        )

        runtimeShader.setFloatUniform(
            "uColors",
            preset.colors,
        )

        runtimeShader.setFloatUniform(
            "uTranslateY",
            preset.translateY,
        )

        runtimeShader.setFloatUniform(
            "uAlphaMulti",
            preset.alphaMulti,
        )

        runtimeShader.setFloatUniform(
            "uNoiseScale",
            preset.noiseScale,
        )

        runtimeShader.setFloatUniform(
            "uPointOffset",
            preset.pointOffset,
        )

        runtimeShader.setFloatUniform(
            "uPointRadiusMulti",
            preset.pointRadiusMulti,
        )

        runtimeShader.setFloatUniform(
            "uLightOffset",
            preset.lightOffset,
        )

        runtimeShader.setFloatUniform(
            "uSaturateOffset",
            preset.saturateOffset,
        )

        runtimeShader.setFloatUniform(
            "uBound",
            bound,
        )
    }

    private fun updateBound(
        logoHeight: Float,
        totalHeight: Float,
        totalWidth: Float,
    ) {
        if (totalHeight <= 0f || totalWidth <= 0f) return

        val heightRatio = logoHeight / totalHeight
        if (totalWidth <= totalHeight) {
            bound[0] = 0f
            bound[1] = 1f - heightRatio
            bound[2] = 1f
            bound[3] = heightRatio
        } else {
            val aspectRatio = totalWidth / totalHeight
            val contentCenterY = 1f - heightRatio / 2f
            bound[0] = 0f
            bound[1] = contentCenterY - aspectRatio / 2f
            bound[2] = 1f
            bound[3] = aspectRatio
        }
    }
}
