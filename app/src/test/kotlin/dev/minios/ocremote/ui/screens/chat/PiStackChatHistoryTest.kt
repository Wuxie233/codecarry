package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.PiStackMessagePageDto
import dev.minios.ocremote.data.api.PiStackStructuredMessageDto
import dev.minios.ocremote.data.api.PiStackStructuredPartDto
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PiStackChatHistoryTest {

    @Test
    fun `latest history keeps newer live message content`() {
        val history = message("assistant", 20, "history")
        val live = message("assistant", 20, "history plus live delta")

        val result = mergePiStackLatestHistory(
            page = PiStackChatHistoryPage(listOf(message("user", 10, "prompt"), history), "older", true),
            liveMessages = listOf(live),
        )

        assertEquals(listOf("user", "assistant"), result.messages.map { it.info.id })
        assertEquals("history plus live delta", result.messages.last().text())
        assertEquals("older", result.nextCursor)
        assertTrue(result.hasMore)
    }

    @Test
    fun `older history prepends and deduplicates boundary message`() {
        val currentBoundary = message("boundary", 20, "live boundary")
        val current = PiStackChatHistoryState(
            messages = listOf(currentBoundary, message("recent", 30, "recent")),
            nextCursor = "before-1",
            hasMore = true,
        )

        val result = mergePiStackOlderHistory(
            current,
            PiStackChatHistoryPage(
                items = listOf(message("old", 10, "old"), message("boundary", 20, "stale boundary")),
                nextCursor = null,
                hasMore = false,
            ),
        )

        assertEquals(listOf("old", "boundary", "recent"), result.messages.map { it.info.id })
        assertEquals("live boundary", result.messages[1].text())
        assertNull(result.nextCursor)
        assertFalse(result.hasMore)
    }

    @Test
    fun `latest history reconciles fallback id with matching live content once`() {
        val historicalDuplicate = message("message-1", 20, "same answer")
        val historicalEarlier = message("message-0", 10, "same answer")
        val live = message("message-prompt-1", 30, "same answer")

        val result = mergePiStackLatestHistory(
            page = PiStackChatHistoryPage(listOf(historicalEarlier, historicalDuplicate), null, false),
            liveMessages = listOf(live),
        )

        assertEquals(listOf("message-0", "message-prompt-1"), result.messages.map { it.info.id })
    }

    @Test
    fun `structured history maps text and completed tool to existing cards`() {
        val page = PiStackMessagePageDto(
            items = listOf(
                PiStackStructuredMessageDto(
                    id = "assistant",
                    sessionId = "session",
                    role = "assistant",
                    status = "completed",
                    parts = listOf(
                        PiStackStructuredPartDto.Text(id = "text", text = "answer"),
                        PiStackStructuredPartDto.Tool(
                            id = "tool",
                            toolCallId = "call-1",
                            toolName = "bash",
                            state = "completed",
                            input = buildJsonObject { put("command", "pwd") },
                            output = buildJsonObject { put("stdout", "/work") },
                        ),
                    ),
                    createdAt = "2026-07-22T10:00:00Z",
                    completedAt = "2026-07-22T10:00:01Z",
                ),
            ),
            nextCursor = "opaque",
            hasMore = true,
        )

        val mapped = page.toChatHistoryPage()

        assertEquals("opaque", mapped.nextCursor)
        assertTrue(mapped.hasMore)
        assertEquals("answer", (mapped.items.single().parts[0] as Part.Text).text)
        val tool = mapped.items.single().parts[1] as Part.Tool
        assertEquals("call-1", tool.callId)
        assertEquals("bash", tool.tool)
        assertTrue(tool.state is dev.minios.ocremote.domain.model.ToolState.Completed)
    }

    private fun message(id: String, created: Long, text: String): MessageWithParts = MessageWithParts(
        info = Message.Assistant(id = id, sessionId = "session", time = TimeInfo(created = created)),
        parts = listOf(Part.Text(id = "$id-text", sessionId = "session", messageId = id, text = text)),
    )

    private fun MessageWithParts.text(): String = (parts.single() as Part.Text).text
}
