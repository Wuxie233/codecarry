package dev.minios.ocremote.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCompactJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun `decodes user message with no time`() {
        val m = json.decodeFromString(
            MessageSerializer,
            """{"role":"user","id":"m1","sessionID":"s1"}"""
        )
        assertTrue(m is Message.User)
        assertEquals(0L, m.time.created)
    }

    @Test
    fun `decodes assistant message with no parentID`() {
        val m = json.decodeFromString(
            MessageSerializer,
            """{"role":"assistant","id":"m2","sessionID":"s1"}"""
        )
        assertTrue(m is Message.Assistant)
        assertNull((m as Message.Assistant).parentId)
        assertEquals(0L, m.time.created)
    }

    @Test
    fun `decodes message list mixing user and assistant with missing time`() {
        val list = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(MessageSerializer),
            """[
                {"role":"user","id":"u1","sessionID":"s"},
                {"role":"assistant","id":"a1","sessionID":"s","parentID":"u1"}
            ]"""
        )
        assertEquals(2, list.size)
        assertEquals("u1", list[0].id)
        assertEquals("u1", (list[1] as Message.Assistant).parentId)
    }

    @Test
    fun `full message round-trips (regression)`() {
        val m = json.decodeFromString(
            MessageSerializer,
            """{"role":"assistant","id":"a","sessionID":"s","parentID":"u",
               "time":{"created":1,"completed":2},"modelID":"gpt","providerID":"openai",
               "tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}}"""
        )
        m as Message.Assistant
        assertEquals("u", m.parentId)
        assertEquals(1L, m.time.created)
        assertEquals("gpt", m.modelId)
    }
}
