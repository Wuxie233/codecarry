package dev.wuxie233.codecarry.ui.screens.codex

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.codex.CodexAppServerClient
import dev.wuxie233.codecarry.data.codex.CodexConnectionManager
import dev.wuxie233.codecarry.data.codex.CodexRpcTransport
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #30 list half: persisted unread marks project into the Codex thread list state. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexThreadListUnreadTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val httpClients = mutableListOf<HttpClient>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        httpClients.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `persisted unread marks flow into the list state for this server only`() = scope.runTest {
        val fixture = fixture()
        fixture.completeCatalog("alpha", "beta")
        fixture.repo.markConversationUnread(fixture.server.id, "alpha")

        fixture.vm.uiState.first {
            it.activeThreads.map { thread -> thread.id }.containsAll(listOf("alpha", "beta")) &&
                it.unreadThreadIds == setOf("alpha")
        }

        // Marks are server-scoped: another server's set never leaks into this list.
        fixture.repo.markConversationUnread("other-server", "alpha")
        fixture.vm.uiState.first { it.unreadThreadIds == setOf("alpha") }

        fixture.repo.markConversationRead(fixture.server.id, "alpha")
        fixture.vm.uiState.first { it.unreadThreadIds.isEmpty() }
        assertTrue(fixture.repo.unreadConversationIds("other-server").first().contains("alpha"))
    }

    private suspend fun TestScope.fixture(): Fixture {
        val http = HttpClient(MockEngine { error("OpenCode transport must not be used") }).also(httpClients::add)
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val repository = ServerRepository(store, OpenCodeApi(http, json), json)
        val server = repository.addServer("ws://codex.example.test", type = ServerType.CODEX)
        val repo = SessionListPreferencesRepository(store)
        val transport = FakeTransport()
        val manager = CodexConnectionManager(
            createClient = { CodexAppServerClient(transport = transport, json = json, scope = backgroundScope) },
            scope = backgroundScope,
        )
        val vm = CodexThreadListViewModel(
            SavedStateHandle(mapOf("serverId" to server.id)),
            repository,
            manager,
            CodexProjectPreferencesRepository(store),
            repo,
        ).also(viewModels::add)
        runCurrent()
        return Fixture(vm, transport, repo, server, this)
    }

    private inner class Fixture(
        val vm: CodexThreadListViewModel,
        val transport: FakeTransport,
        val repo: SessionListPreferencesRepository,
        val server: dev.wuxie233.codecarry.domain.model.ServerConfig,
        val testScope: TestScope,
    ) {
        suspend fun completeCatalog(vararg ids: String) {
            val request = transport.next("thread/list")
            val data = ids.joinToString(",") { id ->
                "{\"id\":\"$id\",\"cwd\":\"/workspace\"}"
            }
            transport.reply(request, """{"data":[$data],"nextCursor":null}""")
            testScope.runCurrent()
        }
    }

    private inner class FakeTransport : CodexRpcTransport {
        val incoming = Channel<String>(Channel.UNLIMITED)
        private val requests = Channel<JsonObject>(Channel.UNLIMITED)
        override suspend fun connect() = Unit
        override fun close() = Unit
        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
        override suspend fun send(text: String) {
            val request = json.parseToJsonElement(text).jsonObject
            val method = request["method"]?.jsonPrimitive?.content.orEmpty()
            if (method == "initialize") {
                reply(request, """{"userAgent":"test","codexHome":"/tmp/codex","platformFamily":"unix","platformOs":"linux"}""")
            } else if (method == "account/rateLimits/read") {
                reply(request, "{\"rateLimits\":{}}")
            } else if (request.containsKey("id")) {
                requests.send(request)
            }
        }

        suspend fun next(method: String): JsonObject = requests.receive().also {
            assertEquals(method, it["method"]?.jsonPrimitive?.content)
        }

        suspend fun reply(request: JsonObject, result: String) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", json.parseToJsonElement(result))
            }.toString())
        }
    }
}
