package dev.wuxie233.codecarry.data.codex

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.domain.model.ServerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #30 producer half: a Codex turn that completes with readable agent output
 * persists a server-scoped unread mark through the shared read model — unless the
 * thread is currently visible — and the mark survives a full store reopen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodexThreadUnreadProducerTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `turn completion with readable output marks the thread unread`() = runTest {
        val fixture = fixture()
        fixture.manager.acquire(fixture.server, "child").use { }
        runCurrent()

        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{
                "id":"turn-1","status":"completed","items":[
                    {"id":"message-1","type":"agentMessage","text":"done"}
                ]}}}""",
        )
        runCurrent()

        assertTrue(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
    }

    @Test
    fun `unread mark survives a full store reopen`() = runTest {
        val fixture = fixture()
        fixture.manager.acquire(fixture.server, "child").use { }
        runCurrent()
        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{
                "id":"turn-1","status":"completed","items":[
                    {"id":"message-1","type":"agentMessage","text":"done"}
                ]}}}""",
        )
        runCurrent()
        assertTrue(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))

        // Restart semantics: the persistence owner is the DataStore file, not the
        // connection or the manager. A fresh repository over the same file still
        // reports the mark even after the previous store fully disposed.
        fixture.storeScope.cancel()
        runCurrent()
        val reopened = SessionListPreferencesRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(backgroundScope.coroutineContext + Job()),
                produceFile = { fixture.file },
            ),
        )
        assertTrue(reopened.unreadConversationIds(fixture.server.id).first().contains("child"))
    }

    @Test
    fun `tool-only and failed completions do not fabricate unread`() = runTest {
        val fixture = fixture()
        fixture.manager.acquire(fixture.server, "child").use { }
        runCurrent()

        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{
                "id":"turn-1","status":"completed","items":[
                    {"id":"shell-1","type":"commandExecution","command":"ls","output":"a b"}
                ]}}}""",
        )
        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{
                "id":"turn-2","status":"failed","items":[
                    {"id":"blank-1","type":"agentMessage","text":""}
                ]}}}""",
        )
        runCurrent()

        assertFalse(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
    }

    @Test
    fun `completion for the currently visible thread does not mark unread`() = runTest {
        val fixture = fixture()
        fixture.manager.acquire(fixture.server, "child").use { }
        runCurrent()

        val activeToken = fixture.manager.activateThread(CodexThreadKey(fixture.server.id, "child"))
        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{
                "id":"turn-1","status":"completed","items":[
                    {"id":"message-1","type":"agentMessage","text":"watched live"}
                ]}}}""",
        )
        runCurrent()
        assertFalse(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))

        // Once the chat is no longer visible, a later completion marks unread again.
        activeToken.close()
        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{
                "id":"turn-2","status":"completed","items":[
                    {"id":"message-2","type":"agentMessage","text":"missed reply"}
                ]}}}""",
        )
        runCurrent()
        assertTrue(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
    }

    @Test
    fun `empty-item completion marks unread from merged streamed output`() = runTest {
        val fixture = fixture()
        fixture.manager.acquire(fixture.server, "child").use { }
        runCurrent()

        fixture.transport.incoming.send(
            """{"method":"item/started","params":{"threadId":"child","turnId":"turn-1","item":{
                "id":"message-1","type":"agentMessage","text":""}}}""",
        )
        fixture.transport.incoming.send(
            """{"method":"item/agentMessage/delta","params":{"threadId":"child","turnId":"turn-1","itemId":"message-1","delta":"streamed prose"}}""",
        )
        runCurrent()
        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{
                "id":"turn-1","status":"completed","items":[],"itemsView":"notLoaded"}}}""",
        )
        runCurrent()

        assertTrue(fixture.repo.unreadConversationIds(fixture.server.id).first().contains("child"))
    }

    @Test
    fun `unread marks stay scoped per server`() = runTest {
        val fixture = fixture()
        fixture.manager.acquire(fixture.server, "child").use { }
        runCurrent()
        val secondServer = ServerConfig(
            id = "codex-server-b",
            type = ServerType.CODEX,
            url = "ws://codex-b.example.test",
            token = "secret-b",
        )
        val secondTransport = FakeTransport(json)
        fixture.transportsByServer[secondServer.id] = mutableListOf(secondTransport)
        fixture.manager.acquire(secondServer, "child").use { }
        runCurrent()

        fixture.transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"child","turn":{
                "id":"turn-1","status":"completed","items":[
                    {"id":"message-1","type":"agentMessage","text":"done"}
                ]}}}""",
        )
        runCurrent()

        val unreadFirst = fixture.repo.unreadConversationIds(fixture.server.id).first()
        val unreadSecond = fixture.repo.unreadConversationIds(secondServer.id).first()
        assertTrue(unreadFirst.contains("child"))
        assertFalse("Server B must not inherit server A's marks", unreadSecond.contains("child"))
    }

    private suspend fun TestScope.fixture(): Fixture {
        val file = tmpFolder.newFile("unread_${System.nanoTime()}.preferences_pb")
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val repo = SessionListPreferencesRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }),
        )
        val server = ServerConfig(
            id = "codex-server",
            type = ServerType.CODEX,
            url = "ws://codex.example.test",
            token = "secret",
        )
        val transport = FakeTransport(json)
        val transportsByServer = mutableMapOf(server.id to mutableListOf<CodexRpcTransport>(transport))
        val manager = CodexConnectionManager(
            createClient = { serverConfig ->
                CodexAppServerClient(
                    transport = transportsByServer.getValue(serverConfig.id).first(),
                    json = json,
                    scope = backgroundScope,
                )
            },
            scope = backgroundScope,
            unreadSink = CodexThreadUnreadSink { serverId, threadId ->
                repo.markConversationUnread(serverId, threadId)
            },
        )
        return Fixture(manager, repo, transport, server, transportsByServer, storeScope, file)
    }

    private class Fixture(
        val manager: CodexConnectionManager,
        val repo: SessionListPreferencesRepository,
        val transport: FakeTransport,
        val server: ServerConfig,
        val transportsByServer: MutableMap<String, MutableList<CodexRpcTransport>>,
        val storeScope: CoroutineScope,
        val file: java.io.File,
    )

    private class FakeTransport(private val json: Json) : CodexRpcTransport {
        val incoming = Channel<String>(Channel.UNLIMITED)
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
            }
        }

        suspend fun reply(request: JsonObject, result: String) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", json.parseToJsonElement(result))
            }.toString())
        }
    }
}
