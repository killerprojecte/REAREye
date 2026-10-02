package hk.uwu.reareye.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import hk.uwu.reareye.ui.components.LocalRearWallpaperPreviewProvider
import hk.uwu.reareye.ui.components.config.BusinessManagerScreen
import hk.uwu.reareye.ui.components.config.CardManagerScreen
import hk.uwu.reareye.ui.components.config.ConfigDashboard
import hk.uwu.reareye.ui.components.config.RearWallpaperManagerScreen
import hk.uwu.reareye.ui.components.config.buildMoreCategories
import hk.uwu.reareye.ui.config.REAREyeConfig
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior

/** Use the production screens and dialogs. Only their data and side effects are substituted. */
@Composable
internal fun FeatureGuideDemoScreen(
    step: FeatureGuideStep,
    state: FeatureGuideDemoState,
    bottomPadding: Dp,
    onAction: (FeatureGuideAction) -> Unit,
    onCancelEdit: () -> Unit,
) {
    val preview = remember {
        val bitmap =
            android.graphics.Bitmap.createBitmap(208, 320, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 208f, 320f,
                intArrayOf(0xFF192B61.toInt(), 0xFF7558A3.toInt(), 0xFFEBB891.toInt()), null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        android.graphics.Canvas(bitmap).drawRect(0f, 0f, 208f, 320f, paint)
        bitmap.asImageBitmap()
    }
    CompositionLocalProvider(
        LocalFeatureGuideDemo provides FeatureGuideDemoContext(
            state,
            step.action,
            onAction,
            onCancelEdit
        ),
        LocalRearWallpaperPreviewProvider provides { path ->
            preview.takeIf { path == FeatureGuideDemoState.PREVIEW_KEY }
        },
    ) {
        ConfigDashboard(
            moreCategories = buildMoreCategories(REAREyeConfig),
            contentPadding = PaddingValues(bottom = bottomPadding),
            scrollBehavior = MiuixScrollBehavior(),
            onOpenCategory = {}, onOpenScriptManagement = {}, onMoreSearchRequested = {},
            onOpenFavoriteCategory = {}, favoriteNodeCount = 0,
            selectedTabIndex = step.dashboardTab ?: 0,
            cardContent = { _, _, _, _, _ ->
                CardManagerScreen(
                    prefsManager = state.prefsManager, onBack = {}, embedded = true,
                    contentPadding = PaddingValues(bottom = bottomPadding)
                )
            },
            componentContent = { _, _, _, _, _ ->
                BusinessManagerScreen(
                    prefsManager = state.prefsManager, onBack = {}, embedded = true,
                    contentPadding = PaddingValues(bottom = bottomPadding)
                )
            },
            wallpaperContent = { _, _ ->
                RearWallpaperManagerScreen(
                    prefsManager = state.prefsManager, onBack = {}, embedded = true,
                    contentPadding = PaddingValues(bottom = bottomPadding)
                )
            },
        )
    }
}
