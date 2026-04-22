package dev.minios.ocremote.ui.screens.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListViewModelTest {

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
}
