package hk.uwu.reareye.ui.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File

/** Controls direction/font scaling without changing production page code or string contents. */
@Composable
internal fun NativeTitleTestEnvironment(
    direction: LayoutDirection,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalLayoutDirection provides direction,
        LocalDensity provides Density(density.density, fontScale),
        content = content,
    )
}

internal data class NativeTitleGeometry(
    val bounds: Rect,
    val layout: TextLayoutResult,
) {
    // Read the paragraph edge as well as the semantics bounds. A TextIndent
    // workaround may leave the semantics node at 14.64 while moving its glyphs.
    val paragraphLeft: Float get() = bounds.left + layout.getLineLeft(0)
    val paragraphRight: Float get() = bounds.left + layout.getLineRight(0)
    // Android getLineLeft includes the paragraph's indent allocation, not the
    // first glyph advance. Read the glyph position to test visible title placement.
    fun startInset(root: Rect, direction: LayoutDirection): Float {
        val firstGlyph = layout.getBoundingBox(0)
        return if (direction == LayoutDirection.Ltr) bounds.left + firstGlyph.left - root.left
        else root.right - (bounds.left + firstGlyph.right)
    }

    fun describe(): String = buildString {
        appendLine("bounds=$bounds layoutSize=${layout.size} fontSize=${layout.layoutInput.style.fontSize}")
        appendLine("paragraph=[$paragraphLeft,$paragraphRight] direction=${layout.getParagraphDirection(0)}")
        appendLine("style=${layout.layoutInput.style}")
        // These are glyph advance boxes, not ink bounding boxes. PNG captures
        // remain the visual evidence for optical bearings/anti-aliasing.
        for (index in layout.layoutInput.text.text.indices) {
            val glyph = layout.getBoundingBox(index)
            appendLine("glyph[$index]=[${bounds.left + glyph.left},${bounds.top + glyph.top},${bounds.left + glyph.right},${bounds.top + glyph.bottom}]")
        }
    }
}

internal fun SemanticsNodeInteractionsProvider.nativeTitleGeometry(text: String): List<NativeTitleGeometry> {
    val nodes = onAllNodesWithText(text, useUnmergedTree = true)
    return nodes.fetchSemanticsNodes().mapIndexedNotNull { index, node ->
        val layouts = mutableListOf<TextLayoutResult>()
        nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        layouts.firstOrNull { it.lineCount > 0 }?.let { NativeTitleGeometry(node.boundsInRoot, it) }
    }
}

internal fun writeNativeTitleGeometry(folder: String, name: String, geometries: List<NativeTitleGeometry>) {
    val output = System.getProperty("ui.capture.output") ?: return
    val destination = File(output, folder).apply { mkdirs() }
    File(destination, "$name-geometry.txt").writeText(geometries.joinToString("\n") { it.describe() })
}
