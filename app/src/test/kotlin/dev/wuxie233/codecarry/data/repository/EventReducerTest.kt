package dev.wuxie233.codecarry.data.repository

import dev.wuxie233.codecarry.domain.model.Session
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.domain.model.SseEvent
import dev.wuxie233.codecarry.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun sessionCreatedUpsertsExistingSessionById() {
        val reducer = EventReducer()
        val optimisticSession = testSession(id = "ses_new", updated = 1L)
        val createdSession = optimisticSession.copy(
            title = "Created from SSE",
            time = optimisticSession.time.copy(updated = 2L),
        )

        reducer.setSessions("server-1", listOf(optimisticSession))
        reducer.processEvent(SseEvent.SessionCreated(createdSession), serverId = "server-1")

        assertEquals(listOf("ses_new"), reducer.sessions.value.map { it.id })
        assertEquals(createdSession, reducer.sessions.value.single())
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

    @Test
    fun permissionAskedAddsPendingEntry() {
        val reducer = EventReducer()
        val asked = SseEvent.PermissionAsked(
            id = "perm-1",
            sessionId = "ses-1",
            permission = "write file foo.txt",
        )

        reducer.processEvent(asked, serverId = "server-1")

        val pending = reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty()
        assertEquals(1, pending.size)
        assertEquals("perm-1", pending.single().id)
    }

    @Test
    fun permissionRepliedClearsPendingEntryForRequestId() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-2", sessionId = "ses-1", permission = "p2"),
            serverId = "server-1",
        )

        reducer.processEvent(
            SseEvent.PermissionReplied(sessionId = "ses-1", requestId = "perm-1"),
            serverId = "server-1",
        )

        val remaining = reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().map { it.id }
        assertEquals(listOf("perm-2"), remaining)
    }

    @Test
    fun removePermissionOptimisticallyClearsPendingEntry() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )

        reducer.removePermission("server-1", "perm-1")

        assertTrue(reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().isEmpty())
    }

    @Test
    fun removePermissionIsIdempotentWhenRequestIdMissing() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )

        reducer.removePermission("server-1", "perm-unknown")
        reducer.removePermission("server-1", "perm-1")
        reducer.removePermission("server-1", "perm-1") // second call: no-op

        assertTrue(reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().isEmpty())
    }

    @Test
    fun permissionRepliedAfterLocalRemovalIsNoOp() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )
        reducer.removePermission("server-1", "perm-1")

        // Late-arriving SSE event must not crash, duplicate, or resurrect state.
        reducer.processEvent(
            SseEvent.PermissionReplied(sessionId = "ses-1", requestId = "perm-1"),
            serverId = "server-1",
        )

        assertTrue(reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().isEmpty())
    }

    // ============ Bootstrap hydration (proactive REST snapshot on connect/open) ============

    @Test
    fun setSessionStatusesUpsertsBusyAndRetryFromSnapshot() {
        val reducer = EventReducer()
        reducer.setSessions("server-1", listOf(testSession("ses-busy"), testSession("ses-retry")))

        reducer.setSessionStatuses(
            "server-1",
            mapOf(
                "ses-busy" to SessionStatus.Busy,
                "ses-retry" to SessionStatus.Retry(attempt = 2, message = "rate limited", next = 1_700L),
            ),
        )

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["ses-busy"])
        assertEquals(
            SessionStatus.Retry(attempt = 2, message = "rate limited", next = 1_700L),
            reducer.sessionStatuses.value["ses-retry"],
        )
    }

    @Test
    fun setSessionStatusesResetsTrackedSessionAbsentFromSnapshotToIdle() {
        val reducer = EventReducer()
        reducer.setSessions("server-1", listOf(testSession("ses-was-busy"), testSession("ses-now-busy")))
        // ses-was-busy was retrying before; the fresh snapshot no longer lists it (it completed while we were away).
        processStatusEvent(reducer, "ses-was-busy", SessionStatus.Retry(attempt = 1, message = "x", next = 9L), "server-1")

        reducer.setSessionStatuses("server-1", mapOf("ses-now-busy" to SessionStatus.Busy))

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["ses-was-busy"])
        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["ses-now-busy"])
    }

    @Test
    fun setSessionStatusesDoesNotResetOtherServersSessions() {
        val reducer = EventReducer()
        reducer.setSessions("server-1", listOf(testSession("ses-s1")))
        reducer.setSessions("server-2", listOf(testSession("ses-s2")))
        processStatusEvent(reducer, "ses-s2", SessionStatus.Busy, "server-2")

        // Bootstrapping server-1 with an empty snapshot must not touch server-2's busy session.
        reducer.setSessionStatuses("server-1", emptyMap())

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["ses-s2"])
    }

    @Test
    fun clearForServerKeepsSessionStillOwnedByAnotherServer() {
        val reducer = EventReducer()
        val shared = testSession("shared")
        reducer.setSessions("server-1", listOf(shared))
        reducer.setSessions("server-2", listOf(shared))
        processStatusEvent(reducer, shared.id, SessionStatus.Busy, "server-2")
        reducer.setActiveSessionId(shared.id)

        clearForServer(reducer, "server-1")

        assertEquals(setOf(shared.id), reducer.serverSessions.value["server-2"])
        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value[shared.id])
        assertTrue(reducer.sessions.value.any { it.id == shared.id })
        assertEquals(shared.id, reducer.activeSessionId.value)
    }

    @Test
    fun permissionAskedIsIdempotentByRequestId() {
        val reducer = EventReducer()
        val asked = SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p")

        reducer.processEvent(asked, serverId = "server-1")
        reducer.processEvent(asked, serverId = "server-1")

        assertEquals(listOf("perm-1"), reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().map { it.id })
    }

    @Test
    fun mergePermissionsAddsSnapshotEntriesWithoutWipingLiveEntries() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "live-1", sessionId = "ses-1", permission = "live"),
            serverId = "server-1",
        )

        reducer.mergePermissions(
            "server-1",
            "ses-1",
            listOf(
                SseEvent.PermissionAsked(id = "live-1", sessionId = "ses-1", permission = "live"),
                SseEvent.PermissionAsked(id = "snap-1", sessionId = "ses-1", permission = "snap"),
            ),
        )

        assertEquals(setOf("live-1", "snap-1"), reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().map { it.id }.toSet())
    }

    @Test
    fun mergePermissionsWithEmptySnapshotPreservesLiveEntry() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "live-1", sessionId = "ses-1", permission = "live"),
            serverId = "server-1",
        )

        reducer.mergePermissions("server-1", "ses-1", emptyList())

        assertEquals(listOf("live-1"), reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().map { it.id })
    }

    @Test
    fun reconcilePermissionsClearsStalePreExistingEntryAbsentFromSnapshot() {
        val reducer = EventReducer()
        reducer.setSessions("server-1", listOf(testSession("ses-1")))
        // This permission was pending before reconnect; it was replied while disconnected, so the snapshot omits it.
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "stale-1", sessionId = "ses-1", permission = "stale"),
            serverId = "server-1",
        )

        reducer.reconcilePermissions("server-1", snapshot = emptyList(), preExistingIds = setOf("stale-1"))

        assertTrue(reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().isEmpty())
    }

    @Test
    fun reconcilePermissionsPreservesLiveEntryArrivedDuringBootstrap() {
        val reducer = EventReducer()
        reducer.setSessions("server-1", listOf(testSession("ses-1")))
        // "live-1" arrived via SSE during the bootstrap window, so it is NOT in preExistingIds and NOT in the snapshot.
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "live-1", sessionId = "ses-1", permission = "live"),
            serverId = "server-1",
        )

        reducer.reconcilePermissions("server-1", snapshot = emptyList(), preExistingIds = emptySet())

        assertEquals(listOf("live-1"), reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().map { it.id })
    }

    @Test
    fun reconcilePermissionsAddsSnapshotEntriesAndKeepsConfirmedPreExisting() {
        val reducer = EventReducer()
        reducer.setSessions("server-1", listOf(testSession("ses-1")))
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "keep-1", sessionId = "ses-1", permission = "keep"),
            serverId = "server-1",
        )

        reducer.reconcilePermissions(
            "server-1",
            snapshot = listOf(
                SseEvent.PermissionAsked(id = "keep-1", sessionId = "ses-1", permission = "keep"),
                SseEvent.PermissionAsked(id = "new-1", sessionId = "ses-1", permission = "new"),
            ),
            preExistingIds = setOf("keep-1"),
        )

        assertEquals(setOf("keep-1", "new-1"), reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().map { it.id }.toSet())
    }

    @Test
    fun pendingRequestsWithSameSessionIdRemainOwnedByTheirServer() {
        val reducer = EventReducer()
        val sessionId = "shared-session"
        reducer.setSessions("server-1", listOf(testSession(sessionId)))
        reducer.setSessions("server-2", listOf(testSession(sessionId)))

        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = sessionId, permission = "one"),
            serverId = "server-1",
        )
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-2", sessionId = sessionId, permission = "two"),
            serverId = "server-2",
        )
        reducer.processEvent(
            SseEvent.QuestionAsked(id = "question-1", sessionId = sessionId, questions = emptyList()),
            serverId = "server-1",
        )
        reducer.processEvent(
            SseEvent.QuestionAsked(id = "question-2", sessionId = sessionId, questions = emptyList()),
            serverId = "server-2",
        )

        reducer.processEvent(
            SseEvent.PermissionReplied(sessionId = sessionId, requestId = "perm-1"),
            serverId = "server-1",
        )
        reducer.processEvent(
            SseEvent.QuestionReplied(sessionId = sessionId, requestId = "question-1"),
            serverId = "server-1",
        )

        assertEquals(listOf("perm-2"), reducer.permissionsByServer.value["server-2"]?.get(sessionId).orEmpty().map { it.id })
        assertEquals(listOf("question-2"), reducer.questionsByServer.value["server-2"]?.get(sessionId).orEmpty().map { it.id })
    }

    @Test
    fun clearForServerKeepsOtherServersPendingRequestsForSameSessionId() {
        val reducer = EventReducer()
        val sessionId = "shared-session"
        reducer.setSessions("server-1", listOf(testSession(sessionId)))
        reducer.setSessions("server-2", listOf(testSession(sessionId)))
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = sessionId, permission = "one"),
            serverId = "server-1",
        )
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-2", sessionId = sessionId, permission = "two"),
            serverId = "server-2",
        )

        clearForServer(reducer, "server-1")

        assertNull(reducer.permissionsByServer.value["server-1"])
        assertEquals(listOf("perm-2"), reducer.permissionsByServer.value["server-2"]?.get(sessionId).orEmpty().map { it.id })
    }

    @Test
    fun clearForServerRemovesPendingRequestsReceivedBeforeSessionTracking() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.QuestionAsked(id = "question-1", sessionId = "not-tracked-yet", questions = emptyList()),
            serverId = "server-1",
        )

        clearForServer(reducer, "server-1")

        assertNull(reducer.questionsByServer.value["server-1"])
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
