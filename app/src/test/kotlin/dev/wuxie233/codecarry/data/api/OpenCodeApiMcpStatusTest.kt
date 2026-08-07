package dev.wuxie233.codecarry.data.api

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
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
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeApiMcpStatusTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun newApi(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): OpenCodeApi {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    @Test
    fun `getMcpStatus parses runtime map and attaches encoded directory header`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi { request ->
            captured += request
            assertEquals("/mcp", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            respondJson(
                """
                {
                  "aceTool": {"status": "connected", "version": "1.0.0"},
                  "autoinfo": {"status": "disabled"},
                  "exa": {"status": "failed", "error": "startup failed"},
                  "fetch": {"status": "needs_auth"},
                  "github": {"status": "needs_client_registration", "error": "register OAuth client"},
                  "playwright": {"status": "connected"},
                  "stitch": {"status": "connected"}
                }
                """.trimIndent(),
            )
        }
        val conn = ServerConnection.from("http://example.test:4096")
        val directory = "/workspace/proj"

        val result = api.getMcpStatus(conn, directory)

        assertTrue(result is McpRuntimeStatusResult.Success)
        val map = (result as McpRuntimeStatusResult.Success).statuses
        assertEquals(7, map.size)
        assertEquals("connected", map["aceTool"]?.status)
        assertEquals("1.0.0", map["aceTool"]?.version)
        assertEquals("disabled", map["autoinfo"]?.status)
        assertEquals("failed", map["exa"]?.status)
        assertEquals("startup failed", map["exa"]?.error)
        assertEquals("needs_auth", map["fetch"]?.status)
        assertEquals("needs_client_registration", map["github"]?.status)
        assertEquals("register OAuth client", map["github"]?.error)
        assertEquals(Uri.encode(directory), captured.single().headers[DIRECTORY_HEADER])
    }

    @Test
    fun `getMcpStatus omits directory header when null and preserves empty success`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi { request ->
            captured += request
            respondJson("{}")
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getMcpStatus(conn, directory = null)

        assertTrue(result is McpRuntimeStatusResult.Success)
        assertEquals(emptyMap<String, McpRuntimeStatus>(), (result as McpRuntimeStatusResult.Success).statuses)
        assertNull(captured.single().headers[DIRECTORY_HEADER])
    }

    @Test
    fun `getMcpStatus returns Unsupported for older server endpoint status codes`() = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed, HttpStatusCode.NotImplemented)) {
            val api = newApi { _ -> respond(content = ByteReadChannel(""), status = status) }
            val conn = ServerConnection.from("http://example.test:4096")

            val result = api.getMcpStatus(conn, directory = null)

            assertTrue("status=$status -> Unsupported", result is McpRuntimeStatusResult.Unsupported)
        }
    }

    @Test
    fun `getMcpStatus returns Failed on 401 with no body leak`() = runBlocking {
        val api = newApi { _ ->
            respond(
                content = ByteReadChannel("Authorization required: Bearer secret-token"),
                status = HttpStatusCode.Unauthorized,
            )
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getMcpStatus(conn, directory = null)

        assertTrue(result is McpRuntimeStatusResult.Failed)
        val msg = (result as McpRuntimeStatusResult.Failed).cause.message.orEmpty()
        assertTrue("HTTP status surfaced", msg.contains("401"))
        assertTrue("body must not leak", !msg.contains("secret-token"))
    }

    @Test
    fun `getMcpStatus wraps transport exceptions as Failed`() = runBlocking {
        val api = newApi { _ -> throw IOException("network unavailable") }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getMcpStatus(conn, directory = null)

        assertTrue(result is McpRuntimeStatusResult.Failed)
        assertTrue((result as McpRuntimeStatusResult.Failed).cause is IOException)
    }

    @Test
    fun `getMcpStatus tolerates unknown status string for forward compat`() = runBlocking {
        val api = newApi { _ -> respondJson("""{"future": {"status": "reconnecting"}}""") }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getMcpStatus(conn, directory = null)

        assertTrue(result is McpRuntimeStatusResult.Success)
        assertEquals("reconnecting", (result as McpRuntimeStatusResult.Success).statuses["future"]?.status)
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private companion object {
        private const val DIRECTORY_HEADER = "x-opencode-directory"
    }
}
