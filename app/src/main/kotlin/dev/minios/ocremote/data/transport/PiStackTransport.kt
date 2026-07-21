package dev.minios.ocremote.data.transport

import dev.minios.ocremote.data.api.PiStackApi
import dev.minios.ocremote.data.api.PiStackConnection
import dev.minios.ocremote.data.api.PiStackDirectoryListingDto
import dev.minios.ocremote.data.api.PiStackEnvelope
import dev.minios.ocremote.data.api.PiStackMessagePageDto
import dev.minios.ocremote.data.api.PiStackModelSelectionDto
import dev.minios.ocremote.data.api.PiStackOperationDto
import dev.minios.ocremote.data.api.PiStackProjectDto
import dev.minios.ocremote.data.api.PiStackSessionDto
import dev.minios.ocremote.data.api.PiStackSnapshotDto
import dev.minios.ocremote.data.api.PiStackSseFrame
import dev.minios.ocremote.data.api.PiStackQuestionDto
import dev.minios.ocremote.data.api.PiStackQuestionResolutionDto
import dev.minios.ocremote.data.api.PiStackNotificationDto
import dev.minios.ocremote.data.api.PiStackNotificationReadDto
import dev.minios.ocremote.domain.model.ServerConfig
import kotlinx.coroutines.flow.Flow
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
    suspend fun createSession(
        projectId: String,
        title: String?,
        model: PiStackModelSelectionDto?,
        generation: String,
        idempotencyKey: String,
    ) = api.createSession(conn, projectId, title, model, generation, idempotencyKey)
    suspend fun getMessages(sessionId: String, limit: Int = 50, before: String? = null): PiStackEnvelope<PiStackMessagePageDto> =
        api.getMessages(conn, sessionId, limit, before)
    suspend fun prompt(sessionId: String, prompt: String, generation: String, idempotencyKey: String): PiStackEnvelope<PiStackOperationDto> =
        api.prompt(conn, sessionId, prompt, generation, idempotencyKey)
    suspend fun abort(sessionId: String, reason: String?, generation: String, idempotencyKey: String): PiStackEnvelope<PiStackOperationDto> =
        api.abort(conn, sessionId, reason, generation, idempotencyKey)
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
