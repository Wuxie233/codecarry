package dev.minios.ocremote.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

object SessionNotificationIdentity {
    fun responseReadyId(serverId: String, sessionId: String): Int =
        eventId(serverId, sessionId, typeOffset = 0)

    fun eventId(serverId: String, sessionId: String, typeOffset: Int): Int =
        (serverId + sessionId).hashCode() + typeOffset

    fun serverGroup(serverId: String): String = "server_$serverId"

    fun serverSummaryId(serverId: String): Int = "server_summary_$serverId".hashCode()
}

fun dismissResponseReadyNotification(context: Context, serverId: String, sessionId: String) {
    val manager = try {
        context.getSystemService(NotificationManager::class.java)
    } catch (_: RuntimeException) {
        null
    } ?: return
    val responseId = SessionNotificationIdentity.responseReadyId(serverId, sessionId)
    val group = SessionNotificationIdentity.serverGroup(serverId)
    val hasOtherChildren = manager.activeNotifications.any { notification ->
        notification.id != responseId &&
            notification.notification.group == group &&
            notification.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
    }

    manager.cancel(responseId)
    if (!hasOtherChildren) {
        manager.cancel(SessionNotificationIdentity.serverSummaryId(serverId))
    }
}
