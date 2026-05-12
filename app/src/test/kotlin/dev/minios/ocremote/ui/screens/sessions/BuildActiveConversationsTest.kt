package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.ui.screens.sessions.components.ConversationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildActiveConversationsTest {

    @Test
    fun `idle root with no pending decisions is excluded`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Idle),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `busy root is included with BUSY status`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Busy),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.BUSY, items[0].status)
        assertEquals(0, items[0].pendingCount)
    }

    @Test
    fun `retry root is included with RETRY status`() {
        val root = rootSession("root1", updated = 100)
        val retry = SessionStatus.Retry(attempt = 2, message = "retrying", next = 0L)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to retry),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.RETRY, items[0].status)
    }

    @Test
    fun `pending question wins over busy and sets pendingCount`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Busy),
            pendingQuestions = mapOf(root.id to listOf(questionAsked("q1"), questionAsked("q2"))),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.AWAITING_QUESTION, items[0].status)
        assertEquals(2, items[0].pendingCount)
    }

    @Test
    fun `pending permission wins over busy but loses to pending question`() {
        val permissionOnly = rootSession("root1", updated = 100)
        val bothPending = rootSession("root2", updated = 90)

        val items = buildActiveConversations(
            rootSessions = listOf(permissionOnly, bothPending),
            statuses = mapOf(
                permissionOnly.id to SessionStatus.Busy,
                bothPending.id to SessionStatus.Idle,
            ),
            pendingQuestions = mapOf(
                bothPending.id to listOf(questionAsked("q1")),
            ),
            pendingPermissions = mapOf(
                permissionOnly.id to listOf(permissionAsked("p1")),
                bothPending.id to listOf(permissionAsked("p2")),
            ),
            unreadSessionIds = emptySet(),
        )

        assertEquals(listOf("root2", "root1"), items.map { it.sessionId })
        assertEquals(ConversationStatus.AWAITING_QUESTION, items[0].status)
        assertEquals(ConversationStatus.AWAITING_PERMISSION, items[1].status)
    }

    @Test
    fun `status priority comes before updated time and within-priority sorts by updated desc`() {
        val question1 = rootSession("q1", updated = 100)
        val question2 = rootSession("q2", updated = 200)
        val busy1 = rootSession("b1", updated = 500)
        val retry1 = rootSession("r1", updated = 999)

        val items = buildActiveConversations(
            rootSessions = listOf(question1, question2, busy1, retry1),
            statuses = mapOf(
                busy1.id to SessionStatus.Busy,
                retry1.id to SessionStatus.Retry(1, "x", 0L),
            ),
            pendingQuestions = mapOf(
                question1.id to listOf(questionAsked("qa1")),
                question2.id to listOf(questionAsked("qa2")),
            ),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        // AWAITING_QUESTION demands user attention and outranks background BUSY/RETRY work.
        // Within the same priority, updated desc wins, so q2 precedes q1.
        assertEquals(listOf("q2", "q1", "b1", "r1"), items.map { it.sessionId })
    }

    @Test
    fun `archived root is excluded even if it has pending decisions`() {
        val archived = rootSession("root1", updated = 100, archivedAt = 50L)

        val items = buildActiveConversations(
            rootSessions = listOf(archived),
            statuses = mapOf(archived.id to SessionStatus.Busy),
            pendingQuestions = mapOf(archived.id to listOf(questionAsked("q1"))),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `unread root is included with UNREAD status`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Idle),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = setOf(root.id),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.UNREAD, items[0].status)
    }

    @Test
    fun `unread sorts before pending decision items`() {
        val unread = rootSession("unread", updated = 100)
        val question = rootSession("question", updated = 200)

        val items = buildActiveConversations(
            rootSessions = listOf(unread, question),
            statuses = mapOf(
                unread.id to SessionStatus.Idle,
                question.id to SessionStatus.Idle,
            ),
            pendingQuestions = mapOf(question.id to listOf(questionAsked("qa"))),
            pendingPermissions = emptyMap(),
            unreadSessionIds = setOf(unread.id),
        )

        assertEquals(listOf("unread", "question"), items.map { it.sessionId })
        assertEquals(ConversationStatus.UNREAD, items[0].status)
        assertEquals(ConversationStatus.AWAITING_QUESTION, items[1].status)
    }

    @Test
    fun `unread sorts before busy and retry sessions`() {
        val unread = rootSession("unread", updated = 100)
        val busy = rootSession("busy", updated = 300)
        val retry = rootSession("retry", updated = 200)

        val items = buildActiveConversations(
            rootSessions = listOf(unread, busy, retry),
            statuses = mapOf(
                unread.id to SessionStatus.Idle,
                busy.id to SessionStatus.Busy,
                retry.id to SessionStatus.Retry(1, "x", 0L),
            ),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = setOf(unread.id),
        )

        assertEquals(listOf("unread", "busy", "retry"), items.map { it.sessionId })
        assertEquals(ConversationStatus.UNREAD, items[0].status)
        assertEquals(ConversationStatus.BUSY, items[1].status)
        assertEquals(ConversationStatus.RETRY, items[2].status)
    }

    @Test
    fun `idle root with pending permission is included with AWAITING_PERMISSION status`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Idle),
            pendingQuestions = emptyMap(),
            pendingPermissions = mapOf(root.id to listOf(permissionAsked("perm-1"))),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.AWAITING_PERMISSION, items[0].status)
        assertEquals(1, items[0].pendingCount)
    }

    @Test
    fun `multiple pending permissions on idle root surface pendingCount`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Idle),
            pendingQuestions = emptyMap(),
            pendingPermissions = mapOf(
                root.id to listOf(
                    permissionAsked("perm-1"),
                    permissionAsked("perm-2"),
                    permissionAsked("perm-3"),
                ),
            ),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.AWAITING_PERMISSION, items[0].status)
        assertEquals(3, items[0].pendingCount)
    }

    @Test
    fun `awaiting permission preserves busy ordering when both states are present across sessions`() {
        // Regression guard: surfacing awaiting-permission visibility must not
        // perturb the existing UNREAD < AWAITING_QUESTION < AWAITING_PERMISSION
        // < BUSY < RETRY priority on the other sessions in the same banner.
        val permRoot = rootSession("root-perm", updated = 100)
        val busyRoot = rootSession("root-busy", updated = 200) // newer

        val items = buildActiveConversations(
            rootSessions = listOf(permRoot, busyRoot),
            statuses = mapOf(
                permRoot.id to SessionStatus.Idle,
                busyRoot.id to SessionStatus.Busy,
            ),
            pendingQuestions = emptyMap(),
            pendingPermissions = mapOf(permRoot.id to listOf(permissionAsked("perm-1"))),
            unreadSessionIds = emptySet(),
        )

        // AWAITING_PERMISSION ordinal < BUSY ordinal, so permRoot comes first
        // even though busyRoot is newer.
        assertEquals(listOf("root-perm", "root-busy"), items.map { it.sessionId })
        assertEquals(ConversationStatus.AWAITING_PERMISSION, items[0].status)
        assertEquals(ConversationStatus.BUSY, items[1].status)
    }

    private fun rootSession(id: String, updated: Long, archivedAt: Long? = null) = Session(
        id = id,
        slug = id,
        projectId = "p",
        directory = "/root/CODE/demo",
        parentId = null,
        title = id,
        version = "1.0.0",
        time = Session.Time(created = updated - 10, updated = updated, archived = archivedAt),
    )

    private fun questionAsked(id: String) = SseEvent.QuestionAsked(
        id = id,
        sessionId = "s",
        questions = emptyList(),
    )

    private fun permissionAsked(id: String) = SseEvent.PermissionAsked(
        id = id,
        sessionId = "s",
        permission = "p",
    )
}
