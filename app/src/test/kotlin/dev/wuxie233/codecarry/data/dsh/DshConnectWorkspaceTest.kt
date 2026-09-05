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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshConnectWorkspaceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connection = DshConnection.from("http://192.168.1.8:3080")

    @Test
    fun `reusable blank requires membership matching cwd and excludes archived`() {
        val workspace = DshWorkspaceView(
            workspaceId = "w1",
            path = "/work/a",
            title = "a",
            sessionIds = listOf("subagent", "running-blank", "blank", "busy", "archived", "other-cwd"),
            createdAt = "t",
            updatedAt = "t",
        )
        val state = DshEventState(
            sessions = mapOf(
                "subagent" to DshSessionSnapshot(sessionId = "subagent", blank = true, origin = "subagent", cwd = "/work/a"),
                "running-blank" to DshSessionSnapshot(sessionId = "running-blank", blank = true, running = true, cwd = "/work/a"),
                "blank" to DshSessionSnapshot(sessionId = "blank", blank = true, cwd = "/work/a"),
                "busy" to DshSessionSnapshot(sessionId = "busy", blank = false, cwd = "/work/a"),
                "archived" to DshSessionSnapshot(sessionId = "archived", blank = true, cwd = "/work/a"),
                "other-cwd" to DshSessionSnapshot(sessionId = "other-cwd", blank = true, cwd = "/work/b"),
            ),
            archivedSessionIds = setOf("archived"),
        )
        assertEquals("blank", reusableBlankSessionId(state, workspace))
        assertNull(
            reusableBlankSessionId(
                state,
                workspace.copy(sessionIds = listOf("subagent", "running-blank", "busy", "archived", "other-cwd")),
            ),
        )
    }

    @Test
    fun `directory parent prefers crumbs over string split`() {
        val crumbs = listOf(
            DshDirectoryEntry(name = "/", path = "/", hidden = false),
            DshDirectoryEntry(name = "root", path = "/root", hidden = false),
            DshDirectoryEntry(name = "CODE", path = "/root/CODE", hidden = false),
        )
        assertEquals("/root", directoryParentPath("/root/CODE", crumbs))
        assertEquals("/root", directoryParentPath("/root/CODE", emptyList()))
        assertNull(directoryParentPath("/", emptyList()))
    }

    @Test
    fun `hidden entries are dropped from the picker listing`() {
        val listing = DshDirectoryListing(
            path = "/root",
            home = "/root",
            crumbs = listOf(DshDirectoryEntry(name = "/", path = "/", hidden = false)),
            entries = listOf(
                DshDirectoryEntry(name = ".cache", path = "/root/.cache", hidden = true),
                DshDirectoryEntry(name = "CODE", path = "/root/CODE", hidden = false),
            ),
            truncated = true,
        )
        assertEquals("/", directoryParentPath(listing.path, listing.crumbs))
        assertEquals(listOf("CODE"), visibleDirectoryEntries(listing).map { it.name })
    }

    @Test
    fun `no repo create posts empty payload and uses host cwd`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val method = envelope.getValue("method").jsonPrimitive.content
            val payload = envelope.getValue("payload").jsonObject
            when (method) {
                "session/create" -> {
                    val requestArgs = payload.getValue("args").jsonObject.getValue("request").jsonObject
                    assertTrue(requestArgs.isEmpty())
                    """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"sessionId":"s-no-repo"}}}"""
                }
                else -> error(method)
            }
        }
        val result = connectDshConversation(
            client = client,
            connection = connection,
            state = DshEventState(),
            path = null,
            noRepoDirectory = "/root/.dsh/no-repo",
        )
        assertEquals("s-no-repo", result.sessionId)
        assertEquals("/root/.dsh/no-repo", result.directory)
        assertFalse(result.reused)
        assertEquals("/api/session/create", captured.single().url.encodedPath)
    }

    @Test
    fun `new directory registers workspace then creates with workspaceId not cwd`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val methods = mutableListOf<String>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val method = envelope.getValue("method").jsonPrimitive.content
            val payload = envelope.getValue("payload").jsonObject
            methods += method
            when (method) {
                "workspace/create" -> {
                    assertEquals("/work/new", payload.getValue("args").jsonObject.getValue("request").jsonObject.getValue("path").jsonPrimitive.content)
                    """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"workspace":{"workspaceId":"w-new","path":"/work/new","folders":[],"title":"new","sessionIds":[],"createdAt":"t","updatedAt":"t"},"created":true}}}"""
                }
                "session/create" -> {
                    val requestArgs = payload.getValue("args").jsonObject.getValue("request").jsonObject
                    assertEquals("w-new", requestArgs.getValue("workspaceId").jsonPrimitive.content)
                    assertFalse(requestArgs.containsKey("cwd"))
                    """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"sessionId":"s-new"}}}"""
                }
                else -> error(method)
            }
        }
        val result = connectDshConversation(
            client = client,
            connection = connection,
            state = DshEventState(),
            path = "/work/new",
            noRepoDirectory = "/root/.dsh/no-repo",
        )
        assertEquals("s-new", result.sessionId)
        assertEquals("/work/new", result.directory)
        assertFalse(result.reused)
        assertEquals(listOf("workspace/create", "session/create"), methods)
    }

    @Test
    fun `existing blank member is reused without session create`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val method = envelope.getValue("method").jsonPrimitive.content
            when (method) {
                "workspace/create" ->
                    """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"workspace":{"workspaceId":"w1","path":"/work/a","folders":[],"title":"a","sessionIds":["blank"],"createdAt":"t","updatedAt":"t"},"created":false}}}"""
                else -> error(method)
            }
        }
        val result = connectDshConversation(
            client = client,
            connection = connection,
            state = DshEventState(
                sessions = mapOf(
                    "blank" to DshSessionSnapshot(sessionId = "blank", blank = true, cwd = "/work/a"),
                ),
            ),
            path = "/work/a",
            noRepoDirectory = "/root/.dsh/no-repo",
        )
        assertEquals("blank", result.sessionId)
        assertTrue(result.reused)
        assertEquals(1, captured.size)
        assertEquals("/api/workspace/create", captured.single().url.encodedPath)
    }

    @Test
    fun `explicit preset reaches first no repo create`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val args = envelope.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
            assertEquals("preset-a", args.getValue("agentPreset").jsonPrimitive.content)
            assertFalse(args.containsKey("cwd"))
            assertFalse(args.containsKey("workspaceId"))
            """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{"sessionId":"new"}}}"""
        }
        connectDshConversation(client, connection, DshEventState(), null, "/home", "preset-a")
        assertEquals(1, captured.size)
    }

    @Test
    fun `reused blank selects requested preset before returning`() = runTest {
        verifyPresetReuse(existing = "preset-a", selected = "preset-b", expectSelect = true, expectReuse = true)
    }

    @Test
    fun `reused blank with same preset needs no selection`() = runTest {
        verifyPresetReuse(existing = "preset-a", selected = "preset-a", expectSelect = false, expectReuse = true)
    }

    @Test
    fun `host default creates fresh when blank member has explicit preset`() = runTest {
        verifyPresetReuse(existing = "preset-a", selected = null, expectSelect = false, expectReuse = false)
    }

    @Test
    fun `failed selection never returns reused conversation`() = runTest {
        var failure: Throwable? = null
        try {
            verifyPresetReuse(existing = "preset-a", selected = "preset-b", expectSelect = true, expectReuse = true, failSelect = true)
        } catch (e: DshTransportException) {
            assertTrue(e.cause is IllegalStateException)
            assertEquals("Host refused preset switch", e.cause?.message)
            failure = e
        }
        assertTrue("A failed preset selection must not return a reused conversation", failure != null)
    }

    @Test
    fun `new workspace session carries explicit preset`() = runTest {
        verifyPresetReuse(existing = null, selected = "preset-b", expectSelect = false, expectReuse = false, hasBlank = false)
    }

    private suspend fun verifyPresetReuse(
        existing: String?,
        selected: String?,
        expectSelect: Boolean,
        expectReuse: Boolean,
        failSelect: Boolean = false,
        hasBlank: Boolean = true,
    ) {
        val captured = mutableListOf<HttpRequestData>()
        val methods = mutableListOf<String>()
        val client = api(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val method = envelope.getValue("method").jsonPrimitive.content
            val args = envelope.getValue("payload").jsonObject.getValue("args").jsonObject
            methods += method
            val value = when (method) {
                "workspace/create" -> """{"workspace":{"workspaceId":"w1","path":"/work/a","folders":[],"title":"a","sessionIds":["blank"],"createdAt":"t","updatedAt":"t"},"created":false}"""
                "agentPresets/select" -> {
                    assertEquals("blank", args.getValue("agentId").jsonPrimitive.content)
                    assertEquals(selected, args.getValue("agentPreset").jsonPrimitive.content)
                    if (failSelect) error("Host refused preset switch")
                    "\"$selected\""
                }
                "session/create" -> {
                    val creation = args.getValue("request").jsonObject
                    assertEquals("w1", creation.getValue("workspaceId").jsonPrimitive.content)
                    assertFalse(creation.containsKey("cwd"))
                    if (selected == null) assertFalse(creation.containsKey("agentPreset"))
                    else assertEquals(selected, creation.getValue("agentPreset").jsonPrimitive.content)
                    """{"sessionId":"new"}"""
                }
                else -> error(method)
            }
            """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$value}}"""
        }
        val state = DshEventState(sessions = if (hasBlank) mapOf(
            "blank" to DshSessionSnapshot(sessionId = "blank", blank = true, cwd = "/work/a", agentPreset = "creation",
                projections = mapOf("agentPreset" to (10L to (existing?.let(::JsonPrimitive) ?: JsonNull)))),
        ) else emptyMap())
        val result = connectDshConversation(client, connection, state, "/work/a", "/home", selected)
        assertEquals(expectReuse, result.reused)
        assertEquals(if (expectReuse) "blank" else "new", result.sessionId)
        assertEquals(buildList {
            add("workspace/create")
            if (expectSelect) add("agentPresets/select")
            if (!expectReuse) add("session/create")
        }, methods)
    }

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
