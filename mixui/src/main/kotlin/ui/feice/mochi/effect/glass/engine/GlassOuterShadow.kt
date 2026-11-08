// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass.engine

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import ui.feice.mochi.theme.MochiGlassRecipe

private val ShadowShaders = ThreadLocal<RuntimeShader>()

/** The shadow is drawn outside the optical source and cannot tint the glass or pressed lens. */
internal fun DrawScope.drawGlassOuterShadow(recipe: MochiGlassRecipe, rect: Rect, alpha: Float) {
    if (Build.VERSION.SDK_INT < 33 || recipe.shadowOpacity <= 0f || alpha <= 0f || rect.isEmpty) return
    val shader = ShadowShaders.get() ?: glassOuterShadowShader().also { ShadowShaders.set(it) }
    configureGlassOuterShadow(shader, this, recipe, rect, alpha)
    val reach = recipe.outlineReach().toPx()
    val bounds = rect.inflate(reach)
    drawRect(ShaderBrush(shader), bounds.topLeft, bounds.size)
}
