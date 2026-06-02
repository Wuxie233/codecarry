package dev.minios.ocremote.ui.screens.roundtable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PiModelRefDto
import dev.minios.ocremote.data.api.PiPersonaDto
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.domain.model.ServerType
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLEncoder
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class PersonaLibraryViewModelTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
        prettyPrint = true
    }
    private val viewModels = mutableListOf<PersonaLibraryViewModel>()
    private val collectJobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        collectJobs.forEach { it.cancel() }
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `create edit model and reread server truth`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakePersonaService()
        val serverFixture = serverFixture(backgroundScope)
        val vm = PersonaLibraryViewModel(savedStateHandle(serverFixture.serverId), piApi(requests, service), serverFixture.repository).also { viewModels.add(it) }
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.newPersona()
        advanceUntilIdle()
        vm.updateEditor { it.copy(id = "strategist", name = "Strategist", mbti = "ENTJ", stancePrompt = "Drive plans.", style = "Direct.", provider = "provider-a", model = "draft-model") }
        vm.saveEditor()
        advanceUntilIdle()

        val created = vm.uiState.first { state -> state.personas.any { it.id == "strategist" } }
        assertEquals("draft-model", created.personas.first { it.id == "strategist" }.model)

        vm.editPersona(created.personas.first { it.id == "strategist" })
        vm.updateEditor { it.copy(model = "client-edited-model") }
        vm.saveEditor()
        advanceUntilIdle()

        val reread = vm.uiState.first { state -> state.personas.first { it.id == "strategist" }.model == "server-truth-model" }
        assertEquals("server-truth-model", reread.personas.first { it.id == "strategist" }.model)
        assertTrue(requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/personas" })
        assertTrue(requests.any { it.method == HttpMethod.Put && it.url.encodedPath == "/personas/strategist" })
        assertFalse(requests.any { it.url.encodedPath.startsWith("/session") })
    }


    @Test
    fun `AI generate shows draft and does not save until confirmation`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakePersonaService()
        val serverFixture = serverFixture(backgroundScope)
        val vm = PersonaLibraryViewModel(savedStateHandle(serverFixture.serverId), piApi(requests, service), serverFixture.repository).also { viewModels.add(it) }
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val initialCount = vm.uiState.first { !it.isLoading }.personas.size
        vm.openGenerateDialog()
        vm.setGenerateRequirement("一个爱抬杠的INTP安全专家")
        vm.generateDraft()
        advanceUntilIdle()

        val draftState = vm.uiState.first { it.editor?.id == "generated-intp-security-expert" }
        assertEquals(initialCount, draftState.personas.size)
        assertEquals("INTP Security Contrarian", draftState.editor?.name)
        assertTrue(requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/personas/generate" })
        assertFalse(requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/personas" })

        vm.saveEditor()
        advanceUntilIdle()

        val saved = vm.uiState.first { state -> state.personas.any { it.id == "generated-intp-security-expert" } }
        assertEquals(initialCount + 1, saved.personas.size)
        assertTrue(requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/personas" })
    }

    @Test
    fun `export edit and import round trip remains persona schema shaped`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakePersonaService()
        val serverFixture = serverFixture(backgroundScope)
        val vm = PersonaLibraryViewModel(savedStateHandle(serverFixture.serverId), piApi(requests, service), serverFixture.repository).also { viewModels.add(it) }
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val source = vm.uiState.first { it.personas.isNotEmpty() }.personas.first()
        vm.exportPersona(source)
        val exported = vm.uiState.value.exportText ?: error("missing export")
        assertSchemaShaped(exported)
        assertFalse(exported.lowercase().contains("token"))
        assertFalse(exported.lowercase().contains("secret"))

        val edited = exported.replace(source.model, "imported-model")
        vm.setImportText(edited)
        vm.importJson()
        advanceUntilIdle()

        val imported = vm.uiState.first { state -> state.personas.first { it.id == source.id }.model == "imported-model" }
        assertEquals("imported-model", imported.personas.first { it.id == source.id }.model)
        assertTrue(requests.any { it.method == HttpMethod.Put && it.url.encodedPath == "/personas/${source.id}" })
    }

    @Test
    fun `persona library resolves bearer token from server repository`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakePersonaService()
        val serverFixture = serverFixture(backgroundScope, token = "repository-token")
        val vm = PersonaLibraryViewModel(savedStateHandle(serverFixture.serverId), piApi(requests, service), serverFixture.repository).also { viewModels.add(it) }
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val loaded = vm.uiState.first { !it.isLoading }
        assertEquals("Pi Test", loaded.serverName)
        val listRequest = requests.first { it.method == HttpMethod.Get && it.url.encodedPath == "/personas" }
        assertEquals("Bearer repository-token", listRequest.headers[HttpHeaders.Authorization])
    }

    private fun piApi(requests: MutableList<HttpRequestData>, service: FakePersonaService): PiApi {
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath == "/personas" && request.method == HttpMethod.Get -> respondJson(service.list())
                request.url.encodedPath == "/personas/generate" && request.method == HttpMethod.Post -> respondJson(service.generate(request.body.bodyAsText()))
                request.url.encodedPath == "/personas" && request.method == HttpMethod.Post -> respondJson(service.create(request.body.bodyAsText()))
                request.url.encodedPath.startsWith("/personas/") && request.method == HttpMethod.Get -> respondJson(service.get(request.url.encodedPath.substringAfterLast('/')))
                request.url.encodedPath.startsWith("/personas/") && request.method == HttpMethod.Put -> respondJson(service.update(request.url.encodedPath.substringAfterLast('/'), request.body.bodyAsText()))
                request.url.encodedPath.startsWith("/personas/") && request.method == HttpMethod.Delete -> respondJson(service.delete(request.url.encodedPath.substringAfterLast('/')))
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return PiApi(client, json)
    }

    private fun savedStateHandle(serverId: String): SavedStateHandle = SavedStateHandle(
        mapOf(
            "serverId" to encode(serverId),
        )
    )

    private suspend fun serverFixture(scope: kotlinx.coroutines.CoroutineScope, token: String = "pi-token"): ServerFixture {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("persona-servers-${System.nanoTime()}.preferences_pb") },
        )
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound) }) {
            install(ContentNegotiation) { json(json) }
        }
        val repository = ServerRepository(dataStore, OpenCodeApi(client, json), json)
        val server = repository.addServer(
            url = "https://pi.example.test",
            type = ServerType.PI_ROUNDTABLE,
            token = token,
            name = "Pi Test",
        )
        return ServerFixture(repository, server.id)
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

    private fun assertSchemaShaped(raw: String) {
        val persona = json.decodeFromString(PiPersonaDto.serializer(), raw)
        assertTrue(persona.name.isNotBlank())
        assertTrue(persona.mbti.isNotBlank())
        assertTrue(persona.stancePrompt.isNotBlank())
        assertTrue(persona.style.isNotBlank())
        assertTrue(persona.actionTagPrefs.isNotEmpty())
        assertTrue(persona.provider.isNotBlank())
        assertTrue(persona.model.isNotBlank())
    }

    private inner class FakePersonaService {
        private val items = linkedMapOf(
            "mbti-intj" to PiPersonaDto(
                id = "mbti-intj",
                name = "INTJ Archetype",
                mbti = "INTJ",
                stancePrompt = "Architect the causal model.",
                style = "Structured and concise.",
                actionTagPrefs = listOf("质疑", "修正"),
                provider = "persona-mbti-intj",
                model = "pi-roundtable-mbti-intj-placeholder",
                fallback = listOf(PiModelRefDto("fallback-provider", "fallback-model")),
                enabled = true,
            ),
            "ljg-truth-miner" to PiPersonaDto(
                id = "ljg-truth-miner",
                name = "ljg Truth Miner",
                mbti = "INTJ",
                stancePrompt = "挖深不铺广。",
                style = "Sharp synthesis.",
                actionTagPrefs = listOf("质疑"),
                provider = "persona-ljg-truth-miner",
                model = "pi-roundtable-ljg-truth-miner-placeholder",
                enabled = true,
            ),
        )

        fun list(): String = "{\"items\":[${items.values.joinToString(",") { json.encodeToString(PiPersonaDto.serializer(), it) }}]}"

        fun get(id: String): String = "{\"item\":${json.encodeToString(PiPersonaDto.serializer(), items.getValue(id))}}"

        fun create(raw: String): String {
            val persona = json.decodeFromString(PiPersonaDto.serializer(), raw)
            val id = persona.id ?: "created-${items.size + 1}"
            val created = persona.copy(id = id)
            items[id] = created
            return "{\"item\":${json.encodeToString(PiPersonaDto.serializer(), created)}}"
        }

        fun generate(raw: String): String {
            val request = json.parseToJsonElement(raw).jsonObject
            assertEquals("一个爱抬杠的INTP安全专家", request.getValue("requirement").jsonPrimitive.contentOrNull)
            val draft = PiPersonaDto(
                id = "generated-intp-security-expert",
                name = "INTP Security Contrarian",
                mbti = "INTP",
                stancePrompt = "把安全方案当作可被攻击的假设，持续寻找权限、数据和供应链风险。",
                style = "Sharp, skeptical, concise, and evidence-demanding.",
                actionTagPrefs = listOf("质疑", "反驳"),
                provider = "fake-provider",
                model = "fake-model",
                fallback = emptyList(),
                enabled = true,
            )
            return json.encodeToString(PiPersonaDto.serializer(), draft)
        }

        fun update(id: String, raw: String): String {
            val persona = json.decodeFromString(PiPersonaDto.serializer(), raw)
            val serverTruth = if (id == "strategist") persona.copy(id = id, model = "server-truth-model") else persona.copy(id = id)
            items[id] = serverTruth
            return "{\"item\":${json.encodeToString(PiPersonaDto.serializer(), serverTruth)}}"
        }

        fun delete(id: String): String {
            items.remove(id)
            return "{\"protocolVersion\":1,\"id\":\"$id\",\"deleted\":true}"
        }
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
