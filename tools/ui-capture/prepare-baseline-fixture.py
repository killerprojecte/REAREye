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
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline_checkout', type=pathlib.Path)
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
    print('Added debug-only MIUIX baseline fixture; production sources are unchanged.')


if __name__ == '__main__':
    main()
