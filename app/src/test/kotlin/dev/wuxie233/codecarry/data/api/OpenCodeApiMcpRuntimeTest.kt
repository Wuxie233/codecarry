package dev.wuxie233.codecarry.data.api

import android.net.Uri
import dev.wuxie233.codecarry.domain.model.McpRuntimeState
import dev.wuxie233.codecarry.domain.model.McpRuntimeStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeApiMcpRuntimeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val testConn = ServerConnection.from("http://example.test:4096")

    private fun newApi(
        captured: MutableList<HttpRequestData>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"servers":[]}""",
    ): OpenCodeApi {
        val engine = MockEngine { request ->
            captured += request
            respondJson(body = body, status = status)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    @Test
    fun `getMcpRuntime forwards encoded directory header`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)

        api.getMcpRuntime(testConn, directory = "/workspace/proj")

        val request = captured.single()
        assertEquals("/mcp", request.url.encodedPath)
        assertEquals(Uri.encode("/workspace/proj"), request.headers[DIRECTORY_HEADER])
    }

    @Test
    fun `getMcpRuntime decodes wrapped servers response`() = runBlocking {
        val api = newApi(
            captured = mutableListOf(),
            body = """{"servers":[{"name":"fs","state":"connected"},{"name":"","state":"connected"}]}""",
        )

        val result = api.getMcpRuntime(testConn)

        assertEquals(listOf(McpRuntimeStatus("fs", McpRuntimeState.CONNECTED)), result)
    }

    @Test
    fun `getMcpRuntime decodes bare list response`() = runBlocking {
        val api = newApi(
            captured = mutableListOf(),
            body = """[{"name":"fs","state":"failed","error":"boom"}]""",
        )

        val result = api.getMcpRuntime(testConn)

        assertEquals(1, result?.size)
        assertEquals(McpRuntimeState.FAILED, result?.single()?.state)
        assertEquals("boom", result?.single()?.errorMessage)
    }

    @Test
    fun `getMcpRuntime decodes map response`() = runBlocking {
        val api = newApi(
            captured = mutableListOf(),
            body = """{"fs":{"state":"needs_auth"}}""",
        )

        val result = api.getMcpRuntime(testConn)

        assertEquals(1, result?.size)
        assertEquals("fs", result?.single()?.name)
        assertEquals(McpRuntimeState.NEEDS_AUTH, result?.single()?.state)
    }

    @Test
    fun `getMcpRuntime returns null for unsupported runtime endpoints`() = runBlocking {
        val notFoundApi = newApi(mutableListOf(), status = HttpStatusCode.NotFound)
        val methodNotAllowedApi = newApi(mutableListOf(), status = HttpStatusCode.MethodNotAllowed)

        assertNull(notFoundApi.getMcpRuntime(testConn))
        assertNull(methodNotAllowedApi.getMcpRuntime(testConn))
    }

    @Test
    fun `getMcpRuntime throws sanitized IOException on server errors`() {
        val api = newApi(
            captured = mutableListOf(),
            status = HttpStatusCode.InternalServerError,
            body = "token=abc123 boom",
        )

        val error = assertThrows(IOException::class.java) {
            runBlocking { api.getMcpRuntime(testConn) }
        }
        assertTrue(error.message.orEmpty().contains("token=<redacted> boom"))
        assertFalse(error.message.orEmpty().contains("abc123"))
    }

    @Test
    fun `connectMcp encodes server name and handles statuses`() = runBlocking {
        val happyCaptured = mutableListOf<HttpRequestData>()
        val happyApi = newApi(happyCaptured, status = HttpStatusCode.OK)

        assertTrue(happyApi.connectMcp(testConn, "a/b name"))
        assertEquals("/mcp/a%2Fb+name/connect", happyCaptured.single().url.encodedPath)

        assertFalse(newApi(mutableListOf(), status = HttpStatusCode.NotFound).connectMcp(testConn, "fs"))

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                newApi(
                    mutableListOf(),
                    status = HttpStatusCode.InternalServerError,
                    body = "authorization: Bearer abc123",
                ).connectMcp(testConn, "fs")
            }
        }
        assertFalse(error.message.orEmpty().contains("abc123"))
    }

    @Test
    fun `disconnectMcp mirrors connect status handling`() = runBlocking {
        val happyCaptured = mutableListOf<HttpRequestData>()
        val happyApi = newApi(happyCaptured, status = HttpStatusCode.OK)

        assertTrue(happyApi.disconnectMcp(testConn, "fs"))
        assertEquals("/mcp/fs/disconnect", happyCaptured.single().url.encodedPath)

        assertFalse(newApi(mutableListOf(), status = HttpStatusCode.NotFound).disconnectMcp(testConn, "fs"))

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                newApi(
                    mutableListOf(),
                    status = HttpStatusCode.InternalServerError,
                    body = "headers=Authorization: Bearer abc123",
                ).disconnectMcp(testConn, "fs")
            }
        }
        assertFalse(error.message.orEmpty().contains("abc123"))
    }

    @Test
    fun `sanitizeMcpError redacts secret and config shaped values`() {
        assertEquals("token=<redacted> boom", sanitizeMcpError("token=abc123 boom"))
        assertEquals("command=<redacted>", sanitizeMcpError("command=cat /tmp/secret"))
        assertEquals("headers=<redacted>", sanitizeMcpError("headers=Authorization: Bearer abc123"))
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode,
    ) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private companion object {
        private const val DIRECTORY_HEADER = "x-opencode-directory"
    }
}
