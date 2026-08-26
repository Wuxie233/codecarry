package dev.wuxie233.codecarry.data.dsh

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DshConnectionManagerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connection = DshConnection.from("http://127.0.0.1:3080")

    @Test
    fun `foreground DSH refresh selects only ready generations`() {
        assertTrue(shouldRefreshForegroundDsh(isReady = true))
        assertFalse(shouldRefreshForegroundDsh(isReady = false))
    }

    @Test
    fun `ready catalog refresh updates running without opening chat`() = runTest {
        val mux = FakeDownlink()
        val host = FakeDownlink()
        val factory = object : DshDownlinkFactory {
            override suspend fun openMux(connection: DshConnection) = mux
            override suspend fun openHost(connection: DshConnection) = host
        }
        val manager = DshConnectionManager(
            client = catalogClient(factory, running = false),
            scope = backgroundScope,
        )
        manager.connect("dsh-1", connection)
        runCurrent()
        manager.states.first { it["dsh-1"]?.isReady == true }
        manager.reducer("dsh-1").applySessionList(
            listOf(
                DshSessionSummary(
                    sessionId = "s1",
                    updatedAt = 1L,
                    running = true,
                    blank = false,
                    cwd = "/tmp",
                ),
            ),
        )
        assertTrue(manager.reducer("dsh-1").state.value.sessions.getValue("s1").running)
        manager.refreshReadyCatalog("dsh-1")
        runCurrent()
        assertFalse(manager.reducer("dsh-1").state.value.sessions.getValue("s1").running)
    }

    @Test
    fun `failed ready catalog refresh reconnects the generation`() = runTest {
        val muxGens = mutableListOf<FakeDownlink>()
        val hostGens = mutableListOf<FakeDownlink>()
        val factory = object : DshDownlinkFactory {
            override suspend fun openMux(connection: DshConnection) = FakeDownlink().also(muxGens::add)
            override suspend fun openHost(connection: DshConnection) = FakeDownlink().also(hostGens::add)
        }
        var lists = 0
        val manager = DshConnectionManager(
            client = catalogClient(factory, running = true) {
                lists += 1
                lists > 0
            },
            scope = backgroundScope,
            reconnectInitialMillis = 10L,
            reconnectMaxMillis = 10L,
        )
        manager.connect("dsh-1", connection)
        runCurrent()
        manager.states.first { it["dsh-1"]?.isReady == true }
        val firstGeneration = manager.states.value.getValue("dsh-1").generation
        manager.refreshReadyCatalog("dsh-1")
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        val next = manager.states.first { state ->
            val row = state["dsh-1"]
            row?.isReady == true && row.generation != firstGeneration
        }.getValue("dsh-1")
        assertTrue(next.generation > firstGeneration)
        assertEquals(2, muxGens.size)
    }

    @Test
    fun `ready only after describe and both sockets`() = runTest {
        val mux = FakeDownlink()
        val host = FakeDownlink()
        val factory = object : DshDownlinkFactory {
            override suspend fun openMux(connection: DshConnection) = mux
            override suspend fun openHost(connection: DshConnection) = host
        }
        val manager = DshConnectionManager(
            client = describingClient(factory),
            scope = backgroundScope,
        )
        manager.connect("dsh-1", connection)
        runCurrent()
        val ready = manager.states.first { it["dsh-1"]?.isReady == true }["dsh-1"]!!
        assertEquals("0.9", ready.describe!!.version)
        assertTrue(ready.muxOpen)
        assertTrue(ready.hostOpen)
    }

    @Test
    fun `losing mux invalidates the generation and reconnects`() = runTest {
        val muxGens = mutableListOf<FakeDownlink>()
        val hostGens = mutableListOf<FakeDownlink>()
        val factory = object : DshDownlinkFactory {
            override suspend fun openMux(connection: DshConnection) = FakeDownlink().also(muxGens::add)
            override suspend fun openHost(connection: DshConnection) = FakeDownlink().also(hostGens::add)
        }
        val manager = DshConnectionManager(
            client = describingClient(factory),
            scope = backgroundScope,
            reconnectInitialMillis = 10L,
            reconnectMaxMillis = 10L,
        )
        manager.connect("dsh-1", connection)
        runCurrent()
        manager.states.first { it["dsh-1"]?.isReady == true }
        val firstGeneration = manager.states.value.getValue("dsh-1").generation
        muxGens.single().close()
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        val next = manager.states.first { state ->
            val row = state["dsh-1"]
            row?.isReady == true && row.generation != firstGeneration
        }.getValue("dsh-1")
        assertTrue(next.generation > firstGeneration)
        assertEquals(2, muxGens.size)
    }

    @Test
    fun `pending approvals clear when generation fails`() = runTest {
        val mux = FakeDownlink()
        val host = FakeDownlink()
        val factory = object : DshDownlinkFactory {
            override suspend fun openMux(connection: DshConnection) = mux
            override suspend fun openHost(connection: DshConnection) = host
        }
        val manager = DshConnectionManager(
            client = describingClient(factory),
            scope = backgroundScope,
        )
        manager.connect("dsh-1", connection)
        runCurrent()
        manager.states.first { it["dsh-1"]?.isReady == true }
        manager.reducer("dsh-1").applyMux(
            "approval-rpc",
            DshMuxFrame.ApprovalRequested("s1", "a1", "bash"),
        )
        assertFalse(manager.reducer("dsh-1").state.value.pendingApprovals.isEmpty())
        mux.close()
        runCurrent()
        assertTrue(manager.reducer("dsh-1").state.value.pendingApprovals.isEmpty())
    }

    @Test
    fun `losing host invalidates the generation and reconnects`() = runTest {
        val muxGens = mutableListOf<FakeDownlink>()
        val hostGens = mutableListOf<FakeDownlink>()
        val factory = object : DshDownlinkFactory {
            override suspend fun openMux(connection: DshConnection) = FakeDownlink().also(muxGens::add)
            override suspend fun openHost(connection: DshConnection) = FakeDownlink().also(hostGens::add)
        }
        val manager = DshConnectionManager(
            client = describingClient(factory),
            scope = backgroundScope,
            reconnectInitialMillis = 10L,
            reconnectMaxMillis = 10L,
        )
        manager.connect("dsh-1", connection)
        runCurrent()
        manager.states.first { it["dsh-1"]?.isReady == true }
        val firstGeneration = manager.states.value.getValue("dsh-1").generation
        hostGens.single().close()
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        val next = manager.states.first { state ->
            val row = state["dsh-1"]
            row?.isReady == true && row.generation != firstGeneration
        }.getValue("dsh-1")
        assertTrue(next.generation > firstGeneration)
        assertEquals(2, hostGens.size)
    }

    @Test
    fun `describe failure never becomes ready and retries`() = runTest {
        var describes = 0
        val factory = object : DshDownlinkFactory {
            override suspend fun openMux(connection: DshConnection) = FakeDownlink()
            override suspend fun openHost(connection: DshConnection) = FakeDownlink()
        }
        val engine = MockEngine {
            describes += 1
            if (describes == 1) {
                respond(
                    """{"type":"server-response","rpcId":"fixed","result":{"ok":false,"error":{"code":"internal","message":"not ready","details":{}}}}""",
                    headers = headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"version":"0.9","cwd":"/tmp","attachedSessions":0,"home":"/root","canOpenPath":false}}}""",
                    headers = headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val manager = DshConnectionManager(
            client = DshApiClient(http, json, mintRpcId = { "fixed" }, downlinkFactory = factory),
            scope = backgroundScope,
            reconnectInitialMillis = 10L,
            reconnectMaxMillis = 10L,
        )
        manager.connect("dsh-1", connection)
        runCurrent()
        assertTrue(manager.states.value["dsh-1"]?.isReady != true)
        advanceTimeBy(10)
        runCurrent()
        val ready = manager.states.first { it["dsh-1"]?.isReady == true }["dsh-1"]!!
        assertTrue(describes >= 2)
        assertEquals("0.9", ready.describe!!.version)
    }

    private fun catalogClient(
        factory: DshDownlinkFactory,
        running: Boolean,
        failList: () -> Boolean = { false },
    ): DshApiClient {
        val engine = MockEngine { request ->
            val envelope = json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val method = envelope.getValue("method").jsonPrimitive.content
            val body = when {
                method == "host.describe" ->
                    """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"version":"0.9","cwd":"/tmp","attachedSessions":0,"home":"/root","canOpenPath":false}}}"""
                method == "workspace.list" && failList() ->
                    """{"type":"server-response","rpcId":"$rpcId","result":{"ok":false,"error":{"code":"internal","message":"half-open","details":{}}}}"""
                method == "workspace.list" ->
                    """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"items":[{"workspaceId":"w1","path":"/tmp","folders":[],"title":"tmp","sessionIds":["s1"],"createdAt":"t","updatedAt":"t"}],"archivedSessionIds":[],"hiddenWorkspaceIds":[]}}}"""
                method == "session.list" ->
                    """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"items":[{"sessionId":"s1","updatedAt":2,"running":$running,"blank":false,"cwd":"/tmp"}]}}}"""
                else -> error(method)
            }
            respond(body, headers = headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return DshApiClient(http, json, mintRpcId = { "fixed" }, downlinkFactory = factory)
    }

    private fun describingClient(factory: DshDownlinkFactory): DshApiClient {
        val engine = MockEngine {
            respond(
                """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"version":"0.9","cwd":"/tmp","attachedSessions":0,"home":"/root","canOpenPath":false}}}""",
                headers = headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return DshApiClient(http, json, mintRpcId = { "fixed" }, downlinkFactory = factory)
    }
}
