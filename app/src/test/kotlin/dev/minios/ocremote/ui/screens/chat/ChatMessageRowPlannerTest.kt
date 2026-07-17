package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageRowPlannerTest {
    @Test
    fun `29k single assistant math text expands into stable bounded rows`() {
        val message = assistantMessage("assistant-long", longMathText())

        val rows = planChatMessageRows(listOf(message))
        val chunks = rows.filterIsInstance<ChatMessageRow.TextChunk>()

        assertTrue("expected 5-8 rows, count=${rows.size}", rows.size in 5..8)
        assertEquals(rows.size, chunks.size)
        assertEquals(ChatMessageSegmentPosition.First, chunks.first().position)
        assertEquals(ChatMessageSegmentPosition.Last, chunks.last().position)
        assertTrue(chunks.drop(1).dropLast(1).all { it.position == ChatMessageSegmentPosition.Middle })
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        chunks.forEachIndexed { index, row ->
            assertTrue(row.key.contains("assistant-long"))
            assertTrue(row.key.contains("assistant-long-text"))
            assertTrue(row.key.contains("part-0"))
            assertTrue(row.key.contains("chunk-$index"))
            assertSame(message, row.chatMessage)
        }
    }

    @Test
    fun `expanded rows reconstruct placeholder source exactly`() {
        val message = assistantMessage("assistant-source", longMathText())
        val text = (message.parts.single() as Part.Text).text
        val (placeholderMarkdown, _) = buildPlaceholderMarkdown(text)

        val rows = planChatMessageRows(listOf(message)).filterIsInstance<ChatMessageRow.TextChunk>()

        assertEquals(placeholderMarkdown, rows.joinToString(separator = "") { it.markdown.chunk.source })
    }

    @Test
    fun `ordinary assistant message remains one whole row`() {
        val message = assistantMessage("assistant-short", "Short answer with ${'$'}x${'$'}.")

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
        assertSame(message, rows.single().chatMessage)
    }

    @Test
    fun `user message remains one whole row`() {
        val message = ChatMessage(
            message = Message.User(id = "user-long", sessionId = SessionId, time = TimeInfo(created = 1L)),
            parts = listOf(textPart("user-long", "user-long-text", longMathText())),
        )

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
    }

    @Test
    fun `assistant with one text plus step parts expands without reordering content`() {
        val message = assistantMessage(
            id = "assistant-steps",
            text = longMathText(),
            extraParts = listOf(
                Part.StepStart("step-start", SessionId, "assistant-steps"),
                Part.Tool(id = "tool", sessionId = SessionId, messageId = "assistant-steps", tool = "bash"),
                Part.StepFinish("step-finish", SessionId, "assistant-steps"),
            ),
        )

        val rows = planChatMessageRows(listOf(message))

        assertTrue(rows.size in 5..8)
        assertTrue(rows.all { it is ChatMessageRow.TextChunk })
        assertTrue((rows.first() as ChatMessageRow.TextChunk).showsSteps)
        assertTrue(rows.drop(1).none { (it as ChatMessageRow.TextChunk).showsSteps })
    }

    @Test
    fun `complex multi-content assistant remains one whole row`() {
        val message = assistantMessage(
            id = "assistant-complex",
            text = longMathText(),
            extraParts = listOf(
                Part.Reasoning(
                    id = "reasoning",
                    sessionId = SessionId,
                    messageId = "assistant-complex",
                    text = "Reasoning must keep its original position.",
                ),
            ),
        )

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
    }

    @Test
    fun `long math bearing raw html is normalized before chunk planning`() {
        val rawHtml = buildString {
            append("<!doctype html><html><body><script>window.releaseBlocker = true</script>")
            append("<p>Display math: \\[x^2 + y^2 = z^2\\]</p>")
            repeat(500) { index -> append("<p>Payload $index ${"content ".repeat(8)}</p>") }
            append("</body></html>")
        }
        val normalized = preserveRawHtmlPayload(rawHtml)
        val (placeholderMarkdown, math) = buildPlaceholderMarkdown(normalized)
        val planned = planMarkdownMessageChunks(placeholderMarkdown)

        assertTrue(normalized.startsWith("```text\n<!doctype html>"))
        assertTrue(normalized.endsWith("\n```"))
        assertTrue(math.isEmpty())
        assertEquals(listOf(normalized), planned.map { it.source })
        assertTrue(planned.all { it.renderMarkdown.startsWith("```text\n") })
        assertTrue(planned.none { it.renderMarkdown.startsWith("<!doctype html>") })

        val rows = planChatMessageRows(listOf(assistantMessage("assistant-html", rawHtml)))
        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
    }

    @Test
    fun `timeline indices count expanded rows and optional surrounding items`() {
        val expanded = assistantMessage("assistant-long", longMathText())
        val ordinary = assistantMessage("assistant-next", "Next response.")
        val rows = planChatMessageRows(listOf(expanded, ordinary))
        val expandedCount = rows.count { it.sourceMessageIndex == 0 }

        assertEquals(2 + expandedCount, timelineIndexForMessage(rows, 1, hasOlderMessages = true, hasRoster = true))
        assertEquals(
            2 + rows.size + 1,
            pendingTimelineStartIndex(
                rows = rows,
                hasOlderMessages = true,
                hasRoster = true,
                hasRevertBanner = true,
            ),
        )
    }

    @Test
    fun `auto follow observes last renderable text behind trailing step parts and row growth`() {
        val first = assistantMessage(
            id = "assistant-stream",
            text = streamingMathText(paragraphCount = 40),
            extraParts = listOf(Part.StepFinish("step-finish", SessionId, "assistant-stream")),
        )
        val firstRows = planChatMessageRows(listOf(first))
        val firstTarget = chatAutoFollowTarget(listOf(first), firstRows)
        val grown = assistantMessage(
            id = "assistant-stream",
            text = streamingMathText(paragraphCount = 80),
            extraParts = listOf(Part.StepFinish("step-finish", SessionId, "assistant-stream")),
        )
        val grownRows = planChatMessageRows(listOf(grown))
        val grownTarget = chatAutoFollowTarget(listOf(grown), grownRows)

        assertEquals("assistant-stream-text", firstTarget.partId)
        assertTrue(grownTarget.contentLength > firstTarget.contentLength)
        assertTrue(grownTarget.rowCount > firstTarget.rowCount)
        assertTrue(grownTarget.lastRowKey != firstTarget.lastRowKey)
    }

    private fun assistantMessage(
        id: String,
        text: String,
        extraParts: List<Part> = emptyList(),
    ): ChatMessage = ChatMessage(
        message = Message.Assistant(id = id, sessionId = SessionId, time = TimeInfo(created = 1L)),
        parts = listOf(textPart(id, "$id-text", text)) + extraParts,
    )

    private fun textPart(messageId: String, partId: String, text: String): Part.Text = Part.Text(
        id = partId,
        sessionId = SessionId,
        messageId = messageId,
        text = text,
    )

    private fun longMathText(): String = buildString {
        append("Display math: \\[x^2 + y^2 = z^2\\]\n\n")
        repeat(100) { index ->
            append("Lead paragraph $index ")
            append("content ".repeat(20))
            append("\n\n")
        }
        append("```text\n信号是连续的、网络只吃向量 ${"0123456789abcdef".repeat(36)}\n```\n\n")
        repeat(45) { index ->
            append("Trailing paragraph $index ")
            append("content ".repeat(20))
            if (index != 44) append("\n\n")
        }
    }

    private fun streamingMathText(paragraphCount: Int): String = buildString {
        append("Display math: \\[x^2\\]\n\n")
        repeat(paragraphCount) { index ->
            append("Streaming paragraph $index ")
            append("content ".repeat(20))
            if (index != paragraphCount - 1) append("\n\n")
        }
    }

    private companion object {
        const val SessionId = "session"
    }
}
