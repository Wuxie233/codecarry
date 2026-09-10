package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexThread

/** A server-local forest. Broken ancestry stays visible, never masquerading as ordinary work. */
data class CodexThreadNode(
    val thread: CodexThread,
    val children: List<CodexThreadNode> = emptyList(),
    val orphan: Boolean = false,
) {
    val members: List<CodexThread> by lazy(LazyThreadSafetyMode.NONE) { listOf(thread) + children.flatMap { it.members } }
    val runningCount: Int get() = members.count { it.status.type == "active" }
    val failedCount: Int get() = members.count { it.status.type == "systemError" || it.turns.lastOrNull()?.status == "failed" }
    val recency: Long get() = members.maxOf { it.recencyAt ?: it.updatedAt ?: it.createdAt ?: 0L }
}

internal fun buildCodexThreadTopology(threads: List<CodexThread>): List<CodexThreadNode> {
    // Keep early deltas in the reducer without advertising an unverified thread.
    val byId = threads.filter { it.hasMetadata }.associateBy { it.id }
    val parents = byId.mapValues { (_, thread) -> thread.parentThreadId?.takeIf { it in byId } }.toMutableMap()
    // Break each malformed cycle deterministically. Every thread remains navigable exactly once.
    val finished = mutableSetOf<String>()
    byId.keys.forEach { start ->
        val path = linkedSetOf<String>()
        var current: String? = start
        while (current != null && current !in finished) {
            if (!path.add(current)) {
                parents[current] = null
                break
            }
            current = parents[current]
        }
        finished += path
    }
    val children = byId.values.groupBy { parents[it.id] }
    fun node(thread: CodexThread): CodexThreadNode = CodexThreadNode(
        thread = thread,
        children = children[thread.id].orEmpty().map(::node).sortedByDescending { it.recency },
        orphan = parents[thread.id] == null && (thread.parentThreadId != null || thread.isSubagent),
    )
    return children[null].orEmpty().map(::node).sortedByDescending { it.recency }
}

internal fun filterCodexThreadTopology(
    roots: List<CodexThreadNode>,
    query: String,
    filter: CodexThreadFilter,
    pending: Map<String, Int>,
): List<CodexThreadNode> = roots.filter { root ->
    root.members.any { thread ->
        val matchesFilter = when (filter) {
            CodexThreadFilter.ALL -> true
            CodexThreadFilter.RUNNING -> thread.status.type == "active"
            CodexThreadFilter.PENDING -> pending.getOrDefault(thread.id, 0) > 0
            CodexThreadFilter.FAILED -> thread.status.type == "systemError" || thread.turns.lastOrNull()?.status == "failed"
        }
        matchesFilter && (query.isBlank() || listOf(thread.name.orEmpty(), thread.agentNickname.orEmpty(), thread.preview, thread.cwd.orEmpty(), thread.id)
            .any { it.contains(query.trim(), ignoreCase = true) })
    }
}
