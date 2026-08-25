package dev.wuxie233.codecarry.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.api.OpenCodeFileNotFoundException
import dev.wuxie233.codecarry.data.api.ServerConnection
import dev.wuxie233.codecarry.domain.model.McpConfigLoadState
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val candidatePaths = listOf(
        "/workspace/project/.opencode/opencode.json",
        "/workspace/project/.opencode/config.json",
        "/workspace/project/opencode.json",
        "/home/user/.config/opencode/opencode.json",
        "/home/user/.config/opencode/config.json",
    )

    @Test
    fun legacyServerJsonWithoutTypeDefaultsToOpenCode() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmpFolder.newFile("server_repo_legacy_${System.nanoTime()}.preferences_pb") },
        )
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("servers")] =
                """
                [
                  {
                    "id": "legacy-1",
                    "url": "http://legacy.example:4096",
                    "username": "legacy-user",
                    "password": "legacy-pass",
                    "name": "Legacy Server",
                    "autoConnect": true,
                    "lastConnected": 123456789,
                    "isHealthy": true,
                    "futureField": "ignored"
                  }
                ]
                """.trimIndent()
        }
        val repository = ServerRepository(
            dataStore = dataStore,
            api = OpenCodeApi(HttpClient(OkHttp), json),
            json = json,
        )

        val server = repository.getAllServers().first().single()

        assertEquals("legacy-1", server.id)
        assertEquals(ServerType.OPENCODE, server.type)
        assertEquals("http://legacy.example:4096", server.url)
        assertEquals("legacy-user", server.username)
        assertEquals("legacy-pass", server.password)
        assertEquals(null, server.token)
        assertEquals("Legacy Server", server.name)
        assertEquals(true, server.autoConnect)
        assertEquals(123456789L, server.lastConnected)
        assertEquals(true, server.isHealthy)
    }

    @Test
    fun resolveMcpConfigLoadStateStopsAfterLoadedCandidateInPriorityOrder() = runTest {
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

        assertEquals(candidatePaths.take(4), captured.fileContentPaths())
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

        assertEquals(candidatePaths.take(2), captured.fileContentPaths())
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

    @Test
    fun PLACEHOLDER_NEXTopenCodeHealthStillUsesGlobalHealthEndpoint() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = healthRepository(captured) { request ->
            when (request.url.encodedPath) {
                "/global/health" -> respondJson("""{"healthy":true,"version":"test"}""")
                "/health" -> error("OpenCode health must not call Pi /health")
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val server = repository.addServer(
            url = "http://opencode.example.test:4096/",
            type = ServerType.OPENCODE,
            username = "opencode",
            password = "test-password",
        )

        val healthy = repository.checkServerHealth(server)
        val reread = repository.getServer(server.id)!!

        assertTrue(healthy)
        assertEquals(listOf("/global/health"), captured.map { it.url.encodedPath })
        assertTrue(reread.isHealthy)
        assertEquals("Basic b3BlbmNvZGU6dGVzdC1wYXNzd29yZA==", captured.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun resolveMcpConfigLoadStateProjectEmptyFallsThroughToGlobalLoaded() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val repository = newRepository(
            api = newApi(captured) { path ->
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
        val config = (result as McpConfigLoadState.Loaded).config
        assertEquals(candidatePaths[3], config.filePath)
        assertEquals(setOf("filesystem"), config.servers.keys)
    }

    @Test
    fun resolveMcpConfigLoadStateBlankProjectFallsThroughToGlobalLoaded() = runTest {
        val repository = resolverRepository(backgroundScope)
        val projectPath = "/proj/.opencode/opencode.json"
        val globalPath = "/home/u/.config/opencode/opencode.json"

        val result = repository.resolveMcpConfigLoadState(
            listOf(
                ServerRepository.McpConfigCandidateRead(projectPath, Result.success("")),
                ServerRepository.McpConfigCandidateRead(globalPath, Result.success(globalMcpConfigJson)),
            )
        )

        assertTrue(result is McpConfigLoadState.Loaded)
        assertEquals(globalPath, (result as McpConfigLoadState.Loaded).config.filePath)
    }

    @Test
    fun resolveMcpConfigLoadStateLoadedProjectIsTerminal() = runTest {
        val repository = resolverRepository(backgroundScope)
        val projectPath = "/proj/.opencode/opencode.json"
        val globalPath = "/home/u/.config/opencode/opencode.json"

        val result = repository.resolveMcpConfigLoadState(
            listOf(
                ServerRepository.McpConfigCandidateRead(
                    projectPath,
                    Result.success("""{"mcpServers":{"project":{"command":"project-cmd"}}}"""),
                ),
                ServerRepository.McpConfigCandidateRead(
                    globalPath,
                    Result.success("""{"mcpServers":{"global":{"command":"global-cmd"}}}"""),
                ),
            )
        )

        assertTrue(result is McpConfigLoadState.Loaded)
        val config = (result as McpConfigLoadState.Loaded).config
        assertEquals(projectPath, config.filePath)
        assertEquals(setOf("project"), config.servers.keys)
    }

    @Test
    fun resolveMcpConfigLoadStateAllEmptyReturnsFirstRememberedEmpty() = runTest {
        val repository = resolverRepository(backgroundScope)
        val projectPath = "/proj/.opencode/opencode.json"
        val globalPath = "/home/u/.config/opencode/opencode.json"

        val result = repository.resolveMcpConfigLoadState(
            listOf(
                ServerRepository.McpConfigCandidateRead(projectPath, Result.success("{}")),
                ServerRepository.McpConfigCandidateRead(globalPath, Result.success("""{"mcpServers":{}}""")),
            )
        )

        assertTrue(result is McpConfigLoadState.Empty)
        assertEquals(projectPath, (result as McpConfigLoadState.Empty).config.filePath)
    }

    @Test
    fun resolveMcpConfigLoadStateAllMissingReturnsNotFoundCheckedPaths() = runTest {
        val repository = resolverRepository(backgroundScope)
        val projectPath = "/proj/.opencode/opencode.json"
        val globalPath = "/home/u/.config/opencode/opencode.json"

        val result = repository.resolveMcpConfigLoadState(
            listOf(
                ServerRepository.McpConfigCandidateRead(
                    projectPath,
                    Result.failure(OpenCodeFileNotFoundException(projectPath)),
                ),
                ServerRepository.McpConfigCandidateRead(
                    globalPath,
                    Result.failure(OpenCodeFileNotFoundException(globalPath)),
                ),
            )
        )

        assertTrue(result is McpConfigLoadState.NotFound)
        assertEquals(
            listOf(projectPath, globalPath),
            (result as McpConfigLoadState.NotFound).checkedPaths,
        )
    }

    @Test
    fun resolveMcpConfigLoadStateHardReadErrorIsTerminal() = runTest {
        val repository = resolverRepository(backgroundScope)
        val projectPath = "/proj/.opencode/opencode.json"
        val globalPath = "/home/u/.config/opencode/opencode.json"

        val result = repository.resolveMcpConfigLoadState(
            listOf(
                ServerRepository.McpConfigCandidateRead(
                    projectPath,
                    Result.failure(IOException("permission denied")),
                ),
                ServerRepository.McpConfigCandidateRead(globalPath, Result.success(globalMcpConfigJson)),
            )
        )

        assertTrue(result is McpConfigLoadState.Error)
        assertEquals(projectPath, (result as McpConfigLoadState.Error).filePath)
    }

    @Test
    fun resolveMcpConfigLoadStateParseErrorIsTerminal() = runTest {
        val repository = resolverRepository(backgroundScope)
        val projectPath = "/proj/.opencode/opencode.json"
        val globalPath = "/home/u/.config/opencode/opencode.json"

        val result = repository.resolveMcpConfigLoadState(
            listOf(
                ServerRepository.McpConfigCandidateRead(projectPath, Result.success("{ this is not json ")),
                ServerRepository.McpConfigCandidateRead(globalPath, Result.success(globalMcpConfigJson)),
            )
        )

        assertTrue(result is McpConfigLoadState.Error)
        assertEquals(projectPath, (result as McpConfigLoadState.Error).filePath)
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

    private fun resolverRepository(scope: CoroutineScope): ServerRepository = ServerRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("server_repo_prefs_${System.nanoTime()}.preferences_pb") },
        ),
        api = OpenCodeApi(HttpClient(OkHttp), json),
        json = json,
    )

    private fun healthRepository(
        captured: MutableList<HttpRequestData>,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ServerRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            produceFile = { tmpFolder.newFile("server_repo_health_${System.nanoTime()}.preferences_pb") },
        )
        val engine = MockEngine { request ->
            captured += request
            handler(request)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return ServerRepository(
            dataStore = dataStore,
            api = OpenCodeApi(client, json),
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

                request.method == HttpMethod.Get && request.url.encodedPath == "/mcp" -> respond(
                    status = HttpStatusCode.NotFound,
                    content = ByteReadChannel(""),
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
        private const val globalMcpConfigJson = """{"mcpServers":{"filesystem":{"command":"npx"}}}"""

        private fun configFileContent(content: String): String = """{"type":"file","content":${Json.encodeToString(content)}}"""

        private val validMcpFileContent =
            """{"type":"file","content":"{\"mcpServers\":{\"filesystem\":{\"command\":\"npx\"}}}"}"""
    }

    @Test
    fun dropsArchivedBackendTypesOnReadAndDoesNotRestoreThem() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val raw = """
        [
          {"id":"keep","type":"OPENCODE","url":"http://oc.example","username":"opencode"},
          {"id":"codex","type":"CODEX","url":"wss://codex.example"},
          {"id":"pi","type":"PI_ROUNDTABLE","url":"https://pi.example","token":"t"},
          {"id":"stack","type":"PI_STACK","url":"https://stack.example/control"}
        ]
        """.trimIndent()
        val (kept, dropped) = decodePersistedServers(json, raw)
        assertTrue(dropped)
        assertEquals(listOf("keep"), kept.map { it.id })
        assertEquals(listOf(ServerType.OPENCODE), kept.map { it.type })
    }
}
