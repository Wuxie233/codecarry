package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListEmptyStateTest {

    @Test
    fun `no root sessions means truly empty (onboarding state)`() {
        val state = computeSessionListEmptyState(rootSessionCount = 0, visibleGroupCount = 0)

        assertFalse("hasAnySessions must be false when there are no root sessions", state.hasAnySessions)
        assertFalse("isFilteredEmpty must be false when the server has no sessions at all", state.isFilteredEmpty)
    }

    @Test
    fun `root sessions exist but filter hides everything means filtered-empty`() {
        val state = computeSessionListEmptyState(rootSessionCount = 3, visibleGroupCount = 0)

        assertTrue("hasAnySessions must be true when root sessions exist", state.hasAnySessions)
        assertTrue("isFilteredEmpty must be true when groups are empty despite having sessions", state.isFilteredEmpty)
    }

    @Test
    fun `root sessions exist and groups are visible means normal list (neither flag triggers empty UI)`() {
        val state = computeSessionListEmptyState(rootSessionCount = 5, visibleGroupCount = 2)

        assertTrue(state.hasAnySessions)
        assertFalse("isFilteredEmpty must be false when at least one group is visible", state.isFilteredEmpty)
    }

    @Test
    fun `partitionSubagentsByActivity puts busy and retry into running bucket and idle into historical`() {
        val busy = item("busy", SessionStatus.Busy)
        val retry = item("retry", SessionStatus.Retry(attempt = 1, message = "retrying", next = 0L))
        val idle1 = item("idle1", SessionStatus.Idle)
        val idle2 = item("idle2", SessionStatus.Idle)

        val row = partitionSubagentsByActivity(listOf(busy, retry, idle1, idle2))

        assertEquals(listOf("busy", "retry"), row.running.map { it.session.id })
        assertEquals(listOf("idle1", "idle2"), row.historical.map { it.session.id })
        assertEquals(4, row.total)
    }

    @Test
    fun `partitionSubagentsByActivity preserves caller ordering within each bucket`() {
        val a = item("a", SessionStatus.Idle)
        val b = item("b", SessionStatus.Busy)
        val c = item("c", SessionStatus.Idle)
        val d = item("d", SessionStatus.Busy)

        val row = partitionSubagentsByActivity(listOf(a, b, c, d))

        assertEquals(listOf("b", "d"), row.running.map { it.session.id })
        assertEquals(listOf("a", "c"), row.historical.map { it.session.id })
    }

    @Test
    fun `partitionSubagentsByActivity returns EMPTY for empty input`() {
        val row = partitionSubagentsByActivity(emptyList())

        assertTrue(row.running.isEmpty())
        assertTrue(row.historical.isEmpty())
    }

    private fun item(id: String, status: SessionStatus): SessionItem = SessionItem(
        session = Session(
            id = id,
            slug = id,
            projectId = "p",
            directory = "/root/CODE/demo",
            parentId = "parent",
            title = id,
            version = "1.0.0",
            time = Session.Time(created = 0L, updated = 0L, archived = null),
        ),
        status = status,
    )
}
