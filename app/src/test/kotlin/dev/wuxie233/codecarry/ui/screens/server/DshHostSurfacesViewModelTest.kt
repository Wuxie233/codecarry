package dev.wuxie233.codecarry.ui.screens.server

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.dsh.DshApiClient
import dev.wuxie233.codecarry.data.dsh.DshConnectionManager
import dev.wuxie233.codecarry.data.dsh.DshRpc
import dev.wuxie233.codecarry.data.dsh.unusedDshDownlinks
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Behavior tests for the DSH extended host-surfaces screen state machine:
 * per-module loading isolation (#46), the session-scoped skills entry (#45),
 * and session-context inheritance with settings forms (#47).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshHostSurfacesViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)
    private val viewModels = mutableListOf<DshHostSurfacesViewModel>()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        testScope.runCurrent()
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        testScope.runCurrent()
        Dispatchers.resetMain()
    }

    // ---------- #46: per-module loading and fault isolation ----------

    @Test
    fun `module failure isolates while other modules still publish`() = runTest(dispatcher) {
        val harness = harness(failMethods = setOf("automation/list"))
        val vm = harness.vm

        val state = vm.uiState.first { it.presets.data != null && it.settings.data != null && it.models.data != null }

        assertNotNull(state.automation.error)
        assertNull(state.automation.data)
        assertNull(state.presets.error)
        assertNull(state.settings.error)
        assertNull(state.models.error)
        assertTrue(harness.host.requests.bodies("automation/list").isNotEmpty())
    }

    @Test
    fun `successes publish progressively while a slow module is pending`() = runTest(dispatcher) {
        val harness = harness(stallFirstMethods = setOf("agentPresets/list"))
        val vm = harness.vm
        runCurrent()

        // Other modules must publish even while the preset roster stalls.
        vm.uiState.first { it.settings.data != null && it.models.data != null }
        assertTrue(vm.uiState.value.presets.loading)
        assertNull(vm.uiState.value.presets.data)

        harness.host.stallGate.complete(Unit)
        val published = vm.uiState.first { it.presets.data != null }
        assertNull(published.presets.error)
    }

    @Test
    fun `per-module retry reloads only the failed module`() = runTest(dispatcher) {
        val harness = harness(failMethods = setOf("automation/list"))
        val vm = harness.vm
        vm.uiState.first { it.automation.error != null }

        harness.host.failMethods.clear()
        vm.retry(DshHostModule.AUTOMATION)
        val recovered = vm.uiState.first { it.automation.data != null }

        assertNull(recovered.automation.error)
        assertEquals(1, harness.host.requests.requestCount("settings/describe"))
        assertEquals(1, harness.host.requests.requestCount("session/list"))
        assertEquals(1, harness.host.requests.requestCount("llm/listProviders"))
        assertEquals(2, harness.host.requests.requestCount("automation/list"))
    }

    @Test
    fun `failed refresh keeps already-obtained module data`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm
        val loaded = vm.uiState.first { it.automation.data != null }

        harness.host.failMethods += "automation/list"
        vm.refresh()
        val state = vm.uiState.first { it.automation.error != null }

        assertEquals(loaded.automation.data, state.automation.data)
        assertFalse(state.automation.loading)
    }

    @Test
    fun `cancelling an in-flight module load never publishes an error`() = runTest(dispatcher) {
        val harness = harness(stallFirstMethods = setOf("directoryPicker/list"))
        val vm = harness.vm
        runCurrent()
        vm.uiState.first { it.directory.loading }

        // A retry cancels the stalled request; cancellation must not surface
        // as a module error.
        vm.retry(DshHostModule.DIRECTORY)
        harness.host.stallGate.complete(Unit)

        val state = vm.uiState.first { it.directory.data != null }
        assertNull(state.directory.error)
    }

    @Test
    fun `result from an obsolete connection generation is dropped`() = runTest(dispatcher) {
        val harness = harness(stallFirstMethods = setOf("automation/list"))
        val vm = harness.vm
        runCurrent()
        vm.uiState.first { it.automation.loading }

        // A generation transition (disconnect) mid-flight fences the result.
        harness.manager.disconnect(harness.serverId)
        harness.host.stallGate.complete(Unit)
        runCurrent()

        val state = vm.uiState.first { !it.automation.loading }
        assertNull(state.automation.data)
        assertNull(state.automation.error)

        // The module is not stuck: a retry publishes again.
        vm.retry(DshHostModule.AUTOMATION)
        val recovered = vm.uiState.first { it.automation.data != null }
        assertNull(recovered.automation.error)
    }

    // ---------- #45: session-scoped skills entry ----------

    @Test
    fun `skills stay untouched until a session is selected then load for it`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm

        val roster = vm.uiState.first { it.sessions.data != null }
        assertNull(roster.selectedSessionId)
        // Unselected: distinguishable from failed and from empty.
        assertNull(roster.skills.data)
        assertNull(roster.skills.error)
        assertFalse(roster.skills.loading)

        vm.selectSession("ses_a")
        val loaded = vm.uiState.first { it.skills.data != null }

        assertEquals("ses_a", loaded.selectedSessionId)
        val body = loaded.skills.data
        assertNotNull(body)
        assertEquals(1, body!!.skills.size)
        assertEquals("commit-helper", body.skills.single().name)
        assertTrue(body.skills.single().userInvocable)
        assertTrue(harness.host.requests.single("skills/list").contains("\"sessionId\":\"ses_a\""))
    }

    @Test
    fun `a single session is inherited and its skills load`() = runTest(dispatcher) {
        val harness = harness(sessionsValue = SINGLE_SESSION)
        val vm = harness.vm

        val state = vm.uiState.first { it.skills.data != null }
        assertEquals("ses_a", state.selectedSessionId)
        assertTrue(harness.host.requests.single("skills/list").contains("\"sessionId\":\"ses_a\""))
    }

    @Test
    fun `empty failed and unselected skills states are distinct`() = runTest(dispatcher) {
        val harness = harness(skillsValue = """{"skills":[]}""")
        val vm = harness.vm
        vm.uiState.first { it.sessions.data != null }

        // Empty: data present with no entries and no error.
        vm.selectSession("ses_a")
        val empty = vm.uiState.first { it.skills.data != null && !it.skills.loading }
        assertEquals(0, empty.skills.data!!.skills.size)
        assertNull(empty.skills.error)

        // Failed: error set, retry recovers.
        harness.host.failMethods += "skills/list"
        vm.retry(DshHostModule.SKILLS)
        val failed = vm.uiState.first { it.skills.error != null }
        assertNotNull(failed.skills.error)

        harness.host.failMethods.clear()
        vm.retry(DshHostModule.SKILLS)
        val recovered = vm.uiState.first { it.skills.error == null && !it.skills.loading }
        assertNull(recovered.skills.error)
    }

    @Test
    fun `switching sessions reloads skills for the new session`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm
        vm.uiState.first { it.sessions.data != null }

        vm.selectSession("ses_a")
        vm.uiState.first { it.skills.data != null }
        vm.selectSession("ses_b")
        vm.uiState.first { it.selectedSessionId == "ses_b" && it.skills.data != null }

        val bodies = harness.host.requests.bodies("skills/list")
        assertEquals(
            listOf("ses_a", "ses_b"),
            bodies.map { it.substringAfter("\"sessionId\":\"").substringBefore('"') },
        )
    }

    @Test
    fun `a roster of only blank sessions offers no target`() = runTest(dispatcher) {
        val harness = harness(
            sessionsValue = """{"items":[{"sessionId":"ses_blank","updatedAt":5,"running":false,"blank":true}]}""",
        )
        val vm = harness.vm

        val roster = vm.uiState.first { it.sessions.data != null }
        assertTrue(roster.sessions.data!!.isEmpty())
        assertNull(roster.selectedSessionId)
        assertTrue(harness.host.requests.bodies("skills/list").isEmpty())
    }

    @Test
    fun `a stale selection clears and its skills reset`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm
        vm.uiState.first { it.sessions.data != null }

        vm.selectSession("ses_a")
        vm.uiState.first { it.skills.data != null }

        // The host drops ses_a; the roster refresh then reports only ses_b.
        harness.manager.reducer(harness.serverId).applyEventsFrame(
            dev.wuxie233.codecarry.data.dsh.DshEventsFrame.Emit(
                event = "api-session/removed",
                args = listOf(kotlinx.serialization.json.JsonPrimitive("ses_a")),
            ),
        )
        harness.host.sessionsValue =
            """{"items":[{"sessionId":"ses_b","updatedAt":20,"running":false,"blank":false,"cwd":"/root/CODE/beta"}]}"""
        vm.retry(DshHostModule.SESSIONS)
        val roster = vm.uiState.first { it.sessions.data?.map { o -> o.sessionId } == listOf("ses_b") }

        assertNull(roster.selectedSessionId)
        // Skills for the vanished session are reset, not kept as current.
        assertNull(roster.skills.data)
        assertNull(roster.skills.error)
    }

    // ---------- #47: inherited session context, pickers, settings forms ----------

    @Test
    fun `preset and goal operations inherit the selected session`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm
        vm.uiState.first { it.sessions.data != null }
        vm.selectSession("ses_a")
        vm.uiState.first { it.skills.data != null }

        vm.selectPreset("coder")
        vm.createGoal("ship it")
        vm.uiState.first { it.lastGoal != null }

        val selectBody = harness.host.requests.single("agentPresets/select")
        assertTrue(selectBody.contains("\"agentId\":\"ses_a\""))
        assertTrue(selectBody.contains("\"agentPreset\":\"coder\""))
        val goalBody = harness.host.requests.single("goals/create")
        assertTrue(goalBody.contains("\"agentId\":\"ses_a\""))
        assertTrue(goalBody.contains("\"objective\":\"ship it\""))
    }

    @Test
    fun `subagent operations inherit the selected parent session`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm
        vm.uiState.first { it.sessions.data != null }
        vm.selectSession("ses_a")
        vm.uiState.first { it.skills.data != null }

        vm.loadSubagents()
        vm.uiState.first { it.subagents.data != null }

        vm.promptSubagent("child-1", "continue")
        vm.interruptSubagent("child-1")
        vm.uiState.first { harness.host.requests.bodies("subagents/interruptByParent").isNotEmpty() }

        // Both actions reload the catalog after their receipt.
        assertTrue(harness.host.requests.bodies("subagents/list").first().contains("\"parentSessionId\":\"ses_a\""))
        val promptBody = harness.host.requests.single("subagents/prompt")
        assertTrue(promptBody.contains("\"parentSessionId\":\"ses_a\""))
        assertTrue(promptBody.contains("\"childSessionId\":\"child-1\""))
        val interruptBody = harness.host.requests.single("subagents/interruptByParent")
        assertTrue(interruptBody.contains("\"parentSessionId\":\"ses_a\""))
        assertTrue(interruptBody.contains("\"childSessionId\":\"child-1\""))
    }

    @Test
    fun `settings form parses simple fields and submits typed set ops`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm
        vm.uiState.first { it.settings.data != null }

        val fields = vm.settingsFieldsFor("llm-deepseek")
        assertEquals(listOf("model", "maxTokens", "verbose"), fields.map { it.key })
        assertEquals(
            listOf(
                DshSettingsFieldKind.TEXT,
                DshSettingsFieldKind.NUMBER,
                DshSettingsFieldKind.BOOLEAN,
            ),
            fields.map { it.kind },
        )
        assertEquals("v3", fields.first { it.key == "model" }.textValue)
        assertEquals("5", fields.first { it.key == "maxTokens" }.textValue)
        assertEquals(false, fields.first { it.key == "verbose" }.booleanValue)

        vm.mutateSettingsForm(
            ns = "llm-deepseek",
            textValues = mapOf("model" to "v4", "maxTokens" to "5"),
            booleanValues = mapOf("verbose" to true),
        )
        val state = vm.uiState.first { it.lastSettings != null }

        val body = harness.host.requests.single("settings/mutate")
        val args = json.parseToJsonElement(body).jsonObject
            .getValue("payload").jsonObject
            .getValue("args").jsonObject
        assertEquals(3L, args.getValue("expectedRevision").jsonPrimitive.content.toLong())
        val ops = args.getValue("ops").jsonArray
        assertEquals(2, ops.size)
        val modelOp = ops[0].jsonObject
        assertEquals("set", modelOp.getValue("op").jsonPrimitive.content)
        assertEquals("model", modelOp.getValue("path").jsonArray.single().jsonPrimitive.content)
        assertEquals("v4", modelOp.getValue("value").jsonPrimitive.content)
        val verboseOp = ops[1].jsonObject
        assertEquals("verbose", verboseOp.getValue("path").jsonArray.single().jsonPrimitive.content)
        assertEquals(true, verboseOp.getValue("value").jsonPrimitive.content.toBoolean())
        assertEquals(4L, state.lastSettings!!.revision)
        assertEquals(4L, state.settings.data!!.namespaces.single().revision)
    }

    @Test
    fun `invalid number and empty patch are rejected client-side`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm
        vm.uiState.first { it.settings.data != null }

        vm.mutateSettingsForm(
            ns = "llm-deepseek",
            textValues = mapOf("model" to "v4", "maxTokens" to "not-a-number"),
            booleanValues = emptyMap(),
        )
        runCurrent()
        assertEquals(DshSettingsFormError.INVALID_NUMBER, vm.uiState.value.formError)
        assertTrue(harness.host.requests.bodies("settings/mutate").isEmpty())

        vm.clearFormError()
        vm.mutateSettingsForm(
            ns = "llm-deepseek",
            textValues = mapOf("model" to "v3", "maxTokens" to "5"),
            booleanValues = mapOf("verbose" to false),
        )
        runCurrent()
        assertEquals(DshSettingsFormError.EMPTY_PATCH, vm.uiState.value.formError)
        assertTrue(harness.host.requests.bodies("settings/mutate").isEmpty())
    }

    @Test
    fun `advanced json patch validates before sending`() = runTest(dispatcher) {
        val harness = harness()
        val vm = harness.vm
        vm.uiState.first { it.settings.data != null }

        vm.mutateSettingsJson("llm-deepseek", "nope")
        assertEquals(DshSettingsFormError.INVALID_JSON_ARRAY, vm.uiState.value.formError)
        assertTrue(harness.host.requests.bodies("settings/mutate").isEmpty())

        vm.clearFormError()
        vm.mutateSettingsJson("llm-deepseek", """[{"path":["model"]}]""")
        assertEquals(DshSettingsFormError.INVALID_JSON_ARRAY, vm.uiState.value.formError)
        assertTrue(harness.host.requests.bodies("settings/mutate").isEmpty())

        vm.clearFormError()
        vm.mutateSettingsJson("llm-deepseek", """[{"op":"set","path":["model"],"value":"v4"}]""")
        vm.uiState.first { it.lastSettings != null }
        assertEquals(1, harness.host.requests.bodies("settings/mutate").size)
    }

    @Test
    fun `non-loopback server keeps loopback-only methods out of module loads`() = runTest(dispatcher) {
        val harness = harness(serverUrl = "http://192.168.1.8:3080")
        val vm = harness.vm
        val state = vm.uiState.first { it.sessions.data != null }

        assertFalse(state.catalog!!.isLoopback)
        assertTrue(state.catalog.loopbackOnlyHidden.isNotEmpty())
        harness.host.requests.bodies.keys.forEach { method ->
            assertFalse("unexpected loopback-only call: $method", DshRpc.isLoopbackOnly(method))
        }
        // Non-loopback surfaces still load.
        assertTrue(harness.host.requests.bodies.containsKey("session/list"))
        assertTrue(harness.host.requests.bodies.containsKey("settings/describe"))
    }

    // ---------- harness ----------

    private class RequestLog {
        val bodies: MutableMap<String, CopyOnWriteArrayList<String>> = ConcurrentHashMap()

        fun add(method: String, body: String) {
            bodies.getOrPut(method) { CopyOnWriteArrayList() }.add(body)
        }

        fun bodies(method: String): List<String> = bodies[method].orEmpty()

        fun single(method: String): String = bodies(method).single()

        fun requestCount(method: String): Int = bodies(method).size
    }

    /** Mutable mock Host state shared with the MockEngine handler. */
    private class MockHost(
        var sessionsValue: String,
        var skillsValue: String,
        val failMethods: MutableSet<String>,
        val stallFirstMethods: MutableSet<String>,
    ) {
        val stallGate = CompletableDeferred<Unit>()
        val requests = RequestLog()

        fun valueFor(method: String): String = when (method) {
            "session/list" -> sessionsValue
            "skills/list" -> skillsValue
            "agentPresets/list" -> """{"presets":[{"id":"coder","trust":"system","isDefault":true}],"authorable":false}"""
            "automation/list" -> AUTOMATION
            "settings/describe" -> SETTINGS_DESCRIBE
            "settings/mutate" -> MUTATED
            "llm/listProviders" -> """[{"id":"deepseek-official","name":"DeepSeek"}]"""
            "session/modelCatalog" -> MODEL_CATALOG
            "systemPrompt/list" -> """{"sections":[{"name":"harness:identity","order":0,"text":"hi","complete":false}]}"""
            "git/describe" -> GIT
            "directoryPicker/list" -> DIRECTORY
            "agentPresets/select" -> """"coder""""
            "goals/create" -> """{"ref":{"id":"g1","revision":1}}"""
            "subagents/list" -> SUBAGENTS
            "subagents/prompt" -> """{"messageId":"m-1"}"""
            "subagents/interruptByParent" -> """{"accepted":true}"""
            else -> error(method)
        }
    }

    private class Harness(
        val vm: DshHostSurfacesViewModel,
        val manager: DshConnectionManager,
        val host: MockHost,
        val serverId: String,
    )

    private suspend fun harness(
        serverUrl: String = "http://127.0.0.1:3080",
        sessionsValue: String = TWO_SESSIONS,
        skillsValue: String = SKILLS,
        failMethods: Set<String> = emptySet(),
        stallFirstMethods: Set<String> = emptySet(),
    ): Harness {
        val host = MockHost(
            sessionsValue = sessionsValue,
            skillsValue = skillsValue,
            failMethods = Collections.synchronizedSet(failMethods.toMutableSet()),
            stallFirstMethods = Collections.synchronizedSet(stallFirstMethods.toMutableSet()),
        )
        val engine = MockEngine { request ->
            val method = request.url.encodedPath.removePrefix("/api/")
            host.requests.add(method, (request.body as TextContent).text)
            if (host.stallFirstMethods.remove(method)) {
                host.stallGate.await()
            }
            if (method in host.failMethods) {
                return@MockEngine respond(
                    content = ByteReadChannel("boom"),
                    status = HttpStatusCode.InternalServerError,
                )
            }
            respond(
                content = ByteReadChannel(envelope(host.valueFor(method))),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val client = DshApiClient(
            http,
            json,
            mintRpcId = { "fixed" },
            downlinkFactory = unusedDshDownlinks(),
        )
        val manager = DshConnectionManager(client = client, scope = testScope.backgroundScope)
        val repo = ServerRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmpFolder.newFile("servers-${System.nanoTime()}.preferences_pb") },
            ),
            api = unusedOpenCodeApi(),
            json = json,
        )
        val server = repo.addServer(url = serverUrl, type = ServerType.DSH, name = "Test DSH")
        val vm = DshHostSurfacesViewModel(
            savedStateHandle = SavedStateHandle(mapOf("serverId" to server.id)),
            serverRepository = repo,
            dshConnectionManager = manager,
            api = client,
        )
        viewModels += vm
        return Harness(vm = vm, manager = manager, host = host, serverId = server.id)
    }

    private fun envelope(value: String): String =
        """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":$value}}"""

    private fun unusedOpenCodeApi(): OpenCodeApi {
        val engine = MockEngine { error("opencode unused: ${it.url.encodedPath}") }
        return OpenCodeApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)
    }

    private companion object {
        private val TWO_SESSIONS = """
            {"items":[
              {"sessionId":"ses_a","updatedAt":10,"running":false,"blank":false,"cwd":"/root/CODE/alpha","projections":{"asOfSeq":1,"values":{"title":"Alpha"}}},
              {"sessionId":"ses_b","updatedAt":20,"running":true,"blank":false,"cwd":"/root/CODE/beta"}
            ]}
        """.trimIndent()

        private val SINGLE_SESSION = """
            {"items":[
              {"sessionId":"ses_a","updatedAt":10,"running":false,"blank":false,"cwd":"/root/CODE/alpha","projections":{"asOfSeq":1,"values":{"title":"Alpha"}}}
            ]}
        """.trimIndent()

        private const val SKILLS = """{"skills":[{"name":"commit-helper","description":"Git","modelInvocable":true}]}"""

        private const val AUTOMATION =
            """{"items":[{"id":"auto-1","name":"nightly","enabled":true,"task":"check","workspaceId":"w1","onOverlap":"skip","selector":{},"scheduledAt":"t","createdAt":"t","updatedAt":"t","state":"scheduled","nextAt":"t"}]}"""

        private const val SETTINGS_DESCRIBE =
            """{"writable":true,"hasDocument":false,"namespaces":[{"ns":"llm-deepseek","schema":{"type":"object","properties":{"model":{"type":"string"},"maxTokens":{"type":"integer"},"verbose":{"type":"boolean"},"nested":{"type":"object"}}},"value":{"model":"v3","maxTokens":5,"verbose":false},"applies":"live","secrets":[],"revision":3}]}"""

        private const val MUTATED =
            """{"ns":"llm-deepseek","schema":{},"value":{},"applies":"live","secrets":[],"revision":4}"""

        private const val MODEL_CATALOG =
            """{"default":{"provider":"deepseek-official","model":"deepseek-v4-flash"},"routableProviders":["deepseek-official"],"groups":[{"id":"deepseek-official","name":"DeepSeek","models":[{"id":"v3","name":"V3"}]}],"failures":[]}"""

        private const val GIT =
            """{"currentBranch":"main","detached":false,"worktreePath":"/tmp","isolated":false,"dirtyCount":0,"unpushedCount":0,"branches":[]}"""

        private const val DIRECTORY =
            """{"path":"/tmp","home":"/root","crumbs":[],"entries":[],"truncated":false}"""

        private const val SUBAGENTS =
            """{"entries":[{"kind":"child","id":"child-1","mode":"continuable","activity":"inactive","hasChildren":false,"label":"worker"}],"parentAvailable":true}"""
    }
}
