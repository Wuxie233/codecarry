package dev.minios.ocremote.data.codex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val clients = mutableListOf<CodexAppServerClient>()

    @After
    fun tearDown() {
        clients.forEach(CodexAppServerClient::close)
    }

    @Test
    fun `connect initializes experimental API then sends initialized notification`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)

        val connecting = async { client.connect() }
        val initialize = transport.takeSentObject()
        assertFalse(initialize.containsKey("jsonrpc"))
        assertEquals("initialize", initialize["method"]?.jsonPrimitive?.content)
        assertEquals(
            true,
            initialize["params"]
                ?.jsonObject
                ?.get("capabilities")
                ?.jsonObject
                ?.get("experimentalApi")
                ?.jsonPrimitive
                ?.content
                ?.toBoolean(),
        )
        assertEquals(
            false,
            initialize["params"]
                ?.jsonObject
                ?.get("capabilities")
                ?.jsonObject
                ?.get("mcpServerOpenaiFormElicitation")
                ?.jsonPrimitive
                ?.content
                ?.toBoolean(),
        )
        assertEquals(
            "oc-remote",
            initialize["params"]
                ?.jsonObject
                ?.get("clientInfo")
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content,
        )

        transport.respond(
            id = initialize.getValue("id").jsonPrimitive,
            result = buildJsonObject {
                put("userAgent", "codex-cli/0.144.3")
                put("codexHome", "/home/codex")
                put("platformFamily", "unix")
                put("platformOs", "linux")
                put("futureField", "preserved")
            },
        )

        val result = connecting.await()
        assertEquals("codex-cli/0.144.3", result.userAgent)
        assertEquals("preserved", result.extra["futureField"]?.jsonPrimitive?.content)
        val initialized = transport.takeSentObject()
        assertEquals("initialized", initialized["method"]?.jsonPrimitive?.content)
        assertFalse(initialized.containsKey("id"))
    }

    @Test
    fun `concurrent requests correlate out of order responses by id`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)

        val alpha = async { client.request("test/alpha") }
        val beta = async { client.request("test/beta") }
        val sent = listOf(transport.takeSentObject(), transport.takeSentObject())
        val alphaRequest = sent.first { it["method"]?.jsonPrimitive?.content == "test/alpha" }
        val betaRequest = sent.first { it["method"]?.jsonPrimitive?.content == "test/beta" }

        transport.respond(
            id = betaRequest.getValue("id").jsonPrimitive,
            result = JsonPrimitive("beta-result"),
        )
        transport.respond(
            id = alphaRequest.getValue("id").jsonPrimitive,
            result = JsonPrimitive("alpha-result"),
        )

        assertEquals("alpha-result", alpha.await().jsonPrimitive.content)
        assertEquals("beta-result", beta.await().jsonPrimitive.content)
    }

    @Test
    fun `steer turn sends active turn precondition and text input`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)

        val steering = async {
            client.steerTurn(
                "thread-1",
                "turn-1",
                "Focus on tests",
                clientUserMessageId = "client-message-1",
            )
        }
        val request = transport.takeSentObject()
        assertEquals("turn/steer", request["method"]?.jsonPrimitive?.content)
        val params = request.getValue("params").jsonObject
        assertEquals("thread-1", params["threadId"]?.jsonPrimitive?.content)
        assertEquals("turn-1", params["expectedTurnId"]?.jsonPrimitive?.content)
        assertEquals("client-message-1", params["clientUserMessageId"]?.jsonPrimitive?.content)
        assertEquals(
            "Focus on tests",
            (params.getValue("input") as kotlinx.serialization.json.JsonArray)
                .single()
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content,
        )
        transport.respond(
            id = request.getValue("id").jsonPrimitive,
            result = buildJsonObject { put("turnId", "turn-1") },
        )
        assertEquals("turn-1", steering.await())
    }

    @Test
    fun `start turn treats response as acceptance and leaves active identity to events`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)

        val starting = async {
            client.startTurn(
                threadId = "thread-1",
                text = "Ship it",
                clientUserMessageId = "client-message-1",
            )
        }
        val request = transport.takeSentObject()
        assertEquals("turn/start", request["method"]?.jsonPrimitive?.content)
        assertEquals(
            "client-message-1",
            request["params"]?.jsonObject?.get("clientUserMessageId")?.jsonPrimitive?.content,
        )

        transport.respond(
            id = request.getValue("id").jsonPrimitive,
            result = buildJsonObject {
                put("turn", buildJsonObject {
                    put("id", "provisional-turn-id")
                    put("status", "inProgress")
                })
            },
        )

        assertEquals(Unit, starting.await())
    }

    @Test
    fun `unsubscribe thread uses the experimental app server wire method`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)

        val unsubscribing = async { client.unsubscribeThread("thread-1") }
        val request = transport.takeSentObject()
        assertEquals("thread/unsubscribe", request["method"]?.jsonPrimitive?.content)
        assertEquals(
            "thread-1",
            request["params"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content,
        )
        transport.respond(
            request.getValue("id").jsonPrimitive,
            buildJsonObject { put("status", "unsubscribed") },
        )

        assertEquals("unsubscribed", unsubscribing.await())
    }

    @Test
    fun `disconnect fails requests that are still pending`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)

        supervisorScope {
            val pending = async { client.request("test/willDisconnect") }
            transport.takeSentObject()
            transport.incoming.close()

            try {
                pending.await()
                fail("Expected pending request to fail when the connection closes")
            } catch (error: CodexDisconnectedException) {
                assertTrue(error.message.orEmpty().contains("connection closed"))
            }
        }
    }

    @Test
    fun `initialize failure preserves the server error state`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)

        val connecting = async { runCatching { client.connect() } }
        val initialize = transport.takeSentObject()
        transport.respondError(
            id = initialize.getValue("id").jsonPrimitive,
            code = -32001,
            message = "invalid capability token",
        )

        val error = connecting.await().exceptionOrNull()
        assertTrue(error is CodexRpcException)
        assertEquals("invalid capability token", error?.message)
        val state = client.connectionState.value
        assertTrue(state is CodexClientConnectionState.Failed)
        assertEquals(
            "invalid capability token",
            (state as CodexClientConnectionState.Failed).error.message,
        )
    }

    @Test
    fun `disconnect during initialize fails without waiting for request timeout`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)

        val connecting = async { runCatching { client.connect() } }
        transport.takeSentObject()
        transport.incoming.close()
        runCurrent()

        assertTrue(connecting.isCompleted)
        assertTrue(connecting.await().exceptionOrNull() is CodexDisconnectedException)
    }

    @Test
    fun `unknown notification and item fields remain available`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)
        val notification = async(start = CoroutineStart.UNDISPATCHED) { client.notifications.first() }

        transport.incoming.send(
            """
            {
              "jsonrpc":"2.0",
              "method":"future/item/changed",
              "serverSequence":17,
              "params":{
                "threadId":"thread-1",
                "newPayload":{"enabled":true},
                "item":{
                  "id":"item-1",
                  "type":"futureTool",
                  "status":"working",
                  "futureItemField":"kept"
                }
              }
            }
            """.trimIndent(),
        )

        val received = notification.await()
        assertEquals("future/item/changed", received.method)
        assertEquals("thread-1", received.threadId)
        assertEquals("17", received.extra["serverSequence"]?.jsonPrimitive?.content)
        assertEquals("futureTool", received.item?.type)
        assertEquals(
            "kept",
            received.item?.extra?.get("futureItemField")?.jsonPrimitive?.content,
        )
        assertNotNull(received.params["newPayload"])
    }

    @Test
    fun `server approval request is exposed and can be replied to`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)
        val serverRequest = async(start = CoroutineStart.UNDISPATCHED) { client.serverRequests.first() }

        transport.incoming.send(
            """
            {
              "jsonrpc":"2.0",
              "id":"approval-7",
              "method":"item/commandExecution/requestApproval",
              "params":{
                "threadId":"thread-1",
                "turnId":"turn-1",
                "itemId":"item-1",
                "command":"git status",
                "cwd":"/workspace",
                "startedAtMs":42,
                  "futureApprovalField":"kept",
                  "availableDecisions":["accept","decline"]
              }
            }
            """.trimIndent(),
        )

        val request = serverRequest.await()
        assertEquals(CodexApprovalKind.COMMAND_EXECUTION, request.approval?.kind)
        assertEquals("git status", request.approval?.command)
        assertEquals(
            "kept",
            request.approval?.extra?.get("futureApprovalField")?.jsonPrimitive?.content,
        )

        client.replyApproval(requireNotNull(request.approval), JsonPrimitive("accept"))
        val reply = transport.takeSentObject()
        assertEquals("approval-7", reply["id"]?.jsonPrimitive?.content)
        assertEquals(
            "accept",
            reply["result"]?.jsonObject?.get("decision")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `approval rejects a decision omitted from available decisions`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)
        val serverRequest = async(start = CoroutineStart.UNDISPATCHED) { client.serverRequests.first() }
        transport.incoming.send(
            """
            {"id":"approval-8","method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","startedAtMs":10,"availableDecisions":["accept","decline"]}}
            """.trimIndent(),
        )

        val approval = requireNotNull(serverRequest.await().approval)
        val error = runCatching { client.replyApproval(approval, "acceptForSession") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("not offered"))
    }

    @Test
    fun `permission approval without available decisions replies with session grant profile`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)
        val serverRequest = async(start = CoroutineStart.UNDISPATCHED) { client.serverRequests.first() }
        transport.incoming.send(
            """{"id":"approval-9","method":"item/permissions/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","environmentId":"production","cwd":"/workspace","startedAtMs":10,"permissions":{"network":{"enabled":true}}}}""",
        )
        val approval = requireNotNull(serverRequest.await().approval)

        client.replyPermissionApproval(
            approval,
            decision = "acceptForSession",
            grant = CodexPermissionGrant(
                permissions = approval.permissions?.jsonObject ?: JsonObject(emptyMap()),
                scope = CodexPermissionGrantScope.SESSION,
            ),
        )
        val reply = transport.takeSentObject()

        assertEquals("approval-9", reply["id"]?.jsonPrimitive?.content)
        assertEquals("session", reply["result"]?.jsonObject?.get("scope")?.jsonPrimitive?.content)
        assertEquals(
            true,
            reply["result"]?.jsonObject
                ?.get("permissions")?.jsonObject
                ?.get("network")?.jsonObject
                ?.get("enabled")?.jsonPrimitive
                ?.content?.toBoolean(),
        )
        assertFalse(reply["result"]?.jsonObject?.containsKey("decision") == true)
    }

    @Test
    fun `tool user input request parses questions and reply uses answer map wire shape`() = runTest {
        val transport = FakeTransport()
        val client = newClient(transport, backgroundScope)
        initialize(client, transport)
        val serverRequest = async(start = CoroutineStart.UNDISPATCHED) { client.serverRequests.first() }
        transport.incoming.send(
            """
            {
              "id":9,
              "method":"item/tool/requestUserInput",
              "params":{
                "threadId":"thread-1",
                "turnId":"turn-1",
                "itemId":"item-1",
                "autoResolutionMs":30000,
                "questions":[{
                  "id":"target",
                  "header":"Target",
                  "question":"Where should this run?",
                  "options":[{"label":"Local","description":"Run here"}],
                  "isOther":true
                }]
              }
            }
            """.trimIndent(),
        )
        val request = serverRequest.await()
        val userInput = requireNotNull(request.userInput)
        assertEquals(30_000L, userInput.autoResolutionMs)
        assertEquals("Where should this run?", userInput.questions.single().question)
        assertEquals("Local", userInput.questions.single().options.single().label)
        assertTrue(userInput.questions.single().isOther)

        client.replyUserInput(userInput, mapOf("target" to listOf("Local")))
        val reply = transport.takeSentObject()
        assertEquals("9", reply["id"]?.jsonPrimitive?.content)
        assertEquals(
            "Local",
            reply["result"]
                ?.jsonObject
                ?.get("answers")
                ?.jsonObject
                ?.get("target")
                ?.jsonObject
                ?.get("answers")
                ?.let { it as kotlinx.serialization.json.JsonArray }
                ?.single()
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `model page maps picker fields and preserves additions`() {
        val page = CodexModelListPage.fromJson(
            json.parseToJsonElement(
                """
                {
                  "data":[{
                    "id":"gpt-5.4",
                    "model":"gpt-5.4",
                    "displayName":"GPT-5.4",
                    "description":"Best coding model",
                    "isDefault":true,
                    "hidden":false,
                    "defaultReasoningEffort":"high",
                    "supportedReasoningEfforts":[{"reasoningEffort":"high","description":"Deep"}],
                    "inputModalities":["text","image"],
                    "supportsPersonality":true,
                    "serviceTiers":[{"id":"fast","name":"Fast","description":"Low latency"}],
                    "futureModelField":7
                  }],
                  "nextCursor":"next"
                }
                """.trimIndent(),
            ),
        )

        val model = page.models.single()
        assertEquals("gpt-5.4", model.model)
        assertTrue(model.isDefault)
        assertEquals(listOf("text", "image"), model.inputModalities)
        assertEquals("high", model.supportedReasoningEfforts.single().reasoningEffort)
        assertEquals("fast", model.serviceTiers.single().id)
        assertEquals("7", model.extra["futureModelField"]?.jsonPrimitive?.content)
        assertEquals("next", page.nextCursor)
    }

    @Test
    fun `thread session preserves active model and reasoning effort`() {
        val session = CodexThreadSession.fromJson(
            json.parseToJsonElement(
                """
                {
                  "thread":{"id":"thread-1"},
                  "model":"gpt-5.4",
                  "modelProvider":"openai",
                  "reasoningEffort":"high",
                  "cwd":"/workspace",
                  "approvalPolicy":"on-request",
                  "sandbox":"workspace-write"
                }
                """.trimIndent(),
            ),
        )

        assertEquals("gpt-5.4", session.model)
        assertEquals("high", session.reasoningEffort)
        assertFalse(session.extra.containsKey("reasoningEffort"))
    }

    @Test
    fun `thread mapping keeps typed fields and unknown data`() {
        val thread = CodexThread.fromJson(
            json.parseToJsonElement(
                """
                {
                  "id":"thread-1",
                  "sessionId":"session-1",
                  "name":"Fix login",
                  "preview":"Please fix login",
                  "cwd":"/workspace",
                  "modelProvider":"openai",
                  "cliVersion":"0.144.3",
                  "createdAt":100,
                  "updatedAt":200,
                  "ephemeral":false,
                  "status":{"type":"active","activeFlags":["waitingOnApproval"],"newStatusField":1},
                  "source":"appServer",
                  "futureThreadField":{"value":9},
                  "turns":[{
                    "id":"turn-1",
                    "status":"completed",
                    "newTurnField":"kept",
                    "items":[{
                      "id":"message-1",
                      "type":"agentMessage",
                      "text":"Done",
                      "memoryCitation":{"source":"memory.md"}
                    }]
                  }]
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertEquals("thread-1", thread.id)
        assertEquals("Fix login", thread.name)
        assertEquals("active", thread.status.type)
        assertEquals(listOf("waitingOnApproval"), thread.status.activeFlags)
        assertEquals("completed", thread.turns.single().status)
        assertEquals("Done", thread.turns.single().items.single().text)
        assertTrue(thread.extra.containsKey("futureThreadField"))
        assertEquals(
            "kept",
            thread.turns.single().extra["newTurnField"]?.jsonPrimitive?.content,
        )
        assertTrue(thread.turns.single().items.single().extra.containsKey("memoryCitation"))
    }

    @Test
    fun `websocket URL normalization requires TLS except on loopback`() {
        assertEquals("wss://host.test/ws", normalizeCodexWebSocketUrl("wss://host.test/ws"))
        assertEquals("ws://localhost:8765", normalizeCodexWebSocketUrl("ws://localhost:8765"))
        assertEquals("ws://127.0.0.1:8765", normalizeCodexWebSocketUrl("ws://127.0.0.1:8765"))
        assertEquals("ws://[::1]:8765", normalizeCodexWebSocketUrl("ws://[::1]:8765"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeCodexWebSocketUrl("ws://host.test:8080/ws")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeCodexWebSocketUrl("http://host.test/ws")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeCodexWebSocketUrl("https://host.test/ws")
        }
    }

    private fun newClient(
        transport: FakeTransport,
        scope: CoroutineScope,
    ): CodexAppServerClient = CodexAppServerClient(
        transport = transport,
        json = json,
        scope = scope,
    ).also(clients::add)

    private suspend fun initialize(
        client: CodexAppServerClient,
        transport: FakeTransport,
    ) {
        val connecting = CoroutineScope(kotlin.coroutines.coroutineContext).async { client.connect() }
        val initialize = transport.takeSentObject()
        transport.respond(
            id = initialize.getValue("id").jsonPrimitive,
            result = buildJsonObject {
                put("userAgent", "codex-test")
                put("codexHome", "/tmp/codex")
                put("platformFamily", "unix")
                put("platformOs", "linux")
            },
        )
        connecting.await()
        val initialized = transport.takeSentObject()
        assertEquals("initialized", initialized["method"]?.jsonPrimitive?.content)
    }

    private inner class FakeTransport : CodexRpcTransport {
        val incoming = Channel<String>(Channel.UNLIMITED)
        private val sent = Channel<String>(Channel.UNLIMITED)
        var connected = false

        override suspend fun connect() {
            connected = true
        }

        override suspend fun send(text: String) {
            check(connected)
            sent.send(text)
        }

        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

        override fun close() {
            connected = false
            incoming.close()
            sent.close()
        }

        suspend fun takeSentObject(): JsonObject = json.parseToJsonElement(sent.receive()).jsonObject

        suspend fun respond(id: JsonPrimitive, result: JsonObject) {
            respond(id, result as kotlinx.serialization.json.JsonElement)
        }

        suspend fun respond(id: JsonPrimitive, result: kotlinx.serialization.json.JsonElement) {
            incoming.send(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", result)
                    },
                ),
            )
        }

        suspend fun respondError(id: JsonPrimitive, code: Long, message: String) {
            incoming.send(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("id", id)
                        put("error", buildJsonObject {
                            put("code", code)
                            put("message", message)
                        })
                    },
                ),
            )
        }
    }
}
