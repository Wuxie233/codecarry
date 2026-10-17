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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshHostSurfacesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `removed host methods are absent from every connection catalog and loopback fence`() {
        val removed = setOf(
            "session/rehome", "session/rewrite", "workspace/addFolder", "workspace/removeFolder",
            "git/describe", "git/checkout", "git/createBranch", "systemPrompt/list",
            "agentPresets/copy", "agentPresets/deletePreset", "subagents/list",
            "settings/canOpenAgentPresetDirectory", "settings/openAgentPresetDirectory",
        )
        val connections = listOf(
            DshConnection.from("http://127.0.0.1:3080"),
            DshConnection.from("http://192.168.1.8:3080"),
            DshConnection.from("https://example.com", "secret"),
        )
        val methodSets = listOf(DshMethods.unary.toSet(), DshRpc.LOOPBACK_ONLY_METHODS) +
            connections.flatMap { connection ->
                val catalog = dshHostSurfaceCatalog(connection)
                listOf(catalog.availableMethods, catalog.loopbackOnlyHidden)
            }
        methodSets.forEach { methods ->
            assertTrue("Removed methods remain: ${methods.intersect(removed)}", methods.intersect(removed).isEmpty())
            assertFalse(methods.any { it.startsWith("automation/") })
        }
    }

    @Test
    fun `non-loopback catalog hides locked methods and keeps remaining unary surfaces`() {
        val catalog = dshHostSurfaceCatalog(DshConnection.from("http://192.168.1.8:3080"))
        assertFalse(catalog.isLoopback)
        DshRpc.LOOPBACK_ONLY_METHODS.forEach { method ->
            assertFalse(method, catalog.can(method))
            assertTrue(method, method in catalog.loopbackOnlyHidden)
        }
        listOf(
            "workspace/create", "skills/list",
            "agentPresets/list", "agentPresets/select", "goals/create",
            "settings/describe", "settings/mutate", "llm/listProviders", "session/modelCatalog",
            "directoryPicker/list", "directoryPicker/createDirectory",
        ).forEach { method -> assertTrue(method, catalog.can(method)) }
        assertTrue(catalog.canManageWorkspaces)
        assertTrue(catalog.canBrowseHost)
        assertTrue(catalog.canListLlm)
        assertFalse(catalog.can("credentials/set"))
        assertFalse(catalog.can("llm/discoverModels"))
    }

    @Test
    fun `loopback catalog keeps authoring methods`() {
        val catalog = dshHostSurfaceCatalog(DshConnection.from("http://127.0.0.1:3080"))
        assertTrue(catalog.isLoopback)
        assertTrue(catalog.loopbackOnlyHidden.isEmpty())
        assertTrue(catalog.can("agentPresets/read"))
        assertTrue(catalog.can("settings/openSettingsDocument"))
    }

    @Test
    fun `passworded public host catalog keeps authoring methods`() {
        val catalog = dshHostSurfaceCatalog(DshConnection.from("https://dsh.wuxie233.com", "secret"))
        assertTrue(catalog.isLoopback)
        assertTrue(catalog.loopbackOnlyHidden.isEmpty())
        assertTrue(catalog.can("credentials/set"))
        assertTrue(catalog.can("agentPresets/read"))
        assertTrue(catalog.can("directoryPicker/pick"))
    }

    @Test
    fun `controller consumes remaining unary domains through typed RPC`() = runTest {
        val captured = mutableListOf<String>()
        val controller = controller(captured) { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val method = envelope.getValue("method").jsonPrimitive.content
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            ok(rpcId, method)
        }

        assertTrue(controller.createWorkspace("/tmp/project").created)
        assertEquals("s1", controller.sessionList().items.single().sessionId)
        assertEquals("/tmp", controller.listDirectory().path)
        assertEquals("/tmp/new", controller.createDirectory("/tmp", "new").path)
        assertEquals("coder", controller.agentPresetList().presets.single().id)
        assertEquals("coder", controller.agentPresetSelect("s1", "coder").agentPreset)
        assertEquals("g1", controller.goalCreate("s1", "ship").ref.id)
        assertEquals("llm-deepseek", controller.settingsDescribe().namespaces.single().ns)
        val mutated = controller.settingsMutate(
            "llm-deepseek",
            buildJsonArray {
                add(buildJsonObject {
                    put("op", "set")
                    put("path", buildJsonArray { add(JsonPrimitive("apiKey")) })
                    put("value", "x")
                })
            },
            expectedRevision = 3,
        )
        assertEquals(4L, mutated.revision)
        assertEquals("deepseek-official", controller.llmProviders().providers.single().id)
        assertEquals("v3", controller.llmModels().groups.single().models.single().id)
        assertEquals("m-1", controller.subagentPrompt("s1", "child-1", "continue", delivery = "queue").messageId)
        assertTrue(controller.subagentInterrupt("s1", "child-1").accepted)

        assertTrue(
            captured.containsAll(
                listOf(
                    "session/list",
                    "workspace/create", "directoryPicker/list", "directoryPicker/createDirectory",
                    "agentPresets/list", "agentPresets/select",
                    "goals/create", "settings/describe", "settings/mutate",
                    "llm/listProviders", "session/modelCatalog", "subagents/prompt",
                    "subagents/interruptByParent",
                ),
            ),
        )
        assertFalse(captured.contains("llm/discoverModels"))
        assertFalse(captured.contains("credentials/describe"))
    }

    @Test
    fun `controller refuses loopback-only methods on LAN without posting`() = runTest {
        val captured = mutableListOf<String>()
        val connection = DshConnection.from("http://192.168.1.8:3080")
        val client = DshApiClient(
            HttpClient(MockEngine {
                captured += "posted"
                error("must not post")
            }),
            json,
            downlinkFactory = unusedDownlinks(),
        )
        val controller = DshHostSurfaceController(client, connection)
        assertFalse(controller.catalog().can("credentials/set"))
        try {
            client.call(connection, "credentials/set")
            throw AssertionError("expected fence")
        } catch (error: DshLoopbackUnavailableException) {
            assertEquals("credentials/set", error.method)
        }
        assertTrue(captured.isEmpty())
    }

    private fun controller(
        captured: MutableList<String>,
        body: (HttpRequestData) -> String,
    ): DshHostSurfaceController {
        val engine = MockEngine { request ->
            val envelope = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            captured += envelope.getValue("method").jsonPrimitive.content
            respond(
                content = body(request),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val connection = DshConnection.from("http://127.0.0.1:3080")
        return DshHostSurfaceController(
            client = DshApiClient(http, json, downlinkFactory = unusedDownlinks()),
            connection = connection,
        )
    }

    private fun unusedDownlinks(): DshDownlinkFactory = object : DshDownlinkFactory {
        override suspend fun openMux(connection: DshConnection) = error("unused")
    }

    private fun ok(rpcId: String, method: String): String {
        val value = when (method) {
            "session/list" -> """{"items":[{"sessionId":"s1","updatedAt":1,"running":false,"blank":false}]}"""
            "workspace/create" -> """{"workspace":{"workspaceId":"w1","path":"/tmp/project","folders":[],"title":"project","sessionIds":[],"createdAt":"t","updatedAt":"t"},"created":true}"""
            "directoryPicker/list" -> """{"path":"/tmp","home":"/root","crumbs":[],"entries":[],"truncated":false}"""
            "directoryPicker/createDirectory" -> """{"path":"/tmp/new"}"""
            "skills/list" -> """{"skills":[{"name":"commit-helper","description":"Git","modelInvocable":true}]}"""
            "agentPresets/list" -> """{"presets":[{"id":"coder","trust":"system","isDefault":true}],"authorable":false}"""
            "agentPresets/select" -> """"coder""""
            "goals/create" -> """{"ref":{"id":"g1","revision":1}}"""
            "settings/describe" -> """{"writable":true,"hasDocument":false,"namespaces":[{"ns":"llm-deepseek","schema":{},"value":{},"applies":"live","secrets":[],"revision":3}]}"""
            "settings/mutate" -> """{"ns":"llm-deepseek","schema":{},"value":{},"applies":"live","secrets":[],"revision":4}"""
            "llm/listProviders" -> """[{"id":"deepseek-official","name":"DeepSeek"}]"""
            "session/modelCatalog" -> """{"default":{"provider":"deepseek-official","model":"deepseek-v4-flash"},"routableProviders":["deepseek-official"],"groups":[{"id":"deepseek-official","name":"DeepSeek","models":[{"id":"v3","name":"V3"}]}],"failures":[]}"""
            "subagents/prompt" -> """{"messageId":"m-1"}"""
            "subagents/interruptByParent" -> """{"accepted":true}"""
            else -> error(method)
        }
        return """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$value}}"""
    }
}
