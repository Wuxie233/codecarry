package dev.minios.ocremote.service

import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.transport.AgentTransport
import org.junit.Assert.assertEquals
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
