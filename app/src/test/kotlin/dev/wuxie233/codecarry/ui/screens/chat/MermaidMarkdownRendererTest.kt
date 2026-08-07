package dev.wuxie233.codecarry.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidMarkdownRendererTest {
    @Test
    fun findMermaidFenceBlocksDetectsOnlyMermaidFences() {
        val markdown = """
            Intro paragraph.

            ```mermaid
            flowchart TD
              A[Start] --> B[Done]
            ```

            ```kotlin
            val untouched = true
            ```
        """.trimIndent()

        val blocks = findMermaidFenceBlocks(markdown)

        assertEquals(1, blocks.size)
        assertEquals("mermaid", blocks.single().language)
        assertTrue(blocks.single().code.contains("flowchart TD"))
    }

    @Test
    fun decideMermaidFenceRenderingRendersValidFlowchartCandidate() {
        val decision = decideMermaidFenceRendering(
            code = """
                flowchart TD
                  Moderator --> Agent
            """.trimIndent(),
            language = "Mermaid",
        )

        assertEquals(MermaidFenceRenderMode.RenderDiagram, decision.mode)
        assertEquals("mermaid", decision.normalizedLanguage)
        assertEquals(null, decision.reason)
    }

    @Test
    fun decideMermaidFenceRenderingFallsBackForInvalidMermaidText() {
        val decision = decideMermaidFenceRendering(
            code = "this is not a mermaid diagram",
            language = "mermaid",
        )

        assertEquals(MermaidFenceRenderMode.CodeBlockFallback, decision.mode)
        assertNotNull(decision.reason)
    }

    @Test
    fun decideMermaidFenceRenderingLeavesOrdinaryCodeFenceUntouched() {
        val decision = decideMermaidFenceRendering(
            code = "flowchart TD\nA --> B",
            language = "kotlin",
        )

        assertEquals(MermaidFenceRenderMode.NotMermaid, decision.mode)
    }
}
