package dev.wuxie233.codecarry.ui.screens.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun decideMermaidFenceRenderingRendersFlowchartLrCandidate() {
        val decision = decideMermaidFenceRendering(
            code = """
                flowchart LR
                  A[当前方案: 手册代码] --> B[逐个计算布局]
                  B --> C[网易原生 UI]
            """.trimIndent(),
            language = "mermaid",
        )

        assertEquals(MermaidFenceRenderMode.RenderDiagram, decision.mode)
    }

    @Test
    fun reduceMermaidWebRenderStateKeepsRenderedAfterLateTimeoutOrFailure() {
        val rendered = reduceMermaidWebRenderState(
            MermaidWebRenderState.Loading,
            MermaidWebRenderEvent.Rendered(240),
        )

        assertEquals(MermaidWebRenderState.Rendered(240), rendered)
        assertEquals(
            rendered,
            reduceMermaidWebRenderState(rendered, MermaidWebRenderEvent.Timeout),
        )
        assertEquals(
            rendered,
            reduceMermaidWebRenderState(rendered, MermaidWebRenderEvent.Failed),
        )
        assertEquals(
            rendered,
            reduceMermaidWebRenderState(rendered, MermaidWebRenderEvent.Load),
        )
    }

    @Test
    fun reduceMermaidWebRenderStateFallsBackOnlyBeforeRenderCompletes() {
        assertEquals(
            MermaidWebRenderState.Fallback,
            reduceMermaidWebRenderState(MermaidWebRenderState.Loading, MermaidWebRenderEvent.Timeout),
        )
        assertEquals(
            MermaidWebRenderState.Fallback,
            reduceMermaidWebRenderState(MermaidWebRenderState.Preparing, MermaidWebRenderEvent.Failed),
        )
        assertEquals(
            MermaidWebRenderState.Loading,
            reduceMermaidWebRenderState(MermaidWebRenderState.Preparing, MermaidWebRenderEvent.Load),
        )
    }

    @Test
    fun mermaidRenderHtmlDoesNotScheduleUnconditionalFailureTimeout() {
        val html = buildMermaidRenderHtml(
            source = "flowchart LR\nA --> B",
            renderKey = "diagram-1",
            darkMode = true,
            backgroundColor = Color.Black,
            textColor = Color.White,
            primaryColor = Color.Cyan,
        )

        assertTrue(html.contains("let completed = false"))
        assertTrue(html.contains("if (completed) return"))
        assertFalse(html.contains("Mermaid render timed out"))
        assertFalse(html.contains("setTimeout(function() { fail"))
    }

    @Test
    fun nextMermaidRenderKeyStaysUniqueForTheSameSource() {
        val source = "flowchart LR\nA --> B"

        assertNotEquals(nextMermaidRenderKey(source), nextMermaidRenderKey(source))
    }
}
