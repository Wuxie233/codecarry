package dev.wuxie233.codecarry.data.transport

import dev.wuxie233.codecarry.data.api.PiStackApi
import dev.wuxie233.codecarry.data.api.PiStackConnection
import dev.wuxie233.codecarry.data.api.PiStackAttachmentDto
import dev.wuxie233.codecarry.data.api.PiStackDirectoryListingDto
import dev.wuxie233.codecarry.data.api.PiStackEnvelope
import dev.wuxie233.codecarry.data.api.PiStackMessagePageDto
import dev.wuxie233.codecarry.data.api.PiStackModelSelectionDto
import dev.wuxie233.codecarry.data.api.PiStackOperationDto
import dev.wuxie233.codecarry.data.api.PiStackProjectDto
import dev.wuxie233.codecarry.data.api.PiStackSessionDto
import dev.wuxie233.codecarry.data.api.PiStackSessionControlsDescriptorDto
import dev.wuxie233.codecarry.data.api.PiStackSnapshotDto
import dev.wuxie233.codecarry.data.api.PiStackSseFrame
import dev.wuxie233.codecarry.data.api.PiStackQuestionDto
import dev.wuxie233.codecarry.data.api.PiStackQuestionResolutionDto
import dev.wuxie233.codecarry.data.api.PiStackNotificationDto
import dev.wuxie233.codecarry.data.api.PiStackNotificationReadDto
import dev.wuxie233.codecarry.domain.model.ServerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-bound Pi Control client. Live state ownership remains in the service/reducer layer.
 */
class PiStackTransport internal constructor(
    server: ServerConfig,
    private val api: PiStackApi,
) {
    private val conn = PiStackConnection.from(server.url, server.token)

    suspend fun probe() = api.getCapabilities(conn)
    suspend fun snapshot(): PiStackSnapshotDto = api.getSnapshot(conn)
    suspend fun listDirectories(path: String? = null): PiStackEnvelope<PiStackDirectoryListingDto> =
        api.listDirectories(conn, path)
    suspend fun listProjects(): PiStackEnvelope<List<PiStackProjectDto>> = api.listProjects(conn)
    suspend fun registerProject(directory: String, name: String?, generation: String, idempotencyKey: String) =
        api.registerProject(conn, directory, name, generation, idempotencyKey)
    suspend fun listSessions(projectId: String? = null): PiStackEnvelope<List<PiStackSessionDto>> =
        api.listSessions(conn, projectId)
    suspend fun getSession(sessionId: String) = api.getSession(conn, sessionId)
    suspend fun getSessionControls(sessionId: String) = api.getSessionControls(conn, sessionId)
    suspend fun createSession(
        projectId: String,
        title: String?,
        model: PiStackModelSelectionDto?,
        generation: String,
        idempotencyKey: String,
    ) = api.createSession(conn, projectId, title, model, generation, idempotencyKey)
    suspend fun getMessages(sessionId: String, limit: Int = 50, before: String? = null): PiStackEnvelope<PiStackMessagePageDto> =
        api.getMessages(conn, sessionId, limit, before)
    suspend fun prompt(
        sessionId: String,
        prompt: String,
        generation: String,
        attachments: List<String> = emptyList(),
        idempotencyKey: String,
    ): PiStackEnvelope<PiStackOperationDto> =
        api.prompt(conn, sessionId, prompt, generation, idempotencyKey, attachments)
    suspend fun abort(sessionId: String, reason: String?, generation: String, idempotencyKey: String): PiStackEnvelope<PiStackOperationDto> =
        api.abort(conn, sessionId, reason, generation, idempotencyKey)
    suspend fun renameSession(sessionId: String, title: String, generation: String, idempotencyKey: String) =
        api.renameSession(conn, sessionId, title, generation, idempotencyKey)
    suspend fun archiveSession(sessionId: String, generation: String, idempotencyKey: String) =
        api.archiveSession(conn, sessionId, generation, idempotencyKey)
    suspend fun restoreSession(sessionId: String, generation: String, idempotencyKey: String) =
        api.restoreSession(conn, sessionId, generation, idempotencyKey)
    suspend fun deleteSession(sessionId: String, generation: String, idempotencyKey: String) =
        api.deleteSession(conn, sessionId, generation, idempotencyKey)
    suspend fun selectModel(sessionId: String, provider: String, modelId: String, generation: String, idempotencyKey: String) =
        api.selectModel(conn, sessionId, provider, modelId, generation, idempotencyKey)
    suspend fun selectThinking(sessionId: String, level: String, generation: String, idempotencyKey: String) =
        api.selectThinking(conn, sessionId, level, generation, idempotencyKey)
    suspend fun compactSession(sessionId: String, instructions: String?, generation: String, idempotencyKey: String) =
        api.compactSession(conn, sessionId, instructions, generation, idempotencyKey)
    suspend fun forkSession(sessionId: String, entryId: String, generation: String, idempotencyKey: String) =
        api.forkSession(conn, sessionId, entryId, generation, idempotencyKey)
    suspend fun executeCommand(sessionId: String, text: String, generation: String, idempotencyKey: String) =
        api.executeCommand(conn, sessionId, text, generation, idempotencyKey)
    suspend fun uploadAttachment(
        sessionId: String,
        kind: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        generation: String,
        idempotencyKey: String,
    ): PiStackEnvelope<PiStackAttachmentDto> =
        api.uploadAttachment(conn, sessionId, kind, name, mimeType, bytes, generation, idempotencyKey)
    suspend fun getOperation(operationId: String) = api.getOperation(conn, operationId)

    suspend fun awaitOperation(operationId: String, timeoutMillis: Long = 30_000): PiStackOperationDto = withTimeout(timeoutMillis) {
        while (true) {
            val operation = getOperation(operationId).data
            when (operation.status) {
                "completed" -> return@withTimeout operation
                "failed", "aborted", "dispatch_failed" -> throw IllegalStateException(
                    operation.error ?: "Pi Stack operation ${operation.status}",
                )
            }
            delay(150)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun listQuestions(status: String? = "pending"): PiStackEnvelope<List<PiStackQuestionDto>> =
        api.listQuestions(conn, status)
    suspend fun replyQuestion(
        questionId: String,
        answer: JsonElement,
        generation: String,
        idempotencyKey: String,
    ): PiStackEnvelope<PiStackQuestionResolutionDto> =
        api.replyQuestion(conn, questionId, answer, generation, idempotencyKey)
    suspend fun rejectQuestion(
        questionId: String,
        generation: String,
        idempotencyKey: String,
    ): PiStackEnvelope<PiStackQuestionResolutionDto> =
        api.rejectQuestion(conn, questionId, generation, idempotencyKey)
    suspend fun listNotifications(read: Boolean? = false): PiStackEnvelope<List<PiStackNotificationDto>> =
        api.listNotifications(conn, read)
    suspend fun readNotification(
        notificationId: String,
        generation: String,
        idempotencyKey: String,
    ): PiStackEnvelope<PiStackNotificationReadDto> =
        api.readNotification(conn, notificationId, generation, idempotencyKey)
    fun events(lastEventId: String? = null): Flow<PiStackSseFrame> = api.connectEvents(conn, lastEventId)
}

@Singleton
class PiStackTransportFactory @Inject constructor(private val api: PiStackApi) {
    fun create(server: ServerConfig): PiStackTransport = PiStackTransport(server, api)
}
