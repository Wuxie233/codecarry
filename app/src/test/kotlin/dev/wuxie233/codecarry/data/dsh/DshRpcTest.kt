package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshRpcTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `client-request envelope encodes type rpcId method payload`() {
        val request = DshClientRequest(
            rpcId = "rpc-1",
            method = "host.describe",
            payload = JsonObject(emptyMap()),
        )
        val encoded = json.encodeToString(DshClientRequest.serializer(), request)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("client-request", obj.getValue("type").jsonPrimitive.content)
        assertEquals("rpc-1", obj.getValue("rpcId").jsonPrimitive.content)
        assertEquals("host.describe", obj.getValue("method").jsonPrimitive.content)
        assertEquals(DshRpc.unaryPath("host.describe"), "/api/host.describe")
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
    fun `loopback-only methods match the DSH Host pin and hide on LAN`() {
        assertTrue(DshRpc.isLoopbackOnly("host.pickDirectory"))
        assertTrue(DshRpc.isLoopbackOnly("credentials.set"))
        assertFalse(DshRpc.isLoopbackOnly("host.describe"))
        assertFalse(DshRpc.isLoopbackOnly("session.prompt"))
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
        assertFalse(DshMethods.availableOn(passwordlessPublic).contains("credentials.set"))
    }

    @Test
    fun `http urls map to downlink-only websocket paths`() {
        assertEquals(
            "ws://192.168.1.8:3080/api/events.mux",
            dshHttpToWebSocketUrl("http://192.168.1.8:3080/", DshRpc.MUX_EVENTS_PATH),
        )
        assertEquals(
            "wss://dsh.example/api/events.host",
            dshHttpToWebSocketUrl("https://dsh.example", DshRpc.HOST_EVENTS_PATH),
        )
    }

    @Test
    fun `unary catalog includes session workspace goal automation and subagent`() {
        val methods = DshMethods.unary.toSet()
        listOf(
            "session.list", "session.prompt", "session.updateQueue", "workspace.list",
            "goal.create", "automation.list", "subagent.prompt", "skill.list", "git.describe",
            "host.listDirectory", "host.createDirectory", "settings.mutate", "llm.providers",
            "systemPrompt.list", "agentPreset.list", "agentPreset.select",
        ).forEach { method -> assertTrue(method in methods) }
        DshRpc.LOOPBACK_ONLY_METHODS.forEach { method -> assertTrue(method in methods) }
    }

    @Test
    fun `unknown mux type is preserved rather than dropped`() {
        val payload = buildJsonObject {
            put("type", "future/frame")
            put("extra", "keep")
        }
        val frame = parseMuxFrame(payload)
        assertTrue(frame is DshMuxFrame.Unknown)
        assertEquals("future/frame", frame.type)
    }

    @Test
    fun `host session-added and workspace frames parse`() {
        val added = parseHostFrame(
            buildJsonObject {
                put("type", "host/session-added")
                put("sessionId", "s1")
                put("blank", true)
                put("cwd", "/tmp")
            },
        ) as DshHostFrame.SessionAdded
        assertEquals("s1", added.sessionId)
        assertTrue(added.blank)
        assertEquals("/tmp", added.cwd)

        val changed = parseHostFrame(
            buildJsonObject {
                put("type", "host/workspace-changed")
                put("workspace", buildJsonObject {
                    put("workspaceId", "w1")
                    put("title", "one")
                })
            },
        ) as DshHostFrame.WorkspaceChanged
        assertEquals("w1", changed.workspace.getValue("workspaceId").jsonPrimitive.content)
    }
}
