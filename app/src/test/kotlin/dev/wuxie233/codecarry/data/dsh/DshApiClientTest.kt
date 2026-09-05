package dev.wuxie233.codecarry.data.dsh

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshApiClientTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connection = DshConnection.from("http://192.168.1.8:3080")

    @Test
    fun `preset roster matches the host path free list contract`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("agentPresets/list", envelope.getValue("method").jsonPrimitive.content)
            assertTrue(envelope.getValue("payload").jsonObject.getValue("args").jsonObject.isEmpty())
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"presets":[{"id":"standard","trust":"system","isDefault":true},{"id":"custom","trust":"user","isDefault":false,"name":"Custom","description":"My preset","broken":"Missing plugin"}],"authorable":true}}}"""
        }
        val roster = client.agentPresetList(connection)
        assertEquals(listOf("standard", "custom"), roster.presets.map { it.id })
        assertTrue(roster.authorable)
        assertEquals("Missing plugin", roster.presets[1].broken)
        assertEquals("/api/agentPresets/list", captured.single().url.encodedPath)
    }

    @Test
    fun `session list posts slash method with args underscore request`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val body = (request.body as TextContent).text
            val envelope = json.parseToJsonElement(body).jsonObject
            assertEquals("session/list", envelope.getValue("method").jsonPrimitive.content)
            val args = envelope.getValue("payload").jsonObject.getValue("args").jsonObject
            assertTrue(args.containsKey("_request"))
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"items":[]}}}"""
        }
        val list = client.sessionList(connection)
        assertTrue(list.items.isEmpty())
        assertEquals("/api/session/list", captured.single().url.encodedPath)
        assertEquals("application/json", captured.single().body.contentType?.withoutParameters().toString())
        assertNull(captured.single().headers[HttpHeaders.Cookie])
    }

    @Test
    fun `cookie exchange follows token index url and keeps the set-cookie pair`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/?token=launch-token", request.url.encodedPath + "?" + request.url.encodedQuery)
            respond(
                content = "",
                status = HttpStatusCode.SeeOther,
                headers = headersOf(
                    HttpHeaders.SetCookie,
                    "dsh-auth-zz=v1.body; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict",
                ),
            )
        }
        val http = HttpClient(engine) {
            followRedirects = false
            install(ContentNegotiation) { json(json) }
        }
        val client = DshApiClient(http, json, downlinkFactory = unusedDownlinks())
        val tokened = DshConnection.from("http://127.0.0.1:18790", token = "launch-token")
        val authed = client.exchangeCookie(tokened)
        assertEquals("dsh-auth-zz=v1.body", authed.cookie)
    }

    @Test
    fun `screen connections reuse the cached cookie after exchange`() = runTest {
        var cookieHeaders = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/") {
                respond(
                    content = "",
                    status = HttpStatusCode.SeeOther,
                    headers = headersOf(
                        HttpHeaders.SetCookie,
                        "dsh-auth-zz=v1.body; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict",
                    ),
                )
            } else {
                if (!request.headers[HttpHeaders.Cookie].isNullOrBlank()) cookieHeaders += 1
                respond(
                    content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"items":[]}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val http = HttpClient(engine) {
            followRedirects = false
            install(ContentNegotiation) { json(json) }
        }
        val client = DshApiClient(http, json, mintRpcId = { "fixed" }, downlinkFactory = unusedDownlinks())
        // Manager-style connect exchanges with the tokened connection...
        client.exchangeCookie(DshConnection.from("http://192.168.1.8:3080", token = "launch-token"))
        // ...while screens call with their own cookie-less connection.
        val screenConn = DshConnection.from("http://192.168.1.8:3080")
        client.sessionList(screenConn)
        client.sessionList(screenConn)
        assertEquals(2, cookieHeaders)
    }

    @Test
    fun `http 401 clears the cached cookie for the next generation`() = runTest {
        var unauthorizedOnce = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/") {
                respond(
                    content = "",
                    status = HttpStatusCode.SeeOther,
                    headers = headersOf(
                        HttpHeaders.SetCookie,
                        "dsh-auth-zz=v1.body; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict",
                    ),
                )
            } else if (!unauthorizedOnce) {
                unauthorizedOnce = true
                respond(
                    content = "unauthorized",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                )
            } else {
                respond(
                    content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"items":[]}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val http = HttpClient(engine) {
            followRedirects = false
            install(ContentNegotiation) { json(json) }
        }
        val client = DshApiClient(http, json, mintRpcId = { "fixed" }, downlinkFactory = unusedDownlinks())
        client.exchangeCookie(DshConnection.from("http://192.168.1.8:3080", token = "launch-token"))
        val screenConn = DshConnection.from("http://192.168.1.8:3080")
        try {
            client.sessionList(screenConn)
            throw AssertionError("expected auth failure")
        } catch (_: DshAuthRequiredException) {
        }
        // Re-exchange mints a fresh cookie and the same screen connection works.
        client.exchangeCookie(DshConnection.from("http://192.168.1.8:3080", token = "launch-token"))
        client.sessionList(screenConn)
        assertTrue(unauthorizedOnce)
    }

    @Test
    fun `authed unary sends cookie and basic on every post`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val authed = DshConnection.from("https://dsh.wuxie233.com", "secret", "launch-token")
            .withCookie("dsh-auth-zz=v1.body")
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            when (envelope.getValue("method").jsonPrimitive.content) {
                "session/canOpenWorkspacePath" -> """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":false}}"""
                else -> error(envelope.getValue("method").jsonPrimitive.content)
            }
        }
        client.canOpenWorkspacePath(authed)
        val sent = captured.single()
        assertEquals("Basic OnNlY3JldA==", sent.headers[HttpHeaders.Authorization])
        assertEquals("dsh-auth-zz=v1.body", sent.headers[HttpHeaders.Cookie])
        assertEquals("/api/session/canOpenWorkspacePath", sent.url.encodedPath)
    }

    @Test
    fun `http 401 becomes DshAuthRequiredException`() = runTest {
        val engine = MockEngine {
            respond(
                content = "unauthorized",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val client = DshApiClient(http, json, downlinkFactory = unusedDownlinks())
        try {
            client.sessionList(DshConnection.from("https://dsh.wuxie233.com", "wrong"))
            throw AssertionError("expected auth failure")
        } catch (error: DshAuthRequiredException) {
            assertTrue(error.message!!.contains("authentication failed"))
        }
    }

    @Test
    fun `rpcId mismatch is a transport failure`() = runTest {
        val client = api(mutableListOf()) {
            """{"type":"server-response","rpcId":"other","result":{"ok":true,"value":{}}}"""
        }
        try {
            client.call(connection, "session/list", rpcId = "expected")
            throw AssertionError("expected mismatch")
        } catch (error: DshTransportException) {
            assertTrue(error.message!!.contains("rpcId mismatch"))
        }
    }

    @Test
    fun `session prompt mints requestId inside request args`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("session/prompt", envelope.getValue("method").jsonPrimitive.content)
            val requestArgs = envelope.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
            assertEquals("s1", requestArgs.getValue("sessionId").jsonPrimitive.content)
            assertEquals("queue", requestArgs.getValue("mode").jsonPrimitive.content)
            assertTrue(requestArgs.getValue("requestId").jsonPrimitive.content.isNotBlank())
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"accepted":true}}}"""
        }
        val result = client.sessionPrompt(
            connection,
            sessionId = "s1",
            mode = "queue",
            content = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hello") }) },
        )
        assertTrue(result.accepted)
        assertEquals("/api/session/prompt", captured.single().url.encodedPath)
    }

    @Test
    fun `approval and question answers post events result with client and event ids`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("\$events/result", envelope.getValue("method").jsonPrimitive.content)
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{}}}"""
        }
        client.answerApproval(connection, clientId = "client-1", eventId = "event-1", outcome = "allowed-once")
        client.answerQuestion(
            connection,
            clientId = "client-1",
            eventId = "event-2",
            answers = buildJsonObject { put("answers", buildJsonArray {}) },
        )
        captured.forEach { sent ->
            assertEquals("/api/\$events/result", sent.url.encodedPath)
        }
        val approvalArgs = json.parseToJsonElement((captured[0].body as TextContent).text)
            .jsonObject.getValue("payload").jsonObject.getValue("args").jsonObject
        assertEquals("client-1", approvalArgs.getValue("clientId").jsonPrimitive.content)
        assertEquals("event-1", approvalArgs.getValue("eventId").jsonPrimitive.content)
        assertEquals("allowed-once", approvalArgs.getValue("outcome").jsonPrimitive.content)
        val questionArgs = json.parseToJsonElement((captured[1].body as TextContent).text)
            .jsonObject.getValue("payload").jsonObject.getValue("args").jsonObject
        assertEquals("event-2", questionArgs.getValue("eventId").jsonPrimitive.content)
        assertEquals("result", questionArgs.getValue("outcome").jsonObject.getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun `llm providers decodes the raw id-name array`() = runTest {
        val client = api(mutableListOf()) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("llm/listProviders", envelope.getValue("method").jsonPrimitive.content)
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":[{"id":"deepseek-official","name":"DeepSeek"},{"id":"fac","name":"FAC"}]}}"""
        }
        val providers = client.llmProviders(connection)
        assertEquals(listOf("deepseek-official", "fac"), providers.providers.map { it.id })
        assertEquals("DeepSeek", providers.providers.first().name)
    }

    @Test
    fun `agent preset select posts agentId and decodes plain string`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val args = envelope.getValue("payload").jsonObject.getValue("args").jsonObject
            assertEquals("s1", args.getValue("agentId").jsonPrimitive.content)
            assertEquals("coder", args.getValue("agentPreset").jsonPrimitive.content)
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":"coder"}}"""
        }
        val selected = client.agentPresetSelect(connection, "s1", "coder")
        assertEquals("coder", selected.agentPreset)
    }

    @Test
    fun `session page folds records into history entries`() = runTest {
        val client = api(mutableListOf()) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val requestArgs = envelope.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
            assertEquals("session", requestArgs.getValue("address").jsonObject.getValue("kind").jsonPrimitive.content)
            assertEquals(12072L, requestArgs.getValue("throughSeq").jsonPrimitive.content.toLong())
            assertEquals(1L, requestArgs.getValue("beforeSeq").jsonPrimitive.content.toLong())
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"records":[{"type":"event","event":{"type":"user/message","seq":1,"time":1}},{"type":"chunks","event":{"type":"chunkrow/delta","seq":2,"time":2}}],"hasMore":false}}}"""
        }
        val history = client.sessionHistory(
            connection,
            address = DshSessionAddress.Session("s1"),
            throughSeq = 12072L,
            beforeSeq = 1L,
        )
        assertEquals(2, history.events.size)
        assertEquals("user/message", history.events.first().event.type)
        assertTrue(!history.hasMore)
    }

    @Test
    fun `session page with subagent address encodes parent child and mode`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val requestArgs = envelope.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
            val address = requestArgs.getValue("address").jsonObject
            assertEquals("subagent", address.getValue("kind").jsonPrimitive.content)
            assertEquals("parent-1", address.getValue("parentSessionId").jsonPrimitive.content)
            assertEquals("child-1", address.getValue("childSessionId").jsonPrimitive.content)
            assertEquals("continuable", address.getValue("mode").jsonPrimitive.content)
            assertEquals(12L, requestArgs.getValue("throughSeq").jsonPrimitive.content.toLong())
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"records":[],"hasMore":false}}}"""
        }
        client.sessionPage(
            connection,
            address = DshSessionAddress.Subagent(
                parentSessionId = "parent-1",
                childSessionId = "child-1",
                mode = "continuable",
            ),
            throughSeq = 12L,
        )
        assertEquals("/api/session/page", captured.single().url.encodedPath)
    }

    @Test
    fun `subagent prompt includes mode continuable`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("subagents/prompt", envelope.getValue("method").jsonPrimitive.content)
            val requestArgs = envelope.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
            assertEquals("parent-1", requestArgs.getValue("parentSessionId").jsonPrimitive.content)
            assertEquals("child-1", requestArgs.getValue("childSessionId").jsonPrimitive.content)
            assertEquals("continuable", requestArgs.getValue("mode").jsonPrimitive.content)
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"messageId":"m-1"}}}"""
        }
        val receipt = client.subagentPrompt(
            connection,
            parentSessionId = "parent-1",
            childSessionId = "child-1",
            content = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hello") }) },
        )
        assertEquals("m-1", receipt.messageId)
        assertEquals("/api/subagents/prompt", captured.single().url.encodedPath)
    }

    @Test
    fun `mux demux routes item error and end by stream id`() = runTest {
        val downlink = FakeDownlink()
        val client = DshApiClient(unusedHttp(), json, downlinkFactory = unusedDownlinks())
        downlink.incoming.send(
            """{"type":"item","streamId":"st-1","value":{"type":"ready","clientId":"c1","host":{"home":"/root"}}}""",
        )
        downlink.incoming.send("""{"type":"item","streamId":"st-2","value":null}""")
        downlink.incoming.send("""{"type":"error","streamId":"st-3","error":{"code":"internal","message":"boom","details":{}}}""")
        downlink.incoming.send("""{"type":"end","streamId":"st-4"}""")
        downlink.incoming.close()
        val messages = client.muxMessages(downlink).toList()
        assertEquals(4, messages.size)
        val ready = messages[0] as DshMuxWireMessage.Item
        assertEquals("st-1", ready.streamId)
        val parsed = parseEventsFrame(ready.value)
        assertEquals("c1", (parsed as DshEventsFrame.Ready).clientId)
        assertEquals("st-3", (messages[2] as DshMuxWireMessage.WireError).streamId)
        assertEquals("st-4", (messages[3] as DshMuxWireMessage.End).streamId)
        assertTrue(downlink.sent.isEmpty())
    }

    @Test
    fun `stream open sends logical open frame with args payload`() = runTest {
        val downlink = FakeDownlink()
        val client = DshApiClient(unusedHttp(), json, downlinkFactory = unusedDownlinks())
        client.sendStreamOpen(downlink, "\$events", "st-1", JsonObject(emptyMap()))
        val sent = json.parseToJsonElement(downlink.sent.single()).jsonObject
        assertEquals("open", sent.getValue("type").jsonPrimitive.content)
        assertEquals("st-1", sent.getValue("streamId").jsonPrimitive.content)
        assertEquals("\$events", sent.getValue("endpoint").jsonPrimitive.content)
        assertTrue(sent.getValue("payload").jsonObject.containsKey("args"))
        client.sendStreamCancel(downlink, "st-1")
        assertEquals("cancel", json.parseToJsonElement(downlink.sent.last()).jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `goal create posts agentId plus request objective`() = runTest {
        val client = api(mutableListOf()) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val args = envelope.getValue("payload").jsonObject.getValue("args").jsonObject
            assertEquals("s1", args.getValue("agentId").jsonPrimitive.content)
            assertEquals("ship", args.getValue("request").jsonObject.getValue("objective").jsonPrimitive.content)
            """{"type":"server-response","rpcId":"${envelope.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":{"ref":{"id":"g1","revision":1}}}}"""
        }
        assertEquals("g1", client.goalCreate(connection, "s1", "ship").ref.id)
    }

    @Test
    fun `workspace create decodes typed values`() = runTest {
        val client = api(mutableListOf()) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            when (envelope.getValue("method").jsonPrimitive.content) {
                "workspace/create" -> """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"workspace":{"workspaceId":"w1","path":"/tmp","folders":[],"title":"tmp","sessionIds":[],"createdAt":"t","updatedAt":"t"},"created":true}}}"""
                else -> error(envelope.getValue("method").jsonPrimitive.content)
            }
        }
        assertTrue(client.workspaceCreate(connection, "/tmp").created)
    }

    @Test
    fun `loopback-only methods throw without posting on LAN`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { error("must not post") }
        try {
            client.call(connection, "credentials/set")
            throw AssertionError("expected loopback fence")
        } catch (error: DshLoopbackUnavailableException) {
            assertEquals("credentials/set", error.method)
        }
        assertTrue(captured.isEmpty())
    }

    private fun unusedHttp(): HttpClient = HttpClient(MockEngine { error("http unused") })

    private fun unusedDownlinks(): DshDownlinkFactory = object : DshDownlinkFactory {
        override suspend fun openMux(connection: DshConnection) = error("unused")
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
