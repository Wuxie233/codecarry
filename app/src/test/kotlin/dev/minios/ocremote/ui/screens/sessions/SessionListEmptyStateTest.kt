package dev.minios.ocremote.ui.screens.sessions

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
}
