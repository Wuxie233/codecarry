package dev.wuxie233.codecarry.data.repository

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.wuxie233.codecarry.data.api.OpenCodeApi
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryMcpRuntimeAcceptanceTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val conn = ServerConnection.from("http://example.test:4096")

    @Test
    fun `AC2_clicking_connected_disconnects_then_refetches`() = runTest {
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
        assertEquals(1, captured.countPath(HttpMethod.Get, "/mcp"))
        assertEquals(HttpMethod.Post, captured[0].method)
        assertEquals(HttpMethod.Get, captured[1].method)
    }

    @Test
    fun `AC4_clicking_disabled_or_failed_calls_connect`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(
                    McpReply.Ok(runtimeBody("fs", "connected")),
                    McpReply.Ok(runtimeBody("fs", "connected")),
                ),
            ),
            scope = backgroundScope,
        )

        val disabledResult = repository.toggleMcpRuntime(
            conn = conn,
            projectDir = projectDir,
            name = "fs",
            previous = snapshot("fs", McpRuntimeState.DISABLED),
        )
        val failedResult = repository.toggleMcpRuntime(
            conn = conn,
            projectDir = projectDir,
            name = "fs",
            previous = snapshot("fs", McpRuntimeState.FAILED),
        )

        assertTrue(disabledResult.isSuccess)
        assertTrue(failedResult.isSuccess)
        assertEquals(2, captured.countPath(HttpMethod.Post, "/mcp/fs/connect"))
        assertEquals(0, captured.countPath(HttpMethod.Post, "/mcp/fs/disconnect"))
    }

    @Test
    fun `AC5_auth_required_states_never_call_connect`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(api = newApi(captured), scope = backgroundScope)

        val needsAuth = repository.toggleMcpRuntime(
            conn = conn,
            projectDir = projectDir,
            name = "fs",
            previous = snapshot("fs", McpRuntimeState.NEEDS_AUTH),
        )
        val needsClientRegistration = repository.toggleMcpRuntime(
            conn = conn,
            projectDir = projectDir,
            name = "fs",
            previous = snapshot("fs", McpRuntimeState.NEEDS_CLIENT_REGISTRATION),
        )

        assertTrue(needsAuth.isFailure)
        assertTrue(needsAuth.exceptionOrNull() is McpAuthRequiredException)
        assertTrue(needsClientRegistration.isFailure)
        assertTrue(needsClientRegistration.exceptionOrNull() is McpAuthRequiredException)
        assertEquals(0, captured.countPath(HttpMethod.Post, "/mcp/fs/connect"))
        assertEquals(0, captured.countMcpPosts())
    }

    @Test
    fun `AC6_status_refetched_after_successful_toggle`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(
                    McpReply.Ok(
                        """
                        {
                          "servers": [
                            {"name":"fs","state":"connected"},
                            {"name":"db","state":"failed","error":"boom"}
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
            scope = backgroundScope,
        )

        val result = repository.toggleMcpRuntime(
            conn = conn,
            projectDir = projectDir,
            name = "fs",
            previous = snapshot("fs", McpRuntimeState.DISABLED),
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                McpRuntimeStatus("fs", McpRuntimeState.CONNECTED, null),
                McpRuntimeStatus("db", McpRuntimeState.FAILED, "boom"),
            ),
            result.getOrThrow().servers,
        )
        assertEquals(2, captured.size)
        assertEquals(HttpMethod.Post, captured[0].method)
        assertEquals("/mcp/fs/connect", captured[0].url.encodedPath)
        assertEquals(HttpMethod.Get, captured[1].method)
        assertEquals("/mcp", captured[1].url.encodedPath)
    }

    @Test
    fun `AC8_project_directory_forwarded_on_every_call`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
                mcpReplies = mutableListOf(
                    McpReply.Ok(runtimeBody("fs", "connected")),
                    McpReply.Ok(runtimeBody("fs", "disabled")),
                    McpReply.Ok(runtimeBody("fs", "connected")),
                    McpReply.Unsupported,
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
        repository.toggleMcpRuntime(conn, projectDir, "fs", snapshot("fs", McpRuntimeState.CONNECTED)).getOrThrow()
        repository.toggleMcpRuntime(conn, projectDir, "fs", snapshot("fs", McpRuntimeState.DISABLED)).getOrThrow()
        repository.loadMcpRuntime(conn, projectDir).getOrThrow()

        val encodedDirectory = Uri.encode(projectDir)
        assertTrue(captured.isNotEmpty())
        captured.forEach { request ->
            assertEquals(encodedDirectory, request.headers[DIRECTORY_HEADER])
        }
    }

    @Test
    fun `AC9_old_server_falls_back_to_read_only_snapshot`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(
                captured = captured,
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
        assertEquals(0, captured.countMcpPosts())
    }

    @Test
    fun `AC10_no_command_args_or_secrets_leak_to_runtime_status`() = runTest {
        val longSecretError = "token=abc123 connection refused at /home/user/.opencode/mcp.sock " + "x".repeat(250)
        val repository = newRepository(
            api = newApi(
                mcpReplies = mutableListOf(
                    McpReply.Ok(
                        """
                        {
                          "servers": [
                            {
                              "name": "fs",
                              "state": "failed",
                              "error": "$longSecretError",
                              "command": "npx",
                              "args": ["--token", "abc123"],
                              "headers": {"Authorization": "Bearer abc123"},
                              "env": {"TOKEN": "abc123"},
                              "oauth": {"clientSecret": "abc123"}
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
            scope = backgroundScope,
        )

        val result = repository.loadMcpRuntime(conn, projectDir)

        assertTrue(result.isSuccess)
        val errorMessage = result.getOrThrow().servers.single().errorMessage.orEmpty()
        assertTrue(errorMessage.contains("token=<redacted>"))
        assertFalse(errorMessage.contains("abc123"))
        assertFalse(errorMessage.contains("npx"))
        assertFalse(errorMessage.contains("--token"))
        assertFalse(errorMessage.contains("Authorization"))
        assertTrue(errorMessage.length <= 200)
    }

    private fun newRepository(api: OpenCodeApi, scope: CoroutineScope): ServerRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("server_repo_mcp_runtime_acceptance_${System.nanoTime()}.preferences_pb") },
        )

        return ServerRepository(
            dataStore = dataStore,
            api = api,
            json = json,
        )
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
