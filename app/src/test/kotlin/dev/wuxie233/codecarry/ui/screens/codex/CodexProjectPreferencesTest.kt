package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexThreadStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.wuxie233.codecarry.data.preferences.SessionListViewMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class CodexProjectPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `project preferences persist per server and can unhide the same directory`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { temporaryFolder.newFile("projects.preferences_pb") })
        val repository = CodexProjectPreferencesRepository(store)
        repository.toggle("one", "hidden", "/same/path")
        repository.toggle("one", "pinned", "/same/path")
        repository.setViewMode("one", SessionListViewMode.ACTIVITY)
        assertEquals(CodexProjectPreferences(), repository.observe("two").first())
        val recreated = CodexProjectPreferencesRepository(store)
        assertEquals(setOf("/same/path"), recreated.observe("one").first().hidden)
        assertEquals(SessionListViewMode.ACTIVITY, recreated.observe("one").first().viewMode)
        recreated.toggle("one", "hidden", "/same/path")
        assertTrue(repository.observe("one").first().hidden.isEmpty())
        assertEquals(setOf("/same/path"), repository.observe("one").first().pinned)
    }

    private val old = CodexThread(id = "old", cwd = "/projects/a", updatedAt = 1)
    private val recent = CodexThread(id = "recent", cwd = "/projects/b", updatedAt = 2)

    @Test fun `recent directories and create command preserve significant path whitespace`() {
        val paths = listOf("/tmp/repo ", "/tmp/repo", "C:\\work\\folder ")
        val state = CodexThreadListUiState(activeThreads = paths.mapIndexed { index, path ->
            CodexThread(id = "thread-$index", cwd = path, updatedAt = (10 - index).toLong())
        } + CodexThread(id = "default", cwd = ""))
        assertEquals(paths, state.recentDirectories)
        state.recentDirectories.forEach { path -> assertEquals(path, codexThreadWorkingDirectory(path)) }
        assertNull(codexThreadWorkingDirectory(""))
    }

    @Test fun `pinned project precedes newer project without losing members`() {
        val state = CodexThreadListUiState(activeThreads = listOf(recent, old, old.copy(id = "sibling")),
            projectPreferences = CodexProjectPreferences(pinned = setOf("/projects/a")))
        assertEquals(listOf("/projects/a", "/projects/b"), state.projects.map { it.directory })
        assertEquals(setOf("old", "sibling"), state.projects.first().threads.map { it.id }.toSet())
    }

    @Test fun `hidden project remains recoverable and search opens collapsed matches`() {
        val state = CodexThreadListUiState(activeThreads = listOf(old, recent),
            projectPreferences = CodexProjectPreferences(hidden = setOf("/projects/a"), collapsed = setOf("/projects/b")))
        assertEquals(listOf("/projects/b"), state.projects.map { it.directory })
        assertTrue(state.projects.single().collapsed)
        assertEquals(2, state.copy(showHiddenProjects = true).projects.size)
        assertFalse(state.copy(searchQuery = "projects/b").projects.single().collapsed)
        assertTrue(state.copy(showHiddenProjects = true).projects.first { it.directory == "/projects/a" }.hidden)
    }

    @Test fun `activity excludes archived and idle but includes requests and errors`() {
        val state = CodexThreadListUiState(showArchived = true,
            activeThreads = listOf(old, recent.copy(status = CodexThreadStatus(type = "active")),
                old.copy(id = "pending"), old.copy(id = "error", status = CodexThreadStatus(type = "systemError"))),
            archivedThreads = listOf(old.copy(id = "archived", status = CodexThreadStatus(type = "active"))),
            pendingRequestCounts = mapOf("pending" to 1))
        assertEquals(setOf("recent", "pending", "error"), state.activityThreads.map { it.id }.toSet())
        assertEquals(listOf("pending"), state.copy(filter = CodexThreadFilter.PENDING).activityThreads.map { it.id })
    }

    @Test fun `same basename in different paths remains distinct and archive scope stays separate`() {
        val state = CodexThreadListUiState(activeThreads = listOf(old, recent.copy(cwd = "/elsewhere/a")),
            archivedThreads = listOf(old.copy(id = "archived")))
        assertEquals(2, state.projects.size)
        assertEquals(listOf("archived"), state.copy(showArchived = true).projects.single().threads.map { it.id })
    }
}
