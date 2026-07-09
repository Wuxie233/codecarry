package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `containsWideAsciiToken detects unbreakable prose segments`() {
        assertTrue(containsWideAsciiToken("→ ${"a".repeat(48)}"))
        assertTrue(containsWideAsciiToken("/root/CODE/oc-remote/app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt"))
    }

    @Test
    fun `containsWideAsciiToken ignores normal wrapped prose`() {
        val prose = "This is a normal assistant reply with spaces between words and no single token that needs horizontal drag."

        assertFalse(containsWideAsciiToken(prose))
        assertFalse(containsWideAsciiToken("中文内容会按正常气泡宽度显示，不应该触发横向拖动"))
    }
}
