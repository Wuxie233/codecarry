package dev.wuxie233.codecarry.ui.screens.chat

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.api.PromptPart
import dev.wuxie233.codecarry.data.dsh.unusedDshApi
import dev.wuxie233.codecarry.data.dsh.unusedDshConnectionManager
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.DraftRepository
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.SessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.http.HttpMethod
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelOptimisticBusyTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)

    private val json = Json {
        isLenient = true
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
        Dispatchers.resetMain()
    }

    @Test
    fun `sending a prompt optimistically marks the session busy so the working state shows without waiting for an event`() = runTest(dispatcher) {
        val reducer = EventReducer()
        val vm = newViewModel(reducer, directory = "/workspace/project")

        vm.sendMessage(listOf(PromptPart(type = "text", text = "hello")), emptyList())
        scheduler.runCurrent()

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["ses_real"])
    }

    @Test
    fun `route directory sends before session lookup completes`() = runTest(dispatcher) {
        val releaseSession = CompletableDeferred<Unit>()
        val promptRequests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val vm = newViewModel(
            reducer = EventReducer(),
            directory = "/workspace/project",
            api = mockApi { request ->
                when {
                    request.url.encodedPath.endsWith("/prompt_async") -> {
                        promptRequests += request
                        respondJson("{}")
                    }
                    request.url.encodedPath.startsWith("/session/") -> {
                        releaseSession.await()
                        respondJson("""{"id":"ses_real","directory":"/workspace/project","time":{}}""")
                    }
                    else -> defaultResponse(request)
                }
            },
        )

        vm.sendMessage(listOf(PromptPart(type = "text", text = "early")), emptyList())
        scheduler.runCurrent()

        assertEquals(1, promptRequests.size)
        assertEquals("%2Fworkspace%2Fproject", promptRequests.single().headers["x-opencode-directory"])
        assertEquals(0, vm.uiState.value.pendingSendCount)

        releaseSession.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `early sends wait for directory then drain once in fifo order`() = runTest(dispatcher) {
        val releaseSession = CompletableDeferred<Unit>()
        val promptBodies = Collections.synchronizedList(mutableListOf<String>())
        val accepted = mutableListOf<Boolean>()
        val vm = newViewModel(
            reducer = EventReducer(),
            api = mockApi { request ->
                when {
                    request.url.encodedPath.endsWith("/prompt_async") -> {
                        promptBodies += (request.body as OutgoingContent.ByteArrayContent)
                            .bytes()
                            .toString(Charsets.UTF_8)
                        respondJson("{}")
                    }
                    request.url.encodedPath.startsWith("/session/") -> {
                        releaseSession.await()
                        respondJson("""{"id":"ses_real","directory":"/workspace/project","time":{}}""")
                    }
                    else -> defaultResponse(request)
                }
            },
        )
        backgroundScope.launch { vm.uiState.collect {} }

        vm.sendMessage(listOf(PromptPart(type = "text", text = "first")), emptyList()) { accepted += it }
        vm.sendMessage(listOf(PromptPart(type = "text", text = "second")), emptyList()) { accepted += it }
        scheduler.runCurrent()

        assertEquals(listOf(true, true), accepted)
        vm.uiState.first { it.pendingSendCount == 2 }
        assertEquals(emptyList<String>(), promptBodies)

        releaseSession.complete(Unit)
        vm.uiState.first { it.pendingSendCount == 0 }

        assertEquals(2, promptBodies.size)
        assertEquals(true, promptBodies[0].contains("first"))
        assertEquals(true, promptBodies[1].contains("second"))
        assertEquals(0, vm.uiState.value.pendingSendCount)
        assertEquals(listOf(true, true), accepted)
    }

    @Test
    fun `failed head stays queued while later sends append and retry in fifo order`() = runTest(dispatcher) {
        val attempt = AtomicInteger()
        val promptBodies = Collections.synchronizedList(mutableListOf<String>())
        val accepted = mutableListOf<Boolean>()
        val vm = newViewModel(
            reducer = EventReducer(),
            directory = "/workspace/project",
            api = mockApi { request ->
                if (request.url.encodedPath.endsWith("/prompt_async")) {
                    promptBodies += (request.body as OutgoingContent.ByteArrayContent)
                        .bytes()
                        .toString(Charsets.UTF_8)
                    if (attempt.getAndIncrement() == 0) {
                        respondJson("{}", HttpStatusCode.InternalServerError)
                    } else {
                        respondJson("{}")
                    }
                } else {
                    defaultResponse(request)
                }
            },
        )
        backgroundScope.launch { vm.uiState.collect {} }

        vm.sendMessage(listOf(PromptPart(type = "text", text = "first")), emptyList()) { accepted += it }
        vm.uiState.first { it.pendingSendError != null }
        vm.sendMessage(listOf(PromptPart(type = "text", text = "second")), emptyList()) { accepted += it }
        vm.uiState.first { it.pendingSendCount == 2 }

        assertEquals(listOf(true, true), accepted)
        assertEquals(1, promptBodies.size)
        assertEquals(true, promptBodies.single().contains("first"))

        vm.retryPendingSend()
        vm.uiState.first { it.pendingSendCount == 0 }

        assertEquals(3, promptBodies.size)
        assertEquals(true, promptBodies[1].contains("first"))
        assertEquals(true, promptBodies[2].contains("second"))
        assertEquals(listOf(true, true), accepted)
    }

    private fun newViewModel(
        reducer: EventReducer,
        directory: String? = null,
        api: OpenCodeApi = mockApi(),
    ): ChatViewModel = ChatViewModel(
        appContext = object : ContextWrapper(null) {},
        savedStateHandle = SavedStateHandle(
            mapOf(
                "serverUrl" to "http://x",
                "username" to "",
                "password" to "",
                "serverName" to "",
                "serverId" to "srv",
                "sessionId" to "ses_real",
                "directory" to directory,
            ),
        ),
        eventReducer = reducer,
        api = api,
        json = json,
        draftRepository = draftRepository(),
        sessionListPreferencesRepository = sessionListPreferencesRepository(),
        settingsRepository = settingsRepository(),
        dshApi = unusedDshApi(json),
        dshConnectionManager = unusedDshConnectionManager(testScope.backgroundScope, json),
    )

    private fun mockApi(): OpenCodeApi {
        return mockApi { request -> defaultResponse(request) }
    }

    private fun mockApi(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): OpenCodeApi {
        val engine = MockEngine(handler)
        return OpenCodeApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)
    }

    private fun MockRequestHandleScope.defaultResponse(request: HttpRequestData): HttpResponseData {
        val body = when {
            request.url.encodedPath.endsWith("/prompt_async") -> "{}"
            request.url.encodedPath.contains("/message") -> "[]"
            request.url.encodedPath == "/question" -> "[]"
            request.url.encodedPath == "/permission" -> "[]"
            request.url.encodedPath == "/session/status" -> "{}"
            request.url.encodedPath == "/config/providers" -> "{}"
            request.url.encodedPath == "/agent" -> "[]"
            request.url.encodedPath == "/command" -> "[]"
            request.url.encodedPath == "/session" && request.method == HttpMethod.Get -> "[]"
            request.url.encodedPath.startsWith("/session/") -> """{"id":"ses_real","time":{}}"""
            else -> "{}"
        }
        return respondJson(body)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )


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
        return SettingsRepository(dataStore, object : ContextWrapper(null) {})
    }
}
