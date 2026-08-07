package dev.wuxie233.codecarry.service

import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.domain.model.ConnectionPhase
import dev.wuxie233.codecarry.ui.screens.home.messageRes
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionProgressTest {

    @Test
    fun `each connection phase exposes a user visible status message`() {
        val expectedMessages = mapOf(
            ConnectionPhase.CheckingServer to R.string.home_connection_checking_server,
            ConnectionPhase.LoadingWorkspace to R.string.home_connection_loading_workspace,
            ConnectionPhase.SyncingSessions to R.string.home_connection_syncing_sessions,
            ConnectionPhase.RestoringActivity to R.string.home_connection_restoring_activity,
            ConnectionPhase.OpeningLiveUpdates to R.string.home_connection_opening_live_updates,
            ConnectionPhase.WaitingToRetry to R.string.home_connection_waiting_to_retry,
        )

        assertEquals(expectedMessages.keys, ConnectionPhase.entries.toSet())
        expectedMessages.forEach { (phase, message) ->
            assertEquals(message, phase.messageRes)
        }
    }
}
