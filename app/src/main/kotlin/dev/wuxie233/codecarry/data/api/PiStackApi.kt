package dev.wuxie233.codecarry.data.api

import dev.wuxie233.codecarry.domain.model.normalizePiStackControlUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

const val PI_STACK_PROTOCOL_VERSION = 1
private const val PI_STACK_EVENT_HEARTBEAT_TIMEOUT_MS = 40_000L

data class PiStackConnection(
    val baseUrl: String,
    val authHeader: String?,
) {
    companion object {
        fun from(url: String, token: String?): PiStackConnection = PiStackConnection(
            baseUrl = normalizePiStackControlUrl(url),
            authHeader = token?.trim()?.takeIf(String::isNotEmpty)?.let { "Bearer $it" },
        )
    }
}

enum class PiStackProjectStatus { Active, Archived, Unknown }
enum class PiStackSessionState { Idle, Busy, Retry, AwaitingCommand, AwaitingSkip, Ended, Error, Unknown }
enum class PiStackMessageRole { User, Assistant, System, Unknown }
enum class PiStackMessageStatus { Streaming, Completed, Error, Aborted, Unknown }
enum class PiStackToolState { Pending, Running, Completed, Error, Unknown }

@Serializable
data class PiStackWorkerDto(
    val generation: String,
    val epoch: Long,
    val startedAt: String,
    val active: Boolean,
)

@Serializable
data class PiStackEnvelope<T>(
    val protocolVersion: Int,
    val worker: PiStackWorkerDto,
    val data: T,
    val replayed: Boolean = false,
)

@Serializable
data class PiStackEventCursorDto(
    val generation: String,
    val eventId: String? = null,
    val sequence: Long,
)

@Serializable
data class PiStackProjectDto(
    val id: String,
    val name: String,
    val directory: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
) {
    val statusKind: PiStackProjectStatus
        get() = when (status) {
            "active" -> PiStackProjectStatus.Active
            "archived" -> PiStackProjectStatus.Archived
            else -> PiStackProjectStatus.Unknown
        }
}

@Serializable
data class PiStackDirectoryEntryDto(val name: String, val path: String)

@Serializable
data class PiStackDirectoryListingDto(
    val path: String,
    val parent: String? = null,
    val entries: List<PiStackDirectoryEntryDto> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class PiStackSessionDto(
    val id: String,
    val projectId: String,
    val parentId: String? = null,
    val supervisorHandle: String? = null,
    val piSessionId: String? = null,
    val sessionFile: String? = null,
    val runtimeGeneration: Long? = null,
    val runtimeNextSequence: Long? = null,
    val activePromptId: String? = null,
    val cwd: String,
    val workerGeneration: String,
    val state: String,
    val title: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val endedAt: String? = null,
) {
    val stateKind: PiStackSessionState
        get() = when (state) {
            "idle" -> PiStackSessionState.Idle
            "busy" -> PiStackSessionState.Busy
            "retry" -> PiStackSessionState.Retry
            "awaiting_command" -> PiStackSessionState.AwaitingCommand
            "awaiting_skip" -> PiStackSessionState.AwaitingSkip
            "ended" -> PiStackSessionState.Ended
            "error" -> PiStackSessionState.Error
            else -> PiStackSessionState.Unknown
        }
}

@Serializable
data class PiStackModelSelectionDto(
    val provider: String,
    val modelId: String,
)

object PiStackStructuredPartSerializer : JsonContentPolymorphicSerializer<PiStackStructuredPartDto>(PiStackStructuredPartDto::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<PiStackStructuredPartDto> =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "text" -> PiStackStructuredPartDto.Text.serializer()
            "tool" -> PiStackStructuredPartDto.Tool.serializer()
            else -> PiStackStructuredPartDto.Unknown.serializer()
        }
}

@Serializable(with = PiStackStructuredPartSerializer::class)
sealed class PiStackStructuredPartDto {
    abstract val id: String
    abstract val type: String

    @Serializable
    data class Text(
        override val id: String,
        override val type: String = "text",
        val text: String,
    ) : PiStackStructuredPartDto()

    @Serializable
    data class Tool(
        override val id: String,
        override val type: String = "tool",
        val toolCallId: String,
        val toolName: String,
        val state: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val error: String? = null,
    ) : PiStackStructuredPartDto() {
        val stateKind: PiStackToolState
            get() = when (state) {
                "pending" -> PiStackToolState.Pending
                "running" -> PiStackToolState.Running
                "completed" -> PiStackToolState.Completed
                "error" -> PiStackToolState.Error
                else -> PiStackToolState.Unknown
            }
    }

    @Serializable
    data class Unknown(
        override val id: String = "",
        override val type: String = "unknown",
    ) : PiStackStructuredPartDto()
}

@Serializable
data class PiStackStructuredMessageDto(
    val id: String,
    val entryId: String? = null,
    val sessionId: String,
    val promptId: String? = null,
    val role: String,
    val status: String,
    val parts: List<PiStackStructuredPartDto> = emptyList(),
    val createdAt: String? = null,
    val completedAt: String? = null,
) {
    val roleKind: PiStackMessageRole
        get() = when (role) {
            "user" -> PiStackMessageRole.User
            "assistant" -> PiStackMessageRole.Assistant
            "system" -> PiStackMessageRole.System
            else -> PiStackMessageRole.Unknown
        }

    val statusKind: PiStackMessageStatus
        get() = when (status) {
            "streaming" -> PiStackMessageStatus.Streaming
            "completed" -> PiStackMessageStatus.Completed
            "error" -> PiStackMessageStatus.Error
            "aborted" -> PiStackMessageStatus.Aborted
            else -> PiStackMessageStatus.Unknown
        }
}

@Serializable
data class PiStackMessagePageDto(
    val items: List<PiStackStructuredMessageDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class PiStackOperationDto(
    val id: String,
    val kind: String,
    val status: String,
    val workerGeneration: String,
    val sessionId: String? = null,
    val teamId: String? = null,
    val taskId: String? = null,
    val supervisorHandle: String? = null,
    val piSessionId: String? = null,
    val runtimeGeneration: Long? = null,
    val promptId: String? = null,
    val command: JsonElement = JsonNull,
    val result: JsonElement? = null,
    val error: String? = null,
    val acceptedAt: String,
    val settledAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PiStackQuestionOptionDto(
    val label: String,
    val description: String? = null,
)

@Serializable
data class PiStackQuestionPayloadDto(
    val kind: String,
    val prompt: String,
    val options: List<PiStackQuestionOptionDto> = emptyList(),
    val allowFreeformInput: Boolean = false,
    val placeholder: String? = null,
)

@Serializable
data class PiStackQuestionDeliveryDto(
    val id: String,
    val questionId: String,
    val sessionId: String,
    val resolution: String,
    val answer: JsonElement? = null,
    val status: String,
    val attemptCount: Int,
    val lastError: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deliveredAt: String? = null,
)

@Serializable
data class PiStackQuestionDto(
    val id: String,
    val sessionId: String,
    val projectId: String,
    val teamId: String? = null,
    val taskId: String? = null,
    val workerGeneration: String,
    val kind: String,
    val payload: PiStackQuestionPayloadDto,
    val status: String,
    val answer: JsonElement? = null,
    val delivery: PiStackQuestionDeliveryDto? = null,
    val createdAt: String,
    val resolvedAt: String? = null,
)

@Serializable
data class PiStackQuestionResolutionDto(
    val kind: String,
    val question: PiStackQuestionDto,
    val delivery: PiStackQuestionDeliveryDto? = null,
)

@Serializable
data class PiStackControlTargetDto(
    val projectId: String? = null,
    val sessionId: String? = null,
    val teamId: String? = null,
    val taskId: String? = null,
    val questionId: String? = null,
)

@Serializable
data class PiStackNotificationDto(
    val id: String,
    val workerGeneration: String,
    val severity: String,
    val message: String,
    val target: PiStackControlTargetDto = PiStackControlTargetDto(),
    val dedupeKey: String? = null,
    val read: Boolean,
    val createdAt: String,
    val readAt: String? = null,
)

@Serializable
data class PiStackNotificationReadDto(
    val kind: String,
    val notification: PiStackNotificationDto,
)

@Serializable
data class PiStackEventScopeDto(
    val projectId: String? = null,
    val sessionId: String? = null,
    val teamId: String? = null,
    val taskId: String? = null,
    val questionId: String? = null,
    val notificationId: String? = null,
)

@Serializable
data class PiStackEventDto(
    val protocolVersion: Int,
    val generation: String,
    val eventId: String,
    val sequence: Long,
    val scope: PiStackEventScopeDto = PiStackEventScopeDto(),
    val type: String,
    val payload: JsonElement = JsonObject(emptyMap()),
    val ts: String,
)

@Serializable
data class PiStackMessageEventPayloadDto(val message: PiStackStructuredMessageDto)

@Serializable
data class PiStackMessageDeltaPayloadDto(
    val messageId: String,
    val partId: String,
    val delta: String,
)

@Serializable
data class PiStackToolEventPayloadDto(
    val messageId: String,
    val part: PiStackStructuredPartDto.Tool,
)

sealed interface PiStackSseFrame {
    data class Connected(val generation: String) : PiStackSseFrame
    data class Event(val event: PiStackEventDto) : PiStackSseFrame
    data class ResyncRequired(
        val generation: String,
        val snapshotCursor: PiStackEventCursorDto,
    ) : PiStackSseFrame
}

@Serializable
data class PiStackPermissionsCapabilityDto(
    val supported: Boolean,
    val pending: List<JsonElement> = emptyList(),
)

@Serializable
data class PiStackRuntimeCapabilityDto(
    val prompt: Boolean,
    val abort: Boolean,
    val retry: Boolean,
    val sessionPatch: List<String> = emptyList(),
    val compact: Boolean = false,
    val fork: Boolean = false,
    val commands: Boolean = false,
    val attachments: Boolean = false,
)

@Serializable
data class PiStackQuestionCapabilityDto(val reply: Boolean, val reject: Boolean)

@Serializable
data class PiStackFilesystemCapabilityDto(
    val directoryBrowse: Boolean = false,
    val defaultPath: String? = null,
)

@Serializable
data class PiStackProjectCapabilityDto(val register: Boolean = false)

@Serializable
data class PiStackSessionCapabilityDto(
    val create: Boolean = false,
    val resume: String? = null,
    val structuredHistory: Boolean = false,
    val maxHistoryPageSize: Int = 100,
    val streamingActivity: Boolean = false,
    val controls: Boolean = false,
    val archive: Boolean = false,
    val restore: Boolean = false,
    val delete: Boolean = false,
)

@Serializable
data class PiStackSelectionCapabilityDto(
    val list: Boolean = false,
    val select: Boolean = false,
)

@Serializable
data class PiStackSessionControlModelDto(
    val provider: String,
    val modelId: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val reasoning: JsonElement? = null,
)

@Serializable
data class PiStackCommandDto(
    val name: String,
    val description: String? = null,
    val source: String,
)

@Serializable
data class PiStackSessionControlsDto(
    val model: PiStackSessionControlModelDto? = null,
    val thinkingLevel: String? = null,
    val models: List<PiStackSessionControlModelDto> = emptyList(),
    val thinkingLevels: List<String> = emptyList(),
    val commands: List<PiStackCommandDto> = emptyList(),
)

@Serializable
data class PiStackAuthorityOperationDto(
    val requestId: String,
    val requestHash: String,
    val sessionId: String,
    val kind: String,
    val status: String,
    val outcome: String? = null,
    val result: JsonElement? = null,
    val error: String? = null,
    val acceptedAt: String,
    val settledAt: String? = null,
)

@Serializable
data class PiStackAuthorityRuntimeDto(
    val protocolVersion: Int,
    val daemonInstanceId: String,
    val runtimeGeneration: Long,
    val nextSequence: Long,
)

@Serializable
data class PiStackMachineSessionDto(
    val id: String,
    val projectId: String,
    val cwd: String,
    val sessionFile: String? = null,
    val parentSessionId: String? = null,
    val title: String? = null,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
    val messageCount: Int,
)

@Serializable
data class PiStackSessionControlsDescriptorDto(
    val runtime: PiStackAuthorityRuntimeDto,
    val session: PiStackMachineSessionDto,
    val controls: PiStackSessionControlsDto,
    val activeOperation: PiStackAuthorityOperationDto? = null,
)

@Serializable
data class PiStackAttachmentDto(
    val handle: String,
    val sessionId: String,
    val cwd: String,
    val kind: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val path: String,
    val createdAt: String,
)

@Serializable
data class PiStackCapabilitiesDto(
    val protocolVersion: Int,
    val permissions: PiStackPermissionsCapabilityDto,
    val runtime: PiStackRuntimeCapabilityDto,
    val questions: PiStackQuestionCapabilityDto,
    val filesystem: PiStackFilesystemCapabilityDto = PiStackFilesystemCapabilityDto(),
    val projects: PiStackProjectCapabilityDto = PiStackProjectCapabilityDto(),
    val sessions: PiStackSessionCapabilityDto = PiStackSessionCapabilityDto(),
    val models: PiStackSelectionCapabilityDto = PiStackSelectionCapabilityDto(),
    val thinking: PiStackSelectionCapabilityDto = PiStackSelectionCapabilityDto(),
)

@Serializable
data class PiStackSnapshotDataDto(
    val projects: List<PiStackProjectDto> = emptyList(),
    val sessions: List<PiStackSessionDto> = emptyList(),
    val questions: List<PiStackQuestionDto> = emptyList(),
    val notifications: List<PiStackNotificationDto> = emptyList(),
)

@Serializable
data class PiStackSnapshotDto(
    val protocolVersion: Int,
    val worker: PiStackWorkerDto,
    val cursor: PiStackEventCursorDto,
    val data: PiStackSnapshotDataDto,
)

@Serializable
private data class RegisterProjectRequest(val directory: String, val name: String? = null)

@Serializable
private data class CreateSessionRequest(val title: String? = null, val model: PiStackModelSelectionDto? = null)

@Serializable
private data class PiStackPromptRequest(
    val prompt: String,
    val attachments: List<String> = emptyList(),
)

@Serializable
private data class SessionTitleRequest(val title: String)

@Serializable
private data class AbortRequest(val reason: String? = null)

@Serializable
private data class QuestionAnswerRequest(val answer: JsonElement)

@Serializable
private data class PiStackErrorEnvelopeDto(val protocolVersion: Int, val error: PiStackErrorDto)

@Serializable
private data class PiStackErrorDto(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val requestId: String? = null,
)

@Serializable
private data class ConnectedFrameDto(val protocolVersion: Int, val generation: String)

@Serializable
private data class ResyncFrameDto(
    val protocolVersion: Int,
    val generation: String,
    val type: String,
    val snapshotCursor: PiStackEventCursorDto,
)

enum class PiStackApiErrorKind { Auth, StaleGeneration, Conflict, Unsupported, Protocol, Transport, Server }

class PiStackApiException(
    val kind: PiStackApiErrorKind,
    message: String,
    val retryable: Boolean = false,
    val status: Int? = null,
    val code: String? = null,
) : IllegalStateException(message)

@Singleton
class PiStackApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val heartbeatTimeoutMs: Long = PI_STACK_EVENT_HEARTBEAT_TIMEOUT_MS,
) {
    @Inject
    constructor(httpClient: HttpClient, json: Json) : this(httpClient, json, PI_STACK_EVENT_HEARTBEAT_TIMEOUT_MS)

    suspend fun getCapabilities(conn: PiStackConnection): PiStackEnvelope<PiStackCapabilitiesDto> =
        getEnvelope(conn, "/v1/capabilities", PiStackCapabilitiesDto.serializer()).also {
            requireProtocol(it.data.protocolVersion, "capabilities")
        }

    suspend fun getSnapshot(conn: PiStackConnection): PiStackSnapshotDto {
        val response = httpClient.get("${conn.baseUrl}/v1/control/snapshot") { applyHeaders(conn) }
        val snapshot = decodeSuccessful(response, PiStackSnapshotDto.serializer())
        requireProtocol(snapshot.protocolVersion, "snapshot")
        if (snapshot.worker.generation != snapshot.cursor.generation) {
            throw protocolError("Snapshot worker and cursor generations differ")
        }
        return snapshot
    }

    suspend fun listDirectories(conn: PiStackConnection, path: String? = null): PiStackEnvelope<PiStackDirectoryListingDto> =
        requestEnvelope(PiStackDirectoryListingDto.serializer()) {
            httpClient.get("${conn.baseUrl}/v1/directories") {
                applyHeaders(conn)
                path?.let { parameter("path", it) }
            }
        }

    suspend fun listProjects(conn: PiStackConnection): PiStackEnvelope<List<PiStackProjectDto>> =
        getEnvelope(conn, "/v1/projects", ListSerializer(PiStackProjectDto.serializer()))

    suspend fun registerProject(
        conn: PiStackConnection,
        directory: String,
        name: String? = null,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackProjectDto> = mutateEnvelope(PiStackProjectDto.serializer(), generation) {
        httpClient.post("${conn.baseUrl}/v1/projects") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(RegisterProjectRequest(directory, name))
        }
    }

    suspend fun listSessions(
        conn: PiStackConnection,
        projectId: String? = null,
    ): PiStackEnvelope<List<PiStackSessionDto>> {
        val path = projectId?.let { "/v1/projects/${it.pathSegment()}/sessions" } ?: "/v1/sessions"
        return getEnvelope(conn, path, ListSerializer(PiStackSessionDto.serializer()))
    }

    suspend fun getSession(conn: PiStackConnection, sessionId: String): PiStackEnvelope<PiStackSessionDto> =
        getEnvelope(conn, "/v1/sessions/${sessionId.pathSegment()}", PiStackSessionDto.serializer())

    suspend fun getSessionControls(
        conn: PiStackConnection,
        sessionId: String,
    ): PiStackEnvelope<PiStackSessionControlsDescriptorDto> =
        getEnvelope(conn, "/v1/sessions/${sessionId.pathSegment()}/controls", PiStackSessionControlsDescriptorDto.serializer())

    suspend fun createSession(
        conn: PiStackConnection,
        projectId: String,
        title: String? = null,
        model: PiStackModelSelectionDto? = null,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackSessionDto> = mutateEnvelope(PiStackSessionDto.serializer(), generation) {
        httpClient.post("${conn.baseUrl}/v1/projects/${projectId.pathSegment()}/sessions") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(CreateSessionRequest(title, model))
        }
    }

    suspend fun getMessages(
        conn: PiStackConnection,
        sessionId: String,
        limit: Int = 50,
        before: String? = null,
    ): PiStackEnvelope<PiStackMessagePageDto> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return requestEnvelope(PiStackMessagePageDto.serializer()) {
            httpClient.get("${conn.baseUrl}/v1/sessions/${sessionId.pathSegment()}/history") {
                applyHeaders(conn)
                parameter("limit", limit)
                before?.let { parameter("before", it) }
            }
        }
    }

    suspend fun prompt(
        conn: PiStackConnection,
        sessionId: String,
        prompt: String,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
        attachments: List<String> = emptyList(),
    ): PiStackEnvelope<PiStackOperationDto> = mutateEnvelope(PiStackOperationDto.serializer(), generation) {
        httpClient.post("${conn.baseUrl}/v1/sessions/${sessionId.pathSegment()}/prompt") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(PiStackPromptRequest(prompt, attachments))
        }
    }

    suspend fun renameSession(
        conn: PiStackConnection,
        sessionId: String,
        title: String,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackOperationDto> = mutateEnvelope(PiStackOperationDto.serializer(), generation) {
        httpClient.patch("${conn.baseUrl}/v1/sessions/${sessionId.pathSegment()}") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(SessionTitleRequest(title))
        }
    }

    suspend fun archiveSession(conn: PiStackConnection, sessionId: String, generation: String, idempotencyKey: String = UUID.randomUUID().toString()) =
        emptySessionMutation(conn, sessionId, "archive", generation, idempotencyKey)

    suspend fun restoreSession(conn: PiStackConnection, sessionId: String, generation: String, idempotencyKey: String = UUID.randomUUID().toString()) =
        emptySessionMutation(conn, sessionId, "restore", generation, idempotencyKey)

    suspend fun deleteSession(
        conn: PiStackConnection,
        sessionId: String,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackOperationDto> = mutateEnvelope(PiStackOperationDto.serializer(), generation) {
        httpClient.delete("${conn.baseUrl}/v1/sessions/${sessionId.pathSegment()}") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(JsonObject(emptyMap()))
        }
    }

    suspend fun selectModel(conn: PiStackConnection, sessionId: String, provider: String, modelId: String, generation: String, idempotencyKey: String = UUID.randomUUID().toString()) =
        sessionMutation(conn, sessionId, "model", generation, idempotencyKey, JsonObject(mapOf(
            "provider" to JsonPrimitive(provider),
            "modelId" to JsonPrimitive(modelId),
        )))

    suspend fun selectThinking(conn: PiStackConnection, sessionId: String, level: String, generation: String, idempotencyKey: String = UUID.randomUUID().toString()) =
        sessionMutation(conn, sessionId, "thinking", generation, idempotencyKey, JsonObject(mapOf("level" to JsonPrimitive(level))))

    suspend fun compactSession(conn: PiStackConnection, sessionId: String, instructions: String? = null, generation: String, idempotencyKey: String = UUID.randomUUID().toString()) =
        sessionMutation(conn, sessionId, "compact", generation, idempotencyKey, JsonObject(
            instructions?.let { mapOf("instructions" to JsonPrimitive(it)) }.orEmpty(),
        ))

    suspend fun forkSession(conn: PiStackConnection, sessionId: String, entryId: String, generation: String, idempotencyKey: String = UUID.randomUUID().toString()) =
        sessionMutation(conn, sessionId, "fork", generation, idempotencyKey, JsonObject(mapOf("entryId" to JsonPrimitive(entryId))))

    suspend fun executeCommand(conn: PiStackConnection, sessionId: String, text: String, generation: String, idempotencyKey: String = UUID.randomUUID().toString()) =
        sessionMutation(conn, sessionId, "commands", generation, idempotencyKey, JsonObject(mapOf("text" to JsonPrimitive(text))))

    suspend fun uploadAttachment(
        conn: PiStackConnection,
        sessionId: String,
        kind: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackAttachmentDto> = mutateEnvelope(PiStackAttachmentDto.serializer(), generation) {
        httpClient.post("${conn.baseUrl}/v1/sessions/${sessionId.pathSegment()}/attachments") {
            applyMutationIdentityHeaders(conn, generation, idempotencyKey)
            contentType(ContentType.Application.OctetStream)
            header("X-Attachment-Kind", kind)
            header("X-Attachment-Name", name)
            header("X-Attachment-Mime", mimeType)
            setBody(bytes)
        }
    }

    suspend fun abort(
        conn: PiStackConnection,
        sessionId: String,
        reason: String? = null,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackOperationDto> = mutateEnvelope(PiStackOperationDto.serializer(), generation) {
        httpClient.post("${conn.baseUrl}/v1/sessions/${sessionId.pathSegment()}/abort") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(AbortRequest(reason))
        }
    }

    suspend fun listQuestions(
        conn: PiStackConnection,
        status: String? = "pending",
    ): PiStackEnvelope<List<PiStackQuestionDto>> = requestEnvelope(ListSerializer(PiStackQuestionDto.serializer())) {
        httpClient.get("${conn.baseUrl}/v1/questions") {
            applyHeaders(conn)
            status?.let { parameter("status", it) }
        }
    }

    suspend fun replyQuestion(
        conn: PiStackConnection,
        questionId: String,
        answer: JsonElement,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackQuestionResolutionDto> = mutateEnvelope(PiStackQuestionResolutionDto.serializer(), generation) {
        require(answer.isQuestionAnswer()) { "answer must be text or a string array" }
        httpClient.post("${conn.baseUrl}/v1/questions/${questionId.pathSegment()}/reply") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(QuestionAnswerRequest(answer))
        }
    }

    suspend fun rejectQuestion(
        conn: PiStackConnection,
        questionId: String,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackQuestionResolutionDto> = mutateEnvelope(PiStackQuestionResolutionDto.serializer(), generation) {
        httpClient.post("${conn.baseUrl}/v1/questions/${questionId.pathSegment()}/reject") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(JsonObject(emptyMap()))
        }
    }

    suspend fun listNotifications(
        conn: PiStackConnection,
        read: Boolean? = false,
    ): PiStackEnvelope<List<PiStackNotificationDto>> = requestEnvelope(ListSerializer(PiStackNotificationDto.serializer())) {
        httpClient.get("${conn.baseUrl}/v1/notifications") {
            applyHeaders(conn)
            read?.let { parameter("read", it) }
        }
    }

    suspend fun readNotification(
        conn: PiStackConnection,
        notificationId: String,
        generation: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): PiStackEnvelope<PiStackNotificationReadDto> = mutateEnvelope(PiStackNotificationReadDto.serializer(), generation) {
        httpClient.post("${conn.baseUrl}/v1/notifications/${notificationId.pathSegment()}/read") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(JsonObject(emptyMap()))
        }
    }

    suspend fun getOperation(conn: PiStackConnection, operationId: String): PiStackEnvelope<PiStackOperationDto> =
        getEnvelope(conn, "/v1/operations/${operationId.pathSegment()}", PiStackOperationDto.serializer())

    fun connectEvents(conn: PiStackConnection, lastEventId: String? = null): Flow<PiStackSseFrame> = flow {
        val statement = httpClient.prepareGet("${conn.baseUrl}/v1/events") {
            applyHeaders(conn)
            header(HttpHeaders.Accept, "text/event-stream")
            lastEventId?.let { header("Last-Event-ID", it) }
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
        }
        statement.execute { response ->
            if (!response.status.isSuccess()) throw response.toException()
            val channel = response.bodyAsChannel()
            var id: String? = null
            var eventType: String? = null
            val data = mutableListOf<String>()

            suspend fun emitFrame() {
                if (data.isEmpty()) return
                val body = data.joinToString("\n")
                val frame = parseSseFrame(id, eventType, body)
                id = null
                eventType = null
                data.clear()
                frame?.let { emit(it) }
            }

            while (!channel.isClosedForRead) {
                val line = withTimeoutOrNull(heartbeatTimeoutMs) { channel.readUTF8Line() }
                    ?: throw PiStackApiException(PiStackApiErrorKind.Transport, "Pi Stack event stream heartbeat timed out", retryable = true)
                when {
                    line.isEmpty() -> emitFrame()
                    line.startsWith(":") -> Unit
                    line.startsWith("id:") -> id = line.substringAfter(':').trim()
                    line.startsWith("event:") -> eventType = line.substringAfter(':').trim()
                    line.startsWith("data:") -> data += line.substringAfter(':').trimStart()
                }
            }
            emitFrame()
        }
    }

    private fun parseSseFrame(id: String?, eventType: String?, body: String): PiStackSseFrame? = try {
        when (eventType) {
            "system.connected" -> json.decodeFromString(ConnectedFrameDto.serializer(), body).let { value ->
                requireProtocol(value.protocolVersion, "SSE connected frame")
                PiStackSseFrame.Connected(value.generation)
            }
            "system.resync_required" -> json.decodeFromString(ResyncFrameDto.serializer(), body).let { value ->
                requireProtocol(value.protocolVersion, "SSE resync frame")
                if (id != null) throw protocolError("SSE resync frame must not carry an event id")
                PiStackSseFrame.ResyncRequired(value.generation, value.snapshotCursor)
            }
            else -> json.decodeFromString(PiStackEventDto.serializer(), body).let { value ->
                requireProtocol(value.protocolVersion, "SSE event")
                if (id != null && id != value.eventId) throw protocolError("SSE id does not match eventId")
                PiStackSseFrame.Event(value)
            }
        }
    } catch (error: PiStackApiException) {
        throw error
    } catch (error: Exception) {
        throw protocolError("Invalid Pi Stack SSE frame", error)
    }

    private suspend fun emptySessionMutation(
        conn: PiStackConnection,
        sessionId: String,
        action: String,
        generation: String,
        idempotencyKey: String,
    ): PiStackEnvelope<PiStackOperationDto> = sessionMutation(
        conn,
        sessionId,
        action,
        generation,
        idempotencyKey,
        JsonObject(emptyMap()),
    )

    private suspend fun sessionMutation(
        conn: PiStackConnection,
        sessionId: String,
        action: String,
        generation: String,
        idempotencyKey: String,
        body: JsonObject,
    ): PiStackEnvelope<PiStackOperationDto> = mutateEnvelope(PiStackOperationDto.serializer(), generation) {
        httpClient.post("${conn.baseUrl}/v1/sessions/${sessionId.pathSegment()}/$action") {
            applyMutationHeaders(conn, generation, idempotencyKey)
            setBody(body)
        }
    }

    private suspend fun <T> getEnvelope(
        conn: PiStackConnection,
        path: String,
        serializer: KSerializer<T>,
    ): PiStackEnvelope<T> = requestEnvelope(serializer) {
        httpClient.get("${conn.baseUrl}$path") { applyHeaders(conn) }
    }

    private suspend fun <T> requestEnvelope(
        serializer: KSerializer<T>,
        request: suspend () -> HttpResponse,
    ): PiStackEnvelope<T> = decodeSuccessful(request(), PiStackEnvelope.serializer(serializer)).also {
        requireProtocol(it.protocolVersion, "response envelope")
    }

    private suspend fun <T> mutateEnvelope(
        serializer: KSerializer<T>,
        generation: String,
        request: suspend () -> HttpResponse,
    ): PiStackEnvelope<T> = requestEnvelope(serializer, request).also { envelope ->
        if (envelope.worker.generation != generation) {
            throw PiStackApiException(
                kind = PiStackApiErrorKind.StaleGeneration,
                message = "Pi Stack response belongs to a different worker generation",
                code = "stale_generation",
            )
        }
    }

    private suspend fun <T> decodeSuccessful(response: HttpResponse, serializer: KSerializer<T>): T {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw response.toException(body)
        return try {
            json.decodeFromString(serializer, body)
        } catch (error: Exception) {
            throw protocolError("Invalid Pi Stack response", error)
        }
    }

    private suspend fun HttpResponse.toException(): PiStackApiException = toException(bodyAsText())

    private fun HttpResponse.toException(body: String): PiStackApiException {
        val error = runCatching { json.decodeFromString(PiStackErrorEnvelopeDto.serializer(), body) }.getOrNull()
        error?.let { requireProtocol(it.protocolVersion, "error envelope") }
        val code = error?.error?.code
        val kind = when {
            status == HttpStatusCode.Unauthorized || code == "auth_invalid" -> PiStackApiErrorKind.Auth
            code == "stale_generation" -> PiStackApiErrorKind.StaleGeneration
            code == "unsupported" -> PiStackApiErrorKind.Unsupported
            status == HttpStatusCode.Conflict -> PiStackApiErrorKind.Conflict
            status.value >= 500 -> PiStackApiErrorKind.Server
            else -> PiStackApiErrorKind.Server
        }
        return PiStackApiException(
            kind = kind,
            message = error?.error?.message ?: "Pi Stack request failed with HTTP ${status.value}",
            retryable = error?.error?.retryable ?: (status.value >= 500),
            status = status.value,
            code = code,
        )
    }

    private fun HttpRequestBuilder.applyHeaders(conn: PiStackConnection) {
        conn.authHeader?.let { header(HttpHeaders.Authorization, it) }
    }

    private fun HttpRequestBuilder.applyMutationHeaders(
        conn: PiStackConnection,
        generation: String,
        idempotencyKey: String,
    ) {
        applyMutationIdentityHeaders(conn, generation, idempotencyKey)
        contentType(ContentType.Application.Json)
    }

    private fun HttpRequestBuilder.applyMutationIdentityHeaders(
        conn: PiStackConnection,
        generation: String,
        idempotencyKey: String,
    ) {
        require(generation.isNotBlank()) { "generation must not be blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        applyHeaders(conn)
        header("Idempotency-Key", idempotencyKey)
        header("X-Pi-Worker-Generation", generation)
    }

    private fun requireProtocol(version: Int, source: String) {
        if (version != PI_STACK_PROTOCOL_VERSION) throw protocolError("$source protocolVersion is not 1")
    }

    private fun protocolError(message: String, cause: Throwable? = null): PiStackApiException =
        PiStackApiException(PiStackApiErrorKind.Protocol, message).also { if (cause != null) it.initCause(cause) }
}

private fun String.pathSegment(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

private fun JsonElement.isQuestionAnswer(): Boolean = when (this) {
    is JsonPrimitive -> isString && contentOrNull != null
    is JsonArray -> all { value -> value is JsonPrimitive && value.isString }
    else -> false
}
