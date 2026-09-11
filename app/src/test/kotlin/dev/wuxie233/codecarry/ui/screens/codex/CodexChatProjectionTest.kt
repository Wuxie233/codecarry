package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexEventState
import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexTurn
import org.junit.Assert.*
import org.junit.Test

class CodexChatProjectionTest {
    @Test
    fun unrelatedStreamDoesNotInvalidateChatProjection() {
        val current = CodexThread(id = "current")
        val state = CodexEventState(threads = mapOf(current.id to current))
        val other = CodexThread(id = "other", turns = listOf(CodexTurn(id = "turn", status = "inProgress")))
        assertTrue(sameCodexChatProjection(state, state.copy(threads = state.threads + (other.id to other)), current.id))
        assertFalse(sameCodexChatProjection(state, state.copy(threads = mapOf(current.id to current.copy(name = "renamed"))), current.id))
        assertFalse(sameCodexChatProjection(state, state.copy(resetGeneration = 1), current.id))
        assertFalse(sameCodexChatProjection(state, state.copy(knownGoalThreadIds = setOf(current.id)), current.id))
    }

    @Test
    fun familyKeepsIdentityForItemDeltasButReflectsNewAncestry() {
        val projection = CodexChatFamilyProjection()
        val parent = CodexThread(id = "parent")
        val child = CodexThread(id = "child", parentThreadId = parent.id)
        val threads = mapOf(parent.id to parent, child.id to child)
        val first = projection.project(threads, child.id)
        val streamed = child.copy(turns = listOf(CodexTurn(id = "turn", status = "inProgress")))
        assertSame(first, projection.project(threads + (child.id to streamed), child.id))
        val sibling = CodexThread(id = "sibling", parentThreadId = parent.id)
        assertEquals(setOf("parent", "child", "sibling"), projection.project(threads + (sibling.id to sibling), child.id).map { it.id }.toSet())
        assertEquals(listOf("child"), projection.project(mapOf(child.id to child), child.id).map { it.id })
    }
}
