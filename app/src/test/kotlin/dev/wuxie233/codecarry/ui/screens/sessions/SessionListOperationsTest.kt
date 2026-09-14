package dev.wuxie233.codecarry.ui.screens.sessions

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.diagnostics.AppEventDiagnosticsGenerator
import dev.wuxie233.codecarry.data.diagnostics.DiagnosticsLogRepository
import dev.wuxie233.codecarry.data.dsh.unusedDshApi
import dev.wuxie233.codecarry.data.dsh.unusedDshConnectionManager
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavior coverage for the generic session list operations slice:
 * authoritative per-server operation targets (#42), actionable operation
 * errors while content is shown (#43), and connection/pending operation
 * state (#44).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionListOperationsTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)
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
        Dispatchers.resetMain()
    }

    // ============ #42: authoritative per-server operation targets ============

    @Test
    fun `project archive targets only the selected server sessions when ids repeat across servers`() = runTest(dispatcher) {
        val reducer = EventReducer()
        // The other server is registered last so the global sessions aggregate
        // holds ITS metadata for the shared id (directory and archived flag differ).
        reducer.setSessions(
            SERVER,
            listOf(
                testSession(id = "ses-shared", directory = "/work/project"),
                testSession(id = "ses-only-a", directory = "/work/project"),
            ),
        )
        reducer.setSessions(
            "srv-other",
            listOf(
                testSession(id = "ses-shared", directory = "/elsewhere", archived = 1_000L),
                testSession(id = "ses-only-b", directory = "/work/project"),
            ),
        )
        // Sanity: the global aggregate really does hold the other server's copy.
        assertEquals("/elsewhere", reducer.sessions.value.first { it.id == "ses-shared" }.directory)

        val api = operationsApi()
        val vm = newViewModel(eventReducer = reducer, api = api.api)
        collectUiState(vm)
        advanceUntilIoSettles { api.projectHits.get() >= 1 }

        vm.archiveProjectSessions("/work/project")
        advanceUntilIoSettles { api.patchHits.get() >= 2 }

        // The other server's copy of the shared id lives in /elsewhere and is
        // already archived; only this server's targets are archived, and the
        // other server's /work/project session is never touched.
        assertEquals(listOf("ses-only-a", "ses-shared"), archivedIds.sorted())
    }

    @Test
    fun `single archive resolves the title from the selected server metadata`() = runTest(dispatcher) {
        val reducer = EventReducer()
        reducer.setSessions(SERVER, listOf(testSession(id = "ses-shared", directory = "/work/project", title = "mine")))
        reducer.setSessions("srv-other", listOf(testSession(id = "ses-shared", directory = "/elsewhere", title = "theirs")))

        val api = operationsApi()
        val vm = newViewModel(eventReducer = reducer, api = api.api)
        collectUiState(vm)
        advanceUntilIoSettles { api.projectHits.get() >= 1 }

        val undoTitles = Collections.synchronizedList(mutableListOf<String>())
        collectJobs += testScope.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            vm.undoState.collect { action ->
                (action as? UndoAction.Archive)?.let { undoTitles += it.title }
            }
        }
        vm.archiveSession("ses-shared")
        advanceUntilIoSettles { undoTitles.isNotEmpty() }

        assertEquals(listOf("mine"), undoTitles)
    }

    // ============ #43: actionable errors while content is shown ============

    @Test
    fun `rename failure surfaces an actionable operation error while list content stays`() = runTest(dispatcher) {
        val reducer = EventReducer()
        reducer.setSessions(SERVER, listOf(testSession(id = "ses-1", directory = "/work/project")))
        val api = operationsApi(renameFails = true)
        val vm = newViewModel(eventReducer = reducer, api = api.api)
        collectUiState(vm)
        advanceUntilIoSettles { api.projectHits.get() >= 1 }
        assertTrue(vm.uiState.value.hasAnySessions)
        assertNull(vm.uiState.value.operationError)

        vm.renameSession("ses-1", "New title")
        advanceUntilIoSettles { vm.uiState.value.operationError != null }

        val state = vm.uiState.value
        assertNotNull(state.operationError)
        assertTrue(state.operationError!!.message.isNotBlank())
        assertEquals(SessionListOperation.Rename("ses-1", "New title"), state.operationError!!.operation)
        // The list itself is untouched and no operation stays pending.
        assertTrue(state.hasAnySessions)
        assertEquals(1, state.groups.sumOf { it.sessions.size })
        assertNull(state.pendingOperation)

        // A later successful load must not swallow the surfaced failure.
        api.renameFails = false
        val loadsBefore = api.projectHits.get()
        vm.loadSessions()
        advanceUntilIoSettles { api.projectHits.get() > loadsBefore }
        assertNotNull(vm.uiState.value.operationError)

        vm.dismissOperationError()
        scheduler.runCurrent()
        assertNull(vm.uiState.value.operationError)
    }

    @Test
    fun `refresh failure with content surfaces a retryable refresh error and disables operations`() = runTest(dispatcher) {
        val reducer = EventReducer()
        reducer.setSessions(SERVER, listOf(testSession(id = "ses-1", directory = "/work/project")))
        val api = operationsApi(loadFails = true)
        val vm = newViewModel(eventReducer = reducer, api = api.api)
        collectUiState(vm)
        advanceUntilIoSettles { vm.uiState.value.operationError != null }

        val failed = vm.uiState.value
        assertTrue(failed.hasAnySessions)
        assertTrue(failed.groups.isNotEmpty())
        assertEquals(SessionListOperation.Refresh, failed.operationError?.operation)
        assertFalse(failed.serverOperationsAvailable)
        assertNull(failed.pendingOperation)

        // Recovery: a successful refresh clears the refresh error and re-enables operations.
        api.loadFails = false
        vm.retryOperationError()
        advanceUntilIoSettles { vm.uiState.value.operationError == null }

        val recovered = vm.uiState.value
        assertNull(recovered.operationError)
        assertTrue(recovered.serverOperationsAvailable)
    }

    @Test
    fun `retry operation error reruns the failed rename`() = runTest(dispatcher) {
        val reducer = EventReducer()
        reducer.setSessions(SERVER, listOf(testSession(id = "ses-1", directory = "/work/project")))
        val api = operationsApi(renameFails = true)
        val vm = newViewModel(eventReducer = reducer, api = api.api)
        collectUiState(vm)
        advanceUntilIoSettles { api.projectHits.get() >= 1 }

        vm.renameSession("ses-1", "First")
        advanceUntilIoSettles { vm.uiState.value.operationError != null }
        assertNotNull(vm.uiState.value.operationError)

        api.renameFails = false
        vm.retryOperationError()
        advanceUntilIoSettles { vm.uiState.value.operationError == null }

        assertNull(vm.uiState.value.operationError)
        // Exactly one rename request reached the server and it carried the retried title.
        assertEquals(1, api.renameBodies.size)
        assertTrue(api.renameBodies.single().contains("First"))
    }

    // ============ #44: pending state, re-entry, connection capability ============

    @Test
    fun `second rename while one is pending does not re-enter`() = runTest(dispatcher) {
        val reducer = EventReducer()
        reducer.setSessions(SERVER, listOf(testSession(id = "ses-1", directory = "/work/project")))
        val api = operationsApi()
        val vm = newViewModel(eventReducer = reducer, api = api.api)
        collectUiState(vm)
        advanceUntilIoSettles { api.projectHits.get() >= 1 }

        vm.renameSession("ses-1", "First")
        // Double-tap before the first request completes: the pending guard rejects it synchronously.
        vm.renameSession("ses-1", "Second")
        advanceUntilIoSettles { api.renameBodies.isNotEmpty() }
        scheduler.advanceUntilIdle()

        assertEquals(1, api.renameBodies.size)
        assertTrue(api.renameBodies.single().contains("First"))
        assertFalse(api.renameBodies.single().contains("Second"))
        assertNull(vm.uiState.value.pendingOperation)
    }

    @Test
    fun `operations capability reflects load outcome and keeps local navigation data`() = runTest(dispatcher) {
        val reducer = EventReducer()
        reducer.setSessions(SERVER, listOf(testSession(id = "ses-1", directory = "/work/project")))
        val api = operationsApi(loadFails = true)
        val vm = newViewModel(eventReducer = reducer, api = api.api)
        collectUiState(vm)
        advanceUntilIoSettles { !vm.uiState.value.serverOperationsAvailable }

        assertFalse(vm.uiState.value.serverOperationsAvailable)
        // Local, safe surface stays intact: the list content is still rendered from reducer state.
        assertTrue(vm.uiState.value.hasAnySessions)
        assertTrue(vm.uiState.value.groups.isNotEmpty())

        api.loadFails = false
        vm.loadSessions()
        advanceUntilIoSettles { vm.uiState.value.serverOperationsAvailable }

        assertTrue(vm.uiState.value.serverOperationsAvailable)
    }

    // ============ Helpers ============

    private companion object {
        private const val SERVER = "srv-ops"
    }

    private val archivedIds = Collections.synchronizedList(mutableListOf<String>())

    private fun testSession(
        id: String,
        directory: String,
        title: String? = null,
        archived: Long? = null,
    ) = Session(
        id = id,
        directory = directory,
        title = title,
        parentId = null,
        time = Session.Time(created = 1L, updated = 1L, archived = archived),
    )

    private fun collectUiState(vm: SessionListViewModel) {
        collectJobs += testScope.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            vm.uiState.collect {}
        }
    }

    /** MockEngine answers on real IO threads; drain the scheduler until the condition holds. */
    private fun advanceUntilIoSettles(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            testScope.advanceUntilIdle()
            Thread.sleep(20)
        }
        testScope.advanceUntilIdle()
    }

    private fun newViewModel(
        eventReducer: EventReducer,
        api: OpenCodeApi,
    ): SessionListViewModel = SessionListViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "serverUrl" to "http%3A%2F%2Fexample.test%3A4096",
                "username" to "",
                "password" to "",
                "serverName" to "Local",
                "serverId" to SERVER,
            ),
        ),
        eventReducer = eventReducer,
        api = api,
        preferencesRepo = SessionListPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmpFolder.newFile("session-list-ops-${System.nanoTime()}.preferences_pb") },
            ),
        ),
        settingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmpFolder.newFile("settings-ops-${System.nanoTime()}.preferences_pb") },
            ),
            ApplicationProvider.getApplicationContext(),
        ),
        appEventDiagnosticsGenerator = AppEventDiagnosticsGenerator(diagnosticsLogRepository()),
        dshApi = unusedDshApi(json),
        dshConnectionManager = unusedDshConnectionManager(testScope.backgroundScope, json),
    )

    private fun diagnosticsLogRepository(): DiagnosticsLogRepository {
        val filesDir = tmpFolder.newFolder("diagnostics-files-${System.nanoTime()}")
        val cacheDir = tmpFolder.newFolder("diagnostics-cache-${System.nanoTime()}")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
        }
        return DiagnosticsLogRepository(context)
    }

    private fun operationsApi(
        loadFails: Boolean = false,
        renameFails: Boolean = false,
    ): OperationsMockApi = OperationsMockApi(json, loadFails, renameFails, archivedIds)

    private class OperationsMockApi(
        json: Json,
        var loadFails: Boolean,
        var renameFails: Boolean,
        archivedIds: MutableList<String>,
    ) {
        val api: OpenCodeApi
        val projectHits = AtomicInteger()
        val patchHits = AtomicInteger()
        val renameBodies = Collections.synchronizedList(mutableListOf<String>())

        init {
            val engine = MockEngine { request ->
                when {
                    request.url.encodedPath == "/project" -> {
                        projectHits.incrementAndGet()
                        if (loadFails) respondError("load offline")
                        else respondJson("""[{"id":"p1","worktree":"/work/project","name":"project"}]""")
                    }
                    request.url.encodedPath == "/session" && request.method.value == "GET" -> {
                        if (loadFails) respondError("load offline")
                        else respondJson("[]")
                    }
                    // PATCH /session/{id} covers rename (title) and archive (time.archived).
                    request.method.value == "PATCH" && request.url.encodedPath.startsWith("/session/") -> {
                        patchHits.incrementAndGet()
                        val sessionId = request.url.encodedPath.removePrefix("/session/").substringBefore('/')
                        val body = (request.body as io.ktor.http.content.TextContent).text
                        when {
                            body.contains("\"archived\"") -> {
                                archivedIds += sessionId
                                respondJson("""{"id":"$sessionId","directory":"/work/project","time":{"archived":1}}""")
                            }
                            renameFails -> respondError("rename rejected")
                            else -> {
                                renameBodies += body
                                respondJson("""{"id":"$sessionId","directory":"/work/project"}""")
                            }
                        }
                    }
                    else -> respondJson("{}")
                }
            }
            api = OpenCodeApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)
        }

        private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData = respond(
            content = ByteReadChannel(body),
            status = HttpStatusCode.OK,
            headers = headersOf(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

        private fun MockRequestHandleScope.respondError(message: String): HttpResponseData = respond(
            content = ByteReadChannel("""{"error":"$message"}"""),
            status = HttpStatusCode.InternalServerError,
            headers = headersOf(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }
}
