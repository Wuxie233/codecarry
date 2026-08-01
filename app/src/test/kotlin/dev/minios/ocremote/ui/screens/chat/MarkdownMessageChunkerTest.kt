package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMessageChunkerTest {
    @Test
    fun `short placeholder markdown stays in one chunk`() {
        val source = "Short paragraph with xMJXMATH0HTAMXJMx."

        val chunks = planMarkdownMessageChunks(source)

        assertEquals(listOf(source), chunks.map { it.source })
        assertEquals(source, chunks.single().renderMarkdown)
    }

    @Test
    fun `29k paragraph message produces four to six chunks`() {
        val source = buildString {
            repeat(145) { index ->
                append("Paragraph $index ")
                append("content ".repeat(23))
                if (index != 144) append("\n\n")
            }
        }

        val chunks = planMarkdownMessageChunks(source)

        assertTrue("fixture should be about 29k chars, length=${source.length}", source.length in 28_000..30_000)
        assertTrue("expected 4-6 chunks, count=${chunks.size}", chunks.size in 4..6)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertTrue(chunks.all { it.source.length <= MarkdownMessageChunkTargetChars })
    }

    @Test
    fun `source reconstructs exactly while root link definitions remain available`() {
        val source = """
            First chunk uses [docs][guide].

            ${"lead ".repeat(12)}

            Second chunk also uses [docs][guide].

            [guide]: https://example.com/docs "Guide"
        """.trimIndent()

        val chunks = planMarkdownMessageChunks(source, targetChars = 80)

        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertEquals(1, RootDefinition.findAll(chunk.renderMarkdown).count())
        }
    }

    @Test
    fun `indented definitions with multiline titles remain available in every chunk`() {
        val definition = "  [guide]: https://example.com/docs\n    \"Long guide title\""
        val source = buildString {
            append("First chunk uses [docs][guide].\n\n")
            append("lead ".repeat(10))
            append("\n\nSecond chunk also uses [docs][guide].\n\n")
            append(definition)
        }

        val chunks = planMarkdownMessageChunks(source, targetChars = 90)

        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        chunks.forEach { chunk ->
            assertEquals(1, Regex("(?m)^[ ]{0,3}\\[guide]:").findAll(chunk.renderMarkdown).count())
            assertTrue(definition in chunk.renderMarkdown)
        }
    }

    @Test
    fun `planner never splits inside an oversized fenced code block`() {
        val fenced = buildString {
            append("```text\n")
            repeat(30) { append("wide code line $it ${"x".repeat(20)}\n\n") }
            append("```")
        }
        val source = "Before.\n\n$fenced\n\nAfter."

        val chunks = planMarkdownMessageChunks(source, targetChars = 120)

        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertEquals(1, chunks.count { fenced in it.source })
        assertTrue(chunks.single { fenced in it.source }.source.length > 120)
    }

    @Test
    fun `fenced block after substantial prose starts an atomic fresh chunk`() {
        val lead = "Lead paragraph ${"content ".repeat(90)}\n\n"
        val fence = "```text\n信号是连续的、网络只吃向量\n```\n"
        val following = "\nFollowing paragraph."
        val source = lead + fence + following

        val chunks = planMarkdownMessageChunks(source, targetChars = 2_000)

        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertEquals(listOf(lead, fence, following), chunks.map { it.source })
    }

    @Test
    fun `global math placeholders remain mapped across chunks`() {
        val markdown = """
            First ${'$'}x${'$'} formula.

            ${"middle ".repeat(30)}

            Second ${'$'}${'$'}y^2${'$'}${'$'} formula.
        """.trimIndent()
        val (placeholderMarkdown, math) = buildPlaceholderMarkdown(markdown)

        val chunks = planMarkdownMessageChunks(placeholderMarkdown, targetChars = 100)

        assertEquals(listOf("x", "y^2"), math.map { it.source })
        assertEquals(1, chunks.count { "xMJXMATH0HTAMXJMx" in it.source })
        assertEquals(1, chunks.count { "xMJXMATH1HTAMXJMx" in it.source })
        assertEquals(placeholderMarkdown, chunks.joinToString(separator = "") { it.source })
    }

    @Test
    fun `typed top level blocks expose exact source ranges`() {
        val source = "Intro xMJXMATH0HTAMXJMx.\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n```text\ncode\n```"

        val blocks = scanMarkdownBlocks(source).blocks

        assertTrue(blocks.any { it is MarkdownSourceBlock.Prose })
        assertTrue(blocks.any { it is MarkdownSourceBlock.MathPlaceholder })
        assertTrue(blocks.any { it is MarkdownSourceBlock.Table })
        assertTrue(blocks.any { it is MarkdownSourceBlock.Fence })
        blocks.forEach { block ->
            assertEquals(block.source, source.substring(block.sourceRange.first, block.sourceRange.last + 1))
        }
        assertEquals(source, blocks.joinToString(separator = "") { it.source })
    }

    @Test
    fun `interaction blocks always own row boundaries`() {
        val table = "| A | B |\n| --- | --- |\n| 1 | 2 |\n"
        val fence = "```text\ncode\n```\n"
        val source = "Before.\n\n$table\nBetween.\n\n$fence\nAfter."

        val chunks = planMarkdownMessageChunks(source)

        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertEquals(table, chunks.single { it.source == table }.source)
        assertEquals(fence, chunks.single { it.source == fence }.source)
    }

    @Test
    fun `many prose blocks stay chunked without a global hard cap`() {
        val source = List(20) { index -> "Block $index ${"x".repeat(20)}" }.joinToString("\n\n")

        val chunks = planMarkdownMessageChunks(source, targetChars = 30)

        assertTrue(chunks.size > 12)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
    }

    @Test
    fun `malformed link definition candidate no longer collapses the plan`() {
        val source = buildString {
            append("[broken]:\n\n")
            repeat(20) { append("Paragraph $it ${"content ".repeat(8)}\n\n") }
        }

        val chunks = planMarkdownMessageChunks(source, targetChars = 120)

        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
    }

    @Test
    fun `single oversized prose paragraph is split into bounded rows`() {
        val source = "one continuous paragraph ${"content ".repeat(100)}"

        val chunks = planMarkdownMessageChunks(source, targetChars = 120)

        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertTrue(chunks.all { it.source.length <= 120 })
    }

    @Test
    fun `multiple tables split at table boundaries and reconstruct exactly`() {
        val first = "| Name | Value |\n| --- | --- |\n| alpha | one |\n"
        val second = "| Name | Value |\n| --- | --- |\n| beta | two |\n"
        val source = "Before.\n\n$first\n$second\nAfter."

        val chunks = planMarkdownMessageChunks(source, targetChars = first.length + 4)

        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertEquals(2, chunks.count { it.source.contains("| --- | --- |") })
        assertTrue(chunks.all { it.source.length <= first.length + 4 })
    }

    @Test
    fun `oversized table splits by rows and repeats header in rendered chunks`() {
        val source = buildString {
            append("| Name | Details |\n| --- | --- |\n")
            repeat(18) { index -> append("| row-$index | ${"value ".repeat(12)}|\n") }
        }

        val chunks = planMarkdownMessageChunks(source, targetChars = 180)

        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertTrue(chunks.drop(1).all { chunk ->
            chunk.renderMarkdown.startsWith("| Name | Details |\n| --- | --- |\n")
        })
    }

    @Test
    fun `table row chunks preserve repeated header and divider without duplicating source`() {
        val source = """
            | A | B |
            | --- | --- |
            | 1 | ${"x".repeat(45)} |
            | 2 | ${"y".repeat(45)} |
            | 3 | ${"z".repeat(45)} |
        """.trimIndent()

        val chunks = planMarkdownMessageChunks(source, targetChars = 80)

        assertTrue(chunks.size >= 3)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        chunks.forEachIndexed { index, chunk ->
            assertTrue(chunk.renderMarkdown.startsWith("| A | B |\n| --- | --- |\n"))
            if (index > 0) assertTrue(!chunk.source.startsWith("| A | B |"))
        }
    }

    @Test
    fun `oversized tables relax target instead of falling back when rows exceed max`() {
        val source = buildString {
            append("| A | B |\n| --- | --- |\n")
            repeat(20) { index -> append("| $index | ${"value ".repeat(10)} |\n") }
        }

        val chunks = planMarkdownMessageChunks(source, targetChars = 80)

        assertTrue(chunks.size > 2)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertTrue(chunks.all { it.renderMarkdown.startsWith("| A | B |\n| --- | --- |\n") })
    }

    @Test
    fun `many small tables keep each table in its own row`() {
        val source = buildString {
            repeat(20) { index ->
                append("| Name | Value |\n| --- | --- |\n| table-$index | ${"value ".repeat(8)}|\n")
                if (index != 19) append('\n')
            }
        }

        val chunks = planMarkdownMessageChunks(source, targetChars = 120)

        val tableChunks = chunks.filter { "| --- | --- |" in it.renderMarkdown }
        assertEquals(20, tableChunks.size)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertTrue(tableChunks.all { it.renderMarkdown.count { char -> char == '\n' } >= 2 })
    }

    @Test
    fun `valid single column table is split by rows`() {
        val source = buildString {
            append("| Value |\n| --- |\n")
            repeat(8) { index -> append("| row-$index-${"x".repeat(30)} |\n") }
        }

        val chunks = planMarkdownMessageChunks(source, targetChars = 90)

        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertTrue(chunks.all { it.renderMarkdown.startsWith("| Value |\n| --- |\n") })
    }

    @Test
    fun `root structures stay atomic while surrounding content is chunked`() {
        val structures = listOf(
            "- first item\n\n  continuation paragraph\n\n- second item\n\n",
            "> quoted paragraph\n>\n> second paragraph\n\n",
            "    indented code ${"x".repeat(80)}\n\n",
            "<section>\n<p>raw document block</p>\n</section>\n\n",
        )

        structures.forEach { structure ->
            val source = buildString {
                repeat(4) { append("Before ${"lead ".repeat(8)}\n\n") }
                append(structure)
                repeat(4) { append("After ${"tail ".repeat(8)}\n\n") }
            }
            val chunks = planMarkdownMessageChunks(source, targetChars = 80)

            assertEquals(source, chunks.joinToString(separator = "") { it.source })
            assertTrue("mixed source should use multiple chunks", chunks.size > 1)
            assertEquals(1, chunks.count { structure in it.source })
        }
    }

    @Test
    fun `mixed production-shaped markdown chunks tables without splitting root structures`() {
        val quote = "> **Simple work stays direct; complex work asks only for business decisions.**\n\n"
        val list = buildString {
            repeat(18) { index -> append("- workflow rule $index keeps its contract intact\n") }
            append('\n')
        }
        val rawHtml = "<details>\n<summary>Runtime evidence</summary>\n<p>Preserve this block atomically.</p>\n</details>\n\n"
        val table = buildString {
            append("| Lane | Scope | User involvement |\n")
            append("|---|---|---|\n")
            repeat(12) { index ->
                append("| L$index | ${"bounded delivery ".repeat(8)}| only for unresolved product decisions |\n")
            }
        }
        val source = buildString {
            append("Opening ${"context ".repeat(75)}\n\n")
            append(quote)
            append("Bridge ${"evidence ".repeat(75)}\n\n")
            append(table)
            append('\n')
            append(list)
            append(rawHtml)
            repeat(15) { index -> append("Closing paragraph $index ${"result ".repeat(20)}\n\n") }
        }

        val chunks = planMarkdownMessageChunks(source, targetChars = 900)

        assertTrue("expected multiple bounded chunks, count=${chunks.size}", chunks.size > 1)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertEquals(1, chunks.count { quote in it.source })
        assertEquals(1, chunks.count { list in it.source })
        assertEquals(1, chunks.count { rawHtml in it.source })
        assertTrue(chunks.any { "| Lane | Scope | User involvement |" in it.renderMarkdown })
        assertTrue(chunks.any { "| L0 |" in it.source })
    }

    @Test
    fun `mixed safe blocks always reconstruct source exactly`() {
        val source = buildString {
            repeat(18) { index ->
                append("Paragraph $index ${"body ".repeat(8)}\n\n")
            }
            append("```text\n${"atomic fence line\n".repeat(20)}```\n\n")
            repeat(10) { index -> append("Tail $index ${"body ".repeat(8)}\n\n") }
        }

        val chunks = planMarkdownMessageChunks(source, targetChars = 300)

        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertTrue(chunks.filterNot { it.source.startsWith("```") }.all { it.source.length <= 300 })
    }

    private companion object {
        val RootDefinition = Regex("(?m)^\\[[^]]+]:\\s+\\S.*$")
    }
}
