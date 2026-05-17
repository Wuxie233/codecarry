package dev.minios.ocremote.ui.screens.chat

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.preferences.SessionListPreferencesRepository
import dev.minios.ocremote.data.repository.DraftRepository
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.SettingsRepository
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRouteGuardTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val json = Json {
        prettyPrint = true
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
    fun `empty sessionId in SavedStateHandle does not crash and surfaces error state`() = runTest(dispatcher) {
        val handle = savedStateHandle(sessionId = "")
        var requestCount = 0

        val vm = newViewModel(
            savedStateHandle = handle,
            eventReducer = EventReducer(),
            api = mockApi { requestCount++ },
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
        )

        val state = vm.uiState.first { !it.isLoading }
        assertFalse("isLoading should not stay true after guard", state.isLoading)
        assertEquals("Invalid session", state.error)
        assertEquals("blank sessionId should not trigger REST calls", 0, requestCount)
    }

    @Test
    fun `non-blank sessionId proceeds without immediate error`() = runTest(dispatcher) {
        val vm = newViewModel(
            savedStateHandle = savedStateHandle(sessionId = "ses_real"),
            eventReducer = EventReducer(),
            api = mockApi(),
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
        )

        assertEquals(null, vm.uiState.value.error)
    }

    private fun savedStateHandle(sessionId: String) = SavedStateHandle(
        mapOf(
            "serverUrl" to "http://x",
            "username" to "",
            "password" to "",
            "serverName" to "",
            "serverId" to "srv",
            "sessionId" to sessionId,
        )
    )

    private fun mockApi(onRequest: () -> Unit = {}): OpenCodeApi {
        val engine = MockEngine { request ->
            onRequest()
            val body = when {
                request.url.encodedPath.contains("/message") -> "[]"
                request.url.encodedPath == "/question" -> "[]"
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
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
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

    private fun newViewModel(
        savedStateHandle: SavedStateHandle,
        eventReducer: EventReducer,
        api: OpenCodeApi,
        draftRepository: DraftRepository,
        sessionListPreferencesRepository: SessionListPreferencesRepository,
        settingsRepository: SettingsRepository,
    ): ChatViewModel {
        return ChatViewModel(
            savedStateHandle = savedStateHandle,
            eventReducer = eventReducer,
            api = api,
            draftRepository = draftRepository,
            sessionListPreferencesRepository = sessionListPreferencesRepository,
            settingsRepository = settingsRepository,
        )
    }
}
