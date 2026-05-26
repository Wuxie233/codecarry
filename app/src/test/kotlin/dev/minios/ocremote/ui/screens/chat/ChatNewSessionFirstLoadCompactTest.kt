package dev.minios.ocremote.ui.screens.chat

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.preferences.SessionListPreferencesRepository
import dev.minios.ocremote.data.repository.DraftRepository
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Session
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class ChatNewSessionFirstLoadCompactTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
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
    fun `ChatViewModel survives full compact first-load response set`() = runTest(dispatcher) {
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val vm = newViewModel(
            savedStateHandle = savedStateHandle(sessionId = "ses_new", serverId = "srv-compact"),
            eventReducer = EventReducer(),
            api = compactFirstLoadApi(requestedPaths),
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
        )

        val finalState = vm.uiState.first { !it.isLoading }

        assertNull(finalState.error)
        assertTrue(finalState.providers.isEmpty())
        assertTrue(finalState.agents.isEmpty())
        assertTrue(finalState.commands.isEmpty())

        assertTrue(requestedPaths.contains("/session/ses_new"))
        assertTrue(requestedPaths.contains("/session/ses_new/message"))
        assertTrue(requestedPaths.contains("/question"))
        assertTrue(requestedPaths.contains("/config/providers"))
        assertTrue(requestedPaths.contains("/agent"))
        assertTrue(requestedPaths.contains("/command"))
    }

    @Test
    fun `ChatViewModel accepts already decoded route directory containing percent and plus`() = runTest(dispatcher) {
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val directory = "/work/100% ready/a+b"
        val vm = newViewModel(
            savedStateHandle = savedStateHandle(sessionId = "ses_new", serverId = "srv-route", directory = directory),
            eventReducer = EventReducer(),
            api = compactFirstLoadApi(requestedPaths, sessionDirectory = directory),
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
        )

        val finalState = vm.uiState.first { !it.isLoading }

        assertNull(finalState.error)
    }

    @Test
    fun `blank sessionId enters error state and does not call any API`() = runTest(dispatcher) {
        var requestCount = 0
        val vm = newViewModel(
            savedStateHandle = savedStateHandle(sessionId = "", serverId = "srv-blank"),
            eventReducer = EventReducer(),
            api = compactFirstLoadApi(onRequest = { requestCount++ }),
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
        )

        val state = vm.uiState.first { !it.isLoading }

        assertFalse(state.isLoading)
        assertEquals("Invalid session", state.error)
        assertEquals("blank sessionId should not trigger REST calls", 0, requestCount)
    }

    @Test
    fun `createNewSession inherits blank compact response directory from current session directory`() = runTest(dispatcher) {
        val eventReducer = EventReducer()
        val createSessionDirectoryHeaders = Collections.synchronizedList(mutableListOf<String?>())
        val vm = newViewModel(
            savedStateHandle = savedStateHandle(sessionId = "ses_new", serverId = "srv-compact", directory = "/work/project"),
            eventReducer = eventReducer,
            api = createSessionApiWithBlankDirectoryResponse(createSessionDirectoryHeaders),
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
        )
        val emittedSession = CompletableDeferred<Session?>()
        vm.createNewSession { emittedSession.complete(it) }
        val session = emittedSession.await()

        assertTrue(session != null)
        assertEquals("/work/project", session?.directory)
        assertEquals("/work/project", eventReducer.sessions.value.first { it.id == "ses_created" }.directory)
        val createHeader = createSessionDirectoryHeaders.single()
        assertTrue(createHeader?.contains("work") == true)
        assertTrue(createHeader?.contains("project") == true)
    }

    private fun savedStateHandle(sessionId: String, serverId: String) = SavedStateHandle(
        mapOf(
            "serverUrl" to "http%3A%2F%2Fexample.test%3A4096",
            "username" to "",
            "password" to "",
            "serverName" to "Local",
            "serverId" to serverId,
            "sessionId" to sessionId,
        )
    )

    private fun savedStateHandle(sessionId: String, serverId: String, directory: String) = SavedStateHandle(
        mapOf(
            "serverUrl" to "http%3A%2F%2Fexample.test%3A4096",
            "username" to "",
            "password" to "",
            "serverName" to "Local",
            "serverId" to serverId,
            "sessionId" to sessionId,
            "directory" to directory,
        )
    )

    private fun compactFirstLoadApi(
        requestedPaths: MutableList<String> = mutableListOf(),
        onRequest: () -> Unit = {},
        sessionDirectory: String = "",
    ): OpenCodeApi {
        val engine = MockEngine { request ->
            onRequest()
            requestedPaths.add(request.url.encodedPath)
            val body = when (request.url.encodedPath) {
                "/session/ses_new" -> """{"id":"ses_new","directory":"$sessionDirectory","time":{}}"""
                "/session/ses_new/message" -> "[]"
                "/question" -> "[]"
                "/config/providers" -> "{}"
                "/agent" -> "[]"
                "/command" -> "[]"
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

    private fun createSessionApiWithBlankDirectoryResponse(createSessionDirectoryHeaders: MutableList<String?>): OpenCodeApi {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/session" && request.method.value == "POST") {
                createSessionDirectoryHeaders.add(request.headers["x-opencode-directory"])
            }
            val body = when (request.url.encodedPath) {
                "/session" -> """{"id":"ses_created","time":{}}"""
                "/session/ses_new" -> """{"id":"ses_new","directory":"/work/project","time":{}}"""
                "/session/ses_new/message" -> "[]"
                "/question" -> "[]"
                "/config/providers" -> "{}"
                "/agent" -> "[]"
                "/command" -> "[]"
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
