package dev.wuxie233.codecarry.ui.screens.sessions

import dev.wuxie233.codecarry.domain.model.Session
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.domain.model.SseEvent
import dev.wuxie233.codecarry.ui.screens.sessions.components.ConversationStatus
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
        assertEquals(root.directory, items[0].directory)
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
        assertEquals(listOf("q2", "q1", "r1", "b1"), items.map { it.sessionId })
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
    fun `pending question sorts before unread`() {
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

        assertEquals(listOf("question", "unread"), items.map { it.sessionId })
        assertEquals(ConversationStatus.AWAITING_QUESTION, items[0].status)
        assertEquals(ConversationStatus.UNREAD, items[1].status)
    }

    @Test
    fun `retry and busy sort before unread`() {
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

        assertEquals(listOf("retry", "busy", "unread"), items.map { it.sessionId })
        assertEquals(ConversationStatus.RETRY, items[0].status)
        assertEquals(ConversationStatus.BUSY, items[1].status)
        assertEquals(ConversationStatus.UNREAD, items[2].status)
    }

    @Test
    fun `primary kind follows contract and secondary signals are preserved`() {
        val root = rootSession("root", updated = 100)

        val queue = buildSessionActivityQueue(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Retry(1, "retrying", 0L)),
            pendingQuestions = mapOf(root.id to listOf(questionAsked("q1"), questionAsked("q2"))),
            pendingPermissions = mapOf(root.id to listOf(permissionAsked("p1"))),
            unreadSessionIds = setOf(root.id),
        )

        val item = queue.items.single()
        assertEquals(SessionActivityKind.QUESTION, item.primaryKind)
        assertEquals(2, item.signals.questionCount)
        assertEquals(1, item.signals.permissionCount)
        assertTrue(item.signals.hasRetry)
        assertTrue(item.signals.isUnread)
        assertEquals(1, queue.sessionCountsByKind.getValue(SessionActivityKind.PERMISSION))
        assertEquals(2, queue.signalCountsByKind.getValue(SessionActivityKind.QUESTION))
    }

    @Test
    fun `filter matches secondary signal while groups retain primary kind`() {
        val questionAndPermission = rootSession("both", updated = 100)
        val permissionOnly = rootSession("permission", updated = 90)

        val queue = buildSessionActivityQueue(
            rootSessions = listOf(questionAndPermission, permissionOnly),
            statuses = emptyMap(),
            pendingQuestions = mapOf(questionAndPermission.id to listOf(questionAsked("q"))),
            pendingPermissions = mapOf(
                questionAndPermission.id to listOf(permissionAsked("p1")),
                permissionOnly.id to listOf(permissionAsked("p2")),
            ),
            unreadSessionIds = emptySet(),
            filter = SessionActivityFilter.PENDING,
        )

        assertEquals(listOf("both", "permission"), queue.items.map { it.sessionId })
        assertEquals(
            listOf(SessionActivityGroupKind.PENDING_ACTION),
            queue.groups.map { it.kind },
        )
        assertEquals(2, queue.sessionCountsByKind.getValue(SessionActivityKind.PERMISSION))
        assertEquals(2, queue.pendingSessionCount)
    }

    @Test
    fun `queue groups primary kinds into pending running and unread completed`() {
        val question = rootSession("question", updated = 500)
        val permission = rootSession("permission", updated = 400)
        val retry = rootSession("retry", updated = 300)
        val busy = rootSession("busy", updated = 200)
        val unread = rootSession("unread", updated = 100)

        val queue = buildSessionActivityQueue(
            rootSessions = listOf(question, permission, retry, busy, unread),
            statuses = mapOf(
                retry.id to SessionStatus.Retry(1, "retry", 0L),
                busy.id to SessionStatus.Busy,
            ),
            pendingQuestions = mapOf(question.id to listOf(questionAsked("q"))),
            pendingPermissions = mapOf(permission.id to listOf(permissionAsked("p"))),
            unreadSessionIds = setOf(unread.id),
        )

        assertEquals(
            listOf(
                SessionActivityGroupKind.PENDING_ACTION,
                SessionActivityGroupKind.RUNNING,
                SessionActivityGroupKind.UNREAD_COMPLETED,
            ),
            queue.groups.map { it.kind },
        )
        assertEquals(listOf("question", "permission", "retry"), queue.groups[0].items.map { it.sessionId })
        assertEquals(listOf("busy"), queue.groups[1].items.map { it.sessionId })
    }

    @Test
    fun `filtered queue retains total activity counts`() {
        val pending = rootSession("pending", updated = 300)
        val busy = rootSession("busy", updated = 200)
        val unread = rootSession("unread", updated = 100)

        val queue = buildSessionActivityQueue(
            rootSessions = listOf(pending, busy, unread),
            statuses = mapOf(busy.id to SessionStatus.Busy),
            pendingQuestions = mapOf(pending.id to listOf(questionAsked("q"))),
            pendingPermissions = emptyMap(),
            unreadSessionIds = setOf(unread.id),
            filter = SessionActivityFilter.BUSY,
        )

        assertEquals(listOf("busy"), queue.items.map { it.sessionId })
        assertEquals(3, queue.totalSessionCount)
        assertEquals(1, queue.sessionCountsByKind.getValue(SessionActivityKind.QUESTION))
        assertEquals(1, queue.sessionCountsByKind.getValue(SessionActivityKind.BUSY))
        assertEquals(1, queue.sessionCountsByKind.getValue(SessionActivityKind.UNREAD))
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
