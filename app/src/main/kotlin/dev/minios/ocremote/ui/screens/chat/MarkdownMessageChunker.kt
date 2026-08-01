package dev.minios.ocremote.ui.screens.chat

internal const val MarkdownMessageChunkTargetChars = 6_000

internal data class MarkdownMessageChunk(
    val source: String,
    val renderMarkdown: String,
)

internal fun planMarkdownMessageChunks(
    placeholderMarkdown: String,
    targetChars: Int = MarkdownMessageChunkTargetChars,
): List<MarkdownMessageChunk> {
    require(targetChars > 0)
    if (placeholderMarkdown.isEmpty()) return listOf(MarkdownMessageChunk("", ""))

    val scan = scanMarkdownBlocks(placeholderMarkdown)
    val plannedChunks = packBlocks(scan.blocks, targetChars)
    return plannedChunks.map { chunk ->
        val missingDefinitions = scan.rootLinkDefinitions.filterNot(chunk.renderMarkdown::contains)
        chunk.copy(renderMarkdown = appendLinkDefinitions(chunk.renderMarkdown, missingDefinitions))
    }
}

internal data class MarkdownBlockScan(
    val blocks: List<MarkdownSourceBlock>,
    val rootLinkDefinitions: List<String>,
)

internal sealed interface MarkdownSourceBlock {
    val source: String
    val sourceRange: IntRange

    data class Prose(override val source: String, override val sourceRange: IntRange) : MarkdownSourceBlock
    data class MathPlaceholder(override val source: String, override val sourceRange: IntRange) : MarkdownSourceBlock
    data class Fence(override val source: String, override val sourceRange: IntRange) : MarkdownSourceBlock
    data class AtomicStructure(override val source: String, override val sourceRange: IntRange) : MarkdownSourceBlock
    data class Table(
        val header: String,
        val divider: String,
        val rows: List<String>,
        override val sourceRange: IntRange,
    ) : MarkdownSourceBlock {
        override val source: String = header + divider + rows.joinToString(separator = "")
    }
}

private data class MarkdownTableScan(
    val block: MarkdownSourceBlock.Table,
    val consumedLines: Int,
)

private data class MarkdownAtomicStructureScan(
    val block: MarkdownSourceBlock.AtomicStructure,
    val consumedLines: Int,
)

private data class MarkdownLine(val raw: String, val start: Int, val endExclusive: Int) {
    val content: String = raw.removeSuffix("\n").removeSuffix("\r")
}

private data class RootLinkDefinition(
    val source: String,
    val consumedLines: Int,
)

internal fun scanMarkdownBlocks(markdown: String): MarkdownBlockScan {
    val lines = markdownLines(markdown)
    val blocks = mutableListOf<MarkdownSourceBlock>()
    val definitions = linkedSetOf<String>()
    val block = StringBuilder()
    var fence: FenceMarker? = null
    var index = 0
    var blockStart = 0

    fun flushProse() {
        if (block.isNotEmpty()) {
            blocks += proseBlocks(block.toString(), blockStart)
            block.clear()
        }
    }

    fun flushFence() {
        val source = block.toString()
        blocks += MarkdownSourceBlock.Fence(source, blockStart..(blockStart + source.length - 1))
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
            blockStart = line.start
            block.append(line.raw)
            index++
            continue
        }

        val table = markdownTable(lines, index)
        if (table != null) {
            flushProse()
            blocks += table.block
            index += table.consumedLines
            continue
        }

        val definition = rootLinkDefinition(lines, index)
        if (definition != null) {
            definitions += definition.source
            repeat(definition.consumedLines) { consumedOffset ->
                if (block.isEmpty()) blockStart = lines[index + consumedOffset].start
                block.append(lines[index + consumedOffset].raw)
            }
            index += definition.consumedLines
            continue
        }

        val atomicStructure = markdownAtomicStructure(lines, index)
        if (atomicStructure != null) {
            flushProse()
            blocks += atomicStructure.block
            index += atomicStructure.consumedLines
            continue
        }

        if (block.isEmpty()) blockStart = line.start
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
    )
}

private val MathPlaceholderRegex = Regex("xMJXMATH\\d+HTAMXJMx")

private fun proseBlocks(source: String, start: Int): List<MarkdownSourceBlock> {
    val blocks = mutableListOf<MarkdownSourceBlock>()
    var cursor = 0
    MathPlaceholderRegex.findAll(source).forEach { match ->
        if (match.range.first > cursor) {
            blocks += MarkdownSourceBlock.Prose(source.substring(cursor, match.range.first), start + cursor..(start + match.range.first - 1))
        }
        blocks += MarkdownSourceBlock.MathPlaceholder(match.value, start + match.range.first..(start + match.range.last))
        cursor = match.range.last + 1
    }
    if (cursor < source.length) {
        blocks += MarkdownSourceBlock.Prose(source.substring(cursor), start + cursor..(start + source.length - 1))
    }
    return blocks
}

private enum class MarkdownAtomicStructureKind {
    List,
    BlockQuote,
    RawHtml,
    IndentedCode,
}

private fun markdownAtomicStructure(
    lines: List<MarkdownLine>,
    index: Int,
): MarkdownAtomicStructureScan? {
    val kind = atomicStructureKind(lines[index].content) ?: return null
    val endExclusive = when (kind) {
        MarkdownAtomicStructureKind.List -> scanContainerStructure(lines, index, ::isRootListLine)
        MarkdownAtomicStructureKind.BlockQuote -> scanContainerStructure(lines, index, ::isRootBlockQuoteLine)
        MarkdownAtomicStructureKind.RawHtml -> scanRawHtmlStructure(lines, index)
        MarkdownAtomicStructureKind.IndentedCode -> scanIndentedCodeStructure(lines, index)
    }
    val source = buildString {
        for (lineIndex in index until endExclusive) append(lines[lineIndex].raw)
    }
    return MarkdownAtomicStructureScan(
        block = MarkdownSourceBlock.AtomicStructure(source, lines[index].start..(lines[endExclusive - 1].endExclusive - 1)),
        consumedLines = endExclusive - index,
    )
}

private fun atomicStructureKind(line: String): MarkdownAtomicStructureKind? {
    if (line.startsWith('\t') || line.takeWhile { it == ' ' }.length >= 4) {
        return MarkdownAtomicStructureKind.IndentedCode
    }
    return when {
        isRootBlockQuoteLine(line) -> MarkdownAtomicStructureKind.BlockQuote
        isRootListLine(line) -> MarkdownAtomicStructureKind.List
        isRootRawHtmlLine(line) -> MarkdownAtomicStructureKind.RawHtml
        else -> null
    }
}

private fun scanContainerStructure(
    lines: List<MarkdownLine>,
    start: Int,
    isContainerMarker: (String) -> Boolean,
): Int {
    var index = start + 1
    var afterBlank = false
    while (index < lines.size) {
        val line = lines[index].content
        if (line.isBlank()) {
            afterBlank = true
            index++
            continue
        }
        val indentation = line.takeWhile { it == ' ' }.length
        if (isContainerMarker(line) || indentation > 0 || line.startsWith('\t')) {
            afterBlank = false
            index++
            continue
        }
        if (afterBlank) break

        // A container may lazily continue on an unmarked line until a blank boundary.
        index++
    }
    return index
}

private fun scanIndentedCodeStructure(lines: List<MarkdownLine>, start: Int): Int {
    var index = start + 1
    while (index < lines.size) {
        val line = lines[index].content
        if (line.isBlank() || line.startsWith('\t') || line.takeWhile { it == ' ' }.length >= 4) {
            index++
        } else {
            break
        }
    }
    return index
}

private fun scanRawHtmlStructure(lines: List<MarkdownLine>, start: Int): Int {
    val startLine = lines[start].content.dropRootIndent()
    val closingMarker = when {
        startLine.startsWith("<!--") -> "-->"
        startLine.startsWith("<?") -> "?>"
        startLine.startsWith("<![CDATA[", ignoreCase = true) -> "]]\u003e"
        startLine.startsWith("<!") && !startLine.startsWith("<!DOCTYPE", ignoreCase = true) -> ">"
        else -> null
    }
    if (closingMarker != null) {
        var index = start
        while (index < lines.size) {
            index++
            if (closingMarker in lines[index - 1].content) break
        }
        return index
    }

    var index = start + 1
    while (index < lines.size) {
        index++
        if (lines[index - 1].content.isBlank()) break
    }
    return index
}

private fun String.dropRootIndent(): String {
    val indentation = takeWhile { it == ' ' }.length
    return if (indentation <= 3) drop(indentation) else this
}

private fun isRootBlockQuoteLine(line: String): Boolean = line.dropRootIndent().startsWith('>')

private fun isRootListLine(line: String): Boolean =
    MarkdownListMarkerRegex.containsMatchIn(line.dropRootIndent())

private fun isRootRawHtmlLine(line: String): Boolean =
    MarkdownRawHtmlBlockRegex.containsMatchIn(line.dropRootIndent())

private fun markdownLines(markdown: String): List<MarkdownLine> {
    val lines = mutableListOf<MarkdownLine>()
    var offset = 0
    while (offset < markdown.length) {
        val newline = markdown.indexOf('\n', offset)
        val end = if (newline >= 0) newline + 1 else markdown.length
        lines += MarkdownLine(markdown.substring(offset, end), offset, end)
        offset = end
    }
    return lines
}

private fun markdownTable(lines: List<MarkdownLine>, index: Int): MarkdownTableScan? {
    val header = lines.getOrNull(index) ?: return null
    val divider = lines.getOrNull(index + 1) ?: return null
    if (isUnsafeSplitLine(header.content) || RootLinkDefinitionCandidateRegex.containsMatchIn(header.content)) {
        return null
    }
    if (!isTableRow(header.content) || !isTableRow(divider.content)) return null

    val headerCells = splitMarkdownTableRow(header.content)
    val dividerCells = splitMarkdownTableRow(divider.content)
    if (headerCells.isEmpty() || dividerCells.size != headerCells.size) return null
    val dividerPattern = Regex(":?-{3,}:?")
    if (!dividerCells.all { dividerPattern.matches(it.trim()) }) return null

    val rows = mutableListOf<String>()
    var consumedLines = 2
    while (true) {
        val row = lines.getOrNull(index + consumedLines) ?: break
        if (RootLinkDefinitionCandidateRegex.containsMatchIn(row.content)) break
        if (!isTableRow(row.content)) break
        rows += row.raw
        consumedLines++
    }
    return MarkdownTableScan(
        block = MarkdownSourceBlock.Table(
            header.raw,
            divider.raw,
            rows,
            header.start..(lines[index + consumedLines - 1].endExclusive - 1),
        ),
        consumedLines = consumedLines,
    )
}

private fun isTableRow(line: String): Boolean {
    if (line.startsWith('\t')) return false
    if (line.takeWhile { it == ' ' }.length > 3) return false
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.indices.any { index ->
        trimmed[index] == '|' && !trimmed.isTablePipeEscaped(index)
    }
}

private fun String.isTablePipeEscaped(index: Int): Boolean {
    var slashCount = 0
    var position = index - 1
    while (position >= 0 && this[position] == '\\') {
        slashCount++
        position--
    }
    return slashCount % 2 == 1
}

private fun packBlocks(
    blocks: List<MarkdownSourceBlock>,
    targetChars: Int,
): List<MarkdownMessageChunk> {
    val chunks = mutableListOf<MarkdownMessageChunk>()
    val current = StringBuilder()

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            val source = current.toString()
            chunks += MarkdownMessageChunk(source = source, renderMarkdown = source)
            current.clear()
        }
    }

    for (block in blocks) {
        when (block) {
            is MarkdownSourceBlock.Prose, is MarkdownSourceBlock.MathPlaceholder -> {
                if (current.isNotEmpty() && current.length + block.source.length > targetChars) {
                    flushCurrent()
                }
                if (block.source.length > targetChars) {
                    flushCurrent()
                    chunks += splitOversizedProse(block.source, targetChars)
                } else current.append(block.source)
            }
            is MarkdownSourceBlock.AtomicStructure -> {
                if (current.isNotEmpty() && current.length + block.source.length > targetChars) {
                    flushCurrent()
                }
                if (block.source.length > targetChars) {
                    flushCurrent()
                    chunks += MarkdownMessageChunk(source = block.source, renderMarkdown = block.source)
                } else {
                    current.append(block.source)
                }
            }
            is MarkdownSourceBlock.Fence -> {
                flushCurrent()
                chunks += MarkdownMessageChunk(source = block.source, renderMarkdown = block.source)
            }
            is MarkdownSourceBlock.Table -> {
                flushCurrent()
                chunks += splitTable(block, targetChars)
            }
        }
    }
    flushCurrent()
    return chunks
}

private fun splitOversizedProse(source: String, targetChars: Int): List<MarkdownMessageChunk> {
    val chunks = mutableListOf<MarkdownMessageChunk>()
    var offset = 0
    while (offset < source.length) {
        val end = (offset + targetChars).coerceAtMost(source.length)
        val piece = source.substring(offset, end)
        chunks += MarkdownMessageChunk(piece, piece)
        offset = end
    }
    return chunks
}

private fun splitTable(
    table: MarkdownSourceBlock.Table,
    targetChars: Int,
): List<MarkdownMessageChunk> {
    val chunks = mutableListOf<MarkdownMessageChunk>()
    var rowIndex = 0
    var first = true
    while (first || rowIndex < table.rows.size) {
        val prefix = table.header + table.divider
        val rows = StringBuilder()
        while (rowIndex < table.rows.size) {
            val row = table.rows[rowIndex]
            if (rows.isNotEmpty() && prefix.length + rows.length + row.length > targetChars) break
            rows.append(row)
            rowIndex++
            if (prefix.length + rows.length > targetChars) break
        }
        val rowSource = rows.toString()
        chunks += MarkdownMessageChunk(
            source = (if (first) prefix else "") + rowSource,
            renderMarkdown = prefix + rowSource,
        )
        first = false
        if (table.rows.isEmpty()) break
    }
    return chunks
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
