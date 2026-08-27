package dev.wuxie233.codecarry.ui.screens.chat

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

internal object MarkdownParseMetrics {
    var parseCount: Int = 0
    var parseChars: Long = 0L

    fun reset() {
        parseCount = 0
        parseChars = 0L
    }

    fun record(charCount: Int) {
        parseCount += 1
        parseChars += charCount.toLong()
    }
}

private val gfmFlavour = GFMFlavourDescriptor()

internal fun parseMarkdownDocument(source: String): MarkdownDocumentParseResult {
    return runCatching {
        val normalizedSource = preserveRawHtmlPayload(source)
        val mathPreprocessing = preprocessDocumentMath(normalizedSource)
        MarkdownParseMetrics.record(mathPreprocessing.parserSource.length)
        val root = MarkdownParser(gfmFlavour).buildMarkdownTreeFromString(mathPreprocessing.parserSource)
        astToMarkdownDocument(
            originalSource = source,
            normalizedSource = normalizedSource,
            parserSource = mathPreprocessing.parserSource,
            math = mathPreprocessing.placeholders,
            root = root,
        )
    }.fold(
        onSuccess = MarkdownDocumentParseResult::Success,
        onFailure = { failure ->
            MarkdownDocumentParseResult.Failure(
                source = source,
                message = failure.message ?: failure::class.java.simpleName,
            )
        },
    )
}

private fun preserveRawHtmlPayload(markdown: String): String {
    if (markdown.isBlank() || "```" in markdown) return markdown

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

internal fun MarkdownDocumentParseResult.getOrThrow(): MarkdownDocument = when (this) {
    is MarkdownDocumentParseResult.Success -> document
    is MarkdownDocumentParseResult.Failure -> error("Markdown parse failed: $message")
}

private fun astToMarkdownDocument(
    originalSource: String,
    normalizedSource: String,
    parserSource: String,
    math: List<MarkdownMathPlaceholder>,
    root: ASTNode,
): MarkdownDocument {
    val semanticNodes = root.children.filterNot(ASTNode::isRootTrivia)
    val initialBlocks = semanticNodes.map { node -> node.toMarkdownBlock(parserSource) }
    val blocks = initialBlocks.mapIndexed { index, block ->
        val endExclusive = semanticNodes.getOrNull(index + 1)?.startOffset ?: parserSource.length
        block.withOwnedRange(SourceRange(block.semanticRange.start, endExclusive))
    }
    val segments = buildDocumentSegments(parserSource, blocks)
    val linkDefinitions = blocks.mapNotNull { (it as? MarkdownBlock.LinkDefinition)?.definition }
        .distinctBy(MarkdownLinkDefinition::normalizedLabel)
    val inlineReferences = semanticNodes.flatMap { it.inlineReferences() }
    return MarkdownDocument(
        originalSource = originalSource,
        normalizedSource = normalizedSource,
        parserSource = parserSource,
        blocks = blocks,
        segments = segments,
        linkDefinitions = linkDefinitions,
        inlineReferences = inlineReferences,
        math = math,
    )
}

private fun buildDocumentSegments(source: String, blocks: List<MarkdownBlock>): List<DocumentSegment> {
    if (source.isEmpty()) return emptyList()
    if (blocks.isEmpty()) return listOf(DocumentSegment.Trivia(SourceRange(0, source.length)))
    val segments = mutableListOf<DocumentSegment>()
    var cursor = 0
    blocks.forEachIndexed { index, block ->
        if (cursor < block.ownedRange.start) {
            segments += DocumentSegment.Trivia(SourceRange(cursor, block.ownedRange.start))
        }
        segments += DocumentSegment.BlockRef(index, block.ownedRange)
        cursor = block.ownedRange.endExclusive
    }
    if (cursor < source.length) segments += DocumentSegment.Trivia(SourceRange(cursor, source.length))
    return segments
}

private fun ASTNode.toMarkdownBlock(source: String): MarkdownBlock {
    val range = sourceRange()
    return when (type) {
        MarkdownElementTypes.PARAGRAPH -> MarkdownBlock.Paragraph(range)
        MarkdownElementTypes.ATX_1 -> heading(source, 1, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_2 -> heading(source, 2, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_3 -> heading(source, 3, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_4 -> heading(source, 4, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_5 -> heading(source, 5, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_6 -> heading(source, 6, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.SETEXT_1 -> heading(source, 1, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.SETEXT_2 -> heading(source, 2, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.BLOCK_QUOTE -> MarkdownBlock.Quote(
            children = children.filterNot(ASTNode::isNestedTrivia).map { it.toMarkdownBlock(source) },
            semanticRange = range,
        )
        MarkdownElementTypes.ORDERED_LIST -> listBlock(source, ordered = true)
        MarkdownElementTypes.UNORDERED_LIST -> listBlock(source, ordered = false)
        GFMElementTypes.TABLE -> tableBlock(source)
        MarkdownElementTypes.CODE_FENCE -> fenceBlock()
        MarkdownElementTypes.CODE_BLOCK -> MarkdownBlock.IndentedCode(
            contentRanges = descendantsOfType(MarkdownTokenTypes.CODE_LINE).map(ASTNode::sourceRange),
            semanticRange = range,
        )
        MarkdownElementTypes.HTML_BLOCK -> MarkdownBlock.RawHtml(range)
        MarkdownElementTypes.LINK_DEFINITION -> linkDefinitionBlock(source)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownBlock.ThematicBreak(range)
        else -> MarkdownBlock.Unknown(type.toString(), range)
    }
}

private fun ASTNode.heading(source: String, level: Int, contentType: IElementType): MarkdownBlock.Heading {
    val content = firstDescendantOfType(contentType)?.sourceRange() ?: sourceRange()
    return MarkdownBlock.Heading(level, content, sourceRange())
}

private fun ASTNode.listBlock(source: String, ordered: Boolean): MarkdownBlock.ListBlock {
    val itemNodes = children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    val markerType = if (ordered) MarkdownTokenTypes.LIST_NUMBER else MarkdownTokenTypes.LIST_BULLET
    val items = itemNodes.map { item ->
        val marker = item.firstDescendantOfType(markerType)
        val nested = item.children.filterNot { child ->
            child.type == markerType || child.isNestedTrivia()
        }.map { child -> child.toMarkdownBlock(source) }
        MarkdownListItem(
            markerRange = marker?.sourceRange() ?: SourceRange(item.startOffset, item.startOffset),
            children = nested,
        )
    }
    val start = if (ordered) {
        itemNodes.firstOrNull()?.firstDescendantOfType(MarkdownTokenTypes.LIST_NUMBER)
            ?.sourceRange()?.slice(source)?.takeWhile(Char::isDigit)?.toIntOrNull()
    } else null
    return MarkdownBlock.ListBlock(ordered, start, items, sourceRange())
}

private fun ASTNode.tableBlock(source: String): MarkdownBlock.Table {
    val headerNode = children.firstOrNull { it.type == GFMElementTypes.HEADER }
        ?: return MarkdownBlock.Table(emptyList(), emptyList(), sourceRange(), sourceRange())
    val header = headerNode.tableCells(source)
    val width = header.size
    val rows = children.filter { it.type == GFMElementTypes.ROW }
        .map { row -> row.tableCells(source).normalizeCells(width, row.endOffset) }
    val divider = children.firstOrNull { child ->
        child.type == GFMTokenTypes.TABLE_SEPARATOR && child.startOffset >= headerNode.endOffset
    }?.sourceRange() ?: SourceRange(headerNode.endOffset, headerNode.endOffset)
    return MarkdownBlock.Table(header, rows, divider, sourceRange())
}

private fun ASTNode.tableCells(source: String): List<MarkdownTableCell> {
    return children.mapIndexedNotNull { index, child ->
        if (child.type != GFMTokenTypes.CELL) return@mapIndexedNotNull null
        val previousSeparator = children.subList(0, index).lastOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
        val nextSeparator = children.drop(index + 1).firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
        val rawStart = previousSeparator?.endOffset ?: startOffset
        val rawEnd = nextSeparator?.startOffset ?: endOffset
        val safeStart = rawStart.coerceIn(startOffset, endOffset)
        val safeEnd = rawEnd.coerceIn(safeStart, endOffset)
        val contentRange = trimRange(source, safeStart, safeEnd)
        MarkdownTableCell(SourceRange(safeStart, safeEnd), contentRange)
    }
}

private fun List<MarkdownTableCell>.normalizeCells(width: Int, emptyOffset: Int): List<MarkdownTableCell> {
    if (width == 0) return emptyList()
    if (size >= width) return take(width)
    return this + List(width - size) {
        val empty = SourceRange(emptyOffset, emptyOffset)
        MarkdownTableCell(empty, empty)
    }
}

private fun ASTNode.fenceBlock(): MarkdownBlock.CodeFence {
    val start = firstDescendantOfType(MarkdownTokenTypes.CODE_FENCE_START)
    val end = firstDescendantOfType(MarkdownTokenTypes.CODE_FENCE_END)
    return MarkdownBlock.CodeFence(
        markerRange = start?.sourceRange() ?: SourceRange(startOffset, startOffset),
        languageRange = firstDescendantOfType(MarkdownTokenTypes.FENCE_LANG)?.sourceRange(),
        contentRanges = descendantsOfType(MarkdownTokenTypes.CODE_FENCE_CONTENT).map(ASTNode::sourceRange),
        isClosed = end != null,
        semanticRange = sourceRange(),
    )
}

private fun ASTNode.linkDefinitionBlock(source: String): MarkdownBlock.LinkDefinition {
    val labelRange = firstDescendantOfType(MarkdownElementTypes.LINK_LABEL)?.sourceRange()
        ?: SourceRange(startOffset, startOffset)
    val destinationRange = firstDescendantOfType(MarkdownElementTypes.LINK_DESTINATION)?.sourceRange()
        ?: SourceRange(startOffset, startOffset)
    val titleRange = firstDescendantOfType(MarkdownElementTypes.LINK_TITLE)?.sourceRange()
    val rawLabel = labelRange.slice(source).removeSurrounding("[", "]")
    val definition = MarkdownLinkDefinition(
        label = rawLabel,
        normalizedLabel = rawLabel.trim().replace(Regex("\\s+"), " ").lowercase(),
        destination = destinationRange.slice(source).removeSurrounding("<", ">"),
        title = titleRange?.slice(source)?.trim()?.trim('"', '\'', '(', ')'),
        semanticRange = sourceRange(),
    )
    return MarkdownBlock.LinkDefinition(definition, sourceRange())
}

private fun MarkdownBlock.withOwnedRange(range: SourceRange): MarkdownBlock = when (this) {
    is MarkdownBlock.Paragraph -> copy(ownedRange = range)
    is MarkdownBlock.Heading -> copy(ownedRange = range)
    is MarkdownBlock.Quote -> copy(ownedRange = range)
    is MarkdownBlock.ListBlock -> copy(ownedRange = range)
    is MarkdownBlock.Table -> copy(ownedRange = range)
    is MarkdownBlock.CodeFence -> copy(ownedRange = range)
    is MarkdownBlock.IndentedCode -> copy(ownedRange = range)
    is MarkdownBlock.RawHtml -> copy(ownedRange = range)
    is MarkdownBlock.LinkDefinition -> copy(ownedRange = range)
    is MarkdownBlock.ThematicBreak -> copy(ownedRange = range)
    is MarkdownBlock.Unknown -> copy(ownedRange = range)
}

private fun ASTNode.inlineReferences(): List<MarkdownInlineReference> = buildList {
    when (type) {
        MarkdownElementTypes.IMAGE -> add(
            MarkdownInlineReference(
                kind = MarkdownInlineReferenceKind.Image,
                semanticRange = sourceRange(),
                destinationRange = firstDescendantOfType(MarkdownElementTypes.LINK_DESTINATION)?.sourceRange(),
            ),
        )
        MarkdownElementTypes.INLINE_LINK -> if (parent?.type != MarkdownElementTypes.IMAGE) add(
            MarkdownInlineReference(
                kind = MarkdownInlineReferenceKind.Link,
                semanticRange = sourceRange(),
                destinationRange = firstDescendantOfType(MarkdownElementTypes.LINK_DESTINATION)?.sourceRange(),
            ),
        )
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        -> if (parent?.type != MarkdownElementTypes.IMAGE) add(
            MarkdownInlineReference(
                kind = MarkdownInlineReferenceKind.Reference,
                semanticRange = sourceRange(),
                destinationRange = null,
            ),
        )
    }
    children.forEach { child -> addAll(child.inlineReferences()) }
}

private data class DocumentMathPreprocessing(
    val parserSource: String,
    val placeholders: List<MarkdownMathPlaceholder>,
)

private fun preprocessDocumentMath(source: String): DocumentMathPreprocessing {
    if (source.isEmpty()) return DocumentMathPreprocessing(source, emptyList())
    val builder = StringBuilder(source.length)
    val placeholders = mutableListOf<MarkdownMathPlaceholder>()
    var index = 0
    var inlineCode = false

    fun appendMath(start: Int, endExclusive: Int, mathSource: String, display: Boolean, delimiter: String) {
        val token = documentMathPlaceholder(placeholders.size)
        val parserStart = builder.length
        builder.append(token)
        placeholders += MarkdownMathPlaceholder(
            id = placeholders.size,
            parserRange = SourceRange(parserStart, parserStart + token.length),
            normalizedRange = SourceRange(start, endExclusive),
            source = mathSource,
            display = display,
            delimiter = delimiter,
        )
    }

    while (index < source.length) {
        if ((index == 0 || source[index - 1] == '\n')) {
            val fence = documentFenceStart(source, index)
            if (fence != null) {
                val close = documentFenceClose(source, index, fence)
                val end = if (close >= 0) {
                    source.indexOf('\n', close).let { if (it >= 0) it + 1 else source.length }
                } else source.length
                builder.append(source, index, end)
                index = end
                continue
            }
        }
        val char = source[index]
        if (char == '`') {
            val end = index + documentRepeatedCount(source, index, '`')
            inlineCode = !inlineCode
            builder.append(source, index, end)
            index = end
            continue
        }
        if (!inlineCode && char == '\\' && index + 1 < source.length) {
            val next = source[index + 1]
            if (next == '$') {
                val display = source.startsWith("\\$\\$", index)
                val delimiter = if (display) "\\$\\$" else "\\$"
                if (!display && documentCurrencyAmount(source, index + delimiter.length)) {
                    builder.append(source, index, index + delimiter.length)
                    index += delimiter.length
                    continue
                }
                val contentStart = index + delimiter.length
                val close = documentEscapedDollarClose(source, contentStart, display)
                if (close >= 0) {
                    val mathSource = source.substring(contentStart, close).trim()
                    if (mathSource.isNotEmpty()) {
                        appendMath(index, close + delimiter.length, mathSource, display, delimiter)
                        index = close + delimiter.length
                        continue
                    }
                }
            }
            if (next == '[' || next == '(') {
                val closeDelimiter = if (next == '[') "\\]" else "\\)"
                val close = source.indexOf(closeDelimiter, index + 2)
                val crossesLine = next == '(' && source.indexOf('\n', index + 2).let { it >= 0 && it < close }
                if (close >= 0 && !crossesLine) {
                    val mathSource = source.substring(index + 2, close).trim()
                    if (mathSource.isNotEmpty()) {
                        appendMath(index, close + closeDelimiter.length, mathSource, next == '[', "\\$next")
                        index = close + closeDelimiter.length
                        continue
                    }
                }
            }
            builder.append(char).append(next)
            index += 2
            continue
        }
        if (!inlineCode && char == '$' && !documentEscaped(source, index)) {
            val display = source.getOrNull(index + 1) == '$'
            val delimiter = if (display) "$$" else "$"
            if (!display && documentCurrencyAmount(source, index + 1)) {
                builder.append(char)
                index++
                continue
            }
            val contentStart = index + delimiter.length
            val close = documentDollarClose(source, contentStart, display)
            if (close >= 0) {
                val mathSource = source.substring(contentStart, close).trim()
                if (mathSource.isNotEmpty()) {
                    appendMath(index, close + delimiter.length, mathSource, display, delimiter)
                    index = close + delimiter.length
                    continue
                }
            }
        }
        builder.append(char)
        index++
    }
    return DocumentMathPreprocessing(builder.toString(), placeholders)
}

private fun documentMathPlaceholder(index: Int): String = "xMJXMATH${index}HTAMXJMx"

private fun documentFenceStart(text: String, index: Int): String? {
    var cursor = index
    while (cursor < text.length && (text[cursor] == ' ' || text[cursor] == '\t')) cursor++
    val marker = text.getOrNull(cursor)?.takeIf { it == '`' || it == '~' } ?: return null
    val count = documentRepeatedCount(text, cursor, marker)
    return marker.toString().repeat(count).takeIf { count >= 3 }
}

private fun documentFenceClose(text: String, start: Int, fence: String): Int {
    var cursor = text.indexOf('\n', start).let { if (it >= 0) it + 1 else return -1 }
    while (cursor < text.length) {
        val lineEnd = text.indexOf('\n', cursor).let { if (it >= 0) it else text.length }
        if (text.substring(cursor, lineEnd).trim() == fence) return cursor
        cursor = if (lineEnd < text.length) lineEnd + 1 else text.length
    }
    return -1
}

private fun documentRepeatedCount(text: String, start: Int, char: Char): Int {
    var cursor = start
    while (cursor < text.length && text[cursor] == char) cursor++
    return cursor - start
}

private fun documentEscaped(text: String, index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        slashCount++
        cursor--
    }
    return slashCount % 2 == 1
}

private fun documentCurrencyAmount(text: String, contentStart: Int): Boolean {
    var cursor = contentStart
    if (cursor >= text.length || !text[cursor].isDigit()) return false
    while (cursor < text.length && (text[cursor].isDigit() || text[cursor] == ',' || text[cursor] == '.')) cursor++
    val next = text.getOrNull(cursor) ?: return true
    if (next.isLetter() || next in "^_{}\\(+=*|<>") return false
    if (next != '/') return true
    cursor++
    while (cursor < text.length && text[cursor].isWhitespace()) cursor++
    return cursor < text.length - 1 && text[cursor] == '$' && text[cursor + 1].isDigit()
}

private fun documentDollarClose(text: String, start: Int, display: Boolean): Int {
    val delimiter = if (display) "$$" else "$"
    val lineEnd = if (display) text.length else text.indexOf('\n', start).let { if (it >= 0) it else text.length }
    var cursor = start
    while (cursor < lineEnd) {
        val close = text.indexOf(delimiter, cursor)
        if (close < 0 || close >= lineEnd) return -1
        if (!documentEscaped(text, close) && (display || !documentCurrencyAmount(text, close + 1))) return close
        cursor = close + delimiter.length
    }
    return -1
}

private fun documentEscapedDollarClose(text: String, start: Int, display: Boolean): Int {
    val delimiter = if (display) "\\$\\$" else "\\$"
    val lineEnd = if (display) text.length else text.indexOf('\n', start).let { if (it >= 0) it else text.length }
    var cursor = start
    while (cursor < lineEnd) {
        val close = text.indexOf(delimiter, cursor)
        if (close < 0 || close >= lineEnd) return -1
        if (display || !documentCurrencyAmount(text, close + delimiter.length)) return close
        cursor = close + delimiter.length
    }
    return -1
}

private fun ASTNode.sourceRange(): SourceRange = SourceRange(startOffset, endOffset)

private fun ASTNode.isRootTrivia(): Boolean =
    type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.WHITE_SPACE

private fun ASTNode.isNestedTrivia(): Boolean = isRootTrivia() ||
    type == MarkdownTokenTypes.BLOCK_QUOTE ||
    type == MarkdownTokenTypes.LIST_NUMBER ||
    type == MarkdownTokenTypes.LIST_BULLET

private fun ASTNode.firstDescendantOfType(target: IElementType): ASTNode? {
    children.forEach { child ->
        if (child.type == target) return child
        child.firstDescendantOfType(target)?.let { return it }
    }
    return null
}

private fun ASTNode.descendantsOfType(target: IElementType): List<ASTNode> = buildList {
    children.forEach { child ->
        if (child.type == target) add(child)
        addAll(child.descendantsOfType(target))
    }
}

private fun trimRange(source: String, start: Int, endExclusive: Int): SourceRange {
    var trimmedStart = start
    var trimmedEnd = endExclusive
    while (trimmedStart < trimmedEnd && source[trimmedStart].isWhitespace()) trimmedStart++
    while (trimmedEnd > trimmedStart && source[trimmedEnd - 1].isWhitespace()) trimmedEnd--
    return SourceRange(trimmedStart, trimmedEnd)
}
