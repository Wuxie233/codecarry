package dev.minios.ocremote.ui.screens.sessions.components

data class ActiveConversationItem(
    val sessionId: String,
    val title: String?,
    val projectName: String?,
    val status: ConversationStatus,
    val pendingCount: Int,
    val updatedAt: Long,
)

enum class ConversationStatus {
    UNREAD,
    AWAITING_QUESTION,
    AWAITING_PERMISSION,
    BUSY,
    RETRY,
}
