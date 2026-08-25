package dev.wuxie233.codecarry.data.dsh

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshApiClientTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connection = DshConnection.from("http://192.168.1.8:3080")

    @Test
    fun `host describe posts client-request and returns value after rpcId echo`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val body = (request.body as TextContent).text
            val envelope = json.parseToJsonElement(body).jsonObject
            assertEquals("client-request", envelope.getValue("type").jsonPrimitive.content)
            assertEquals("host.describe", envelope.getValue("method").jsonPrimitive.content)
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"version":"0.9","cwd":"/tmp","attachedSessions":1,"home":"/root","canOpenPath":false}}}"""
        }

        val describe = client.describe(connection)
        assertEquals("0.9", describe.version)
        assertEquals("/tmp", describe.cwd)
        assertEquals("/api/host.describe", captured.single().url.encodedPath)
        assertEquals("application/json", captured.single().body.contentType?.withoutParameters().toString())
    }

    @Test
    fun `rpcId mismatch is a transport failure`() = runTest {
        val client = api(mutableListOf()) {
            """{"type":"server-response","rpcId":"other","result":{"ok":true,"value":{}}}"""
        }
        try {
            client.call(connection, "host.describe", rpcId = "expected")
            throw AssertionError("expected mismatch")
        } catch (error: DshTransportException) {
            assertTrue(error.message!!.contains("rpcId mismatch"))
        }
    }

    @Test
    fun `approval answer posts client-response to respond with host rpcId`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) {
            """{"accepted":true}"""
        }
        val receipt = client.answerApproval(connection, "host-rpc", "s1", "a1", "allowed-once")
        assertTrue(receipt.accepted)
        assertEquals("/api/respond", captured.single().url.encodedPath)
        val body = json.parseToJsonElement((captured.single().body as TextContent).text).jsonObject
        assertEquals("client-response", body.getValue("type").jsonPrimitive.content)
        assertEquals("host-rpc", body.getValue("rpcId").jsonPrimitive.content)
    }

    @Test
    fun `mux frames parse server-request text without sending application data`() = runTest {
        val downlink = FakeDownlink()
        val client = DshApiClient(unusedHttp(), json, downlinkFactory = unusedDownlinks())
        downlink.incoming.send(
            """{"type":"server-request","rpcId":"mux-1","method":"session/event","payload":{"type":"session/event","sessionId":"s1","event":{"type":"assistant/message","seq":3,"time":1}}}""",
        )
        downlink.incoming.close()
        val frames = client.muxFrames(downlink).toList()
        val event = frames.single().payload as DshMuxFrame.SessionEvent
        assertEquals("mux-1", frames.single().rpcId)
        assertEquals("s1", event.sessionId)
        assertEquals(3L, event.event.seq)
        assertTrue(downlink.sent.isEmpty())
    }

    @Test
    fun `loopback-only methods throw without posting on LAN`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { error("must not post") }
        try {
            client.call(connection, "credentials.describe")
            throw AssertionError("expected loopback fence")
        } catch (error: DshLoopbackUnavailableException) {
            assertEquals("credentials.describe", error.method)
        }
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `session prompt posts typed payload and decodes accepted command`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("session.prompt", envelope.getValue("method").jsonPrimitive.content)
            val payload = envelope.getValue("payload").jsonObject
            assertEquals("s1", payload.getValue("sessionId").jsonPrimitive.content)
            assertEquals("queue", payload.getValue("mode").jsonPrimitive.content)
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"accepted":true,"command":{"kind":"success","text":"ok"}}}}"""
        }
        val result = client.sessionPrompt(
            connection,
            sessionId = "s1",
            mode = "queue",
            content = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hello") }) },
        )
        assertTrue(result.accepted)
        assertEquals("success", result.command!!.kind)
        assertEquals("/api/session.prompt", captured.single().url.encodedPath)
    }

    @Test
    fun `workspace list and goal create decode typed values`() = runTest {
        val client = api(mutableListOf()) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            when (envelope.getValue("method").jsonPrimitive.content) {
                "workspace.list" -> """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"items":[{"workspaceId":"w1","path":"/tmp","folders":[],"title":"tmp","sessionIds":[],"createdAt":"t","updatedAt":"t"}],"archivedSessionIds":[],"hiddenWorkspaceIds":[]}}}"""
                "goal.create" -> """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"ref":{"id":"g1","revision":1}}}}"""
                else -> error(envelope.getValue("method").jsonPrimitive.content)
            }
        }
        val workspaces = client.workspaceList(connection)
        assertEquals("w1", workspaces.items.single().workspaceId)
        val goal = client.goalCreate(connection, "s1", "ship 1.9")
        assertEquals("g1", goal.ref.id)
        assertEquals(1L, goal.ref.revision)
    }

    @Test
    fun `mux stream error fails the generation instead of folding`() = runTest {
        val downlink = FakeDownlink()
        val client = DshApiClient(unusedHttp(), json, downlinkFactory = unusedDownlinks())
        downlink.incoming.send(
            """{"type":"server-request","rpcId":"err-1","method":"stream/error","payload":{"type":"stream/error","error":{"code":"internal","message":"impl broke","details":{}}}}""",
        )
        try {
            client.muxFrames(downlink).toList()
            throw AssertionError("expected stream error")
        } catch (error: DshTransportException) {
            assertTrue(error.message!!.contains("stream/error"))
        }
    }

    private fun unusedHttp(): HttpClient = HttpClient(MockEngine { error("http unused") })

    private fun unusedDownlinks(): DshDownlinkFactory = object : DshDownlinkFactory {
        override suspend fun openMux(connection: DshConnection) = error("unused")
        override suspend fun openHost(connection: DshConnection) = error("unused")
    }

    private fun api(
        captured: MutableList<HttpRequestData>,
        body: (HttpRequestData) -> String,
    ): DshApiClient {
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = body(request),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return DshApiClient(http, json, downlinkFactory = unusedDownlinks())
    }
}

internal class FakeDownlink : DshDownlink {
    val incoming = Channel<String>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    override var isOpen: Boolean = true
    override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
    override suspend fun close() {
        isOpen = false
        incoming.close()
    }
}
