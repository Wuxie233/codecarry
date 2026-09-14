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
import dev.wuxie233.codecarry.data.codex.CodexUserInput
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Issue #36: pending-send confirmation read-backs must publish the merged reducer
 * state, so a delayed raw `thread/read` snapshot can never roll back newer streamed
 * items or terminal turns, while a newer live event that confirms the client message
 * still releases the send lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexChatSendConfirmationRaceTest {
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
    fun `recheck publishes merged state when the delayed snapshot is older than live output`() = scope.runTest {
        val fixture = fixture(restoredPending = true)
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        // Newer live state streams in while the confirmation read is in flight.
        fixture.vm.recheckPendingSend()
        runCurrent()
        fixture.transport.incoming.send(
            """{"method":"item/agentMessage/delta","params":{"threadId":"child","turnId":"turn-1","itemId":"message-1","delta":" more"}}""",
        )
        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{"id":"turn-1","status":"completed","items":[],"itemsView":"notLoaded"}}}""",
        )
        runCurrent()
        assertEquals("hello more", fixture.vm.uiState.value.thread?.turns?.single()?.items?.single()?.text)
        assertEquals("completed", fixture.vm.uiState.value.thread?.turns?.single()?.status)
        // The read-back returns a stale pre-stream snapshot; it must not roll the UI back.
        fixture.transport.replyNext(
            "thread/read",
            """{"thread":{"id":"child","turns":[{"id":"turn-1","status":"inProgress","items":[
                {"id":"message-1","type":"agentMessage","text":"hello"}
            ]}]}}""",
        )
        runCurrent()
        val thread = fixture.vm.uiState.value.thread
        assertEquals("hello more", thread?.turns?.single()?.items?.single()?.text)
        assertEquals("completed", thread?.turns?.single()?.status)
        assertEquals(null, fixture.vm.uiState.value.activeTurnId)
        assertEquals("Codex has not confirmed this message", fixture.vm.uiState.value.error)
        assertTrue(fixture.vm.uiState.value.isSendConfirmationPending)
        assertTrue(fixture.sendResults.isEmpty())
    }

    @Test
    fun `delayed snapshot confirms the send while newer live output survives the publish`() = scope.runTest {
        val fixture = fixture(restoredPending = true)
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        // Newer live output streams in while the confirmation read is in flight.
        fixture.vm.recheckPendingSend()
        runCurrent()
        fixture.transport.incoming.send(
            """{"method":"item/agentMessage/delta","params":{"threadId":"child","turnId":"turn-1","itemId":"message-1","delta":" more"}}""",
        )
        runCurrent()
        assertEquals("hello more", fixture.vm.uiState.value.thread?.turns?.single()?.items?.single()?.text)
        // The read-back carries the client message but predates the streamed delta.
        fixture.transport.replyNext(
            "thread/read",
            """{"thread":{"id":"child","turns":[{"id":"turn-1","status":"inProgress","items":[
                {"id":"item-user","type":"userMessage","clientId":"client-message-1",
                 "content":[{"type":"text","text":"ship it"}]},
                {"id":"message-1","type":"agentMessage","text":"hello"}
            ]}]}}""",
        )
        runCurrent()
        val state = fixture.vm.uiState.value
        // Acceptance flows from the merged state, and the newer live text is not rolled back.
        assertEquals("hello more", state.thread?.turns?.single()?.items?.singleOrNull { it.id == "message-1" }?.text)
        assertFalse(state.isSendConfirmationPending)
        assertEquals(listOf(true), fixture.sendResults.map { it.accepted })
    }

    @Test
    fun `uncertain send reconciliation keeps newer live state and attachments`() = scope.runTest {
        val fixture = fixture(restoredPending = false)
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        // The resumed turn must be terminal so the send takes the turn/start path.
        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{"id":"turn-1","status":"completed","items":[],"itemsView":"notLoaded"}}}""",
        )
        runCurrent()
        assertEquals(null, fixture.vm.uiState.value.activeTurnId)
        fixture.transport.failSendForMethod = "turn/start"
        fixture.vm.addAttachment(
            CodexComposerAttachment(id = "att-1", label = "note", input = CodexUserInput.Text("attach")),
        )
        fixture.vm.sendMessage("ship it", fixture.vm.uiState.value.composerAttachments)
        runCurrent()
        // While the reconciliation read is in flight, another terminal drives the same thread.
        fixture.transport.failSendForMethod = null
        fixture.transport.incoming.send(
            """{"method":"item/agentMessage/delta","params":{"threadId":"child","turnId":"turn-1","itemId":"message-1","delta":" more"}}""",
        )
        runCurrent()
        fixture.transport.replyNext(
            "thread/read",
            """{"thread":{"id":"child","turns":[{"id":"turn-1","status":"inProgress","items":[
                {"id":"message-1","type":"agentMessage","text":"hello"}
            ]}]}}""",
        )
        runCurrent()
        val state = fixture.vm.uiState.value
        assertEquals("hello more", state.thread?.turns?.single()?.items?.singleOrNull { it.id == "message-1" }?.text)
        assertEquals("completed", state.thread?.turns?.single()?.status)
        assertTrue(state.isSendConfirmationPending)
        assertEquals("simulated send failure for turn/start", state.error)
        // A send that could not be confirmed keeps its composer attachments; nothing is dropped.
        assertEquals(listOf("att-1"), state.composerAttachments.map { it.id })
        assertTrue(fixture.sendResults.isEmpty())
    }

    private suspend fun TestScope.fixture(restoredPending: Boolean): Fixture {
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
        val savedState = SavedStateHandle(
            buildMap<String, Any> {
                put("serverId", server.id)
                put("threadId", "child")
                if (restoredPending) {
                    put("codexPendingSendContent", "ship it")
                    put("codexPendingSendId", "client-message-1")
                }
            },
        )
        val vm = CodexChatViewModel(
            savedState,
            manager,
            repository,
            dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository(store),
        ).also(viewModels::add)
        val sendResults = mutableListOf<CodexSendResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.sendResults.toList(sendResults) }
        runCurrent()
        return Fixture(vm, transport, this, sendResults)
    }

    private inner class Fixture(
        val vm: CodexChatViewModel,
        val transport: FakeTransport,
        val testScope: TestScope,
        val sendResults: List<CodexSendResult>,
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
        var failSendForMethod: String? = null
        private val requests = Channel<JsonObject>(Channel.UNLIMITED)
        override suspend fun connect() = Unit
        override fun close() = Unit
        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
        override suspend fun send(text: String) {
            val request = json.parseToJsonElement(text).jsonObject
            val method = request["method"]?.jsonPrimitive?.content.orEmpty()
            if (failSendForMethod == method) throw IOException("simulated send failure for $method")
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

        suspend fun reply(request: JsonObject, result: String) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", json.parseToJsonElement(result))
            }.toString())
        }
    }
}
