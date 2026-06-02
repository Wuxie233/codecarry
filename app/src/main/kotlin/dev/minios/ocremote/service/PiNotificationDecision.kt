package dev.minios.ocremote.service

import dev.minios.ocremote.domain.transport.PiTransportEvent

internal data class PiNotificationDecision(
    val eventKey: String,
    val title: String,
    val message: String,
)

internal fun decidePiNotification(event: PiTransportEvent): PiNotificationDecision? = when (event) {
    is PiTransportEvent.RoundEnd -> PiNotificationDecision(
        eventKey = "${event.envelope.roundId}:round_end:${event.envelope.eventId}",
        title = "Roundtable complete",
        message = event.finalSummaryMarkdown?.firstNotBlankLine()?.takeForNotification()
            ?: "Round ended: ${event.reason}",
    )
    is PiTransportEvent.AwaitingCommand -> PiNotificationDecision(
        eventKey = "${event.envelope.roundId}:awaiting_command:${event.envelope.eventId}",
        title = "Roundtable awaiting command",
        message = event.prompt.ifBlank { "The roundtable is waiting for your command." }.takeForNotification(),
    )
    else -> null
}

private fun String.firstNotBlankLine(): String? = lineSequence()
    .map { line -> line.trim() }
    .firstOrNull { line -> line.isNotBlank() }

private fun String.takeForNotification(maxLength: Int = 160): String {
    val normalized = trim().replace(Regex("\\s+"), " ")
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 3).trimEnd() + "..."
}
