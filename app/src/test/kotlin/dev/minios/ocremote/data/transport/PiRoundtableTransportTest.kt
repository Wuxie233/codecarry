package dev.minios.ocremote.data.transport

import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.RoundtableSseEvent
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.ServerType
import dev.minios.ocremote.domain.transport.PiTransportEvent
import dev.minios.ocremote.domain.transport.TransportEvent
import dev.minios.ocremote.domain.transport.TransportMessagePart
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PiTransportTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `happy fixture reassembles canonical messages`() = runTest {
        val outcome = parseFixtureOutcome("happy-one-round.json")

        assertCanonicalOutcome(outcome)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L), outcome.eventIds)
        assertEquals("completed", outcome.roundEndReason)
        assertTrue(outcome.messageIntegrity.all { it })
    }

    @Test
    fun `out-of-order duplicate and reconnect fixtures converge to happy outcome`() = runTest {
        val happy = parseFixtureOutcome("happy-one-round.json").canonicalComparable()

        listOf("out-of-order.json", "duplicate-events.json", "reconnect-midturn.json").forEach { fixtureName ->
            val outcome = parseFixtureOutcome(fixtureName)
            assertEquals(fixtureName, happy, outcome.canonicalComparable())
            assertCanonicalOutcome(outcome)
        }
    }

    @Test
    fun `unknown type and extra fields are ignored without crashing`() = runTest {
        val happy = fixtureEvents("happy-one-round.json")
        val unknown = happy.first().copy(
            eventId = 99,
            sequence = 99,
            type = "future_event",
            payload = JsonObject(mapOf("future" to JsonPrimitive("ignored"))),
        )
        val withExtraField = json.parseToJsonElement(fixtureFile("happy-one-round.json").readText()).jsonArray.first().jsonObject.toMutableMap().apply {
            put("futureEnvelopeField", JsonPrimitive("ignored"))
            put("payload", JsonObject(happy.first().payload.jsonObject + ("futurePayloadField" to JsonPrimitive("ignored"))))
        }
        val decodedExtra = json.decodeFromJsonElement(RoundtableSseEvent.serializer(), JsonObject(withExtraField))
        val outcome = assembleWireEvents(listOf(unknown, decodedExtra) + happy.drop(1))

        assertCanonicalOutcome(outcome)
        assertFalse(outcome.eventTypes.contains("future_event"))
    }

    @Test
    fun `openEventStream resumes reconnect midturn with Last-Event-ID and no dup no loss`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val firstChunk = sseFrame(fixtureEvents("happy-one-round.json").take(3))
        val secondChunk = sseFrame(fixtureEvents("reconnect-midturn.json").drop(3))
        var eventRequestCount = 0
        val transport = newTransport(captured) { request ->
            captured += request
            when {
                request.url.encodedPath == "/roundtables" -> respondJson("""[{"id":"round-fixture-001","topic":"fixture"}]""")
                request.url.encodedPath == "/roundtables/round-fixture-001/events" -> {
                    eventRequestCount++
                    if (eventRequestCount == 1) {
                        respondSse(firstChunk)
                    } else {
                        assertEquals("3", request.headers["Last-Event-ID"])
                        respondSse(secondChunk)
                    }
                }
                else -> respond(status = HttpStatusCode.NotFound, content = ByteReadChannel(""))
            }
        }

        val collected = async {
            transport.openEventStream()
                .filterIsInstance<TransportEvent.Pi>()
                .map { event -> event.event }
                .take(12)
                .toList()
        }
        val outcome = assembleTransportEvents(collected.await())

        assertCanonicalOutcome(outcome)
        val eventRequests = captured.filter { request -> request.url.encodedPath.endsWith("/events") }
        assertEquals(null, eventRequests[0].headers["Last-Event-ID"])
        assertEquals("3", eventRequests[1].headers["Last-Event-ID"])
        assertEquals(2, eventRequestCount)
        assertTrue(outcome.messageIntegrity.all { it })
    }

    @Test
    fun `sendMessage sendCommand cancel and SSE all use Pi bearer auth`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val transport = newTransport(captured) { request ->
            captured += request
            when (request.url.encodedPath) {
                "/roundtables" -> respondJson("""[{"id":"round-fixture-001","topic":"fixture"}]""")
                "/roundtables/round-fixture-001/command" -> respondJson("""{"accepted":true}""")
                "/roundtables/round-fixture-001/cancel" -> respondJson("""{"cancelled":true}""")
                "/roundtables/round-fixture-001/events" -> respondSse(sseFrame(fixtureEvents("happy-one-round.json").take(1)))
                else -> respond(status = HttpStatusCode.NotFound, content = ByteReadChannel(""))
            }
        }

        transport.listRooms()
        transport.sendMessage("round-fixture-001", listOf(TransportMessagePart(type = "text", text = "hello")))
        assertTrue(transport.sendCommand("round-fixture-001", "可", "continue"))
        assertTrue(transport.sendCommand("round-fixture-001", "cancel", ""))
        val streamJob = async { transport.openEventStream().take(1).toList() }
        streamJob.await()

        assertEquals(5, captured.size)
        captured.forEach { request -> assertEquals("Bearer pi-token", request.headers[HttpHeaders.Authorization]) }
        assertEquals(HttpMethod.Get, captured[0].method)
        assertEquals(HttpMethod.Post, captured[1].method)
        assertEquals(HttpMethod.Post, captured[2].method)
        assertEquals(HttpMethod.Post, captured[3].method)
        assertEquals(HttpMethod.Get, captured[4].method)
    }

    private fun parseFixtureOutcome(name: String): FixtureOutcome = assembleWireEvents(fixtureEvents(name))

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

    private fun assembleWireEvents(events: List<RoundtableSseEvent>): FixtureOutcome =
        assembleTransportEvents(PiRoundtableEventProcessor(json).processSnapshot(events))

    private fun assembleTransportEvents(events: List<PiTransportEvent>): FixtureOutcome {
        val messages = events.filterIsInstance<PiTransportEvent.MessageEnd>().associate { event ->
            event.envelope.turnId!! to event.assembledText
        }
        val hashes = events.filterIsInstance<PiTransportEvent.MessageEnd>().associate { event ->
            event.envelope.turnId!! to event.contentSha256
        }
        val synthesis = events.filterIsInstance<PiTransportEvent.ModeratorSynthesis>().associate { event ->
            event.envelope.turnId!! to ModeratorOutcome(event.markdownBody, event.nextQuestion)
        }
        return FixtureOutcome(
            eventIds = events.map { event -> event.envelope.eventId },
            eventTypes = events.map { event -> event.envelope.type },
            messages = messages,
            hashes = hashes,
            synthesis = synthesis,
            roundEndReason = events.filterIsInstance<PiTransportEvent.RoundEnd>().lastOrNull()?.reason,
            messageIntegrity = events.filterIsInstance<PiTransportEvent.MessageEnd>().map { event -> event.integrity.isValid },
        )
    }

    private fun assertCanonicalOutcome(outcome: FixtureOutcome) {
        assertEquals("Truth seeking should lead because coverage without pressure-testing becomes trivia.", outcome.messages["turn-ada-001"])
        assertEquals("Coverage still matters when it maps the disagreement space before depth.", outcome.messages["turn-curie-001"])
        assertEquals("ed09bbb66b36c76d284bcae5b6e708e3603e957d98423a0c629ba6743ca76f87", outcome.hashes["turn-ada-001"])
        assertEquals("775516bc108e67c4b16a9909158a577326443b81c8c435a7fc227d6e23a49aed", outcome.hashes["turn-curie-001"])
        val moderator = outcome.synthesis["turn-moderator-001"]!!
        assertEquals(
            "The round keeps truth as the goal and coverage as the map.\n\n```mermaid\ngraph TD\n  A[Truth seeking] --> B[Pressure-test claims]\n  C[Coverage] --> D[Map disagreement space]\n  B --> E[Next experiment]\n  D --> E\n```\n",
            moderator.markdownBody,
        )
        assertEquals("Which pressure test should the group run next?", moderator.nextQuestion)
    }

    private fun FixtureOutcome.canonicalComparable(): FixtureOutcome = copy(
        eventIds = emptyList(),
        eventTypes = eventTypes.filter { type -> type in listOf("message_end", "moderator_synthesis", "awaiting_command", "round_end") },
    )

    private fun newTransport(
        captured: MutableList<HttpRequestData>,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PiRoundtableTransport {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout)
        }
        return PiRoundtableTransport(
            server = ServerConfig(
                id = "server-pi",
                type = ServerType.PI_ROUNDTABLE,
                url = "https://pi.example.test",
                token = "pi-token",
            ),
            api = PiApi(client, json, heartbeatTimeoutMs = 1_000),
            json = json,
            baseReconnectDelayMs = 1,
            maxReconnectDelayMs = 1,
        )
    }

    private fun sseFrame(events: List<RoundtableSseEvent>): String = events.joinToString(separator = "") { event ->
        val data = json.encodeToString(RoundtableSseEvent.serializer(), event)
        "id: ${event.eventId}\ndata: $data\n\n"
    }

    private fun MockRequestHandleScope.respondJson(content: String): HttpResponseData = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.respondSse(content: String): HttpResponseData = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
    )

    private data class ModeratorOutcome(
        val markdownBody: String,
        val nextQuestion: String,
    )

    private data class FixtureOutcome(
        val eventIds: List<Long>,
        val eventTypes: List<String>,
        val messages: Map<String, String>,
        val hashes: Map<String, String?>,
        val synthesis: Map<String, ModeratorOutcome>,
        val roundEndReason: String?,
        val messageIntegrity: List<Boolean>,
    )
}
