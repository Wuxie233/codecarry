package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathRendererTest {
    @Test
    fun `splitMarkdownMathSegments detects inline and display math`() {
        val segments = splitMarkdownMathSegments("Before \$x + y\$ middle \$\$\\frac{1}{2}\$\$ after")

        assertEquals(5, segments.size)
        assertEquals("Before ", (segments[0] as MarkdownMathSegment.Markdown).text)
        assertEquals(MarkdownMathSegment.Math("x + y", display = false, delimiter = "\$"), segments[1])
        assertEquals(" middle ", (segments[2] as MarkdownMathSegment.Markdown).text)
        assertEquals(MarkdownMathSegment.Math("\\frac{1}{2}", display = true, delimiter = "\$\$"), segments[3])
        assertEquals(" after", (segments[4] as MarkdownMathSegment.Markdown).text)
    }

    @Test
    fun `splitMarkdownMathSegments detects escaped bracket math`() {
        val segments = splitMarkdownMathSegments("Inline \\(a^2\\) and display \\[b^2\\]")

        assertEquals(MarkdownMathSegment.Math("a^2", display = false, delimiter = "\\("), segments[1])
        assertEquals(MarkdownMathSegment.Math("b^2", display = true, delimiter = "\\["), segments[3])
    }

    @Test
    fun `splitMarkdownMathSegments skips inline code code fences and currency`() {
        val markdown = """
            Price is ${'$'}20 and code `inline ${'$'}x${'$'}`.

            ```kotlin
            val formula = "${'$'}y${'$'}"
            ```

            Real math: ${'$'}z${'$'}.
        """.trimIndent()

        val math = splitMarkdownMathSegments(markdown).filterIsInstance<MarkdownMathSegment.Math>()

        assertEquals(listOf(MarkdownMathSegment.Math("z", display = false, delimiter = "\$")), math)
    }

    @Test
    fun `splitMarkdownMathSegments leaves unmatched delimiters as markdown`() {
        val segments = splitMarkdownMathSegments("Keep \$unfinished and \\(also unfinished")

        assertEquals(1, segments.size)
        assertTrue(segments.single() is MarkdownMathSegment.Markdown)
    }
}
