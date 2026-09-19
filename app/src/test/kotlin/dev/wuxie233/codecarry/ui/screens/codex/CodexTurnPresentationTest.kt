package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import dev.wuxie233.codecarry.data.codex.CodexTurn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CodexTurnPresentationTest {
    @Test fun finalAnswerRemainsOutsideActivityWhenLaterToolsAndAsyncMessagesArrive() {
        val user = item("user", "userMessage")
        val commentary = item("commentary", phase = "commentary")
        val final = item("final", phase = "final_answer")
        val lateReasoning = item("reason", "reasoning")
        val async = item("question", phase = "final_answer", delivery = "async")
        val lateTool = item("tool", "dynamicToolCall")
        val result = projectCodexTurn(CodexTurn("turn", "completed", listOf(user, commentary, final, lateReasoning, async, lateTool)))

        assertEquals(listOf(user), result.userItems)
        assertEquals(listOf(final), result.answerItems)
        assertEquals(listOf(commentary, lateReasoning, async, lateTool), result.activityItems)
        assertFalse(result.defaultExpanded)
    }

    @Test fun allAnswerFragmentsAndImagesRemainAccessibleOutsideCollapsedActivity() {
        val first = item("first", phase = "final_answer")
        val second = item("second", phase = "final_answer")
        val image = item("image", "imageGeneration").copy(status = "completed")
        val result = projectCodexTurn(CodexTurn("turn", "completed", listOf(item("tool", "commandExecution"), first, image, second)))
        assertEquals(listOf(first, image, second), result.answerItems)
        assertEquals(listOf("tool"), result.activityItems.map { it.id })
    }

    @Test fun commentaryOnlyCompletedTurnDoesNotInventAnAnswer() {
        val result = projectCodexTurn(CodexTurn("turn", "completed", listOf(item("update", phase = "commentary"))))
        assertTrue(result.answerItems.isEmpty())
        assertFalse(result.hasFinalAnswer)
        assertTrue(result.defaultExpanded)
    }

    @Test fun legacyAnswerUsesLastUnphasedSynchronousMessageNotLastItem() {
        val update = item("update")
        val final = item("answer")
        val async = item("question", delivery = "async")
        val result = projectCodexTurn(CodexTurn("turn", "completed", listOf(update, final, async, item("reason", "reasoning"))))
        assertEquals(listOf(final), result.answerItems)
        assertEquals(listOf("update", "question", "reason"), result.activityItems.map { it.id })
    }

    @Test fun failedAndStoppedTurnsKeepTheirProcessOpen() {
        for (status in listOf("failed", "interrupted", "cancelled")) {
            assertTrue(projectCodexTurn(CodexTurn("turn", status, listOf(item("answer", phase = "final_answer")))).defaultExpanded)
        }
    }

    @Test fun terminalStatusStopsStaleThinkingAndToolsWithoutChangingKnownFailures() {
        val running = item("tool", "dynamicToolCall").copy(status = "inProgress")
        assertEquals("completed", codexItemPresentationStatus(running, "completed"))
        assertEquals("interrupted", codexItemPresentationStatus(running, "interrupted"))
        assertEquals("failed", codexItemPresentationStatus(running.copy(status = "failed"), "completed"))
        assertNull(codexItemPresentationStatus(item("reason", "reasoning"), "inProgress"))
    }

    @Test fun timestampsUseServerSecondsAndTerminalDurationNeverKeepsGrowing() {
        val running = CodexTurn("turn", "inProgress", startedAt = 1_789_817_371L)
        assertEquals(5_000L, codexTurnElapsedMs(running, 1_789_817_376_000L))
        val completed = running.copy(status = "completed", completedAt = 1_789_819_005L, durationMs = 1_634_222)
        assertEquals(1_634_222L, codexTurnElapsedMs(completed, Long.MAX_VALUE))
        assertEquals(1_634_000L, codexTurnElapsedMs(completed.copy(durationMs = null), Long.MAX_VALUE))
        assertNull(codexTurnElapsedMs(CodexTurn("unknown", "completed"), Long.MAX_VALUE))
        assertNull(codexTurnElapsedMs(running.copy(status = "failed"), Long.MAX_VALUE))
        assertEquals(0L, codexTurnElapsedMs(running, 1L))
    }

    @Test fun historicalPresentationRetainsIdentityWhileCurrentTurnStreams() {
        val cache = CodexTurnPresentationCache()
        val history = CodexTurn("history", "completed", listOf(item("answer")))
        val active = CodexTurn("active", "inProgress", listOf(item("reason", "reasoning")))
        val first = cache.project(listOf(history, active))
        val next = cache.project(listOf(history, active.copy(items = active.items + item("final", phase = "final_answer"))))
        assertSame(first[0], next[0])
        assertNotSame(first[1], next[1])
        assertFalse(next[1].defaultExpanded)
    }

    private fun item(id: String, type: String = "agentMessage", phase: String? = null, delivery: String? = null): CodexThreadItem =
        CodexThreadItem.fromJson(buildJsonObject {
            put("id", id)
            put("type", type)
            put("text", id)
            phase?.let { put("phase", it) }
            delivery?.let { put("delivery", it) }
        })
}
