package dev.wuxie233.codecarry.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolStateCompactJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun `unknown status falls back to Pending`() {
        val s = json.decodeFromString(
            ToolStateSerializer,
            """{"status":"some-future-state"}"""
        )

        assertTrue(s is ToolState.Pending)
    }

    @Test
    fun `missing status field falls back to Pending`() {
        val s = json.decodeFromString(ToolStateSerializer, """{}""")

        assertTrue(s is ToolState.Pending)
    }

    @Test
    fun `running with no fields decodes successfully`() {
        val s = json.decodeFromString(
            ToolStateSerializer,
            """{"status":"running"}"""
        )

        s as ToolState.Running
        assertEquals("", s.output)
    }

    @Test
    fun `completed with minimum fields decodes successfully`() {
        val s = json.decodeFromString(
            ToolStateSerializer,
            """{"status":"completed","time":{"start":1,"end":2}}"""
        )

        s as ToolState.Completed
        assertEquals("", s.output)
    }

    @Test
    fun `error with minimum fields decodes successfully`() {
        val s = json.decodeFromString(
            ToolStateSerializer,
            """{"status":"error","time":{"start":1,"end":2}}"""
        )

        s as ToolState.Error
        assertEquals("", s.error)
    }
}
