package dev.wuxie233.codecarry.data.repository

import dev.wuxie233.codecarry.data.api.PiStackEventCursorDto
import dev.wuxie233.codecarry.data.api.PiStackEventDto
import dev.wuxie233.codecarry.data.api.PiStackEventScopeDto
import dev.wuxie233.codecarry.data.api.PiStackSessionDto
import dev.wuxie233.codecarry.data.api.PiStackSessionState
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReducerPiStackTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `cursor guard ignores duplicates and requires repair for gaps or generation changes`() {
        val guard = PiStackCursorGuard()
        guard.installSnapshot("server-1", PiStackEventCursorDto("generation-1", "event-4", 4))

        assertTrue(guard.evaluate("server-1", event(4, "generation-1")) is PiStackEventResult.IgnoredDuplicate)
        assertTrue(guard.evaluate("server-1", event(6, "generation-1")) is PiStackEventResult.ResyncRequired)
        assertTrue(guard.evaluate("server-1", event(5, "generation-2")) is PiStackEventResult.ResyncRequired)
        val next = event(5, "generation-1")
        assertTrue(guard.evaluate("server-1", next) is PiStackEventResult.Applied)
        guard.advance("server-1", next)
        assertEquals("event-5", guard.cursor("server-1")?.eventId)
    }

    @Test
    fun `snapshot preserves exact Pi Stack awaiting states while presentation stays idle`() {
        val reducer = EventReducer()
        reducer.applyPiStackSnapshot(
            serverId = "server-1",
            cursor = PiStackEventCursorDto("generation-1", "event-2", 2),
            sessions = listOf(
                session("command", "awaiting_command"),
                session("skip", "awaiting_skip"),
            ),
            questions = emptyList(),
            notifications = emptyList(),
        )

        assertEquals(PiStackSessionState.AwaitingCommand, reducer.piStackSessionStatesByServer.value["server-1"]?.get("command"))
        assertEquals(PiStackSessionState.AwaitingSkip, reducer.piStackSessionStatesByServer.value["server-1"]?.get("skip"))
        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["command"])
        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["skip"])
    }

    @Test
    fun `structured messages merge full state then apply text delta and tool upsert`() {
        val reducer = EventReducer()
        reducer.applyPiStackSnapshot(
            "server-1",
            PiStackEventCursorDto("generation-1", null, 0),
            listOf(session("session-1", "busy")),
            emptyList(),
            emptyList(),
        )

        assertTrue(reducer.applyPiStackEvent(event(
            sequence = 1,
            type = "message.started",
            payload = """{"message":{"id":"message-1","sessionId":"session-1","promptId":"prompt-1","role":"assistant","status":"streaming","parts":[{"id":"text-1","type":"text","text":"Hi"}],"createdAt":"2026-07-22T00:00:00Z","completedAt":null}}""",
        ), "server-1") is PiStackEventResult.Applied)
        reducer.applyPiStackEvent(event(
            sequence = 2,
            type = "message.delta",
            payload = """{"messageId":"message-1","partId":"text-1","delta":" there"}""",
        ), "server-1")
        reducer.applyPiStackEvent(event(
            sequence = 3,
            type = "tool.completed",
            payload = """{"messageId":"message-1","part":{"id":"tool-1","type":"tool","toolCallId":"call-1","toolName":"bash","state":"completed","input":{"command":"pwd"},"output":"/tmp","error":null}}""",
        ), "server-1")

        assertEquals("Hi there", (reducer.parts.value["message-1"]?.first() as Part.Text).text)
        val tool = reducer.parts.value["message-1"]?.filterIsInstance<Part.Tool>()?.single()
        assertEquals("bash", tool?.tool)
        assertTrue(tool?.state is ToolState.Completed)
    }

    @Test
    fun `stale snapshot repair replaces server questions and sessions`() {
        val reducer = EventReducer()
        reducer.applyPiStackSnapshot(
            "server-1",
            PiStackEventCursorDto("generation-1", "event-2", 2),
            listOf(session("old", "idle")),
            emptyList(),
            emptyList(),
        )
        reducer.applyPiStackSnapshot(
            "server-1",
            PiStackEventCursorDto("generation-2", "event-1-new", 1),
            listOf(session("new", "busy", generation = "generation-2")),
            emptyList(),
            emptyList(),
        )

        assertEquals(setOf("new"), reducer.serverSessions.value["server-1"])
        assertEquals("generation-2", reducer.piStackCursor("server-1")?.generation)
    }

    @Test
    fun `operation lifecycle projects busy then idle`() {
        val reducer = EventReducer()
        reducer.applyPiStackSnapshot(
            "server-1",
            PiStackEventCursorDto("generation-1", null, 0),
            listOf(session("session-1", "idle")),
            emptyList(),
            emptyList(),
        )

        reducer.applyPiStackEvent(event(1, type = "operation.accepted", payload = """{"operationId":"op-1"}"""), "server-1")
        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["session-1"])
        reducer.applyPiStackEvent(event(2, type = "operation.settled", payload = """{"operationId":"op-1","outcome":"completed"}"""), "server-1")
        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["session-1"])
    }

    private fun event(
        sequence: Long,
        generation: String = "generation-1",
        type: String = "unknown.future",
        payload: String = "{}",
    ) = PiStackEventDto(
        protocolVersion = 1,
        generation = generation,
        eventId = "event-$sequence",
        sequence = sequence,
        scope = PiStackEventScopeDto(sessionId = "session-1"),
        type = type,
        payload = json.parseToJsonElement(payload),
        ts = "2026-07-22T00:00:00Z",
    )

    private fun session(id: String, state: String, generation: String = "generation-1") = PiStackSessionDto(
        id = id,
        projectId = "project-1",
        cwd = "/tmp/project",
        workerGeneration = generation,
        state = state,
        createdAt = "2026-07-22T00:00:00Z",
        updatedAt = "2026-07-22T00:00:00Z",
    )
}
