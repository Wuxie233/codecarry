package dev.minios.ocremote.data.diagnostics

import dev.minios.ocremote.di.NetworkModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsUploadClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun newClient(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): DiagnosticsUploadClient {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return DiagnosticsUploadClient(client)
    }

    @Test
    fun `upload sends multipart POST with bearer token and parses response`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val uploadClient = newClient { request ->
            captured += request
            respondJson(
                """
                {
                  "id": "diag_123",
                  "filename": "bundle.zip",
                  "size": 11,
                  "stored_at": "2026-05-26T04:00:00Z",
                  "sha256": "abc123"
                }
                """.trimIndent(),
            )
        }

        val response = uploadClient.upload(
            config = DiagnosticsUploadConfig(
                uploadUrl = "https://diagnostics.example/upload?token=query-secret",
                bearerToken = "upload-token",
            ),
            file = DiagnosticsUploadFile(
                filename = "bundle.zip",
                bytes = "hello world".toByteArray(),
                contentType = "application/zip",
            ),
        )

        assertEquals("diag_123", response.id)
        assertEquals("bundle.zip", response.filename)
        assertEquals(11L, response.size)
        assertEquals("2026-05-26T04:00:00Z", response.storedAt)
        assertEquals("abc123", response.sha256)

        val request = captured.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/upload", request.url.encodedPath)
        assertEquals("Bearer upload-token", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType?.match(ContentType.MultiPart.FormData) == true)
        val body = request.body.bodyAsText()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"bundle.zip\""))
        assertTrue(body.contains("hello world"))
    }

    @Test
    fun `upload throws sanitized failure for unauthorized response`() = runBlocking {
        val uploadClient = newClient { _ ->
            respond(
                content = ByteReadChannel("Authorization: Bearer leaked-token\npassword=leaked-password"),
                status = HttpStatusCode.Unauthorized,
            )
        }

        val error = runCatching {
            uploadClient.upload(
                config = DiagnosticsUploadConfig("https://diagnostics.example/upload", "upload-token"),
                file = DiagnosticsUploadFile("bundle.zip", "zip".toByteArray()),
            )
        }.exceptionOrNull()

        assertNotNull(error)
        val message = error?.message.orEmpty()
        assertTrue(message.contains("HTTP 401"))
        assertFalse(message.contains("leaked-token"))
        assertFalse(message.contains("leaked-password"))
        assertTrue(message.contains("<redacted>"))
    }

    @Test
    fun `missing upload URL fails before network request`() = runBlocking {
        var requestCount = 0
        val uploadClient = newClient { _ ->
            requestCount += 1
            respondJson("{}")
        }

        val error = runCatching {
            uploadClient.upload(
                config = DiagnosticsUploadConfig(" ", "upload-token"),
                file = DiagnosticsUploadFile("bundle.zip", byteArrayOf(1)),
            )
        }.exceptionOrNull()

        assertTrue(error is DiagnosticsUploadException.MissingUploadUrl)
        assertEquals(0, requestCount)
    }

    @Test
    fun `missing bearer token fails before network request`() = runBlocking {
        var requestCount = 0
        val uploadClient = newClient { _ ->
            requestCount += 1
            respondJson("{}")
        }

        val error = runCatching {
            uploadClient.upload(
                config = DiagnosticsUploadConfig("https://diagnostics.example/upload", "\t"),
                file = DiagnosticsUploadFile("bundle.zip", byteArrayOf(1)),
            )
        }.exceptionOrNull()

        assertTrue(error is DiagnosticsUploadException.MissingBearerToken)
        assertEquals(0, requestCount)
    }

    @Test
    fun `diagnostics redactor removes bearer password api key cookie and URL query secrets`() {
        val raw = """
            Authorization: Bearer auth-secret
            password=hunter2 api_key=api-secret token: token-secret
            Cookie: session=abc123; theme=dark
            https://diagnostics.example/upload?token=query-token&api_key=query-key&safe=value
            cookie=loose-cookie
        """.trimIndent()

        val redacted = DiagnosticsRedactor.redact(raw)

        assertFalse(redacted.contains("auth-secret"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("api-secret"))
        assertFalse(redacted.contains("token-secret"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("query-token"))
        assertFalse(redacted.contains("query-key"))
        assertFalse(redacted.contains("loose-cookie"))
        assertTrue(redacted.contains("safe=value"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `diagnostics DI client has no header logging plugin`() {
        val client = NetworkModule.provideDiagnosticsHttpClient(json)

        assertEquals(null, client.pluginOrNull(Logging))
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private suspend fun OutgoingContent.bodyAsText(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().toString(Charsets.UTF_8)
        is OutgoingContent.ReadChannelContent -> readFrom().readRemaining().readText()
        is OutgoingContent.WriteChannelContent -> coroutineScope {
            val channel = ByteChannel(autoFlush = true)
            withContext(Dispatchers.Default) {
                writeTo(channel)
                channel.close(null)
            }
            val text = channel.readRemaining().readText()
            text
        }
        is OutgoingContent.NoContent -> ""
        is OutgoingContent.ProtocolUpgrade -> error("Unexpected protocol upgrade request body")
    }
}
