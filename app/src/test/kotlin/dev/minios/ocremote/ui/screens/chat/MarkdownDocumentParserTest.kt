package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentParserTest {
    @Test
    fun `segments and owned ranges reconstruct parser source including root trivia`() {
        val source = "\n# Heading\n\nParagraph.\n"

        val document = parseMarkdownDocument(source).getOrThrow()

        assertEquals(source, reconstructParserSource(document))
        assertTrue(document.segments.first() is DocumentSegment.Trivia)
        assertEquals(2, document.blocks.size)
        val heading = document.blocks.first() as MarkdownBlock.Heading
        assertEquals(1, heading.level)
        assertEquals(" Heading", heading.contentRange.slice(document.parserSource))
        assertEquals("# Heading\n\n", heading.ownedRange.slice(document.parserSource))
    }

    @Test
    fun `nested ordered lists preserve independent starts and marker ranges`() {
        val source = "3. outer\n   7. nested\n   8. next\n4. done"

        val document = parseMarkdownDocument(source).getOrThrow()
        val root = document.blocks.single() as MarkdownBlock.ListBlock

        assertTrue(root.ordered)
        assertEquals(3, root.startNumber)
        assertEquals("3. ", root.items.first().markerRange.slice(document.parserSource))
        val nested = root.items.first().children.filterIsInstance<MarkdownBlock.ListBlock>().single()
        assertEquals(7, nested.startNumber)
        assertEquals(listOf("7. ", "8. "), nested.items.map { it.markerRange.slice(document.parserSource) })
    }

    @Test
    fun `GFM table exposes structured cells and normalizes missing row cells`() {
        val source = "| A | B | C |\n| --- | --- | --- |\n| 1 || 3 |\n| 4 | 5 |\n"

        val document = parseMarkdownDocument(source).getOrThrow()
        val table = document.blocks.single() as MarkdownBlock.Table

        assertEquals(listOf("A", "B", "C"), table.header.map { it.contentRange.slice(document.parserSource) })
        assertEquals(listOf("1", "", "3"), table.rows[0].map { it.contentRange.slice(document.parserSource) })
        assertEquals(3, table.rows[1].size)
        assertTrue(table.rows[1].last().contentRange.isEmpty)
        assertEquals("| --- | --- | --- |", table.dividerRange.slice(document.parserSource))
    }

    @Test
    fun `fence exposes language content and open state`() {
        val closed = parseMarkdownDocument("```mermaid\ngraph TD\n```\n").getOrThrow()
            .blocks.single() as MarkdownBlock.CodeFence
        val open = parseMarkdownDocument("```kotlin\nval x = 1").getOrThrow()
            .blocks.single() as MarkdownBlock.CodeFence

        assertEquals("mermaid", closed.languageRange!!.slice(closedDocumentSource(closed, "```mermaid\ngraph TD\n```\n")))
        assertTrue(closed.isClosed)
        assertFalse(open.isClosed)
    }

    @Test
    fun `link definitions preserve normalized label destination and title`() {
        val source = "[My  Label]: <https://example.com> \"Title\"\n[my label]: /ignored\n\nUse [it][my label] and [inline](https://inline.example). ![image](asset.png)"

        val document = parseMarkdownDocument(source).getOrThrow()
        val definition = document.linkDefinitions.single()

        assertEquals("My  Label", definition.label)
        assertEquals("my label", definition.normalizedLabel)
        assertEquals("https://example.com", definition.destination)
        assertEquals("Title", definition.title)
        assertEquals(
            listOf(
                MarkdownInlineReferenceKind.Reference,
                MarkdownInlineReferenceKind.Link,
                MarkdownInlineReferenceKind.Image,
            ),
            document.inlineReferences.map { it.kind },
        )
        assertEquals("https://inline.example", document.inlineReferences[1].destinationRange!!.slice(document.parserSource))
        assertEquals("asset.png", document.inlineReferences[2].destinationRange!!.slice(document.parserSource))
    }

    @Test
    fun `math placeholders keep parser and normalized coordinates`() {
        val source = "Price ${'$'}20, inline ${'$'}x+1${'$'}, display \\[y^2\\], code `${'$'}z${'$'}`."

        val document = parseMarkdownDocument(source).getOrThrow()

        assertEquals(2, document.math.size)
        assertEquals(listOf("x+1", "y^2"), document.math.map { it.source })
        document.math.forEach { placeholder ->
            assertEquals("xMJXMATH${placeholder.id}HTAMXJMx", placeholder.parserRange.slice(document.parserSource))
            assertTrue(placeholder.normalizedRange.slice(document.normalizedSource).isNotEmpty())
        }
        assertEquals(source, reconstructOriginalSource(document))
    }

    @Test
    fun `math preprocessing detects all supported delimiters in order`() {
        val source = "Inline ${'$'}x${'$'}, display ${'$'}${'$'}y${'$'}${'$'}, brackets \\(a\\) and \\[b\\]."

        val math = parseMarkdownDocument(source).getOrThrow().math

        assertEquals(listOf("x", "y", "a", "b"), math.map { it.source })
        assertEquals(listOf(false, true, false, true), math.map { it.display })
        assertEquals(listOf("${'$'}", "${'$'}${'$'}", "\\(", "\\["), math.map { it.delimiter })
    }

    @Test
    fun `math preprocessing ignores code fences inline code currency and unmatched delimiters`() {
        val source = """
            Price is ${'$'}20, ratio (${ '$' }827/${ '$' }1484), and code `${'$'}inline${'$'}`.

            ```kotlin
            val formula = "${'$'}fenced${'$'}"
            ```

            Keep ${'$'}unfinished and \\(also unfinished.

            Real math: ${'$'}z${'$'}.
        """.trimIndent()

        val document = parseMarkdownDocument(source).getOrThrow()

        assertEquals(listOf("z"), document.math.map { it.source })
        assertEquals(source, reconstructOriginalSource(document))
    }

    @Test
    fun `math preprocessing distinguishes numeric expressions from financial amounts`() {
        val source = "Budget ${'$'}7,000 plus ${'$'}58, while ratios use ${'$'}3/4${'$'} and ${'$'}3+2${'$'}."

        val document = parseMarkdownDocument(source).getOrThrow()

        assertEquals(listOf("3/4", "3+2"), document.math.map { it.source })
        assertEquals(source, reconstructOriginalSource(document))
    }

    @Test
    fun `math preprocessing detects markdown escaped display dollars`() {
        val source = "Before \\$\\$\\frac{1}{y}\\$\\$ after"

        val math = parseMarkdownDocument(source).getOrThrow().math.single()

        assertEquals("\\frac{1}{y}", math.source)
        assertTrue(math.display)
        assertEquals("\\${'$'}\\${'$'}", math.delimiter)
    }

    @Test
    fun `HTML document protection remains the single parser preprocessor`() {
        val source = "<!doctype html><html><body><p>a</p><p>b</p><p>c</p><p>d</p></body></html>"

        val document = parseMarkdownDocument(source).getOrThrow()

        assertTrue(document.normalizedSource.startsWith("```text\n<!doctype html>"))
        assertTrue(document.blocks.single() is MarkdownBlock.CodeFence)
        assertEquals(source, document.originalSource)
        assertTrue(document.math.isEmpty())
    }

    private fun reconstructParserSource(document: MarkdownDocument): String = buildString {
        document.segments.forEach { append(it.parserRange.slice(document.parserSource)) }
    }

    private fun reconstructOriginalSource(document: MarkdownDocument): String {
        if (document.math.isEmpty()) return document.originalSource
        val output = StringBuilder()
        var parserCursor = 0
        var originalCursor = 0
        document.math.forEach { placeholder ->
            output.append(document.parserSource, parserCursor, placeholder.parserRange.start)
            output.append(document.normalizedSource, placeholder.normalizedRange.start, placeholder.normalizedRange.endExclusive)
            parserCursor = placeholder.parserRange.endExclusive
            originalCursor = placeholder.normalizedRange.endExclusive
        }
        output.append(document.parserSource, parserCursor, document.parserSource.length)
        assertEquals(document.normalizedSource.length, originalCursor + document.normalizedSource.substring(originalCursor).length)
        return output.toString()
    }

    private fun closedDocumentSource(block: MarkdownBlock.CodeFence, source: String): String = source
}
