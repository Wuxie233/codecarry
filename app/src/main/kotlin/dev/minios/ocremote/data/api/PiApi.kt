package dev.minios.ocremote.data.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

const val PI_PROTOCOL_VERSION = 1
private const val PI_HEARTBEAT_TIMEOUT_MS = 40_000L

data class PiConnection(
    val baseUrl: String,
    val authHeader: String?,
) {
    companion object {
        fun from(url: String, token: String?): PiConnection = PiConnection(
            baseUrl = url.trimEnd('/'),
            authHeader = token?.trim()?.takeIf { it.isNotEmpty() }?.let { "Bearer $it" },
        )
    }
}

@Singleton
class PiApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val heartbeatTimeoutMs: Long = PI_HEARTBEAT_TIMEOUT_MS,
) {
    @Inject
    constructor(httpClient: HttpClient, json: Json) : this(httpClient, json, PI_HEARTBEAT_TIMEOUT_MS)

    suspend fun listRoundtables(conn: PiConnection): List<PiRoundtableDto> {
        val body = httpClient.prepareGet("${conn.baseUrl}/roundtables") {
            applyPiHeaders(conn)
        }.execute { response ->
            if (!response.status.isSuccess()) throw PiApiException("GET /roundtables failed with HTTP ${response.status.value}")
            response.bodyAsText()
        }

        val root = json.parseToJsonElement(body)
        val serializer = ListSerializer(PiRoundtableDto.serializer())
        return when (root) {
            is JsonArray -> json.decodeFromJsonElement(serializer, root)
            is JsonObject -> {
                val roundtables = root["roundtables"] ?: root["items"] ?: root["data"]
                when (roundtables) {
                    is JsonArray -> json.decodeFromJsonElement(serializer, roundtables)
                    else -> listOf(json.decodeFromJsonElement(PiRoundtableDto.serializer(), root))
                }
            }
            else -> emptyList()
        }
    }

    suspend fun createRoundtable(conn: PiConnection, request: PiCreateRoundtableRequest): PiRoundtableDto {
        return httpClient.post("${conn.baseUrl}/roundtables") {
            applyPiHeaders(conn)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyFromSuccessfulResponse("POST /roundtables")
    }

    suspend fun proposeLineup(conn: PiConnection, request: PiLineupProposalRequest): PiLineupProposalDto {
        return httpClient.post("${conn.baseUrl}/roundtables/lineup-proposal") {
            applyPiHeaders(conn)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyFromSuccessfulResponse("POST /roundtables/lineup-proposal")
    }

    suspend fun getRoundtable(conn: PiConnection, roundId: String): PiRoundtableDto {
        return httpClient.prepareGet("${conn.baseUrl}/roundtables/$roundId") {
            applyPiHeaders(conn)
        }.execute { response ->
            if (!response.status.isSuccess()) throw PiApiException("GET /roundtables/:id failed with HTTP ${response.status.value}")
            json.decodeFromString(PiRoundtableDto.serializer(), response.bodyAsText())
        }
    }

    suspend fun archiveRoundtable(conn: PiConnection, roundId: String): PiRoundtableDto {
        return httpClient.post("${conn.baseUrl}/roundtables/$roundId/archive") {
            applyPiHeaders(conn)
            contentType(ContentType.Application.Json)
        }.bodyFromSuccessfulResponse("POST /roundtables/:id/archive")
    }

    suspend fun deleteRoundtable(conn: PiConnection, roundId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/roundtables/$roundId") {
            applyPiHeaders(conn)
        }
        return response.status.isSuccess()
    }

    suspend fun sendCommand(conn: PiConnection, roundId: String, command: PiCommandRequest): Boolean {
        val response = httpClient.post("${conn.baseUrl}/roundtables/$roundId/command") {
            applyPiHeaders(conn)
            contentType(ContentType.Application.Json)
            setBody(command)
        }
        return response.status.isSuccess()
    }

    suspend fun getTranscript(conn: PiConnection, roundId: String): PiTranscriptDto {
        return httpClient.prepareGet("${conn.baseUrl}/roundtables/$roundId/transcript") {
            applyPiHeaders(conn)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw PiApiException("GET /roundtables/:id/transcript failed with HTTP ${response.status.value}")
            }
            json.decodeFromString(PiTranscriptDto.serializer(), response.bodyAsText())
        }
    }

    suspend fun getTranscriptMarkdown(conn: PiConnection, roundId: String): String {
        return httpClient.prepareGet("${conn.baseUrl}/roundtables/$roundId/transcript?format=md") {
            applyPiHeaders(conn)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw PiApiException("GET /roundtables/:id/transcript?format=md failed with HTTP ${response.status.value}")
            }
            response.bodyAsText()
        }
    }

    suspend fun cancelRoundtable(conn: PiConnection, roundId: String): Boolean {
        val response = httpClient.post("${conn.baseUrl}/roundtables/$roundId/cancel") {
            applyPiHeaders(conn)
            contentType(ContentType.Application.Json)
            setBody(PiCancelRequest(protocolVersion = PI_PROTOCOL_VERSION, roundId = roundId))
        }
        return response.status.isSuccess()
    }


    suspend fun listPersonas(conn: PiConnection): List<PiPersonaDto> {
        val body = httpClient.prepareGet("${conn.baseUrl}/personas") {
            applyPiHeaders(conn)
        }.execute { response ->
            if (!response.status.isSuccess()) throw PiApiException("GET /personas failed with HTTP ${response.status.value}")
            response.bodyAsText()
        }
        return decodePersonaList(body)
    }

    suspend fun listCatalog(conn: PiConnection): List<PiCatalogEntryDto> {
        val body = httpClient.prepareGet("${conn.baseUrl}/catalog") {
            applyPiHeaders(conn)
        }.execute { response ->
            if (!response.status.isSuccess()) throw PiApiException("GET /catalog failed with HTTP ${response.status.value}")
            response.bodyAsText()
        }
        val root = json.parseToJsonElement(body)
        val serializer = ListSerializer(PiCatalogEntryDto.serializer())
        return when (root) {
            is JsonArray -> json.decodeFromJsonElement(serializer, root)
            is JsonObject -> {
                val items = root["items"] ?: root["catalog"] ?: root["data"]
                when (items) {
                    is JsonArray -> json.decodeFromJsonElement(serializer, items)
                    else -> root["item"]?.let { listOf(json.decodeFromJsonElement(PiCatalogEntryDto.serializer(), it)) } ?: listOf(json.decodeFromJsonElement(PiCatalogEntryDto.serializer(), root))
                }
            }
            else -> emptyList()
        }
    }

    suspend fun getPersona(conn: PiConnection, personaId: String): PiPersonaDto {
        return httpClient.get("${conn.baseUrl}/personas/$personaId") {
            applyPiHeaders(conn)
        }.bodyFromPersonaEnvelope("GET /personas/:id")
    }

    suspend fun createPersona(conn: PiConnection, persona: PiPersonaDto): PiPersonaDto {
        return httpClient.post("${conn.baseUrl}/personas") {
            applyPiHeaders(conn)
            contentType(ContentType.Application.Json)
            setBody(persona)
        }.bodyFromPersonaEnvelope("POST /personas")
    }

    suspend fun updatePersona(conn: PiConnection, personaId: String, persona: PiPersonaDto): PiPersonaDto {
        return httpClient.put("${conn.baseUrl}/personas/$personaId") {
            applyPiHeaders(conn)
            contentType(ContentType.Application.Json)
            setBody(persona.copy(id = personaId))
        }.bodyFromPersonaEnvelope("PUT /personas/:id")
    }

    suspend fun deletePersona(conn: PiConnection, personaId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/personas/$personaId") {
            applyPiHeaders(conn)
        }
        return response.status.isSuccess()
    }

    suspend fun generatePersona(conn: PiConnection, request: PiGeneratePersonaRequest): PiPersonaDto {
        return httpClient.post("${conn.baseUrl}/personas/generate") {
            applyPiHeaders(conn)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyFromPersonaEnvelope("POST /personas/generate")
    }

    fun connectEvents(
        conn: PiConnection,
        roundId: String,
        lastEventId: Long? = null,
    ): Flow<RoundtableSseEvent> = flow {
        val statement = httpClient.prepareGet("${conn.baseUrl}/roundtables/$roundId/events") {
            applyPiHeaders(conn)
            header(HttpHeaders.Accept, "text/event-stream")
            lastEventId?.let { header("Last-Event-ID", it.toString()) }
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
        }

        statement.execute { response ->
            if (response.status == HttpStatusCode.Unauthorized) {
                throw SseAuthException("Pi Roundtable authentication failed (401)")
            }
            if (!response.status.isSuccess()) {
                throw SseConnectionException("HTTP ${response.status.value}")
            }

            val channel = response.bodyAsChannel()
            val dataLines = mutableListOf<String>()

            while (true) {
                val line = withTimeoutOrNull(heartbeatTimeoutMs) { channel.readUTF8Line() } ?: break
                if (line.isEmpty()) {
                    val data = dataLines.joinToString(separator = "\n")
                    dataLines.clear()
                    if (data.isNotBlank()) {
                        parseEvent(data)?.let { emit(it) }
                    }
                } else if (line.startsWith("data: ")) {
                    dataLines += line.substring(6)
                } else if (line.startsWith("data:")) {
                    dataLines += line.substring(5)
                }
                if (channel.isClosedForRead) break
            }

            val trailingData = dataLines.joinToString(separator = "\n")
            if (trailingData.isNotBlank()) {
                parseEvent(trailingData)?.let { emit(it) }
            }
        }
    }


    private fun decodePersonaList(body: String): List<PiPersonaDto> {
        val root = json.parseToJsonElement(body)
        val serializer = ListSerializer(PiPersonaDto.serializer())
        return when (root) {
            is JsonArray -> json.decodeFromJsonElement(serializer, root)
            is JsonObject -> {
                val personas = root["personas"] ?: root["items"] ?: root["data"]
                when (personas) {
                    is JsonArray -> json.decodeFromJsonElement(serializer, personas)
                    else -> root["item"]?.let { listOf(json.decodeFromJsonElement(PiPersonaDto.serializer(), it)) } ?: listOf(json.decodeFromJsonElement(PiPersonaDto.serializer(), root))
                }
            }
            else -> emptyList()
        }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.bodyFromPersonaEnvelope(path: String): PiPersonaDto {
        if (!status.isSuccess()) throw PiApiException("$path failed with HTTP ${status.value}")
        val root = json.parseToJsonElement(bodyAsText())
        val item = (root as? JsonObject)?.get("item") ?: root
        return json.decodeFromJsonElement(PiPersonaDto.serializer(), item)
    }

    private fun parseEvent(data: String): RoundtableSseEvent? = runCatching {
        json.decodeFromString(RoundtableSseEvent.serializer(), data)
    }.getOrNull()

    private fun io.ktor.client.request.HttpRequestBuilder.applyPiHeaders(conn: PiConnection) {
        conn.authHeader?.let { header(HttpHeaders.Authorization, it) }
    }

    private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.bodyFromSuccessfulResponse(path: String): T {
        if (!status.isSuccess()) throw PiApiException("$path failed with HTTP ${status.value}")
        return bodyAsText().let { body -> json.decodeFromString(body) }
    }
}

class PiApiException(message: String) : IllegalStateException(message)

@Serializable
data class RoundtableSseEvent(
    val protocolVersion: Int,
    val eventId: Long,
    val roundId: String,
    val turnId: String? = null,
    val sequence: Long,
    val type: String,
    val author: PiAuthorDto,
    val payload: JsonElement = JsonObject(emptyMap()),
    val ts: String,
)

@Serializable
data class PiAuthorDto(
    val id: String,
    val name: String,
    val mbti: String,
    val role: String,
    val colorSeed: JsonElement,
) {
    fun colorSeedText(): String = colorSeed.jsonPrimitive.contentOrNull ?: colorSeed.toString()
}

@Serializable
data class PiRoundtableDto(
    val id: String? = null,
    val roundId: String? = null,
    val title: String? = null,
    val roundTitle: String? = null,
    val topic: String? = null,
    val status: String? = null,
    val directory: String? = null,
    val roundCount: Int = 0,
    val roster: List<PiRosterSummaryDto> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val archivedAt: String? = null,
    val templateOf: String? = null,
)

@Serializable
data class PiRosterSummaryDto(
    val id: String,
    val name: String,
    val role: String,
    val colorSeed: String,
)

@Serializable
data class PiCreateRoundtableRequest(
    val protocolVersion: Int = PI_PROTOCOL_VERSION,
    val topic: String,
    val roundTitle: String? = null,
    val initialMessage: String? = null,
    val templateOf: String? = null,
    val roster: List<PiPersonaDto>? = null,
    val moderator: PiPersonaDto? = null,
    val limits: PiRoundLimitsDto? = null,
    val speakerPolicy: PiSpeakerPolicyDto? = null,
)

@Serializable
data class PiLineupProposalRequest(
    val protocolVersion: Int = PI_PROTOCOL_VERSION,
    val topic: String,
    val size: Int? = null,
    val speakerPolicy: PiSpeakerPolicyDto? = null,
)

@Serializable
data class PiLineupProposalDto(
    val protocolVersion: Int = PI_PROTOCOL_VERSION,
    val topic: String,
    val speakerPolicy: PiSpeakerPolicyDto = PiSpeakerPolicyDto(mode = "moderator_routed"),
    val items: List<PiLineupProposalItemDto> = emptyList(),
)

@Serializable
data class PiLineupProposalItemDto(
    val persona: PiPersonaDto,
    val reason: String,
)

@Serializable
data class PiCommandRequest(
    val protocolVersion: Int = PI_PROTOCOL_VERSION,
    val roundId: String,
    val command: String,
    val clientCommandId: String = UUID.randomUUID().toString(),
    val ts: String? = null,
    val note: String? = null,
    val reason: String? = null,
    val wrapUpInstruction: String? = null,
    val target: PiCommandTargetDto? = null,
    val instruction: String? = null,
    val persona: PiPersonaDto? = null,
    val targetPersonaId: String? = null,
    val participant: PiCommandParticipantDto? = null,
    val content: String? = null,
    val speakerPolicy: PiSpeakerPolicyDto? = null,
    val arguments: String? = null,
)

@Serializable
data class PiCancelRequest(
    val protocolVersion: Int = PI_PROTOCOL_VERSION,
    val roundId: String,
)

@Serializable
data class PiCommandTargetDto(
    val turnId: String? = null,
    val topicNodeId: String? = null,
    val quote: String? = null,
)

@Serializable
data class PiCommandParticipantDto(
    val id: String,
    val name: String,
    val role: String = "user",
)

@Serializable
data class PiGeneratePersonaRequest(
    val protocolVersion: Int = PI_PROTOCOL_VERSION,
    val requirement: String,
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
data class PiPersonaDto(
    val id: String? = null,
    val name: String,
    val mbti: String,
    val stancePrompt: String,
    val style: String,
    val actionTagPrefs: List<String>,
    val provider: String,
    val model: String,
    val fallback: List<PiModelRefDto> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
data class PiCatalogEntryDto(
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val api: String,
    val models: List<PiCatalogModelDto> = emptyList(),
    val fallback: List<PiModelRefDto> = emptyList(),
    val enabled: Boolean = true,
    val validation: PiCatalogValidationDto,
)

@Serializable
data class PiCatalogModelDto(
    val id: String,
    val displayName: String,
    val contextWindow: Int? = null,
    val supportsStreaming: Boolean? = null,
    val enabled: Boolean = true,
)

@Serializable
data class PiCatalogValidationDto(
    val status: String,
    val checkedAt: String? = null,
    val message: String? = null,
    val streamingChecked: Boolean? = null,
)

@Serializable
data class PiTranscriptDto(
    val protocolVersion: Int,
    val roundId: String,
    val events: List<RoundtableSseEvent> = emptyList(),
    val commands: List<JsonElement> = emptyList(),
    val assembled: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class PiSpeakerPolicyDto(
    val mode: String,
    val maxTurnsPerRound: Int? = null,
    val allowInterruptions: Boolean? = null,
    val maxConsecutiveTurnsPerPersona: Int? = null,
)

@Serializable
data class PiRoundLimitsDto(
    val maxTurnsPerRound: Int? = null,
    val maxRetriesPerTurn: Int? = null,
    val maxRoundMs: Long? = null,
    val maxTranscriptBytes: Long? = null,
)

@Serializable
data class PiModelRefDto(
    val providerId: String,
    val model: String,
)

@Serializable
data class RoundStartPayloadDto(
    val topic: String,
    val roundTitle: String? = null,
    val speakerPolicy: PiSpeakerPolicyDto,
    val participantIds: List<String>,
    val moderatorId: String,
    val limits: PiRoundLimitsDto? = null,
)

@Serializable
data class AgentTurnStartPayloadDto(
    val personaId: String,
    val providerId: String,
    val model: String,
    val attempt: Int,
    val actionTag: String? = null,
    val speakerPolicyMode: String? = null,
    val reason: String? = null,
)

@Serializable
data class MessageDeltaPayloadDto(
    val chunk: String,
    val deltaIndex: Int,
    val charStart: Int? = null,
    val encoding: String? = null,
)

@Serializable
data class MessageEndPayloadDto(
    val deltaCount: Int,
    val finalText: String? = null,
    val contentSha256: String? = null,
    val finishReason: String? = null,
)

@Serializable
data class ModeratorSynthesisPayloadDto(
    val markdownBody: String,
    val nextQuestion: String,
    val coveredTurnIds: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
)

@Serializable
data class AwaitingCommandPayloadDto(
    val prompt: String,
    val allowedCommands: List<String>,
    val commandEndpoint: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class AgentRetryPayloadDto(
    val personaId: String,
    val providerId: String,
    val model: String,
    val attempt: Int,
    val maxAttempts: Int? = null,
    val reason: String,
    val retryAfterMs: Long,
)

@Serializable
data class AgentFallbackPayloadDto(
    val personaId: String,
    val from: PiModelRefDto,
    val to: PiModelRefDto,
    val attempt: Int,
    val reason: String,
)

@Serializable
data class AgentErrorPayloadDto(
    val personaId: String,
    val providerId: String,
    val model: String,
    val attempt: Int,
    val reason: String,
    val errorCode: String? = null,
    val recoverable: Boolean,
)

@Serializable
data class AwaitingSkipPayloadDto(
    val personaId: String,
    val providerId: String,
    val model: String,
    val attempt: Int,
    val reason: String,
    val skipCommand: String,
)

@Serializable
data class RoundEndPayloadDto(
    val reason: String,
    val finalSummaryMarkdown: String? = null,
    val openQuestions: List<String> = emptyList(),
    val endedByCommandId: String? = null,
    val turnCount: Int? = null,
    val summaryKind: String? = null,
)

@Serializable
data class ErrorPayloadDto(
    val code: String,
    val message: String,
    val severity: String,
    val retryable: Boolean? = null,
    val relatedEventId: Long? = null,
)

internal fun PiRoundtableDto.resolvedId(): String? = id ?: roundId

internal fun JsonElement.asObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> = this[name]?.jsonArray?.toList() ?: emptyList()

internal fun JsonObject.primitiveStringMap(): Map<String, String> = mapValues { (_, value) ->
    (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
}
