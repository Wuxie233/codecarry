package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReducerBadPartFilterTest {

    @Test
    fun messagePartUpdatedDropsToolPartsWithBlankCallIdOrToolName() {
        val reducer = EventReducer()
        val messageId = "msg-1"

        reducer.processEvent(
            SseEvent.MessagePartUpdated(
                toolPart(id = "missing-call", messageId = messageId, callId = "", tool = "bash")
            ),
            serverId = "server-1",
        )
        reducer.processEvent(
            SseEvent.MessagePartUpdated(
                toolPart(id = "missing-tool", messageId = messageId, callId = "call-1", tool = "   ")
            ),
            serverId = "server-1",
        )

        assertTrue(reducer.parts.value[messageId].orEmpty().isEmpty())
    }

    @Test
    fun messagePartUpdatedKeepsValidToolAndNonToolParts() {
        val reducer = EventReducer()
        val messageId = "msg-1"
        val parts = listOf(
            Part.Text(
                id = "text-1",
                sessionId = "ses-1",
                messageId = messageId,
                text = "hello",
            ),
            Part.File(
                id = "file-1",
                sessionId = "ses-1",
                messageId = messageId,
                filename = "example.txt",
            ),
            Part.Unknown(
                id = "unknown-1",
                sessionId = "ses-1",
                messageId = messageId,
            ),
            toolPart(id = "tool-1", messageId = messageId, callId = "call-1", tool = "bash"),
        )

        parts.forEach { part ->
            reducer.processEvent(SseEvent.MessagePartUpdated(part), serverId = "server-1")
        }

        assertEquals(parts.map { it.id }, reducer.parts.value[messageId].orEmpty().map { it.id })
    }

    @Test
    fun setMessagesDropsOnlyToolPartsWithBlankCallIdOrToolName() {
        val reducer = EventReducer()
        val messageId = "msg-1"
        val keptText = Part.Text(
            id = "text-1",
            sessionId = "ses-1",
            messageId = messageId,
            text = "hello",
        )
        val keptTool = toolPart(id = "tool-1", messageId = messageId, callId = "call-1", tool = "bash")
        val badCallIdTool = toolPart(id = "missing-call", messageId = messageId, callId = " ", tool = "bash")
        val badToolNameTool = toolPart(id = "missing-tool", messageId = messageId, callId = "call-2", tool = "")

        reducer.setMessages(
            sessionId = "ses-1",
            messages = listOf(
                MessageWithParts(
                    info = Message.Assistant(id = messageId, sessionId = "ses-1"),
                    parts = listOf(keptText, badCallIdTool, keptTool, badToolNameTool),
                )
            ),
        )

        assertEquals(listOf(keptText.id, keptTool.id), reducer.parts.value[messageId].orEmpty().map { it.id })
    }

    private fun toolPart(
        id: String,
        messageId: String,
        callId: String,
        tool: String,
    ) = Part.Tool(
        id = id,
        sessionId = "ses-1",
        messageId = messageId,
        callId = callId,
        tool = tool,
        state = ToolState.Pending(),
    )
}
