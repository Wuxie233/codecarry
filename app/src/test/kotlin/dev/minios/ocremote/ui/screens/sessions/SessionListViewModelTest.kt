package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.preferences.SessionFilter
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListViewModelTest {

    @Test
    fun archivedSessionsOnlyMatchArchivedFilter() {
        val item = SessionItem(session = testSession(id = "archived", archived = 1_000L), status = SessionStatus.Idle)

        assertFalse(matchesSessionFilter(item, SessionFilter.ALL))
        assertTrue(matchesSessionFilter(item, SessionFilter.ARCHIVED))
    }

    @Test
    fun restoredSessionsReturnToActiveFiltersWhenArchivedTimeClears() {
        val item = SessionItem(session = testSession(id = "restored", archived = 0L), status = SessionStatus.Idle)

        assertTrue(matchesSessionFilter(item, SessionFilter.ALL))
        assertFalse(matchesSessionFilter(item, SessionFilter.ARCHIVED))
    }

    @Test
    fun archiveableRootSessionIdsOnlyIncludeActiveRootsInDirectory() {
        val ids = archiveableRootSessionIds(
            sessions = listOf(
                testSession(id = "root-active", directory = "/workspace/project", archived = null),
                testSession(id = "root-archived", directory = "/workspace/project", archived = 1_000L),
                testSession(id = "child-active", directory = "/workspace/project", parentId = "root-active", archived = null),
                testSession(id = "other-root", directory = "/workspace/other", archived = null),
            ),
            directory = "/workspace/project",
            normalizeDirectory = { it.trimEnd('/').ifEmpty { "/" } },
        )

        assertEquals(listOf("root-active"), ids)
    }

    @Test
    fun `deriveAllDirectories includes pinned empty directory`() {
        val directories = deriveAllDirectories(
            projectDirectories = emptyList(),
            rootSessionDirectories = emptyList(),
            pinnedDirectories = listOf("/workspace/empty-project"),
        )

        assertEquals(listOf("/workspace/empty-project"), directories)
    }

    @Test
    fun `deriveAllDirectories deduplicates pinned and active directories`() {
        val directories = deriveAllDirectories(
            projectDirectories = listOf("/workspace/project-a"),
            rootSessionDirectories = listOf("/workspace/project-b", "/workspace/project-a"),
            pinnedDirectories = listOf("/workspace/project-b", "/workspace/project-c"),
        )

        assertEquals(
            listOf("/workspace/project-a", "/workspace/project-b", "/workspace/project-c"),
            directories,
        )
    }

    @Test
    fun `pinned empty project group stays visible`() {
        val pinnedEmptyGroup = ProjectGroup(
            directory = "/workspace/empty-project",
            projectName = "empty-project",
            tildeDirectory = "~/empty-project",
            isPinned = true,
            isCollapsed = false,
            isHidden = false,
            sessionCount = 0,
            activeCount = 0,
            additionsSum = 0,
            deletionsSum = 0,
            sessions = emptyList(),
            subagentRowsByParent = emptyMap(),
        )
        val unpinnedEmptyGroup = pinnedEmptyGroup.copy(isPinned = false, directory = "/workspace/unpinned")

        assertTrue(isProjectGroupVisible(pinnedEmptyGroup, showHiddenProjects = false))
        assertFalse(isProjectGroupVisible(unpinnedEmptyGroup, showHiddenProjects = false))
    }

    private fun testSession(
        id: String,
        directory: String = "/workspace/project",
        parentId: String? = null,
        archived: Long? = null,
    ) = Session(
        id = id,
        directory = directory,
        parentId = parentId,
        time = Session.Time(
            created = 1L,
            updated = 1L,
            archived = archived,
        ),
    )
}
