package dev.minios.ocremote.data.api

import android.content.ContextWrapper
import dev.minios.ocremote.data.diagnostics.DiagnosticsLogRepository
import dev.minios.ocremote.data.diagnostics.NetworkDiagnosticsRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OpenCodeApiNetworkDiagnosticsTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `createSession success records safe network summary without changing request`() = runBlocking {
        val fixture = newFixture { request ->
            assertEquals("/session", request.url.encodedPath)
            assertEquals("Basic abc123", request.headers[HttpHeaders.Authorization])
            respondJson(
                """
                {
                  "id": "ses_created",
                  "directory": "/workspace/project",
                  "time": { "created": 1, "updated": 2, "archived": null }
                }
                """.trimIndent(),
            )
        }
        val conn = ServerConnection(
            baseUrl = "https://example.test",
            authHeader = "Basic abc123",
        )

        val session = fixture.api.createSession(conn = conn, title = "request body secret", directory = "/workspace")

        assertEquals("ses_created", session.id)
        val summary = fixture.recorder.snapshot().single()
        assertEquals("POST", summary.method)
        assertEquals("/session", summary.pathCategory)
        assertEquals(200, summary.statusCode)
        assertEquals(null, summary.failureType)
        assertTrue(summary.durationMillis >= 0)
        val content = fixture.recorder.buildArtifactContent(fixture.recorder.snapshot(), generatedAtMillis = 5000L)
        assertFalse(content.contains("Authorization"))
        assertFalse(content.contains("Bearer"))
        assertFalse(content.contains("Basic abc123"))
        assertFalse(content.contains("request body secret"))
        assertFalse(content.contains("response body"))
    }

    @Test
    fun `createSession failure records status and failure type without raw body url or token`() = runBlocking {
        val fixture = newFixture { _ ->
            respond(
                content = ByteReadChannel("response body Authorization: Bearer response-token password=response-password"),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }
        val conn = ServerConnection(
            baseUrl = "https://example.test",
            authHeader = "Bearer request-token",
        )

        val error = runCatching {
            fixture.api.createSession(conn = conn, title = "request body password=body-password")
        }.exceptionOrNull()

        assertTrue(error is Exception)
        val summary = fixture.recorder.snapshot().single()
        assertEquals("POST", summary.method)
        assertEquals("/session", summary.pathCategory)
        assertEquals(500, summary.statusCode)
        assertTrue(summary.failureType.orEmpty().endsWith("Exception"))
        val item = fixture.recorder.createArtifact(createdAtMillis = 6000L)
        val content = fixture.repository.getArtifactFile(item)?.readText().orEmpty()
        assertTrue(content.contains("\"status_code\":500"))
        assertFalse(content.contains("Authorization"))
        assertFalse(content.contains("Bearer"))
        assertFalse(content.contains("request-token"))
        assertFalse(content.contains("response-token"))
        assertFalse(content.contains("response-password"))
        assertFalse(content.contains("body-password"))
        assertFalse(content.contains("response body"))
        assertFalse(content.contains("https://example.test"))
    }

    @Test
    fun `createSession transport failure records class only not exception message`() = runBlocking {
        val fixture = newFixture { _ ->
            throw IOException("failed https://example.test/session?token=exception-token Authorization: Bearer exception-token")
        }

        val error = runCatching {
            fixture.api.createSession(ServerConnection.from("https://example.test"))
        }.exceptionOrNull()

        assertTrue(error is IOException)
        val summary = fixture.recorder.snapshot().single()
        assertEquals(null, summary.statusCode)
        assertEquals("IOException", summary.failureType)
        val content = fixture.recorder.buildArtifactContent(fixture.recorder.snapshot(), generatedAtMillis = 7000L)
        assertFalse(content.contains("exception-token"))
        assertFalse(content.contains("https://example.test"))
    }

    private fun newFixture(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Fixture {
        val filesDir = tmpFolder.newFolder("files-${System.nanoTime()}")
        val cacheDir = tmpFolder.newFolder("cache-${System.nanoTime()}")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
        }
        val repository = DiagnosticsLogRepository(context)
        val recorder = NetworkDiagnosticsRecorder(repository)
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return Fixture(
            repository = repository,
            recorder = recorder,
            api = OpenCodeApi(client, json, recorder),
        )
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private data class Fixture(
        val repository: DiagnosticsLogRepository,
        val recorder: NetworkDiagnosticsRecorder,
        val api: OpenCodeApi,
    )
}
