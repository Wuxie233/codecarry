package dev.minios.ocremote.ui.screens.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRowMenuActionsTest {

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
}
