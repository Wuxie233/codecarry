package dev.wuxie233.codecarry.ui.screens.chat

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.api.PiApi
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.DraftRepository
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.SessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelRetryNowTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)
    private val viewModels = mutableListOf<ChatViewModel>()
    private val collectJobs = mutableListOf<Job>()
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
        collectJobs.forEach(Job::cancel)
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `retry now stays latched after success until authoritative status exits retry`() = runTest(dispatcher) {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger()
        val retryRequests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val retryStatus = SessionStatus.Retry(attempt = 2, message = "rate limited", next = 42L)
        val reducer = EventReducer().also { it.updateSessionStatus(SESSION_ID, retryStatus) }
        val vm = newViewModel(
            eventReducer = reducer,
            api = openCodeApi(retryStatus) { request ->
                retryRequests += request
                requestCount.incrementAndGet()
                requestStarted.complete(Unit)
                releaseResponse.await()
                respondJson("true")
            },
        )
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.uiState.first { !it.isLoading && it.sessionStatus == retryStatus }

        vm.retrySessionNow()
        vm.retrySessionNow()
        scheduler.runCurrent()
        requestStarted.await()
        scheduler.runCurrent()

        assertEquals(1, requestCount.get())
        assertTrue(vm.uiState.value.isRetryingNow)
        assertEquals(retryStatus, reducer.sessionStatuses.value[SESSION_ID])
        assertEquals("%2Fworkspace%2Fproject%20name", retryRequests.single().headers["x-opencode-directory"])

        releaseResponse.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isRetryingNow)
        assertEquals(retryStatus, reducer.sessionStatuses.value[SESSION_ID])
        assertEquals(null, vm.uiState.value.error)

        vm.retrySessionNow()
        scheduler.runCurrent()

        assertEquals(1, requestCount.get())

        reducer.updateSessionStatus(SESSION_ID, SessionStatus.Idle)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isRetryingNow)
    }

    @Test
    fun `retry now false response emits one-shot failure without setting global error`() = runTest(dispatcher) {
        val retryStatus = SessionStatus.Retry(attempt = 1, message = "waiting", next = 42L)
        val reducer = EventReducer().also { it.updateSessionStatus(SESSION_ID, retryStatus) }
        val vm = newViewModel(
            eventReducer = reducer,
            api = openCodeApi(retryStatus) { respondJson("false") },
        )
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.uiState.first { !it.isLoading && it.sessionStatus == retryStatus }
        val failureEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            vm.retryNowFailureEvent.first()
        }

        vm.retrySessionNow()
        advanceUntilIdle()

        assertEquals(Unit, failureEvent.await())
        vm.uiState.first { !it.isRetryingNow }
        assertFalse(vm.uiState.value.isRetryingNow)
        assertEquals(null, vm.uiState.value.error)
        assertEquals(retryStatus, reducer.sessionStatuses.value[SESSION_ID])
    }

    @Test
    fun `retry now exception emits one-shot failure without setting global error`() = runTest(dispatcher) {
        val retryStatus = SessionStatus.Retry(attempt = 1, message = "waiting", next = 42L)
        val reducer = EventReducer().also { it.updateSessionStatus(SESSION_ID, retryStatus) }
        val vm = newViewModel(
            eventReducer = reducer,
            api = openCodeApi(retryStatus) { respondJson("{}") },
        )
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.uiState.first { !it.isLoading && it.sessionStatus == retryStatus }
        val failureEvent = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            vm.retryNowFailureEvent.first()
        }

        vm.retrySessionNow()
        advanceUntilIdle()

        assertEquals(Unit, failureEvent.await())
        vm.uiState.first { !it.isRetryingNow }
        assertFalse(vm.uiState.value.isRetryingNow)
        assertEquals(null, vm.uiState.value.error)
        assertEquals(retryStatus, reducer.sessionStatuses.value[SESSION_ID])
    }

    private fun newViewModel(eventReducer: EventReducer, api: OpenCodeApi): ChatViewModel = ChatViewModel(
        appContext = appContext(),
        savedStateHandle = SavedStateHandle(
            mapOf(
                "serverUrl" to "http://example.test:4096",
                "username" to "",
                "password" to "",
                "serverName" to "Test",
                "serverId" to "srv",
                "sessionId" to SESSION_ID,
                "directory" to DIRECTORY,
            ),
        ),
        eventReducer = eventReducer,
        api = api,
        piApi = piApi(),
        json = json,
        draftRepository = draftRepository(),
        sessionListPreferencesRepository = sessionListPreferencesRepository(),
        settingsRepository = settingsRepository(),
    ).also(viewModels::add)

    private fun openCodeApi(
        retryStatus: SessionStatus.Retry,
        retryHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): OpenCodeApi {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/session/$SESSION_ID/retry" -> retryHandler(request)
                request.method == HttpMethod.Get && request.url.encodedPath == "/session/$SESSION_ID" -> respondJson(
                    """{"id":"$SESSION_ID","directory":"$DIRECTORY","time":{}}""",
                )
                request.method == HttpMethod.Get && request.url.encodedPath == "/session/$SESSION_ID/message" -> respondJson("[]")
                request.method == HttpMethod.Get && request.url.encodedPath == "/session/status" -> respondJson(
                    """{"$SESSION_ID":{"type":"retry","attempt":${retryStatus.attempt},"message":"${retryStatus.message}","next":${retryStatus.next}}}""",
                )
                request.url.encodedPath == "/question" || request.url.encodedPath == "/permission" -> respondJson("[]")
                request.url.encodedPath == "/config/providers" -> respondJson("{}")
                request.url.encodedPath == "/agent" || request.url.encodedPath == "/command" -> respondJson("[]")
                else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
            }
        }
        return OpenCodeApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)
    }

    private fun piApi(): PiApi = PiApi(
        HttpClient(MockEngine { respondJson("{}") }) { install(ContentNegotiation) { json(json) } },
        json,
    )

    private fun draftRepository(): DraftRepository {
        val filesDir = tmpFolder.newFolder("drafts-${System.nanoTime()}")
        return DraftRepository(object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
        })
    }

    private fun sessionListPreferencesRepository(): SessionListPreferencesRepository = SessionListPreferencesRepository(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("sessions-${System.nanoTime()}.preferences_pb") },
        ),
    )

    private fun settingsRepository(): SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("settings-${System.nanoTime()}.preferences_pb") },
        ),
        appContext(),
    )

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        private const val SESSION_ID = "ses_retry"
        private const val DIRECTORY = "/workspace/project name"
    }
}
