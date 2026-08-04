package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

class ChatMarkdownRendererTest {
    @Test
    fun `render plan keeps non math markdown on compose route`() {
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

        val plan = planMarkdownDocument(parseMarkdownDocument(markdown).getOrThrow())

        assertTrue(plan.blocks.all { it.route == MarkdownRenderRoute.Compose })
    }

    @Test
    fun `render plan carries link definitions into non math block context`() {
        val source = "Chunk source using [docs][guide]."
        val markdown = "$source\n\n[guide]: https://example.com/docs"
        val plan = planMarkdownDocument(parseMarkdownDocument(markdown).getOrThrow())
        val prose = plan.blocks.first()

        assertEquals(MarkdownRenderRoute.Compose, prose.route)
        assertTrue("[guide]: https://example.com/docs" in prose.renderSource)
    }

    @Test
    fun `planned table chunk stays compose when another chunk contains math`() {
        val table = "| Name | Value |\n| --- | --- |\n| Alpha | Beta |"
        val markdown = "Before\n\n$table\n\nAfter ${'$'}x${'$'}"
        val plan = planMarkdownDocument(parseMarkdownDocument(markdown).getOrThrow(), targetChars = 20)
        val tableBlock = plan.blocks.first { it.kind == MarkdownRenderBlockKind.Table }
        val mathBlock = plan.blocks.first { it.math.isNotEmpty() }

        assertEquals(MarkdownRenderRoute.Compose, tableBlock.route)
        assertEquals(MarkdownRenderRoute.Katex, mathBlock.route)
    }

    @Test
    fun `planned renderer chunks reconstruct placeholder source exactly`() {
        val source = "Intro\n\n| Name | Value |\n| --- | --- |\n| Alpha | Beta |\n\nFormula ${'$'}x${'$'}"
        val plan = planMarkdownDocument(parseMarkdownDocument(source).getOrThrow(), targetChars = 24)

        assertEquals(source, plan.blocks.joinToString(separator = "") { it.source })
    }

    @Test
    fun `render plan sends inline and display math to katex route`() {
        listOf(
            "Inline ${'$'}x${'$'} math",
            "Display ${'$'}${'$'}x^2${'$'}${'$'} math",
            "Bracket \\(x + y\\) math",
            "Display bracket \\[x + y\\] math",
        ).forEach { markdown ->
            assertTrue(planMarkdownDocument(parseMarkdownDocument(markdown).getOrThrow()).blocks.any {
                it.route == MarkdownRenderRoute.Katex
            })
        }
    }

    @Test
    fun `render plan ignores currency inline code and fenced dollars`() {
        val markdown = """
            Price is ${'$'}20 and inline code `value = ${'$'}x${'$'}`.

            ```kotlin
            val formula = "${'$'}y${'$'}"
            ```
        """.trimIndent()

        val plan = planMarkdownDocument(parseMarkdownDocument(markdown).getOrThrow())
        assertTrue(plan.blocks.all { it.route == MarkdownRenderRoute.Compose })
    }

    @Test
    fun `render plan keeps financial markdown with dollar ratios on compose route`() {
        val markdown = """
            **Total budget**: ${'$'}7000
            **Current spend**: ${'$'}769 + ${'$'}58 = ${'$'}827
            **Coverage**: (${ '$' }827/${ '$' }1484)
        """.trimIndent()

        val plan = planMarkdownDocument(parseMarkdownDocument(markdown).getOrThrow())
        assertTrue(plan.blocks.all { it.route == MarkdownRenderRoute.Compose })
    }

    @Test
    fun `render plan keeps mermaid fences on compose route`() {
        val markdown = """
            ```mermaid
            flowchart TD
              A[Start] --> B[Done]
            ```
        """.trimIndent()

        val plan = planMarkdownDocument(parseMarkdownDocument(markdown).getOrThrow())
        assertTrue(plan.blocks.all { it.route == MarkdownRenderRoute.Compose })
    }

    @Test
    fun `ordered list numbering preserves start and increments by item index`() {
        val markdown = """
            3. First
            4. Second
        """.trimIndent()
        val list = parseOrderedLists(markdown).single()

        val startNumber = orderedListStartNumber(markdown, list)

        assertEquals(3, startNumber)
        assertEquals(listOf("3. ", "4. "), List(2) { orderedListMarker(startNumber, it) })
    }

    @Test
    fun `ordered list numbering extracts each nested list start independently`() {
        val markdown = """
            3. Outer first
               7. Inner first
               8. Inner second
            4. Outer second
        """.trimIndent()
        val lists = parseOrderedLists(markdown)

        assertEquals(2, lists.size)
        assertEquals(3, orderedListStartNumber(markdown, lists[0]))
        assertEquals(7, orderedListStartNumber(markdown, lists[1]))
        assertEquals("8. ", orderedListMarker(orderedListStartNumber(markdown, lists[1]), 1))
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

    private fun parseOrderedLists(markdown: String): List<ASTNode> {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        return buildList {
            fun collect(node: ASTNode) {
                if (node.type == MarkdownElementTypes.ORDERED_LIST) add(node)
                node.children.forEach(::collect)
            }
            collect(root)
        }
    }
}
