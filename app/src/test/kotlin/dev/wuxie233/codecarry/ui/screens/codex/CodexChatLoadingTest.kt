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
    fun `slash skill selection attaches without sending and preserves draft at capacity`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        val skill = dev.wuxie233.codecarry.data.codex.CodexSkill("review", "Review code", null, "/skills/review/SKILL.md", true)
        fixture.vm.updateDraft("/rev")
        fixture.vm.selectSlashSkill(skill)
        assertEquals("", fixture.vm.uiState.value.draft)
        assertEquals(1, fixture.vm.uiState.value.composerAttachments.size)
        assertEquals(dev.wuxie233.codecarry.data.codex.CodexUserInput.Skill(skill.name, skill.path), fixture.vm.uiState.value.composerAttachments.single().input)
        fixture.vm.updateDraft("/review")
        fixture.vm.selectSlashSkill(skill)
        assertEquals(1, fixture.vm.uiState.value.composerAttachments.size)
        repeat(7) { i ->
            fixture.vm.updateDraft("/skill$i")
            fixture.vm.selectSlashSkill(skill.copy(name = "skill$i", path = "/skills/$i"))
        }
        fixture.vm.updateDraft("/extra")
        fixture.vm.selectSlashSkill(skill.copy(name = "extra", path = "/skills/extra"))
        assertEquals("/extra", fixture.vm.uiState.value.draft)
        assertTrue(fixture.vm.uiState.value.attachmentLimitReached)
        assertEquals(8, fixture.vm.uiState.value.composerAttachments.size)
        runCurrent()
        assertFalse(fixture.transport.methods.contains("turn/start"))
        assertFalse(fixture.transport.methods.contains("turn/steer"))
    }

    @Test
    fun `skill selection in an existing draft preserves preceding text without sending`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        val skill = dev.wuxie233.codecarry.data.codex.CodexSkill("review", "Review code", null, "/skills/review", true)
        fixture.vm.updateDraft("Please review this\n/rev")
        fixture.vm.selectSlashSkill(skill)
        assertEquals("Please review this\n", fixture.vm.uiState.value.draft)
        assertEquals(1, fixture.vm.uiState.value.composerAttachments.size)
        runCurrent()
        assertFalse(fixture.transport.methods.contains("turn/start"))
        assertFalse(fixture.transport.methods.contains("turn/steer"))
    }

    @Test
    fun `skill selection preserves suffix and ignores a stale cursor or selected text`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        val skill = dev.wuxie233.codecarry.data.codex.CodexSkill("review", "Review code", null, "/skills/review", true)
        val input = androidx.compose.ui.text.input.TextFieldValue(
            "Before /review after", androidx.compose.ui.text.TextRange(11),
        )
        fixture.vm.updateComposerValue(input)
        fixture.vm.updateComposerValue(input.copy(selection = androidx.compose.ui.text.TextRange(0)))
        fixture.vm.selectSlashSkill(skill, input)
        assertEquals("Before /review after", fixture.vm.uiState.value.draft)
        assertTrue(fixture.vm.uiState.value.composerAttachments.isEmpty())
        fixture.vm.updateComposerValue(input.copy(selection = androidx.compose.ui.text.TextRange(7, 14)))
        fixture.vm.selectSlashSkill(skill)
        assertTrue(fixture.vm.uiState.value.composerAttachments.isEmpty())
        fixture.vm.updateComposerValue(input)
        fixture.vm.selectSlashSkill(skill, input)
        assertEquals("Before  after", fixture.vm.uiState.value.draft)
        assertEquals(androidx.compose.ui.text.TextRange(7), fixture.vm.uiState.value.composerValue.selection)
        assertEquals(1, fixture.vm.uiState.value.composerAttachments.size)
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
        assertEquals("turn failed remotely", fixture.vm.uiState.value.threadFailure?.message)
        fixture.completeMetadata()
        assertNull(fixture.vm.uiState.value.activeTurnId)
        assertEquals("completed", fixture.vm.uiState.value.thread?.turns?.single()?.status)
        assertEquals("turn failed remotely", fixture.vm.uiState.value.threadFailure?.message)
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

    @Test
    fun `unrelated events do not erase an operation failure`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        fixture.vm.compactThread()
        runCurrent()
        val request = fixture.transport.next("thread/compact/start")
        fixture.transport.incoming.send(buildJsonObject {
            put("id", request.getValue("id"))
            put("error", buildJsonObject {
                put("code", -32000)
                put("message", "cannot compact active turn")
            })
        }.toString())
        runCurrent()
        val operationError = fixture.vm.uiState.value.error
        assertTrue(operationError.orEmpty().contains("cannot compact active turn"))
        fixture.transport.incoming.send(
            """{"method":"thread/name/updated","params":{"threadId":"child","threadName":"new name"}}""",
        )
        fixture.transport.incoming.send(
            """{"method":"error","params":{"threadId":"child","turnId":"turn-1","willRetry":true,"error":{"message":"reconnecting model"}}}""",
        )
        runCurrent()
        assertEquals(operationError, fixture.vm.uiState.value.error)
        assertTrue(fixture.vm.uiState.value.turnFailures.getValue("turn-1").willRetry)
        fixture.vm.dismissError()
        assertNull(fixture.vm.uiState.value.error)
        assertTrue(fixture.vm.uiState.value.turnFailures.getValue("turn-1").willRetry)
    }

    @Test
    fun `family projection follows server catalog ancestry`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        for (thread in listOf(
            """{"id":"parent"}""",
            """{"id":"child","parentThreadId":"parent"}""",
            """{"id":"sibling","parentThreadId":"parent"}""",
            """{"id":"unrelated"}""",
        )) {
            fixture.transport.incoming.send("""{"method":"thread/started","params":{"thread":$thread}}""")
        }
        runCurrent()
        fixture.vm.refreshRelatedThreads()
        assertEquals(setOf("parent", "child", "sibling"), fixture.vm.uiState.value.relatedThreads.map { it.id }.toSet())
    }

    @Test
    fun fastChoiceWaitsThroughSteerThenStartsWithAdvertisedTier() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata("""{"data":[{"id":"gpt-5","model":"gpt-5","displayName":"GPT", "defaultReasoningEffort":"high", "serviceTiers":[{"id":"fast","name":"Fast"}]}]}""")
        fixture.vm.toggleFast()
        assertTrue(fixture.vm.uiState.value.fastEnabled)
        assertTrue(fixture.vm.uiState.value.fastPending)
        assertEquals("high", fixture.vm.uiState.value.selectedEffort)
        fixture.vm.sendMessage("steer")
        runCurrent()
        val steer = fixture.transport.next("turn/steer")
        assertFalse(steer["params"]!!.jsonObject.containsKey("serviceTier"))
        fixture.transport.reply(steer, "{}")
        runCurrent()
        assertTrue(fixture.vm.uiState.value.fastPending)
        fixture.completeTurn()
        runCurrent()
        fixture.vm.sendMessage("next")
        runCurrent()
        val start = fixture.transport.next("turn/start")
        assertEquals("fast", start["params"]!!.jsonObject["serviceTier"]!!.jsonPrimitive.content)
        assertEquals("high", start["params"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        fixture.transport.reply(start, """{"turn":{"id":"turn-2","status":"completed","items":[]}}""")
        runCurrent()
        assertFalse(fixture.vm.uiState.value.fastSelectionPending)
        fixture.vm.toggleFast()
        fixture.vm.sendMessage("normal")
        runCurrent()
        val normal = fixture.transport.next("turn/start")
        assertTrue(normal["params"]!!.jsonObject.containsKey("serviceTier"))
        assertEquals(kotlinx.serialization.json.JsonNull, normal["params"]!!.jsonObject["serviceTier"])
    }

    @Test
    fun cachedResumeKeepsHistoryAndDoesNotRepeatResumeOrModelRpc() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        val history = fixture.vm.uiState.value.thread
        fixture.vm.connectAndLoad()
        assertEquals(history, fixture.vm.uiState.value.thread)
        runCurrent()
        assertFalse(fixture.vm.uiState.value.isLoading)
        assertTrue(fixture.vm.uiState.value.isConnected)
        assertEquals(1, fixture.transport.methods.count { it == "thread/resume" })
        assertEquals(1, fixture.transport.methods.count { it == "model/list" })
        fixture.transport.replyNext("thread/goal/get", "{\"goal\":null}")
        runCurrent()
    }

    @Test
    fun delayedModelCatalogPreservesExplicitModelAndEffortSelection() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        val selected = dev.wuxie233.codecarry.data.codex.CodexModel(
            id = "chosen", model = "chosen", displayName = "Chosen", defaultReasoningEffort = "high",
        )
        fixture.vm.selectModel(selected)
        fixture.completeMetadata("""{"data":[{"id":"gpt-5","model":"gpt-5","displayName":"Default"}]}""")
        assertEquals("chosen", fixture.vm.uiState.value.selectedModel?.model)
        assertEquals("high", fixture.vm.uiState.value.selectedEffort)
    }

    @Test
    fun failedResumeKeepsCachedHistoryAndCanBeRetried() = scope.runTest {
        val fixture = fixture()
        val cached = dev.wuxie233.codecarry.data.codex.CodexThread(
            id = "child", turns = listOf(dev.wuxie233.codecarry.data.codex.CodexTurn(id = "cached")),
        )
        fixture.connection.reducer.upsertThread(cached)
        runCurrent()
        val request = fixture.transport.next("thread/resume")
        fixture.transport.incoming.send(buildJsonObject {
            put("id", request.getValue("id"))
            put("error", buildJsonObject { put("code", -32000); put("message", "temporarily unavailable") })
        }.toString())
        runCurrent()
        assertEquals(cached, fixture.vm.uiState.value.thread)
        assertTrue(fixture.vm.uiState.value.canRetryConnection)
        assertFalse(fixture.vm.uiState.value.isConnected)
        fixture.vm.connectAndLoad()
        runCurrent()
        assertEquals(cached, fixture.vm.uiState.value.thread)
        assertFalse(fixture.vm.uiState.value.canRetryConnection)
        fixture.resume()
        runCurrent()
        assertTrue(fixture.vm.uiState.value.isConnected)
        assertFalse(fixture.vm.uiState.value.canRetryConnection)
        fixture.completeMetadata()
    }

    @Test
    fun ephemeralResumeKeepsCachedHistoryAndExplainsUnsupportedRecovery() = scope.runTest {
        val fixture = fixture()
        val cached = dev.wuxie233.codecarry.data.codex.CodexThread(
            id = "child", turns = listOf(dev.wuxie233.codecarry.data.codex.CodexTurn(id = "cached")),
        )
        fixture.connection.reducer.upsertThread(cached)
        runCurrent()
        val request = fixture.transport.next("thread/resume")
        fixture.transport.incoming.send(buildJsonObject {
            put("id", request.getValue("id"))
            put("error", buildJsonObject { put("code", -32600); put("message", "no rollout found for thread id child") })
        }.toString())
        runCurrent()
        fixture.transport.replyNext("thread/read", """{"thread":{"id":"child","ephemeral":true}}""")
        runCurrent()
        assertEquals(cached, fixture.vm.uiState.value.thread)
        assertTrue(fixture.vm.uiState.value.ephemeralHistoryUnavailable)
        assertFalse(fixture.vm.uiState.value.copy(error = "Other operation failed").ephemeralHistoryUnavailable)
        assertFalse(fixture.vm.uiState.value.copy(error = null).ephemeralHistoryUnavailable)
        assertFalse(fixture.vm.uiState.value.isConnected)
        assertFalse(fixture.vm.uiState.value.isLoading)
        fixture.vm.connectAndLoad()
        runCurrent()
        fixture.resume()
        runCurrent()
        assertFalse(fixture.vm.uiState.value.ephemeralHistoryUnavailable)
        assertTrue(fixture.vm.uiState.value.isConnected)
        fixture.completeMetadata()
    }

    @Test
    fun `async answer steers original turn once and never starts another turn`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        fixture.transport.incoming.send("""{"method":"item/completed","params":{
            "threadId":"child","turnId":"turn-1","item":{"id":"question-1","type":"agentMessage",
            "phase":"final_answer","delivery":"async","text":"Which?",
            "questions":[{"title":"Which?","options":["A","B"]}]}}}""")
        runCurrent()
        val question = fixture.vm.uiState.value.asyncQuestions.single()
        fixture.vm.answerAsyncQuestions(question.turnId, question.sourceItemId, mapOf(question.id to "A"))
        fixture.vm.answerAsyncQuestions(question.turnId, question.sourceItemId, mapOf(question.id to "A"))
        runCurrent()
        val request = fixture.transport.next("turn/steer")
        val params = request.getValue("params").jsonObject
        assertEquals("turn-1", params.getValue("expectedTurnId").jsonPrimitive.content)
        assertTrue(params.getValue("input").toString().contains("send_user_message_question_reply"))
        fixture.transport.reply(request, """{"turnId":"turn-1"}""")
        runCurrent()
        assertEquals("A", fixture.vm.uiState.value.asyncQuestions.single().answer)
        assertFalse(fixture.vm.uiState.value.asyncQuestions.single().canAnswer)
        fixture.vm.answerAsyncQuestions(question.turnId, question.sourceItemId, mapOf(question.id to "B"))
        runCurrent()
        assertEquals(1, fixture.transport.methods.count { it == "turn/steer" })
        assertFalse(fixture.transport.methods.contains("turn/start"))
    }

    @Test
    fun `expired async answer cannot submit or start a new turn`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        fixture.transport.incoming.send("""{"method":"item/completed","params":{
            "threadId":"child","turnId":"turn-1","item":{"id":"question-1","type":"agentMessage",
            "phase":"final_answer","delivery":"async","text":"Which?"}}}""")
        runCurrent()
        val question = fixture.vm.uiState.value.asyncQuestions.single()
        fixture.completeTurn()
        runCurrent()
        fixture.vm.answerAsyncQuestions(question.turnId, question.sourceItemId, mapOf(question.id to "A"))
        runCurrent()
        assertFalse(fixture.transport.methods.contains("turn/steer"))
        assertFalse(fixture.transport.methods.contains("turn/start"))
    }

    @Test
    fun `manual reconnect refreshes history and late snapshot cannot undo live final`() = scope.runTest {
        val fixture = fixture()
        fixture.resume()
        runCurrent()
        fixture.completeMetadata()
        fixture.vm.reconnect()
        runCurrent()
        val refresh = fixture.transport.next("thread/resume")
        fixture.transport.incoming.send("""{"method":"item/completed","params":{
            "threadId":"child","turnId":"turn-1","item":{"id":"final","type":"agentMessage",
            "phase":"final_answer","text":"Complete final answer"}}}""")
        fixture.completeTurn()
        runCurrent()
        assertEquals("Complete final answer", fixture.vm.uiState.value.thread?.turns?.single()?.items?.last()?.text)
        fixture.transport.reply(refresh, """{"thread":{"id":"child","turns":[{"id":"turn-1",
            "status":"inProgress","items":[{"id":"message-1","type":"agentMessage","text":"hello"}]}]}}""")
        runCurrent()
        fixture.transport.replyNext("thread/goal/get", "{\"goal\":null}")
        runCurrent()
        assertEquals(1, fixture.transport.methods.count { it == "model/list" })
        val turn = fixture.vm.uiState.value.thread?.turns?.single()
        assertEquals("completed", turn?.status)
        assertEquals("Complete final answer", turn?.items?.last()?.text)
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
            dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository(store),
        ).also(viewModels::add)
        runCurrent()
        return Fixture(vm, transport, this, requireNotNull(manager.get(server.id)))
    }

    private inner class Fixture(
        val vm: CodexChatViewModel,
        val transport: FakeTransport,
        val testScope: TestScope,
        val connection: dev.wuxie233.codecarry.data.codex.CodexServerConnection,
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

        suspend fun reply(request: JsonObject, result: String) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", json.parseToJsonElement(result))
            }.toString())
        }
    }
}
