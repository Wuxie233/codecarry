package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshEventReducerTest {
    @Test
    fun `mux session event appends and queue snapshot replaces`() {
        val reducer = DshEventReducer()
        reducer.applyMux(
            "push-1",
            DshMuxFrame.SessionEvent(
                sessionId = "s1",
                event = DshSessionEvent(type = "user/message", seq = 1, time = 10),
            ),
        )
        reducer.applyMux(
            "push-2",
            DshMuxFrame.SessionQueue(
                sessionId = "s1",
                items = listOf(DshQueuedInboxItem("m1", "queued", JsonObject(emptyMap()))),
            ),
        )
        reducer.applyMux(
            "push-3",
            DshMuxFrame.SessionQueue(sessionId = "s1", items = emptyList()),
        )
        val session = reducer.state.value.sessions.getValue("s1")
        assertEquals(1, session.events.size)
        assertTrue(session.queue.isEmpty())
    }

    @Test
    fun `approval and question requested retain host rpcId until resolved`() {
        val reducer = DshEventReducer()
        reducer.applyMux(
            "approval-rpc",
            DshMuxFrame.ApprovalRequested(
                sessionId = "s1",
                approvalId = "a1",
                toolName = "bash",
            ),
        )
        reducer.applyMux(
            "question-rpc",
            DshMuxFrame.QuestionRequested(
                sessionId = "s1",
                questions = listOf(DshQuestionItem(id = "q1", question = "Ship?")),
            ),
        )
        assertEquals("approval-rpc", reducer.state.value.pendingApprovals.getValue("approval-rpc").rpcId)
        assertEquals("question-rpc", reducer.state.value.pendingQuestions.getValue("question-rpc").rpcId)

        reducer.applyMux("resolved-a", DshMuxFrame.ApprovalResolved("s1", "a1", "allowed-once"))
        reducer.applyMux("resolved-q", DshMuxFrame.QuestionResolved("s1", "question-rpc", "answered"))
        assertTrue(reducer.state.value.pendingApprovals.isEmpty())
        assertTrue(reducer.state.value.pendingQuestions.isEmpty())
    }

    @Test
    fun `projection higher-seq wins`() {
        val reducer = DshEventReducer()
        reducer.applyMux("p1", DshMuxFrame.SessionProjection("s1", "title", JsonPrimitive("old"), 2))
        reducer.applyMux("p2", DshMuxFrame.SessionProjection("s1", "title", JsonPrimitive("new"), 5))
        reducer.applyMux("p3", DshMuxFrame.SessionProjection("s1", "title", JsonPrimitive("stale"), 4))
        val stored = reducer.state.value.sessions.getValue("s1").projections.getValue("title")
        assertEquals(5L, stored.first)
        assertEquals("new", (stored.second as JsonPrimitive).content)
    }

    @Test
    fun `host session added and archive snapshot`() {
        val reducer = DshEventReducer()
        reducer.applyHost(
            DshHostFrame.SessionAdded(
                sessionId = "s1",
                blank = true,
                cwd = "/root/CODE/oc-remote",
            ),
        )
        reducer.applyHost(DshHostFrame.SessionStatus("s1", running = true))
        reducer.applyHost(DshHostFrame.ArchivedSessionsChanged(listOf("old")))
        val session = reducer.state.value.sessions.getValue("s1")
        assertEquals("/root/CODE/oc-remote", session.cwd)
        assertTrue(session.running)
        assertEquals(setOf("old"), reducer.state.value.archivedSessionIds)
    }

    @Test
    fun `reset generation clears pending requests`() {
        val reducer = DshEventReducer()
        reducer.applyMux(
            "approval-rpc",
            DshMuxFrame.ApprovalRequested("s1", "a1", "bash"),
        )
        reducer.resetGeneration(9)
        assertEquals(9L, reducer.state.value.generation)
        assertTrue(reducer.state.value.pendingApprovals.isEmpty())
        assertTrue(reducer.state.value.sessions.isEmpty())
        assertNull(reducer.state.value.sessions["s1"])
    }

    @Test
    fun `jobs snapshot replaces and unknown session event fields are kept`() {
        val reducer = DshEventReducer()
        reducer.applyMux(
            "jobs-1",
            DshMuxFrame.SessionJobs(
                sessionId = "s1",
                jobs = listOf(DshJobView("bash-1", "bash", "ls", "running", startedAt = 10)),
            ),
        )
        reducer.applyMux(
            "jobs-2",
            DshMuxFrame.SessionJobs(sessionId = "s1", jobs = emptyList()),
        )
        reducer.applyMux(
            "event-1",
            DshMuxFrame.SessionEvent(
                sessionId = "s1",
                event = DshSessionEvent(
                    type = "future/event",
                    seq = 4,
                    time = 20,
                    data = buildJsonObject { put("keep", "me") },
                    raw = buildJsonObject {
                        put("type", "future/event")
                        put("seq", 4)
                        put("extra", "field")
                    },
                ),
            ),
        )
        val session = reducer.state.value.sessions.getValue("s1")
        assertTrue(session.jobs.isEmpty())
        assertEquals("future/event", session.events.single().type)
        assertEquals("field", (session.events.single().raw["extra"] as JsonPrimitive).content)
    }

    @Test
    fun `host workspace changed upserts by workspaceId`() {
        val reducer = DshEventReducer()
        reducer.applyHost(
            DshHostFrame.WorkspaceChanged(
                buildJsonObject {
                    put("workspaceId", "w1")
                    put("title", "one")
                },
            ),
        )
        reducer.applyHost(DshHostFrame.WorkspaceOrderChanged(listOf("w1")))
        assertEquals("w1", reducer.state.value.workspaceOrder.single())
        assertEquals("one", (reducer.state.value.workspaces.getValue("w1")["title"] as JsonPrimitive).content)
        assertFalse(reducer.state.value.sessions.containsKey("w1"))
    }

    @Test
    fun `mergeHistory concatenates then mux queue snapshot replaces`() {
        val reducer = DshEventReducer()
        reducer.mergeHistory(
            sessionId = "s1",
            events = listOf(DshSessionEvent(type = "user/message", seq = 1, time = 10)),
        )
        reducer.mergeHistory(
            sessionId = "s1",
            events = listOf(DshSessionEvent(type = "assistant/chunk", seq = 2, time = 11)),
        )
        reducer.applyMux(
            "q1",
            DshMuxFrame.SessionQueue(
                sessionId = "s1",
                items = listOf(DshQueuedInboxItem("m1", "queued", JsonObject(emptyMap()))),
            ),
        )
        reducer.applyMux(
            "q2",
            DshMuxFrame.SessionQueue(
                sessionId = "s1",
                items = listOf(DshQueuedInboxItem("m2", "steering", JsonObject(emptyMap()))),
            ),
        )
        val session = reducer.state.value.sessions.getValue("s1")
        assertEquals(listOf(1L, 2L), session.events.map { it.seq })
        assertEquals(listOf("m2"), session.queue.map { it.id })
        assertEquals("steering", session.queue.single().placement)
    }

    @Test
    fun `approval requested keeps host rpcId until resolved frame`() {
        val reducer = DshEventReducer()
        reducer.applyMux(
            "host-rpc-1",
            DshMuxFrame.ApprovalRequested("s1", "a1", "bash", reason = "needs sandbox"),
        )
        val pending = reducer.state.value.pendingApprovals.getValue("host-rpc-1")
        assertEquals("host-rpc-1", pending.rpcId)
        assertEquals("a1", pending.approvalId)
        reducer.applyMux("resolved", DshMuxFrame.ApprovalResolved("s1", "a1", "allowed-once"))
        assertTrue(reducer.state.value.pendingApprovals.isEmpty())
    }
}
