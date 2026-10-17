package hk.uwu.reareye.ui.components.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.LocalTableRowIndex
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal fun tableAlignments(content: String, node: ASTNode): List<TextAlign> =
    node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }?.getTextInNode(content)
        ?.toString()?.trim()?.trim('|')?.split('|')?.map { cell ->
            val marker = cell.trim()
            when {
                marker.startsWith(':') && marker.endsWith(':') -> TextAlign.Center
                marker.endsWith(':') -> TextAlign.Right
                else -> TextAlign.Left
            }
        }.orEmpty()

/** Retain upstream table scrolling/inline rendering; supply the missing GFM cell alignment. */
@Composable
internal fun MarkdownAlignedTableRow(
    content: String, node: ASTNode, width: Dp, style: TextStyle,
    alignments: List<TextAlign>, isHeader: Boolean = false,
) {
    val rowIndex = if (isHeader) 0 else LocalTableRowIndex.current
    val padding = LocalMarkdownDimens.current.tableCellPadding
    Row(
        Modifier
            .widthIn(min = width)
            .height(IntrinsicSize.Max),
        verticalAlignment = Alignment.CenterVertically
    ) {
        node.children.filter { it.type == GFMTokenTypes.CELL }.forEachIndexed { index, cell ->
            val align = alignments.getOrElse(index) { TextAlign.Left }
            Box(
                Modifier
                    .weight(1f)
                    .padding(padding)
                    .semantics {
                        if (isHeader) heading()
                        collectionItemInfo = CollectionItemInfo(rowIndex, 1, index, 1)
                    }, contentAlignment = when (align) {
                    TextAlign.Center -> Alignment.Center
                    TextAlign.Right -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                }
            ) {
                MarkdownTableBasicText(
                    content, cell, style.copy(
                        textAlign = align,
                        fontWeight = if (isHeader) FontWeight.Bold else style.fontWeight
                    ), maxLines = Int.MAX_VALUE
                )
            }
        }
    }
}
