package dev.minios.ocremote.service

import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.transport.AgentTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class SessionStatusSnapshotTest {

    @Test
    fun `project status snapshots keep scoped child sessions active when global snapshot is empty`() = kotlinx.coroutines.test.runTest {
        val childBusy = "child-busy"
        val childRetry = "child-retry"
        val transport = StatusSnapshotTransport(
            statusesByDirectory = mapOf(
                null to emptyMap(),
                "/workspace/a" to mapOf(childBusy to SessionStatus.Busy),
                "/workspace/b" to mapOf(
                    childRetry to SessionStatus.Retry(attempt = 1, message = "retrying", next = 0L),
                ),
            ),
        )

        val statuses = loadSessionStatusSnapshot(
            transport = transport,
            projectDirectories = listOf("/workspace/a", "/workspace/b", "/workspace/a"),
        )

        assertEquals(SessionStatus.Busy, statuses[childBusy])
        assertEquals(
            SessionStatus.Retry(attempt = 1, message = "retrying", next = 0L),
            statuses[childRetry],
        )
        assertEquals(listOf(null, "/workspace/a", "/workspace/b"), transport.requestedDirectories)
    }

    @Test
    fun `empty project snapshot preserves statuses from global snapshot`() = kotlinx.coroutines.test.runTest {
        val transport = StatusSnapshotTransport(
            statusesByDirectory = mapOf(
                null to mapOf("root-busy" to SessionStatus.Busy),
                "/workspace/empty" to emptyMap(),
            ),
        )

        val statuses = loadSessionStatusSnapshot(
            transport = transport,
            projectDirectories = listOf("/workspace/empty"),
        )

        assertEquals(mapOf("root-busy" to SessionStatus.Busy), statuses)
    }

    @Test
    fun `failed project snapshot does not reconcile incomplete statuses`() = kotlinx.coroutines.test.runTest {
        val reducer = EventReducer()
        val child = Session(id = "child", parentId = "parent")
        reducer.setSessions("server", listOf(child))
        reducer.processEvent(
            SseEvent.SessionStatus(sessionId = child.id, status = SessionStatus.Busy),
            serverId = "server",
        )
        val transport = StatusSnapshotTransport(
            statusesByDirectory = mapOf(null to emptyMap()),
            failingDirectories = setOf("/workspace/failing"),
        )
        var failure: IOException? = null

        try {
            reducer.reconcileSessionStatusSnapshot(
                serverId = "server",
                transport = transport,
                projectDirectories = listOf("/workspace/failing"),
            )
        } catch (error: IOException) {
            failure = error
        }

        assertEquals("status snapshot failed", failure?.message)
        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value[child.id])
    }

    @Test
    fun `snapshot cannot overwrite a newer live status event`() = kotlinx.coroutines.test.runTest {
        val reducer = EventReducer()
        val session = Session(id = "session")
        reducer.setSessions("server", listOf(session))
        reducer.processEvent(SseEvent.SessionStatus(session.id, SessionStatus.Busy), "server")
        val baseline = reducer.captureSessionStatusBaseline("server")

        reducer.processEvent(SseEvent.SessionIdle(session.id), "server")
        reducer.reconcileSessionStatuses(mapOf(session.id to SessionStatus.Busy), baseline)

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun `snapshot revision detects live ABA status changes`() = kotlinx.coroutines.test.runTest {
        val reducer = EventReducer()
        val session = Session(id = "session")
        reducer.setSessions("server", listOf(session))
        reducer.processEvent(SseEvent.SessionStatus(session.id, SessionStatus.Busy), "server")
        val baseline = reducer.captureSessionStatusBaseline("server")

        reducer.processEvent(SseEvent.SessionIdle(session.id), "server")
        reducer.processEvent(SseEvent.SessionStatus(session.id, SessionStatus.Busy), "server")
        reducer.reconcileSessionStatuses(emptyMap(), baseline)

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun `reconciling one server leaves another server unchanged`() = kotlinx.coroutines.test.runTest {
        val reducer = EventReducer()
        reducer.setSessions("server-a", listOf(Session(id = "a")))
        reducer.setSessions("server-b", listOf(Session(id = "b")))
        reducer.processEvent(SseEvent.SessionStatus("a", SessionStatus.Busy), "server-a")
        reducer.processEvent(SseEvent.SessionStatus("b", SessionStatus.Busy), "server-b")
        val baseline = reducer.captureSessionStatusBaseline("server-a")

        reducer.reconcileSessionStatuses(emptyMap(), baseline)

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["a"])
        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["b"])
    }

    @Test
    fun `snapshot cannot restore a session deleted while request was in flight`() = kotlinx.coroutines.test.runTest {
        val reducer = EventReducer()
        val session = Session(id = "session")
        reducer.setSessions("server", listOf(session))
        val baseline = reducer.captureSessionStatusBaseline("server")

        reducer.processEvent(SseEvent.SessionDeleted(session), "server")
        reducer.reconcileSessionStatuses(mapOf(session.id to SessionStatus.Busy), baseline)

        assertNull(reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun `session deleted before a new snapshot stays absent`() = kotlinx.coroutines.test.runTest {
        val reducer = EventReducer()
        val session = Session(id = "session")
        reducer.setSessions("server", listOf(session))
        reducer.processEvent(SseEvent.SessionStatus(session.id, SessionStatus.Busy), "server")

        reducer.processEvent(SseEvent.SessionDeleted(session), "server")
        val baseline = reducer.captureSessionStatusBaseline("server")
        reducer.reconcileSessionStatuses(emptyMap(), baseline)

        assertNull(reducer.sessionStatuses.value[session.id])
        assertEquals(emptySet<String>(), reducer.serverSessions.value["server"].orEmpty())
    }

    @Test
    fun `same session id on two servers is excluded from snapshot reconciliation`() = kotlinx.coroutines.test.runTest {
        val reducer = EventReducer()
        val shared = Session(id = "shared")
        reducer.setSessions("server-a", listOf(shared))
        reducer.setSessions("server-b", listOf(shared))
        reducer.processEvent(SseEvent.SessionStatus(shared.id, SessionStatus.Busy), "server-b")
        val baseline = reducer.captureSessionStatusBaseline("server-a")

        reducer.reconcileSessionStatuses(emptyMap(), baseline)

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value[shared.id])
    }

    @Test
    fun `snapshot captured before server clear cannot restore orphaned session`() = kotlinx.coroutines.test.runTest {
        val reducer = EventReducer()
        val session = Session(id = "session")
        reducer.setSessions("server", listOf(session))
        val baseline = reducer.captureSessionStatusBaseline("server")

        reducer.clearForServer("server")
        reducer.reconcileSessionStatuses(mapOf(session.id to SessionStatus.Busy), baseline)

        assertNull(reducer.sessionStatuses.value[session.id])
    }

    private class StatusSnapshotTransport(
        private val statusesByDirectory: Map<String?, Map<String, SessionStatus>>,
        private val failingDirectories: Set<String> = emptySet(),
    ) : AgentTransport {
        val requestedDirectories = mutableListOf<String?>()

        override suspend fun listRooms(
            directory: String?,
            rootsOnly: Boolean,
        ) = emptyList<dev.minios.ocremote.domain.transport.TransportRoom>()

        override suspend fun listRoomScopes() = emptyList<dev.minios.ocremote.domain.transport.TransportRoomScope>()

        override fun openEventStream(directory: String?) = kotlinx.coroutines.flow.emptyFlow<dev.minios.ocremote.domain.transport.TransportEvent>()

        override suspend fun getSessionStatuses(directory: String?): Map<String, SessionStatus> {
            requestedDirectories += directory
            if (directory in failingDirectories) throw IOException("status snapshot failed")
            return statusesByDirectory.getValue(directory)
        }

        override suspend fun sendMessage(
            roomId: String,
            parts: List<dev.minios.ocremote.domain.transport.TransportMessagePart>,
            model: dev.minios.ocremote.domain.transport.TransportModelSelection?,
            agent: String?,
            variant: String?,
            directory: String?,
        ) = Unit

        override suspend fun sendCommand(
            roomId: String,
            command: String,
            arguments: String,
            directory: String?,
        ) = false

        override suspend fun sendShellCommand(
            roomId: String,
            command: String,
            agent: String,
            model: dev.minios.ocremote.domain.transport.TransportModelSelection?,
            directory: String?,
        ) = false

        override suspend fun replyToPermission(
            requestId: String,
            reply: String,
            message: String?,
            directory: String?,
        ) = false
    }
}
