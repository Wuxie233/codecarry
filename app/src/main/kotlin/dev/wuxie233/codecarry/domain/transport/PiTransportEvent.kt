package dev.wuxie233.codecarry.domain.transport

import dev.wuxie233.codecarry.domain.model.Roundtable

data class PiRoundtableRoom(
    val id: String,
    val title: String? = null,
    val topic: String? = null,
    val status: String? = null,
    val directory: String? = null,
    val roundCount: Int = 0,
    val roster: List<Roundtable.RoleSummary> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val archivedAt: String? = null,
    val templateOf: String? = null,
)

data class PiAuthor(
    val id: String,
    val name: String,
    val mbti: String,
    val role: String,
    val colorSeed: String,
)

data class PiEventEnvelope(
    val protocolVersion: Int,
    val eventId: Long,
    val roundId: String,
    val turnId: String?,
    val sequence: Long,
    val type: String,
    val author: PiAuthor,
    val ts: String,
)

data class PiSpeakerPolicy(
    val mode: String,
    val maxTurnsPerRound: Int? = null,
    val allowInterruptions: Boolean? = null,
    val maxConsecutiveTurnsPerPersona: Int? = null,
)

data class PiRoundLimits(
    val maxTurnsPerRound: Int? = null,
    val maxRetriesPerTurn: Int? = null,
    val maxRoundMs: Long? = null,
    val maxTranscriptBytes: Long? = null,
)

data class PiModelRef(
    val providerId: String,
    val model: String,
)

data class PiAcceptedDelta(
    val deltaIndex: Int,
    val chunk: String,
    val charStart: Int? = null,
    val encoding: String? = null,
)

data class PiMessageIntegrity(
    val deltaCountMatches: Boolean,
    val finalTextMatches: Boolean?,
    val contentSha256Matches: Boolean?,
) {
    val isValid: Boolean
        get() = deltaCountMatches && finalTextMatches != false && contentSha256Matches != false
}

sealed interface PiTransportEvent {
    val envelope: PiEventEnvelope

    data class RoundStart(
        override val envelope: PiEventEnvelope,
        val topic: String,
        val roundTitle: String?,
        val speakerPolicy: PiSpeakerPolicy,
        val participantIds: List<String>,
        val moderatorId: String,
        val limits: PiRoundLimits?,
    ) : PiTransportEvent

    data class AgentTurnStart(
        override val envelope: PiEventEnvelope,
        val personaId: String,
        val providerId: String,
        val model: String,
        val attempt: Int,
        val actionTag: String?,
        val speakerPolicyMode: String?,
        val reason: String?,
    ) : PiTransportEvent

    data class MessageDelta(
        override val envelope: PiEventEnvelope,
        val chunk: String,
        val deltaIndex: Int,
        val charStart: Int?,
        val encoding: String?,
    ) : PiTransportEvent

    data class MessageEnd(
        override val envelope: PiEventEnvelope,
        val deltaCount: Int,
        val finalText: String?,
        val contentSha256: String?,
        val finishReason: String?,
        val acceptedDeltas: List<PiAcceptedDelta>,
        val assembledText: String,
        val integrity: PiMessageIntegrity,
    ) : PiTransportEvent

    data class ModeratorSynthesis(
        override val envelope: PiEventEnvelope,
        val markdownBody: String,
        val nextQuestion: String,
        val coveredTurnIds: List<String>,
        val openQuestions: List<String>,
    ) : PiTransportEvent

    data class AwaitingCommand(
        override val envelope: PiEventEnvelope,
        val prompt: String,
        val allowedCommands: List<String>,
        val commandEndpoint: String?,
        val expiresAt: String?,
    ) : PiTransportEvent

    data class AgentRetry(
        override val envelope: PiEventEnvelope,
        val personaId: String,
        val providerId: String,
        val model: String,
        val attempt: Int,
        val maxAttempts: Int?,
        val reason: String,
        val retryAfterMs: Long,
    ) : PiTransportEvent

    data class AgentFallback(
        override val envelope: PiEventEnvelope,
        val personaId: String,
        val from: PiModelRef,
        val to: PiModelRef,
        val attempt: Int,
        val reason: String,
    ) : PiTransportEvent

    data class AgentError(
        override val envelope: PiEventEnvelope,
        val personaId: String,
        val providerId: String,
        val model: String,
        val attempt: Int,
        val reason: String,
        val errorCode: String?,
        val recoverable: Boolean,
    ) : PiTransportEvent

    data class AwaitingSkip(
        override val envelope: PiEventEnvelope,
        val personaId: String,
        val providerId: String,
        val model: String,
        val attempt: Int,
        val reason: String,
        val skipCommand: String,
    ) : PiTransportEvent

    data class RoundEnd(
        override val envelope: PiEventEnvelope,
        val reason: String,
        val finalSummaryMarkdown: String?,
        val endedByCommandId: String?,
        val turnCount: Int?,
    ) : PiTransportEvent

    data class Error(
        override val envelope: PiEventEnvelope,
        val code: String,
        val message: String,
        val severity: String,
        val retryable: Boolean?,
        val relatedEventId: Long?,
    ) : PiTransportEvent
}
