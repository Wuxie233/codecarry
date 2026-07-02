package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownRendererTest {
    @Test
    fun `resolveMessageMarkdownRoute keeps non math markdown on compose route`() {
        val markdown = """
            # Heading

            [OpenCode](https://opencode.ai)

            | Name | Value |
            | --- | --- |
            | Alpha | Beta |

            ```kotlin
            val answer = 42
            ```
        """.trimIndent()

        val route = resolveMessageMarkdownRoute(markdown)

        assertEquals(MessageMarkdownRoute.ComposeMarkdown, route)
    }

    @Test
    fun `resolveMessageMarkdownRoute sends inline and display math to katex route`() {
        assertEquals(MessageMarkdownRoute.KatexWebView, resolveMessageMarkdownRoute("Inline ${'$'}x${'$'} math"))
        assertEquals(MessageMarkdownRoute.KatexWebView, resolveMessageMarkdownRoute("Display ${'$'}${'$'}x^2${'$'}${'$'} math"))
        assertEquals(MessageMarkdownRoute.KatexWebView, resolveMessageMarkdownRoute("Bracket \\(x + y\\) math"))
        assertEquals(MessageMarkdownRoute.KatexWebView, resolveMessageMarkdownRoute("Display bracket \\[x + y\\] math"))
    }

    @Test
    fun `resolveMessageMarkdownRoute ignores currency inline code and fenced dollars`() {
        val markdown = """
            Price is ${'$'}20 and inline code `value = ${'$'}x${'$'}`.

            ```kotlin
            val formula = "${'$'}y${'$'}"
            ```
        """.trimIndent()

        val route = resolveMessageMarkdownRoute(markdown)

        assertEquals(MessageMarkdownRoute.ComposeMarkdown, route)
    }

    @Test
    fun `resolveMessageMarkdownRoute keeps financial markdown with dollar ratios on compose route`() {
        val markdown = """
            **Total budget**: ${'$'}7000
            **Current spend**: ${'$'}769 + ${'$'}58 = ${'$'}827
            **Coverage**: (${ '$' }827/${ '$' }1484)
        """.trimIndent()

        val route = resolveMessageMarkdownRoute(markdown)

        assertEquals(MessageMarkdownRoute.ComposeMarkdown, route)
    }

    @Test
    fun `resolveMessageMarkdownRoute keeps mermaid fences on compose route`() {
        val markdown = """
            ```mermaid
            flowchart TD
              A[Start] --> B[Done]
            ```
        """.trimIndent()

        val route = resolveMessageMarkdownRoute(markdown)

        assertEquals(MessageMarkdownRoute.ComposeMarkdown, route)
    }
}
