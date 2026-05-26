package dev.minios.ocremote.data.diagnostics

import android.content.ContextWrapper
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppEventDiagnosticsGeneratorTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `app event artifact captures create new breadcrumbs with redacted failure details`() {
        val fixture = newFixture()

        val item = fixture.generator.createArtifact(
            breadcrumbs = listOf(
                AppEventBreadcrumb(
                    name = AppEventName.CREATE_NEW_TAPPED,
                    timestampMillis = 1000L,
                    serverId = "srv-token=server-secret",
                    serverName = "Local password=server-password",
                    directory = "/work/project?secret=directory-secret",
                ),
                AppEventBreadcrumb(
                    name = AppEventName.CREATE_NEW_SUCCESS,
                    timestampMillis = 1100L,
                    sessionId = "ses_created",
                    directory = "/work/project",
                ),
                AppEventBreadcrumb(
                    name = AppEventName.CREATE_NEW_FAILURE,
                    timestampMillis = 1200L,
                    details = mapOf(
                        "error" to "Authorization: Bearer failure-token password=failure-password token=failure-upload-token",
                        "upload" to "uploadToken=app-upload-token",
                        "url" to "https://example.test/upload?secret=query-secret&token=query-token&uploadToken=query-upload-token",
                    ),
                ),
            ),
            createdAtMillis = 1300L,
            serverName = "Server Authorization: Bearer metadata-token",
        )

        val content = fixture.repository.getArtifactFile(item)?.readText().orEmpty()

        assertEquals(DiagnosticsLogType.APP_EVENT, item.type)
        assertTrue(content.contains("create_new_tapped"))
        assertTrue(content.contains("create_new_success"))
        assertTrue(content.contains("create_new_failure"))
        Json.parseToJsonElement(content)
        assertTrue(content.contains("<redacted>"))
        assertFalse(content.contains("server-secret"))
        assertFalse(content.contains("server-password"))
        assertFalse(content.contains("directory-secret"))
        assertFalse(content.contains("failure-token"))
        assertFalse(content.contains("Bearer failure-token"))
        assertFalse(content.contains("failure-password"))
        assertFalse(content.contains("failure-upload-token"))
        assertFalse(content.contains("app-upload-token"))
        assertFalse(content.contains("query-secret"))
        assertFalse(content.contains("query-token"))
        assertFalse(content.contains("query-upload-token"))
        assertFalse(item.serverName.orEmpty().contains("metadata-token"))
    }

    @Test
    fun `app event enum includes first release breadcrumb names`() {
        val names = AppEventName.entries.map { it.wireName }.toSet()

        assertTrue("app_start" in names)
        assertTrue("server_connect" in names)
        assertTrue("server_disconnect" in names)
        assertTrue("session_list_opened" in names)
        assertTrue("create_new_tapped" in names)
        assertTrue("create_new_success" in names)
        assertTrue("create_new_failure" in names)
        assertTrue("navigation_to_chat_requested" in names)
        assertTrue("upload_started" in names)
        assertTrue("upload_succeeded" in names)
        assertTrue("upload_failed" in names)
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
            generator = AppEventDiagnosticsGenerator(repository),
        )
    }

    private data class Fixture(
        val repository: DiagnosticsLogRepository,
        val generator: AppEventDiagnosticsGenerator,
    )
}
