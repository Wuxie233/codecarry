package dev.wuxie233.codecarry.data.diagnostics

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.wuxie233.codecarry.data.repository.SettingsRepository
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsUploadRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `initial state is idle and upload without selection is a no-op`() = testScope.runTest {
        var requestCount = 0
        val fixture = newRepository(
            uploadClient = newClient {
                requestCount += 1
                respondJson("{}")
            },
        )

        val result = fixture.repository.uploadSelectedFile()

        assertEquals(DiagnosticsUploadState.Idle, fixture.repository.state.first())
        assertEquals(DiagnosticsUploadState.Idle, result)
        assertEquals(0, requestCount)
    }

    @Test
    fun `clearing selection after picker cancellation preserves previous idle state`() = testScope.runTest {
        val fixture = newRepository(uploadClient = newClient { respondJson("{}") })

        assertEquals(DiagnosticsUploadState.Idle, fixture.repository.state.value)
        val result = fixture.repository.uploadSelectedFile()

        assertEquals(DiagnosticsUploadState.Idle, result)
        assertEquals(DiagnosticsUploadState.Idle, fixture.repository.state.value)
    }

    @Test
    fun `lazy selected file reads bytes only during explicit upload`() = testScope.runTest {
        var readCount = 0
        var requestCount = 0
        val fixture = newRepository(
            uploadClient = newClient {
                requestCount += 1
                respondJson(
                    """
                    {
                      "id": "diag_lazy",
                      "filename": "selected.log",
                      "size": 4,
                      "stored_at": "2026-05-26T04:40:00Z",
                      "sha256": "lazy"
                    }
                    """.trimIndent(),
                )
            },
        )
        fixture.settingsRepository.setDiagnosticsUploadUrl("https://diagnostics.example/upload")
        fixture.settingsRepository.setDiagnosticsUploadToken("upload-token")

        fixture.repository.selectFile(
            DiagnosticsSelectedFile("selected.log", 4, "text/plain"),
        ) {
            readCount += 1
            DiagnosticsUploadFile("selected.log", "test".toByteArray(), "text/plain")
        }

        assertEquals(0, readCount)
        assertEquals(0, requestCount)

        val result = fixture.repository.uploadSelectedFile()

        assertTrue(result is DiagnosticsUploadState.Success)
        assertEquals(1, readCount)
        assertEquals(1, requestCount)
        assertEquals("diag_lazy", (result as DiagnosticsUploadState.Success).reportId)
    }

    @Test
    fun `selected file uploads through uploading state and reports success id`() = testScope.runTest {
        var requestCount = 0
        lateinit var fixture: RepositoryFixture
        fixture = newRepository(
            uploadClient = newClient {
                requestCount += 1
                assertTrue(fixture.repository.state.value is DiagnosticsUploadState.Uploading)
                respondJson(
                    """
                    {
                      "id": "diag_456",
                      "filename": "bundle.zip",
                      "size": 3,
                      "stored_at": "2026-05-26T04:30:00Z",
                      "sha256": "def456"
                    }
                    """.trimIndent(),
                )
            },
        )
        fixture.settingsRepository.setDiagnosticsUploadUrl(" https://diagnostics.example/upload ")
        fixture.settingsRepository.setDiagnosticsUploadToken(" upload-token ")

        fixture.repository.selectFile(DiagnosticsUploadFile("bundle.zip", byteArrayOf(1, 2, 3), "application/zip"))

        val selected = fixture.repository.state.value as DiagnosticsUploadState.FileSelected
        assertEquals(DiagnosticsSelectedFile("bundle.zip", 3, "application/zip"), selected.file)

        val result = fixture.repository.uploadSelectedFile()

        assertEquals(1, requestCount)
        assertTrue(result is DiagnosticsUploadState.Success)
        assertEquals("diag_456", (result as DiagnosticsUploadState.Success).reportId)
        assertEquals(result, fixture.repository.state.value)
    }

    @Test
    fun `missing upload URL fails before network request`() = testScope.runTest {
        var requestCount = 0
        var readCount = 0
        val fixture = newRepository(
            uploadClient = newClient {
                requestCount += 1
                respondJson("{}")
            },
        )
        fixture.settingsRepository.setDiagnosticsUploadToken("upload-token")
        fixture.repository.selectFile(DiagnosticsSelectedFile("bundle.zip", 1, "application/zip")) {
            readCount += 1
            DiagnosticsUploadFile("bundle.zip", byteArrayOf(1), "application/zip")
        }

        val result = fixture.repository.uploadSelectedFile()

        assertEquals(0, requestCount)
        assertEquals(0, readCount)
        assertTrue(result is DiagnosticsUploadState.Error)
        assertTrue((result as DiagnosticsUploadState.Error).message.contains("URL is required"))
    }

    @Test
    fun `missing bearer token fails before network request`() = testScope.runTest {
        var requestCount = 0
        var readCount = 0
        val fixture = newRepository(
            uploadClient = newClient {
                requestCount += 1
                respondJson("{}")
            },
        )
        fixture.settingsRepository.setDiagnosticsUploadUrl("https://diagnostics.example/upload")
        fixture.repository.selectFile(DiagnosticsSelectedFile("bundle.zip", 1, "application/zip")) {
            readCount += 1
            DiagnosticsUploadFile("bundle.zip", byteArrayOf(1), "application/zip")
        }

        val result = fixture.repository.uploadSelectedFile()

        assertEquals(0, requestCount)
        assertEquals(0, readCount)
        assertTrue(result is DiagnosticsUploadState.Error)
        assertTrue((result as DiagnosticsUploadState.Error).message.contains("bearer token is required"))
    }

    @Test
    fun `failed upload redacts secrets before exposing error state`() = testScope.runTest {
        val fixture = newRepository(
            uploadClient = newClient {
                throw IOException("Authorization: Bearer leaked-token password=leaked-password token=leaked-token")
            },
        )
        fixture.settingsRepository.setDiagnosticsUploadUrl("https://diagnostics.example/upload")
        fixture.settingsRepository.setDiagnosticsUploadToken("upload-token")
        fixture.repository.selectFile(DiagnosticsUploadFile("bundle.zip", byteArrayOf(1)))

        val result = fixture.repository.uploadSelectedFile()

        assertTrue(result is DiagnosticsUploadState.Error)
        val message = (result as DiagnosticsUploadState.Error).message
        assertFalse(message.contains("leaked-token"))
        assertFalse(message.contains("leaked-password"))
        assertTrue(message.contains("<redacted>"))
    }

    @Test
    fun `cancel leaves selected state unchanged`() = testScope.runTest {
        val fixture = newRepository(
            uploadClient = newClient { respondJson("{}") },
        )
        val selectedFile = DiagnosticsUploadFile("bundle.zip", byteArrayOf(1, 2, 3), "application/zip")
        fixture.repository.selectFile(selectedFile)
        val beforeCancel = fixture.repository.state.value

        val afterCancel = fixture.repository.state.value

        assertEquals(beforeCancel, afterCancel)
        assertEquals(DiagnosticsUploadState.FileSelected(DiagnosticsSelectedFile("bundle.zip", 3, "application/zip")), afterCancel)
    }

    @Test
    fun `open file failure is redacted and keeps selected metadata`() = testScope.runTest {
        val fixture = newRepository(
            uploadClient = newClient { throw AssertionError("unexpected diagnostics upload request") },
        )
        fixture.settingsRepository.setDiagnosticsUploadUrl("https://diagnostics.example/upload")
        fixture.settingsRepository.setDiagnosticsUploadToken("upload-token")
        fixture.repository.selectFile(
            file = DiagnosticsSelectedFile("bundle.zip", 1, "application/zip"),
            openFile = { throw IOException("failed with Authorization: Bearer leaked-token") },
        )

        val result = fixture.repository.uploadSelectedFile()

        assertTrue(result is DiagnosticsUploadState.Error)
        val error = result as DiagnosticsUploadState.Error
        assertEquals(DiagnosticsSelectedFile("bundle.zip", 1, "application/zip"), error.file)
        assertFalse(error.message.contains("leaked-token"))
        assertTrue(error.message.contains("<redacted>"))
    }

    private fun newRepository(
        uploadClient: DiagnosticsUploadClient,
    ): RepositoryFixture {
        val settingsRepository = createSettingsRepository()
        return RepositoryFixture(
            repository = DiagnosticsUploadRepository(settingsRepository, uploadClient),
            settingsRepository = settingsRepository,
        )
    }

    private data class RepositoryFixture(
        val repository: DiagnosticsUploadRepository,
        val settingsRepository: SettingsRepository,
    )

    private fun createSettingsRepository(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("diagnostics-settings-${System.nanoTime()}.preferences_pb") },
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
}
