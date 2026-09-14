package dev.wuxie233.codecarry.ui.screens.chat

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.dsh.DshApiClient
import dev.wuxie233.codecarry.data.dsh.DshConnection
import dev.wuxie233.codecarry.data.dsh.DshConnectionManager
import dev.wuxie233.codecarry.data.dsh.DshDownlink
import dev.wuxie233.codecarry.data.dsh.DshDownlinkFactory
import dev.wuxie233.codecarry.data.dsh.FakeDownlink
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.DraftRepository
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.ServerType
import dev.wuxie233.codecarry.service.ForegroundResumeDispatcher
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DSH-side verification of the shared read model (issue #31): a DSH chat
 * advances the persisted read anchor only when its replies are actually
 * presented (screen visible + following the tail), through the same generic
 * ChatViewModel/ChatScreen wiring the OpenCode path uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelDshReadAnchorTest {

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

    @Test
    fun `presented DSH reply advances the anchor and clears unread`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += testScope.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.uiState.collect {} }

        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        runCurrent()
        repo.markConversationUnread(SERVER_ID, SESSION_ID)

        // The chat screen becomes visible and the follow snapshot renders a reply.
        vm.onChatScreenStarted()
        runCurrent()
        val followOpen = mux.sent.last { it.contains("\"session/follow\"") }
        val followId = streamIdOf(followOpen)
        mux.incoming.trySend(
            item(
                followId,
                """{"type":"snapshot","header":null,"cursor":21,"records":[""" +
                    userRecord(10L) + "," + assistantRecord("msg_a", 21L, "dsh reply") +
                    """],"hasMore":false}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()
        vm.uiState.first { state -> !state.isLoading && state.messages.isNotEmpty() }

        // Folded DSH message ids are the anchor currency the tracker compares against.
        assertEquals("msg_a-21", awaitAnchor())
        assertTrue(SESSION_ID !in repo.unreadConversationIds(SERVER_ID).first { SESSION_ID !in it })
        // The DSH chat registered itself as the visible session while open.
        assertTrue(vmReducer.isSessionVisible(SERVER_ID, SESSION_ID))
    }

    @Test
    fun `backgrounded DSH chat does not advance the anchor`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += testScope.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.uiState.collect {} }

        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        runCurrent()

        // Wait until the session list lands (real-IO HTTP) and the follow opens.
        harness.manager.reducer(SERVER_ID).state.first { it.sessions[SESSION_ID]?.listed == true }
        runCurrent()
        mux.sent.first { it.contains("\"session/follow\"") }

        // Screen started then stopped (app backgrounded, ViewModel alive).
        vm.onChatScreenStarted()
        vm.onChatScreenStopped()
        runCurrent()
        val followOpen = mux.sent.last { it.contains("\"session/follow\"") }
        val followId = streamIdOf(followOpen)
        mux.incoming.trySend(
            item(
                followId,
                """{"type":"snapshot","header":null,"cursor":21,"records":[""" +
                    assistantRecord("msg_a", 21L, "completed in background") +
                    """],"hasMore":false}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()

        val changed = withTimeoutOrNull(GRACE_MS) {
            repo.readAnchor(SERVER_ID, SESSION_ID).first { it != null }
        }
        assertNull("anchor unexpectedly advanced: $changed", changed)
        assertFalse(vmReducer.isSessionVisible(SERVER_ID, SESSION_ID))
    }

    // ============ Await helpers ============

    private suspend fun awaitAnchor(): String? =
        withTimeoutOrNull(GRACE_MS) { repo.readAnchor(SERVER_ID, SESSION_ID).first { it != null } }

    // ============ Harness (mirrors ChatViewModelDshFollowTest) ============

    private class DshHarness(
        val client: DshApiClient,
        val manager: DshConnectionManager,
        val reducer: EventReducer,
    )

    private fun dshHarness(mux: FakeDownlink, unary: MutableList<String>): DshHarness {
        var nextStream = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/" -> respond(
                    content = "",
                    status = HttpStatusCode.SeeOther,
                    headers = headersOf(
                        HttpHeaders.SetCookie,
                        "dsh-auth-zz=v1.body; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict",
                    ),
                )
                "/api/session/list" -> {
                    unary += (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = ByteReadChannel(
                            """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"items":[{"sessionId":"$SESSION_ID","updatedAt":2,"running":false,"blank":false,"cwd":"/root/CODE/project"}]}}}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/agentPresets/list" -> {
                    unary += (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"presets":[{"id":"standard","trust":"system","isDefault":true}],"authorable":true}}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/session/modelCatalog" -> respond(
                    content = ByteReadChannel(
                        """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"default":{"provider":"p","model":"m"},"groups":[]}}}""",
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error(request.url.encodedPath)
            }
        }
        val http = HttpClient(engine) {
            followRedirects = false
            install(ContentNegotiation) { json(json) }
        }
        val client = DshApiClient(
            http,
            json,
            mintRpcId = { "fixed" },
            downlinkFactory = object : DshDownlinkFactory {
                override suspend fun openMux(connection: DshConnection): DshDownlink = mux
            },
        )
        val manager = DshConnectionManager(
            client = client,
            scope = testScope.backgroundScope,
            mintStreamId = { "st-${nextStream++}" },
        )
        val reducer = EventReducer()
        return DshHarness(client, manager, reducer)
    }

    private var vmReducer: EventReducer = EventReducer()

    private fun newViewModel(dshApi: DshApiClient, manager: DshConnectionManager): ChatViewModel {
        repo = SessionListPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmpFolder.newFile("anchor-${System.nanoTime()}.preferences_pb") },
            ),
        )
        vmReducer = EventReducer()
        return ChatViewModel(
            appContext = appContext(),
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "serverUrl" to "http://127.0.0.1:3080",
                    "username" to "",
                    "password" to "",
                    "serverName" to "DSH",
                    "serverId" to SERVER_ID,
                    "sessionId" to SESSION_ID,
                    "directory" to "/root/CODE/project",
                    "serverType" to ServerType.DSH.name,
                    "token" to "launch-token",
                ),
            ),
            eventReducer = vmReducer,
            api = unusedOpenCodeApi(),
            json = json,
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = repo,
            settingsRepository = settingsRepository(),
            dshApi = dshApi,
            dshConnectionManager = manager,
            foregroundResumeDispatcher = ForegroundResumeDispatcher(),
        ).also(viewModels::add)
    }

    private fun unusedOpenCodeApi(): OpenCodeApi {
        val engine = MockEngine { error("opencode unused: ${it.url.encodedPath}") }
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

    private fun pushBaselines(mux: FakeDownlink) {
        mux.incoming.trySend(item("st-0", """{"type":"ready","clientId":"client-1","host":{"home":"/root"}}"""))
        mux.incoming.trySend(item("st-1", """{"type":"baseline","value":{"queues":{},"jobs":{},"projections":{}}}"""))
        mux.incoming.trySend(
            item("st-2", """{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"hiddenWorkspaceIds":[]}}"""),
        )
    }

    private fun userRecord(seq: Long): String =
        """{"type":"event","event":{"type":"user/message","seq":$seq,"time":$seq,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}"""

    private fun assistantRecord(id: String, seq: Long, text: String): String =
        """{"type":"event","event":{"type":"assistant/message","seq":$seq,"time":$seq,"data":{"message":{"id":"$id","content":[{"type":"text","text":"$text"}]}}}}"""

    private fun item(streamId: String, value: String): String =
        """{"type":"item","streamId":"$streamId","value":$value}"""

    private fun streamIdOf(sent: String): String =
        json.parseToJsonElement(sent).jsonObject.getValue("streamId").jsonPrimitive.content

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    private companion object {
        private const val SERVER_ID = "dsh-anchor"
        private const val SESSION_ID = "ses_anchor"
        private const val GRACE_MS = 2_000L
    }
}
