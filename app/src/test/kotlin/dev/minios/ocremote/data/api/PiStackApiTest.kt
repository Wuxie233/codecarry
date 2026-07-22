package dev.minios.ocremote.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiStackApiTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val conn = PiStackConnection.from("https://pi.example.test/control/", " secret-token ")

    @Test
    fun `connection normalizes origin and trailing slashes without replacing custom paths`() {
        assertEquals("https://pi.example.test/control", PiStackConnection.from("https://pi.example.test/", null).baseUrl)
        assertEquals("https://pi.example.test/control", PiStackConnection.from("https://pi.example.test/control///", null).baseUrl)
        assertEquals(
            "https://pi.example.test/custom-control",
            PiStackConnection.from("https://pi.example.test/custom-control/", null).baseUrl,
        )
    }

    @Test
    fun `capability probe preserves base path and sends bearer auth`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val api = api(captured) { respondJson(envelope(capabilities())) }

        val response = api.getCapabilities(conn)

        assertEquals(1, response.protocolVersion)
        assertTrue(response.data.runtime.prompt)
        assertEquals("/control/v1/capabilities", captured.single().url.encodedPath)
        assertEquals("Bearer secret-token", captured.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `directory and project session mutations use exact routes and guarded headers`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val replies = ArrayDeque(
            listOf(
                envelope("""{"path":"/srv","parent":"/","entries":[{"name":"repo","path":"/srv/repo"}],"truncated":false}"""),
                envelope(project()),
                envelope(session()),
            )
        )
        val api = api(captured) { respondJson(replies.removeFirst()) }

        val listing = api.listDirectories(conn, "/srv")
        api.registerProject(conn, "/srv/repo", "Repo", "generation-1", "project-key")
        api.createSession(
            conn = conn,
            projectId = "project/one",
            title = "Android",
            model = PiStackModelSelectionDto("openai", "gpt-5"),
            generation = "generation-1",
            idempotencyKey = "session-key",
        )

        assertEquals("/srv/repo", listing.data.entries.single().path)
        assertEquals("/control/v1/directories", captured[0].url.encodedPath)
        assertEquals("/srv", captured[0].url.parameters["path"])
        assertEquals(HttpMethod.Post, captured[1].method)
        assertEquals("/control/v1/projects", captured[1].url.encodedPath)
        assertEquals("project-key", captured[1].headers["Idempotency-Key"])
        assertEquals("generation-1", captured[1].headers["X-Pi-Worker-Generation"])
        assertEquals(ContentType.Application.Json.toString(), captured[1].body.contentType.toString())
        assertEquals("/control/v1/projects/project%2Fone/sessions", captured[2].url.encodedPath)
        assertEquals("session-key", captured[2].headers["Idempotency-Key"])
    }

    @Test
    fun `history decodes structured messages oldest to newest with tool state and opaque cursor`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val api = api(captured) {
            respondJson(envelope("""
                {
                  "items": [
                    {
                      "id":"message-1","sessionId":"session-1","promptId":"prompt-1","role":"user",
                      "status":"completed","parts":[{"id":"part-1","type":"text","text":"hello"}],
                      "createdAt":"2026-07-22T00:00:00.000Z","completedAt":"2026-07-22T00:00:00.000Z"
                    },
                    {
                      "id":"message-2","sessionId":"session-1","promptId":"prompt-1","role":"assistant",
                      "status":"streaming","parts":[
                        {"id":"part-2","type":"tool","toolCallId":"call-1","toolName":"bash","state":"running","input":{"command":"pwd"},"output":null,"error":null},
                        {"id":"future","type":"future_part","future":true}
                      ],"createdAt":null,"completedAt":null,"future":"ignored"
                    }
                  ],
                  "nextCursor":"opaque-before","hasMore":true
                }
            """.trimIndent()))
        }

        val page = api.getMessages(conn, "session/one", limit = 25, before = "opaque cursor")

        assertEquals(listOf("message-1", "message-2"), page.data.items.map { it.id })
        assertEquals(PiStackMessageStatus.Streaming, page.data.items[1].statusKind)
        val tool = page.data.items[1].parts[0] as PiStackStructuredPartDto.Tool
        assertEquals(PiStackToolState.Running, tool.stateKind)
        assertTrue(page.data.items[1].parts[1] is PiStackStructuredPartDto.Unknown)
        assertEquals("opaque-before", page.data.nextCursor)
        assertTrue(page.data.hasMore)
        assertEquals("/control/v1/sessions/session%2Fone/history", captured.single().url.encodedPath)
        assertEquals("25", captured.single().url.parameters["limit"])
        assertEquals("opaque cursor", captured.single().url.parameters["before"])
    }

    @Test
    fun `prompt and question reply carry generation idempotency and JSON answer`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val replies = ArrayDeque(listOf(envelope(operation()), envelope(questionResolution())))
        val api = api(captured) { respondJson(replies.removeFirst(), HttpStatusCode.Accepted) }

        val operation = api.prompt(conn, "session-1", "work", "generation-1", "prompt-key")
        api.replyQuestion(conn, "question-1", JsonPrimitive("answer"), "generation-1", "answer-key")

        assertEquals("operation-1", operation.data.id)
        assertEquals(listOf("prompt-key", "answer-key"), captured.map { it.headers["Idempotency-Key"] })
        assertTrue(captured.all { it.headers["X-Pi-Worker-Generation"] == "generation-1" })
        assertEquals("/control/v1/questions/question-1/reply", captured[1].url.encodedPath)
    }

    @Test
    fun `SSE sends Last Event ID and decodes connected event and resync frames`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val stream = """
            : heartbeat

            event: system.connected
            data: {"protocolVersion":1,"generation":"generation-1","type":"system.connected","ts":"now"}

            id: event-2
            event: future.event
            data: {"protocolVersion":1,"generation":"generation-1","eventId":"event-2","sequence":2,"scope":{"sessionId":"session-1"},"type":"future.event","payload":{"future":true},"ts":"now"}

            event: system.resync_required
            data: {"protocolVersion":1,"generation":"generation-1","type":"system.resync_required","snapshotCursor":{"generation":"generation-1","eventId":"event-9","sequence":9}}

        """.trimIndent().replace("\n", "\r\n")
        val api = api(captured) { respondSse(stream) }

        val frames = api.connectEvents(conn, "event-1").take(3).toList()

        assertTrue(frames[0] is PiStackSseFrame.Connected)
        assertEquals("future.event", (frames[1] as PiStackSseFrame.Event).event.type)
        assertEquals("event-9", (frames[2] as PiStackSseFrame.ResyncRequired).snapshotCursor.eventId)
        assertEquals("event-1", captured.single().headers["Last-Event-ID"])
        assertEquals("text/event-stream", captured.single().headers[HttpHeaders.Accept])
    }

    @Test
    fun `protocol mismatch and auth failure use stable error categories`() = runTest {
        val protocolApi = api(mutableListOf()) { respondJson(envelope(capabilities(), protocolVersion = 2)) }
        val protocol = runCatching { protocolApi.getCapabilities(conn) }.exceptionOrNull() as PiStackApiException
        assertEquals(PiStackApiErrorKind.Protocol, protocol.kind)

        val authApi = api(mutableListOf()) {
            respondJson(
                """{"protocolVersion":1,"error":{"code":"auth_invalid","message":"no","retryable":false}}""",
                HttpStatusCode.Unauthorized,
            )
        }
        val auth = runCatching { authApi.getCapabilities(conn) }.exceptionOrNull() as PiStackApiException
        assertEquals(PiStackApiErrorKind.Auth, auth.kind)
        assertEquals(401, auth.status)
        assertNull(auth.cause)
    }

    private fun api(
        captured: MutableList<HttpRequestData>,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PiStackApi {
        val client = HttpClient(MockEngine { request ->
            captured += request
            handler(request)
        }) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout)
        }
        return PiStackApi(client, json, heartbeatTimeoutMs = 1_000)
    }

    private fun MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(content),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.respondSse(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
    )

    private fun envelope(data: String, protocolVersion: Int = 1): String =
        """{"protocolVersion":$protocolVersion,"worker":${worker()},"data":$data,"replayed":false}"""

    private fun worker() =
        """{"generation":"generation-1","epoch":1,"startedAt":"2026-07-22T00:00:00.000Z","active":true}"""

    private fun capabilities() = """
        {"protocolVersion":1,"permissions":{"supported":false,"pending":[]},
         "runtime":{"prompt":true,"abort":true,"retry":false,"sessionPatch":["title"]},
         "questions":{"reply":true,"reject":true},
         "filesystem":{"directoryBrowse":true,"defaultPath":"home"},
         "projects":{"register":true},
         "sessions":{"create":true,"resume":"automatic","structuredHistory":true,"maxHistoryPageSize":100,"streamingActivity":true},
         "ensemble":{"projections":true,"commands":true,"tools":[]},"futureCapability":true}
    """.trimIndent()

    private fun project() =
        """{"id":"project-1","name":"Repo","directory":"/srv/repo","status":"active","createdAt":"now","updatedAt":"now"}"""

    private fun session() = """
        {"id":"session-1","projectId":"project-1","parentId":null,"supervisorHandle":"handle","piSessionId":"pi-1",
         "sessionFile":"/tmp/session.jsonl","runtimeGeneration":1,"runtimeNextSequence":2,"activePromptId":null,
         "cwd":"/srv/repo","workerGeneration":"generation-1","state":"idle","title":"Android",
         "createdAt":"now","updatedAt":"now","endedAt":null}
    """.trimIndent()

    private fun operation() = """
        {"id":"operation-1","kind":"prompt","status":"pending_dispatch","workerGeneration":"generation-1",
         "sessionId":"session-1","teamId":null,"taskId":null,"supervisorHandle":"handle","piSessionId":"pi-1",
         "runtimeGeneration":1,"promptId":"prompt-1","command":{"prompt":"work"},"result":null,"error":null,
         "acceptedAt":"now","settledAt":null,"createdAt":"now","updatedAt":"now"}
    """.trimIndent()

    private fun questionResolution() = """
        {"kind":"delivery_pending","question":{"id":"question-1","sessionId":"session-1","projectId":"project-1",
         "teamId":null,"taskId":null,"workerGeneration":"generation-1","kind":"free_text",
         "payload":{"kind":"free_text","prompt":"Answer?"},"status":"resolution_pending_delivery","answer":"answer",
         "delivery":null,"createdAt":"now","resolvedAt":null},"delivery":null}
    """.trimIndent()
}
