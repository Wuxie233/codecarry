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
    fun `resolveMessageMarkdownRoute keeps planned non math chunk on compose route`() {
        val source = "Chunk source using [docs][guide]."
        val plannedChunk = PlannedMarkdownMessageChunk(
            chunk = MarkdownMessageChunk(
                source = source,
                renderMarkdown = "$source\n\n[guide]: https://example.com/docs",
            ),
            math = emptyList(),
        )

        val route = resolveMessageMarkdownRoute(source, plannedChunk)

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
        assertTrue(ChatOverflowPolicy.containsWideAsciiToken("→ ${"a".repeat(48)}"))
        assertTrue(ChatOverflowPolicy.containsWideAsciiToken("/root/CODE/oc-remote/app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt"))
        assertTrue(ChatOverflowPolicy.containsWideAsciiToken("`chatgpt-comparison-detection`"))
        assertTrue(ChatOverflowPolicy.containsWideAsciiToken("`~/.config/opencode/skills/**/SKILL.md`"))
    }

    @Test
    fun `containsWideAsciiToken ignores normal wrapped prose`() {
        val prose = "This is a normal assistant reply with spaces between words and no single token that needs horizontal drag."
        val reviewFindings = """
            1. High risk: main-thread command queue timeout does not cancel the pending command.
            [DispatchAsync](/root/CODE/RimWorld/RimWorldMod_RimWorldAI/RimWorldMCP/McpCommandQueue.cs:72) times out, but the queued command can still execute later.
        """.trimIndent()

        assertFalse(ChatOverflowPolicy.containsWideAsciiToken(prose))
        assertFalse(ChatOverflowPolicy.containsWideAsciiToken("中文内容会按正常气泡宽度显示，不应该触发横向拖动"))
        assertFalse(ChatOverflowPolicy.containsWideAsciiToken(reviewFindings))
    }

    @Test
    fun `containsWideAsciiToken keeps 28 character threshold`() {
        assertFalse(ChatOverflowPolicy.containsWideAsciiToken("a".repeat(27)))
        assertTrue(ChatOverflowPolicy.containsWideAsciiToken("a".repeat(ChatOverflowPolicy.WideAsciiThreshold)))
    }

    @Test
    fun `overflow policy scrolls only wide wrap by default text kinds`() {
        val reviewFindings = """
            1. High risk: main-thread command queue timeout does not cancel the pending command.
            [DispatchAsync](/root/CODE/RimWorld/RimWorldMod_RimWorldAI/RimWorldMCP/McpCommandQueue.cs:72) times out, but the queued command can still execute later.
        """.trimIndent()

        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.MarkdownParagraph,
                text = "`~/.config/opencode/skills/**/SKILL.md`",
            ),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.PlainText,
                text = "→ ${"a".repeat(48)}",
            ),
        )
        assertEquals(
            ChatOverflowTreatment.Wrap,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.MarkdownParagraph,
                text = reviewFindings,
            ),
        )
    }

    @Test
    fun `overflow policy preserves code table and webview semantics`() {
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.CodeBlock,
                codeWordWrap = false,
            ),
        )
        assertEquals(
            ChatOverflowTreatment.Wrap,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.CodeBlock,
                codeWordWrap = true,
            ),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(ChatOverflowContentKind.Table),
        )
        assertEquals(
            ChatOverflowTreatment.Wrap,
            ChatOverflowPolicy.resolve(ChatOverflowContentKind.WebViewProse),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(ChatOverflowContentKind.WebViewStructuredBlock),
        )
    }
}
