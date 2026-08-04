package dev.minios.ocremote.ui.screens.chat

internal sealed interface MarkdownStreamingPlanResult {
    data class Success(val plan: MarkdownRenderPlan) : MarkdownStreamingPlanResult
    data class Failure(val source: String, val message: String) : MarkdownStreamingPlanResult
}

internal fun planStreamingMarkdown(
    source: String,
    previous: MarkdownRenderPlan? = null,
    targetChars: Int = MarkdownRenderPlanTargetChars,
): MarkdownStreamingPlanResult {
    return when (val parsed = parseMarkdownDocument(source)) {
        is MarkdownDocumentParseResult.Failure -> MarkdownStreamingPlanResult.Failure(parsed.source, parsed.message)
        is MarkdownDocumentParseResult.Success -> {
            val next = planMarkdownDocument(
                document = parsed.document,
                targetChars = targetChars,
                coalesceSelectableProse = false,
            )
            MarkdownStreamingPlanResult.Success(reconcileMarkdownRenderPlan(previous, next))
        }
    }
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
        if (index < reusableCount) block.copy(key = previous.blocks[index].key) else block
    }
    return next.copy(blocks = reconciled)
}

private fun MarkdownRenderBlock.hasSameCompletedIdentity(other: MarkdownRenderBlock): Boolean {
    return kind == other.kind &&
        semanticParserRange == other.semanticParserRange &&
        semanticOriginalRange == other.semanticOriginalRange &&
        semanticSource == other.semanticSource &&
        route == other.route &&
        interactionOwner == other.interactionOwner &&
        math.map(MarkdownMathPlaceholder::identity) == other.math.map(MarkdownMathPlaceholder::identity) &&
        table == other.table &&
        isOpen == other.isOpen
}

private fun MarkdownMathPlaceholder.identity(): List<Any> =
    listOf(source, display, delimiter, originalRange, parserRange)
