#!/usr/bin/env python3
"""Overlay only the debug fixture host onto a separately checked-out baseline.

This deliberately modifies the baseline capture build, not its production sources.
Record the baseline commit AND this overlay's revision with capture evidence.
"""
import argparse
import pathlib
import shutil

BASELINE_THEME = '''package hk.uwu.reareye.ui.capture

import androidx.compose.runtime.Composable
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode

@Composable
internal fun CaptureTheme(backend: String, dark: Boolean, content: @Composable () -> Unit) {
    require(backend == "miuix") { "The baseline has no Mochi implementation" }
    AppTheme(
        themeMode = if (dark) AppThemeMode.MIUIX_DARK else AppThemeMode.MIUIX_LIGHT,
        content = content,
    )
}

@Composable
internal fun CaptureDialogHost(content: @Composable () -> Unit) {
    top.yukonga.miuix.kmp.basic.Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { content() }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline_checkout', type=pathlib.Path)
    parser.add_argument('--local-render', action='store_true', help='Also overlay the Robolectric capture test and test-only Gradle configuration')
    args = parser.parse_args()
    source_root = pathlib.Path(__file__).resolve().parents[2]
    target = args.baseline_checkout.resolve()
    if target == source_root:
        parser.error('Use a separate baseline checkout, not the candidate source tree')
    if not (target / 'app/src/main/java/hk/uwu/reareye/ui/FeatureGuideDemoScreen.kt').is_file():
        parser.error('Target must contain the production guide fixture seam')
    destination = target / 'app/src/debug'
    if destination.exists():
        parser.error('Refusing to overwrite an existing debug source set')
    shutil.copytree(source_root / 'app/src/debug', destination)
    (destination / 'java/hk/uwu/reareye/ui/capture/CaptureTheme.kt').write_text(BASELINE_THEME)
    if args.local_render:
        test_path = pathlib.Path('app/src/test/java/hk/uwu/reareye/ui/capture/RealPageCaptureTest.kt')
        (target / test_path).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_root / test_path, target / test_path)
        build = target / 'app/build.gradle.kts'
        original = build.read_text()
        config = original.replace(
            'unitTests.isIncludeAndroidResources = providers.gradleProperty("maryUiResources")',
            'unitTests.isIncludeAndroidResources = providers.gradleProperty("uiCapture").map(String::toBoolean).getOrElse(false) || providers.gradleProperty("maryUiResources")',
        )
        config = config.replace('tasks.withType<Test>().configureEach {', '''tasks.withType<Test>().configureEach {
    val uiCapture = providers.gradleProperty("uiCapture").map(String::toBoolean).getOrElse(false)
    systemProperty("ui.capture.enabled", uiCapture.toString())
    systemProperty("ui.capture.backends", "miuix")
    systemProperty("ui.capture.baseline", "true")
    systemProperty("ui.capture.output", rootProject.layout.buildDirectory.dir("reports/ui-capture").get().asFile.absolutePath)''')
        config = config.replace('if (maryUiResources) {', 'if (uiCapture) {\n            includeTestsMatching("hk.uwu.reareye.ui.capture.RealPageCaptureTest")\n        } else if (maryUiResources) {')
        if config == original:
            parser.error('Baseline Gradle test configuration did not match the known overlay seam')
        build.write_text(config)
    print('Added debug-only MIUIX baseline fixture; production sources are unchanged.')


if __name__ == '__main__':
    main()
