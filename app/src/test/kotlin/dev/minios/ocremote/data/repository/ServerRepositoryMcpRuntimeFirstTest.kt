package dev.minios.ocremote.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpSource
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryMcpRuntimeFirstTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private val candidatePaths = listOf(
        "/workspace/project/.opencode/opencode.json",
        "/workspace/project/.opencode/config.json",
        "/workspace/project/opencode.json",
        "/home/user/.config/opencode/opencode.json",
        "/home/user/.config/opencode/config.json",
    )

    @Test
    fun runtimeMcpStatusWinsOverEmptyOrMissingProjectConfig() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(captured, runtimeStatus = RuntimeReply(HttpStatusCode.OK, sevenServerRuntimeFixture)) { path ->
                when (path) {
                    candidatePaths[0] -> FileReply(HttpStatusCode.OK, configFileContent("{}"))
                    else -> FileReply(HttpStatusCode.NotFound)
                }
            },
            scope = backgroundScope,
        )

        val result = repository.readMcpConfigState(
            conn = ServerConnection.from("http://example.test:4096"),
            projectDir = "/workspace/project",
        )

        assertEquals(listOf("/mcp"), captured.requestPaths())
        assertTrue(result is McpConfigLoadState.Loaded)
        val loaded = result as McpConfigLoadState.Loaded
        assertEquals(McpSource.Runtime, loaded.source)
        assertEquals("<runtime>", loaded.config.filePath)
        assertEquals(expectedRuntimeNames, loaded.config.servers.keys)
        assertEquals(7, loaded.config.servers.size)
    }

    @Test
    fun runtimeUnsupportedFallsThroughToFileFallbackUnchanged() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(captured, runtimeStatus = RuntimeReply(HttpStatusCode.NotFound)) { path ->
                when (path) {
                    candidatePaths[0] -> FileReply(HttpStatusCode.OK, configFileContent("{}"))
                    candidatePaths[3] -> FileReply(HttpStatusCode.OK, configFileContent(globalMcpConfigJson))
                    else -> FileReply(HttpStatusCode.NotFound)
                }
            },
            scope = backgroundScope,
        )

        val result = repository.readMcpConfigState(
            conn = ServerConnection.from("http://example.test:4096"),
            projectDir = "/workspace/project",
        )

        assertEquals(listOf("/mcp", "/path") + candidatePaths.take(4).map { "/file/content" }, captured.requestPaths())
        assertEquals(candidatePaths.take(4), captured.fileContentPaths())
        assertTrue(result is McpConfigLoadState.Loaded)
        val loaded = result as McpConfigLoadState.Loaded
        assertEquals(McpSource.File, loaded.source)
        assertEquals(candidatePaths[3], loaded.config.filePath)
        assertEquals(setOf("filesystem"), loaded.config.servers.keys)
    }

    @Test
    fun runtimeFailedWithEmptyOrMissingFallbackYieldsRuntimeUnavailable() = runTest {
        val missingFallback = readState(
            runtimeStatus = RuntimeReply(HttpStatusCode.Unauthorized, "Authorization required"),
        ) { FileReply(HttpStatusCode.NotFound) }

        assertTrue(missingFallback is McpConfigLoadState.RuntimeUnavailable)
        val missingState = (missingFallback as McpConfigLoadState.RuntimeUnavailable).fallback
        assertTrue(missingState is McpConfigLoadState.NotFound)
        assertEquals(candidatePaths, (missingState as McpConfigLoadState.NotFound).checkedPaths)

        val emptyFallback = readState(
            runtimeStatus = RuntimeReply(HttpStatusCode.Unauthorized, "Authorization required"),
        ) { path ->
            when (path) {
                candidatePaths[0] -> FileReply(HttpStatusCode.OK, configFileContent("{}"))
                else -> FileReply(HttpStatusCode.NotFound)
            }
        }

        assertTrue(emptyFallback is McpConfigLoadState.RuntimeUnavailable)
        val emptyState = (emptyFallback as McpConfigLoadState.RuntimeUnavailable).fallback
        assertTrue(emptyState is McpConfigLoadState.Empty)
        assertEquals(candidatePaths[0], (emptyState as McpConfigLoadState.Empty).config.filePath)
    }

    @Test
    fun runtimeSuccessEmptyFallsThroughToFileLoaded() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(captured, runtimeStatus = RuntimeReply(HttpStatusCode.OK, "{}")) { path ->
                when (path) {
                    candidatePaths[0] -> FileReply(HttpStatusCode.OK, configFileContent("{}"))
                    candidatePaths[3] -> FileReply(HttpStatusCode.OK, configFileContent(globalMcpConfigJson))
                    else -> FileReply(HttpStatusCode.NotFound)
                }
            },
            scope = backgroundScope,
        )

        val result = repository.readMcpConfigState(
            conn = ServerConnection.from("http://example.test:4096"),
            projectDir = "/workspace/project",
        )

        assertEquals(candidatePaths.take(4), captured.fileContentPaths())
        assertTrue(result is McpConfigLoadState.Loaded)
        val loaded = result as McpConfigLoadState.Loaded
        assertEquals(McpSource.File, loaded.source)
        assertEquals(candidatePaths[3], loaded.config.filePath)
        assertEquals(setOf("filesystem"), loaded.config.servers.keys)
    }

    private suspend fun readState(
        runtimeStatus: RuntimeReply,
        fileContentResponse: (String) -> FileReply,
    ): McpConfigLoadState {
        val repository = newRepository(
            api = newApi(mutableListOf(), runtimeStatus, fileContentResponse),
            scope = kotlinx.coroutines.test.TestScope(),
        )
        return repository.readMcpConfigState(
            conn = ServerConnection.from("http://example.test:4096"),
            projectDir = "/workspace/project",
        )
    }

    private fun newRepository(api: OpenCodeApi, scope: CoroutineScope): ServerRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("server_repo_runtime_first_${System.nanoTime()}.preferences_pb") },
        )

        return ServerRepository(
            dataStore = dataStore,
            api = api,
            json = json,
        )
    }

    private fun newApi(
        captured: MutableList<HttpRequestData>,
        runtimeStatus: RuntimeReply,
        fileContentResponse: (String) -> FileReply,
    ): OpenCodeApi {
        val engine = MockEngine { request ->
            captured += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/mcp" -> {
                    if (runtimeStatus.status == HttpStatusCode.OK) {
                        respondJson(runtimeStatus.content)
                    } else {
                        respond(status = runtimeStatus.status, content = ByteReadChannel(runtimeStatus.content))
                    }
                }

                request.method == HttpMethod.Get && request.url.encodedPath == "/path" -> respondJson(
                    """
                    {
                      "home": "/home/user",
                      "state": "/state",
                      "config": "/config",
                      "worktree": "/workspace/project",
                      "directory": "/workspace/project"
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

    private fun List<HttpRequestData>.requestPaths(): List<String> = map { it.url.encodedPath }

    private fun List<HttpRequestData>.fileContentPaths(): List<String> = filter {
        it.method == HttpMethod.Get && it.url.encodedPath == "/file/content"
    }.map { request ->
        request.url.parameters["path"].orEmpty()
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private data class RuntimeReply(
        val status: HttpStatusCode,
        val content: String = "",
    )

    private data class FileReply(
        val status: HttpStatusCode,
        val content: String = "",
    )

    private companion object {
        private val expectedRuntimeNames = setOf(
            "aceTool",
            "autoinfo",
            "exa",
            "fetch",
            "github",
            "playwright",
            "stitch",
        )

        private const val globalMcpConfigJson = """{"mcp":{"filesystem":{"command":"npx"}}}"""

        private val sevenServerRuntimeFixture: String =
            ServerRepositoryMcpRuntimeFirstTest::class.java
                .getResource("/mcp/runtime-status-seven-servers.json")!!
                .readText()

        private fun configFileContent(content: String): String =
            """{"type":"file","content":${Json.encodeToString(content)}}"""
    }
}
