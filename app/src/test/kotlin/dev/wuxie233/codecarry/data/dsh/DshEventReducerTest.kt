package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshEventReducerTest {
    @Test
    fun `follow event appends and control queue snapshot replaces`() {
        val reducer = DshEventReducer()
        reducer.applyFollowEvent("s1", DshSessionEvent(type = "user/message", seq = 1, time = 10))
        reducer.applyControlFrame(
            DshControlFrame.Queue(
                sessionId = "s1",
                items = listOf(DshQueuedInboxItem("m1", "queued", JsonObject(emptyMap()))),
            ),
        )
        reducer.applyControlFrame(DshControlFrame.Queue(sessionId = "s1", items = emptyList()))
        val session = reducer.state.value.sessions.getValue("s1")
        assertEquals(1, session.events.size)
        assertTrue(session.queue.isEmpty())
    }

    @Test
    fun `approval and question waterfalls retain eventId until cancelled`() {
        val reducer = DshEventReducer()
        reducer.applyEventsFrame(
            DshEventsFrame.Waterfall(
                eventId = "event-1",
                event = "approval/request",
                agentId = "s1",
                request = buildJsonObject {
                    put("toolName", "bash")
                    put("callId", "call-9")
                },
            ),
        )
        reducer.applyEventsFrame(
            DshEventsFrame.Waterfall(
                eventId = "event-2",
                event = "user-questions/request",
                agentId = "s1",
                request = buildJsonObject {
                    put(
                        "questions",
                        buildJsonArray {
                            add(buildJsonObject { put("id", "q1"); put("question", "Ship?") })
                        },
                    )
                },
            ),
        )
        assertEquals("event-1", reducer.state.value.pendingApprovals.getValue("event-1").eventId)
        assertEquals("bash", reducer.state.value.pendingApprovals.getValue("event-1").toolName)
        assertEquals("s1", reducer.state.value.pendingApprovals.getValue("event-1").sessionId)
        assertEquals("event-2", reducer.state.value.pendingQuestions.getValue("event-2").eventId)
        assertEquals(1, reducer.state.value.pendingQuestions.getValue("event-2").questions.size)
        reducer.removePendingApproval("event-1")
        reducer.removePendingQuestion("event-2")
        assertTrue(reducer.state.value.pendingApprovals.isEmpty())
        assertTrue(reducer.state.value.pendingQuestions.isEmpty())
    }

    @Test
    fun `events ready stores clientId and home`() {
        val reducer = DshEventReducer()
        reducer.applyEventsFrame(DshEventsFrame.Ready(clientId = "client-7", home = "/root"))
        assertEquals("client-7", reducer.state.value.eventsClientId)
        assertEquals("/root", reducer.state.value.home)
    }

    @Test
    fun `api-session emits add remove status error and activity`() {
        val reducer = DshEventReducer()
        reducer.applyEventsFrame(
            DshEventsFrame.Emit(
                event = "api-session/added",
                args = listOf(
                    buildJsonObject {
                        put("sessionId", "s1")
                        put("updatedAt", 5)
                        put("running", false)
                        put("blank", true)
                        put("cwd", "/tmp")
                    },
                ),
            ),
        )
        assertTrue(reducer.state.value.sessions.containsKey("s1"))
        reducer.applyEventsFrame(
            DshEventsFrame.Emit(event = "api-session/status", args = listOf(JsonPrimitive("s1"), JsonPrimitive(true))),
        )
        assertTrue(reducer.state.value.sessions.getValue("s1").running)
        reducer.applyEventsFrame(
            DshEventsFrame.Emit(event = "api-session/activity", args = listOf(JsonPrimitive("s1"), JsonPrimitive(9))),
        )
        assertEquals(9L, reducer.state.value.sessions.getValue("s1").updatedAt)
        reducer.applyEventsFrame(
            DshEventsFrame.Emit(event = "api-session/error", args = listOf(JsonPrimitive("s1"), JsonPrimitive("boom"))),
        )
        assertEquals("boom", reducer.state.value.sessions.getValue("s1").error)
        reducer.applyEventsFrame(
            DshEventsFrame.Emit(event = "api-session/removed", args = listOf(JsonPrimitive("s1"))),
        )
        assertFalse(reducer.state.value.sessions.containsKey("s1"))
    }

    @Test
    fun `control baseline seeds queues jobs and projections`() {
        val reducer = DshEventReducer()
        reducer.applyControlFrame(
            DshControlFrame.Baseline(
                queues = mapOf(
                    "s1" to listOf(DshQueuedInboxItem("m1", "queued", JsonObject(emptyMap()))),
                ),
                jobs = mapOf(
                    "s1" to listOf(DshJobView(id = "j1", kind = "automation", label = "run", status = "running")),
                ),
                projections = mapOf(
                    "s1" to DshProjectionsBlock(
                        asOfSeq = 3,
                        values = buildJsonObject { put("title", JsonPrimitive("hello")) },
                    ),
                ),
            ),
        )
        val session = reducer.state.value.sessions.getValue("s1")
        assertEquals(1, session.queue.size)
        assertEquals(1, session.jobs.size)
        assertEquals("hello", (session.projections.getValue("title").second as? JsonPrimitive)?.content)
    }

    @Test
    fun `workspace baseline and increments update catalog`() {
        val reducer = DshEventReducer()
        val view = DshWorkspaceView(
            workspaceId = "w1",
            path = "/tmp",
            title = "tmp",
            sessionIds = listOf("s1"),
            createdAt = "t",
            updatedAt = "t",
        )
        reducer.applyWorkspaceFrame(
            DshWorkspaceFrame.Baseline(
                value = DshWorkspaceListValue(
                    items = listOf(view),
                    archivedSessionIds = listOf("old"),
                    hiddenWorkspaceIds = listOf("w0"),
                ),
            ),
        )
        assertEquals(listOf("w1"), reducer.state.value.workspaceOrder)
        assertEquals(setOf("old"), reducer.state.value.archivedSessionIds)
        assertEquals(setOf("w0"), reducer.state.value.hiddenWorkspaceIds)
        reducer.applyWorkspaceFrame(DshWorkspaceFrame.Remove("w1"))
        assertTrue(reducer.state.value.workspaces.isEmpty())
    }

    @Test
    fun `follow snapshot replaces history and advances cursor`() {
        val reducer = DshEventReducer()
        reducer.applyFollowSnapshot(
            "s1",
            DshFollowFrame.Snapshot(
                header = null,
                cursor = 4,
                records = listOf(
                    DshHistoryRecord(type = "event", event = DshSessionEventDto(type = "user/message", seq = 1, time = 1)),
                    DshHistoryRecord(type = "event", event = DshSessionEventDto(type = "assistant/message", seq = 2, time = 2)),
                ),
                hasMore = true,
            ),
        )
        val session = reducer.state.value.sessions.getValue("s1")
        assertEquals(2, session.events.size)
        assertEquals(4L, session.lastSeq)
        reducer.applyFollowEvent("s1", DshSessionEvent(type = "assistant/message", seq = 5, time = 5))
        assertEquals(3, reducer.state.value.sessions.getValue("s1").events.size)
    }

    @Test
    fun `session list merge keeps newer live state`() {
        val reducer = DshEventReducer()
        reducer.applyFollowEvent("s1", DshSessionEvent(type = "user/message", seq = 1, time = 10))
        reducer.applySessionList(
            listOf(
                DshSessionSummary(
                    sessionId = "s1",
                    updatedAt = 2,
                    running = true,
                    blank = false,
                    cwd = "/tmp",
                ),
            ),
        )
        val session = reducer.state.value.sessions.getValue("s1")
        assertTrue(session.running)
        assertEquals(1, session.events.size)
    }

    @Test
    fun `clearPending drops waterfalls without touching sessions`() {
        val reducer = DshEventReducer()
        reducer.applyFollowEvent("s1", DshSessionEvent(type = "user/message", seq = 1, time = 10))
        reducer.applyEventsFrame(
            DshEventsFrame.Waterfall(
                eventId = "e1",
                event = "approval/request",
                agentId = "s1",
                request = buildJsonObject { put("toolName", "bash") },
            ),
        )
        reducer.clearPending()
        assertTrue(reducer.state.value.pendingApprovals.isEmpty())
        assertTrue(reducer.state.value.sessions.containsKey("s1"))
        assertNull(reducer.state.value.home)
    }
}
