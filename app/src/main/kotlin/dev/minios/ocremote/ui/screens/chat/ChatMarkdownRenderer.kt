package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.theme.CodeTypography
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

@Composable
internal fun MessageMarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    plannedBlock: MarkdownRenderBlock? = null,
) {
    val blocks = remember(markdown, plannedBlock) {
        plannedBlock?.let(::listOf) ?: when (val planned = planStreamingMarkdown(markdown)) {
            is MarkdownStreamingPlanResult.Success -> planned.plan.blocks
            is MarkdownStreamingPlanResult.Failure -> emptyList()
        }
    }
    val isAmoled = isMessageMarkdownAmoledTheme()

    val inlineCodeFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val codeBlockBg = when {
        isAmoled -> MaterialTheme.colorScheme.surfaceContainerLow
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val codeBlockFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val fontSizeSetting = LocalChatFontSize.current
    val (bodyFontSize, bodyLineHeight) = when (fontSizeSetting) {
        "small" -> 13.sp to 18.sp
        "large" -> 16.sp to 26.sp
        else -> 14.sp to 22.sp
    }
    val (codeFontSize, codeLineHeight) = when (fontSizeSetting) {
        "small" -> 11.sp to 16.sp
        "large" -> 15.sp to 22.sp
        else -> 13.sp to 20.sp
    }

    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = textColor,
        fontSize = bodyFontSize,
        lineHeight = bodyLineHeight,
    )

    val colors = markdownColor(
        text = textColor,
        codeText = codeBlockFg,
        inlineCodeText = inlineCodeFg,
        linkText = when {
            isAmoled -> MaterialTheme.colorScheme.primary
            isUser -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        codeBackground = codeBlockBg,
        inlineCodeBackground = Color.Transparent,
        dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(
            color = textColor,
            fontWeight = FontWeight.Bold,
            lineHeight = 32.sp,
        ),
        h2 = MaterialTheme.typography.titleMedium.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 28.sp,
        ),
        h3 = MaterialTheme.typography.titleSmall.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
        ),
        h4 = MaterialTheme.typography.bodyLarge.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold,
        ),
        h5 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold,
        ),
        h6 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium,
        ),
        text = bodyStyle,
        code = CodeTypography.copy(color = codeBlockFg, fontSize = codeFontSize, lineHeight = codeLineHeight),
        inlineCode = CodeTypography.copy(
            color = inlineCodeFg,
            fontSize = codeFontSize,
            fontWeight = FontWeight.Medium,
        ),
        quote = bodyStyle.copy(
            color = textColor.copy(alpha = 0.65f),
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        ),
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        link = bodyStyle.copy(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        ),
    )

    val components = markdownComponents(
        codeBlock = safeHighlightedCodeBlock,
        codeFence = mermaidAwareCodeFence,
        paragraph = { model ->
            ScrollableMarkdownParagraph(model)
        },
        orderedList = { model ->
            OrderedMarkdownList(model)
        },
        table = {
            DisableSelection {
                MeasuredMarkdownTable(
                    table = markdownTableFromComponent(it),
                    textStyle = bodyStyle,
                    textColor = textColor,
                )
            }
        },
    )

    val context = LocalContext.current
    val linkColor = when {
        isAmoled -> MaterialTheme.colorScheme.primary
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            key(block.key) {
                when {
                    block.kind == MarkdownRenderBlockKind.Table && block.table != null -> {
                        MeasuredMarkdownTable(
                            table = block.table,
                            textStyle = bodyStyle,
                            textColor = textColor,
                        )
                    }
                    block.route == MarkdownRenderRoute.Katex -> {
                        MarkdownMessageView(
                            block = block,
                            textColor = textColor,
                            codeBackground = codeBlockBg,
                            codeForeground = codeBlockFg,
                            linkColor = linkColor,
                            bodyFontSizeSp = bodyFontSize.value.toInt(),
                            onLinkClick = { url -> openMessageLink(context, url) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    block.interactionOwner == MarkdownInteractionOwner.HorizontalScroll -> {
                        Markdown(
                            content = block.renderSource,
                            colors = colors,
                            typography = typography,
                            components = components,
                            imageTransformer = Coil2ImageTransformerImpl,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    block.interactionOwner == MarkdownInteractionOwner.Passive &&
                        block.kind == MarkdownRenderBlockKind.LinkDefinition -> Unit
                    else -> SelectionContainer {
                        Markdown(
                            content = block.renderSource,
                            colors = colors,
                            typography = typography,
                            components = components,
                            imageTransformer = Coil2ImageTransformerImpl,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun markdownTableFromComponent(model: MarkdownComponentModel): MarkdownRenderTable {
    val headerNode = model.node.children.firstOrNull { it.type == GFMElementTypes.HEADER }
    val header = headerNode?.componentTableCells(model.content).orEmpty()
    val rows = model.node.children.filter { it.type == GFMElementTypes.ROW }
        .map { row ->
            val cells = row.componentTableCells(model.content)
            List(header.size) { index -> cells.getOrElse(index) { "" } }
        }
    return MarkdownRenderTable(header = header, rows = rows)
}

private fun ASTNode.componentTableCells(source: String): List<String> = children
    .filter { it.type == org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL }
    .map { cell ->
        if (cell.endOffset <= cell.startOffset || cell.startOffset !in 0..source.length) {
            ""
        } else {
            renderTableCellText(source.substring(cell.startOffset, cell.endOffset.coerceAtMost(source.length)))
        }
    }

@Composable
private fun OrderedMarkdownList(model: MarkdownComponentModel, depth: Int = 0) {
    val padding = LocalMarkdownPadding.current
    val startNumber = orderedListStartNumber(model.content, model.node)

    Column(
        modifier = Modifier.padding(
            start = padding.indentList * depth,
            top = padding.list,
            bottom = padding.list,
        ),
    ) {
        model.node.children
            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .forEachIndexed { itemIndex, listItem ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = orderedListMarker(startNumber, itemIndex),
                        style = model.typography.ordered,
                    )
                    Column(modifier = Modifier.padding(bottom = padding.listItemBottom)) {
                        listItem.children.forEach { child ->
                            RenderListChild(model.content, child, model, depth)
                        }
                    }
                }
            }
    }
}

@Composable
private fun ColumnScope.RenderListChild(
    content: String,
    node: ASTNode,
    parentModel: MarkdownComponentModel,
    depth: Int,
) {
    val components = LocalMarkdownComponents.current
    val model = MarkdownComponentModel(content, node, parentModel.typography)
    when (node.type) {
        MarkdownElementTypes.ORDERED_LIST -> OrderedMarkdownList(model, depth + 1)
        MarkdownElementTypes.UNORDERED_LIST -> BulletMarkdownList(model, depth + 1)
        MarkdownElementTypes.PARAGRAPH -> components.paragraph.invoke(this, model)
        MarkdownElementTypes.BLOCK_QUOTE -> components.blockQuote.invoke(this, model)
        MarkdownElementTypes.CODE_BLOCK -> components.codeBlock.invoke(this, model)
        MarkdownElementTypes.CODE_FENCE -> components.codeFence.invoke(this, model)
        MarkdownElementTypes.ATX_1 -> components.heading1.invoke(this, model)
        MarkdownElementTypes.ATX_2 -> components.heading2.invoke(this, model)
        MarkdownElementTypes.ATX_3 -> components.heading3.invoke(this, model)
        MarkdownElementTypes.ATX_4 -> components.heading4.invoke(this, model)
        MarkdownElementTypes.ATX_5 -> components.heading5.invoke(this, model)
        MarkdownElementTypes.ATX_6 -> components.heading6.invoke(this, model)
        MarkdownElementTypes.SETEXT_1 -> components.setextHeading1.invoke(this, model)
        MarkdownElementTypes.SETEXT_2 -> components.setextHeading2.invoke(this, model)
        MarkdownElementTypes.IMAGE -> components.image.invoke(this, model)
        MarkdownElementTypes.LINK_DEFINITION -> components.linkDefinition.invoke(this, model)
        GFMElementTypes.TABLE -> components.table.invoke(this, model)
        MarkdownTokenTypes.HORIZONTAL_RULE -> components.horizontalRule.invoke(this, model)
        else -> node.children.forEach { child -> RenderListChild(content, child, model, depth) }
    }
}

@Composable
private fun BulletMarkdownList(model: MarkdownComponentModel, depth: Int) {
    val padding = LocalMarkdownPadding.current
    Column(
        modifier = Modifier.padding(
            start = padding.indentList * depth,
            top = padding.list,
            bottom = padding.list,
        ),
    ) {
        model.node.children
            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .forEach { listItem ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "• ", style = model.typography.bullet)
                    Column(modifier = Modifier.padding(bottom = padding.listItemBottom)) {
                        listItem.children.forEach { child ->
                            RenderListChild(model.content, child, model, depth)
                        }
                    }
                }
            }
    }
}

internal fun orderedListStartNumber(content: String, orderedList: ASTNode): Int {
    val numberNode = orderedList.children
        .firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
        ?.firstDescendantOfType(MarkdownTokenTypes.LIST_NUMBER)
        ?: return 1
    return content.substring(numberNode.startOffset, numberNode.endOffset)
        .takeWhile(Char::isDigit)
        .toIntOrNull()
        ?: 1
}

internal fun orderedListMarker(startNumber: Int, itemIndex: Int): String = "${startNumber + itemIndex}. "

private fun ASTNode.firstDescendantOfType(type: IElementType): ASTNode? {
    children.forEach { child ->
        if (child.type == type) return child
        child.firstDescendantOfType(type)?.let { return it }
    }
    return null
}

@Composable
private fun ScrollableMarkdownParagraph(model: MarkdownComponentModel) {
    val rawParagraph = remember(model.content, model.node.startOffset, model.node.endOffset) {
        runCatching {
            model.content.substring(model.node.startOffset, model.node.endOffset)
        }.getOrElse { _ -> model.content }
    }
    val overflowTreatment = remember(rawParagraph) {
        ChatOverflowPolicy.resolve(
            kind = ChatOverflowContentKind.MarkdownParagraph,
            text = rawParagraph,
        )
    }
    if (overflowTreatment == ChatOverflowTreatment.Wrap) {
        MarkdownParagraph(
            model.content,
            model.node,
            Modifier.fillMaxWidth(),
            model.typography.paragraph,
        )
        return
    }

    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val paragraphMinWidth = maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            MarkdownParagraph(
                model.content,
                model.node,
                Modifier.widthIn(min = paragraphMinWidth),
                model.typography.paragraph,
            )
        }
    }
}

internal fun containsWideAsciiToken(text: String, threshold: Int = 28): Boolean {
    return ChatOverflowPolicy.containsWideAsciiToken(text = text, threshold = threshold)
}

@Composable
private fun isMessageMarkdownAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}
