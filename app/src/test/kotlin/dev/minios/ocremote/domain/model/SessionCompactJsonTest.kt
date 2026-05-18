package dev.minios.ocremote.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCompactJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun `decodes session with only id field`() {
        val s = json.decodeFromString(Session.serializer(), """{"id":"ses_new"}""")
        assertEquals("ses_new", s.id)
        assertEquals(0L, s.time.created)
        assertEquals(0L, s.time.updated)
        assertNull(s.share)
        assertNull(s.revert)
    }

    @Test
    fun `decodes session with empty share object (missing url)`() {
        val s = json.decodeFromString(
            Session.serializer(),
            """{"id":"s","time":{"created":1,"updated":2},"share":{}}"""
        )
        assertNotNull(s.share)
        assertEquals("", s.share!!.url)
    }

    @Test
    fun `decodes revert with no messageID`() {
        val s = json.decodeFromString(
            Session.serializer(),
            """{"id":"s","time":{"created":1,"updated":2},"revert":{}}"""
        )
        assertEquals("", s.revert!!.messageId)
    }

    @Test
    fun `decodes permission rule with no permission field defaults to ask`() {
        val s = json.decodeFromString(
            Session.serializer(),
            """{"id":"s","time":{"created":1,"updated":2},"permission":[{"pattern":"*"}]}"""
        )
        assertEquals(1, s.permission!!.size)
        assertEquals("ask", s.permission!![0].permission)
    }

    @Test
    fun `full response still round-trips fields correctly (regression)`() {
        val s = json.decodeFromString(
            Session.serializer(),
            """{"id":"s","slug":"sl","projectID":"p","directory":"/d","parentID":"par",
               "title":"T","version":"v1","time":{"created":10,"updated":20,"archived":30},
               "share":{"url":"https://x"},"revert":{"messageID":"m1","snapshot":"sn"},
               "permission":[{"permission":"allow","pattern":"git/*","action":"once"}]}"""
        )
        assertEquals("s", s.id)
        assertEquals("p", s.projectId)
        assertEquals("par", s.parentId)
        assertEquals("https://x", s.share!!.url)
        assertEquals("m1", s.revert!!.messageId)
        assertEquals("allow", s.permission!![0].permission)
        assertEquals("once", s.permission!![0].action)
    }
}
