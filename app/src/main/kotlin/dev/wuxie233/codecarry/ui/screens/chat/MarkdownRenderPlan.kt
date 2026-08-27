package dev.wuxie233.codecarry.ui.screens.chat

internal const val MarkdownRenderPlanTargetChars = 6_000

internal data class MarkdownRenderPlan(
    val originalSource: String,
    val parserSource: String,
    val blocks: List<MarkdownRenderBlock>,
)

internal data class MarkdownRenderBlock(
    val key: String,
    val kind: MarkdownRenderBlockKind,
    val semanticParserRange: SourceRange,
    val semanticNormalizedRange: SourceRange,
    val parserRange: SourceRange,
    val normalizedRange: SourceRange,
    val semanticSource: String,
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
    coalesceSelectableProse: Boolean = true,
    isolateLastBlock: Boolean = false,
): MarkdownRenderPlan {
    require(targetChars > 0)
    val candidates = document.blocks.flatMapIndexed { index, block ->
        planBlock(
            document = document,
            block = block,
            targetChars = targetChars,
            ownedRange = if (index == 0) {
                SourceRange(0, block.ownedRange.endExclusive)
            } else {
                block.ownedRange
            },
        )
    }
    val planned = if (coalesceSelectableProse && isolateLastBlock && candidates.size > 1) {
        coalesceProse(document, candidates.dropLast(1), targetChars) + candidates.last()
    } else if (coalesceSelectableProse) {
        coalesceProse(document, candidates, targetChars)
    } else {
        candidates
    }
    val coalesced = uniquifyRenderBlockKeys(planned)
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
    ownedRange: SourceRange,
): List<MarkdownRenderBlock> {
    if (block is MarkdownBlock.Paragraph && ownedRange.length > targetChars) {
        return splitLargeProse(document, block, ownedRange, targetChars)
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
    val parserRange = ownedRange
    val normalizedRange = document.mapParserRangeToNormalized(parserRange)
    val semanticNormalizedRange = document.mapParserRangeToNormalized(block.semanticRange)
    val source = normalizedRange.slice(document.normalizedSource)
    val semanticSource = semanticNormalizedRange.slice(document.normalizedSource)
    val parserSource = parserRange.slice(document.parserSource)
    val renderSource = appendDocumentLinkDefinitions(
        source = parserSource,
        definitions = document.linkDefinitions,
        documentSource = document.parserSource,
    )
    return listOf(
        MarkdownRenderBlock(
            key = stableRenderBlockKey(kind, semanticSource),
            kind = kind,
            semanticParserRange = block.semanticRange,
            semanticNormalizedRange = semanticNormalizedRange,
            parserRange = parserRange,
            normalizedRange = normalizedRange,
            semanticSource = semanticSource,
            source = source,
            renderSource = renderSource,
            route = route,
            interactionOwner = interaction,
            math = math,
            table = (block as? MarkdownBlock.Table)?.toRenderTable(document),
            isOpen = block is MarkdownBlock.CodeFence && !block.isClosed,
        ),
    )
}

private fun splitLargeProse(
    document: MarkdownDocument,
    block: MarkdownBlock.Paragraph,
    ownedRange: SourceRange,
    targetChars: Int,
): List<MarkdownRenderBlock> {
    val ranges = mutableListOf<SourceRange>()
    var cursor = ownedRange.start
    while (cursor < ownedRange.endExclusive) {
        val ideal = (cursor + targetChars).coerceAtMost(ownedRange.endExclusive)
        val containingMath = document.math.firstOrNull { placeholder ->
            ideal > placeholder.parserRange.start && ideal < placeholder.parserRange.endExclusive
        }
        val safeIdeal = when {
            containingMath == null -> ideal
            containingMath.parserRange.start > cursor -> containingMath.parserRange.start
            else -> containingMath.parserRange.endExclusive
        }
        val end = if (safeIdeal == ownedRange.endExclusive || containingMath != null) {
            safeIdeal
        } else {
            document.parserSource.lastIndexOf('\n', safeIdeal).takeIf { it >= cursor }?.plus(1) ?: safeIdeal
        }
        ranges += SourceRange(cursor, end)
        cursor = end
    }
    return ranges.map { parserRange ->
        val normalizedRange = document.mapParserRangeToNormalized(parserRange)
        val math = document.math.filter { parserRange.contains(it.parserRange) }
        val source = normalizedRange.slice(document.normalizedSource)
        val parserSource = parserRange.slice(document.parserSource)
        MarkdownRenderBlock(
            key = stableRenderBlockKey(MarkdownRenderBlockKind.Prose, source),
            kind = MarkdownRenderBlockKind.Prose,
            semanticParserRange = parserRange,
            semanticNormalizedRange = normalizedRange,
            parserRange = parserRange,
            normalizedRange = normalizedRange,
            semanticSource = source,
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
        val normalizedRange = document.mapParserRangeToNormalized(parserRange)
        val source = normalizedRange.slice(document.normalizedSource)
        pending = current.copy(
            key = stableRenderBlockKey(MarkdownRenderBlockKind.Prose, source),
            kind = MarkdownRenderBlockKind.Prose,
            semanticParserRange = SourceRange(
                current.semanticParserRange.start,
                block.semanticParserRange.endExclusive,
            ),
            semanticNormalizedRange = SourceRange(
                current.semanticNormalizedRange.start,
                block.semanticNormalizedRange.endExclusive,
            ),
            parserRange = parserRange,
            normalizedRange = normalizedRange,
            semanticSource = source,
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

private fun MarkdownBlock.Table.toRenderTable(document: MarkdownDocument): MarkdownRenderTable = MarkdownRenderTable(
    header = header.map { cell -> document.renderTableCell(cell) },
    rows = rows.map { row -> row.map { cell -> document.renderTableCell(cell) } },
)

private fun MarkdownDocument.renderTableCell(cell: MarkdownTableCell): String {
    val normalizedRange = mapParserRangeToNormalized(cell.contentRange)
    return renderTableCellText(normalizedRange.slice(normalizedSource))
}

internal fun renderTableCellText(source: String): String = source
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace("**", "")
    .replace("`", "")
    .replace("\\|", "|")
    .trim()

private fun SourceRange.contains(other: SourceRange): Boolean =
    other.start >= start && other.endExclusive <= endExclusive

private fun MarkdownDocument.mapParserRangeToNormalized(range: SourceRange): SourceRange {
    val start = mapParserOffsetToNormalized(range.start, endBias = false)
    val end = mapParserOffsetToNormalized(range.endExclusive, endBias = true)
    return SourceRange(start, end)
}

private fun MarkdownDocument.mapParserOffsetToNormalized(offset: Int, endBias: Boolean): Int {
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
            return if (endBias) placeholder.normalizedRange.endExclusive else placeholder.normalizedRange.start
        }
        originalCursor = placeholder.normalizedRange.endExclusive
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

internal fun stableRenderBlockKey(kind: MarkdownRenderBlockKind, source: String): String =
    "${kind.name.lowercase()}-${source.hashCode().toUInt().toString(36)}-${source.length.toString(36)}"

internal fun MarkdownRenderBlock.shiftRanges(parserDelta: Int, normalizedDelta: Int): MarkdownRenderBlock {
    if (parserDelta == 0 && normalizedDelta == 0) return this
    return copy(
        semanticParserRange = semanticParserRange.shift(parserDelta),
        semanticNormalizedRange = semanticNormalizedRange.shift(normalizedDelta),
        parserRange = parserRange.shift(parserDelta),
        normalizedRange = normalizedRange.shift(normalizedDelta),
        math = math.map { placeholder ->
            placeholder.copy(
                parserRange = placeholder.parserRange.shift(parserDelta),
                normalizedRange = placeholder.normalizedRange.shift(normalizedDelta),
            )
        },
    )
}

internal fun MarkdownRenderBlock.extendSelectableSource(extra: String): MarkdownRenderBlock {
    if (extra.isEmpty()) return this
    val nextSource = source + extra
    val occurrence = key.substringAfterLast('-')
    return copy(
        key = "${stableRenderBlockKey(kind, nextSource)}-$occurrence",
        semanticParserRange = semanticParserRange.copy(
            endExclusive = semanticParserRange.endExclusive + extra.length,
        ),
        semanticNormalizedRange = semanticNormalizedRange.copy(
            endExclusive = semanticNormalizedRange.endExclusive + extra.length,
        ),
        parserRange = parserRange.copy(endExclusive = parserRange.endExclusive + extra.length),
        normalizedRange = normalizedRange.copy(endExclusive = normalizedRange.endExclusive + extra.length),
        semanticSource = semanticSource + extra,
        source = nextSource,
        renderSource = renderSource + extra,
    )
}

private fun uniquifyRenderBlockKeys(blocks: List<MarkdownRenderBlock>): List<MarkdownRenderBlock> {
    val occurrences = mutableMapOf<String, Int>()
    return blocks.map { block ->
        val occurrence = occurrences.getOrDefault(block.key, 0)
        occurrences[block.key] = occurrence + 1
        block.copy(key = "${block.key}-${occurrence.toString(36)}")
    }
}
