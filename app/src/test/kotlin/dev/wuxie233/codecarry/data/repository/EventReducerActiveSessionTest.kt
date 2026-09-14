package dev.wuxie233.codecarry.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReducerActiveSessionTest {

    @Test
    fun setActiveSessionIdTracksCurrentVisibleSession() {
        val reducer = EventReducer()

        reducer.setActiveSessionId("ses_visible")

        assertEquals("ses_visible", reducer.activeSessionId.value)
    }

    @Test
    fun clearActiveSessionIdDoesNotClearNewerActiveSession() {
        val reducer = EventReducer()
        reducer.setActiveSessionId("ses_stale")
        reducer.setActiveSessionId("ses_visible")

        reducer.clearActiveSessionId("ses_stale")
        assertEquals("ses_visible", reducer.activeSessionId.value)

        reducer.clearActiveSessionId("ses_visible")
        assertNull(reducer.activeSessionId.value)
    }

    // ============ Server-scoped visible sessions (issues #32/#34) ============

    @Test
    fun visibleSessionIsTrackedPerServerAndSessionPair() {
        val reducer = EventReducer()

        reducer.setVisibleSession("srv-a", "ses_shared")

        assertTrue(reducer.isSessionVisible("srv-a", "ses_shared"))
        // The same session id on another server is not visible.
        assertFalse(reducer.isSessionVisible("srv-b", "ses_shared"))
        // Another session on the same server is not visible.
        assertFalse(reducer.isSessionVisible("srv-a", "ses_other"))
    }

    @Test
    fun clearingVisibleSessionOnlyAffectsItsOwnServer() {
        val reducer = EventReducer()
        reducer.setVisibleSession("srv-a", "ses_shared")
        reducer.setVisibleSession("srv-b", "ses_shared")

        reducer.clearVisibleSession("srv-a", "ses_shared")

        assertFalse(reducer.isSessionVisible("srv-a", "ses_shared"))
        assertTrue(reducer.isSessionVisible("srv-b", "ses_shared"))
    }

    @Test
    fun clearVisibleSessionDoesNotClearNewerVisibleSessionOnSameServer() {
        val reducer = EventReducer()
        reducer.setVisibleSession("srv-a", "ses_stale")
        reducer.setVisibleSession("srv-a", "ses_current")

        reducer.clearVisibleSession("srv-a", "ses_stale")

        assertTrue(reducer.isSessionVisible("srv-a", "ses_current"))
    }

    @Test
    fun visibleSessionMirrorsLegacyActiveSessionIdForDiagnostics() {
        val reducer = EventReducer()

        reducer.setVisibleSession("srv-a", "ses_visible")
        assertEquals("ses_visible", reducer.activeSessionId.value)

        reducer.clearVisibleSession("srv-a", "ses_visible")
        assertNull(reducer.activeSessionId.value)
    }

    @Test
    fun clearForServerRemovesOnlyThatServerVisibleSession() {
        val reducer = EventReducer()
        reducer.setVisibleSession("srv-a", "ses_a")
        reducer.setVisibleSession("srv-b", "ses_b")

        reducer.clearForServer("srv-a")

        assertFalse(reducer.isSessionVisible("srv-a", "ses_a"))
        assertTrue(reducer.isSessionVisible("srv-b", "ses_b"))
    }
}
