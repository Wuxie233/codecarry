package dev.wuxie233.codecarry.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStreamingPlanTest {
    @Test
    fun `completed prefix keys remain stable while open prose tail grows`() {
        val table = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val first = plan("$table\n\nTail")
        val tableKey = first.blocks.single { it.kind == MarkdownRenderBlockKind.Table }.key
        val tailKey = first.blocks.last().key

        val grown = plan("$table\n\nTail grows", first)

        assertEquals(tableKey, grown.blocks.single { it.kind == MarkdownRenderBlockKind.Table }.key)
        assertNotEquals(tailKey, grown.blocks.last().key)
    }

    @Test
    fun `active tail may change from prose to table when divider arrives`() {
        val first = plan("Intro.\n\n| A | B |")
        val prefixKey = first.blocks.first().key
        val tailKey = first.blocks.last().key

        val completed = plan("Intro.\n\n| A | B |\n| --- | --- |\n| 1 | 2 |", first)

        assertEquals(prefixKey, completed.blocks.first().key)
        val table = completed.blocks.single { it.kind == MarkdownRenderBlockKind.Table }
        assertNotEquals(tailKey, table.key)
        assertEquals(MarkdownInteractionOwner.HorizontalScroll, table.interactionOwner)
    }

    @Test
    fun `active fence key changes until closed while earlier table scroll identity stays stable`() {
        val table = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val open = plan("$table\n\n```text\npartial")
        val tableKey = open.blocks.first().key
        val fenceKey = open.blocks.last().key
        assertTrue(open.blocks.last().isOpen)

        val closed = plan("$table\n\n```text\npartial\n```", open)

        assertEquals(tableKey, closed.blocks.first().key)
        assertNotEquals(fenceKey, closed.blocks.last().key)
        assertTrue(!closed.blocks.last().isOpen)
    }

    @Test
    fun `identical duplicate blocks keep independent stable identities while tail grows`() {
        val table = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val first = plan("$table\n\n$table\n\nTail")
        val tableKeys = first.blocks.filter { it.kind == MarkdownRenderBlockKind.Table }.map { it.key }

        val grown = plan("$table\n\n$table\n\nTail grows", first)

        assertEquals(2, tableKeys.distinct().size)
        assertEquals(tableKeys, grown.blocks.filter { it.kind == MarkdownRenderBlockKind.Table }.map { it.key })
    }

    @Test
    fun `parse failure is returned rather than silently routed through fallback`() {
        val result = planStreamingMarkdown(source = "", previous = null)

        assertTrue(result is MarkdownStreamingPlanResult.Success)
        assertTrue((result as MarkdownStreamingPlanResult.Success).plan.blocks.isEmpty())
    }

    private fun plan(source: String, previous: MarkdownRenderPlan? = null): MarkdownRenderPlan {
        return (planStreamingMarkdown(source, previous) as MarkdownStreamingPlanResult.Success).plan
    }
}
