package dev.wuxie233.codecarry.ui.screens.chat

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.dsh.unusedDshApi
import dev.wuxie233.codecarry.data.dsh.unusedDshConnectionManager
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.DraftRepository
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.MessageWithParts
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.TimeInfo
import dev.wuxie233.codecarry.service.ForegroundResumeDispatcher
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.withTimeoutOrNull
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Focused tests for the chat read model (issues #32/#33): the persisted read
 * anchor advances only when new replies are actually presented — chat screen
 * visible (screen lifecycle, not ViewModel lifetime) AND following the tail AND
 * some history rendered.
 *
 * Assertions await (or disprove within a virtual-time grace window) repository
 * state instead of assuming `advanceUntilIdle` has flushed every DataStore hop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelReadModelTest {

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
    private lateinit var repo: SessionListPreferencesRepository

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

    private fun seedAssistantReply(reducer: EventReducer, messageId: String, text: String, created: Long) {
        val priorInfos = reducer.serverMessages.value[SERVER_ID].orEmpty()[SESSION_ID].orEmpty()
        val partsByMessage = reducer.serverParts.value[SERVER_ID].orEmpty()
        val prior = priorInfos.map { info ->
            MessageWithParts(info, partsByMessage[info.id].orEmpty())
        }
        val assistant = Message.Assistant(id = messageId, sessionId = SESSION_ID, time = TimeInfo(created = created))
        val part = Part.Text(id = "part_$messageId", sessionId = SESSION_ID, messageId = messageId, text = text)
        reducer.setMessages(
            serverId = SERVER_ID,
            sessionId = SESSION_ID,
            messages = prior + MessageWithParts(assistant, listOf(part)),
        )
    }

    @Test
    fun `init does not mark the conversation read before replies are presented`() = runTest(dispatcher) {
        val reducer = EventReducer()
        seedAssistantReply(reducer, "msg_seen", "earlier reply", created = 1L)
        createRepo()
        repo.markConversationUnread(SERVER_ID, SESSION_ID)
        repo.setReadAnchor(SERVER_ID, SESSION_ID, "msg_seen")

        val vm = newViewModel(reducer)
        collectUiState(vm)
        advanceUntilIdle()

        // The chat exists but nobody marked it visible: unread survives, anchor untouched.
        assertAnchorStays("msg_seen")
        assertUnreadKeeps(SESSION_ID)
    }

    @Test
    fun `visible chat following the tail advances the anchor and clears unread`() = runTest(dispatcher) {
        val reducer = EventReducer()
        seedAssistantReply(reducer, "msg_1", "first reply", created = 1L)
        seedAssistantReply(reducer, "msg_2", "second reply", created = 2L)
        createRepo()
        repo.markConversationUnread(SERVER_ID, SESSION_ID)

        val vm = newViewModel(reducer)
        collectUiState(vm)
        vm.onChatScreenStarted()
        advanceUntilIdle()

        assertAnchorBecomes("msg_2")
        assertUnreadClears(SESSION_ID)
        // The server-scoped active/visible chat is registered for suppression.
        assertTrue(reducer.isSessionVisible(SERVER_ID, SESSION_ID))
    }

    @Test
    fun `backgrounded chat with alive ViewModel does not advance the anchor`() = runTest(dispatcher) {
        val reducer = EventReducer()
        seedAssistantReply(reducer, "msg_1", "first reply", created = 1L)
        createRepo()
        repo.markConversationUnread(SERVER_ID, SESSION_ID)

        val vm = newViewModel(reducer)
        collectUiState(vm)
        // First visit: the tail is presented while visible.
        vm.onChatScreenStarted()
        assertAnchorBecomes("msg_1")

        // The app goes to the background (ON_STOP) while the ViewModel survives.
        // A new reply completes; the anchor must not move to it.
        vm.onChatScreenStopped()
        seedAssistantReply(reducer, "msg_2", "reply completed in background", created = 3L)
        advanceUntilIdle()

        assertAnchorStays("msg_1")
        assertFalse(reducer.isSessionVisible(SERVER_ID, SESSION_ID))
    }

    @Test
    fun `chat stopped before any presentation keeps the unread mark untouched`() = runTest(dispatcher) {
        val reducer = EventReducer()
        seedAssistantReply(reducer, "msg_1", "first reply", created = 1L)
        createRepo()
        repo.markConversationUnread(SERVER_ID, SESSION_ID)

        val vm = newViewModel(reducer)
        collectUiState(vm)
        vm.onChatScreenStarted()
        vm.onChatScreenStopped()
        advanceUntilIdle()

        assertAnchorStays(null)
        assertUnreadKeeps(SESSION_ID)
    }

    @Test
    fun `browsing old history keeps not-yet-seen newer replies unread`() = runTest(dispatcher) {
        val reducer = EventReducer()
        seedAssistantReply(reducer, "msg_1", "first reply", created = 1L)
        createRepo()
        repo.markConversationUnread(SERVER_ID, SESSION_ID)

        val vm = newViewModel(reducer)
        collectUiState(vm)
        vm.onChatScreenStarted()
        vm.onFollowTailChanged(false) // user scrolled away from the tail
        advanceUntilIdle()

        assertAnchorStays(null)
        assertUnreadKeeps(SESSION_ID)
    }

    @Test
    fun `returning to the tail is the manual mark-read gesture`() = runTest(dispatcher) {
        val reducer = EventReducer()
        seedAssistantReply(reducer, "msg_1", "first reply", created = 1L)
        createRepo()
        repo.markConversationUnread(SERVER_ID, SESSION_ID)

        val vm = newViewModel(reducer)
        collectUiState(vm)
        vm.onChatScreenStarted()
        vm.onFollowTailChanged(false)
        advanceUntilIdle()
        assertAnchorStays(null)

        // Jump-to-latest returns the timeline to the tail: replies are presented now.
        vm.onFollowTailChanged(true)

        assertAnchorBecomes("msg_1")
        assertUnreadClears(SESSION_ID)
    }

    @Test
    fun `chat whose content never rendered cannot advance the anchor`() = runTest(dispatcher) {
        // Empty reducer: the history load also returns no messages, so nothing
        // is ever presented even though the screen is visible at the tail.
        val reducer = EventReducer()
        createRepo()
        repo.markConversationUnread(SERVER_ID, SESSION_ID)

        val vm = newViewModel(reducer)
        collectUiState(vm)
        vm.onChatScreenStarted()
        advanceUntilIdle()

        assertAnchorStays(null)
        // The unread mark is untouched: presentation never happened.
        assertUnreadKeeps(SESSION_ID)
    }

    @Test
    fun `screen stop clears the visible session and restart re-registers it`() = runTest(dispatcher) {
        val reducer = EventReducer()
        createRepo()

        val vm = newViewModel(reducer)
        collectUiState(vm)
        vm.onChatScreenStarted()
        advanceUntilIdle()
        assertTrue(reducer.isSessionVisible(SERVER_ID, SESSION_ID))

        vm.onChatScreenStopped()
        advanceUntilIdle()
        assertFalse(reducer.isSessionVisible(SERVER_ID, SESSION_ID))

        vm.onChatScreenStarted()
        advanceUntilIdle()
        assertTrue(reducer.isSessionVisible(SERVER_ID, SESSION_ID))
    }

    // ============ Await helpers (deterministic under runTest virtual time) ============

    private suspend fun assertAnchorBecomes(expected: String) {
        assertEquals(expected, repo.readAnchor(SERVER_ID, SESSION_ID).first { it == expected })
    }

    /** Fails when the anchor changes to anything else within the grace window. */
    private suspend fun assertAnchorStays(expected: String?) {
        val changed = withTimeoutOrNull(GRACE_MS) {
            repo.readAnchor(SERVER_ID, SESSION_ID).first { it != expected }
        }
        assertNull("read anchor unexpectedly became $changed", changed)
    }

    private suspend fun assertUnreadClears(conversationId: String) {
        assertTrue(repo.unreadConversationIds(SERVER_ID).first { conversationId !in it }.isEmpty())
    }

    /** Fails when the unread mark disappears within the grace window. */
    private suspend fun assertUnreadKeeps(conversationId: String) {
        val cleared = withTimeoutOrNull(GRACE_MS) {
            repo.unreadConversationIds(SERVER_ID).first { conversationId !in it }
        }
        assertNull("unread mark for $conversationId unexpectedly cleared", cleared)
    }

    private fun collectUiState(vm: ChatViewModel) {
        collectJobs += testScope.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            vm.uiState.collect {}
        }
    }

    private fun createRepo() {
        repo = SessionListPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmpFolder.newFile("sessions-${System.nanoTime()}.preferences_pb") },
            ),
        )
    }

    private fun newViewModel(eventReducer: EventReducer): ChatViewModel = ChatViewModel(
        appContext = appContext(),
        savedStateHandle = SavedStateHandle(
            mapOf(
                "serverUrl" to "http://example.test:4096",
                "username" to "",
                "password" to "",
                "serverName" to "Test",
                "serverId" to SERVER_ID,
                "sessionId" to SESSION_ID,
                "directory" to DIRECTORY,
            ),
        ),
        eventReducer = eventReducer,
        api = openCodeApi(),
        json = json,
        draftRepository = draftRepository(),
        sessionListPreferencesRepository = repo,
        settingsRepository = settingsRepository(),
        dshApi = unusedDshApi(json),
        dshConnectionManager = unusedDshConnectionManager(testScope.backgroundScope, json),
        foregroundResumeDispatcher = ForegroundResumeDispatcher(),
    ).also(viewModels::add)

    private fun openCodeApi(): OpenCodeApi {
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/session/$SESSION_ID" ->
                    respondJson("""{"id":"$SESSION_ID","directory":"$DIRECTORY","time":{}}""")
                request.method == HttpMethod.Get && request.url.encodedPath == "/session/$SESSION_ID/message" ->
                    respondJson("[]")
                request.url.encodedPath == "/session/status" -> respondJson("{}")
                request.url.encodedPath == "/question" || request.url.encodedPath == "/permission" -> respondJson("[]")
                request.url.encodedPath == "/config/providers" -> respondJson("{}")
                request.url.encodedPath == "/agent" || request.url.encodedPath == "/command" -> respondJson("[]")
                else -> error("Unexpected request: ${request.method.value} ${request.url.encodedPath}")
            }
        }
        return OpenCodeApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)
    }

    private fun draftRepository(): DraftRepository {
        val filesDir = tmpFolder.newFolder("drafts-${System.nanoTime()}")
        return DraftRepository(object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
        })
    }

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
        private const val SERVER_ID = "srv"
        private const val SESSION_ID = "ses_read"
        private const val DIRECTORY = "/workspace/project"
        private const val GRACE_MS = 2_000L
    }
}
