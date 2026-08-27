package dev.wuxie233.codecarry.ui.screens.chat

internal data class SourceRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0)
        require(endExclusive >= start)
    }

    val length: Int get() = endExclusive - start
    val isEmpty: Boolean get() = length == 0

    fun slice(source: String): String = source.substring(start, endExclusive)

    fun shift(delta: Int): SourceRange = if (delta == 0) this else SourceRange(start + delta, endExclusive + delta)
}

internal data class MarkdownDocument(
    val originalSource: String,
    val normalizedSource: String,
    val parserSource: String,
    val blocks: List<MarkdownBlock>,
    val segments: List<DocumentSegment>,
    val linkDefinitions: List<MarkdownLinkDefinition>,
    val inlineReferences: List<MarkdownInlineReference>,
    val math: List<MarkdownMathPlaceholder>,
)

internal sealed interface MarkdownDocumentParseResult {
    data class Success(val document: MarkdownDocument) : MarkdownDocumentParseResult
    data class Failure(val source: String, val message: String) : MarkdownDocumentParseResult
}

internal sealed interface DocumentSegment {
    val parserRange: SourceRange

    data class BlockRef(
        val blockIndex: Int,
        override val parserRange: SourceRange,
    ) : DocumentSegment

    data class Trivia(override val parserRange: SourceRange) : DocumentSegment
}

internal sealed interface MarkdownBlock {
    val semanticRange: SourceRange
    val ownedRange: SourceRange

    data class Paragraph(
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class Heading(
        val level: Int,
        val contentRange: SourceRange,
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class Quote(
        val children: List<MarkdownBlock>,
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class ListBlock(
        val ordered: Boolean,
        val startNumber: Int?,
        val items: List<MarkdownListItem>,
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class Table(
        val header: List<MarkdownTableCell>,
        val rows: List<List<MarkdownTableCell>>,
        val dividerRange: SourceRange,
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class CodeFence(
        val markerRange: SourceRange,
        val languageRange: SourceRange?,
        val contentRanges: List<SourceRange>,
        val isClosed: Boolean,
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class IndentedCode(
        val contentRanges: List<SourceRange>,
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class RawHtml(
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class LinkDefinition(
        val definition: MarkdownLinkDefinition,
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class ThematicBreak(
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock

    data class Unknown(
        val typeName: String,
        override val semanticRange: SourceRange,
        override val ownedRange: SourceRange = semanticRange,
    ) : MarkdownBlock
}

internal data class MarkdownListItem(
    val markerRange: SourceRange,
    val children: List<MarkdownBlock>,
)

internal data class MarkdownTableCell(
    val sourceRange: SourceRange,
    val contentRange: SourceRange,
)

internal data class MarkdownLinkDefinition(
    val label: String,
    val normalizedLabel: String,
    val destination: String,
    val title: String?,
    val semanticRange: SourceRange,
)

internal data class MarkdownInlineReference(
    val kind: MarkdownInlineReferenceKind,
    val semanticRange: SourceRange,
    val destinationRange: SourceRange?,
)

internal enum class MarkdownInlineReferenceKind {
    Link,
    Image,
    Reference,
}

internal data class MarkdownMathPlaceholder(
    val id: Int,
    val parserRange: SourceRange,
    val normalizedRange: SourceRange,
    val source: String,
    val display: Boolean,
    val delimiter: String,
)
