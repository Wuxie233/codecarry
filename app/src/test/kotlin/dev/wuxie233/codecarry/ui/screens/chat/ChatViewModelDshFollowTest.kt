package dev.wuxie233.codecarry.ui.screens.chat

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
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
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelDshFollowTest {

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
    fun `opening a DSH chat follows the snapshot and never pages with a sentinel`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = Collections.synchronizedList(mutableListOf<String>())
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        runCurrent()

        val followOpen = mux.sent.last { it.contains("\"session/follow\"") }
        val followId = json.parseToJsonElement(followOpen).jsonObject.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(
            item(
                followId,
                """{"type":"snapshot","header":null,"cursor":12072,"records":[{"type":"event","event":{"type":"user/message","seq":12070,"time":1,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}],"hasMore":true}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()

        val state = vm.uiState.first { !it.isLoading && it.error == null && it.messages.isNotEmpty() }
        assertEquals("hello", (state.messages.single().parts.single() as Part.Text).text)
        assertTrue(state.hasOlderMessages)
        assertFalse(unary.any { it.contains("session/page") })
        assertFalse(unary.any { it.contains("9007199254740991") })
        assertEquals(
            12072L,
            harness.manager.reducer(SERVER_ID).state.value.sessions.getValue(SESSION_ID).pageThroughSeq,
        )

        vm.loadOlderMessages()
        runCurrent()
        advanceUntilIdle()

        val pageBodies = unary.filter { it.contains("\"session/page\"") }
        assertEquals(1, pageBodies.size)
        val request = json.parseToJsonElement(pageBodies.single()).jsonObject
            .getValue("payload").jsonObject
            .getValue("args").jsonObject
            .getValue("request").jsonObject
        assertEquals(12072L, request.getValue("throughSeq").jsonPrimitive.content.toLong())
        assertEquals(12070L, request.getValue("beforeSeq").jsonPrimitive.content.toLong())
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `load older without a follow cut does not page`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = Collections.synchronizedList(mutableListOf<String>())
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        vm.loadOlderMessages()
        runCurrent()
        advanceUntilIdle()
        assertFalse(unary.any { it.contains("session/page") })
    }

    @Test
    fun `retry without a ready mux surfaces an error instead of spinning`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = Collections.synchronizedList(mutableListOf<String>())
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        advanceUntilIdle()

        vm.loadMessages()
        runCurrent()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals("DSH is not connected", state.error)
        assertFalse(mux.sent.any { it.contains("\"session/follow\"") })
    }

    @Test
    fun `follow end while ready reopens session follow`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = Collections.synchronizedList(mutableListOf<String>())
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        runCurrent()

        val firstFollow = mux.sent.last { it.contains("\"session/follow\"") }
        val followId = json.parseToJsonElement(firstFollow).jsonObject.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(
            item(
                followId,
                """{"type":"snapshot","header":null,"cursor":12072,"records":[{"type":"event","event":{"type":"user/message","seq":12070,"time":1,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}],"hasMore":false}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()
        vm.uiState.first { !it.isLoading && it.messages.isNotEmpty() }

        mux.incoming.trySend("""{"type":"end","streamId":"$followId"}""")
        runCurrent()
        assertEquals("session/follow stream ended", vm.uiState.value.error)

        val opensBefore = mux.sent.count { it.contains("\"session/follow\"") }
        advanceTimeBy(1_000)
        runCurrent()

        val opensAfter = mux.sent.count { it.contains("\"session/follow\"") }
        assertEquals(opensBefore + 1, opensAfter)
        val secondFollow = mux.sent.last { it.contains("\"session/follow\"") }
        val secondId = json.parseToJsonElement(secondFollow).jsonObject.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(
            item(
                secondId,
                """{"type":"snapshot","header":null,"cursor":12072,"records":[{"type":"event","event":{"type":"user/message","seq":12070,"time":1,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}],"hasMore":false}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()
        vm.uiState.first { it.error == null && it.messages.isNotEmpty() }
    }

    private data class DshHarness(
        val client: DshApiClient,
        val manager: DshConnectionManager,
    )

    private fun dshHarness(
        mux: FakeDownlink,
        unary: MutableList<String>,
    ): DshHarness {
        var nextStream = 0
        val streamIds = listOf("st-0", "st-1", "st-2", "st-3", "st-4", "st-5")
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
                    unary += (request.body as TextContent).text
                    respond(
                        content = ByteReadChannel(
                            """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"items":[{"sessionId":"$SESSION_ID","updatedAt":2,"running":false,"blank":false,"cwd":"/root/CODE/Minecraft"}]}}}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/session/modelCatalog" -> {
                    unary += (request.body as TextContent).text
                    respond(
                        content = ByteReadChannel(
                            """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"default":{"provider":"p","model":"m"},"groups":[]}}}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/session/page" -> {
                    unary += (request.body as TextContent).text
                    respond(
                        content = ByteReadChannel(
                            """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"records":[],"hasMore":false}}}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
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
            mintStreamId = { streamIds[nextStream++ % streamIds.size] },
        )
        return DshHarness(client, manager)
    }

    private fun newViewModel(dshApi: DshApiClient, manager: DshConnectionManager): ChatViewModel {
        return ChatViewModel(
            appContext = object : ContextWrapper(null) {},
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "serverUrl" to "http://127.0.0.1:3080",
                    "username" to "",
                    "password" to "",
                    "serverName" to "DSH",
                    "serverId" to SERVER_ID,
                    "sessionId" to SESSION_ID,
                    "directory" to "/root/CODE/Minecraft",
                    "serverType" to ServerType.DSH.name,
                    "token" to "launch-token",
                ),
            ),
            eventReducer = EventReducer(),
            api = unusedOpenCodeApi(),
            json = json,
            draftRepository = draftRepository(),
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
            dshApi = dshApi,
            dshConnectionManager = manager,
            foregroundResumeDispatcher = dev.wuxie233.codecarry.service.ForegroundResumeDispatcher(),
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

    private fun sessionListPreferencesRepository(): SessionListPreferencesRepository =
        SessionListPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmpFolder.newFile("session-list-${System.nanoTime()}.preferences_pb") },
            ),
        )

    private fun settingsRepository(): SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("settings-${System.nanoTime()}.preferences_pb") },
        ),
        object : ContextWrapper(null) {},
    )

    private fun pushBaselines(mux: FakeDownlink) {
        mux.incoming.trySend(item("st-0", """{"type":"ready","clientId":"client-1","host":{"home":"/root"}}"""))
        mux.incoming.trySend(item("st-1", """{"type":"baseline","value":{"queues":{},"jobs":{},"projections":{}}}"""))
        mux.incoming.trySend(
            item("st-2", """{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"hiddenWorkspaceIds":[]}}"""),
        )
    }

    private fun item(streamId: String, value: String): String =
        """{"type":"item","streamId":"$streamId","value":$value}"""

    private companion object {
        private const val SERVER_ID = "dsh-1"
        private const val SESSION_ID = "ses_minecraft"
    }
}
