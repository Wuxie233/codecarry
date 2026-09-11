package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexThreadStatus
import dev.wuxie233.codecarry.data.codex.CodexServerRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.wuxie233.codecarry.data.codex.CodexEventState
import dev.wuxie233.codecarry.data.codex.CodexThreadListPage
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
    fun `pagination loads every page and deduplicates threads`() = runTest {
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
                nextCursor = null,
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
    fun `cursor cycle fails instead of publishing an incomplete catalog`() = runTest {
        val result = runCatching {
            loadAllCodexThreads { CodexThreadListPage(listOf(active), nextCursor = "cycle") }
        }
        org.junit.Assert.assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `connection reset and event only updates preserve loaded catalog and archives`() {
        val reducer = dev.wuxie233.codecarry.data.codex.CodexEventReducer(listOf(active, archived))
        val before = reducer.state.value.copy(archivedThreadIds = setOf(archived.id))
        val ui = CodexThreadListUiState(activeThreads = listOf(active), archivedThreads = listOf(archived))
        reducer.clear()
        val reset = reducer.state.value
        val retained = ui.applyCodexEventState(before, reset)
        assertEquals(ui.activeThreads, retained.activeThreads)
        assertEquals(ui.archivedThreads, retained.archivedThreads)
        val events = reset.copy(threads = mapOf(active.id to CodexThread(active.id, hasMetadata = false)))
        val after = retained.applyCodexEventState(reset, events)
        assertEquals(ui.activeThreads, after.activeThreads)
        assertEquals(ui.archivedThreads, after.archivedThreads)
        // StateFlow may conflate away the empty reset frame.
        assertEquals(after, ui.applyCodexEventState(before, events))
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
    @Test
    fun `status filter combines with search and archive scope`() {
        val running = active.copy(status = CodexThreadStatus(type = "active"))
        val state = CodexThreadListUiState(
            activeThreads = listOf(running, archived),
            archivedThreads = listOf(archived),
            pendingRequestCounts = mapOf(running.id to 2),
        )
        assertEquals(listOf(running.id), state.copy(filter = CodexThreadFilter.RUNNING).visibleThreads.map { it.id })
        assertEquals(listOf(running.id), state.copy(filter = CodexThreadFilter.PENDING).visibleThreads.map { it.id })
        assertEquals(emptyList<CodexThread>(), state.copy(filter = CodexThreadFilter.PENDING, searchQuery = "docs").visibleThreads)
        assertEquals(emptyList<CodexThread>(), state.copy(filter = CodexThreadFilter.PENDING, showArchived = true).visibleThreads)
    }

    @Test
    fun `pending is derived from server requests and clears on resolution`() {
        fun request(id: Int, threadId: String?) = CodexServerRequest(
            id = JsonPrimitive(id), method = "item/commandExecution/requestApproval",
            params = JsonObject(threadId?.let { mapOf("threadId" to JsonPrimitive(it)) }.orEmpty()),
        )
        val requests = listOf(request(1, active.id), request(2, active.id), request(3, null))
        val state = CodexThreadListUiState(
            activeThreads = listOf(active.copy(status = CodexThreadStatus(type = "active", activeFlags = listOf("waitingOnApproval")))),
            filter = CodexThreadFilter.PENDING,
            pendingRequestCounts = codexPendingRequestCounts(requests),
        )
        assertEquals(mapOf(active.id to 2), state.pendingRequestCounts)
        assertEquals(1, state.visibleThreads.size)
        assertEquals(emptyList<CodexThread>(), state.copy(pendingRequestCounts = codexPendingRequestCounts(emptyList())).visibleThreads)
    }

    @Test
    fun `live state updates running filter without changing selected controls`() {
        val state = CodexThreadListUiState(activeThreads = listOf(active), filter = CodexThreadFilter.RUNNING, searchQuery = "mobile")
        val running = active.copy(status = CodexThreadStatus(type = "active"))
        val event = CodexEventState(threads = mapOf(running.id to running))
        val updated = state.applyCodexEventState(null, event)
        assertEquals(listOf(active.id), updated.visibleThreads.map { it.id })
        assertEquals("mobile", updated.searchQuery)
        assertEquals(emptyList<CodexThread>(), updated.applyCodexEventState(event, CodexEventState(threads = mapOf(active.id to active))).visibleThreads)
    }

    @Test
    fun `recent directory choices include archived paths deduplicate and sort by recency`() {
        val state = CodexThreadListUiState(
            activeThreads = listOf(active, active.copy(id = "duplicate", updatedAt = 300), active.copy(id = "none", cwd = "")),
            archivedThreads = listOf(archived.copy(recencyAt = 400)),
            searchQuery = "no-match",
        )
        assertEquals(listOf("/work/docs", "/work/mobile"), state.recentDirectories)
        assertEquals(emptyList<String>(), CodexThreadListUiState().recentDirectories)
    }

    @Test
    fun `empty filtered lists offer constraint reset instead of first session creation`() {
        assertEquals(true, CodexThreadListUiState(filter = CodexThreadFilter.RUNNING).hasListConstraints)
        assertEquals(true, CodexThreadListUiState(filter = CodexThreadFilter.PENDING, showArchived = true).hasListConstraints)
        assertEquals(true, CodexThreadListUiState(searchQuery = "project").hasListConstraints)
        assertEquals(false, CodexThreadListUiState(searchQuery = " ").hasListConstraints)
    }

    @Test
    fun `running includes active turns paused for a real request and never infers pending from flags`() {
        val thread = active.copy(status = CodexThreadStatus(type = "active", activeFlags = listOf("waitingOnUserInput")))
        val state = CodexThreadListUiState(activeThreads = listOf(thread))
        assertEquals(listOf(thread), state.copy(filter = CodexThreadFilter.RUNNING).visibleThreads)
        assertEquals(emptyList<CodexThread>(), state.copy(filter = CodexThreadFilter.PENDING).visibleThreads)
    }

    @Test
    fun `first page is published before later pages are requested`() = runTest {
        val published = mutableListOf<String>()
        val result = loadAllCodexThreads(onPage = { page ->
            published += page.threads.map { it.id }
        }) { cursor ->
            if (cursor == null) CodexThreadListPage(listOf(active), "older")
            else {
                assertEquals(listOf(active.id), published)
                CodexThreadListPage(listOf(archived))
            }
        }
        assertEquals(listOf(active.id, archived.id), result.map { it.id })
    }

    @Test
    fun `later page failure retains published first page`() = runTest {
        val published = mutableListOf<String>()
        val result = runCatching {
            loadAllCodexThreads(onPage = { page -> published += page.threads.map { it.id } }) { cursor ->
                if (cursor == null) CodexThreadListPage(listOf(active), "older") else error("offline")
            }
        }
        org.junit.Assert.assertTrue(result.isFailure)
        assertEquals(listOf(active.id), published)
    }

    @Test
    fun `catalog projection ignores token deltas but retains status and ancestry`() {
        val turn = dev.wuxie233.codecarry.data.codex.CodexTurn(id = "turn", status = "inProgress")
        val thread = active.copy(parentThreadId = "parent", turns = listOf(turn))
        val before = CodexEventState(threads = mapOf(thread.id to thread))
        val streamed = thread.copy(turns = listOf(turn.copy(items = listOf(
            dev.wuxie233.codecarry.data.codex.CodexThreadItem(id = "item", type = "agentMessage", text = "new token"),
        ))))
        val after = before.copy(threads = mapOf(thread.id to streamed))
        assertEquals(before.toCodexCatalogState(), after.toCodexCatalogState())
        val failed = after.copy(threads = mapOf(thread.id to streamed.copy(turns = listOf(turn.copy(status = "failed")))))
        org.junit.Assert.assertNotEquals(after.toCodexCatalogState(), failed.toCodexCatalogState())
        assertEquals("parent", failed.toCodexCatalogState().threads[thread.id]?.parentThreadId)
        assertEquals(1, CodexThreadListUiState(activeThreads = failed.toCodexCatalogState().threads.values.toList()).topology.single().failedCount)
    }

    @Test
    fun `database fallback only recognizes unsupported option errors`() {
        assertEquals(true, codexStateDbUnsupported(dev.wuxie233.codecarry.data.codex.CodexRpcException(-32602L, "unknown field useStateDbOnly")))
        assertEquals(false, codexStateDbUnsupported(dev.wuxie233.codecarry.data.codex.CodexRpcException(-32602L, "invalid cursor")))
        assertEquals(false, codexStateDbUnsupported(dev.wuxie233.codecarry.data.codex.CodexRpcException(-32000L, "useStateDbOnly database unavailable")))
    }

}
