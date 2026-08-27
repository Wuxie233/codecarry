package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatStreamingHotPathTest {
    @Before
    fun resetMetrics() {
        MarkdownParseMetrics.reset()
    }

    @Test
    fun `streaming appends reuse completed prefix without a full reparse`() {
        val table = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val prefix = "$table\n\n"
        var source = prefix + "Start"
        var plan = plan(source)
        val tableBlock = plan.blocks.first { it.kind == MarkdownRenderBlockKind.Table }

        MarkdownParseMetrics.reset()
        repeat(40) { step ->
            source += " token$step"
            plan = plan(source, plan)
        }

        assertSame(tableBlock, plan.blocks.first { it.kind == MarkdownRenderBlockKind.Table })
        assertEquals(tableBlock.key, plan.blocks.first { it.kind == MarkdownRenderBlockKind.Table }.key)
        assertTrue(
            "expected suffix-only parses, parseChars=${MarkdownParseMetrics.parseChars} parseCount=${MarkdownParseMetrics.parseCount}",
            MarkdownParseMetrics.parseChars < source.length * 3,
        )
        assertTrue(
            "expected not to full-parse on every token, parseCount=${MarkdownParseMetrics.parseCount}",
            MarkdownParseMetrics.parseCount <= 2,
        )
    }

    @Test
    fun `prose token appends do not invoke the GFM parser`() {
        var source = "Opening paragraph that stays a single block."
        var plan = plan(source)
        MarkdownParseMetrics.reset()
        repeat(50) { step ->
            source += " word$step"
            plan = plan(source, plan)
        }

        assertEquals(1, plan.blocks.size)
        assertEquals(0, MarkdownParseMetrics.parseCount)
        assertEquals(source, plan.originalSource)
        assertEquals(source, plan.blocks.single().source)
    }

    @Test
    fun `row planner skips unchanged earlier messages while the last part grows`() {
        val state = ChatMessageRowPlanningState()
        val earlier = assistant("earlier", longStructuredMarkdown())
        val firstLive = assistant("live", "Hello")
        val initial = planChatMessageRows(listOf(earlier, firstLive), state)
        val earlierRows = initial.filter { it.sourceMessageIndex == 0 }

        MarkdownParseMetrics.reset()
        var liveText = "Hello"
        var lastRows = initial
        repeat(30) { step ->
            liveText += " token$step"
            lastRows = planChatMessageRows(listOf(earlier, assistant("live", liveText)), state)
        }

        val grownEarlier = lastRows.filter { it.sourceMessageIndex == 0 }
        assertEquals(earlierRows.map { it.key }, grownEarlier.map { it.key })
        assertTrue(grownEarlier.zip(earlierRows).all { (next, previous) -> next.markdownOrNull() === previous.markdownOrNull() || next.key == previous.key })
        assertTrue(
            "unchanged earlier markdown must not be reparsed, parseChars=${MarkdownParseMetrics.parseChars}",
            MarkdownParseMetrics.parseChars < liveText.length * 4,
        )
        assertTrue(grownEarlier.zip(earlierRows).all { (next, previous) -> next === previous })
    }

    @Test
    fun `stable chat messages keep earlier identities while the last message grows`() {
        val earlier = assistant("earlier", "History")
        val firstLive = assistant("live", "Hello")
        val previous = listOf(earlier, firstLive)
        val grownLive = assistant("live", "Hello world")
        val reused = reuseStableChatMessages(previous, listOf(earlier.copy(), grownLive))

        assertSame(earlier, reused.first())
        assertEquals("Hello world", (reused.last().parts.single() as Part.Text).text)
        assertTrue(reused.last() !== firstLive)

        val appended = reuseStableChatMessages(reused, listOf(earlier.copy(), grownLive, assistant("next", "New")))
        assertSame(earlier, appended.first())
        assertEquals(3, appended.size)
    }

    private fun ChatMessageRow.markdownOrNull() = (this as? ChatMessageRow.TextChunk)?.markdown

    private fun plan(source: String, previous: MarkdownRenderPlan? = null): MarkdownRenderPlan {
        return (planStreamingMarkdown(source, previous) as MarkdownStreamingPlanResult.Success).plan
    }

    private fun assistant(id: String, text: String): ChatMessage = ChatMessage(
        message = Message.Assistant(id = id, sessionId = "session", time = TimeInfo(created = 1L)),
        parts = listOf(
            Part.Text(id = "$id-text", sessionId = "session", messageId = id, text = text),
        ),
    )

    private fun longStructuredMarkdown(): String = buildString {
        append("| A | B |\n| --- | --- |\n| 1 | 2 |\n\n")
        repeat(80) { index ->
            append("History paragraph $index ")
            append("content ".repeat(20))
            append("\n\n")
        }
        append("```text\nstale fence\n```\n")
    }
}
