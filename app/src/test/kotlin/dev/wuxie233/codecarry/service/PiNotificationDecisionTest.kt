package dev.wuxie233.codecarry.service

import dev.wuxie233.codecarry.domain.transport.PiAuthor
import dev.wuxie233.codecarry.domain.transport.PiEventEnvelope
import dev.wuxie233.codecarry.domain.transport.PiTransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiNotificationDecisionTest {

    @Test
    fun `round complete event becomes task notification decision`() {
        val decision = decidePiNotification(
            PiTransportEvent.RoundEnd(
                envelope = envelope(eventId = 12, type = "round_end"),
                reason = "completed",
                finalSummaryMarkdown = "\n# Final synthesis\nDetailed text",
                endedByCommandId = null,
                turnCount = 3,
            )
        )

        assertEquals("round-1:round_end:12", decision?.eventKey)
        assertEquals("Roundtable complete", decision?.title)
        assertEquals("# Final synthesis", decision?.message)
    }

    @Test
    fun `awaiting command event becomes action-needed notification decision`() {
        val decision = decidePiNotification(
            PiTransportEvent.AwaitingCommand(
                envelope = envelope(eventId = 8, type = "awaiting_command"),
                prompt = "  Choose 可 or 止 before the cap expires.  ",
                allowedCommands = listOf("可", "止"),
                commandEndpoint = "/roundtables/round-1/command",
                expiresAt = "2026-06-03T00:05:00Z",
            )
        )

        assertEquals("round-1:awaiting_command:8", decision?.eventKey)
        assertEquals("Roundtable awaiting command", decision?.title)
        assertEquals("Choose 可 or 止 before the cap expires.", decision?.message)
    }

    @Test
    fun `non terminal Pi events do not notify`() {
        val decision = decidePiNotification(
            PiTransportEvent.MessageDelta(
                envelope = envelope(eventId = 3, type = "message_delta", turnId = "turn-1"),
                chunk = "partial",
                deltaIndex = 0,
                charStart = 0,
                encoding = "utf-8",
            )
        )

        assertNull(decision)
    }

    @Test
    fun `notification message is bounded`() {
        val longPrompt = "x".repeat(240)
        val decision = decidePiNotification(
            PiTransportEvent.AwaitingCommand(
                envelope = envelope(eventId = 9, type = "awaiting_command"),
                prompt = longPrompt,
                allowedCommands = listOf("可"),
                commandEndpoint = null,
                expiresAt = null,
            )
        )

        assertTrue(decision!!.message.length <= 160)
        assertTrue(decision.message.endsWith("..."))
    }

    private fun envelope(
        eventId: Long,
        type: String,
        turnId: String? = null,
    ): PiEventEnvelope = PiEventEnvelope(
        protocolVersion = 1,
        eventId = eventId,
        roundId = "round-1",
        turnId = turnId,
        sequence = eventId,
        type = type,
        author = PiAuthor(id = "system", name = "System", mbti = "SYSTEM", role = "system", colorSeed = "system"),
        ts = "2026-06-03T00:00:00Z",
    )
}
