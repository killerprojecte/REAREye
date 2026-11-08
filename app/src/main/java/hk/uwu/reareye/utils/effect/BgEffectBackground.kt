// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.reareye.utils.effect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import hk.uwu.mixui.theme.MixTheme

@Composable
inline fun BgEffectBackground(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    isDarkTheme: Boolean? = null,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    crossinline alpha: () -> Float = { 1f },
    content: @Composable (BoxScope.() -> Unit),
) {
    val shaderSupported = remember { isRuntimeShaderSupported() }
    val surface = backgroundColor ?: MixTheme.colorScheme.surface
    val darkTheme = isDarkTheme ?: (surface.luminance() < 0.5f)
    if (!shaderSupported) {
        Box(
            modifier = modifier.background(surface),
            content = content,
        )
        return
    }
    Box(
        modifier = modifier,
    ) {
        val painter = remember { BgEffectPainter() }
        val animTime = rememberFrameTimeSeconds(dynamicBackground)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier),
        ) {
            drawRect(surface)
            if (effectBackground) {
                val drawHeight = if (isFullSize) size.height else size.height * 0.78f
                painter.updateResolution(
                    size.width,
                    size.height,
                )
                painter.updatePresetIfNeeded(
                    drawHeight,
                    size.height,
                    size.width,
                    darkTheme,
                )
                painter.updateAnimTime(animTime())
                drawRect(painter.runtimeShader.asBrush(), alpha = alpha())
            }
        }
        content()
    }
}
