package dev.wuxie233.codecarry.data.repository

import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.MessageWithParts
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.SseEvent
import dev.wuxie233.codecarry.domain.model.ToolState
import dev.wuxie233.codecarry.domain.model.TimeInfo
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

    @Test
    fun mergeMessagesKeepsLiveMessagesAndSameIdMessageAndParts() {
        val reducer = EventReducer()
        reducer.mergeMessages(
            sessionId = "ses-1",
            messages = listOf(message("rest-only", 1, "rest"), message("shared", 2, "stale")),
        )
        val liveMessage = Message.Assistant(id = "shared", sessionId = "ses-1", time = TimeInfo(created = 3), finish = "stop")
        val livePart = Part.Text(id = "part-shared", sessionId = "ses-1", messageId = "shared", text = "live")
        reducer.processEvent(SseEvent.MessageUpdated(liveMessage), "server-1")
        reducer.processEvent(SseEvent.MessagePartUpdated(livePart), "server-1")
        reducer.processEvent(
            SseEvent.MessageUpdated(Message.User(id = "live-only", sessionId = "ses-1", time = TimeInfo(created = 4))),
            "server-1",
        )

        reducer.mergeMessages(
            sessionId = "ses-1",
            messages = listOf(message("rest-only", 1, "rest"), message("shared", 2, "stale-again")),
        )

        assertEquals(listOf("rest-only", "shared", "live-only"), reducer.messages.value["ses-1"].orEmpty().map { it.id })
        assertEquals(liveMessage, reducer.messages.value["ses-1"].orEmpty().first { it.id == "shared" })
        assertEquals(listOf(livePart), reducer.parts.value["shared"])
    }

    private fun message(id: String, created: Long, text: String) = MessageWithParts(
        info = Message.Assistant(id = id, sessionId = "ses-1", time = TimeInfo(created = created)),
        parts = listOf(Part.Text(id = "part-$id", sessionId = "ses-1", messageId = id, text = text)),
    )

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
