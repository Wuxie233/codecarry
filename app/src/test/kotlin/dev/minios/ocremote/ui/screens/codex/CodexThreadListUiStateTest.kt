package dev.minios.ocremote.ui.screens.codex

import dev.minios.ocremote.data.codex.CodexThread
import dev.minios.ocremote.data.codex.CodexEventState
import dev.minios.ocremote.data.codex.CodexThreadListPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexThreadListUiStateTest {
    private val active = CodexThread(
        id = "active-1",
        name = "Fix login",
        preview = "Investigate authentication",
        cwd = "/work/mobile",
        updatedAt = 100,
    )
    private val archived = CodexThread(
        id = "archived-1",
        name = "Release notes",
        preview = "Prepare changelog",
        cwd = "/work/docs",
        updatedAt = 200,
    )

    @Test
    fun `active and archived modes use their independent result sets`() {
        val state = CodexThreadListUiState(
            activeThreads = listOf(active),
            archivedThreads = listOf(archived),
        )

        assertEquals(listOf("active-1"), state.visibleThreads.map(CodexThread::id))
        assertEquals(
            listOf("archived-1"),
            state.copy(showArchived = true).visibleThreads.map(CodexThread::id),
        )
    }

    @Test
    fun `search matches name preview and cwd without losing wire timestamps`() {
        val state = CodexThreadListUiState(
            activeThreads = listOf(active, archived),
            searchQuery = "DOCS",
        )

        val result = state.visibleThreads.single()
        assertEquals("archived-1", result.id)
        assertEquals("/work/docs", result.cwd)
        assertEquals(200L, result.updatedAt)
    }

    @Test
    fun `pagination loads every page deduplicates threads and stops cursor cycles`() = runTest {
        val requestedCursors = mutableListOf<String?>()
        val pages = mapOf(
            null to CodexThreadListPage(
                threads = listOf(active, archived),
                nextCursor = "page-2",
            ),
            "page-2" to CodexThreadListPage(
                threads = listOf(archived.copy(name = "Updated release notes"), CodexThread(id = "third")),
                nextCursor = "page-3",
            ),
            "page-3" to CodexThreadListPage(
                threads = listOf(CodexThread(id = "fourth")),
                nextCursor = "page-2",
            ),
        )

        val result = loadAllCodexThreads { cursor ->
            requestedCursors += cursor
            checkNotNull(pages[cursor])
        }

        assertEquals(listOf(null, "page-2", "page-3"), requestedCursors)
        assertEquals(listOf("active-1", "archived-1", "third", "fourth"), result.map(CodexThread::id))
        assertEquals("Updated release notes", result[1].name)
    }

    @Test
    fun `reducer events add migrate and delete list members`() {
        val initialEvents = CodexEventState(
            threads = mapOf(active.id to active, archived.id to archived),
        )
        val initialState = CodexThreadListUiState(
            activeThreads = listOf(active),
            archivedThreads = listOf(archived),
        )
        val started = CodexThread(id = "started", name = "External thread")
        val afterStartedEvents = initialEvents.copy(
            threads = initialEvents.threads + (started.id to started),
        )

        val afterStarted = initialState.applyCodexEventState(initialEvents, afterStartedEvents)
        assertEquals(listOf("active-1", "started"), afterStarted.activeThreads.map(CodexThread::id))

        val afterArchivedEvents = afterStartedEvents.copy(archivedThreadIds = setOf(started.id))
        val afterArchived = afterStarted.applyCodexEventState(afterStartedEvents, afterArchivedEvents)
        assertEquals(listOf("active-1"), afterArchived.activeThreads.map(CodexThread::id))
        assertEquals(listOf("archived-1", "started"), afterArchived.archivedThreads.map(CodexThread::id))

        val afterUnarchivedEvents = afterArchivedEvents.copy(archivedThreadIds = emptySet())
        val afterUnarchived = afterArchived.applyCodexEventState(afterArchivedEvents, afterUnarchivedEvents)
        assertEquals(listOf("active-1", "started"), afterUnarchived.activeThreads.map(CodexThread::id))
        assertEquals(listOf("archived-1"), afterUnarchived.archivedThreads.map(CodexThread::id))

        val afterDeletedEvents = afterUnarchivedEvents.copy(
            threads = afterUnarchivedEvents.threads - started.id,
        )
        val afterDeleted = afterUnarchived.applyCodexEventState(afterUnarchivedEvents, afterDeletedEvents)
        assertEquals(listOf("active-1"), afterDeleted.activeThreads.map(CodexThread::id))
        assertEquals(listOf("archived-1"), afterDeleted.archivedThreads.map(CodexThread::id))
    }

    @Test
    fun `search covers threads loaded from later pages`() = runTest {
        val pages = mapOf(
            null to CodexThreadListPage(listOf(active), nextCursor = "older"),
            "older" to CodexThreadListPage(listOf(archived), nextCursor = null),
        )
        val loaded = loadAllCodexThreads { cursor -> checkNotNull(pages[cursor]) }

        val result = CodexThreadListUiState(
            activeThreads = loaded,
            searchQuery = "changelog",
        ).visibleThreads

        assertEquals(listOf("archived-1"), result.map(CodexThread::id))
    }
}
