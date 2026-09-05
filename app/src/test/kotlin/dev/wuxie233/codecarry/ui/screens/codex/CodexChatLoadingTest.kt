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
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexChatLoadingTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private val viewModels = mutableListOf<CodexChatViewModel>()
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
    fun `resume history is visible without a disk backed read`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        assertEquals("child", fixture.vm.uiState.value.thread?.id)
        assertEquals("hello", fixture.vm.uiState.value.thread?.turns?.single()?.items?.single()?.text)
        assertFalse(fixture.vm.uiState.value.isLoading)
        fixture.completeMetadata()
        assertFalse(fixture.transport.methods.contains("thread/read"))
    }

    @Test
    fun `completion during delayed goal metadata cannot restore an active turn`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        assertEquals("turn-1", fixture.vm.uiState.value.activeTurnId)
        fixture.completeTurn()
        runCurrent()
        assertNull(fixture.vm.uiState.value.activeTurnId)
        fixture.transport.incoming.send(
            """{"method":"error","params":{"threadId":"child","error":{"message":"turn failed remotely"}}}""",
        )
        runCurrent()
        assertEquals("turn failed remotely", fixture.vm.uiState.value.error)
        fixture.completeMetadata()
        assertNull(fixture.vm.uiState.value.activeTurnId)
        assertEquals("completed", fixture.vm.uiState.value.thread?.turns?.single()?.status)
        assertEquals("turn failed remotely", fixture.vm.uiState.value.error)
    }

    @Test
    fun `completion during delayed model metadata cannot restore an active turn`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.transport.replyNext("thread/goal/get", "{\"goal\":null}")
        runCurrent()
        fixture.completeTurn()
        runCurrent()
        assertNull(fixture.vm.uiState.value.activeTurnId)
        fixture.transport.replyNext("model/list", "{\"data\":[]}")
        runCurrent()
        assertNull(fixture.vm.uiState.value.activeTurnId)
        assertEquals("completed", fixture.vm.uiState.value.thread?.turns?.single()?.status)
    }

    @Test
    fun `unrelated thread notifications cannot erase resume failure`() = scope.runTest {
        val fixture = fixture()
        val request = fixture.transport.next("thread/resume")
        fixture.transport.incoming.send(buildJsonObject {
            put("id", request.getValue("id"))
            put("error", buildJsonObject {
                put("code", -32000)
                put("message", "parent thread is not loaded")
            })
        }.toString())
        runCurrent()
        val error = fixture.vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error.orEmpty().contains("parent thread is not loaded"))
        fixture.transport.incoming.send(
            """{"method":"thread/started","params":{"thread":{"id":"unrelated","name":"Other"}}}""",
        )
        runCurrent()
        assertEquals(error, fixture.vm.uiState.value.error)
        assertFalse(fixture.vm.uiState.value.isLoading)
        assertNull(fixture.vm.uiState.value.thread)
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
        val transport = FakeTransport()
        val manager = CodexConnectionManager(
            createClient = { CodexAppServerClient(transport = transport, json = json, scope = backgroundScope) },
            scope = backgroundScope,
        )
        val vm = CodexChatViewModel(
            SavedStateHandle(mapOf("serverId" to server.id, "threadId" to "child")),
            manager,
            repository,
        ).also(viewModels::add)
        runCurrent()
        return Fixture(vm, transport, this)
    }

    private inner class Fixture(
        val vm: CodexChatViewModel,
        val transport: FakeTransport,
        val testScope: TestScope,
    ) {
        suspend fun resume() {
            val request = transport.next("thread/resume")
            assertFalse(request["params"]?.jsonObject?.get("excludeTurns")?.jsonPrimitive?.content == "true")
            transport.reply(request, """{
                "thread":{"id":"child","turns":[{"id":"turn-1","status":"inProgress","items":[
                    {"id":"message-1","type":"agentMessage","text":"hello"}
                ]}]},
                "model":"gpt-5","modelProvider":"openai","cwd":"/workspace",
                "approvalPolicy":"on-request","approvalsReviewer":"user","sandbox":"workspace-write"
            }""")
        }

        suspend fun completeTurn() {
            transport.incoming.send(
                """{"method":"turn/completed","params":{"threadId":"child","turn":{"id":"turn-1","status":"completed","items":[],"itemsView":"notLoaded"}}}""",
            )
        }

        suspend fun completeMetadata() {
            transport.replyNext("thread/goal/get", "{\"goal\":null}")
            testScope.runCurrent()
            transport.replyNext("model/list", "{\"data\":[]}")
            testScope.runCurrent()
        }
    }

    private inner class FakeTransport : CodexRpcTransport {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val methods = mutableListOf<String>()
        private val requests = Channel<JsonObject>(Channel.UNLIMITED)
        override suspend fun connect() = Unit
        override fun close() = Unit
        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
        override suspend fun send(text: String) {
            val request = json.parseToJsonElement(text).jsonObject
            val method = request["method"]?.jsonPrimitive?.content.orEmpty()
            methods += method
            if (method == "initialize") {
                reply(request, """{"userAgent":"test","codexHome":"/tmp/codex","platformFamily":"unix","platformOs":"linux"}""")
            } else if (request.containsKey("id")) {
                requests.send(request)
            }
        }

        suspend fun next(method: String): JsonObject = requests.receive().also {
            assertEquals(method, it["method"]?.jsonPrimitive?.content)
        }

        suspend fun replyNext(method: String, result: String) = reply(next(method), result)

        suspend fun reply(request: JsonObject, result: String) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", json.parseToJsonElement(result))
            }.toString())
        }
    }
}
