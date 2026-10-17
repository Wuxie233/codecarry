package dev.wuxie233.codecarry.ui.screens.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRowMenuActionsTest {

    @Test
    fun `pin and unpin share one menu slot only on eligible active rows`() {
        for (pinned in listOf(false, true)) {
            val actions = sessionRowMenuActions(isArchived = false, pinState = pinned)
            assertEquals(1, actions.count { it == SessionRowMenuAction.TOGGLE_PIN })
        }
        assertEquals(0, sessionRowMenuActions(isArchived = false).count { it == SessionRowMenuAction.TOGGLE_PIN })
        assertEquals(0, sessionRowMenuActions(isArchived = true, pinState = true).count { it == SessionRowMenuAction.TOGGLE_PIN })
    }

    @Test
    fun `active session menu keeps archive and delete actions`() {
        assertEquals(
            listOf(
                SessionRowMenuAction.RENAME,
                SessionRowMenuAction.ARCHIVE,
                SessionRowMenuAction.DELETE,
            ),
            sessionRowMenuActions(isArchived = false),
        )
    }

    @Test
    fun `menu filters unsupported capability actions independently`() {
        assertEquals(
            listOf(SessionRowMenuAction.RENAME, SessionRowMenuAction.DELETE),
            sessionRowMenuActions(
                isArchived = false,
                supportsRename = true,
                supportsArchive = false,
                supportsRestore = false,
                supportsDelete = true,
            ),
        )
        assertEquals(
            listOf(SessionRowMenuAction.RESTORE),
            sessionRowMenuActions(
                isArchived = true,
                supportsRename = false,
                supportsArchive = false,
                supportsRestore = true,
                supportsDelete = false,
            ),
        )
    }

    @Test
    fun `archived session menu swaps in restore without removing delete`() {
        assertEquals(
            listOf(
                SessionRowMenuAction.RENAME,
                SessionRowMenuAction.RESTORE,
                SessionRowMenuAction.DELETE,
            ),
            sessionRowMenuActions(isArchived = true),
        )
    }

    @Test
    fun `dsh session menu includes rehome and hides delete`() {
        assertEquals(
            listOf(
                SessionRowMenuAction.RENAME,
                SessionRowMenuAction.REHOME,
                SessionRowMenuAction.ARCHIVE,
            ),
            sessionRowMenuActions(
                isArchived = false,
                supportsRename = true,
                supportsArchive = true,
                supportsRestore = true,
                supportsDelete = false,
                supportsRehome = true,
            ),
        )
    }
}
