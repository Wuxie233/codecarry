package dev.wuxie233.codecarry.data.repository

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.api.OpenCodeFileReadException
import dev.wuxie233.codecarry.data.api.PiApi
import dev.wuxie233.codecarry.data.api.ServerConnection
import dev.wuxie233.codecarry.domain.model.McpRuntimeSnapshot
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryMcpRuntimeTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val conn = ServerConnection.from("http://example.test:4096")

    @Test
    fun `connected toggle disconnects and returns refreshed snapshot`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(McpReply.Ok(runtimeBody("fs", "disabled"))),
            ),
            scope = backgroundScope,
        )
        val previous = snapshot("fs", McpRuntimeState.CONNECTED)

        val result = repository.toggleMcpRuntime(conn, projectDir, "fs", previous)

        assertTrue(result.isSuccess)
        assertEquals(McpRuntimeState.DISABLED, result.getOrThrow().servers.single().state)
        assertEquals(1, captured.countPath(HttpMethod.Post, "/mcp/fs/disconnect"))
        assertEquals(0, captured.countPath(HttpMethod.Post, "/mcp/fs/connect"))
    }

    @Test
    fun `disabled toggle connects and returns refreshed snapshot`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(McpReply.Ok(runtimeBody("fs", "connected"))),
            ),
            scope = backgroundScope,
        )
        val previous = snapshot("fs", McpRuntimeState.DISABLED)

        val result = repository.toggleMcpRuntime(conn, projectDir, "fs", previous)

        assertTrue(result.isSuccess)
        assertEquals(McpRuntimeState.CONNECTED, result.getOrThrow().servers.single().state)
        assertEquals(1, captured.countPath(HttpMethod.Post, "/mcp/fs/connect"))
        assertEquals(0, captured.countPath(HttpMethod.Post, "/mcp/fs/disconnect"))
    }

    @Test
    fun `failed toggle connects and returns refreshed snapshot`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(McpReply.Ok(runtimeBody("fs", "connected"))),
            ),
            scope = backgroundScope,
        )
        val previous = snapshot("fs", McpRuntimeState.FAILED)

        val result = repository.toggleMcpRuntime(conn, projectDir, "fs", previous)

        assertTrue(result.isSuccess)
        assertEquals(McpRuntimeState.CONNECTED, result.getOrThrow().servers.single().state)
        assertEquals(1, captured.countPath(HttpMethod.Post, "/mcp/fs/connect"))
        assertEquals(0, captured.countPath(HttpMethod.Post, "/mcp/fs/disconnect"))
    }

    @Test
    fun `unknown toggle connects and returns refreshed snapshot`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(McpReply.Ok(runtimeBody("fs", "connected"))),
            ),
            scope = backgroundScope,
        )
        val previous = snapshot("fs", McpRuntimeState.UNKNOWN)

        val result = repository.toggleMcpRuntime(conn, projectDir, "fs", previous)

        assertTrue(result.isSuccess)
        assertEquals(McpRuntimeState.CONNECTED, result.getOrThrow().servers.single().state)
        assertEquals(1, captured.countPath(HttpMethod.Post, "/mcp/fs/connect"))
        assertEquals(0, captured.countPath(HttpMethod.Post, "/mcp/fs/disconnect"))
    }

    @Test
    fun `needs auth toggle fails without calling runtime action`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(api = newApi(captured), scope = backgroundScope)
        val previous = snapshot("fs", McpRuntimeState.NEEDS_AUTH)

        val result = repository.toggleMcpRuntime(conn, projectDir, "fs", previous)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is McpAuthRequiredException)
        assertEquals(McpRuntimeState.NEEDS_AUTH, (error as McpAuthRequiredException).state)
        assertEquals(0, captured.countMcpPosts())
    }

    @Test
    fun `needs client registration toggle fails without calling runtime action`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(api = newApi(captured), scope = backgroundScope)
        val previous = snapshot("fs", McpRuntimeState.NEEDS_CLIENT_REGISTRATION)

        val result = repository.toggleMcpRuntime(conn, projectDir, "fs", previous)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is McpAuthRequiredException)
        assertEquals(McpRuntimeState.NEEDS_CLIENT_REGISTRATION, (error as McpAuthRequiredException).state)
        assertEquals(0, captured.countMcpPosts())
    }

    @Test
    fun `connect API failure returns toggle exception carrying previous snapshot`() = runTest {
        val repository = newRepository(
            api = newApi(connectStatus = HttpStatusCode.InternalServerError),
            scope = backgroundScope,
        )
        val previous = snapshot("fs", McpRuntimeState.DISABLED)

        val result = repository.toggleMcpRuntime(conn, projectDir, "fs", previous)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is McpToggleException)
        assertSame(previous, (error as McpToggleException).previous)
    }

    @Test
    fun `load runtime uses runtime endpoint without falling back to file config`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(McpReply.Ok(runtimeBody("fs", "connected"))),
                fileContentResponse = { error("File config fallback should not be called") },
            ),
            scope = backgroundScope,
        )

        val result = repository.loadMcpRuntime(conn, projectDir)

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertTrue(snapshot.supportsRuntimeControl)
        assertEquals(listOf(McpRuntimeStatus("fs", McpRuntimeState.CONNECTED, null)), snapshot.servers)
        assertEquals(1, captured.size)
        val request = captured.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/mcp", request.url.encodedPath)
        assertEquals(Uri.encode(projectDir), request.headers[DIRECTORY_HEADER])
    }

    @Test
    fun `load runtime falls back to file config when endpoint is unsupported`() = runTest {
        val repository = newRepository(
            api = newApi(
                mcpReplies = mutableListOf(McpReply.Unsupported),
                fileContentResponse = { path ->
                    if (path == "$projectDir/.opencode/opencode.json") {
                        FileReply(HttpStatusCode.OK, validMcpFileContent)
                    } else {
                        FileReply(HttpStatusCode.NotFound)
                    }
                },
            ),
            scope = backgroundScope,
        )

        val result = repository.loadMcpRuntime(conn, projectDir)

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertFalse(snapshot.supportsRuntimeControl)
        assertEquals(listOf(McpRuntimeStatus("filesystem", McpRuntimeState.UNKNOWN, null)), snapshot.servers)
    }

    @Test
    fun `load runtime returns file config error cause when fallback read fails`() = runTest {
        val repository = newRepository(
            api = newApi(
                mcpReplies = mutableListOf(McpReply.Unsupported),
                fileContentResponse = { FileReply(HttpStatusCode.Forbidden, "forbidden") },
            ),
            scope = backgroundScope,
        )

        val result = repository.loadMcpRuntime(conn, projectDir)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OpenCodeFileReadException)
    }

    @Test
    fun `mid toggle unsupported runtime returns toggle exception with unsupported cause`() = runTest {
        val repository = newRepository(
            api = newApi(mcpReplies = mutableListOf(McpReply.Unsupported)),
            scope = backgroundScope,
        )
        val previous = snapshot("fs", McpRuntimeState.DISABLED)

        val result = repository.toggleMcpRuntime(conn, projectDir, "fs", previous)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is McpToggleException)
        assertSame(previous, (error as McpToggleException).previous)
        assertTrue(error.cause is McpRuntimeUnsupportedException)
    }

    @Test
    fun `project directory is forwarded on every runtime and fallback API call`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(
                    McpReply.Unsupported,
                    McpReply.Ok(runtimeBody("fs", "connected")),
                ),
                fileContentResponse = { path ->
                    if (path == "$projectDir/.opencode/opencode.json") {
                        FileReply(HttpStatusCode.OK, validMcpFileContent)
                    } else {
                        FileReply(HttpStatusCode.NotFound)
                    }
                },
            ),
            scope = backgroundScope,
        )

        repository.loadMcpRuntime(conn, projectDir).getOrThrow()
        repository.toggleMcpRuntime(conn, projectDir, "fs", snapshot("fs", McpRuntimeState.DISABLED)).getOrThrow()

        val encodedDirectory = Uri.encode(projectDir)
        assertTrue(captured.isNotEmpty())
        captured.forEach { request ->
            assertEquals(encodedDirectory, request.headers[DIRECTORY_HEADER])
        }
    }

    private fun newRepository(api: OpenCodeApi, scope: CoroutineScope): ServerRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("server_repo_mcp_runtime_${System.nanoTime()}.preferences_pb") },
        )

        return ServerRepository(
            dataStore = dataStore,
            api = api,
            piApi = unusedPiApi(),
            json = json,
        )
    }

    private fun unusedPiApi(): PiApi {
        val client = HttpClient(MockEngine { error("Unexpected Pi API request") }) {
            install(ContentNegotiation) { json(json) }
        }
        return PiApi(client, json)
    }

    private fun newApi(
        captured: MutableList<HttpRequestData> = mutableListOf(),
        mcpReplies: MutableList<McpReply> = mutableListOf(),
        connectStatus: HttpStatusCode = HttpStatusCode.OK,
        disconnectStatus: HttpStatusCode = HttpStatusCode.OK,
        fileContentResponse: (String) -> FileReply = { FileReply(HttpStatusCode.NotFound) },
    ): OpenCodeApi {
        val engine = MockEngine { request ->
            captured += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/mcp" -> {
                    when (val reply = mcpReplies.removeFirstOrNull() ?: McpReply.Ok("""{"servers":[]}""")) {
                        is McpReply.Ok -> respondJson(reply.body)
                        McpReply.Unsupported -> respond(status = HttpStatusCode.NotFound, content = ByteReadChannel(""))
                    }
                }

                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/connect") -> respond(
                    content = ByteReadChannel("connect reply"),
                    status = connectStatus,
                )

                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/disconnect") -> respond(
                    content = ByteReadChannel("disconnect reply"),
                    status = disconnectStatus,
                )

                request.method == HttpMethod.Get && request.url.encodedPath == "/path" -> respondJson(
                    """
                    {
                      "home": "/home/user",
                      "state": "/state",
                      "config": "/config",
                      "worktree": "$projectDir",
                      "directory": "$projectDir"
                    }
                    """.trimIndent(),
                )

                request.method == HttpMethod.Get && request.url.encodedPath == "/file/content" -> {
                    val path = request.url.parameters["path"].orEmpty()
                    val reply = fileContentResponse(path)
                    if (reply.status == HttpStatusCode.OK) {
                        respondJson(reply.content)
                    } else {
                        respond(status = reply.status, content = ByteReadChannel(reply.content))
                    }
                }

                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun snapshot(name: String, state: McpRuntimeState): McpRuntimeSnapshot = McpRuntimeSnapshot(
        servers = listOf(McpRuntimeStatus(name = name, state = state, errorMessage = null)),
        supportsRuntimeControl = true,
    )

    private fun runtimeBody(name: String, state: String): String =
        """{"servers":[{"name":"$name","state":"$state"}]}"""

    private fun List<HttpRequestData>.countPath(method: HttpMethod, path: String): Int = count {
        it.method == method && it.url.encodedPath == path
    }

    private fun List<HttpRequestData>.countMcpPosts(): Int = count {
        it.method == HttpMethod.Post && it.url.encodedPath.startsWith("/mcp")
    }

    private sealed interface McpReply {
        data class Ok(val body: String) : McpReply
        data object Unsupported : McpReply
    }

    private data class FileReply(
        val status: HttpStatusCode,
        val content: String = "",
    )

    private companion object {
        private const val DIRECTORY_HEADER = "x-opencode-directory"
        private const val projectDir = "/workspace/project"
        private val validMcpFileContent =
            """{"type":"file","content":"{\"mcpServers\":{\"filesystem\":{\"command\":\"npx\"}}}"}"""
    }
}
