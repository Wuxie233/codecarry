package dev.minios.ocremote.data.transport

import dev.minios.ocremote.data.api.AgentErrorPayloadDto
import dev.minios.ocremote.data.api.AgentFallbackPayloadDto
import dev.minios.ocremote.data.api.AgentRetryPayloadDto
import dev.minios.ocremote.data.api.AgentTurnStartPayloadDto
import dev.minios.ocremote.data.api.AwaitingCommandPayloadDto
import dev.minios.ocremote.data.api.AwaitingSkipPayloadDto
import dev.minios.ocremote.data.api.ErrorPayloadDto
import dev.minios.ocremote.data.api.MessageDeltaPayloadDto
import dev.minios.ocremote.data.api.MessageEndPayloadDto
import dev.minios.ocremote.data.api.ModeratorSynthesisPayloadDto
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PiCommandParticipantDto
import dev.minios.ocremote.data.api.PiCommandRequest
import dev.minios.ocremote.data.api.PiConnection
import dev.minios.ocremote.data.api.PiCreateRoundtableRequest
import dev.minios.ocremote.data.api.PiModelRefDto
import dev.minios.ocremote.data.api.PiRoundLimitsDto
import dev.minios.ocremote.data.api.PiRoundtableDto
import dev.minios.ocremote.data.api.PiSpeakerPolicyDto
import dev.minios.ocremote.data.api.PiRosterSummaryDto
import dev.minios.ocremote.data.api.RoundEndPayloadDto
import dev.minios.ocremote.data.api.RoundStartPayloadDto
import dev.minios.ocremote.data.api.RoundtableSseEvent
import dev.minios.ocremote.data.api.resolvedId
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Roundtable
import dev.minios.ocremote.domain.transport.AgentTransport
import dev.minios.ocremote.domain.transport.PiAcceptedDelta
import dev.minios.ocremote.domain.transport.PiAuthor
import dev.minios.ocremote.domain.transport.PiEventEnvelope
import dev.minios.ocremote.domain.transport.PiMessageIntegrity
import dev.minios.ocremote.domain.transport.PiModelRef
import dev.minios.ocremote.domain.transport.PiRoundLimits
import dev.minios.ocremote.domain.transport.PiRoundtableRoom
import dev.minios.ocremote.domain.transport.PiSpeakerPolicy
import dev.minios.ocremote.domain.transport.PiTransportEvent
import dev.minios.ocremote.domain.transport.TransportEvent
import dev.minios.ocremote.domain.transport.TransportMessagePart
import dev.minios.ocremote.domain.transport.TransportModelSelection
import dev.minios.ocremote.domain.transport.TransportRoom
import dev.minios.ocremote.domain.transport.TransportRoomScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.security.MessageDigest

private const val PI_RECONNECT_BASE_DELAY_MS = 1_000L
private const val PI_RECONNECT_MAX_DELAY_MS = 30_000L
private const val PI_RECONNECT_BACKOFF_FACTOR = 2.0
private const val PI_RECONNECT_MAX_ATTEMPTS = 6

class PiRoundtableTransport(
    server: ServerConfig,
    private val api: PiApi,
    private val json: Json,
    private val baseReconnectDelayMs: Long = PI_RECONNECT_BASE_DELAY_MS,
    private val maxReconnectDelayMs: Long = PI_RECONNECT_MAX_DELAY_MS,
    private val backoffFactor: Double = PI_RECONNECT_BACKOFF_FACTOR,
    private val maxReconnectAttempts: Int = PI_RECONNECT_MAX_ATTEMPTS,
) : AgentTransport {
    private val conn = PiConnection.from(server.url, server.token)
    private var activeRoundId: String? = null
    private var lastEventId: Long? = null

    override suspend fun listRooms(directory: String?, rootsOnly: Boolean): List<TransportRoom> {
        val rooms = api.listRoundtables(conn).mapNotNull { dto -> dto.toTransportRoom() }
        if (activeRoundId == null) activeRoundId = rooms.firstOrNull()?.id
        return rooms.map { room -> TransportRoom.Pi(room) }
    }

    override suspend fun listRoomScopes(): List<TransportRoomScope> = emptyList()

    override fun openEventStream(directory: String?): Flow<TransportEvent> = flow {
        val roundId = resolveRoundId(directory)
        val processor = PiRoundtableEventProcessor(json)
        var reconnectAttempts = 0

        while (currentCoroutineContext().isActive) {
            try {
                api.connectEvents(conn, roundId, lastEventId).collect { wireEvent ->
                    lastEventId = maxOf(lastEventId ?: wireEvent.eventId, wireEvent.eventId)
                    val events = processor.accept(wireEvent)
                    events.forEach { event -> emit(TransportEvent.Pi(event)) }
                    reconnectAttempts = 0
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!currentCoroutineContext().isActive || error.isFlowCancellation()) throw error
            }

            if (!currentCoroutineContext().isActive) break
            if (reconnectAttempts >= maxReconnectAttempts.coerceAtLeast(0)) break
            reconnectAttempts += 1
            val delayMs = calculateBackoff(reconnectAttempts)
            delay(delayMs)
        }
    }

    override suspend fun sendMessage(
        roomId: String,
        parts: List<TransportMessagePart>,
        model: TransportModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?,
    ) {
        val text = parts.joinToString(separator = "\n") { part ->
            part.text ?: part.url ?: part.path ?: part.filename ?: ""
        }.trim()
        if (text.isBlank()) return

        val roundId = roomId.ifBlank { activeRoundId ?: createRoundtable(text).id }
        activeRoundId = roundId
        val accepted = api.sendCommand(
            conn = conn,
            roundId = roundId,
            command = PiCommandRequest(
                roundId = roundId,
                command = "inject",
                participant = PiCommandParticipantDto(id = "user-local", name = "User"),
                content = text,
            ),
        )
        if (!accepted) error("Pi roundtable inject command was rejected")
    }

    override suspend fun sendCommand(
        roomId: String,
        command: String,
        arguments: String,
        directory: String?,
    ): Boolean {
        val roundId = roomId.ifBlank { activeRoundId ?: return false }
        activeRoundId = roundId
        if (command == "cancel") return api.cancelRoundtable(conn, roundId)
        return api.sendCommand(conn, roundId, command.toPiCommandRequest(roundId, arguments))
    }

    override suspend fun sendShellCommand(
        roomId: String,
        command: String,
        agent: String,
        model: TransportModelSelection?,
        directory: String?,
    ): Boolean = sendCommand(roomId = roomId, command = "inject", arguments = command, directory = directory)

    override suspend fun replyToPermission(
        requestId: String,
        reply: String,
        message: String?,
        directory: String?,
    ): Boolean = false

    suspend fun getTranscript(roundId: String) = api.getTranscript(conn, roundId)

    private suspend fun resolveRoundId(directory: String?): String {
        directory?.takeIf { it.isNotBlank() }?.let {
            activeRoundId = it
            return it
        }
        activeRoundId?.let { return it }
        val firstListed = listRooms().firstOrNull()?.id
        if (firstListed != null) {
            activeRoundId = firstListed
            return firstListed
        }
        return createRoundtable(topic = "Pi Roundtable").id.also { activeRoundId = it }
    }

    private suspend fun createRoundtable(topic: String): PiRoundtableRoom {
        val created = api.createRoundtable(conn, PiCreateRoundtableRequest(topic = topic))
        return created.toTransportRoom() ?: PiRoundtableRoom(id = created.resolvedId() ?: error("Pi roundtable response missing id"), topic = topic)
    }

    private fun calculateBackoff(attempt: Int): Long {
        val delay = (baseReconnectDelayMs * Math.pow(backoffFactor, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
        return delay.coerceAtMost(maxReconnectDelayMs)
    }

    private fun Throwable.isFlowCancellation(): Boolean = generateSequence(this) { error -> error.cause }
        .any { error ->
            val className = error.javaClass.name
            className.contains("AbortFlowException") || className.startsWith("kotlinx.coroutines.flow.internal.")
        }

    private fun PiRoundtableDto.toTransportRoom(): PiRoundtableRoom? {
        val roomId = resolvedId() ?: return null
        return PiRoundtableRoom(
            id = roomId,
            title = title ?: roundTitle,
            topic = topic,
            status = status,
            directory = directory,
            roundCount = roundCount,
            roster = roster.map { item -> item.toDomain() },
            createdAt = createdAt,
            updatedAt = updatedAt,
            archivedAt = archivedAt,
            templateOf = templateOf,
        )
    }

    private fun PiRosterSummaryDto.toDomain(): Roundtable.RoleSummary = Roundtable.RoleSummary(
        id = id,
        name = name,
        role = role,
        colorSeed = colorSeed,
    )

    private fun String.toPiCommandRequest(roundId: String, arguments: String): PiCommandRequest {
        val trimmedArguments = arguments.trim().ifBlank { null }
        return when (this) {
            "可" -> PiCommandRequest(roundId = roundId, command = this, note = trimmedArguments)
            "止" -> PiCommandRequest(roundId = roundId, command = this, wrapUpInstruction = trimmedArguments)
            "switch_cadence" -> PiCommandRequest(
                roundId = roundId,
                command = this,
                speakerPolicy = trimmedArguments?.let { PiSpeakerPolicyDto(mode = it) },
                arguments = trimmedArguments,
            )
            "@mention" -> {
                val targetPersonaId = trimmedArguments?.substringBefore(' ')?.takeIf { it.isNotBlank() }
                val instruction = trimmedArguments
                    ?.substringAfter(' ', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
                PiCommandRequest(
                    roundId = roundId,
                    command = this,
                    targetPersonaId = targetPersonaId,
                    instruction = instruction,
                    arguments = trimmedArguments,
                )
            }
            "inject" -> PiCommandRequest(
                roundId = roundId,
                command = this,
                participant = PiCommandParticipantDto(id = "user-local", name = "User"),
                content = trimmedArguments.orEmpty(),
            )
            "skip" -> PiCommandRequest(
                roundId = roundId,
                command = this,
                personaId = trimmedArguments,
            )
            else -> PiCommandRequest(roundId = roundId, command = this, arguments = trimmedArguments)
        }
    }
}

class PiRoundtableEventProcessor(private val json: Json) {
    private val seenEventIds = linkedSetOf<Long>()
    private val turnBuffers = mutableMapOf<String, TurnBuffer>()

    fun accept(event: RoundtableSseEvent): List<PiTransportEvent> {
        if (!seenEventIds.add(event.eventId)) return emptyList()
        return processKnownEvent(event)?.let(::listOf) ?: emptyList()
    }

    fun processSnapshot(events: Iterable<RoundtableSseEvent>): List<PiTransportEvent> {
        seenEventIds.clear()
        turnBuffers.clear()
        return events
            .distinctBy { event -> event.eventId }
            .sortedWith(compareBy<RoundtableSseEvent> { event -> event.sequence }.thenBy { event -> event.eventId })
            .mapNotNull { event ->
                seenEventIds += event.eventId
                processKnownEvent(event)
            }
    }

    private fun processKnownEvent(event: RoundtableSseEvent): PiTransportEvent? = when (event.type) {
        "round_start" -> event.toRoundStart()
        "agent_turn_start" -> event.toAgentTurnStart()
        "message_delta" -> event.toMessageDelta()
        "message_end" -> event.toMessageEnd()
        "moderator_synthesis" -> event.toModeratorSynthesis()
        "awaiting_command" -> event.toAwaitingCommand()
        "agent_retry" -> event.toAgentRetry()
        "agent_fallback" -> event.toAgentFallback()
        "agent_error" -> event.toAgentError()
        "awaiting_skip" -> event.toAwaitingSkip()
        "round_end" -> event.toRoundEnd()
        "error" -> event.toError()
        else -> null
    }

    private fun RoundtableSseEvent.toRoundStart(): PiTransportEvent.RoundStart {
        val payload = decodePayload<RoundStartPayloadDto>()
        return PiTransportEvent.RoundStart(
            envelope = envelope(),
            topic = payload.topic,
            roundTitle = payload.roundTitle,
            speakerPolicy = payload.speakerPolicy.toDomain(),
            participantIds = payload.participantIds,
            moderatorId = payload.moderatorId,
            limits = payload.limits?.toDomain(),
        )
    }

    private fun RoundtableSseEvent.toAgentTurnStart(): PiTransportEvent.AgentTurnStart {
        val payload = decodePayload<AgentTurnStartPayloadDto>()
        val turnId = turnId ?: return PiTransportEvent.AgentTurnStart(
            envelope = envelope(),
            personaId = payload.personaId,
            providerId = payload.providerId,
            model = payload.model,
            attempt = payload.attempt,
            actionTag = payload.actionTag,
            speakerPolicyMode = payload.speakerPolicyMode,
            reason = payload.reason,
        )
        turnBuffers.getOrPut(turnId) { TurnBuffer(startedAt = ts) }.startedAt = ts
        return PiTransportEvent.AgentTurnStart(
            envelope = envelope(),
            personaId = payload.personaId,
            providerId = payload.providerId,
            model = payload.model,
            attempt = payload.attempt,
            actionTag = payload.actionTag,
            speakerPolicyMode = payload.speakerPolicyMode,
            reason = payload.reason,
        )
    }

    private fun RoundtableSseEvent.toMessageDelta(): PiTransportEvent.MessageDelta? {
        val currentTurnId = turnId ?: return null
        val payload = decodePayload<MessageDeltaPayloadDto>()
        val buffer = turnBuffers.getOrPut(currentTurnId) { TurnBuffer(startedAt = null) }
        val existing = buffer.deltas[payload.deltaIndex]
        if (existing != null) {
            if (existing.chunk != payload.chunk) {
                throw PiMessageIntegrityException("Conflicting delta ${payload.deltaIndex} for $currentTurnId")
            }
            return null
        }
        buffer.deltas[payload.deltaIndex] = PiAcceptedDelta(
            deltaIndex = payload.deltaIndex,
            chunk = payload.chunk,
            charStart = payload.charStart,
            encoding = payload.encoding,
        )
        return PiTransportEvent.MessageDelta(
            envelope = envelope(),
            chunk = payload.chunk,
            deltaIndex = payload.deltaIndex,
            charStart = payload.charStart,
            encoding = payload.encoding,
        )
    }

    private fun RoundtableSseEvent.toMessageEnd(): PiTransportEvent.MessageEnd? {
        val currentTurnId = turnId ?: return null
        val payload = decodePayload<MessageEndPayloadDto>()
        val acceptedDeltas = turnBuffers[currentTurnId]
            ?.deltas
            ?.values
            ?.sortedBy { delta -> delta.deltaIndex }
            ?: emptyList()
        val assembledFromDeltas = acceptedDeltas.joinToString(separator = "") { delta -> delta.chunk }
        val assembledText = payload.finalText ?: assembledFromDeltas
        val integrity = PiMessageIntegrity(
            deltaCountMatches = acceptedDeltas.size == payload.deltaCount,
            finalTextMatches = payload.finalText?.let { finalText -> finalText == assembledFromDeltas },
            contentSha256Matches = payload.contentSha256?.let { expected -> sha256Hex(assembledText) == expected },
        )
        if (!integrity.isValid) {
            throw PiMessageIntegrityException("message_end integrity failed for $currentTurnId")
        }
        return PiTransportEvent.MessageEnd(
            envelope = envelope(),
            deltaCount = payload.deltaCount,
            finalText = payload.finalText,
            contentSha256 = payload.contentSha256,
            finishReason = payload.finishReason,
            acceptedDeltas = acceptedDeltas,
            assembledText = assembledText,
            integrity = integrity,
        )
    }

    private fun RoundtableSseEvent.toModeratorSynthesis(): PiTransportEvent.ModeratorSynthesis {
        val payload = decodePayload<ModeratorSynthesisPayloadDto>()
        return PiTransportEvent.ModeratorSynthesis(
            envelope = envelope(),
            markdownBody = payload.markdownBody,
            nextQuestion = payload.nextQuestion,
            coveredTurnIds = payload.coveredTurnIds,
            openQuestions = payload.openQuestions,
        )
    }

    private fun RoundtableSseEvent.toAwaitingCommand(): PiTransportEvent.AwaitingCommand {
        val payload = decodePayload<AwaitingCommandPayloadDto>()
        return PiTransportEvent.AwaitingCommand(
            envelope = envelope(),
            prompt = payload.prompt,
            allowedCommands = payload.allowedCommands,
            commandEndpoint = payload.commandEndpoint,
            expiresAt = payload.expiresAt,
        )
    }

    private fun RoundtableSseEvent.toAgentRetry(): PiTransportEvent.AgentRetry {
        val payload = decodePayload<AgentRetryPayloadDto>()
        return PiTransportEvent.AgentRetry(
            envelope = envelope(),
            personaId = payload.personaId,
            providerId = payload.providerId,
            model = payload.model,
            attempt = payload.attempt,
            maxAttempts = payload.maxAttempts,
            reason = payload.reason,
            retryAfterMs = payload.retryAfterMs,
        )
    }

    private fun RoundtableSseEvent.toAgentFallback(): PiTransportEvent.AgentFallback {
        val payload = decodePayload<AgentFallbackPayloadDto>()
        return PiTransportEvent.AgentFallback(
            envelope = envelope(),
            personaId = payload.personaId,
            from = payload.from.toDomain(),
            to = payload.to.toDomain(),
            attempt = payload.attempt,
            reason = payload.reason,
        )
    }

    private fun RoundtableSseEvent.toAgentError(): PiTransportEvent.AgentError {
        val payload = decodePayload<AgentErrorPayloadDto>()
        return PiTransportEvent.AgentError(
            envelope = envelope(),
            personaId = payload.personaId,
            providerId = payload.providerId,
            model = payload.model,
            attempt = payload.attempt,
            reason = payload.reason,
            errorCode = payload.errorCode,
            recoverable = payload.recoverable,
        )
    }

    private fun RoundtableSseEvent.toAwaitingSkip(): PiTransportEvent.AwaitingSkip {
        val payload = decodePayload<AwaitingSkipPayloadDto>()
        return PiTransportEvent.AwaitingSkip(
            envelope = envelope(),
            personaId = payload.personaId,
            providerId = payload.providerId,
            model = payload.model,
            attempt = payload.attempt,
            reason = payload.reason,
            skipCommand = payload.skipCommand,
        )
    }

    private fun RoundtableSseEvent.toRoundEnd(): PiTransportEvent.RoundEnd {
        val payload = decodePayload<RoundEndPayloadDto>()
        return PiTransportEvent.RoundEnd(
            envelope = envelope(),
            reason = payload.reason,
            finalSummaryMarkdown = payload.finalSummaryMarkdown,
            endedByCommandId = payload.endedByCommandId,
            turnCount = payload.turnCount,
        )
    }

    private fun RoundtableSseEvent.toError(): PiTransportEvent.Error {
        val payload = decodePayload<ErrorPayloadDto>()
        return PiTransportEvent.Error(
            envelope = envelope(),
            code = payload.code,
            message = payload.message,
            severity = payload.severity,
            retryable = payload.retryable,
            relatedEventId = payload.relatedEventId,
        )
    }

    private inline fun <reified T> RoundtableSseEvent.decodePayload(): T = json.decodeFromJsonElement(payload)

    private fun RoundtableSseEvent.envelope(): PiEventEnvelope = PiEventEnvelope(
        protocolVersion = protocolVersion,
        eventId = eventId,
        roundId = roundId,
        turnId = turnId,
        sequence = sequence,
        type = type,
        author = PiAuthor(
            id = author.id,
            name = author.name,
            mbti = author.mbti,
            role = author.role,
            colorSeed = author.colorSeedText(),
        ),
        ts = ts,
    )

    private fun PiSpeakerPolicyDto.toDomain(): PiSpeakerPolicy = PiSpeakerPolicy(
        mode = mode,
        maxTurnsPerRound = maxTurnsPerRound,
        allowInterruptions = allowInterruptions,
        maxConsecutiveTurnsPerPersona = maxConsecutiveTurnsPerPersona,
    )

    private fun PiRoundLimitsDto.toDomain(): PiRoundLimits = PiRoundLimits(
        maxTurnsPerRound = maxTurnsPerRound,
        maxRetriesPerTurn = maxRetriesPerTurn,
        maxRoundMs = maxRoundMs,
        maxTranscriptBytes = maxTranscriptBytes,
    )

    private fun PiModelRefDto.toDomain(): PiModelRef = PiModelRef(
        providerId = providerId,
        model = model,
    )

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private data class TurnBuffer(
        var startedAt: String?,
        val deltas: MutableMap<Int, PiAcceptedDelta> = linkedMapOf(),
    )
}

class PiMessageIntegrityException(message: String) : IllegalStateException(message)
