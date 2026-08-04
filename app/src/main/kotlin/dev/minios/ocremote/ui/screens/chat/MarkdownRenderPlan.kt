package dev.minios.ocremote.ui.screens.chat

internal const val MarkdownRenderPlanTargetChars = 6_000

internal data class MarkdownRenderPlan(
    val originalSource: String,
    val parserSource: String,
    val blocks: List<MarkdownRenderBlock>,
)

internal data class MarkdownRenderBlock(
    val key: String,
    val kind: MarkdownRenderBlockKind,
    val parserRange: SourceRange,
    val originalRange: SourceRange,
    val source: String,
    val renderSource: String,
    val route: MarkdownRenderRoute,
    val interactionOwner: MarkdownInteractionOwner,
    val math: List<MarkdownMathPlaceholder>,
    val table: MarkdownRenderTable? = null,
    val isOpen: Boolean = false,
)

internal enum class MarkdownRenderBlockKind {
    Prose,
    Heading,
    Quote,
    List,
    Table,
    CodeFence,
    IndentedCode,
    RawHtml,
    LinkDefinition,
    ThematicBreak,
    Unknown,
}

internal enum class MarkdownRenderRoute {
    Compose,
    Katex,
}

internal enum class MarkdownInteractionOwner {
    SelectableText,
    HorizontalScroll,
    WebView,
    Passive,
}

internal data class MarkdownRenderTable(
    val header: List<String>,
    val rows: List<List<String>>,
)

internal fun planMarkdownDocument(
    document: MarkdownDocument,
    targetChars: Int = MarkdownRenderPlanTargetChars,
): MarkdownRenderPlan {
    require(targetChars > 0)
    val candidates = document.blocks.flatMap { block ->
        planBlock(document, block, targetChars)
    }
    val coalesced = uniquifyRenderBlockKeys(coalesceProse(document, candidates, targetChars))
    return MarkdownRenderPlan(
        originalSource = document.originalSource,
        parserSource = document.parserSource,
        blocks = coalesced,
    )
}

private fun planBlock(
    document: MarkdownDocument,
    block: MarkdownBlock,
    targetChars: Int,
): List<MarkdownRenderBlock> {
    if (block is MarkdownBlock.Paragraph && block.ownedRange.length > targetChars) {
        return splitLargeProse(document, block, targetChars)
    }
    val math = document.math.filter { placeholder ->
        block.semanticRange.contains(placeholder.parserRange)
    }
    val kind = block.renderKind()
    val route = when {
        block is MarkdownBlock.Table -> MarkdownRenderRoute.Compose
        math.isNotEmpty() -> MarkdownRenderRoute.Katex
        else -> MarkdownRenderRoute.Compose
    }
    val interaction = when {
        block is MarkdownBlock.Table || block is MarkdownBlock.CodeFence || block is MarkdownBlock.IndentedCode ->
            MarkdownInteractionOwner.HorizontalScroll
        route == MarkdownRenderRoute.Katex -> MarkdownInteractionOwner.WebView
        block is MarkdownBlock.LinkDefinition || block is MarkdownBlock.ThematicBreak ->
            MarkdownInteractionOwner.Passive
        else -> MarkdownInteractionOwner.SelectableText
    }
    val parserRange = block.ownedRange
    val originalRange = document.mapParserRangeToOriginal(parserRange)
    val source = originalRange.slice(document.normalizedSource)
    val parserSource = parserRange.slice(document.parserSource)
    val renderSource = appendDocumentLinkDefinitions(
        source = parserSource,
        definitions = document.linkDefinitions,
        documentSource = document.parserSource,
    )
    return listOf(
        MarkdownRenderBlock(
            key = stableRenderBlockKey(kind, source),
            kind = kind,
            parserRange = parserRange,
            originalRange = originalRange,
            source = source,
            renderSource = renderSource,
            route = route,
            interactionOwner = interaction,
            math = math,
            table = (block as? MarkdownBlock.Table)?.toRenderTable(document.parserSource),
            isOpen = block is MarkdownBlock.CodeFence && !block.isClosed,
        ),
    )
}

private fun splitLargeProse(
    document: MarkdownDocument,
    block: MarkdownBlock.Paragraph,
    targetChars: Int,
): List<MarkdownRenderBlock> {
    val ranges = mutableListOf<SourceRange>()
    var cursor = block.ownedRange.start
    while (cursor < block.ownedRange.endExclusive) {
        val ideal = (cursor + targetChars).coerceAtMost(block.ownedRange.endExclusive)
        val end = if (ideal == block.ownedRange.endExclusive) ideal else {
            document.parserSource.lastIndexOf('\n', ideal).takeIf { it >= cursor }?.plus(1) ?: ideal
        }
        ranges += SourceRange(cursor, end)
        cursor = end
    }
    return ranges.map { parserRange ->
        val originalRange = document.mapParserRangeToOriginal(parserRange)
        val math = document.math.filter { parserRange.contains(it.parserRange) }
        val source = originalRange.slice(document.normalizedSource)
        val parserSource = parserRange.slice(document.parserSource)
        MarkdownRenderBlock(
            key = stableRenderBlockKey(MarkdownRenderBlockKind.Prose, source),
            kind = MarkdownRenderBlockKind.Prose,
            parserRange = parserRange,
            originalRange = originalRange,
            source = source,
            renderSource = appendDocumentLinkDefinitions(
                parserSource,
                document.linkDefinitions,
                document.parserSource,
            ),
            route = if (math.isEmpty()) MarkdownRenderRoute.Compose else MarkdownRenderRoute.Katex,
            interactionOwner = if (math.isEmpty()) {
                MarkdownInteractionOwner.SelectableText
            } else {
                MarkdownInteractionOwner.WebView
            },
            math = math,
        )
    }
}

private fun coalesceProse(
    document: MarkdownDocument,
    blocks: List<MarkdownRenderBlock>,
    targetChars: Int,
): List<MarkdownRenderBlock> {
    val output = mutableListOf<MarkdownRenderBlock>()
    var pending: MarkdownRenderBlock? = null

    fun flush() {
        pending?.let(output::add)
        pending = null
    }

    for (block in blocks) {
        val current = pending
        val mayMerge = current != null &&
            current.route == MarkdownRenderRoute.Compose &&
            block.route == MarkdownRenderRoute.Compose &&
            current.interactionOwner == MarkdownInteractionOwner.SelectableText &&
            block.interactionOwner == MarkdownInteractionOwner.SelectableText &&
            current.parserRange.endExclusive == block.parserRange.start &&
            current.parserRange.length + block.parserRange.length <= targetChars
        if (!mayMerge) {
            flush()
            pending = block
            continue
        }
        val parserRange = SourceRange(current!!.parserRange.start, block.parserRange.endExclusive)
        val originalRange = document.mapParserRangeToOriginal(parserRange)
        val source = originalRange.slice(document.normalizedSource)
        pending = current.copy(
            key = stableRenderBlockKey(MarkdownRenderBlockKind.Prose, source),
            kind = MarkdownRenderBlockKind.Prose,
            parserRange = parserRange,
            originalRange = originalRange,
            source = source,
            renderSource = appendDocumentLinkDefinitions(
                parserRange.slice(document.parserSource),
                document.linkDefinitions,
                document.parserSource,
            ),
        )
    }
    flush()
    return output
}

private fun MarkdownBlock.renderKind(): MarkdownRenderBlockKind = when (this) {
    is MarkdownBlock.Paragraph -> MarkdownRenderBlockKind.Prose
    is MarkdownBlock.Heading -> MarkdownRenderBlockKind.Heading
    is MarkdownBlock.Quote -> MarkdownRenderBlockKind.Quote
    is MarkdownBlock.ListBlock -> MarkdownRenderBlockKind.List
    is MarkdownBlock.Table -> MarkdownRenderBlockKind.Table
    is MarkdownBlock.CodeFence -> MarkdownRenderBlockKind.CodeFence
    is MarkdownBlock.IndentedCode -> MarkdownRenderBlockKind.IndentedCode
    is MarkdownBlock.RawHtml -> MarkdownRenderBlockKind.RawHtml
    is MarkdownBlock.LinkDefinition -> MarkdownRenderBlockKind.LinkDefinition
    is MarkdownBlock.ThematicBreak -> MarkdownRenderBlockKind.ThematicBreak
    is MarkdownBlock.Unknown -> MarkdownRenderBlockKind.Unknown
}

private fun MarkdownBlock.Table.toRenderTable(parserSource: String): MarkdownRenderTable = MarkdownRenderTable(
    header = header.map { it.contentRange.slice(parserSource) },
    rows = rows.map { row -> row.map { it.contentRange.slice(parserSource) } },
)

private fun SourceRange.contains(other: SourceRange): Boolean =
    other.start >= start && other.endExclusive <= endExclusive

private fun MarkdownDocument.mapParserRangeToOriginal(range: SourceRange): SourceRange {
    val start = mapParserOffsetToOriginal(range.start, endBias = false)
    val end = mapParserOffsetToOriginal(range.endExclusive, endBias = true)
    return SourceRange(start, end)
}

private fun MarkdownDocument.mapParserOffsetToOriginal(offset: Int, endBias: Boolean): Int {
    var parserCursor = 0
    var originalCursor = 0
    math.forEach { placeholder ->
        if (offset <= placeholder.parserRange.start) {
            return originalCursor + (offset - parserCursor)
        }
        val gap = placeholder.parserRange.start - parserCursor
        parserCursor = placeholder.parserRange.endExclusive
        originalCursor += gap
        if (offset < parserCursor || (endBias && offset == parserCursor)) {
            return if (endBias) placeholder.originalRange.endExclusive else placeholder.originalRange.start
        }
        originalCursor = placeholder.originalRange.endExclusive
    }
    return originalCursor + (offset - parserCursor)
}

private fun appendDocumentLinkDefinitions(
    source: String,
    definitions: List<MarkdownLinkDefinition>,
    documentSource: String,
): String {
    val missing = definitions.filterNot { definition ->
        definition.semanticRange.slice(documentSource) in source
    }
    if (missing.isEmpty()) return source
    return buildString {
        append(source)
        if (isNotEmpty() && last() != '\n') append('\n')
        if (isNotEmpty() && !endsWith("\n\n")) append('\n')
        missing.forEachIndexed { index, definition ->
            if (index > 0) append('\n')
            append(definition.semanticRange.slice(documentSource))
        }
    }
}

private fun stableRenderBlockKey(kind: MarkdownRenderBlockKind, source: String): String =
    "${kind.name.lowercase()}-${source.hashCode().toUInt().toString(36)}-${source.length.toString(36)}"

private fun uniquifyRenderBlockKeys(blocks: List<MarkdownRenderBlock>): List<MarkdownRenderBlock> {
    val occurrences = mutableMapOf<String, Int>()
    return blocks.map { block ->
        val occurrence = occurrences.getOrDefault(block.key, 0)
        occurrences[block.key] = occurrence + 1
        block.copy(key = "${block.key}-${occurrence.toString(36)}")
    }
}
