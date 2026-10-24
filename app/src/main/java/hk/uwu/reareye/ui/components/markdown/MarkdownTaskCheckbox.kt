package hk.uwu.reareye.ui.components.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import org.intellij.markdown.ast.getTextInNode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A task in an assistant message is a read-only status, not an editable form field. */
@Composable
internal fun MarkdownTaskCheckbox(model: MarkdownComponentModel) {
    val checked =
        model.node.getTextInNode(model.content).toString().trim().equals("[x]", ignoreCase = true)
    val primary = MiuixTheme.colorScheme.primary
    val outline = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val check = MiuixTheme.colorScheme.onPrimary
    Canvas(Modifier
        .padding(end = 6.dp, top = 3.dp)
        .size(18.dp)
        .semantics {
            role = Role.Checkbox
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            disabled()
        }) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2
        drawRoundRect(
            if (checked) primary else outline,
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = if (checked) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke)
        )
        if (checked) {
            drawLine(
                check,
                Offset(size.width * .23f, size.height * .50f),
                Offset(size.width * .43f, size.height * .70f),
                stroke,
                StrokeCap.Round
            )
            drawLine(
                check,
                Offset(size.width * .43f, size.height * .70f),
                Offset(size.width * .78f, size.height * .29f),
                stroke,
                StrokeCap.Round
            )
        }
    }
}
