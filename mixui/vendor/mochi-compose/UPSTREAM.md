# Mochi 0.2.0 compatibility source

Source: https://github.com/feice11/mochi-compose/tree/3f4eda8603443af2774cf450b47d13f1f311eea7/mochi-compose

Pinned upstream commit: 3f4eda8603443af2774cf450b47d13f1f311eea7. Apache-2.0; upstream LICENSE and third-party notices are preserved.

Upstream Kotlin sources are under `mixui/src/main/kotlin/ui/feice/mochi`; upstream resources, manifest and baseline profile use standard `mixui/src/main` paths. License records remain in this vendor directory.

This source is compiled inside mixui rather than packaging the incompatible published core binary. The published mochi-ios recipes remain a dependency, with its core binary excluded to prevent duplicate classes. There are no runtime class stubs or missing-class warning suppressions.

Changes from upstream:
- MochiColorScheme.kt: use MaterialKolor 6 DynamicScheme package. Recompilation resolves scheme constructor/default-argument descriptors against the actual shared version.
- GlassPipeline.kt: convert Android RenderEffect to Compose RenderEffect for Backdrop 2's supported overload.

No upstream tests/examples or publishing plugins are included. Keep this directory isolated from application code and upstream further fixes when appropriate. Check upstream-sha256.json before rebasing; hashes describe the original source files, before these two edits.
