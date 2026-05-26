package dev.minios.ocremote.ui.screens.diagnostics

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.minios.ocremote.data.diagnostics.AppEventDiagnosticsGenerator
import dev.minios.ocremote.data.diagnostics.DiagnosticsBundleRepository
import dev.minios.ocremote.data.diagnostics.DiagnosticsLogRepository
import dev.minios.ocremote.data.diagnostics.DiagnosticsLogType
import dev.minios.ocremote.data.diagnostics.DiagnosticsUploadClient
import dev.minios.ocremote.data.diagnostics.DiagnosticsUploadRepository
import dev.minios.ocremote.data.diagnostics.NetworkDiagnosticsRecorder
import dev.minios.ocremote.data.diagnostics.SessionDiagnosticsGenerator
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @Test
    fun `empty state lists no logs and disables upload`() = testScope.runTest {
        val fixture = newFixture()

        val state = fixture.viewModel.uiState.first()

        assertTrue(state.logs.isEmpty())
        assertTrue(state.selectedLogIds.isEmpty())
        assertFalse(state.canUpload)
    }

    @Test
    fun `selecting logs enables upload when url and token are configured`() = testScope.runTest {
        val fixture = newFixture()
        val item = fixture.logRepository.createLog(DiagnosticsLogType.APP_EVENT, "App", "content")
        fixture.settingsRepository.setDiagnosticsUploadUrl("https://diagnostics.example/upload")
        fixture.settingsRepository.setDiagnosticsUploadToken("upload-token")

        advanceUntilIdle()
        fixture.viewModel.refresh()
        advanceUntilIdle()
        fixture.viewModel.toggleSelection(item.id)
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(setOf(item.id), state.selectedLogIds)
        assertTrue(state.uploadUrlConfigured)
        assertTrue(state.uploadTokenConfigured)
        assertTrue(state.canUpload)
    }

    @Test
    fun `upload success bundles selected logs clears selection and reports id`() = testScope.runTest {
        var requestCount = 0
        val fixture = newFixture(
            uploadClient = newClient { request ->
                requestCount += 1
                assertEquals("Bearer upload-token", request.headers[HttpHeaders.Authorization])
                respondJson(
                    """
                    {
                      "id": "diag_success",
                      "filename": "bundle.zip",
                      "size": 5,
                      "stored_at": "2026-05-26T04:30:00Z",
                      "sha256": "success"
                    }
                    """.trimIndent(),
                )
            },
        )
        val first = fixture.logRepository.createLog(DiagnosticsLogType.APP_EVENT, "App", "content")
        val second = fixture.logRepository.createLog(DiagnosticsLogType.NETWORK_DIAGNOSTIC, "Network", "{}")
        fixture.settingsRepository.setDiagnosticsUploadUrl("https://diagnostics.example/upload")
        fixture.settingsRepository.setDiagnosticsUploadToken("upload-token")
        advanceUntilIdle()
        fixture.viewModel.refresh()
        advanceUntilIdle()
        fixture.viewModel.toggleSelection(first.id)
        fixture.viewModel.toggleSelection(second.id)
        advanceUntilIdle()
        assertEquals(setOf(first.id, second.id), fixture.viewModel.uiState.value.selectedLogIds)

        fixture.viewModel.uploadSelected()?.join()
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(1, requestCount)
        assertTrue("selection after success: $state", state.selectedLogIds.isEmpty())
        assertTrue("status after success: ${state.statusMessage}", state.statusMessage.orEmpty().contains("diag_success"))
        assertTrue("status after success: ${state.statusMessage}", state.statusMessage.orEmpty().contains("oc-remote-diagnostics"))
        assertFalse(state.statusMessage.orEmpty().contains("upload-token"))
        assertFalse(state.isUploading)
    }

    @Test
    fun `upload failure preserves selection redacts error and ignores rapid duplicate taps`() = testScope.runTest {
        var requestCount = 0
        val fixture = newFixture(
            uploadClient = newClient {
                requestCount += 1
                throw IOException("Authorization: Bearer leaked-token password=leaked-password token=leaked-token")
            },
        )
        val item = fixture.logRepository.createLog(DiagnosticsLogType.APP_EVENT, "App", "content")
        fixture.settingsRepository.setDiagnosticsUploadUrl("https://diagnostics.example/upload?token=url-secret")
        fixture.settingsRepository.setDiagnosticsUploadToken("upload-token")
        advanceUntilIdle()
        fixture.viewModel.refresh()
        advanceUntilIdle()
        fixture.viewModel.toggleSelection(item.id)
        advanceUntilIdle()
        assertEquals(setOf(item.id), fixture.viewModel.uiState.value.selectedLogIds)

        val uploadJob = fixture.viewModel.uploadSelected()
        val duplicateJob = fixture.viewModel.uploadSelected()
        uploadJob?.join()
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(null, duplicateJob)
        assertEquals(1, requestCount)
        assertEquals(setOf(item.id), state.selectedLogIds)
        assertTrue("error after failure: ${state.errorMessage}", state.errorMessage.orEmpty().contains("<redacted>"))
        assertFalse(state.errorMessage.orEmpty().contains("leaked-token"))
        assertFalse(state.errorMessage.orEmpty().contains("leaked-password"))
        assertFalse(state.errorMessage.orEmpty().contains("url-secret"))
        assertFalse(state.isUploading)
    }

    @Test
    fun `missing url or token blocks upload before bundle creation`() = testScope.runTest {
        var requestCount = 0
        val fixture = newFixture(uploadClient = newClient { requestCount += 1; respondJson("{}") })
        val item = fixture.logRepository.createLog(DiagnosticsLogType.APP_EVENT, "App", "content")
        fixture.viewModel.refresh()
        advanceUntilIdle()
        fixture.viewModel.toggleSelection(item.id)

        fixture.viewModel.uploadSelected()
        advanceUntilIdle()

        assertEquals(0, requestCount)
        assertTrue(fixture.viewModel.uiState.value.errorMessage.orEmpty().contains("URL is required"))
        assertEquals(setOf(item.id), fixture.viewModel.uiState.value.selectedLogIds)

        fixture.settingsRepository.setDiagnosticsUploadUrl("https://diagnostics.example/upload")
        fixture.viewModel.uploadSelected()
        advanceUntilIdle()

        assertEquals(0, requestCount)
        assertTrue(fixture.viewModel.uiState.value.errorMessage.orEmpty().contains("bearer token is required"))
        assertEquals(emptyList<File>(), fixture.logRepository.bundleCacheDirectory.listFiles().orEmpty().toList())
    }

    @Test
    fun `generate now with no session creates app network and no-session artifacts`() = testScope.runTest {
        val fixture = newFixture()

        fixture.viewModel.generateNow()
        advanceUntilIdle()

        val logs = fixture.logRepository.listLogs()
        assertEquals(3, logs.size)
        assertEquals(1, logs.count { it.type == DiagnosticsLogType.APP_EVENT })
        assertEquals(1, logs.count { it.type == DiagnosticsLogType.NETWORK_DIAGNOSTIC })
        val sessionItem = logs.single { it.type == DiagnosticsLogType.SESSION_DIAGNOSTIC }
        assertEquals(null, sessionItem.sessionId)
        assertEquals(null, sessionItem.serverName)
        val content = fixture.logRepository.getArtifactFile(sessionItem)?.readText().orEmpty()
        assertTrue(content.contains("no-session-available"))
        assertTrue(content.contains("\"id\":null"))
        assertTrue(content.contains("\"name\":null"))
    }

    @Test
    fun `generate now with session creates exactly one artifact of each type`() = testScope.runTest {
        val fixture = newFixture()
        fixture.eventReducer.setSessions(
            serverId = "server-1",
            sessions = listOf(
                Session(
                    id = "session-1",
                    title = "Current",
                    time = Session.Time(created = 1L, updated = 2L),
                ),
            ),
        )

        fixture.viewModel.generateNow()
        advanceUntilIdle()

        val logs = fixture.logRepository.listLogs()
        assertEquals(3, logs.size)
        assertEquals(1, logs.count { it.type == DiagnosticsLogType.APP_EVENT })
        assertEquals(1, logs.count { it.type == DiagnosticsLogType.NETWORK_DIAGNOSTIC })
        assertEquals(1, logs.count { it.type == DiagnosticsLogType.SESSION_DIAGNOSTIC })
        assertEquals("session-1", logs.single { it.type == DiagnosticsLogType.SESSION_DIAGNOSTIC }.sessionId)
    }

    @Test
    fun `delete selected removes logs and clears selection`() = testScope.runTest {
        val fixture = newFixture()
        val first = fixture.logRepository.createLog(DiagnosticsLogType.APP_EVENT, "App", "content")
        val second = fixture.logRepository.createLog(DiagnosticsLogType.NETWORK_DIAGNOSTIC, "Network", "{}")
        fixture.viewModel.refresh()
        advanceUntilIdle()
        fixture.viewModel.toggleSelection(first.id)

        fixture.viewModel.deleteSelected()
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(listOf(second.id), state.logs.map { it.id })
        assertTrue(state.selectedLogIds.isEmpty())
        assertTrue(state.statusMessage.orEmpty().contains("Deleted 1"))
        assertEquals(1, fixture.logRepository.listLogs().size)
    }

    private fun newFixture(
        uploadClient: DiagnosticsUploadClient = newClient { throw AssertionError("unexpected diagnostics upload request") },
    ): Fixture {
        val filesDir = tmpFolder.newFolder("files-${System.nanoTime()}")
        val cacheDir = tmpFolder.newFolder("cache-${System.nanoTime()}")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
        }
        val settingsRepository = createSettingsRepository()
        val logRepository = DiagnosticsLogRepository(context)
        val eventReducer = EventReducer()
        val uploadRepository = DiagnosticsUploadRepository(settingsRepository, uploadClient)
        val viewModel = DiagnosticsViewModel(
            settingsRepository = settingsRepository,
            logRepository = logRepository,
            bundleRepository = DiagnosticsBundleRepository(logRepository),
            uploadRepository = uploadRepository,
            appEventDiagnosticsGenerator = AppEventDiagnosticsGenerator(logRepository),
            networkDiagnosticsRecorder = NetworkDiagnosticsRecorder(logRepository),
            sessionDiagnosticsGenerator = SessionDiagnosticsGenerator(logRepository),
            eventReducer = eventReducer,
        )
        return Fixture(
            viewModel = viewModel,
            settingsRepository = settingsRepository,
            logRepository = logRepository,
            eventReducer = eventReducer,
        )
    }

    private fun createSettingsRepository(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("diagnostics-vm-settings-${System.nanoTime()}.preferences_pb") },
        )
        val context = object : ContextWrapper(null) {}
        return SettingsRepository(dataStore, context)
    }

    private fun newClient(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): DiagnosticsUploadClient {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return DiagnosticsUploadClient(client)
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private data class Fixture(
        val viewModel: DiagnosticsViewModel,
        val settingsRepository: SettingsRepository,
        val logRepository: DiagnosticsLogRepository,
        val eventReducer: EventReducer,
    )

    class MainDispatcherRule(
        private val dispatcher: TestDispatcher,
    ) : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
}
