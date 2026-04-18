package dev.minios.ocremote.ui.screens.sessions.components

data class ActiveSubagentItem(
    val sessionId: String,
    val title: String,
    val agentName: String?,
    val parentSessionId: String,
    val parentTitle: String?,
    val projectName: String?,
    val status: SubagentStatus,
    val updatedAt: Long
)

enum class SubagentStatus { BUSY, RETRY, IDLE }
