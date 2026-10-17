package hk.uwu.reareye.ui.components.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ImageRequest
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownInlineImage
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import hk.uwu.reareye.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.URI

/** Tests may supply a loader; production shares Coil's application-scoped loader and caches. */
internal val LocalMarkdownImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
internal fun rememberMarkdownImageTransformer(): ImageTransformer {
    val context = LocalContext.current
    val loader = LocalMarkdownImageLoader.current ?: SingletonImageLoader.get(context)
    val scope = rememberCoroutineScope()
    return remember(context, loader, scope) { MarkdownImageTransformer(context, loader, scope) }
}

/**
 * The upstream inline renderer calls transform for measurement AND drawing, and may then
 * promote the same image to a block. Own each request at message scope, not those transient
 * child compositions. This coalesces all three observers without cancelling/replaying I/O.
 */
private class MarkdownImageTransformer(
    private val context: android.content.Context,
    private val loader: ImageLoader,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : ImageTransformer {
    private val results =
        mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<coil3.request.ImageResult?>>()
    private val started = mutableSetOf<String>()

    @Synchronized
    private fun stateFor(link: String): kotlinx.coroutines.flow.MutableStateFlow<coil3.request.ImageResult?>? {
        // Bound retained decoded images within a single model-generated message.
        if (link !in results && results.size >= 64) return null
        return results.getOrPut(link) { kotlinx.coroutines.flow.MutableStateFlow(null) }
    }

    @Synchronized
    private fun start(
        link: String,
        state: kotlinx.coroutines.flow.MutableStateFlow<coil3.request.ImageResult?>
    ) {
        if (!started.add(link)) return
        scope.launch {
            val request = ImageRequest.Builder(context).data(link).size(1024, 1024).build()
            val result = loader.execute(request)
            if (result is coil3.request.ErrorResult) android.util.Log.w(
                "MaryMarkdown",
                "Unable to load Markdown image",
                result.throwable
            )
            state.value = result
        }
    }

    @Composable
    override fun transform(link: String): ImageData {
        val allowed = remember(link) {
            runCatching {
                val uri = URI(link)
                uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }
        val flow = if (allowed) stateFor(link) else null
        val result = flow?.collectAsState()?.value
        if (flow != null) SideEffect { start(link, flow) }
        val state = when {
            !allowed -> "blocked"
            flow == null -> "error"
            result is coil3.request.SuccessResult -> "success"
            result is coil3.request.ErrorResult -> "error"
            else -> "loading"
        }
        val label = when (state) {
            "success" -> null
            "loading" -> stringResource(R.string.mary_markdown_image_loading)
            "blocked" -> stringResource(R.string.mary_markdown_image_blocked)
            else -> stringResource(R.string.mary_markdown_image_error)
        }
        val painter = if (result is coil3.request.SuccessResult) remember(result) {
            result.image.asPainter(context)
        } else statusPainter(label!!)
        return ImageData(
            painter,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .heightIn(max = 480.dp)
                .testTag("markdown-image-$state")
                .semantics { stateDescription = state },
            contentDescription = label
        )
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size =
        if (painter is MarkdownImagePlaceholder) Size.Unspecified else Coil3ImageTransformerImpl.intrinsicSize(
            painter
        )

    override fun placeholderConfig(
        link: String, density: androidx.compose.ui.unit.Density,
        containerSize: Size, imageWidth: com.mikepenz.markdown.model.ImageWidth, imageSize: Size,
        imageSizeChanged: ((String, Size) -> Unit)?,
    ): com.mikepenz.markdown.model.PlaceholderConfig {
        val image = (results[link]?.value as? coil3.request.SuccessResult)?.image
        val actual = image?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: imageSize
        return super.placeholderConfig(
            link,
            density,
            containerSize,
            imageWidth,
            actual,
            imageSizeChanged
        )
    }
}

private interface MarkdownImagePlaceholder

@Composable
private fun statusPainter(label: String): Painter {
    val measurer = rememberTextMeasurer()
    val foreground = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val background = MiuixTheme.colorScheme.surfaceContainerHigh
    val text = remember(label, foreground, measurer) {
        measurer.measure(
            label,
            TextStyle(color = foreground, fontSize = 13.sp)
        )
    }
    return remember(text, background) {
        object : Painter(), MarkdownImagePlaceholder {
            override val intrinsicSize = Size(text.size.width + 32f, text.size.height + 32f)
            override fun DrawScope.onDraw() {
                drawRect(background)
                drawText(text, topLeft = androidx.compose.ui.geometry.Offset(8f, 8f))
            }
        }
    }
}

@Composable
internal fun MarkdownRemoteImage(model: MarkdownComponentModel) {
    val link = model.node.resolveImageLink(model.content, LocalReferenceLinkHandler.current)
    val alt = model.node.resolveImageAlt(model.content)
    if (link == null) {
        Text(alt ?: stringResource(R.string.mary_markdown_image_error))
        return
    }
    val data = LocalImageTransformer.current.transform(link) ?: return
    Column {
        Image(
            data.painter,
            contentDescription = alt ?: data.contentDescription,
            modifier = data.modifier,
            alignment = data.alignment,
            contentScale = data.contentScale
        )
        if (!alt.isNullOrBlank() && data.contentDescription != null) Text(alt)
    }
}

@Composable
internal fun MarkdownRemoteInlineImage(model: MarkdownComponentModel, source: String) {
    val references = LocalReferenceLinkHandler.current
    fun findAlt(node: org.intellij.markdown.ast.ASTNode): String? {
        if (node.type == org.intellij.markdown.MarkdownElementTypes.IMAGE) {
            if (node.resolveImageLink(
                    source,
                    references
                ) == model.content
            ) return node.resolveImageAlt(source)
        }
        return node.children.firstNotNullOfOrNull(::findAlt)
    }
    // retainState may briefly pair the previous AST with the incoming full snapshot.
    val alt = runCatching { findAlt(model.node) }.getOrNull()
    MarkdownInlineImage(model.content, model.node, alt)
}
