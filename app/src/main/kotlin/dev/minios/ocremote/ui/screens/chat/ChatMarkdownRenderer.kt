package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.theme.CodeTypography

internal enum class MessageMarkdownRoute {
    ComposeMarkdown,
    KatexWebView,
}

@Composable
internal fun MessageMarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val normalizedMarkdown = remember(markdown) { preserveRawHtmlPayload(markdown) }
    val mathSegments = remember(normalizedMarkdown) { splitMarkdownMathSegments(normalizedMarkdown) }
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
        table = {
            DisableSelection {
                val rawTable = runCatching {
                    it.content.substring(it.node.startOffset, it.node.endOffset)
                }.getOrElse { _ -> it.content }
                ScrollableMarkdownTable(
                    rawTable = rawTable,
                    textStyle = bodyStyle,
                    textColor = textColor,
                )
            }
        },
    )

    when (resolveMessageMarkdownRoute(normalizedMarkdown)) {
        MessageMarkdownRoute.KatexWebView -> {
            val context = LocalContext.current
            val linkColor = when {
                isAmoled -> MaterialTheme.colorScheme.primary
                isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.primary
            }
            MarkdownMessageView(
                markdown = normalizedMarkdown,
                textColor = textColor,
                codeBackground = codeBlockBg,
                codeForeground = codeBlockFg,
                linkColor = linkColor,
                bodyFontSizeSp = bodyFontSize.value.toInt(),
                onLinkClick = { url -> openMessageLink(context, url) },
                modifier = modifier.fillMaxWidth(),
            )
        }
        MessageMarkdownRoute.ComposeMarkdown -> {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                mathSegments.forEachIndexed { index, segment ->
                    key(markdownMathSegmentKey(index, segment)) {
                        when (segment) {
                            is MarkdownMathSegment.Markdown -> {
                                if (segment.text.isNotBlank()) {
                                    SelectionContainer {
                                        Markdown(
                                            content = segment.text,
                                            colors = colors,
                                            typography = typography,
                                            components = components,
                                            imageTransformer = Coil2ImageTransformerImpl,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            is MarkdownMathSegment.Math -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun isMessageMarkdownAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}

private fun markdownMathSegmentKey(index: Int, segment: MarkdownMathSegment): String {
    return when (segment) {
        is MarkdownMathSegment.Markdown -> "md-$index-${segment.text.hashCode()}"
        is MarkdownMathSegment.Math -> "math-$index-${segment.source.hashCode()}-${segment.display}"
    }
}

@Composable
private fun ScrollableMarkdownTable(
    rawTable: String,
    textStyle: TextStyle,
    textColor: Color,
) {
    val parsed = remember(rawTable) { parseMarkdownTable(rawTable) }
    if (parsed == null) {
        Text(
            text = rawTable.trim(),
            style = textStyle,
            color = textColor,
        )
        return
    }

    val (header, rows) = parsed
    val columnCount = header.size
    val scrollState = rememberScrollState()
    val tableShape = RoundedCornerShape(8.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            Column(
                modifier = Modifier
                    .clip(tableShape)
                    .border(BorderStroke(1.dp, borderColor), tableShape)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                MarkdownTableRow(
                    cells = header,
                    columnCount = columnCount,
                    isHeader = true,
                    textStyle = textStyle,
                    textColor = textColor,
                    dividerColor = dividerColor,
                )
                if (rows.isNotEmpty()) {
                    HorizontalDivider(color = dividerColor)
                }
                rows.forEachIndexed { index, row ->
                    MarkdownTableRow(
                        cells = row,
                        columnCount = columnCount,
                        isHeader = false,
                        textStyle = textStyle,
                        textColor = textColor,
                        dividerColor = dividerColor,
                    )
                    if (index != rows.lastIndex) {
                        HorizontalDivider(color = dividerColor)
                    }
                }
            }
        }
        if (scrollState.maxValue > 0) {
            Text(
                text = stringResource(R.string.chat_table_scroll_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    columnCount: Int,
    isHeader: Boolean,
    textStyle: TextStyle,
    textColor: Color,
    dividerColor: Color,
) {
    val backgroundColor = if (isHeader) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .background(backgroundColor),
    ) {
        repeat(columnCount) { index ->
            if (index > 0) {
                VerticalDivider(
                    color = dividerColor,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
            Text(
                text = cells.getOrElse(index) { "" },
                style = textStyle.copy(
                    fontWeight = if (isHeader) FontWeight.SemiBold else textStyle.fontWeight,
                ),
                color = textColor,
                modifier = Modifier
                    .width(176.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

internal fun parseMarkdownTable(raw: String): Pair<List<String>, List<List<String>>>? {
    val lines = raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (lines.size < 2) return null

    val header = splitMarkdownTableRow(lines[0]).map(::cleanInlineTableMarkdown)
    val divider = splitMarkdownTableRow(lines[1])
    if (header.isEmpty() || divider.size != header.size) return null

    val dividerPattern = Regex(":?-{3,}:?")
    if (!divider.all { dividerPattern.matches(it.trim()) }) return null

    val rows = lines.drop(2)
        .map { line -> splitMarkdownTableRow(line).map(::cleanInlineTableMarkdown) }
        .filter { cells -> cells.any { it.isNotBlank() } }
        .map { cells -> List(header.size) { index -> cells.getOrElse(index) { "" } } }
    return header to rows
}

internal fun splitMarkdownTableRow(line: String): List<String> {
    var row = line.trim()
    if (row.startsWith("|")) {
        row = row.drop(1)
    }
    if (row.endsWith("|") && !row.isPipeEscaped(row.lastIndex)) {
        row = row.dropLast(1)
    }

    val cells = mutableListOf<String>()
    val current = StringBuilder()
    row.forEachIndexed { index, char ->
        if (char == '|' && !row.isPipeEscaped(index)) {
            cells += current.toString().trim()
            current.clear()
        } else {
            current.append(char)
        }
    }
    cells += current.toString().trim()
    return cells
}

internal fun cleanInlineTableMarkdown(cell: String): String {
    return cell
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace("**", "")
        .replace("`", "")
        .replace("\\|", "|")
        .trim()
}

private fun String.isPipeEscaped(index: Int): Boolean {
    var slashCount = 0
    var position = index - 1
    while (position >= 0 && this[position] == '\\') {
        slashCount++
        position--
    }
    return slashCount % 2 == 1
}

internal fun resolveMessageMarkdownRoute(markdown: String): MessageMarkdownRoute {
    val normalizedMarkdown = preserveRawHtmlPayload(markdown)
    val hasMath = splitMarkdownMathSegments(normalizedMarkdown).any { it is MarkdownMathSegment.Math }
    return if (hasMath) MessageMarkdownRoute.KatexWebView else MessageMarkdownRoute.ComposeMarkdown
}

internal fun preserveRawHtmlPayload(markdown: String): String {
    if (markdown.isBlank()) return markdown
    if ("```" in markdown) return markdown

    val looksLikeHtmlDocument = HtmlDocumentHintRegex.containsMatchIn(markdown)
    val htmlTagCount = HtmlTagRegex.findAll(markdown).take(16).count()
    if (!looksLikeHtmlDocument && htmlTagCount < 8) return markdown

    return buildString(markdown.length + 16) {
        append("```text\n")
        append(markdown.trimEnd())
        append("\n```")
    }
}

private val HtmlDocumentHintRegex = Regex("(?is)<!doctype\\s+html\\b|<\\s*html\\b")
private val HtmlTagRegex = Regex("(?is)<\\s*/?\\s*[a-z][^>]*>")
