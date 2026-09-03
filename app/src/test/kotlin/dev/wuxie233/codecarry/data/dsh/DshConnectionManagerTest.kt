package dev.wuxie233.codecarry.data.dsh

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DshConnectionManagerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connection = DshConnection.from("http://127.0.0.1:3080", token = "launch-token")
    private val streamIds = listOf("st-events", "st-control", "st-workspace")

    @Test
    fun `foreground DSH refresh selects only ready generations`() {
        assertTrue(shouldRefreshForegroundDsh(isReady = true))
        assertFalse(shouldRefreshForegroundDsh(isReady = false))
    }

    @Test
    fun `generation becomes ready after events control and workspace baselines`() = runTest {
        val mux = FakeDownlink()
        var next = 0
        val manager = DshConnectionManager(
            client = clientFor(muxFactory(mux), running = false),
            scope = backgroundScope,
            mintStreamId = { streamIds[next++ % streamIds.size] },
        )
        manager.connect("dsh-1", connection)
        mux.incoming.trySend(
            item("st-events", """{"type":"ready","clientId":"client-1","host":{"home":"/root"}}"""),
        )
        runCurrent()
        assertFalse(manager.states.value["dsh-1"]?.isReady == true)
        mux.incoming.trySend(
            item("st-control", """{"type":"baseline","value":{"queues":{},"jobs":{},"projections":{}}}"""),
        )
        runCurrent()
        assertFalse(manager.states.value["dsh-1"]?.isReady == true)
        mux.incoming.trySend(
            item("st-workspace", """{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"hiddenWorkspaceIds":[]}}"""),
        )
        val ready = manager.states.first { it["dsh-1"]?.isReady == true }["dsh-1"]!!
        assertEquals("/root", ready.describe!!.home)
        assertEquals("client-1", ready.eventsClientId)
        assertEquals("dsh-auth-zz=v1.body", ready.cookie)
        assertEquals(3, mux.sent.size)
    }

    @Test
    fun `mux close ends the generation and reconnects with a fresh cookie`() = runTest {
        val sockets = mutableListOf<FakeDownlink>()
        var next = 0
        val manager = DshConnectionManager(
            client = clientFor(object : DshDownlinkFactory {
                override suspend fun openMux(connection: DshConnection): DshDownlink =
                    FakeDownlink().also { sockets += it }
            }, running = false),
            scope = backgroundScope,
            reconnectInitialMillis = 10L,
            reconnectMaxMillis = 10L,
            mintStreamId = { streamIds[next++ % streamIds.size] },
        )
        manager.connect("dsh-1", connection)
        manager.states.first { it["dsh-1"]?.muxOpen == true }
        pushBaselines(sockets.first())
        val firstGeneration = manager.states.first { it["dsh-1"]?.isReady == true }["dsh-1"]!!.generation
        sockets.first().incoming.close()
        manager.states.first { it["dsh-1"]?.status == DshGenerationStatus.Failed }
        advanceTimeBy(10)
        manager.states.first { state ->
            val row = state["dsh-1"]
            row?.muxOpen == true && row.generation != firstGeneration
        }
        pushBaselines(sockets.last())
        val nextGeneration = manager.states.first { state ->
            val row = state["dsh-1"]
            row?.isReady == true && row.generation != firstGeneration
        }.getValue("dsh-1")
        assertTrue(nextGeneration.generation > firstGeneration)
        assertEquals(2, sockets.size)
    }

    @Test
    fun `auth failure at cookie exchange fails closed without a reconnect loop`() = runTest {
        var exchanges = 0
        val engine = MockEngine { request ->
            exchanges += 1
            respond(
                content = "dsh web authentication required",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val http = HttpClient(engine) {
            followRedirects = false
            install(ContentNegotiation) { json(json) }
        }
        val mux = FakeDownlink()
        val manager = DshConnectionManager(
            client = DshApiClient(http, json, downlinkFactory = muxFactory(mux)),
            scope = backgroundScope,
            reconnectInitialMillis = 10L,
            reconnectMaxMillis = 10L,
        )
        manager.connect("dsh-1", connection)
        val state = manager.states.first { it["dsh-1"]?.status == DshGenerationStatus.Failed }["dsh-1"]!!
        assertTrue(state.error!!.contains("authentication"))
        assertEquals(1, exchanges)
        assertTrue(mux.sent.isEmpty())
    }

    @Test
    fun `ready catalog refresh updates running without opening chat`() = runTest {
        val mux = FakeDownlink()
        var next = 0
        val manager = DshConnectionManager(
            client = clientFor(muxFactory(mux), running = false),
            scope = backgroundScope,
            mintStreamId = { streamIds[next++ % streamIds.size] },
        )
        manager.connect("dsh-1", connection)
        pushBaselines(mux)
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
    fun `session follow demuxes by stream id into follow frames`() = runTest {
        val mux = FakeDownlink()
        var next = 0
        val manager = DshConnectionManager(
            client = clientFor(muxFactory(mux), running = false),
            scope = backgroundScope,
            mintStreamId = { streamIds[next++ % streamIds.size] },
        )
        manager.connect("dsh-1", connection)
        pushBaselines(mux)
        manager.states.first { it["dsh-1"]?.isReady == true }
        backgroundScope.launch {
            manager.openSessionFollow("dsh-1", DshSessionAddress.Session("s1")).collect { frame ->
                when (frame) {
                    is DshFollowFrame.Snapshot ->
                        manager.reducer("dsh-1").applyFollowSnapshot("s1", frame)
                    is DshFollowFrame.FollowEvent ->
                        manager.reducer("dsh-1").applyFollowEvent("s1", frame.event)
                }
            }
        }
        runCurrent()
        val followOpen = mux.sent.last()
        val followObj = json.parseToJsonElement(followOpen).jsonObject
        assertEquals("open", followObj.getValue("type").jsonPrimitive.content)
        assertEquals("session/follow", followObj.getValue("endpoint").jsonPrimitive.content)
        val followId = followObj.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(
            item(
                followId,
                """{"type":"snapshot","header":null,"cursor":2,"records":[{"type":"event","event":{"type":"user/message","seq":1,"time":1}}],"hasMore":false}""",
            ),
        )
        runCurrent()
        val session = manager.reducer("dsh-1").state.value.sessions.getValue("s1")
        assertEquals(1, session.events.size)
        assertEquals(2L, session.lastSeq)
        val address = followObj.getValue("payload").jsonObject
            .getValue("args").jsonObject
            .getValue("request").jsonObject
            .getValue("address").jsonObject
        assertEquals("session", address.getValue("kind").jsonPrimitive.content)
        assertEquals("s1", address.getValue("sessionId").jsonPrimitive.content)
    }

    @Test
    fun `session follow open payload uses subagent address`() = runTest {
        val mux = FakeDownlink()
        var next = 0
        val manager = DshConnectionManager(
            client = clientFor(muxFactory(mux), running = false),
            scope = backgroundScope,
            mintStreamId = { streamIds[next++ % streamIds.size] },
        )
        manager.connect("dsh-1", connection)
        pushBaselines(mux)
        manager.states.first { it["dsh-1"]?.isReady == true }
        backgroundScope.launch {
            manager.openSessionFollow(
                "dsh-1",
                DshSessionAddress.Subagent(
                    parentSessionId = "parent-1",
                    childSessionId = "child-1",
                    mode = "continuable",
                ),
            ).collect { }
        }
        runCurrent()
        val followObj = json.parseToJsonElement(mux.sent.last()).jsonObject
        val address = followObj.getValue("payload").jsonObject
            .getValue("args").jsonObject
            .getValue("request").jsonObject
            .getValue("address").jsonObject
        assertEquals("subagent", address.getValue("kind").jsonPrimitive.content)
        assertEquals("parent-1", address.getValue("parentSessionId").jsonPrimitive.content)
        assertEquals("child-1", address.getValue("childSessionId").jsonPrimitive.content)
        assertEquals("continuable", address.getValue("mode").jsonPrimitive.content)
    }

    private fun muxFactory(mux: FakeDownlink): DshDownlinkFactory = object : DshDownlinkFactory {
        override suspend fun openMux(connection: DshConnection) = mux
    }

    private fun clientFor(factory: DshDownlinkFactory, running: Boolean): DshApiClient {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/" -> respond(
                    content = "",
                    status = HttpStatusCode.SeeOther,
                    headers = headersOf(
                        HttpHeaders.SetCookie,
                        "dsh-auth-zz=v1.body; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict",
                    ),
                )
                request.url.encodedPath == "/api/session/list" -> respond(
                    content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"items":[{"sessionId":"s1","updatedAt":2,"running":$running,"blank":false,"cwd":"/tmp"}]}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error(request.url.encodedPath)
            }
        }
        val http = HttpClient(engine) {
            followRedirects = false
            install(ContentNegotiation) { json(json) }
        }
        return DshApiClient(http, json, mintRpcId = { "fixed" }, downlinkFactory = factory)
    }

    private fun pushBaselines(mux: FakeDownlink) {
        mux.incoming.trySend(
            item("st-events", """{"type":"ready","clientId":"client-1","host":{"home":"/root"}}"""),
        )
        mux.incoming.trySend(
            item("st-control", """{"type":"baseline","value":{"queues":{},"jobs":{},"projections":{}}}"""),
        )
        mux.incoming.trySend(
            item("st-workspace", """{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"hiddenWorkspaceIds":[]}}"""),
        )
    }

    private fun item(streamId: String, value: String): String =
        """{"type":"item","streamId":"$streamId","value":$value}"""
}
