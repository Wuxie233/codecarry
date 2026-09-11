package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CodexCatalogPageMergeTest {
    @Test fun partialPagePreservesOtherThreadsAndHistory() {
        val history = listOf(CodexTurn(id = "turn", status = "completed"))
        val reducer = CodexEventReducer(listOf(CodexThread(id = "a", turns = history), CodexThread(id = "b")))
        reducer.mergeThreadPage(listOf(CodexThread(id = "a", name = "Updated")), false, reducer.state.value)
        assertEquals(setOf("a", "b"), reducer.state.value.threads.keys)
        assertEquals(history, reducer.state.value.threads.getValue("a").turns)
        assertEquals("Updated", reducer.state.value.threads.getValue("a").name)
    }

    @Test fun latePageCannotUndoDeleteArchiveOrRename() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "deleted"), CodexThread(id = "archived")))
        val baseline = reducer.state.value
        reducer.removeThread("deleted")
        reducer.process(CodexNotification("thread/archived", JsonObject(mapOf("threadId" to JsonPrimitive("archived")))))
        reducer.process(CodexNotification("thread/name/updated", JsonObject(mapOf(
            "threadId" to JsonPrimitive("archived"), "threadName" to JsonPrimitive("Live name"),
        ))))
        reducer.mergeThreadPage(listOf(CodexThread(id = "deleted"), CodexThread(id = "archived", name = "Old name")), false, baseline)
        assertFalse("deleted" in reducer.state.value.threads)
        assertTrue("archived" in reducer.state.value.archivedThreadIds)
        assertEquals("Live name", reducer.state.value.threads.getValue("archived").name)
    }

    @Test fun archivedPagesRemainNonDestructive() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "active")))
        reducer.mergeThreadPage(listOf(CodexThread(id = "old")), true, reducer.state.value)
        assertEquals(setOf("active", "old"), reducer.state.value.threads.keys)
        assertEquals(setOf("old"), reducer.state.value.archivedThreadIds)
    }
}
