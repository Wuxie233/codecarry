package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionPermissionCompactJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun `decodes pending question with no questions list`() {
        val req = json.decodeFromString(
            QuestionRequest.serializer(),
            """{"id":"q1","sessionID":"s1"}"""
        )
        assertEquals("q1", req.id)
        assertTrue(req.questions.isEmpty())
    }

    @Test
    fun `decodes question info with no options`() {
        val info = json.decodeFromString(
            QuestionInfo.serializer(),
            """{"question":"continue?","header":"H"}"""
        )
        assertEquals("continue?", info.question)
        assertEquals("H", info.header)
        assertTrue(info.options.isEmpty())
        assertFalse(info.multiple)
        assertTrue(info.custom)
    }

    @Test
    fun `decodes compact question info with empty fields`() {
        val info = json.decodeFromString(QuestionInfo.serializer(), """{}""")
        assertEquals("", info.question)
        assertEquals("", info.header)
        assertTrue(info.options.isEmpty())
    }

    @Test
    fun `decodes question option without description`() {
        val opt = json.decodeFromString(
            QuestionOption.serializer(),
            """{"label":"yes"}"""
        )
        assertEquals("yes", opt.label)
        assertEquals("", opt.description)
    }

    @Test
    fun `decodes permission request with no permission field`() {
        val req = json.decodeFromString(
            PermissionRequest.serializer(),
            """{"id":"p1","sessionID":"s1"}"""
        )
        assertEquals("ask", req.permission)
        assertTrue(req.patterns.isEmpty())
    }

    @Test
    fun `full permission request round-trips (regression)`() {
        val req = json.decodeFromString(
            PermissionRequest.serializer(),
            """{"id":"p","sessionID":"s","permission":"allow",
               "patterns":["git/*"],"always":["read"]}"""
        )
        assertEquals("allow", req.permission)
        assertEquals(listOf("git/*"), req.patterns)
        assertEquals(listOf("read"), req.always)
    }

    @Test
    fun `decodes SSE question mirror with compact question`() {
        val question = json.decodeFromString(
            SseEvent.QuestionAsked.Question.serializer(),
            """{}"""
        )
        assertEquals("", question.header)
        assertEquals("", question.question)
        assertTrue(question.options.isEmpty())
    }

    @Test
    fun `decodes SSE question option mirror without description`() {
        val option = json.decodeFromString(
            SseEvent.QuestionAsked.Option.serializer(),
            """{"label":"yes"}"""
        )
        assertEquals("yes", option.label)
        assertEquals("", option.description)
    }
}
