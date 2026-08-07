package dev.wuxie233.codecarry.ui.screens.sessions.components

data class ActiveConversationItem(
    val sessionId: String,
    val directory: String,
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
