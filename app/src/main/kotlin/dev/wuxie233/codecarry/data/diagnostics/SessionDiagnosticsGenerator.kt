package dev.wuxie233.codecarry.data.diagnostics

import dev.wuxie233.codecarry.data.api.ServerConnection
import dev.wuxie233.codecarry.domain.model.MessageWithParts
import dev.wuxie233.codecarry.domain.model.Session
import dev.wuxie233.codecarry.domain.model.SessionStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionDiagnosticsGenerator @Inject constructor(
    private val logRepository: DiagnosticsLogRepository,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val noSessionJson = Json { encodeDefaults = true; explicitNulls = true }

    fun createArtifact(
        input: SessionDiagnosticInput,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): DiagnosticsLogItem {
        val content = buildArtifactContent(input, createdAtMillis)
        return logRepository.createLog(
            type = DiagnosticsLogType.SESSION_DIAGNOSTIC,
            displayName = "Session diagnostic",
            content = content,
            createdAtMillis = createdAtMillis,
            sessionId = input.session.id,
            serverName = input.serverName,
        )
    }

    fun createNoSessionArtifact(
        reason: String,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): DiagnosticsLogItem {
        val content = buildNoSessionArtifactContent(reason, createdAtMillis)
        return logRepository.createLog(
            type = DiagnosticsLogType.SESSION_DIAGNOSTIC,
            displayName = "Session diagnostic",
            content = content,
            createdAtMillis = createdAtMillis,
            sessionId = null,
            serverName = null,
        )
    }

    fun buildArtifactContent(input: SessionDiagnosticInput, createdAtMillis: Long = System.currentTimeMillis()): String {
        val payload = SessionDiagnosticPayload(
            generatedAtMillis = createdAtMillis,
            session = SessionDiagnosticSessionPayload(
                id = input.session.id.redactedOrNull().orEmpty(),
                title = input.session.title?.redactedOrNull(),
                directory = input.session.directory.redactedOrNull(),
                projectId = input.session.projectId.redactedOrNull(),
                parentId = input.session.parentId?.redactedOrNull(),
                status = input.status?.diagnosticName(),
                createdAtMillis = input.session.time.created,
                updatedAtMillis = input.session.time.updated,
                archivedAtMillis = input.session.time.archived,
                summary = input.session.summary?.toPayload(),
            ),
            server = SessionDiagnosticServerPayload(
                id = input.serverId?.redactedOrNull(),
                name = input.serverName?.redactedOrNull(),
                baseUrl = input.serverConnection?.baseUrl?.redactedOrNull(),
                hasAuthHeader = input.serverConnection?.authHeader != null,
            ),
            source = SessionDiagnosticSourcePayload(
                currentSource = input.currentSource?.redactedOrNull(),
                currentContext = input.currentContext?.redactedOrNull(),
            ),
            stateSummary = SessionDiagnosticStateSummaryPayload(
                messageCount = input.messages.size,
                userMessageCount = input.messages.count { it.info.role == "user" },
                assistantMessageCount = input.messages.count { it.info.role == "assistant" },
                partCount = input.messages.sumOf { it.parts.size },
                partTypes = input.messages.flatMap { message -> message.parts.map { it::class.simpleName.orEmpty() } }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }
                    .eachCount(),
                pendingPermissionCount = input.pendingPermissionCount,
                pendingQuestionCount = input.pendingQuestionCount,
                todoCount = input.todoCount,
                diffFileCount = input.diffFileCount,
                activeSession = input.isActiveSession,
            ),
        )
        return DiagnosticsRedactor.redact(json.encodeToString(payload))
    }

    fun buildNoSessionArtifactContent(reason: String, createdAtMillis: Long = System.currentTimeMillis()): String {
        val payload = SessionDiagnosticPayload(
            generatedAtMillis = createdAtMillis,
            session = SessionDiagnosticSessionPayload(
                id = null,
                title = null,
                directory = null,
                projectId = null,
                parentId = null,
                status = null,
                createdAtMillis = null,
                updatedAtMillis = null,
                archivedAtMillis = null,
                summary = null,
            ),
            server = SessionDiagnosticServerPayload(
                id = null,
                name = null,
                baseUrl = null,
                hasAuthHeader = false,
            ),
            source = SessionDiagnosticSourcePayload(
                currentSource = "diagnostics-viewmodel",
                currentContext = null,
            ),
            stateSummary = SessionDiagnosticStateSummaryPayload(
                messageCount = 0,
                userMessageCount = 0,
                assistantMessageCount = 0,
                partCount = 0,
                partTypes = emptyMap(),
                pendingPermissionCount = 0,
                pendingQuestionCount = 0,
                todoCount = 0,
                diffFileCount = 0,
                activeSession = false,
            ),
            reason = DiagnosticsRedactor.redact(reason).ifBlank { "no-session-available" },
        )
        return DiagnosticsRedactor.redact(noSessionJson.encodeToString(payload))
    }
}

data class SessionDiagnosticInput(
    val session: Session,
    val serverId: String? = null,
    val serverName: String? = null,
    val serverConnection: ServerConnection? = null,
    val currentSource: String? = null,
    val currentContext: String? = null,
    val status: SessionStatus? = null,
    val messages: List<MessageWithParts> = emptyList(),
    val pendingPermissionCount: Int = 0,
    val pendingQuestionCount: Int = 0,
    val todoCount: Int = 0,
    val diffFileCount: Int = 0,
    val isActiveSession: Boolean = false,
)

@Serializable
private data class SessionDiagnosticPayload(
    val schema: String = "oc-remote.session-diagnostic.v1",
    @SerialName("generated_at_millis") val generatedAtMillis: Long,
    val session: SessionDiagnosticSessionPayload,
    val server: SessionDiagnosticServerPayload,
    val source: SessionDiagnosticSourcePayload,
    @SerialName("state_summary") val stateSummary: SessionDiagnosticStateSummaryPayload,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    val reason: String? = null,
)

@Serializable
private data class SessionDiagnosticSessionPayload(
    val id: String?,
    val title: String? = null,
    val directory: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val status: String? = null,
    @SerialName("created_at_millis") val createdAtMillis: Long? = null,
    @SerialName("updated_at_millis") val updatedAtMillis: Long? = null,
    @SerialName("archived_at_millis") val archivedAtMillis: Long? = null,
    val summary: SessionDiagnosticSummaryPayload? = null,
)

@Serializable
private data class SessionDiagnosticSummaryPayload(
    val additions: Int,
    val deletions: Int,
    val files: Int,
)

@Serializable
private data class SessionDiagnosticServerPayload(
    val id: String? = null,
    val name: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("has_auth_header") val hasAuthHeader: Boolean = false,
)

@Serializable
private data class SessionDiagnosticSourcePayload(
    @SerialName("current_source") val currentSource: String? = null,
    @SerialName("current_context") val currentContext: String? = null,
)

@Serializable
private data class SessionDiagnosticStateSummaryPayload(
    @SerialName("message_count") val messageCount: Int,
    @SerialName("user_message_count") val userMessageCount: Int,
    @SerialName("assistant_message_count") val assistantMessageCount: Int,
    @SerialName("part_count") val partCount: Int,
    @SerialName("part_types") val partTypes: Map<String, Int>,
    @SerialName("pending_permission_count") val pendingPermissionCount: Int,
    @SerialName("pending_question_count") val pendingQuestionCount: Int,
    @SerialName("todo_count") val todoCount: Int,
    @SerialName("diff_file_count") val diffFileCount: Int,
    @SerialName("active_session") val activeSession: Boolean,
)

private fun Session.Summary.toPayload(): SessionDiagnosticSummaryPayload = SessionDiagnosticSummaryPayload(
    additions = additions,
    deletions = deletions,
    files = files,
)

private fun String.redactedOrNull(): String? = takeIf { it.isNotBlank() }?.let(DiagnosticsRedactor::redact)

private fun SessionStatus.diagnosticName(): String = when (this) {
    SessionStatus.Idle -> "Idle"
    SessionStatus.Busy -> "Busy"
    is SessionStatus.Retry -> "Retry"
}
