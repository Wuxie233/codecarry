package dev.minios.ocremote.data.repository

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
    fun clearActiveSessionIdOnlyClearsMatchingSession() {
        val reducer = EventReducer()
        reducer.setActiveSessionId("ses_visible")

        reducer.clearActiveSessionId("other")
        assertEquals("ses_visible", reducer.activeSessionId.value)

        reducer.clearActiveSessionId("ses_visible")
        assertNull(reducer.activeSessionId.value)
    }
}
