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
    fun `non-loopback catalog hides locked methods and keeps remaining unary surfaces`() {
        val catalog = dshHostSurfaceCatalog(DshConnection.from("http://192.168.1.8:3080"))
        assertFalse(catalog.isLoopback)
        DshRpc.LOOPBACK_ONLY_METHODS.forEach { method ->
            assertFalse(method, catalog.can(method))
            assertTrue(method, method in catalog.loopbackOnlyHidden)
        }
        listOf(
            "workspace.list", "workspace.create", "skill.catalog", "git.describe",
            "agentPreset.list", "agentPreset.select", "goal.create", "automation.list",
            "settings.describe", "settings.mutate", "llm.providers", "llm.models",
            "subagent.list", "systemPrompt.list", "host.listDirectory", "host.createDirectory",
        ).forEach { method -> assertTrue(method, catalog.can(method)) }
        assertTrue(catalog.canManageWorkspaces)
        assertTrue(catalog.canBrowseHost)
        assertTrue(catalog.canListLlm)
        assertFalse(catalog.can("credentials.set"))
        assertFalse(catalog.can("llm.discoverModels"))
    }

    @Test
    fun `loopback catalog keeps authoring methods`() {
        val catalog = dshHostSurfaceCatalog(DshConnection.from("http://127.0.0.1:3080"))
        assertTrue(catalog.isLoopback)
        assertTrue(catalog.loopbackOnlyHidden.isEmpty())
        assertTrue(catalog.can("agentPreset.read"))
        assertTrue(catalog.can("settings.openDocument"))
    }

    @Test
    fun `passworded public host catalog keeps authoring methods`() {
        val catalog = dshHostSurfaceCatalog(DshConnection.from("https://dsh.wuxie233.com", "secret"))
        assertTrue(catalog.isLoopback)
        assertTrue(catalog.loopbackOnlyHidden.isEmpty())
        assertTrue(catalog.can("credentials.set"))
        assertTrue(catalog.can("agentPreset.read"))
        assertTrue(catalog.can("host.pickDirectory"))
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

        assertEquals("w1", controller.listWorkspaces().items.single().workspaceId)
        assertTrue(controller.createWorkspace("/tmp/project").created)
        assertEquals("/tmp", controller.listDirectory().path)
        assertEquals("/tmp/new", controller.createDirectory("/tmp", "new").path)
        assertEquals("commit-helper", controller.skillCatalog().skills.single().name)
        assertEquals("main", controller.gitDescribe().currentBranch)
        assertEquals("coder", controller.agentPresetList().presets.single().id)
        assertEquals("coder", controller.agentPresetSelect("s1", "coder").agentPreset)
        assertEquals("g1", controller.goalCreate("s1", "ship").ref.id)
        assertEquals("auto-1", controller.automationList().items.single().id)
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
        assertEquals("deepseek", controller.llmProviders().providers.single().provider)
        assertEquals("v3", controller.llmModels().groups.single().models.single().id)
        assertEquals("child-1", controller.subagentList("s1").entries.single().id)
        assertEquals("m-1", controller.subagentPrompt("s1", "child-1", "continue").messageId)
        assertTrue(controller.subagentInterrupt("s1", "child-1").accepted)
        assertEquals("harness:identity", controller.systemPromptList().sections.single().name)

        assertTrue(captured.containsAll(listOf(
            "workspace.list", "workspace.create", "host.listDirectory", "host.createDirectory",
            "skill.catalog", "git.describe", "agentPreset.list", "agentPreset.select",
            "goal.create", "automation.list", "settings.describe", "settings.mutate",
            "llm.providers", "llm.models", "subagent.list", "subagent.prompt",
            "subagent.interrupt", "systemPrompt.list",
        )))
        assertFalse(captured.contains("llm.discoverModels"))
        assertFalse(captured.contains("credentials.describe"))
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
        assertFalse(controller.catalog().can("credentials.set"))
        try {
            client.call(connection, "credentials.set")
            throw AssertionError("expected fence")
        } catch (error: DshLoopbackUnavailableException) {
            assertEquals("credentials.set", error.method)
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
        val connection = DshConnection.from("http://192.168.1.8:3080")
        return DshHostSurfaceController(
            client = DshApiClient(http, json, downlinkFactory = unusedDownlinks()),
            connection = connection,
        )
    }

    private fun unusedDownlinks(): DshDownlinkFactory = object : DshDownlinkFactory {
        override suspend fun openMux(connection: DshConnection) = error("unused")
        override suspend fun openHost(connection: DshConnection) = error("unused")
    }

    private fun ok(rpcId: String, method: String): String {
        val value = when (method) {
            "workspace.list" -> """{"items":[{"workspaceId":"w1","path":"/tmp","folders":[],"title":"tmp","sessionIds":[],"createdAt":"t","updatedAt":"t"}],"archivedSessionIds":[],"hiddenWorkspaceIds":[]}"""
            "workspace.create" -> """{"workspace":{"workspaceId":"w1","path":"/tmp/project","folders":[],"title":"project","sessionIds":[],"createdAt":"t","updatedAt":"t"},"created":true}"""
            "host.listDirectory" -> """{"path":"/tmp","home":"/root","crumbs":[],"entries":[],"truncated":false}"""
            "host.createDirectory" -> """{"path":"/tmp/new"}"""
            "skill.catalog" -> """{"skills":[{"name":"commit-helper","description":"Git","modelInvocable":true,"userInvocable":true,"source":"bundled","provider":"fixture"}]}"""
            "git.describe" -> """{"currentBranch":"main","detached":false,"worktreePath":"/tmp","isolated":false,"dirtyCount":0,"unpushedCount":0,"branches":[]}"""
            "agentPreset.list" -> """{"presets":[{"id":"coder","trust":"system","isDefault":true}],"authorable":false,"hasDocument":false}"""
            "agentPreset.select" -> """{"agentPreset":"coder"}"""
            "goal.create" -> """{"ref":{"id":"g1","revision":1}}"""
            "automation.list" -> """{"items":[{"id":"auto-1","name":"nightly","enabled":true,"task":"check","workspaceId":"w1","onOverlap":"skip","selector":{},"scheduledAt":"t","createdAt":"t","updatedAt":"t","state":"scheduled","nextAt":"t"}]}"""
            "settings.describe" -> """{"writable":true,"hasDocument":false,"namespaces":[{"ns":"llm-deepseek","schema":{},"value":{},"applies":"live","secrets":[],"revision":3}]}"""
            "settings.mutate" -> """{"ns":"llm-deepseek","schema":{},"value":{},"applies":"live","secrets":[],"revision":4}"""
            "llm.providers" -> """{"providers":[{"provider":"deepseek","displayName":"DeepSeek","settingsNs":"llm-deepseek","settingsPath":[],"active":true}]}"""
            "llm.models" -> """{"groups":[{"id":"deepseek","name":"DeepSeek","models":[{"id":"v3","name":"V3"}]}],"failures":[]}"""
            "subagent.list" -> """{"entries":[{"kind":"child","id":"child-1","mode":"continuable","activity":"inactive","hasChildren":false,"label":"worker"}],"parentAvailable":true}"""
            "subagent.prompt" -> """{"messageId":"m-1"}"""
            "subagent.interrupt" -> """{"accepted":true}"""
            "systemPrompt.list" -> """{"sections":[{"name":"harness:identity","order":0,"text":"hi","complete":false}]}"""
            else -> error(method)
        }
        return """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$value}}"""
    }
}
