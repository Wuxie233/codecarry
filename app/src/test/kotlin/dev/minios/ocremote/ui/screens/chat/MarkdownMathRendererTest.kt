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

    @Test
    fun `splitMarkdownMathSegments treats digit-leading math as math not currency`() {
        val segments = splitMarkdownMathSegments("两边加 \$3x^2y\$ 得 \$y'=y+3x^2y\$。")
        val math = segments.filterIsInstance<MarkdownMathSegment.Math>()
        assertEquals(
            listOf(
                MarkdownMathSegment.Math("3x^2y", display = false, delimiter = "\$"),
                MarkdownMathSegment.Math("y'=y+3x^2y", display = false, delimiter = "\$"),
            ),
            math,
        )
    }

    @Test
    fun `splitMarkdownMathSegments preserves multiple formulas in order`() {
        val segments = splitMarkdownMathSegments("A \$x\$ B \$y\$ C \$\$z\$\$")

        val math = segments.filterIsInstance<MarkdownMathSegment.Math>()
        assertEquals(
            listOf(
                MarkdownMathSegment.Math("x", display = false, delimiter = "\$"),
                MarkdownMathSegment.Math("y", display = false, delimiter = "\$"),
                MarkdownMathSegment.Math("z", display = true, delimiter = "\$\$"),
            ),
            math,
        )
    }

    @Test
    fun `splitMarkdownMathSegments detects markdown escaped display math`() {
        val markdown = """
            之前那个等式，换成撇号就是：

            \$\$
            \frac{1}{y}\,y' \;=\; \big(\ln|y|\big)'
            \$\$
        """.trimIndent()

        val math = splitMarkdownMathSegments(markdown).filterIsInstance<MarkdownMathSegment.Math>()

        assertEquals(
            listOf(
                MarkdownMathSegment.Math(
                    "\\frac{1}{y}\\,y' \\;=\\; \\big(\\ln|y|\\big)'",
                    display = true,
                    delimiter = "\\\$\\\$",
                ),
            ),
            math,
        )
    }
}
