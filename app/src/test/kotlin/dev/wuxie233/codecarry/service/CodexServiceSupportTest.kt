package dev.wuxie233.codecarry.service

import dev.wuxie233.codecarry.data.codex.CodexClientConnectionState
import dev.wuxie233.codecarry.data.codex.CodexAppServerClient
import dev.wuxie233.codecarry.data.codex.CodexManagedConnection
import dev.wuxie233.codecarry.data.codex.CodexRpcTransport
import dev.wuxie233.codecarry.data.codex.CodexServerRequest
import dev.wuxie233.codecarry.data.codex.CodexNotification
import dev.wuxie233.codecarry.data.codex.CodexThreadKey
import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.domain.model.ServerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexServiceSupportTest {
    @Test
    fun `failed Codex connection remains disconnectable while retrying`() {
        val managed = managed(CodexClientConnectionState.Failed(IllegalStateException("offline")))

        val state = codexServiceConnectionState(managed)

        assertFalse(state.connected)
        assertTrue(state.connecting)
        assertEquals("offline", state.error)
    }

    @Test
    fun `connected Codex connection clears retry state`() {
        val initialized = dev.wuxie233.codecarry.data.codex.CodexInitializeResult("codex-test")

        val state = codexServiceConnectionState(managed(CodexClientConnectionState.Connected(initialized)))

        assertTrue(state.connected)
        assertFalse(state.connecting)
        assertNull(state.error)
    }

    @Test
    fun `request notification identity distinguishes requests in one thread`() {
        val first = request("approval-1", "item/fileChange/requestApproval")
        val second = request("approval-2", "item/fileChange/requestApproval")

        assertEquals(CodexRequestNotificationKind.APPROVAL, first.notificationKind())
        assertTrue(
            CodexNotificationIdentity.requestId("server-1", first) !=
                CodexNotificationIdentity.requestId("server-1", second),
        )
    }

    @Test
    fun `MCP and user input requests are actionable notifications`() {
        val mcp = request("mcp-1", "mcpServer/elicitation/request")
        val userInput = request(
            "input-1",
            "item/tool/requestUserInput",
            buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "item-1")
                put("questions", kotlinx.serialization.json.JsonArray(emptyList()))
            },
        )

        assertEquals(CodexRequestNotificationKind.MCP_ELICITATION, mcp.notificationKind())
        assertEquals(CodexRequestNotificationKind.USER_INPUT, userInput.notificationKind())
    }

    @Test
    fun `Codex errors replace only Codex server entries`() {
        val merged = mergeCodexConnectionErrors(
            current = mapOf("open" to "offline", "codex" to "old"),
            codexServerIds = setOf("codex"),
            codexErrors = mapOf("codex" to "retrying"),
        )

        assertEquals(mapOf("open" to "offline", "codex" to "retrying"), merged)
    }

    @Test
    fun `last grouped child dismissal removes server summary`() {
        val serverId = "server-1"
        val group = SessionNotificationIdentity.serverGroup(serverId)

        assertTrue(shouldCancelServerSummary(listOf(10 to group), serverId, dismissedId = 10))
        assertFalse(
            shouldCancelServerSummary(
                activeChildren = listOf(10 to group, 11 to group),
                serverId = serverId,
                dismissedId = 10,
            ),
        )
    }

    @Test
    fun `completed turns post unless their chat is active`() {
        val notification = CodexNotification.fromJson(
            Json.parseToJsonElement(
                """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}}""",
            ).jsonObject,
        )

        assertEquals(
            CodexTurnNotificationDecision.POST,
            codexTurnNotificationDecision("server-1", notification, emptySet(), notificationsEnabled = true),
        )
        assertEquals(
            CodexTurnNotificationDecision.SUPPRESS_ACTIVE,
            codexTurnNotificationDecision(
                "server-1",
                notification,
                setOf(CodexThreadKey("server-1", "thread-1")),
                notificationsEnabled = true,
            ),
        )
    }

    @Test
    fun `non-completion turn events are ignored`() {
        val notification = CodexNotification.fromJson(
            Json.parseToJsonElement(
                """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress","items":[]}}}""",
            ).jsonObject,
        )

        assertEquals(
            CodexTurnNotificationDecision.IGNORE,
            codexTurnNotificationDecision("server-1", notification, emptySet(), notificationsEnabled = true),
        )
    }

    @Test
    fun `turn notification dedupe is bounded`() {
        val keys = BoundedNotificationKeys(capacity = 2)

        assertTrue(keys.add("one"))
        assertFalse(keys.add("one"))
        assertTrue(keys.add("two"))
        assertTrue(keys.add("three"))
        assertEquals(2, keys.size())
        assertTrue(keys.add("one"))
    }

    @Test
    fun `Codex ownership registration is atomic and generations reject stale owners`() {
        val registry = CodexOwnershipRegistry()
        val server = ServerConfig(id = "codex", type = ServerType.CODEX, url = "wss://codex.test")
        val first = requireNotNull(registry.register(server) { Job() })

        assertNull(registry.register(server) { Job() })
        assertTrue(registry.isCurrent(server.id, first.generation))
        assertEquals(first, registry.remove(server.id))
        val second = requireNotNull(registry.register(server) { Job() })

        assertFalse(registry.isCurrent(server.id, first.generation))
        assertTrue(registry.isCurrent(server.id, second.generation))
        assertTrue(second.generation > first.generation)
    }

    private fun managed(state: CodexClientConnectionState) = CodexManagedConnection(
        connectionId = 1,
        client = CodexAppServerClient(
            transport = NoopTransport,
            json = Json { ignoreUnknownKeys = true },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ),
        state = state,
        pendingRequests = emptyList(),
    )

    private fun request(
        id: String,
        method: String,
        params: JsonObject = buildJsonObject { put("threadId", "thread-1") },
    ): CodexServerRequest = requireNotNull(
        CodexServerRequest.fromJson(
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            },
        ),
    )

    private data object NoopTransport : CodexRpcTransport {
        override suspend fun connect() = Unit
        override suspend fun send(text: String) = Unit
        override suspend fun receive(): String? = null
        override fun close() = Unit
    }
}
