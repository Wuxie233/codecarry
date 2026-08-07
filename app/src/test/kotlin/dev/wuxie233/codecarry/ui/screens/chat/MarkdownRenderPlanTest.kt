package dev.wuxie233.codecarry.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderPlanTest {
    @Test
    fun `planner assigns route and interaction owner per block`() {
        val source = """
            Intro ${'$'}x${'$'}.

            | A | B |
            | --- | --- |
            | 1 | 2 |

            ```kotlin
            val x = 1
            ```
        """.trimIndent()

        val plan = plan(source)
        val math = plan.blocks.first { it.math.isNotEmpty() }
        val table = plan.blocks.single { it.kind == MarkdownRenderBlockKind.Table }
        val fence = plan.blocks.single { it.kind == MarkdownRenderBlockKind.CodeFence }

        assertEquals(MarkdownRenderRoute.Katex, math.route)
        assertEquals(MarkdownInteractionOwner.WebView, math.interactionOwner)
        assertEquals(MarkdownRenderRoute.Compose, table.route)
        assertEquals(MarkdownInteractionOwner.HorizontalScroll, table.interactionOwner)
        assertEquals(MarkdownRenderRoute.Compose, fence.route)
        assertEquals(MarkdownInteractionOwner.HorizontalScroll, fence.interactionOwner)
    }

    @Test
    fun `table plan exposes structured cells without reparsing raw table`() {
        val plan = plan("| Name | Value |\n| --- | --- |\n| alpha | one |\n")
        val table = plan.blocks.single()

        assertEquals(listOf("Name", "Value"), table.table!!.header)
        assertEquals(listOf(listOf("alpha", "one")), table.table.rows)
        assertEquals(MarkdownRenderBlockKind.Table, table.kind)
    }

    @Test
    fun `table cells restore math source instead of exposing parser placeholders`() {
        val plan = plan("| Formula |\n| --- |\n| ${'$'}x+1${'$'} |\n")
        val table = plan.blocks.single()

        assertEquals(listOf(listOf("${'$'}x+1${'$'}")), table.table!!.rows)
        assertTrue("xMJXMATH" !in table.table.rows.flatten().joinToString())
        assertEquals(MarkdownRenderRoute.Compose, table.route)
    }

    @Test
    fun `source ranges reconstruct normalized original while render context includes definitions`() {
        val source = "Use [docs][guide].\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n[guide]: https://example.com"
        val plan = plan(source)

        assertEquals(source, plan.blocks.joinToString(separator = "") { it.source })
        plan.blocks.forEach { block ->
            assertTrue("[guide]: https://example.com" in block.renderSource)
        }
    }

    @Test
    fun `source ranges include leading root trivia`() {
        val source = "\n\n# Heading\n\nParagraph.\n"

        val plan = plan(source)

        assertEquals(source, plan.blocks.joinToString(separator = "") { it.source })
        assertTrue(plan.blocks.first().source.startsWith("\n\n"))
    }

    @Test
    fun `large prose splits without whole message fallback`() {
        val source = "content ".repeat(100)

        val plan = plan(source, targetChars = 120)

        assertTrue(plan.blocks.size > 1)
        assertEquals(source, plan.blocks.joinToString(separator = "") { it.source })
        assertTrue(plan.blocks.all { it.source.length <= 120 })
    }

    @Test
    fun `stable key is independent from block index`() {
        val table = "| A | B |\n| --- | --- |\n| alpha | beta |"
        val original = plan("Before.\n\n$table")
        val prefixed = plan("Prefix.\n\nBefore.\n\n$table")

        val beta = original.blocks.single { it.kind == MarkdownRenderBlockKind.Table }
        val shiftedBeta = prefixed.blocks.single { it.kind == MarkdownRenderBlockKind.Table }

        assertEquals(beta.key, shiftedBeta.key)
        assertNotEquals(beta.parserRange.start, shiftedBeta.parserRange.start)
    }

    @Test
    fun `identical repeated blocks still receive unique provisional keys`() {
        val table = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val plan = plan("$table\n\n$table")

        assertEquals(2, plan.blocks.size)
        assertEquals(2, plan.blocks.map { it.key }.distinct().size)
    }

    @Test
    fun `math block keeps only local placeholders`() {
        val source = "First ${'$'}x${'$'}.\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\nSecond ${'$'}y${'$'}."
        val plan = plan(source)

        val mathBlocks = plan.blocks.filter { it.route == MarkdownRenderRoute.Katex }
        val table = plan.blocks.single { it.kind == MarkdownRenderBlockKind.Table }
        assertEquals(listOf(listOf("x"), listOf("y")), mathBlocks.map { block -> block.math.map { it.source } })
        assertTrue(table.math.isEmpty())
    }

    private fun plan(source: String, targetChars: Int = MarkdownRenderPlanTargetChars): MarkdownRenderPlan {
        val document = parseMarkdownDocument(source).getOrThrow()
        return planMarkdownDocument(document, targetChars)
    }
}
