package dev.wuxie233.codecarry.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
