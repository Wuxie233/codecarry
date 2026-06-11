package dev.minios.ocremote.ui.screens.chat

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.minios.ocremote.R
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
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.ServerType
import dev.minios.ocremote.domain.transport.PiTransportEvent
import dev.minios.ocremote.domain.transport.TransportEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
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
import kotlinx.coroutines.delay
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
    fun `awaiting skip roundtable continue does not send command`() = runTest(dispatcher) {
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val vm = newViewModel(sentCommands, roundtableStatus = "awaiting")
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val accepted = sendAndAwaitResult { onDone -> vm.continueRoundtable(onDone) }

        assertEquals(false, accepted)
        assertTrue(sentCommands.isEmpty())
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
    fun `roundtable composer continue sends control command instead of transcript inject`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val vm = newViewModel(sentCommands, eventReducer)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val accepted = sendAndAwaitResult { onDone -> vm.sendMessage("继续", onResult = onDone) }

        assertEquals(true, accepted)
        assertEquals(listOf("可"), sentCommands.map { it.command })
        assertTrue(eventReducer.roundtableMessages.value[ROUND_ID].orEmpty().none { it is Message.User })
    }

    @Test
    fun `roundtable composer continue waits for awaiting command state`() = runTest(dispatcher) {
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val vm = newViewModel(sentCommands, roundtableStatus = "running")
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val accepted = sendAndAwaitResult { onDone -> vm.sendMessage("继续", onResult = onDone) }

        assertEquals(false, accepted)
        assertTrue(sentCommands.isEmpty())
        assertEquals(appContext().getString(R.string.chat_pi_continue_unavailable), vm.uiState.value.error)
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

    @Test
    fun `roundtable command rejection error clears after next successful command`() = runTest(dispatcher) {
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        var rejectCommand = true
        val vm = newViewModel(
            sentCommands = sentCommands,
            commandStatus = { if (rejectCommand) HttpStatusCode.UnprocessableEntity else HttpStatusCode.OK },
            commandBody = {
                if (rejectCommand) {
                    """{"accepted":false,"effect":"rejected because injected content would exceed maxTranscriptBytes"}"""
                } else {
                    """{"accepted":true}"""
                }
            },
        )
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val rejected = sendAndAwaitResult { onDone -> vm.injectAsParticipant("too much transcript", onDone) }
        assertEquals(false, rejected)
        awaitError(vm)

        rejectCommand = false
        val accepted = sendAndAwaitResult { onDone -> vm.continueRoundtable(onDone) }
        assertEquals(true, accepted)
        awaitNoError(vm)
    }

    @Test
    fun `roundtable oversized supplement rejection shows recoverable guidance`() = runTest(dispatcher) {
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val vm = newViewModel(
            sentCommands = sentCommands,
            commandStatus = { HttpStatusCode.UnprocessableEntity },
            commandBody = { """{"accepted":false,"effect":"rejected because injected content would exceed maxTranscriptBytes"}""" },
        )
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()

        val rejected = sendAndAwaitResult { onDone -> vm.injectAsParticipant("too much transcript", onDone) }

        assertEquals(false, rejected)
        awaitError(vm)
        assertEquals(appContext().getString(R.string.chat_pi_append_too_large), vm.uiState.value.error)
    }

    @Test
    fun `roundtable load hydrates transcript and subscribes current room with last event id`() = runTest(dispatcher) {
        val sentCommands = Collections.synchronizedList(mutableListOf<PiCommandRequest>())
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val eventReducer = EventReducer()
        val transcriptEvents = fixtureEvents("happy-one-round.json").take(5)
        val vm = newViewModel(
            sentCommands = sentCommands,
            eventReducer = eventReducer,
            requests = requests,
            transcriptEvents = transcriptEvents,
            liveEvents = fixtureEvents("happy-one-round.json").drop(5).take(1),
        )
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        awaitRequest(requests, "/roundtables/$ROUND_ID/transcript", vm)
        awaitReducerPiAssistantMessage(eventReducer, "persona-ada")

        val adaMessage = awaitPiAssistantMessage(vm, "persona-ada")
        val adaText = adaMessage.parts.filterIsInstance<dev.minios.ocremote.domain.model.Part.Text>().single().text

        assertEquals("Truth seeking should lead because coverage without pressure-testing becomes trivia.", adaText)
        awaitRequest(requests, "/roundtables/$ROUND_ID/events", vm)
        val eventRequest = requests.first { request -> request.url.encodedPath == "/roundtables/$ROUND_ID/events" }
        assertEquals("5", eventRequest.headers["Last-Event-ID"])
    }

    private suspend fun sendAndAwait(send: ((Boolean) -> Unit) -> Unit) {
        assertEquals(true, sendAndAwaitResult(send))
    }

    private suspend fun sendAndAwaitResult(send: ((Boolean) -> Unit) -> Unit): Boolean {
        val result = CompletableDeferred<Boolean>()
        send { ok -> result.complete(ok) }
        scheduler.advanceUntilIdle()
        return result.await()
    }

    private suspend fun awaitSentCommand(sentCommands: List<PiCommandRequest>) {
        repeat(20) {
            scheduler.advanceUntilIdle()
            if (sentCommands.isNotEmpty()) return
            yield()
        }
        assertTrue("Expected a Pi command to be sent", sentCommands.isNotEmpty())
    }

    private suspend fun awaitRequest(requests: List<HttpRequestData>, path: String, vm: ChatViewModel? = null) {
        repeat(50) {
            scheduler.advanceUntilIdle()
            if (requests.any { request -> request.url.encodedPath == path }) return
            withContext(Dispatchers.Default) { delay(5) }
            yield()
        }
        assertTrue("Expected request $path, got ${requests.map { it.url.encodedPath }}; error=${vm?.uiState?.value?.error}", false)
    }

    private suspend fun awaitUserMessage(vm: ChatViewModel) {
        repeat(20) {
            scheduler.advanceUntilIdle()
            if (vm.uiState.value.messages.any { it.isUser }) return
            yield()
        }
        assertTrue("Expected a local user message", vm.uiState.value.messages.any { it.isUser })
    }

    private suspend fun awaitReducerPiAssistantMessage(eventReducer: EventReducer, senderId: String) {
        repeat(50) {
            scheduler.advanceUntilIdle()
            val hasMessage = eventReducer.roundtableMessages.value[ROUND_ID].orEmpty().any { message ->
                (message as? dev.minios.ocremote.domain.model.Message.Assistant)?.senderId == senderId
            }
            if (hasMessage) return
            withContext(Dispatchers.Default) { delay(5) }
            yield()
        }
        val senders = eventReducer.roundtableMessages.value[ROUND_ID].orEmpty().mapNotNull { message ->
            (message as? dev.minios.ocremote.domain.model.Message.Assistant)?.senderId
        }
        assertTrue("Expected reducer to hydrate $senderId from transcript, got $senders", false)
    }

    private suspend fun awaitPiAssistantMessage(vm: ChatViewModel, senderId: String): ChatMessage {
        repeat(20) {
            scheduler.advanceUntilIdle()
            vm.uiState.value.messages.firstOrNull { message ->
                (message.message as? dev.minios.ocremote.domain.model.Message.Assistant)?.senderId == senderId
            }?.let { return it }
            yield()
        }
        val senders = vm.uiState.value.messages.mapNotNull { message ->
            (message.message as? dev.minios.ocremote.domain.model.Message.Assistant)?.senderId
        }
        assertTrue("Expected Pi assistant message from $senderId, got $senders; error=${vm.uiState.value.error}", false)
        error("Unreachable")
    }

    private suspend fun awaitError(vm: ChatViewModel) {
        repeat(20) {
            scheduler.advanceUntilIdle()
            if (vm.uiState.value.error != null) return
            yield()
        }
        assertNotNull(vm.uiState.value.error)
    }

    private suspend fun awaitNoError(vm: ChatViewModel) {
        repeat(20) {
            scheduler.advanceUntilIdle()
            if (vm.uiState.value.error == null) return
            yield()
        }
        assertEquals(null, vm.uiState.value.error)
    }

    private fun newViewModel(
        sentCommands: MutableList<PiCommandRequest>,
        eventReducer: EventReducer = EventReducer(),
        sentBodies: MutableList<String>? = null,
        requests: MutableList<HttpRequestData>? = null,
        commandStatus: (PiCommandRequest) -> HttpStatusCode = { HttpStatusCode.OK },
        commandBody: (PiCommandRequest) -> String = { """{"accepted":true}""" },
        roundtableStatus: String = "awaiting_command",
        transcriptEvents: List<RoundtableSseEvent> = emptyList(),
        liveEvents: List<RoundtableSseEvent> = emptyList(),
    ): ChatViewModel {
        return ChatViewModel(
            appContext = appContext(),
            savedStateHandle = savedStateHandle(),
            eventReducer = eventReducer,
            api = openCodeApi(),
            piApi = piApi(sentCommands, sentBodies, requests, commandStatus, commandBody, roundtableStatus, transcriptEvents, liveEvents),
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

    private fun piApi(
        sentCommands: MutableList<PiCommandRequest>,
        sentBodies: MutableList<String>? = null,
        requests: MutableList<HttpRequestData>? = null,
        commandStatus: (PiCommandRequest) -> HttpStatusCode = { HttpStatusCode.OK },
        commandBody: (PiCommandRequest) -> String = { """{"accepted":true}""" },
        roundtableStatus: String = "awaiting_command",
        transcriptEvents: List<RoundtableSseEvent> = emptyList(),
        liveEvents: List<RoundtableSseEvent> = emptyList(),
    ): PiApi {
        var servedLiveEvents = false
        val engine = MockEngine { request ->
            requests?.add(request)
            when {
                request.url.encodedPath == "/roundtables" && request.method == HttpMethod.Get -> respondJson(roundtablesJson(roundtableStatus))
                request.url.encodedPath == "/personas" && request.method == HttpMethod.Get -> respondJson(personasJson())
                request.url.encodedPath == "/roundtables/$ROUND_ID/transcript" && request.method == HttpMethod.Get -> respondJson(transcriptJson(transcriptEvents))
                request.url.encodedPath == "/roundtables/$ROUND_ID/events" && request.method == HttpMethod.Get -> {
                    if (servedLiveEvents) {
                        respondSse("")
                    } else {
                        servedLiveEvents = true
                        respondSse(sseFrame(liveEvents))
                    }
                }
                request.url.encodedPath == "/roundtables/$ROUND_ID/command" && request.method == HttpMethod.Post -> {
                    val body = request.body.bodyAsText()
                    sentBodies?.add(body)
                    val command = json.decodeFromString(PiCommandRequest.serializer(), body)
                    sentCommands += command
                    respondJson(commandBody(command), commandStatus(command))
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout)
        }
        return PiApi(client, json)
    }

    private fun openCodeApi(): OpenCodeApi {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return OpenCodeApi(client, json)
    }

    private fun roundtablesJson(status: String): String = """
        [{
          "id":"$ROUND_ID",
          "topic":"Steering",
          "status":"$status",
          "roster":[{"id":"ada","name":"Ada","role":"analyst","colorSeed":"ada"}]
        }]
    """.trimIndent()

    private fun transcriptJson(events: List<RoundtableSseEvent>): String = """{"protocolVersion":1,"roundId":"$ROUND_ID","events":${json.encodeToString(ListSerializer(RoundtableSseEvent.serializer()), events)},"commands":[],"assembled":{}}"""

    private fun sseFrame(events: List<RoundtableSseEvent>): String = events.joinToString(separator = "") { event ->
        val data = json.encodeToString(RoundtableSseEvent.serializer(), event)
        "id: ${event.eventId}\ndata: $data\n\n"
    }

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

    private fun appContext(): android.content.Context = ApplicationProvider.getApplicationContext()

    private fun MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = ByteReadChannel(content),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.respondSse(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
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
