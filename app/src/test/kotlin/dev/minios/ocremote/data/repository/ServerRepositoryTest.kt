package dev.minios.ocremote.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.McpConfigLoadState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {

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
    fun resolveMcpConfigLoadStateProbesAllFiveCandidatesInPriorityOrder() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(captured) { path ->
                when (path) {
                    candidatePaths[3] -> FileReply(HttpStatusCode.OK, validMcpFileContent)
                    else -> FileReply(HttpStatusCode.NotFound)
                }
            },
            scope = backgroundScope,
        )

        val result = repository.readMcpConfigState(
            conn = ServerConnection.from("http://example.test:4096"),
            projectDir = "/workspace/project",
        )

        assertEquals(candidatePaths, captured.fileContentPaths())
        assertTrue(result is McpConfigLoadState.Loaded)
        assertEquals(
            candidatePaths[3],
            (result as McpConfigLoadState.Loaded).config.filePath,
        )
    }

    @Test
    fun resolveMcpConfigLoadStateReturnsNotFoundWhenAllFiveAreMissing() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(captured) {
                FileReply(HttpStatusCode.NotFound)
            },
            scope = backgroundScope,
        )

        val result = repository.readMcpConfigState(
            conn = ServerConnection.from("http://example.test:4096"),
            projectDir = "/workspace/project",
        )

        assertEquals(candidatePaths, captured.fileContentPaths())
        assertTrue(result is McpConfigLoadState.NotFound)
        assertEquals(5, (result as McpConfigLoadState.NotFound).checkedPaths.size)
        assertEquals(candidatePaths, result.checkedPaths)
    }

    @Test
    fun resolveMcpConfigLoadStateReturnsErrorOnPermissionFailure() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(captured) { path ->
                when (path) {
                    candidatePaths[1] -> FileReply(HttpStatusCode.Forbidden, "forbidden")

                    else -> FileReply(HttpStatusCode.NotFound)
                }
            },
            scope = backgroundScope,
        )

        val result = repository.readMcpConfigState(
            conn = ServerConnection.from("http://example.test:4096"),
            projectDir = "/workspace/project",
        )

        assertEquals(candidatePaths, captured.fileContentPaths())
        assertTrue(result is McpConfigLoadState.Error)
        assertEquals(
            candidatePaths[1],
            (result as McpConfigLoadState.Error).filePath,
        )
    }

    @Test
    fun resolveMcpConfigLoadStateReturnsErrorWhenConfigReadFails() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmpFolder.newFile("server_repo_prefs.preferences_pb") },
        )

        val repository = ServerRepository(
            dataStore = dataStore,
            api = OpenCodeApi(HttpClient(OkHttp), json),
            json = json,
        )

        val result = repository.resolveMcpConfigLoadState(
            listOf(
                ServerRepository.McpConfigCandidateRead(
                    path = "/workspace/project/.opencode/config.json",
                    readResult = Result.failure(IllegalStateException("boom")),
                )
            )
        )

        assertTrue(result is McpConfigLoadState.Error)
        assertEquals(
            "/workspace/project/.opencode/config.json",
            (result as McpConfigLoadState.Error).filePath,
        )
    }

    private fun newRepository(api: OpenCodeApi, scope: CoroutineScope): ServerRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("server_repo_prefs_${System.nanoTime()}.preferences_pb") },
        )

        return ServerRepository(
            dataStore = dataStore,
            api = api,
            json = json,
        )
    }

    private fun newApi(
        captured: MutableList<HttpRequestData>,
        fileContentResponse: (String) -> FileReply,
    ): OpenCodeApi {
        val engine = MockEngine { request ->
            captured += request
            when {
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

    private data class FileReply(
        val status: HttpStatusCode,
        val content: String = "",
    )

    private companion object {
        private val validMcpFileContent =
            """{"type":"file","content":"{\"mcpServers\":{\"filesystem\":{\"command\":\"npx\"}}}"}"""
    }
}
