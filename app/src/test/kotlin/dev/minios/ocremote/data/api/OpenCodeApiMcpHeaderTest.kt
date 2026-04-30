package dev.minios.ocremote.data.api

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeApiMcpHeaderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun newApi(captured: MutableList<HttpRequestData>): OpenCodeApi {
        val engine = MockEngine { request ->
            captured += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/path" -> respondJson(
                    """
                    {
                      "home": "/home/user",
                      "state": "/state",
                      "config": "/config",
                      "worktree": "/workspace/my-project",
                      "directory": "/workspace/my-project"
                    }
                    """.trimIndent(),
                )

                request.method == HttpMethod.Get && request.url.encodedPath == "/file/content" -> respondJson(
                    """
                    {
                      "type": "file",
                      "content": "hello"
                    }
                    """.trimIndent(),
                )

                request.method == HttpMethod.Put && request.url.encodedPath == "/file/content" -> respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.OK,
                )

                else -> error("Unexpected request: ${'$'}{request.method.value} ${'$'}{request.url}")
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    private fun newApiForMcpRuntime(captured: MutableList<HttpRequestData>, body: String): OpenCodeApi {
        val engine = MockEngine { request ->
            captured += request
            when {
                request.url.encodedPath.startsWith("/mcp") -> respondJson(body)

                else -> error("Unexpected request: ${'$'}{request.method.value} ${'$'}{request.url}")
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    @Test
    fun `MCP file and path APIs attach encoded directory header when supplied`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")
        val directory = "/workspace/my-project"
        val encodedDirectory = Uri.encode(directory)

        api.getServerPaths(conn = conn, directory = directory)
        api.readFile(conn = conn, path = "/tmp/read.txt", directory = directory)
        api.readFileText(conn = conn, path = "/tmp/read-text.txt", directory = directory)
        api.writeFile(conn = conn, path = "/tmp/write.txt", content = "updated", directory = directory)

        assertEquals(4, captured.size)
        assertEquals(encodedDirectory, captured[0].headers[DIRECTORY_HEADER])
        assertEquals(encodedDirectory, captured[1].headers[DIRECTORY_HEADER])
        assertEquals(encodedDirectory, captured[2].headers[DIRECTORY_HEADER])
        assertEquals(encodedDirectory, captured[3].headers[DIRECTORY_HEADER])
    }

    @Test
    fun `MCP file and path APIs omit directory header when null`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        api.getServerPaths(conn = conn, directory = null)
        api.readFile(conn = conn, path = "/tmp/read.txt", directory = null)
        api.readFileText(conn = conn, path = "/tmp/read-text.txt", directory = null)
        api.writeFile(conn = conn, path = "/tmp/write.txt", content = "updated", directory = null)

        assertEquals(4, captured.size)
        captured.forEach { request ->
            assertNull(request.headers[DIRECTORY_HEADER])
        }
    }

    @Test
    fun `getMcpRuntime forwards directory header`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApiForMcpRuntime(captured, body = """{"servers":[]}""")
        val conn = ServerConnection.from("http://example.test:4096")
        val directory = "/workspace/proj"

        api.getMcpRuntime(conn = conn, directory = directory)

        val req = captured.single()
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("/mcp", req.url.encodedPath)
        assertEquals(Uri.encode(directory), req.headers[DIRECTORY_HEADER])
    }

    @Test
    fun `connectMcp forwards directory header and url-encodes name`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApiForMcpRuntime(captured, body = "")
        val conn = ServerConnection.from("http://example.test:4096")
        val directory = "/workspace/proj"

        api.connectMcp(conn = conn, name = "a/b name", directory = directory)

        val req = captured.single()
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(
            "Expected encoded MCP name in path, got ${'$'}{req.url.encodedPath}",
            req.url.encodedPath == "/mcp/a%2Fb%20name/connect" ||
                req.url.encodedPath == "/mcp/a%2Fb+name/connect",
        )
        assertEquals(Uri.encode(directory), req.headers[DIRECTORY_HEADER])
    }

    @Test
    fun `disconnectMcp omits directory header when null`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApiForMcpRuntime(captured, body = "")
        val conn = ServerConnection.from("http://example.test:4096")

        api.disconnectMcp(conn = conn, name = "fs", directory = null)

        val req = captured.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/mcp/fs/disconnect", req.url.encodedPath)
        assertNull(req.headers[DIRECTORY_HEADER])
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
