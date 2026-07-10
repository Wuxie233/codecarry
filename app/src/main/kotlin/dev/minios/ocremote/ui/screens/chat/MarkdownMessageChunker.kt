package dev.minios.ocremote.ui.screens.chat

internal const val MarkdownMessageChunkTargetChars = 6_000
internal const val MarkdownMessageMaxChunks = 12

internal data class MarkdownMessageChunk(
    val source: String,
    val renderMarkdown: String,
)

internal fun planMarkdownMessageChunks(
    placeholderMarkdown: String,
    targetChars: Int = MarkdownMessageChunkTargetChars,
    maxChunks: Int = MarkdownMessageMaxChunks,
): List<MarkdownMessageChunk> {
    require(targetChars > 0)
    require(maxChunks > 0)
    if (placeholderMarkdown.isEmpty()) return listOf(MarkdownMessageChunk("", ""))

    val scan = scanMarkdownBlocks(placeholderMarkdown)
    val plannedSources = if (scan.requiresWholeFallback) {
        listOf(placeholderMarkdown)
    } else {
        packBlocks(scan.blocks, targetChars, maxChunks) ?: listOf(placeholderMarkdown)
    }
    return plannedSources.map { source ->
        val missingDefinitions = scan.rootLinkDefinitions.filterNot(source::contains)
        MarkdownMessageChunk(
            source = source,
            renderMarkdown = appendLinkDefinitions(source, missingDefinitions),
        )
    }
}

private data class MarkdownBlockScan(
    val blocks: List<MarkdownSourceBlock>,
    val rootLinkDefinitions: List<String>,
    val requiresWholeFallback: Boolean,
)

private sealed interface MarkdownSourceBlock {
    val source: String

    data class Prose(override val source: String) : MarkdownSourceBlock
    data class Fence(override val source: String) : MarkdownSourceBlock
}

private data class MarkdownLine(val raw: String) {
    val content: String = raw.removeSuffix("\n").removeSuffix("\r")
}

private data class RootLinkDefinition(
    val source: String,
    val consumedLines: Int,
)

private fun scanMarkdownBlocks(markdown: String): MarkdownBlockScan {
    val lines = markdownLines(markdown)
    val blocks = mutableListOf<MarkdownSourceBlock>()
    val definitions = linkedSetOf<String>()
    val block = StringBuilder()
    var fence: FenceMarker? = null
    var requiresWholeFallback = false
    var index = 0

    fun flushProse() {
        if (block.isNotEmpty()) {
            blocks += MarkdownSourceBlock.Prose(block.toString())
            block.clear()
        }
    }

    fun flushFence() {
        blocks += MarkdownSourceBlock.Fence(block.toString())
        block.clear()
    }

    while (index < lines.size) {
        val line = lines[index]
        val activeFence = fence
        if (activeFence != null) {
            block.append(line.raw)
            if (activeFence.closes(line.content)) {
                fence = null
                flushFence()
            }
            index++
            continue
        }

        val openingFence = openingFence(line.content)
        if (openingFence != null) {
            flushProse()
            fence = openingFence
            block.append(line.raw)
            index++
            continue
        }

        val definition = rootLinkDefinition(lines, index)
        if (definition != null) {
            definitions += definition.source
            repeat(definition.consumedLines) { consumedOffset ->
                block.append(lines[index + consumedOffset].raw)
            }
            index += definition.consumedLines
            continue
        }

        if (RootLinkDefinitionCandidateRegex.containsMatchIn(line.content) || isUnsafeSplitLine(line.content)) {
            requiresWholeFallback = true
        }
        block.append(line.raw)
        if (line.content.isBlank()) flushProse()
        index++
    }

    if (block.isNotEmpty()) {
        if (fence == null) flushProse() else flushFence()
    }
    return MarkdownBlockScan(
        blocks = blocks,
        rootLinkDefinitions = definitions.toList(),
        requiresWholeFallback = requiresWholeFallback,
    )
}

private fun markdownLines(markdown: String): List<MarkdownLine> {
    val lines = mutableListOf<MarkdownLine>()
    var offset = 0
    while (offset < markdown.length) {
        val newline = markdown.indexOf('\n', offset)
        val end = if (newline >= 0) newline + 1 else markdown.length
        lines += MarkdownLine(markdown.substring(offset, end))
        offset = end
    }
    return lines
}

private fun packBlocks(
    blocks: List<MarkdownSourceBlock>,
    targetChars: Int,
    maxChunks: Int,
): List<String>? {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            chunks += current.toString()
            current.clear()
        }
    }

    for (block in blocks) {
        when (block) {
            is MarkdownSourceBlock.Prose -> {
                if (block.source.length > targetChars) return null
                if (current.isNotEmpty() && current.length + block.source.length > targetChars) {
                    flushCurrent()
                }
                current.append(block.source)
            }
            is MarkdownSourceBlock.Fence -> {
                flushCurrent()
                chunks += block.source
            }
        }
    }
    flushCurrent()
    return chunks.takeIf { it.size <= maxChunks }
}

private fun appendLinkDefinitions(source: String, definitions: List<String>): String {
    if (definitions.isEmpty()) return source
    return buildString(source.length + definitions.sumOf { it.length + 1 } + 2) {
        append(source)
        if (isNotEmpty() && last() != '\n') append('\n')
        if (isNotEmpty() && !endsWith("\n\n")) append('\n')
        definitions.forEachIndexed { index, definition ->
            if (index > 0) append('\n')
            append(definition)
        }
    }
}

private data class FenceMarker(val marker: Char, val length: Int) {
    fun closes(line: String): Boolean {
        val indentation = line.takeWhile { it == ' ' }.length
        if (indentation > 3) return false
        val trimmed = line.drop(indentation)
        val markerCount = trimmed.takeWhile { it == marker }.length
        return markerCount >= length && trimmed.drop(markerCount).isBlank()
    }
}

private fun openingFence(line: String): FenceMarker? {
    val indentation = line.takeWhile { it == ' ' }.length
    if (indentation > 3) return null
    val trimmed = line.drop(indentation)
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val markerCount = trimmed.takeWhile { it == marker }.length
    return if (markerCount >= 3) FenceMarker(marker, markerCount) else null
}

private fun rootLinkDefinition(lines: List<MarkdownLine>, index: Int): RootLinkDefinition? {
    val line = lines[index].content
    if (!RootLinkDefinitionRegex.matches(line)) return null
    val continuation = lines.getOrNull(index + 1)?.content
        ?.takeIf(RootLinkDefinitionTitleContinuationRegex::matches)
    return RootLinkDefinition(
        source = if (continuation == null) line else "$line\n$continuation",
        consumedLines = if (continuation == null) 1 else 2,
    )
}

private fun isUnsafeSplitLine(line: String): Boolean {
    if (line.isBlank()) return false
    if (line.startsWith('\t')) return true
    val indentation = line.takeWhile { it == ' ' }.length
    if (indentation >= 4) return true
    val root = line.drop(indentation)
    return root.startsWith('>') ||
        MarkdownListMarkerRegex.containsMatchIn(root) ||
        MarkdownRawHtmlBlockRegex.containsMatchIn(root)
}

private val RootLinkDefinitionCandidateRegex = Regex("^[ ]{0,3}\\[[^]]+]:")
private val RootLinkDefinitionRegex = Regex(
    "^[ ]{0,3}\\[[^]\\r\\n]+]:[ \\t]+(?:<[^>\\r\\n]+>|\\S+)(?:[ \\t]+(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|\\([^\\r\\n)]*\\)))?[ \\t]*$",
)
private val RootLinkDefinitionTitleContinuationRegex = Regex(
    "^[ \\t]+(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|\\([^\\r\\n)]*\\))[ \\t]*$",
)
private val MarkdownListMarkerRegex = Regex("^(?:[-+*]|\\d{1,9}[.)])(?:[ \\t]+|$)")
private val MarkdownRawHtmlBlockRegex = Regex(
    "^<(?:!DOCTYPE\\b|!--|\\?|![A-Z]|/?[A-Za-z][A-Za-z0-9-]*(?:[ \\t>/]|$))",
    RegexOption.IGNORE_CASE,
)
