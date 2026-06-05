package dev.minios.ocremote.ui.screens.chat

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PiCommandRequest
import dev.minios.ocremote.data.api.PiPersonaDto
import dev.minios.ocremote.data.api.RoundtableSseEvent
import dev.minios.ocremote.data.transport.PiRoundtableEventProcessor
import dev.minios.ocremote.data.preferences.SessionListPreferencesRepository
import dev.minios.ocremote.data.repository.DraftRepository
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.ServerType
import dev.minios.ocremote.domain.transport.PiTransportEvent
import dev.minios.ocremote.domain.transport.TransportEvent
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.yield
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRoundtableSteeringTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)
    private val viewModels = mutableListOf<ChatViewModel>()
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
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `steering actions send expected pi commands`() = runTest(dispatcher) {
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val vm = newViewModel(sentCommands)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        sendAndAwait { onDone -> vm.switchRoundtableCadence(RoundtableCadenceMode.RoundRobin, onDone) }
        sendAndAwait { onDone -> vm.continueRoundtable(onDone) }
        sendAndAwait { onDone -> vm.stopRoundtable(onDone) }
        sendAndAwait { onDone -> vm.deepenRoundtableSection(onDone) }
        sendAndAwait { onDone -> vm.mentionRoundtableRole("ada", "pressure test the premise", onDone) }
        sendAndAwait { onDone -> vm.injectAsParticipant("observer note", onDone) }
        sendAndAwait { onDone -> vm.introducePersona("new-persona", onDone) }

        assertEquals(
            listOf("switch_cadence", "可", "止", "深入", "@mention", "inject", "引入新人物"),
            sentCommands.map { it.command },
        )
        sentCommands.forEach { command -> assertEquals(ROUND_ID, command.roundId) }
        assertEquals(RoundtableCadenceMode.RoundRobin.wireName, sentCommands[0].speakerPolicy?.mode)
        assertEquals(RoundtableCadenceMode.RoundRobin.wireName, sentCommands[0].arguments)
        assertEquals("ada", sentCommands[4].targetPersonaId)
        assertEquals("pressure test the premise", sentCommands[4].instruction)
        assertEquals("observer note", sentCommands[5].content)
        assertEquals("new-persona", sentCommands[6].arguments)
    }

    @Test
    fun `supplement guidance reuses inject command and trims content`() = runTest(dispatcher) {
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val vm = newViewModel(sentCommands)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        sendAndAwait { onDone -> vm.supplementRoundtableGuidance("  please clarify the budget cap  ", onDone) }

        val command = sentCommands.single()
        assertEquals("inject", command.command)
        assertEquals("please clarify the budget cap", command.content)
        assertEquals(ROUND_ID, command.roundId)
    }

    @Test
    fun `roundtable send message injects content and shows local user message`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val vm = newViewModel(sentCommands, eventReducer)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.sendMessage("hello table")
        awaitSentCommand(sentCommands)

        val command = sentCommands.single()
        assertEquals("inject", command.command)
        assertEquals("hello table", command.content)
        awaitUserMessage(vm)
        val userMessage = vm.uiState.value.messages.single { it.isUser }
        assertEquals("hello table", userMessage.parts.filterIsInstance<dev.minios.ocremote.domain.model.Part.Text>().single().text)
    }

    @Test
    fun `roundtable live state moves from thinking to speaking to idle`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val vm = newViewModel(sentCommands, eventReducer)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val processor = PiRoundtableEventProcessor(json)
        val events = fixtureEvents("happy-one-round.json")
        processWireEvent(processor, eventReducer, events[0])
        processWireEvent(processor, eventReducer, events[1])
        advanceUntilIdle()
        assertEquals(PiRoleLiveState.Thinking, vm.uiState.value.runState.roleStates.single { it.personaId == "persona-ada" }.liveState)

        processWireEvent(processor, eventReducer, events[2])
        advanceUntilIdle()
        assertEquals(PiRoleLiveState.Speaking, vm.uiState.value.runState.roleStates.single { it.personaId == "persona-ada" }.liveState)

        processWireEvent(processor, eventReducer, events[3])
        processWireEvent(processor, eventReducer, events[4])
        advanceUntilIdle()
        assertEquals(PiRoleLiveState.Idle, vm.uiState.value.runState.roleStates.single { it.personaId == "persona-ada" }.liveState)
    }

    @Test
    fun `awaiting skip run state sends skip command`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val sentBodies = Collections.synchronizedList(mutableListOf<String>())
        val vm = newViewModel(sentCommands, eventReducer, sentBodies)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        eventReducer.processEvent(TransportEvent.Pi(awaitingSkipEvent()), SERVER_ID)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.runState.awaitingSkip)

        sendAndAwait { onDone -> vm.skipAwaitingPersona(onDone) }

        val command = sentCommands.single()
        assertEquals("skip", command.command)
        assertEquals(ROUND_ID, command.roundId)
        assertEquals("persona-turing", command.personaId)
        assertEquals(null, command.targetPersonaId)
        assertEquals(null, command.arguments)
        val requestBody = sentBodies.single()
        assertTrue(requestBody.contains("\"command\":\"skip\""))
        assertTrue(requestBody.contains("\"personaId\":\"persona-turing\""))
        assertFalse(requestBody.contains("targetPersonaId"))
    }

    private suspend fun sendAndAwait(send: ((Boolean) -> Unit) -> Unit) {
        val result = CompletableDeferred<Boolean>()
        send { ok -> result.complete(ok) }
        scheduler.advanceUntilIdle()
        assertEquals(true, result.await())
    }

    private suspend fun awaitSentCommand(sentCommands: List<PiCommandRequest>) {
        repeat(20) {
            scheduler.advanceUntilIdle()
            if (sentCommands.isNotEmpty()) return
            yield()
        }
        assertTrue("Expected a Pi command to be sent", sentCommands.isNotEmpty())
    }

    private suspend fun awaitUserMessage(vm: ChatViewModel) {
        repeat(20) {
            scheduler.advanceUntilIdle()
            if (vm.uiState.value.messages.any { it.isUser }) return
            yield()
        }
        assertTrue("Expected a local user message", vm.uiState.value.messages.any { it.isUser })
    }

    private fun newViewModel(
        sentCommands: MutableList<PiCommandRequest>,
        eventReducer: EventReducer = EventReducer(),
        sentBodies: MutableList<String>? = null,
    ): ChatViewModel {
        return ChatViewModel(
            savedStateHandle = savedStateHandle(),
            eventReducer = eventReducer,
            api = openCodeApi(),
            piApi = piApi(sentCommands, sentBodies),
            json = json,
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
        ).also { viewModels.add(it) }
    }

    private fun savedStateHandle() = SavedStateHandle(
        mapOf(
            "serverUrl" to "https://pi.example.test",
            "username" to "",
            "password" to "pi-token",
            "serverName" to "Pi Test",
            "serverId" to SERVER_ID,
            "sessionId" to ROUND_ID,
            "serverType" to ServerType.PI_ROUNDTABLE.name,
        )
    )

    private fun piApi(sentCommands: MutableList<PiCommandRequest>, sentBodies: MutableList<String>? = null): PiApi {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/roundtables" && request.method == HttpMethod.Get -> respondJson(roundtablesJson())
                request.url.encodedPath == "/personas" && request.method == HttpMethod.Get -> respondJson(personasJson())
                request.url.encodedPath == "/roundtables/$ROUND_ID/command" && request.method == HttpMethod.Post -> {
                    val body = request.body.bodyAsText()
                    sentBodies?.add(body)
                    sentCommands += json.decodeFromString(PiCommandRequest.serializer(), body)
                    respondJson("""{"accepted":true}""")
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return PiApi(client, json)
    }

    private fun openCodeApi(): OpenCodeApi {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return OpenCodeApi(client, json)
    }

    private fun roundtablesJson(): String = """
        [{
          "id":"$ROUND_ID",
          "topic":"Steering",
          "status":"awaiting_command",
          "roster":[{"id":"ada","name":"Ada","role":"analyst","colorSeed":"ada"}]
        }]
    """.trimIndent()

    private fun personasJson(): String = json.encodeToString(
        listOf(
            PiPersonaDto(
                id = "new-persona",
                name = "New Persona",
                mbti = "INTP",
                stancePrompt = "Challenge assumptions.",
                style = "Concise.",
                actionTagPrefs = listOf("质疑"),
                provider = "fake-provider",
                model = "fake-model",
                enabled = true,
            )
        )
    )

    private fun awaitingSkipEvent(): PiTransportEvent.AwaitingSkip {
        return PiRoundtableEventProcessor(json)
            .processSnapshot(fixtureEvents("fallback-then-skip.json"))
            .filterIsInstance<PiTransportEvent.AwaitingSkip>()
            .single()
    }

    private fun processWireEvent(processor: PiRoundtableEventProcessor, reducer: EventReducer, event: RoundtableSseEvent) {
        processor.accept(event).forEach { transportEvent ->
            reducer.processEvent(TransportEvent.Pi(transportEvent), SERVER_ID)
        }
    }

    private fun fixtureEvents(name: String): List<RoundtableSseEvent> = json.decodeFromString(
        ListSerializer(RoundtableSseEvent.serializer()),
        fixtureFile(name).readText(),
    )

    private fun fixtureFile(name: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { file -> file.parentFile }
            .map { root -> File(root, "contracts/pi-roundtable/fixtures/$name") }
            .firstOrNull { file -> file.exists() }
            ?: error("Missing Pi roundtable fixture $name from $start")
    }

    private fun draftRepository(): DraftRepository {
        val filesDir = tmpFolder.newFolder("drafts-${System.nanoTime()}")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
        }
        return DraftRepository(context)
    }

    private fun sessionListPreferencesRepository(): SessionListPreferencesRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("session-list-${System.nanoTime()}.preferences_pb") },
        )
        return SessionListPreferencesRepository(dataStore)
    }

    private fun settingsRepository(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("settings-${System.nanoTime()}.preferences_pb") },
        )
        val context = object : ContextWrapper(null) {}
        return SettingsRepository(dataStore, context)
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    companion object {
        private const val SERVER_ID = "srv-pi"
        private const val ROUND_ID = "round-fixture-001"
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
