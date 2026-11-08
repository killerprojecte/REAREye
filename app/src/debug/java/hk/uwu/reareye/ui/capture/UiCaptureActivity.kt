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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.OnboardingScreen
import hk.uwu.reareye.ui.screen.AboutScreen
import hk.uwu.reareye.ui.screen.MarySettingsScreen
import hk.uwu.reareye.ui.components.script.ScriptManagementScreen
import hk.uwu.reareye.ui.components.TargetAppVersionDialog
import hk.uwu.reareye.version.AppVersionViolation
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
                Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("fixture-root")) {
                    BasicText(
                        "FIXTURE HOST · $page · $backend · ${if (dark) "dark" else "light"}",
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFFFE082)).padding(6.dp),
                        style = TextStyle(color = Color.Black, fontSize = 11.sp),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) { CaptureDialogHost { FixturePage(page) } }
                }
            }
        }
    }

    companion object {
        val PAGES = setOf(
            "cards", "card-editor", "components", "component-editor", "wallpapers", "more",
            "scene-routes", "scene-routes-populated", "business-extras", "business-extra-editor", "custom-bounds",
            "onboarding", "about", "scripts", "mary-settings-unconfigured", "version-dialog",
        )
    }
}

@Composable
private fun FixturePage(page: String) {
    val state = remember(page) {
        FeatureGuideDemoState(initialCardTitle = "Fixture clock").apply {
            if (page == "scene-routes-populated") {
                val routes = (1..16).map { index ->
                    hk.uwu.reareye.repository.rearwidget.RearWidgetSceneRouteConfig(
                        id = "fixture-scene-$index", packageName = "example.fixture",
                        scene = "fixture_scene_$index", business = "fixture_widget_$index",
                    )
                }
                prefsManager.putString(
                    hk.uwu.reareye.ui.config.ConfigKeys.REAR_WIDGET_SCENE_ROUTE_DATA,
                    hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec.encodeSceneRoutes(routes),
                )
            }
        }
    }
    val back: () -> Unit = {} // Root capture screen stays open; production dialogs still dismiss.
    when (page) {
        "onboarding" -> OnboardingScreen(onFinished = {})
        "about" -> AboutScreen()
        "scripts" -> ScriptManagementScreen(onBack = back)
        "mary-settings-unconfigured" -> MarySettingsScreen(onBack = back)
        "version-dialog" -> TargetAppVersionDialog(
            violations = listOf(AppVersionViolation("Fixture target app", "example.fixture", 1L, "1.0", 2L, true)),
            onClose = back,
        )
        "scene-routes", "scene-routes-populated" -> SceneRouteManagerScreen(state.prefsManager, back)
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
