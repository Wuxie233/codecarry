package dev.minios.ocremote.data.diagnostics

import android.content.ContextWrapper
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionDiagnosticsGeneratorTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `session diagnostic includes safe metadata and omits prompt and message bodies`() {
        val fixture = newFixture()
        val session = Session(
            id = "ses_123",
            projectId = "project-token=project-secret",
            directory = "/work/project?secret=directory-secret",
            title = "Investigate Authorization: Bearer title-token",
            time = Session.Time(created = 100L, updated = 200L),
            summary = Session.Summary(additions = 3, deletions = 1, files = 2),
        )
        val messages = listOf(
            MessageWithParts(
                info = Message.User(
                    id = "msg_user",
                    sessionId = session.id,
                    time = TimeInfo(created = 101L),
                    summary = Message.User.UserSummary(
                        title = "prompt title",
                        body = "PROMPT_BODY_SHOULD_NOT_APPEAR password=prompt-password",
                    ),
                ),
                parts = listOf(
                    Part.Text(
                        id = "part_text",
                        sessionId = session.id,
                        messageId = "msg_user",
                        text = "USER_MESSAGE_BODY_SHOULD_NOT_APPEAR token=part-token",
                    ),
                    Part.Subtask(
                        id = "part_subtask",
                        sessionId = session.id,
                        messageId = "msg_user",
                        prompt = "SUBTASK_PROMPT_SHOULD_NOT_APPEAR secret=subtask-secret",
                    ),
                ),
            ),
            MessageWithParts(
                info = Message.Assistant(
                    id = "msg_assistant",
                    sessionId = session.id,
                    time = TimeInfo(created = 102L),
                    error = Message.Assistant.ErrorInfo(name = "ASSISTANT_ERROR_BODY_SHOULD_NOT_APPEAR"),
                ),
                parts = listOf(
                    Part.Reasoning(
                        id = "part_reasoning",
                        sessionId = session.id,
                        messageId = "msg_assistant",
                        text = "ASSISTANT_MESSAGE_BODY_SHOULD_NOT_APPEAR Bearer reasoning-token",
                    ),
                ),
            ),
        )

        val item = fixture.generator.createArtifact(
            input = SessionDiagnosticInput(
                session = session,
                serverId = "srv_123",
                serverName = "Local server password=server-password",
                serverConnection = ServerConnection(
                    baseUrl = "https://example.test/api?token=url-token&password=url-password",
                    authHeader = "Authorization: Bearer raw-auth-token",
                ),
                currentSource = "session-list?secret=source-secret",
                currentContext = "active uploadToken=upload-token",
                status = SessionStatus.Busy,
                messages = messages,
                pendingPermissionCount = 2,
                pendingQuestionCount = 1,
                todoCount = 4,
                diffFileCount = 5,
                isActiveSession = true,
            ),
            createdAtMillis = 300L,
        )

        val content = fixture.repository.getArtifactFile(item)?.readText().orEmpty()

        assertEquals(DiagnosticsLogType.SESSION_DIAGNOSTIC, item.type)
        assertEquals("ses_123", item.sessionId)
        assertTrue(content.contains("ses_123"))
        assertTrue(content.contains("/work/project?secret=<redacted>"))
        assertTrue(content.contains("srv_123"))
        assertTrue(content.contains("\"message_count\":2"))
        assertTrue(content.contains("\"part_count\":3"))
        assertTrue(content.contains("\"has_auth_header\":true"))
        assertTrue(content.contains("\"additions\":3"))
        assertTrue(content.contains("Busy"))
        assertTrue(content.contains("<redacted>"))
        assertFalse(content.contains("PROMPT_BODY_SHOULD_NOT_APPEAR"))
        assertFalse(content.contains("USER_MESSAGE_BODY_SHOULD_NOT_APPEAR"))
        assertFalse(content.contains("SUBTASK_PROMPT_SHOULD_NOT_APPEAR"))
        assertFalse(content.contains("ASSISTANT_MESSAGE_BODY_SHOULD_NOT_APPEAR"))
        assertFalse(content.contains("ASSISTANT_ERROR_BODY_SHOULD_NOT_APPEAR"))
        assertFalse(content.contains("raw-auth-token"))
        assertFalse(content.contains("Bearer raw-auth-token"))
        assertFalse(content.contains("server-password"))
        assertFalse(content.contains("url-token"))
        assertFalse(content.contains("url-password"))
        assertFalse(content.contains("source-secret"))
        assertFalse(content.contains("upload-token"))
        assertFalse(content.contains("project-secret"))
        assertFalse(item.serverName.orEmpty().contains("server-password"))
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
            generator = SessionDiagnosticsGenerator(repository),
        )
    }

    private data class Fixture(
        val repository: DiagnosticsLogRepository,
        val generator: SessionDiagnosticsGenerator,
    )
}
