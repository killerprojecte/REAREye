package hk.uwu.reareye.ui.components.markdown

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import java.io.File

/** Export the actual tested Compose surface, never a separately rendered Markdown mockup. */
internal fun exportMarkdownScreenshot(compose: ComposeTestRule, name: String) {
    val directory =
        System.getProperty("mary.ui.screenshotDir")?.let { File(it, "markdown") } ?: return
    check(directory.mkdirs() || directory.isDirectory)
    compose.waitForIdle()
    fun capture(suffix: String = "") {
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(directory, "$name$suffix.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    capture()
    val scrolls = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        useUnmergedTree = true,
    )
    val node = scrolls.fetchSemanticsNodes().firstOrNull() ?: return
    val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
    if (range.maxValue() <= 0f) return
    val scroll = scrolls[0]
    scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -range.maxValue()) }
    compose.waitForIdle()
    repeat(100) { page ->
        capture("-page-${(page + 1).toString().padStart(2, '0')}")
        val current =
            scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val before = current.value()
        if (before >= current.maxValue()) return
        val height = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, height * 0.85f) }
        compose.waitForIdle()
        check(current.value() > before) { "Screenshot pagination did not advance for $name" }
    }
    error("Screenshot pagination exceeded 100 pages for $name")
}
