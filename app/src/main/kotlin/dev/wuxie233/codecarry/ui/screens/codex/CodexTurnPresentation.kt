package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import dev.wuxie233.codecarry.data.codex.CodexTurn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A turn's answer is separate from activity, including activity arriving after the answer. */
internal data class CodexTurnPresentation(
    val turn: CodexTurn,
    val userItems: List<CodexThreadItem>,
    val activityItems: List<CodexThreadItem>,
    val answerItems: List<CodexThreadItem>,
    val hasFinalAnswer: Boolean,
) {
    val defaultExpanded: Boolean
        get() = !hasFinalAnswer || turn.status in setOf("failed", "interrupted", "cancelled", "canceled")
}

internal fun projectCodexTurn(turn: CodexTurn): CodexTurnPresentation {
    val leadingUsers = turn.items.takeWhile { it.type == "userMessage" || it.type == "steeringUserMessage" }
    val remaining = turn.items.drop(leadingUsers.size)
    val explicitAnswers = remaining.indices.filter { index ->
        val item = remaining[index]
        item.type == "agentMessage" && item.phase == "final_answer" && item.delivery != "async"
    }.toSet()
    // Older servers omit phase. Never promote explicitly marked commentary or asynchronous
    // agent output to the answer, even when it happens to be the final item in the snapshot.
    val legacyAnswer = if (explicitAnswers.isEmpty()) remaining.indexOfLast {
        it.type == "agentMessage" && it.phase == null && it.delivery != "async"
    } else -1
    val answerIndices = remaining.indices.filter { index ->
        index in explicitAnswers || index == legacyAnswer || remaining[index].type == "imageGeneration"
    }.toSet()
    val answers = remaining.filterIndexed { index, _ -> index in answerIndices }
    return CodexTurnPresentation(
        turn = turn,
        userItems = leadingUsers,
        activityItems = remaining.filterIndexed { index, _ -> index !in answerIndices },
        answerItems = answers,
        hasFinalAnswer = explicitAnswers.isNotEmpty() || legacyAnswer >= 0 ||
            answers.any { it.type == "imageGeneration" && it.status == "completed" },
    )
}

internal fun CodexThreadItem.presentationField(name: String): String? =
    ((raw[name] ?: extra[name]) as? JsonPrimitive)?.contentOrNull

/** Do not walk historical items again for every streaming delta in the current turn. */
internal class CodexTurnPresentationCache {
    private var entries = emptyMap<String, CodexTurnPresentation>()

    fun project(turns: List<CodexTurn>): List<CodexTurnPresentation> {
        val result = turns.map { turn ->
            entries[turn.id]?.takeIf { it.turn === turn } ?: projectCodexTurn(turn)
        }
        entries = result.associateBy { it.turn.id }
        return result
    }
}

internal fun codexTurnIsRunning(status: String): Boolean =
    status in setOf("inProgress", "in_progress", "running")

/** Terminal turn state wins over stale in-progress tool/reasoning statuses. */
internal fun codexItemPresentationStatus(item: CodexThreadItem, turnStatus: String?): String? {
    if (turnStatus !in setOf("completed", "failed", "interrupted", "cancelled", "canceled")) return item.status
    return if (item.status == null || item.status in setOf("inProgress", "in_progress", "running", "pending")) {
        turnStatus
    } else item.status
}

internal fun codexTurnElapsedMs(turn: CodexTurn, nowMs: Long): Long? {
    if (!codexTurnIsRunning(turn.status)) turn.durationMs?.takeIf { it >= 0 }?.let { return it }
    val start = turn.startedAt ?: return null
    // The app-server Turn timestamps are Unix seconds; durationMs is milliseconds.
    val endMs = turn.completedAt?.times(1_000L)
        ?: nowMs.takeIf { codexTurnIsRunning(turn.status) }
        ?: return null
    return (endMs - start * 1_000L).coerceAtLeast(0)
}
