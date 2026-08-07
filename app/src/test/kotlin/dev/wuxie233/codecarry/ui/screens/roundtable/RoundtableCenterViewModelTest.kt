package dev.wuxie233.codecarry.ui.screens.roundtable

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.api.PiApi
import dev.wuxie233.codecarry.data.api.PiCreateRoundtableRequest
import dev.wuxie233.codecarry.data.api.PiLineupProposalDto
import dev.wuxie233.codecarry.data.api.PiLineupProposalItemDto
import dev.wuxie233.codecarry.data.api.PiPersonaDto
import dev.wuxie233.codecarry.data.api.PiSpeakerPolicyDto
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URLEncoder
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoundtableCenterViewModelTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val viewModels = mutableListOf<RoundtableCenterViewModel>()
    private val collectJobs = mutableListOf<Job>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `roundtable center lifecycle never calls session store`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakeRoundtableService()
        val serverFixture = serverFixture(backgroundScope)
        val vm = RoundtableCenterViewModel(
            savedStateHandle = savedStateHandle(serverFixture.serverId),
            context = appContext,
            api = piApi(requests, service),
            settingsRepository = settingsRepository(backgroundScope),
            serverRepository = serverFixture.repository,
        ).also { viewModels.add(it) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { }
        }
        advanceUntilIdle()

        createConfiguredRoundtable(vm)
        createConfiguredRoundtable(vm)
        vm.setFilter(RoundtableFilter.All)
        advanceUntilIdle()

        val createdState = vm.uiState.first { it.items.size == 2 }
        assertEquals(2, createdState.items.count { it.status == dev.wuxie233.codecarry.domain.model.Roundtable.Status.Running })

        val firstId = createdState.items.first().id
        vm.archiveRoundtable(firstId)
        advanceUntilIdle()

        val archivedState = vm.uiState.first { state ->
            state.items.any { it.id == firstId && it.status == dev.wuxie233.codecarry.domain.model.Roundtable.Status.Archived }
        }
        assertEquals(1, archivedState.items.count { it.status == dev.wuxie233.codecarry.domain.model.Roundtable.Status.Running })

        val remainingId = archivedState.items.first { it.id != firstId }.id
        vm.deleteRoundtable(remainingId)
        advanceUntilIdle()

        val deletedState = vm.uiState.first { it.items.map { item -> item.id } == listOf(firstId) }
        assertEquals(listOf(firstId), deletedState.items.map { it.id })
        assertEquals(0, requests.count { it.url.encodedPath.startsWith("/session") })
        assertFalse(requests.any { it.url.encodedPath == "/session" })
        assertTrue(requests.any { it.method == HttpMethod.Delete && it.url.encodedPath == "/roundtables/$remainingId" })
    }

    @Test
    fun `topic proposal edit and start saves final lineup`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakeRoundtableService()
        val serverFixture = serverFixture(backgroundScope)
        val vm = RoundtableCenterViewModel(savedStateHandle(serverFixture.serverId), appContext, piApi(requests, service), settingsRepository(backgroundScope), serverFixture.repository).also { viewModels.add(it) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.createRoundtable()
        vm.uiState.first { it.configEditor?.isLoadingCatalog == false }
        vm.updateConfigTopic("Configured debate")
        vm.proposeLineup()
        val review = vm.uiState.first { it.configEditor?.step == NewRoundtableStep.Review }.configEditor
        assertNotNull(review)
        assertEquals(3, review?.roles?.size)
        assertTrue(requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables/lineup-proposal" })

        vm.swapRole("mbti-intj", "ljg-action-forger")
        vm.updateRoleModel("ljg-action-forger", "pi-roundtable-ljg-action-forger-alt")
        vm.updateCadence(RoundtableCadence.RoundRobin)
        vm.updateMaxTurnsPerRound(4)
        vm.saveConfigEditor()
        advanceUntilIdle()

        val savedState = vm.uiState.first { state -> state.configEditor == null && requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables" } }
        assertTrue(savedState.configEditor == null)
        val createRequest = requests.last { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables" }
        val body = json.decodeFromString(PiCreateRoundtableRequest.serializer(), createRequest.body.bodyAsText())
        assertEquals("Configured debate", body.topic)
        assertEquals("ljg-action-forger", body.roster?.first()?.id)
        assertEquals("pi-roundtable-ljg-action-forger-alt", body.roster?.first()?.model)
        assertEquals("round_robin", body.speakerPolicy?.mode)
        assertEquals(4, body.limits?.maxTurnsPerRound)
    }


    @Test
    fun `save template and apply reproduces lineup and config`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakeRoundtableService()
        val serverFixture = serverFixture(backgroundScope)
        val vm = RoundtableCenterViewModel(savedStateHandle(serverFixture.serverId), appContext, piApi(requests, service), settingsRepository(backgroundScope), serverFixture.repository).also { viewModels.add(it) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.createRoundtable()
        vm.uiState.first { it.configEditor?.isLoadingCatalog == false }
        vm.updateConfigTopic("Reusable launch template")
        vm.uiState.first { it.configEditor?.topic == "Reusable launch template" }
        vm.proposeLineup()
        vm.uiState.first { it.configEditor?.step == NewRoundtableStep.Review }
        vm.swapRole("mbti-intj", "ljg-action-forger")
        vm.updateRoleModel("ljg-action-forger", "pi-roundtable-ljg-action-forger-alt")
        vm.updateCadence(RoundtableCadence.FreeRoundtable)
        vm.updateMaxTurnsPerRound(5)
        vm.saveLineupTemplate()
        advanceUntilIdle()
        val template = vm.uiState.value.configEditor?.templates?.singleOrNull()
        assertNotNull(template)

        vm.dismissConfigEditor()
        vm.uiState.first { it.configEditor == null }
        vm.createRoundtable()
        vm.uiState.first { state ->
            val editor = state.configEditor
            editor?.topic == "" && editor.catalog.any { entry -> entry.providerId == "persona-ljg-action-forger" }
        }
        vm.updateConfigTopic("New topic from template")
        vm.uiState.first { it.configEditor?.topic == "New topic from template" }
        vm.applyTemplate(template!!.id)
        advanceUntilIdle()
        val applied = vm.uiState.value.configEditor
        assertEquals(template.id, applied?.selectedTemplateId)
        assertEquals("ljg-action-forger", applied?.roles?.first()?.roleId)
        assertEquals("pi-roundtable-ljg-action-forger-alt", applied?.roles?.first()?.model)
        assertEquals(RoundtableCadence.FreeRoundtable, applied?.cadence)
        assertEquals(5, applied?.maxTurnsPerRound)

        vm.saveConfigEditor()
        vm.uiState.first { state -> state.configEditor == null && requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables" } }
        assertTrue(requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables" })
        val createRequest = requests.last { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables" }
        val body = json.decodeFromString(PiCreateRoundtableRequest.serializer(), createRequest.body.bodyAsText())
        assertEquals("New topic from template", body.topic)
        assertEquals("ljg-action-forger", body.roster?.first()?.id)
        assertEquals("pi-roundtable-ljg-action-forger-alt", body.roster?.first()?.model)
        assertEquals("free_roundtable", body.speakerPolicy?.mode)
        assertEquals(5, body.limits?.maxTurnsPerRound)
    }

    @Test
    fun `bad baseUrl bad model and disabled provider are rejected with error`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakeRoundtableService()
        service.catalogMode = CatalogMode.BadBaseUrl
        val serverFixture = serverFixture(backgroundScope)
        val vm = RoundtableCenterViewModel(savedStateHandle(serverFixture.serverId), appContext, piApi(requests, service), settingsRepository(backgroundScope), serverFixture.repository).also { viewModels.add(it) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.createRoundtable()
        vm.uiState.first { it.configEditor?.catalog?.firstOrNull()?.baseUrl == "not a url" }
        vm.updateConfigTopic("Validation topic")
        vm.proposeLineup()
        vm.uiState.first { it.configEditor?.step == NewRoundtableStep.Review }
        vm.saveConfigEditor()
        val badBaseUrl = vm.uiState.first { it.configEditor?.error != null }.configEditor?.error.orEmpty()
        assertTrue(badBaseUrl, badBaseUrl.contains("bad baseUrl"))
        assertEquals(0, requests.count { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables" })

        service.catalogMode = CatalogMode.Valid
        vm.dismissConfigEditor()
        vm.createRoundtable()
        vm.uiState.first { it.configEditor?.error == null && it.configEditor?.catalog?.firstOrNull()?.baseUrl?.startsWith("http") == true }
        vm.updateConfigTopic("Validation topic")
        vm.proposeLineup()
        vm.uiState.first { it.configEditor?.step == NewRoundtableStep.Review }
        vm.updateRoleModel("mbti-intj", "unknown-model")
        vm.saveConfigEditor()
        val badModel = vm.uiState.first { it.configEditor?.error != null }.configEditor?.error.orEmpty()
        assertTrue(badModel, badModel.contains("unknown model"))
        assertEquals(0, requests.count { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables" })

        service.catalogMode = CatalogMode.DisabledProvider
        vm.dismissConfigEditor()
        vm.createRoundtable()
        vm.uiState.first { it.configEditor?.catalog?.firstOrNull()?.enabled == false }
        vm.updateConfigTopic("Validation topic")
        vm.proposeLineup()
        vm.uiState.first { it.configEditor?.step == NewRoundtableStep.Review }
        vm.saveConfigEditor()
        val disabled = vm.uiState.first { it.configEditor?.error != null }.configEditor?.error.orEmpty()
        assertTrue(disabled, disabled.contains("disabled"))
        assertEquals(0, requests.count { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables" })
    }

    @Test
    fun `roundtable summary loads knowledge network and open questions`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakeRoundtableService()
        val serverFixture = serverFixture(backgroundScope)
        val vm = RoundtableSummaryViewModel(summarySavedStateHandle(serverFixture.serverId), appContext, piApi(requests, service), json, serverFixture.repository).also { summary ->
            collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { summary.uiState.collect {} }
        }
        advanceUntilIdle()

        val state = vm.uiState.first { !it.isLoading && it.knowledgeNetworkMermaid != null }
        assertEquals("round-summary-1", state.roundtableId)
        assertTrue(state.knowledgeNetworkMermaid.orEmpty().contains("graph TD"))
        assertEquals(listOf("Which operational failure should stay visible?"), state.openQuestions)
        assertTrue(state.markdown.contains("## Commands"))
        assertTrue(requests.any { it.method == HttpMethod.Get && it.url.encodedPath == "/roundtables/round-summary-1/transcript" && it.url.parameters["format"] == "md" })
        assertTrue(requests.any { it.method == HttpMethod.Get && it.url.encodedPath == "/roundtables/round-summary-1/transcript" && it.url.parameters["format"] == null })
    }

    @Test
    fun `roundtable center resolves bearer token from server repository`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakeRoundtableService()
        val serverFixture = serverFixture(backgroundScope, token = "repository-token")
        val vm = RoundtableCenterViewModel(
            savedStateHandle = savedStateHandle(serverFixture.serverId),
            context = appContext,
            api = piApi(requests, service),
            settingsRepository = settingsRepository(backgroundScope),
            serverRepository = serverFixture.repository,
        ).also { viewModels.add(it) }
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val loaded = vm.uiState.first { !it.isLoading }
        assertEquals("Pi Test", loaded.serverName)
        val listRequest = requests.first { it.method == HttpMethod.Get && it.url.encodedPath == "/roundtables" }
        assertEquals("Bearer repository-token", listRequest.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `active filter keeps completed error and unknown but excludes archived roundtables`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakeRoundtableService().apply {
            put("round-running", "running")
            put("round-awaiting", "awaiting_command")
            put("round-skip", "awaiting_skip")
            put("round-paused", "paused")
            put("round-awaiting-registry", "awaiting")
            put("round-ended", "completed")
            put("round-archived", "archived")
            put("round-error", "error")
            put("round-unknown", "unknown_state")
        }
        val serverFixture = serverFixture(backgroundScope)
        val vm = RoundtableCenterViewModel(
            savedStateHandle = savedStateHandle(serverFixture.serverId),
            context = appContext,
            api = piApi(requests, service),
            settingsRepository = settingsRepository(backgroundScope),
            serverRepository = serverFixture.repository,
        ).also { viewModels.add(it) }
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val loaded = vm.uiState.first { state -> state.items.size == 8 }
        val activeIds = loaded.items.map { it.id }

        assertEquals(setOf("round-running", "round-awaiting", "round-skip", "round-paused", "round-awaiting-registry", "round-ended", "round-error", "round-unknown"), activeIds.toSet())
        assertEquals(8, activeIds.size)
        assertEquals(dev.wuxie233.codecarry.domain.model.Roundtable.Status.AwaitingCommand, loaded.items.single { it.id == "round-awaiting" }.status)
        assertEquals(dev.wuxie233.codecarry.domain.model.Roundtable.Status.AwaitingCommand, loaded.items.single { it.id == "round-paused" }.status)
        assertEquals(dev.wuxie233.codecarry.domain.model.Roundtable.Status.AwaitingSkip, loaded.items.single { it.id == "round-awaiting-registry" }.status)
        assertEquals(dev.wuxie233.codecarry.domain.model.Roundtable.Status.Completed, loaded.items.single { it.id == "round-ended" }.status)
        assertEquals(dev.wuxie233.codecarry.domain.model.Roundtable.Status.Error, loaded.items.single { it.id == "round-error" }.status)
        assertEquals(dev.wuxie233.codecarry.domain.model.Roundtable.Status.Unknown, loaded.items.single { it.id == "round-unknown" }.status)
    }

    @Test
    fun `resume only sends continue for awaiting command roundtables`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val commandBodies = Collections.synchronizedList(mutableListOf<String>())
        val service = FakeRoundtableService().apply {
            put("round-awaiting", "awaiting_command")
            put("round-paused", "paused")
            put("round-skip", "awaiting")
            put("round-ended", "completed")
        }
        val serverFixture = serverFixture(backgroundScope)
        val vm = RoundtableCenterViewModel(
            savedStateHandle = savedStateHandle(serverFixture.serverId),
            context = appContext,
            api = piApi(requests, service, commandBodies),
            settingsRepository = settingsRepository(backgroundScope),
            serverRepository = serverFixture.repository,
        ).also { viewModels.add(it) }
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.uiState.first { state -> state.items.any { it.id == "round-awaiting" } }

        vm.resumeRoundtable("round-ended")
        advanceUntilIdle()
        assertEquals(appContext.getString(dev.wuxie233.codecarry.R.string.roundtable_error_resume_unavailable), vm.uiState.value.error)
        assertEquals(0, requests.count { it.url.encodedPath.endsWith("/command") })

        vm.resumeRoundtable("round-skip")
        advanceUntilIdle()
        assertEquals(0, requests.count { it.url.encodedPath.endsWith("/command") })

        vm.resumeRoundtable("round-awaiting")
        advanceUntilIdle()
        vm.uiState.first { state -> !state.isMutating && commandBodies.isNotEmpty() }

        assertTrue(requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables/round-awaiting/command" })
        assertTrue(commandBodies.single().contains("\"command\":\"可\""))

        commandBodies.clear()
        vm.resumeRoundtable("round-paused")
        advanceUntilIdle()
        vm.uiState.first { state -> !state.isMutating && commandBodies.isNotEmpty() }

        assertTrue(requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/roundtables/round-paused/command" })
        assertTrue(commandBodies.single().contains("\"command\":\"可\""))
    }

    private fun piApi(
        requests: MutableList<HttpRequestData>,
        service: FakeRoundtableService,
        commandBodies: MutableList<String>? = null,
    ): PiApi {
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath == "/roundtables" && request.method == HttpMethod.Get -> respondJson(service.list())
                request.url.encodedPath == "/roundtables/lineup-proposal" && request.method == HttpMethod.Post -> respondJson(service.proposal())
                request.url.encodedPath == "/roundtables" && request.method == HttpMethod.Post -> respondJson(service.create(request.body.bodyAsText()))
                request.url.encodedPath == "/roundtables/round-summary-1/transcript" && request.method == HttpMethod.Get && request.url.parameters["format"] == "md" -> respond(service.transcriptMarkdown(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/markdown"))
                request.url.encodedPath == "/roundtables/round-summary-1/transcript" && request.method == HttpMethod.Get -> respondJson(service.transcriptJson())
                request.url.encodedPath == "/personas" && request.method == HttpMethod.Get -> respondJson(service.personas())
                request.url.encodedPath == "/catalog" && request.method == HttpMethod.Get -> respondJson(service.catalog())
                request.url.encodedPath.startsWith("/roundtables/") && request.url.encodedPath.endsWith("/command") && request.method == HttpMethod.Post -> {
                    commandBodies?.add(request.body.bodyAsText())
                    respondJson("""{"accepted":true}""")
                }
                request.url.encodedPath.endsWith("/archive") && request.method == HttpMethod.Post -> respondJson(service.archive(request.url.encodedPath.substringAfter("/roundtables/").substringBefore("/archive")))
                request.url.encodedPath.startsWith("/roundtables/") && request.method == HttpMethod.Delete -> respondJson(service.delete(request.url.encodedPath.substringAfterLast('/')))
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return PiApi(client, json)
    }

    private fun savedStateHandle(serverId: String): SavedStateHandle = SavedStateHandle(
        mapOf(
            "serverId" to encode(serverId),
        )
    )

    private fun summarySavedStateHandle(serverId: String): SavedStateHandle = SavedStateHandle(
        mapOf(
            "serverId" to encode(serverId),
            "roundtableId" to encode("round-summary-1"),
        )
    )

    private suspend fun serverFixture(scope: CoroutineScope, token: String = "pi-token"): ServerFixture {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("roundtable-servers-${System.nanoTime()}.preferences_pb") },
        )
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound) }) {
            install(ContentNegotiation) { json(json) }
        }
        val repository = ServerRepository(dataStore, OpenCodeApi(client, json), PiApi(client, json), json)
        val server = repository.addServer(
            url = "https://pi.example.test",
            type = ServerType.PI_ROUNDTABLE,
            token = token,
            name = "Pi Test",
        )
        return ServerFixture(repository, server.id)
    }

    private fun settingsRepository(scope: CoroutineScope): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("roundtable-settings-${System.nanoTime()}.preferences_pb") },
        )
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmpFolder.root
        }
        return SettingsRepository(dataStore, context)
    }

    private data class ServerFixture(
        val repository: ServerRepository,
        val serverId: String,
    )

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private inner class FakeRoundtableService {
        private val items = linkedMapOf<String, String>()
        private var next = 0
        var catalogMode: CatalogMode = CatalogMode.Valid

        fun list(): String = items.entries.joinToString(prefix = "{\"items\":[", postfix = "]}") { (id, status) -> item(id, status) }

        fun put(id: String, status: String) {
            items[id] = status
        }

        fun create(raw: String = "{}"): String {
            next += 1
            val id = "round-$next"
            items[id] = "running"
            return item(id, "running")
        }

        fun personas(): String = """
            {"items":[{"id":"mbti-intj","name":"INTJ Archetype","mbti":"INTJ","stancePrompt":"Architect the causal model.","style":"Structured and concise.","actionTagPrefs":["质疑","修正"],"provider":"persona-mbti-intj","model":"pi-roundtable-mbti-intj-placeholder","fallback":[],"enabled":true},{"id":"mbti-entp","name":"ENTP Archetype","mbti":"ENTP","stancePrompt":"Generate competing hypotheses.","style":"Expansive and sharp.","actionTagPrefs":["补充","反驳"],"provider":"persona-mbti-entp","model":"pi-roundtable-mbti-entp-placeholder","fallback":[],"enabled":true},{"id":"mbti-infj","name":"INFJ Archetype","mbti":"INFJ","stancePrompt":"Surface stakeholder needs.","style":"Integrative.","actionTagPrefs":["综合","补充"],"provider":"persona-mbti-infj","model":"pi-roundtable-mbti-infj-placeholder","fallback":[],"enabled":true},{"id":"ljg-action-forger","name":"ljg Action Forger","mbti":"ENTJ","stancePrompt":"Turn ambiguity into action.","style":"Decisive and operational.","actionTagPrefs":["陈述","修正"],"provider":"persona-ljg-action-forger","model":"pi-roundtable-ljg-action-forger-placeholder","fallback":[],"enabled":true}]}
        """.trimIndent()

        fun proposal(): String = json.encodeToString(
            PiLineupProposalDto.serializer(),
            PiLineupProposalDto(
                topic = "Configured debate",
                speakerPolicy = PiSpeakerPolicyDto(mode = "moderator_routed"),
                items = listOf(
                    PiLineupProposalItemDto(proposalPersona("mbti-intj", "INTJ Archetype", "INTJ", "persona-mbti-intj", "pi-roundtable-mbti-intj-placeholder"), "INTJ pressure-tests the topic."),
                    PiLineupProposalItemDto(proposalPersona("mbti-entp", "ENTP Archetype", "ENTP", "persona-mbti-entp", "pi-roundtable-mbti-entp-placeholder"), "ENTP maps alternatives."),
                    PiLineupProposalItemDto(proposalPersona("mbti-infj", "INFJ Archetype", "INFJ", "persona-mbti-infj", "pi-roundtable-mbti-infj-placeholder"), "INFJ checks stakeholder fit."),
                ),
            ),
        )

        fun catalog(): String = when (catalogMode) {
            CatalogMode.Valid -> catalogEntry("http://127.0.0.1:8780/persona-mbti-intj/v1", enabled = true, status = "valid")
            CatalogMode.BadBaseUrl -> catalogEntry("not a url", enabled = true, status = "valid")
            CatalogMode.DisabledProvider -> catalogEntry("http://127.0.0.1:8780/persona-mbti-intj/v1", enabled = false, status = "disabled")
        }

        fun transcriptMarkdown(): String = """
            # Roundtable Transcript: round-summary-1
            
            ## Timeline
            
            ### Ada [persona]
            - startedAt: 2026-06-02T00:00:00Z
            - endedAt: 2026-06-02T00:00:01Z
            
            Ada message.
            
            ## Commands
            - 2026-06-02T00:00:02Z command 1: 止 accepted=true
            
            ## Final Knowledge Network and Open Questions
            
            ## 知识网络
            ```mermaid
            graph TD
              Topic[Topic] --> Q1[Open]
            ```
            
            ## 开放问题
            - Which operational failure should stay visible?
        """.trimIndent()

        fun transcriptJson(): String = """
            {"protocolVersion":1,"roundId":"round-summary-1","events":[{"protocolVersion":1,"eventId":9,"roundId":"round-summary-1","turnId":null,"sequence":9,"type":"round_end","author":{"id":"system","name":"System","mbti":"SYSTEM","role":"system","colorSeed":"system"},"payload":{"reason":"stopped","turnCount":1,"summaryKind":"global_knowledge_network","finalSummaryMarkdown":"## 知识网络\n```mermaid\ngraph TD\n  Topic[Topic] --> Q1[Open]\n```\n\n## 开放问题\n- Which operational failure should stay visible?","openQuestions":["Which operational failure should stay visible?"]},"ts":"2026-06-02T00:00:02Z"}],"commands":[],"assembled":{} }
        """.trimIndent()

        fun archive(id: String): String {
            items[id] = "archived"
            return item(id, "archived")
        }

        fun delete(id: String): String {
            items.remove(id)
            return "{\"protocolVersion\":1,\"id\":\"$id\",\"deleted\":true}"
        }

        private fun item(id: String, status: String): String = """
            {"id":"$id","roundId":"$id","topic":"Topic $id","status":"$status","roundCount":1,"createdAt":"2026-06-02T00:00:00Z","updatedAt":"2026-06-02T00:00:0${id.takeLast(1)}Z","roster":[{"id":"ada","name":"Ada","role":"persona","colorSeed":"persona-ada"},{"id":"curie","name":"Curie","role":"persona","colorSeed":"persona-curie"}]}
        """.trimIndent()

        private fun catalogEntry(baseUrl: String, enabled: Boolean, status: String): String = """
            {"items":[{"providerId":"persona-mbti-intj","displayName":"INTJ Gateway","baseUrl":"$baseUrl","api":"custom","models":[{"id":"pi-roundtable-mbti-intj-placeholder","displayName":"INTJ model","enabled":true}],"fallback":[{"providerId":"backup-provider","model":"backup-model"}],"enabled":$enabled,"validation":{"status":"$status","message":"${if (status == "disabled") "Provider disabled" else "Provider ready"}","streamingChecked":$enabled}},{"providerId":"persona-mbti-entp","displayName":"ENTP Gateway","baseUrl":"http://127.0.0.1:8780/persona-mbti-entp/v1","api":"custom","models":[{"id":"pi-roundtable-mbti-entp-placeholder","displayName":"ENTP model","enabled":true}],"fallback":[],"enabled":true,"validation":{"status":"valid","message":"Provider ready","streamingChecked":true}},{"providerId":"persona-mbti-infj","displayName":"INFJ Gateway","baseUrl":"http://127.0.0.1:8780/persona-mbti-infj/v1","api":"custom","models":[{"id":"pi-roundtable-mbti-infj-placeholder","displayName":"INFJ model","enabled":true}],"fallback":[],"enabled":true,"validation":{"status":"valid","message":"Provider ready","streamingChecked":true}},{"providerId":"persona-ljg-action-forger","displayName":"Action Forger Gateway","baseUrl":"http://127.0.0.1:8780/persona-ljg-action-forger/v1","api":"custom","models":[{"id":"pi-roundtable-ljg-action-forger-placeholder","displayName":"Action model","enabled":true},{"id":"pi-roundtable-ljg-action-forger-alt","displayName":"Action alt","enabled":true}],"fallback":[],"enabled":true,"validation":{"status":"valid","message":"Provider ready","streamingChecked":true}},{"providerId":"backup-provider","displayName":"Backup Gateway","baseUrl":"http://127.0.0.1:8780/backup/v1","api":"custom","models":[{"id":"backup-model","displayName":"Backup model","enabled":true}],"fallback":[],"enabled":true,"validation":{"status":"valid","message":"Provider ready","streamingChecked":true}}]}
        """.trimIndent()

        private fun proposalPersona(id: String, name: String, mbti: String, provider: String, model: String): PiPersonaDto = PiPersonaDto(
            id = id,
            name = name,
            mbti = mbti,
            stancePrompt = "Test stance",
            style = "Test style",
            actionTagPrefs = listOf("陈述"),
            provider = provider,
            model = model,
        )
    }

    private enum class CatalogMode { Valid, BadBaseUrl, DisabledProvider }

    private suspend fun TestScope.createConfiguredRoundtable(vm: RoundtableCenterViewModel) {
        vm.createRoundtable()
        vm.uiState.first { it.configEditor?.isLoadingCatalog == false }
        vm.updateConfigTopic("Lifecycle topic")
        vm.proposeLineup()
        vm.uiState.first { it.configEditor?.step == NewRoundtableStep.Review }
        vm.saveConfigEditor()
        vm.uiState.first { it.configEditor == null }
    }
}

private suspend fun OutgoingContent.bodyAsText(): String = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes().toString(Charsets.UTF_8)
    is OutgoingContent.ReadChannelContent -> readFrom().readRemaining().readText()
    is OutgoingContent.WriteChannelContent -> coroutineScope {
        val channel = ByteChannel(autoFlush = true)
        withContext(Dispatchers.Default) {
            writeTo(channel)
            channel.close(null)
        }
        channel.readRemaining().readText()
    }
    is OutgoingContent.NoContent -> ""
    is OutgoingContent.ProtocolUpgrade -> error("Unexpected protocol upgrade request body")
}
