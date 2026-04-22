package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventReducerTest {

    @Test
    fun sessionCreatedPreservesExistingBusyStatus() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        processStatusEvent(reducer, session.id, SessionStatus.Busy, serverId = "server-1")
        reducer.processEvent(SseEvent.SessionCreated(session), serverId = "server-1")

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun sessionCreatedInitializesMissingStatusToIdle() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        reducer.processEvent(SseEvent.SessionCreated(session), serverId = "server-1")

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun statusBeforeSessionCreatedIsClearedOnDisconnect() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        processStatusEvent(reducer, session.id, SessionStatus.Busy, serverId = "server-1")

        clearForServer(reducer, "server-1")

        assertNull(reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun clearedStatusDoesNotLeakIntoReconnectedSession() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        processStatusEvent(reducer, session.id, SessionStatus.Busy, serverId = "server-1")

        clearForServer(reducer, "server-1")
        reducer.processEvent(SseEvent.SessionCreated(session), serverId = "server-1")

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun sessionIdleBeforeSessionCreatedIsClearedOnDisconnect() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        reducer.processEvent(SseEvent.SessionIdle(sessionId = session.id), serverId = "server-1")

        clearForServer(reducer, "server-1")

        assertNull(reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun messagePartUpdatedRetainsLatestRunningToolOutput() {
        val reducer = EventReducer()
        val messageId = "msg-1"
        val toolId = "tool-1"
        val sessionId = "ses-1"

        reducer.processEvent(
            SseEvent.MessagePartUpdated(
                Part.Tool(
                    id = toolId,
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = "call-1",
                    tool = "bash",
                    state = ToolState.Running(output = "line 1", title = "Shell")
                )
            ),
            serverId = "server-1"
        )
        reducer.processEvent(
            SseEvent.MessagePartUpdated(
                Part.Tool(
                    id = toolId,
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = "call-1",
                    tool = "bash",
                    state = ToolState.Running(output = "line 1\nline 2", title = "Shell")
                )
            ),
            serverId = "server-1"
        )

        val runningTool = reducer.parts.value[messageId]?.single() as? Part.Tool
        val runningState = runningTool?.state as? ToolState.Running

        assertEquals("line 1\nline 2", runningState?.output)
        assertEquals("Shell", runningState?.title)
    }

    @Test
    fun sessionUpdatedWithArchivedTimeMarksSessionArchivedAndReordersByUpdatedTime() {
        val reducer = EventReducer()
        val firstSession = testSession(id = "ses-1", updated = 1L)
        val secondSession = testSession(id = "ses-2", updated = 2L)

        reducer.processEvent(SseEvent.SessionCreated(firstSession), serverId = "server-1")
        reducer.processEvent(SseEvent.SessionCreated(secondSession), serverId = "server-1")
        reducer.processEvent(
            SseEvent.SessionUpdated(firstSession.copy(time = firstSession.time.copy(updated = 3L, archived = 1_000L))),
            serverId = "server-1",
        )

        assertEquals(listOf("ses-1", "ses-2"), reducer.sessions.value.map { it.id })
        assertEquals(true, reducer.sessions.value.first().isArchived)
    }

    @Test
    fun sessionUpdatedWithoutArchivedTimeRestoresSessionAndReordersByUpdatedTime() {
        val reducer = EventReducer()
        val archived = testSession(id = "ses-restored", updated = 1L, archived = 1_000L)
        val secondSession = testSession(id = "ses-2", updated = 2L)

        reducer.processEvent(SseEvent.SessionCreated(archived), serverId = "server-1")
        reducer.processEvent(SseEvent.SessionCreated(secondSession), serverId = "server-1")
        reducer.processEvent(
            SseEvent.SessionUpdated(archived.copy(time = archived.time.copy(updated = 3L, archived = null))),
            serverId = "server-1",
        )

        assertEquals(listOf("ses-restored", "ses-2"), reducer.sessions.value.map { it.id })
        assertEquals(false, reducer.sessions.value.first().isArchived)
    }

    private fun testSession(id: String, updated: Long = 1L, archived: Long? = null) = Session(
        id = id,
        directory = "/tmp/project",
        time = Session.Time(
            created = 1L,
            updated = updated,
            archived = archived,
        ),
    )

    private fun processStatusEvent(
        reducer: EventReducer,
        sessionId: String,
        status: SessionStatus,
        serverId: String,
    ) {
        try {
            reducer.processEvent(
                SseEvent.SessionStatus(sessionId = sessionId, status = status),
                serverId = serverId
            )
        } catch (error: RuntimeException) {
            if (!error.message.orEmpty().contains("android.util.Log not mocked")) {
                throw error
            }
        }
    }

    private fun clearForServer(reducer: EventReducer, serverId: String) {
        try {
            reducer.clearForServer(serverId)
        } catch (error: RuntimeException) {
            if (!error.message.orEmpty().contains("android.util.Log not mocked")) {
                throw error
            }
        }
    }
}
