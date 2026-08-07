package dev.wuxie233.codecarry.data.diagnostics

import android.content.ContextWrapper
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NetworkDiagnosticsRecorderTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `network artifact stores sanitized summaries without headers bodies full urls or secrets`() {
        val fixture = newFixture()
        val summaries = listOf(
            NetworkDiagnosticSummaryBuilder.success(
                method = "post",
                path = "https://server.example/session?token=query-token&password=query-password",
                statusCode = 200,
                startedAtMillis = 1000L,
                completedAtMillis = 1042L,
                serverId = "srv-token=server-secret",
                serverName = "Local password=server-password uploadToken=server-upload-token",
            ),
            NetworkDiagnosticSummaryBuilder.failure(
                method = "GET Authorization: Bearer method-token",
                path = "http://server.example/session/ses_123?secret=query-secret",
                statusCode = 500,
                failureType = "IOException: https://server.example/session/ses_123?token=exception-token&secret=exception-secret Authorization: Bearer exception-token cookie=session-cookie",
                startedAtMillis = 2000L,
                completedAtMillis = 2050L,
            ),
            NetworkDiagnosticSummaryBuilder.failure(
                method = "POST",
                path = "https://diagnostics.example/upload?uploadToken=upload-token&cookie=query-cookie",
                statusCode = 401,
                failureType = "HTTP_401",
                startedAtMillis = 3000L,
                completedAtMillis = 3025L,
            ),
        )

        val item = fixture.recorder.createArtifact(
            summaries = summaries,
            createdAtMillis = 4000L,
        )

        val content = fixture.repository.getArtifactFile(item)?.readText().orEmpty()
        assertEquals(DiagnosticsLogType.NETWORK_DIAGNOSTIC, item.type)
        assertTrue(content.contains("oc-remote.network-diagnostic.v1"))
        assertTrue(content.contains("\"method\":\"POST\""))
        assertTrue(content.contains("\"path_category\":\"/session\""))
        assertTrue(content.contains("\"path_category\":\"/session/{id}\""))
        assertTrue(content.contains("\"path_category\":\"/upload\""))
        assertTrue(content.contains("\"status_code\":200"))
        assertTrue(content.contains("\"duration_millis\":42"))
        assertTrue(content.contains("\"failure_type\":\"IOException\""))
        Json.parseToJsonElement(content)
        assertFalse(content.contains("Authorization"))
        assertFalse(content.contains("Bearer"))
        assertFalse(content.contains("password"))
        assertFalse(content.contains("upload token"))
        assertFalse(content.contains("upload-token"))
        assertFalse(content.contains("server-upload-token"))
        assertFalse(content.contains("cookie"))
        assertFalse(content.contains("query-token"))
        assertFalse(content.contains("query-password"))
        assertFalse(content.contains("query-secret"))
        assertFalse(content.contains("exception-token"))
        assertFalse(content.contains("exception-secret"))
        assertFalse(content.contains("session-cookie"))
        assertFalse(content.contains("https://server.example"))
        assertFalse(content.contains("http://server.example"))
        assertFalse(content.contains("request body"))
        assertFalse(content.contains("response body"))
        assertFalse(item.serverName.orEmpty().contains("server-password"))
    }

    @Test
    fun `path category sanitizer maps known endpoint families and hides unknown paths`() {
        assertEquals("/session", NetworkDiagnosticSanitizer.pathCategory("/session"))
        assertEquals("/session/{id}", NetworkDiagnosticSanitizer.pathCategory("/session/ses_secret?token=query-secret"))
        assertEquals("/event", NetworkDiagnosticSanitizer.pathCategory("https://example.test/event?token=query-secret"))
        assertEquals("/command", NetworkDiagnosticSanitizer.pathCategory("/command/run?password=query-password"))
        assertEquals("/upload", NetworkDiagnosticSanitizer.pathCategory("https://diagnostics.example/upload?uploadToken=upload-token"))
        assertEquals("unknown", NetworkDiagnosticSanitizer.pathCategory("https://example.test/path?token=query-secret"))
    }

    @Test
    fun `recorder keeps bounded sanitized in-memory snapshot`() {
        val fixture = newFixture()

        fixture.recorder.record(
            NetworkDiagnosticSummary(
                method = "post",
                pathCategory = "https://example.test/session/ses_1?token=query-token",
                statusCode = 201,
                durationMillis = -10L,
                failureType = "IOException: password=leaked-password",
                timestampMillis = 100L,
                serverName = "Server Authorization: Bearer metadata-token",
            ),
        )

        val summary = fixture.recorder.snapshot().single()
        assertEquals("POST", summary.method)
        assertEquals("/session/{id}", summary.pathCategory)
        assertEquals(0L, summary.durationMillis)
        assertEquals("IOException", summary.failureType)
        assertFalse(summary.serverName.orEmpty().contains("metadata-token"))
    }

    private fun newFixture(): Fixture {
        val filesDir = tmpFolder.newFolder("files-${System.nanoTime()}")
        val cacheDir = tmpFolder.newFolder("cache-${System.nanoTime()}")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
        }
        val repository = DiagnosticsLogRepository(context)
        return Fixture(
            repository = repository,
            recorder = NetworkDiagnosticsRecorder(repository),
        )
    }

    private data class Fixture(
        val repository: DiagnosticsLogRepository,
        val recorder: NetworkDiagnosticsRecorder,
    )
}
