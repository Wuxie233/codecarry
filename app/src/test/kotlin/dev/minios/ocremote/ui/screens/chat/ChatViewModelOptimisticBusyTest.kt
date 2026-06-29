package dev.minios.ocremote.ui.screens.chat

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.preferences.SessionListPreferencesRepository
import dev.minios.ocremote.data.repository.DraftRepository
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.SessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
        val vm = newViewModel(reducer)
        vm.uiState.first { !it.isLoading }

        vm.sendMessage(listOf(PromptPart(type = "text", text = "hello")), emptyList())
        advanceUntilIdle()

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["ses_real"])
    }

    private fun newViewModel(reducer: EventReducer): ChatViewModel = ChatViewModel(
        appContext = object : ContextWrapper(null) {},
        savedStateHandle = SavedStateHandle(
            mapOf(
                "serverUrl" to "http://x",
                "username" to "",
                "password" to "",
                "serverName" to "",
                "serverId" to "srv",
                "sessionId" to "ses_real",
            ),
        ),
        eventReducer = reducer,
        api = mockApi(),
        piApi = mockPiApi(),
        json = json,
        draftRepository = draftRepository(),
        sessionListPreferencesRepository = sessionListPreferencesRepository(),
        settingsRepository = settingsRepository(),
    )

    private fun mockApi(): OpenCodeApi {
        val engine = MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/prompt_async") -> "{}"
                request.url.encodedPath.contains("/message") -> "[]"
                request.url.encodedPath == "/question" -> "[]"
                request.url.encodedPath == "/permission" -> "[]"
                request.url.encodedPath == "/session/status" -> "{}"
                request.url.encodedPath == "/config/providers" -> "{}"
                request.url.encodedPath == "/agent" -> "[]"
                request.url.encodedPath == "/command" -> "[]"
                request.url.encodedPath.startsWith("/session/") -> """{"id":"ses_real","time":{}}"""
                else -> "{}"
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return OpenCodeApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)
    }

    private fun mockPiApi(): PiApi {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("{}"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return PiApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)
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
        return SettingsRepository(dataStore, object : ContextWrapper(null) {})
    }
}
