package hk.uwu.reareye.ui.capture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.FeatureGuideAction
import hk.uwu.reareye.ui.FeatureGuideDemoScreen
import hk.uwu.reareye.ui.FeatureGuideDemoState
import hk.uwu.reareye.ui.FeatureGuideStep
import hk.uwu.reareye.ui.components.config.BusinessExtraConfigManagerScreen
import hk.uwu.reareye.ui.components.config.BusinessExtraConfigScreen
import hk.uwu.reareye.ui.components.config.CustomBoundsCompatManagerScreen
import hk.uwu.reareye.ui.components.config.SceneRouteManagerScreen

/**
 * Debug-only entry into production composables with in-memory guide data.
 * This is NOT the app's integrated navigation, activation, or permissions flow.
 * Keep MainActivity and its version/activation gates unchanged.
 */
class UiCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra("page") ?: "cards"
        val backend = intent.getStringExtra("backend") ?: "miuix"
        require(backend in setOf("miuix", "mochi")) { "Unknown capture backend: $backend" }
        require(page in PAGES) { "Unknown fixture page: $page. Available: $PAGES" }
        val dark = intent.getBooleanExtra("dark", false)
        enableEdgeToEdge()
        setContent {
            CaptureTheme(backend, dark) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    BasicText(
                        "FIXTURE HOST · $page · $backend · ${if (dark) "dark" else "light"}",
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFFFE082)).padding(6.dp),
                        style = TextStyle(color = Color.Black, fontSize = 11.sp),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) { FixturePage(page) }
                }
            }
        }
    }

    companion object {
        val PAGES = setOf(
            "cards", "card-editor", "components", "component-editor", "wallpapers", "more",
            "scene-routes", "business-extras", "business-extra-editor", "custom-bounds",
        )
    }
}

@Composable
private fun FixturePage(page: String) {
    val state = remember { FeatureGuideDemoState(initialCardTitle = "Fixture clock") }
    val back: () -> Unit = {} // Root capture screen stays open; production dialogs still dismiss.
    when (page) {
        "scene-routes" -> SceneRouteManagerScreen(state.prefsManager, back)
        "business-extras" -> BusinessExtraConfigManagerScreen(state.prefsManager, back)
        "business-extra-editor" -> BusinessExtraConfigScreen(state.prefsManager, "demo_clock", back)
        "custom-bounds" -> CustomBoundsCompatManagerScreen(state.prefsManager, back)
        else -> FeatureGuideDemoScreen(
            step = FeatureGuideStep(
                titleRes = R.string.guide_config_title,
                summaryRes = R.string.guide_config_summary,
                anchorKey = "capture",
                dashboardTab = when (page) {
                    "components", "component-editor" -> 1
                    "wallpapers" -> 2
                    "more" -> 3
                    else -> 0
                },
                action = when (page) {
                    "card-editor" -> FeatureGuideAction.SAVE_CARD
                    "component-editor" -> FeatureGuideAction.SAVE_COMPONENT
                    else -> null
                },
            ),
            state = state,
            bottomPadding = 0.dp,
            onAction = {},
            onCancelEdit = {},
        )
    }
}
