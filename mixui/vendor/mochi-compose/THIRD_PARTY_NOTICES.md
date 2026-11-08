# Third-party notices

## Kyant Backdrop inverse layer transform

`GlassInverseLayerScope.kt` adapts `InverseLayerScope.kt` from
`io.github.kyant0:backdrop:1.0.6`, by Kyant.
Project: https://github.com/Kyant0/AndroidLiquidGlass
License: Apache License 2.0 (see this repository's LICENSE).
It preserves the upstream inverse-transform behavior for shared backdrop textures.

## Cresto / Glasense Overscroll

Authors: Kyant & Nevoit (Nevodev/Cresto contributors)
Repository: https://github.com/Nevodev/Cresto
Pinned revision: 74564563a686106d76d7d9a403dd994a4fbcb6a7
Source: https://github.com/Nevodev/Cresto/tree/74564563a686106d76d7d9a403dd994a4fbcb6a7/glasense-ui/src/main/java/com/nevoit/glasense/core/interaction/overscroll
License: Apache License 2.0 (see LICENSE-Cresto.txt)

Includes adaptations of OffsetOverscrollEffect.kt and the exponential formula
from util/ProgressConverter.kt. Modified by mochi-compose contributors:
- package and internal visibility; Mochi public API retained as a delegating facade;
- live spring/curve parameters and observable visible offset/axis for title/refresh;
- measured viewport size and standard Compose layer placement instead of SingleLayout;
- optional scope and visible-offset callback for custom dropdown scrolling;
- reset support for programmatic dropdown positioning.
The upstream scrolling, spring velocity handoff, frame timing probe and
isInProgress=false behavior are retained. Mochi's existing fling deceleration
(0.998, stop near rest at 1dp/s) remains a separate policy.

## Ionicons (mochi-icons module)

Version: 8.1.0 (npm package `ionicons@8.1.0`, sha256 of the tarball
6ec604a301f9bb1a59ccc72a746cbb369bae87c5b45f6c67904dc878d9efb8b5)
Project: https://github.com/ionic-team/ionicons
License: MIT, Copyright (c) 2015-present Ionic (see `mochi-icons/src/main/assets/mochi-icons/LICENSE-ionicons.txt`).
`mochi-icons/tools/build_ionicons.py` converts `dist/svg` into path data: shapes become paths,
arc flags are re-spaced, inherited fill/stroke attributes are resolved, and the one 16×16 icon is
scaled to the 512 grid. The artwork itself is unchanged. Chinese search keywords
(`ionicons-zh.txt`, from `tools/zh_keywords.py`) are original to mochi-compose.

## Example media (demo app only)

Used only by the example app's "效果 › 混合字" page; not part of the library artifacts.

- `example/src/main/res/raw/vibrancy_ink.mp4` (and `drawable-nodpi/vibrancy_ink_poster.webp`):
  "Vibrant colored inks interacting with a black liquid" from Mixkit,
  https://mixkit.co/free-stock-video/vibrant-colored-inks-interacting-with-a-black-liquid-creating-flowing-99927/
  — Mixkit Stock Video Free License. Trimmed, cropped, looped and with a blurred material baked in
  (`example/tools/bake_material.py`).
- `example/src/main/res/drawable-nodpi/portrait_*.webp`: photos from Unsplash under the Unsplash License, cropped and re-encoded:
  - yosemite: https://unsplash.com/photos/deer-in-yosemite-valley-meadow-UCd78vfC8vU
  - seceda: https://unsplash.com/photos/seceda-mountains-in-ortisei-italy-hOhlYhAiizc
  - clouds: https://unsplash.com/photos/clouds-during-golden-hour-hgGplX3PFBg
  - nighttrees: https://unsplash.com/photos/mountain-near-green-trees-at-night-LSFuPFE9vKE
  - starfield: https://unsplash.com/photos/a-field-with-grass-and-trees-under-a-night-sky-filled-with-stars-NAV4nIjgwPk
  - nightlake: https://unsplash.com/photos/lake-near-mountain-during-night-R_1-2OnjkDY
