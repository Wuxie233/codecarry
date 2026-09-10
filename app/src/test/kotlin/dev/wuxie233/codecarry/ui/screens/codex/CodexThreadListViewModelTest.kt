package dev.wuxie233.codecarry.ui.screens.codex

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.codex.CodexAppServerClient
import dev.wuxie233.codecarry.data.codex.CodexConnectionManager
import dev.wuxie233.codecarry.data.codex.CodexRpcTransport
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexThreadListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private val owners = mutableListOf<ViewModelStore>()
    private val managers = mutableListOf<CodexConnectionManager>()
    private val httpClients = mutableListOf<HttpClient>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        owners.forEach(ViewModelStore::clear)
        managers.forEach(CodexConnectionManager::closeForTest)
        httpClients.forEach(HttpClient::close)
        Dispatchers.resetMain()
    }

    @Test fun `socket reconnect automatically reloads the catalog and preserves it while loading`() = scope.runTest {
        val f = fixture()
        f.completeCatalog(f.transport, "old")
        val connection = f.manager.get(f.server.id)
        f.transport.disconnect()
        runCurrent()
        assertEquals(listOf("old"), f.ids())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(connection, f.manager.get(f.server.id))
        assertEquals(listOf("old"), f.ids())
        f.completeCatalog(f.transport, "old", "new")
        assertEquals(setOf("old", "new"), f.ids().toSet())
        assertFalse(f.vm.uiState.value.isLoading)
        assertNull(f.vm.uiState.value.error)
    }

    @Test fun `replacement connection automatically rebinds without discarding the old catalog`() = scope.runTest {
        val f = fixture()
        f.completeCatalog(f.transport, "old")
        f.manager.disconnect(f.server.id)
        runCurrent()
        assertEquals(listOf("old"), f.ids())
        assertEquals(1, f.transports.size)
        val reconnect = async { f.manager.connect(f.server) }
        runCurrent()
        reconnect.await()
        assertEquals(2, f.transports.size)
        val replacement = f.transports.last()
        assertEquals(listOf("old"), f.ids())
        f.completeCatalog(replacement, "old", "replacement")
        replacement.incoming.send(
            """{"method":"thread/name/updated","params":{"threadId":"replacement","threadName":"New connection event"}}""",
        )
        runCurrent()
        assertEquals(setOf("old", "replacement"), f.ids().toSet())
        assertEquals("New connection event", f.vm.uiState.value.activeThreads.single { it.id == "replacement" }.name)
        assertFalse(f.vm.uiState.value.isLoading)
    }

    @Test fun `interrupted pagination cannot publish a partial or late old generation catalog`() = scope.runTest {
        val f = fixture()
        f.completeCatalog(f.transport, "old")
        f.vm.refresh()
        runCurrent()
        f.transport.reply(f.transport.nextList(), """{"data":[{"id":"partial"}],"nextCursor":"page-2"}""")
        runCurrent()
        val delayed = f.transport.nextList()
        assertEquals("page-2", delayed["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content)
        f.transport.disconnect()
        runCurrent()
        assertEquals(listOf("old"), f.ids())
        advanceTimeBy(1)
        runCurrent()
        f.completeCatalog(f.transport, "current")
        f.transport.reply(delayed, """{"data":[{"id":"stale"}],"nextCursor":null}""")
        runCurrent()
        assertEquals(listOf("current"), f.ids())
        assertFalse(f.vm.uiState.value.isLoading)
        assertNull(f.vm.uiState.value.error)
    }

    private suspend fun TestScope.fixture(): Fixture {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val http = HttpClient(MockEngine { error("OpenCode transport must not be used") }).also(httpClients::add)
        val repository = ServerRepository(store, OpenCodeApi(http, json), json)
        val server = repository.addServer("ws://codex.example.test", type = ServerType.CODEX)
        val transports = mutableListOf<FakeTransport>()
        val manager = CodexConnectionManager(
            createClient = {
                CodexAppServerClient(FakeTransport().also(transports::add), json, scope = backgroundScope)
            },
            scope = backgroundScope,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        ).also(managers::add)
        val vm = CodexThreadListViewModel(
            SavedStateHandle(mapOf("serverId" to server.id)), repository, manager,
            CodexProjectPreferencesRepository(store),
        )
        ViewModelStore().also { it.put("list", vm); owners += it }
        runCurrent()
        return Fixture(vm, manager, server, transports, this)
    }

    private inner class Fixture(
        val vm: CodexThreadListViewModel,
        val manager: CodexConnectionManager,
        val server: ServerConfig,
        val transports: MutableList<FakeTransport>,
        val testScope: TestScope,
    ) {
        val transport get() = transports.first()
        fun ids() = vm.uiState.value.activeThreads.map { it.id }
        suspend fun completeCatalog(transport: FakeTransport, vararg ids: String) {
            val active = transport.nextList()
            assertEquals("false", active["params"]?.jsonObject?.get("archived")?.jsonPrimitive?.content)
            transport.reply(active, """{"data":[${ids.joinToString { """{"id":"$it"}""" }}],"nextCursor":null}""")
            testScope.runCurrent()
            val archived = transport.nextList()
            assertEquals("true", archived["params"]?.jsonObject?.get("archived")?.jsonPrimitive?.content)
            transport.reply(archived, """{"data":[],"nextCursor":null}""")
            testScope.runCurrent()
        }
    }

    private inner class FakeTransport : CodexRpcTransport {
        var incoming = Channel<String>(Channel.UNLIMITED)
            private set
        private val requests = Channel<JsonObject>(Channel.UNLIMITED)
        private var disconnected = false
        override suspend fun connect() {
            if (disconnected) incoming = Channel(Channel.UNLIMITED)
            disconnected = false
        }
        override fun close() = Unit
        fun disconnect() { disconnected = true; incoming.close() }
        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
        override suspend fun send(text: String) {
            val request = json.parseToJsonElement(text).jsonObject
            when (request["method"]?.jsonPrimitive?.content) {
                "initialize" -> reply(request, """{"userAgent":"test","codexHome":"/tmp/codex","platformFamily":"unix","platformOs":"linux"}""")
                "initialized" -> Unit
                else -> requests.send(request)
            }
        }
        fun nextList(): JsonObject = checkNotNull(requests.tryReceive().getOrNull()) {
            "Expected automatic thread/list request"
        }.also { assertEquals("thread/list", it["method"]?.jsonPrimitive?.content) }
        suspend fun reply(request: JsonObject, result: String) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", json.parseToJsonElement(result))
            }.toString())
        }
    }
}
