package dev.wuxie233.codecarry.ui.screens.chat

internal sealed interface MarkdownStreamingPlanResult {
    data class Success(val plan: MarkdownRenderPlan) : MarkdownStreamingPlanResult
    data class Failure(val source: String, val message: String) : MarkdownStreamingPlanResult
}

internal fun planStreamingMarkdown(
    source: String,
    previous: MarkdownRenderPlan? = null,
    targetChars: Int = MarkdownRenderPlanTargetChars,
): MarkdownStreamingPlanResult {
    if (previous != null && previous.originalSource == source && previous.blocks.isNotEmpty()) {
        return MarkdownStreamingPlanResult.Success(previous)
    }
    incrementalPlan(source, previous, targetChars)?.let { return it }
    return fullPlan(source, previous, targetChars)
}

private fun fullPlan(
    source: String,
    previous: MarkdownRenderPlan?,
    targetChars: Int,
): MarkdownStreamingPlanResult {
    return when (val parsed = parseMarkdownDocument(source)) {
        is MarkdownDocumentParseResult.Failure -> MarkdownStreamingPlanResult.Failure(parsed.source, parsed.message)
        is MarkdownDocumentParseResult.Success -> {
            val next = planMarkdownDocument(
                document = parsed.document,
                targetChars = targetChars,
                coalesceSelectableProse = true,
                isolateLastBlock = true,
            )
            MarkdownStreamingPlanResult.Success(reconcileMarkdownRenderPlan(previous, next))
        }
    }
}

private fun incrementalPlan(
    source: String,
    previous: MarkdownRenderPlan?,
    targetChars: Int,
): MarkdownStreamingPlanResult? {
    if (previous == null || previous.blocks.isEmpty()) return null
    val last = previous.blocks.last()
    val prefixBlocks = previous.blocks.dropLast(1)
    val prefixEnd = last.normalizedRange.start
    if (prefixEnd < 0 || prefixEnd > previous.originalSource.length) return null
    val prefixSource = previous.originalSource.substring(0, prefixEnd)
    if (!source.startsWith(prefixSource)) return null

    if (source.startsWith(previous.originalSource)) {
        val added = source.substring(previous.originalSource.length)
        if (added.isEmpty()) return MarkdownStreamingPlanResult.Success(previous)
        if (canExtendLastBlock(last, previous.originalSource, added)) {
            return MarkdownStreamingPlanResult.Success(extendLastBlock(previous, source, added))
        }
    }

    val coordinatesAlign = previous.originalSource.length == previous.parserSource.length &&
        last.normalizedRange.start == last.parserRange.start
    if (!coordinatesAlign) return null
    val suffix = source.substring(prefixEnd)
    return planOpenSuffix(
        previous = previous,
        prefixBlocks = prefixBlocks,
        prefixEnd = prefixEnd,
        parserPrefixEnd = last.parserRange.start,
        suffix = suffix,
        targetChars = targetChars,
    )
}

private fun extendLastBlock(
    previous: MarkdownRenderPlan,
    source: String,
    added: String,
): MarkdownRenderPlan {
    val last = previous.blocks.last().extendSelectableSource(added)
    return previous.copy(
        originalSource = source,
        parserSource = previous.parserSource + added,
        blocks = previous.blocks.dropLast(1) + last,
    )
}

private fun canExtendLastBlock(
    last: MarkdownRenderBlock,
    previousSource: String,
    added: String,
): Boolean {
    if (added.isEmpty() || last.math.isNotEmpty() || last.table != null) return false
    if (last.route != MarkdownRenderRoute.Compose) return false
    if (added.contains('$') || added.contains("\\[") || added.contains("\\(")) return false
    if (last.isOpen && last.kind == MarkdownRenderBlockKind.CodeFence) {
        return !suffixClosesFence(previousSource, added)
    }
    if (last.kind != MarkdownRenderBlockKind.Prose && last.kind != MarkdownRenderBlockKind.Heading) {
        return false
    }
    if (last.interactionOwner != MarkdownInteractionOwner.SelectableText) return false
    return !suffixIntroducesBlockBoundary(previousSource, added)
}

private fun suffixIntroducesBlockBoundary(previousSource: String, added: String): Boolean {
    if (added.contains("\n\n") || previousSource.endsWith('\n') && added.startsWith('\n')) return true
    if (previousSource.endsWith('\n') && looksLikeBlockStart(added)) return true
    var searchFrom = 0
    while (true) {
        val newline = added.indexOf('\n', searchFrom)
        if (newline < 0) return false
        if (looksLikeBlockStart(added.substring(newline + 1))) return true
        searchFrom = newline + 1
    }
}

private fun suffixClosesFence(previousSource: String, added: String): Boolean {
    val combinedTail = previousSource.substringAfterLast('\n', previousSource) + added
    return combinedTail.lineSequence().drop(1).any { line ->
        val trimmed = line.trim()
        trimmed == "```" || trimmed == "~~~" || trimmed.startsWith("```") || trimmed.startsWith("~~~")
    }
}

private fun looksLikeBlockStart(text: String): Boolean {
    val trimmed = text.trimStart(' ', '\t')
    if (trimmed.isEmpty()) return false
    return trimmed.startsWith("#") ||
        trimmed.startsWith("```") ||
        trimmed.startsWith("~~~") ||
        trimmed.startsWith(">") ||
        trimmed.startsWith("|") ||
        trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.startsWith("+ ") ||
        trimmed.startsWith("---") ||
        trimmed.startsWith("***") ||
        trimmed.firstOrNull()?.isDigit() == true && trimmed.contains(Regex("^\\d+[.)]\\s"))
}

private fun planOpenSuffix(
    previous: MarkdownRenderPlan,
    prefixBlocks: List<MarkdownRenderBlock>,
    prefixEnd: Int,
    parserPrefixEnd: Int,
    suffix: String,
    targetChars: Int,
): MarkdownStreamingPlanResult? {
    return when (val parsed = parseMarkdownDocument(suffix)) {
        is MarkdownDocumentParseResult.Failure -> null
        is MarkdownDocumentParseResult.Success -> {
            val suffixPlan = planMarkdownDocument(
                document = parsed.document,
                targetChars = targetChars,
                coalesceSelectableProse = true,
                isolateLastBlock = true,
            )
            val shifted = suffixPlan.blocks.map { block ->
                block.shiftRanges(parserDelta = parserPrefixEnd, normalizedDelta = prefixEnd)
            }
            val combined = uniquifySuffixKeys(prefixBlocks, shifted)
            MarkdownStreamingPlanResult.Success(
                MarkdownRenderPlan(
                    originalSource = previous.originalSource.substring(0, prefixEnd) + suffix,
                    parserSource = previous.parserSource.substring(0, parserPrefixEnd) + parsed.document.parserSource,
                    blocks = combined,
                ),
            )
        }
    }
}

private fun uniquifySuffixKeys(
    prefixBlocks: List<MarkdownRenderBlock>,
    suffixBlocks: List<MarkdownRenderBlock>,
): List<MarkdownRenderBlock> {
    val used = prefixBlocks.mapTo(mutableSetOf()) { it.key }
    val uniqueSuffix = suffixBlocks.map { block ->
        var key = block.key
        var occurrence = 0
        while (key in used) {
            occurrence += 1
            key = "${block.key}-x${occurrence.toString(36)}"
        }
        used += key
        if (key == block.key) block else block.copy(key = key)
    }
    return prefixBlocks + uniqueSuffix
}

internal fun reconcileMarkdownRenderPlan(
    previous: MarkdownRenderPlan?,
    next: MarkdownRenderPlan,
): MarkdownRenderPlan {
    if (previous == null || previous.blocks.isEmpty() || next.blocks.isEmpty()) return next
    val reusableCount = previous.blocks.zip(next.blocks)
        .takeWhile { (old, new) -> old.hasSameCompletedIdentity(new) }
        .size
    if (reusableCount == 0) return next
    val reconciled = next.blocks.mapIndexed { index, block ->
        if (index < reusableCount) previous.blocks[index] else block
    }
    return next.copy(blocks = reconciled)
}

private fun MarkdownRenderBlock.hasSameCompletedIdentity(other: MarkdownRenderBlock): Boolean {
    return kind == other.kind &&
        semanticParserRange == other.semanticParserRange &&
        semanticNormalizedRange == other.semanticNormalizedRange &&
        semanticSource == other.semanticSource &&
        route == other.route &&
        interactionOwner == other.interactionOwner &&
        math.map(MarkdownMathPlaceholder::identity) == other.math.map(MarkdownMathPlaceholder::identity) &&
        table == other.table &&
        isOpen == other.isOpen
}

private fun MarkdownMathPlaceholder.identity(): List<Any> =
    listOf(source, display, delimiter, normalizedRange, parserRange)
