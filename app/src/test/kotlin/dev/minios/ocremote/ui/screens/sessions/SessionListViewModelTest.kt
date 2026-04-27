package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.preferences.SessionFilter
import dev.minios.ocremote.data.preferences.SessionScope
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListViewModelTest {

    @Test
    fun archivedSessionsAreHiddenInInboxScope() {
        val item = SessionItem(session = testSession(id = "archived", archived = 1_000L), status = SessionStatus.Idle)

        assertFalse(matchesScopeAndFilter(item, SessionScope.INBOX, SessionFilter.ALL))
        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.ALL))
    }

    @Test
    fun activeSessionsAreHiddenInArchivedScope() {
        val item = SessionItem(session = testSession(id = "active", archived = null), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(item, SessionScope.INBOX, SessionFilter.ALL))
        assertFalse(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.ALL))
    }

    @Test
    fun statusFilterIsIgnoredInArchivedScope() {
        // An archived idle session would not match WORKING in inbox,
        // but in Archived scope status filters are forced to ALL.
        val item = SessionItem(session = testSession(id = "a", archived = 1_000L), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.WORKING))
        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.HAS_ERRORS))
    }

    @Test
    fun inboxScopeStillRespectsStatusFilter() {
        val idleActive = SessionItem(session = testSession(id = "idle", archived = null), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(idleActive, SessionScope.INBOX, SessionFilter.ALL))
        assertFalse(matchesScopeAndFilter(idleActive, SessionScope.INBOX, SessionFilter.WORKING))
    }

    @Test
    fun matchesScopeReportsArchivedFlagDirectly() {
        val active = SessionItem(session = testSession(id = "a", archived = null), status = SessionStatus.Idle)
        val archived = SessionItem(session = testSession(id = "b", archived = 1L), status = SessionStatus.Idle)

        assertTrue(matchesScope(active, SessionScope.INBOX))
        assertFalse(matchesScope(active, SessionScope.ARCHIVED))
        assertFalse(matchesScope(archived, SessionScope.INBOX))
        assertTrue(matchesScope(archived, SessionScope.ARCHIVED))
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

    @Test
    fun `pinDirectoryRefreshTargets keeps session refresh explicit after duplicate pin attempts`() {
        assertEquals(
            setOf(PinDirectoryRefreshTarget.PROJECTS, PinDirectoryRefreshTarget.SESSIONS),
            pinDirectoryRefreshTargets(changed = true),
        )
        assertEquals(
            setOf(PinDirectoryRefreshTarget.SESSIONS),
            pinDirectoryRefreshTargets(changed = false),
        )
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
