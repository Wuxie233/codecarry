package dev.minios.ocremote.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartCompactJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun `decodes tool part with missing callID tool and state`() {
        val p = json.decodeFromString(
            PartSerializer,
            """{"type":"tool","id":"p1","sessionID":"s","messageID":"m"}"""
        )
        assertTrue(p is Part.Tool)
        p as Part.Tool
        assertEquals("", p.callId)
        assertEquals("", p.tool)
        assertTrue(p.state is ToolState.Pending)
    }

    @Test
    fun `decodes file part with no mime`() {
        val p = json.decodeFromString(
            PartSerializer,
            """{"type":"file","id":"p","sessionID":"s","messageID":"m"}"""
        )
        assertTrue(p is Part.File)
        assertEquals("application/octet-stream", (p as Part.File).mime)
    }

    @Test
    fun `unknown part type falls back to Unknown subtype`() {
        val p = json.decodeFromString(
            PartSerializer,
            """{"type":"some-future-type","id":"p","sessionID":"s","messageID":"m"}"""
        )
        assertTrue(p is Part.Unknown)
    }

    @Test
    fun `parts list with mix of valid and compact entries`() {
        val list = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PartSerializer),
            """[
                {"type":"text","id":"p1","sessionID":"s","messageID":"m","text":"hi"},
                {"type":"tool","id":"p2","sessionID":"s","messageID":"m"},
                {"type":"file","id":"p3","sessionID":"s","messageID":"m"}
            ]"""
        )
        assertEquals(3, list.size)
        assertTrue(list[0] is Part.Text)
        assertTrue(list[1] is Part.Tool)
        assertTrue(list[2] is Part.File)
    }

    @Test
    fun `full tool part round-trips (regression)`() {
        val p = json.decodeFromString(
            PartSerializer,
            """{"type":"tool","id":"p","sessionID":"s","messageID":"m",
               "callID":"call_1","tool":"bash",
               "state":{"status":"completed","input":{},"output":"ok",
                        "time":{"start":1,"end":2}}}"""
        )
        p as Part.Tool
        assertEquals("call_1", p.callId)
        assertEquals("bash", p.tool)
        assertTrue(p.state is ToolState.Completed)
    }
}
