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
    fun `hard cap falls back to one whole chunk instead of creating an oversized tail`() {
        val source = List(20) { index -> "Block $index ${"x".repeat(20)}" }.joinToString("\n\n")

        val chunks = planMarkdownMessageChunks(source, targetChars = 30, maxChunks = 12)

        assertEquals(1, chunks.size)
        assertEquals(source, chunks.joinToString(separator = "") { it.source })
        assertEquals(source, chunks.single().renderMarkdown)
    }

    @Test
    fun `single oversized prose paragraph falls back to one whole chunk`() {
        val source = "one continuous paragraph ${"content ".repeat(100)}"

        val chunks = planMarkdownMessageChunks(source, targetChars = 120)

        assertEquals(listOf(source), chunks.map { it.source })
        assertEquals(source, chunks.single().renderMarkdown)
    }

    @Test
    fun `loose list blockquote indented code and raw html conservatively stay whole`() {
        val fixtures = listOf(
            "- first item\n\n  continuation paragraph\n\n- second item\n\n${"tail ".repeat(40)}",
            "> quoted paragraph\n>\n> second paragraph\n\n${"tail ".repeat(40)}",
            "Paragraph before.\n\n    indented code ${"x".repeat(80)}\n\nParagraph after.",
            "<section>\n<p>raw document block</p>\n</section>\n\n${"tail ".repeat(40)}",
        )

        fixtures.forEach { source ->
            val chunks = planMarkdownMessageChunks(source, targetChars = 80)
            assertEquals(source, chunks.joinToString(separator = "") { it.source })
            assertEquals("unsafe structure should use Whole fallback", 1, chunks.size)
        }
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
