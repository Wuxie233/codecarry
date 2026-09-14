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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #30 read behavior: the persisted unread mark clears only when replies are
 * actually presented (screen visible + following tail + history loaded); leaving a
 * chat whose tail was never presented keeps newer replies unread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexChatReadModelTest {
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
    fun `presented replies advance the anchor and clear the unread mark`() = scope.runTest {
        val fixture = fixture()
        fixture.resume(turns = REPLY_TURN)
        runCurrent()
        fixture.completeMetadata()
        fixture.repo.markConversationUnread(fixture.server.id, "child")

        fixture.vm.setChatVisible(true)
        runCurrent()

        assertEquals("message-2", fixture.repo.readAnchor(fixture.server.id, "child").first())
        assertFalse(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
    }

    @Test
    fun `invisible chat never clears the unread mark`() = scope.runTest {
        val fixture = fixture()
        fixture.resume(turns = REPLY_TURN)
        runCurrent()
        fixture.completeMetadata()
        fixture.repo.markConversationUnread(fixture.server.id, "child")

        // The screen never started; the ViewModel being alive must not read the thread.
        runCurrent()

        assertTrue(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
        assertNull(fixture.repo.readAnchor(fixture.server.id, "child").first())
    }

    @Test
    fun `browsing older history keeps newer replies unread and leaving marks them`() = scope.runTest {
        val fixture = fixture()
        fixture.resume(turns = REPLY_TURN)
        runCurrent()
        fixture.completeMetadata()
        // The user previously read up to message-1; message-2 arrived later.
        fixture.repo.setReadAnchor(fixture.server.id, "child", "message-1")

        fixture.vm.onFollowTailChanged(false)
        fixture.vm.setChatVisible(true)
        runCurrent()
        // Visible but not at the tail: the anchor must not advance past what was seen.
        assertEquals("message-1", fixture.repo.readAnchor(fixture.server.id, "child").first())
        assertFalse(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))

        fixture.vm.setChatVisible(false)
        runCurrent()
        // Leaving without presenting the tail keeps the newer reply visibly unread.
        assertTrue(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
        assertEquals("message-1", fixture.repo.readAnchor(fixture.server.id, "child").first())
    }

    @Test
    fun `returning to the tail presents the newer reply and clears the mark`() = scope.runTest {
        val fixture = fixture()
        fixture.resume(turns = REPLY_TURN)
        runCurrent()
        fixture.completeMetadata()
        fixture.repo.setReadAnchor(fixture.server.id, "child", "message-1")
        fixture.repo.markConversationUnread(fixture.server.id, "child")

        fixture.vm.onFollowTailChanged(false)
        fixture.vm.setChatVisible(true)
        runCurrent()
        fixture.vm.onFollowTailChanged(true)
        runCurrent()

        assertEquals("message-2", fixture.repo.readAnchor(fixture.server.id, "child").first())
        assertFalse(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
    }

    @Test
    fun `a chat that fails to load never clears the unread mark`() = scope.runTest {
        val fixture = fixture()
        fixture.repo.markConversationUnread(fixture.server.id, "child")
        fixture.vm.setChatVisible(true)
        val request = fixture.transport.next("thread/resume")
        fixture.transport.incoming.send(buildJsonObject {
            put("id", request.getValue("id"))
            put("error", buildJsonObject { put("code", -32000); put("message", "rollout unavailable") })
        }.toString())
        runCurrent()

        assertTrue(fixture.vm.uiState.value.error?.contains("rollout unavailable") == true)
        assertTrue(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
        assertNull(fixture.repo.readAnchor(fixture.server.id, "child").first())
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
        val vm = CodexChatViewModel(
            SavedStateHandle(mapOf("serverId" to server.id, "threadId" to "child")),
            manager,
            repository,
            repo,
        ).also(viewModels::add)
        runCurrent()
        return Fixture(vm, transport, repo, server, this)
    }

    private inner class Fixture(
        val vm: CodexChatViewModel,
        val transport: FakeTransport,
        val repo: SessionListPreferencesRepository,
        val server: dev.wuxie233.codecarry.domain.model.ServerConfig,
        val testScope: TestScope,
    ) {
        suspend fun resume(turns: String) {
            val request = transport.next("thread/resume")
            transport.reply(
                request,
                """{
                    "thread":{"id":"child","turns":[$turns]},
                    "model":"gpt-5","modelProvider":"openai","cwd":"/workspace"
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
        private val requests = Channel<JsonObject>(Channel.UNLIMITED)
        override suspend fun connect() = Unit
        override fun close() = Unit
        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
        override suspend fun send(text: String) {
            val request = json.parseToJsonElement(text).jsonObject
            val method = request["method"]?.jsonPrimitive?.content.orEmpty()
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

    private companion object {
        val REPLY_TURN =
            """{"id":"turn-1","status":"completed","items":[
                {"id":"message-1","type":"agentMessage","text":"first reply"},
                {"id":"message-2","type":"agentMessage","text":"second reply"}
            ]}"""
    }
}
