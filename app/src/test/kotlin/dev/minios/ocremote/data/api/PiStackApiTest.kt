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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `connection rebuilds safe base URL from legacy URI components`() {
        assertEquals(
            "https://pi.example.test/control",
            PiStackConnection.from("https://user:secret@pi.example.test?legacy=1#fragment", null).baseUrl,
        )
        assertEquals(
            "http://[::1]:8787/control",
            PiStackConnection.from("http://[::1]:8787/", null).baseUrl,
        )
        assertEquals("not a url", PiStackConnection.from(" not a url/ ", null).baseUrl)
    }

    @Test
    fun `capability probe preserves base path and sends bearer auth`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val api = api(captured) { respondJson(envelope(capabilities())) }

        val response = api.getCapabilities(conn)

        assertEquals(1, response.protocolVersion)
        assertTrue(response.data.runtime.prompt)
        assertTrue(response.data.runtime.attachments)
        assertTrue(response.data.sessions.archive)
        assertTrue(response.data.models.select)
        assertEquals("/control/v1/capabilities", captured.single().url.encodedPath)
        assertEquals("Bearer secret-token", captured.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `legacy capability response defaults new controls to unavailable`() = runTest {
        val api = api(mutableListOf()) {
            respondJson(envelope("""
                {"protocolVersion":1,"permissions":{"supported":false,"pending":[]},
                 "runtime":{"prompt":true,"abort":true,"retry":false,"sessionPatch":[]},
                 "questions":{"reply":true,"reject":true}}
            """.trimIndent()))
        }

        val capabilities = api.getCapabilities(conn).data

        assertFalse(capabilities.runtime.attachments)
        assertFalse(capabilities.runtime.compact)
        assertFalse(capabilities.sessions.archive)
        assertFalse(capabilities.models.select)
        assertFalse(capabilities.thinking.list)
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
    fun `session controls and extended mutations use public v1 routes`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val controls = """
            {"runtime":{"protocolVersion":1,"daemonInstanceId":"daemon","runtimeGeneration":2,"nextSequence":4},
             "session":{"id":"session-1","projectId":"project-1","cwd":"/srv/repo","sessionFile":"/tmp/a.jsonl",
               "parentSessionId":null,"title":"Work","state":"idle","createdAt":"now","updatedAt":"now","messageCount":3},
             "controls":{"model":{"provider":"openai","modelId":"gpt-5","name":"GPT-5","contextWindow":200000},
               "thinkingLevel":"high","models":[{"provider":"openai","modelId":"gpt-5","name":"GPT-5"}],
               "thinkingLevels":["low","high"],"commands":[{"name":"review","source":"builtin"}]},"activeOperation":null}
        """.trimIndent()
        val replies = ArrayDeque(listOf(
            envelope(controls), envelope(operation("session_rename")), envelope(operation("session_archive")),
            envelope(operation("session_restore")), envelope(operation("session_delete")), envelope(operation("model_change")),
            envelope(operation("thinking_change")), envelope(operation("compact")), envelope(operation("fork")), envelope(operation("command")),
        ))
        val api = api(captured) { respondJson(replies.removeFirst()) }

        val descriptor = api.getSessionControls(conn, "session/one").data
        api.renameSession(conn, "session/one", "Renamed", "generation-1", "rename-key")
        api.archiveSession(conn, "session/one", "generation-1", "archive-key")
        api.restoreSession(conn, "session/one", "generation-1", "restore-key")
        api.deleteSession(conn, "session/one", "generation-1", "delete-key")
        api.selectModel(conn, "session/one", "openai", "gpt-5", "generation-1", "model-key")
        api.selectThinking(conn, "session/one", "high", "generation-1", "thinking-key")
        api.compactSession(conn, "session/one", "focus", "generation-1", "compact-key")
        api.forkSession(conn, "session/one", "entry-7", "generation-1", "fork-key")
        api.executeCommand(conn, "session/one", "/review focus", "generation-1", "command-key")

        assertEquals("gpt-5", descriptor.controls.model?.modelId)
        assertEquals(listOf("low", "high"), descriptor.controls.thinkingLevels)
        assertEquals("review", descriptor.controls.commands.single().name)
        assertEquals(HttpMethod.Get, captured[0].method)
        assertEquals("/control/v1/sessions/session%2Fone/controls", captured[0].url.encodedPath)
        assertEquals(HttpMethod.Patch, captured[1].method)
        assertEquals(HttpMethod.Delete, captured[4].method)
        assertEquals(
            listOf("archive", "restore", "model", "thinking", "compact", "fork", "commands"),
            listOf(2, 3, 5, 6, 7, 8, 9).map { captured[it].url.encodedPath.substringAfterLast('/') },
        )
        assertTrue(captured.drop(1).all { it.headers["X-Pi-Worker-Generation"] == "generation-1" })
    }

    @Test
    fun `attachment upload uses binary contract and prompt sends handle`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val replies = ArrayDeque(listOf(
            envelope("""{"handle":"handle-1","sessionId":"session-1","cwd":"/srv/repo","kind":"file","name":"notes.txt","mimeType":"text/plain","size":5,"path":"/tmp/notes.txt","createdAt":"now"}"""),
            envelope(operation()),
        ))
        val api = api(captured) { respondJson(replies.removeFirst()) }

        val attachment = api.uploadAttachment(conn, "session-1", "file", "notes.txt", "text/plain", "hello".encodeToByteArray(), "generation-1", "upload-key")
        api.prompt(conn, "session-1", "read", "generation-1", "prompt-key", listOf(attachment.data.handle))

        assertEquals("handle-1", attachment.data.handle)
        assertEquals(ContentType.Application.OctetStream.toString(), captured[0].body.contentType.toString())
        assertEquals("file", captured[0].headers["X-Attachment-Kind"])
        assertEquals("notes.txt", captured[0].headers["X-Attachment-Name"])
        assertEquals("text/plain", captured[0].headers["X-Attachment-Mime"])
        assertEquals("upload-key", captured[0].headers["Idempotency-Key"])
        assertEquals("/control/v1/sessions/session-1/prompt", captured[1].url.encodedPath)
    }

    @Test
    fun `history preserves authority entry id for exact fork`() = runTest {
        val api = api(mutableListOf()) {
            respondJson(envelope("""{"items":[{"id":"message-1","entryId":"entry-1","sessionId":"session-1","role":"user","status":"completed","parts":[]}],"hasMore":false}"""))
        }

        assertEquals("entry-1", api.getMessages(conn, "session-1").data.items.single().entryId)
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
    fun `SSE sends Last Event ID and decodes connected event and resync frames`() = runBlocking {
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
         "runtime":{"prompt":true,"abort":true,"retry":false,"sessionPatch":["title"],"compact":true,"fork":true,"commands":true,"attachments":true},
         "questions":{"reply":true,"reject":true},
         "filesystem":{"directoryBrowse":true,"defaultPath":"home"},
         "projects":{"register":true},
         "sessions":{"create":true,"resume":"automatic","structuredHistory":true,"maxHistoryPageSize":100,"streamingActivity":true,"controls":true,"archive":true,"restore":true,"delete":true},
         "models":{"list":true,"select":true},"thinking":{"list":true,"select":true},
         "futureCapability":true}
    """.trimIndent()

    private fun project() =
        """{"id":"project-1","name":"Repo","directory":"/srv/repo","status":"active","createdAt":"now","updatedAt":"now"}"""

    private fun session() = """
        {"id":"session-1","projectId":"project-1","parentId":null,"supervisorHandle":"handle","piSessionId":"pi-1",
         "sessionFile":"/tmp/session.jsonl","runtimeGeneration":1,"runtimeNextSequence":2,"activePromptId":null,
         "cwd":"/srv/repo","workerGeneration":"generation-1","state":"idle","title":"Android",
         "createdAt":"now","updatedAt":"now","endedAt":null}
    """.trimIndent()

    private fun operation(kind: String = "prompt") = """
        {"id":"operation-1","kind":"$kind","status":"pending_dispatch","workerGeneration":"generation-1",
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
