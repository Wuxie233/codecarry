package dev.wuxie233.codecarry.ui.screens.codex

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.codex.CodexAppServerClient
import dev.wuxie233.codecarry.data.codex.CodexConnectionManager
import dev.wuxie233.codecarry.data.codex.CodexMemoryMode
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

/**
 * Issues #37/#38/#40: goal and model catalog expose independent
 * loading/empty/error/loaded states with retry that never gate history; the memory
 * mode is an explicit generation-fenced receipt (the protocol has no read RPC); the
 * thread policy display comes from the authoritative resume receipt and is cleared
 * across connection boundaries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexChatMetadataStateTest {
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
    fun `goal failure is independently retryable and never blocks history or models`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        assertEquals(CodexMetadataLoadState.Loading, fixture.vm.uiState.value.goalState)
        fixture.transport.replyErrorNext("thread/goal/get", "goal backend down")
        fixture.transport.replyNext("model/list", MODELS)
        runCurrent()
        val failedState = fixture.vm.uiState.value
        assertTrue(failedState.goalState is CodexMetadataLoadState.Failed)
        assertTrue(failedState.goalState.failedMessage().contains("goal backend down"))
        assertNull(failedState.goal)
        assertTrue(failedState.modelsState is CodexMetadataLoadState.Loaded)
        assertEquals("gpt-5", failedState.selectedModel?.model)
        assertFalse(failedState.isLoading)
        assertTrue(failedState.isConnected)
        // A metadata failure never surfaces as the chat-level operation error.
        assertNull(failedState.error)

        fixture.vm.retryGoalLoad()
        runCurrent()
        fixture.transport.replyNext(
            "thread/goal/get",
            """{"goal":{"threadId":"child","objective":"ship the slice","status":"active"}}""",
        )
        runCurrent()
        val goalState = fixture.vm.uiState.value.goalState
        assertTrue(goalState is CodexMetadataLoadState.Loaded)
        assertEquals("ship the slice", goalState.loadedValue()?.objective)
    }

    @Test
    fun `models failure is independently retryable while goal and sending stay usable`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.transport.replyNext("thread/goal/get", "{\"goal\":null}")
        fixture.transport.replyErrorNext("model/list", "catalog down")
        runCurrent()
        val state = fixture.vm.uiState.value
        val failedModels = state.modelsState
        assertTrue(failedModels is CodexMetadataLoadState.Failed)
        assertTrue(failedModels.failedMessage().contains("catalog down"))
        assertTrue(state.models.isEmpty())
        assertTrue(state.goalState is CodexMetadataLoadState.Loaded)
        assertNull(state.goal)
        assertTrue(state.isConnected)
        assertNull(state.error)

        fixture.vm.retryModelsLoad()
        runCurrent()
        fixture.transport.replyNext("model/list", MODELS)
        runCurrent()
        val loaded = fixture.vm.uiState.value.modelsState
        assertTrue(loaded is CodexMetadataLoadState.Loaded)
        assertEquals("gpt-5", fixture.vm.uiState.value.selectedModel?.model)
    }

    @Test
    fun `disconnect marks metadata stale and clears policy and memory receipt`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        fixture.vm.setMemoryMode(CodexMemoryMode.DISABLED)
        runCurrent()
        fixture.transport.replyNext("thread/memoryMode/set", "{}")
        runCurrent()
        val receipt = fixture.vm.uiState.value.memoryModeReceipt
        assertNotNull(receipt)
        assertEquals(CodexMemoryMode.DISABLED, receipt?.mode)
        val policy = fixture.vm.uiState.value.threadPolicy
        assertNotNull(policy)
        assertEquals("on-request", policy?.approvalPolicy)
        assertEquals("workspaceWrite", policy?.sandboxMode)
        assertEquals(true, policy?.sandboxNetworkEnabled)
        assertEquals(listOf("/workspace"), policy?.sandboxWritableRoots)

        // A socket boundary invalidates connection-scoped receipts and keeps only
        // visibly stale metadata; the policy never shows an outdated value.
        fixture.transport.incoming.close()
        runCurrent()
        val disconnected = fixture.vm.uiState.value
        assertFalse(disconnected.isConnected)
        assertNull(disconnected.memoryModeReceipt)
        assertNull(disconnected.threadPolicy)
        val staleGoal = disconnected.goalState
        assertTrue(staleGoal is CodexMetadataLoadState.Loaded && staleGoal.stale)
        val staleModels = disconnected.modelsState
        assertTrue(staleModels is CodexMetadataLoadState.Loaded && staleModels.stale)
    }

    @Test
    fun `memory receipt survives a same-connection refresh`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        fixture.vm.setMemoryMode(CodexMemoryMode.ENABLED)
        runCurrent()
        fixture.transport.replyNext("thread/memoryMode/set", "{}")
        runCurrent()
        val before = fixture.vm.uiState.value.memoryModeReceipt
        assertEquals(CodexMemoryMode.ENABLED, before?.mode)

        fixture.vm.connectAndLoad()
        runCurrent()
        assertEquals(before, fixture.vm.uiState.value.memoryModeReceipt)
        fixture.transport.replyNext("thread/goal/get", "{\"goal\":null}")
        runCurrent()
    }

    private suspend fun TestScope.fixture(): Fixture {        val http = HttpClient(MockEngine { error("OpenCode transport must not be used") }).also(httpClients::add)
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
            dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository(store),
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
            transport.reply(
                request,
                """{
                    "thread":{"id":"child","turns":[{"id":"turn-1","status":"inProgress","items":[
                        {"id":"message-1","type":"agentMessage","text":"hello"}
                    ]}]},
                    "model":"gpt-5","modelProvider":"openai","cwd":"/workspace",
                    "approvalPolicy":"on-request","approvalsReviewer":"user",
                    "sandbox":{"type":"workspaceWrite","writableRoots":["/workspace"],"networkAccess":true}
                }""",
            )
        }

        suspend fun completeMetadata(models: String = "{\"data\":[]}") {
            transport.replyNext("thread/goal/get", "{\"goal\":null}")
            testScope.runCurrent()
            transport.replyNext("model/list", models)
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
            } else if (method == "account/read") {
                reply(request, "{\"account\":null}")
            } else if (method == "account/rateLimits/read") {
                reply(request, "{\"rateLimits\":{}}")
            } else if (request.containsKey("id")) {
                requests.send(request)
            }
        }

        suspend fun next(method: String): JsonObject = requests.receive().also {
            assertEquals(method, it["method"]?.jsonPrimitive?.content)
        }

        suspend fun replyNext(method: String, result: String) = reply(next(method), result)

        suspend fun replyErrorNext(method: String, message: String) = replyError(next(method), message)

        suspend fun reply(request: JsonObject, result: String) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", json.parseToJsonElement(result))
            }.toString())
        }

        suspend fun replyError(request: JsonObject, message: String) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("error", buildJsonObject {
                    put("code", -32000)
                    put("message", message)
                })
            }.toString())
        }
    }

    private companion object {
        val MODELS = """{"data":[{"id":"gpt-5","model":"gpt-5","displayName":"GPT"}]}"""
    }
}

private fun CodexMetadataLoadState<*>.failedMessage(): String =
    (this as? CodexMetadataLoadState.Failed)?.message ?: "not failed"

private fun <T> CodexMetadataLoadState<T>.loadedValue(): T? =
    (this as? CodexMetadataLoadState.Loaded)?.value
