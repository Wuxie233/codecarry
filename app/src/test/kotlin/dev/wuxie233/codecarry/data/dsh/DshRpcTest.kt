package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshRpcTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `client-request envelope encodes type rpcId method and args payload`() {
        val request = DshClientRequest(
            rpcId = "rpc-1",
            method = "session/list",
            payload = DshRpc.argsPayload(DshRpc.listRequestArgs()),
        )
        val encoded = json.encodeToString(DshClientRequest.serializer(), request)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("client-request", obj.getValue("type").jsonPrimitive.content)
        assertEquals("rpc-1", obj.getValue("rpcId").jsonPrimitive.content)
        assertEquals("session/list", obj.getValue("method").jsonPrimitive.content)
        val args = obj.getValue("payload").jsonObject.getValue("args").jsonObject
        assertTrue(args.containsKey("_request"))
        assertEquals("/api/session/list", DshRpc.unaryPath("session/list"))
    }

    @Test
    fun `server-response envelope round-trips ok and error branches`() {
        val ok = json.decodeFromString(
            DshServerResponse.serializer(),
            """{"type":"server-response","rpcId":"rpc-1","result":{"ok":true,"value":{"version":"1"}}}""",
        )
        assertTrue(ok.result.ok)
        assertEquals("1", ok.result.value!!.jsonObject.getValue("version").jsonPrimitive.content)

        val error = json.decodeFromString(
            DshServerResponse.serializer(),
            """{"type":"server-response","rpcId":"rpc-2","result":{"ok":false,"error":{"code":"session-not-found","message":"gone","details":{"sessionId":"s1"}}}}""",
        )
        assertFalse(error.result.ok)
        assertEquals("session-not-found", error.result.error!!.code)
    }

    @Test
    fun `loopback-only methods match the Connection pin and hide on LAN`() {
        assertTrue(DshRpc.isLoopbackOnly("directoryPicker/pick"))
        assertTrue(DshRpc.isLoopbackOnly("credentials/set"))
        assertFalse(DshRpc.isLoopbackOnly("session/list"))
        assertFalse(DshRpc.isLoopbackOnly("session/prompt"))
        assertTrue(isDshLoopbackHostname("127.0.0.1"))
        assertTrue(isDshLoopbackHostname("localhost"))
        assertTrue(isDshLoopbackHostname("[::1]"))
        assertTrue(isDshLoopbackHostname("127.8.9.10"))
        assertFalse(isDshLoopbackHostname("192.168.1.8"))
        assertFalse(isDshLoopbackHostname("127.0.0.256"))
        val lan = DshConnection.from("http://192.168.1.8:3080")
        assertFalse(lan.isLoopback)
        DshRpc.LOOPBACK_ONLY_METHODS.forEach { method ->
            assertFalse(method, DshMethods.availableOn(lan).contains(method))
        }
        val loopback = DshConnection.from("http://127.0.0.1:3080")
        assertTrue(loopback.isLoopback)
        assertTrue(isDshLoopbackUrl("http://[::1]:3080"))
        assertTrue(DshMethods.availableOn(loopback).containsAll(DshRpc.LOOPBACK_ONLY_METHODS))
        assertEquals(DshMethods.unary.size, DshMethods.availableOn(loopback).size)
        val authedPublic = DshConnection.from("https://dsh.wuxie233.com", "secret")
        assertTrue(authedPublic.hasBasicAuth)
        assertTrue(authedPublic.isLoopback)
        assertTrue(DshMethods.availableOn(authedPublic).containsAll(DshRpc.LOOPBACK_ONLY_METHODS))
        assertEquals("Basic OnNlY3JldA==", authedPublic.basicAuthorization)
        val passwordlessPublic = DshConnection.from("https://dsh.wuxie233.com")
        assertFalse(passwordlessPublic.isLoopback)
        assertFalse(DshMethods.availableOn(passwordlessPublic).contains("credentials/set"))
    }

    @Test
    fun `token query is stripped into the connection token`() {
        val connection = DshConnection.from("http://127.0.0.1:18790/?token=abc123")
        assertEquals("http://127.0.0.1:18790", connection.baseUrl)
        assertEquals("abc123", connection.token)
        val explicit = DshConnection.from("http://127.0.0.1:18790", token = "tok")
        assertEquals("tok", explicit.token)
        val otherQuery = stripTokenQuery("http://h:1/?a=1&token=x&b=2")
        assertEquals("http://h:1/?a=1&b=2", otherQuery.first)
        assertEquals("x", otherQuery.second)
        val none = stripTokenQuery("http://h:1/path")
        assertNull(none.second)
    }

    @Test
    fun `index url carries the token and cookie pair keeps name equals value`() {
        assertEquals(
            "http://127.0.0.1:18790/?token=abc",
            dshIndexUrl("http://127.0.0.1:18790", "abc"),
        )
        assertEquals("http://127.0.0.1:18790/", dshIndexUrl("http://127.0.0.1:18790", null))
        assertEquals(
            "dsh-auth-zz=v1.body",
            dshCookiePair("dsh-auth-zz=v1.body; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict"),
        )
        assertNull(dshCookiePair("novalue"))
    }

    @Test
    fun `http urls map to the single remote mux socket`() {
        assertEquals(
            "ws://192.168.1.8:3080/api/remote.mux",
            dshHttpToWebSocketUrl("http://192.168.1.8:3080/", DshRpc.REMOTE_MUX_PATH),
        )
        assertEquals(
            "wss://dsh.example/api/remote.mux",
            dshHttpToWebSocketUrl("https://dsh.example", DshRpc.REMOTE_MUX_PATH),
        )
    }

    @Test
    fun `unary catalog uses slash remote endpoints`() {
        val methods = DshMethods.unary.toSet()
        listOf(
            "session/list", "session/prompt", "session/page", "session/modelCatalog",
            "session/updateQueue", "workspace/create",
            "goals/create", "automation/list", "subagents/prompt", "skills/list", "git/describe",
            "directoryPicker/list", "directoryPicker/createDirectory", "settings/mutate",
            "llm/listProviders", "session/modelCatalog",
            "systemPrompt/list", "agentPresets/list", "agentPresets/select", "\$events/result",
        ).forEach { method -> assertTrue(method in methods) }
        DshRpc.LOOPBACK_ONLY_METHODS.forEach { method -> assertTrue(method in methods) }
        assertFalse("host.describe" in methods)
        assertFalse("workspace.list" in methods)
        assertFalse("skill.catalog" in methods)
    }

    @Test
    fun `generation ready requires mux events control and workspace`() {
        val partial = DshGenerationState(
            status = DshGenerationStatus.Ready,
            describe = dshHostDescribeFromReady("/root"),
            muxOpen = true,
            eventsReady = true,
            controlReady = true,
        )
        assertFalse(partial.isReady)
        val ready = partial.copy(workspaceReady = true)
        assertTrue(ready.isReady)
        assertEquals("/root", ready.describe!!.home)
    }

    @Test
    fun `args helpers wrap named wire args`() {
        val wrapped = DshRpc.argsPayload(DshRpc.requestArgs(JsonObject(emptyMap())))
        val args = wrapped.getValue("args").jsonObject
        assertTrue(args.containsKey("request"))
        val listArgs = DshRpc.argsPayload(DshRpc.listRequestArgs("cursor-1"))
            .getValue("args").jsonObject.getValue("_request").jsonObject
        assertEquals("cursor-1", listArgs.getValue("cursor").jsonPrimitive.content)
    }
}
