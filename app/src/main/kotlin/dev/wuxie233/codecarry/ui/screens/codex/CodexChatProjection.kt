package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexEventState
import dev.wuxie233.codecarry.data.codex.CodexThread

/** Reference checks intentionally avoid walking historical turns on unrelated server events. */
internal fun sameCodexChatProjection(a: CodexEventState, b: CodexEventState, id: String): Boolean =
    a.resetGeneration == b.resetGeneration &&
        a.threads[id] === b.threads[id] && a.turnPlans[id] === b.turnPlans[id] &&
        a.turnDiffs[id] === b.turnDiffs[id] && a.tokenUsage[id] === b.tokenUsage[id] &&
        a.goals[id] === b.goals[id] && (id in a.knownGoalThreadIds) == (id in b.knownGoalThreadIds) &&
        a.threadFailures[id] === b.threadFailures[id] && a.turnFailures[id] === b.turnFailures[id]

/** The family panel needs identities and ancestry, not every streamed item in every thread. */
internal class CodexChatFamilyProjection {
    private var metadata: List<CodexThread> = emptyList()
    private var selectedId: String? = null
    private var result: List<CodexThread> = emptyList()

    fun project(threads: Map<String, CodexThread>, threadId: String): List<CodexThread> {
        val next = threads.values.map { thread ->
            CodexThread(
                id = thread.id, name = thread.name, agentNickname = thread.agentNickname,
                preview = thread.preview, cwd = thread.cwd, hasMetadata = thread.hasMetadata,
                parentThreadId = thread.parentThreadId, source = thread.source,
                status = thread.status, recencyAt = thread.recencyAt,
                createdAt = thread.createdAt, updatedAt = thread.updatedAt,
            )
        }
        if (next != metadata || selectedId != threadId) {
            metadata = next
            selectedId = threadId
            result = buildCodexThreadTopology(next)
                .firstOrNull { root -> root.members.any { it.id == threadId } }?.members.orEmpty()
        }
        return result
    }
}
