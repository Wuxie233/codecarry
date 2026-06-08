package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.RoundtableSseEvent
import dev.minios.ocremote.data.transport.PiRoundtableEventProcessor
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.Roundtable
import dev.minios.ocremote.domain.transport.PiTransportEvent
import dev.minios.ocremote.domain.transport.TransportEvent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EventReducerPiRoundtableTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `happy one round fixture accumulates per sender messages and moderator synthesis`() {
        val reducer = EventReducer()
        val events = PiRoundtableEventProcessor(json).processSnapshot(fixtureEvents("happy-one-round.json"))

        events.forEach { event -> reducer.processEvent(TransportEvent.Pi(event), serverId = "server-pi") }

        val roundtableId = "round-fixture-001"
        val roundtable = reducer.roundtables.value[roundtableId]!!
        val messages = reducer.roundtableMessages.value[roundtableId].orEmpty().filterIsInstance<Message.Assistant>()
        val textBySender = messages.associate { message ->
            message.senderId!! to reducer.roundtableParts.value[message.id]
                .orEmpty()
                .filterIsInstance<Part.Text>()
                .joinToString(separator = "") { part -> part.text }
        }

        assertEquals(Roundtable.Status.Completed, roundtable.status)
        assertEquals("Should a roundtable optimize for truth seeking or broad coverage?", roundtable.topic)
        assertEquals(1, roundtable.roundCount)
        assertTrue(roundtable.rosterSummary.orEmpty().contains("persona-ada"))
        assertFalse(reducer.sessions.value.any { session -> session.id == roundtableId })
        assertEquals(listOf("turn-ada-001", "turn-curie-001", "turn-moderator-001"), messages.map { it.id })
        assertEquals("Truth seeking should lead because coverage without pressure-testing becomes trivia.", textBySender["persona-ada"])
        assertEquals("Coverage still matters when it maps the disagreement space before depth.", textBySender["persona-curie"])
        assertEquals(
            "The round keeps truth as the goal and coverage as the map.\n\n```mermaid\ngraph TD\n  A[Truth seeking] --> B[Pressure-test claims]\n  C[Coverage] --> D[Map disagreement space]\n  B --> E[Next experiment]\n  D --> E\n```\n",
            textBySender["moderator-main"],
        )

        val ada = messages.first { message -> message.senderId == "persona-ada" }
        assertEquals("Ada", ada.senderName)
        assertEquals("INTJ", ada.mbti)
        assertEquals("persona", ada.senderRole)
        assertEquals("42", ada.colorSeed)
        assertEquals("local-gateway", ada.providerId)
        assertEquals("pi-agent-alpha", ada.modelId)
    }

    @Test
    fun `agent turn start creates live chat placeholder before first delta`() {
        val reducer = EventReducer()
        val events = PiRoundtableEventProcessor(json).processSnapshot(fixtureEvents("happy-one-round.json"))
        val firstTurnIndex = events.indexOfFirst { event -> event is PiTransportEvent.AgentTurnStart }

        events.take(firstTurnIndex + 1).forEach { event -> reducer.processEvent(TransportEvent.Pi(event), serverId = "server-pi") }

        val message = reducer.roundtableMessages.value["round-fixture-001"].orEmpty().single() as Message.Assistant
        val textPart = reducer.roundtableParts.value[message.id].orEmpty().single() as Part.Text

        assertEquals("turn-ada-001", message.id)
        assertEquals(null, message.finish)
        assertEquals("Ada", message.senderName)
        assertEquals("", textPart.text)
    }

    private fun fixtureEvents(name: String): List<RoundtableSseEvent> = json.decodeFromString(
        ListSerializer(RoundtableSseEvent.serializer()),
        fixtureFile(name).readText(),
    )

    private fun fixtureFile(name: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { file -> file.parentFile }
            .map { root -> File(root, "contracts/pi-roundtable/fixtures/$name") }
            .firstOrNull { file -> file.exists() }
            ?: error("Missing Pi roundtable fixture $name from $start")
    }
}
