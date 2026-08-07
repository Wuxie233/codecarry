package dev.wuxie233.codecarry.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionNotificationDismissalTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "test", NotificationManager.IMPORTANCE_LOW),
        )
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun `reading session removes its response notification but keeps sibling event and summary`() {
        val responseId = SessionNotificationIdentity.responseReadyId(SERVER_ID, SESSION_ID)
        val permissionId = (SERVER_ID + SESSION_ID).hashCode() + 1000
        val summaryId = SessionNotificationIdentity.serverSummaryId(SERVER_ID)
        post(responseId, SessionNotificationIdentity.serverGroup(SERVER_ID))
        post(permissionId, SessionNotificationIdentity.serverGroup(SERVER_ID))
        post(summaryId, SessionNotificationIdentity.serverGroup(SERVER_ID), isSummary = true)

        dismissResponseReadyNotification(context, SERVER_ID, SESSION_ID)

        assertFalse(notificationManager.activeNotifications.any { it.id == responseId })
        assertTrue(notificationManager.activeNotifications.any { it.id == permissionId })
        assertTrue(notificationManager.activeNotifications.any { it.id == summaryId })
    }

    @Test
    fun `reading last notified session also removes empty server summary`() {
        val responseId = SessionNotificationIdentity.responseReadyId(SERVER_ID, SESSION_ID)
        val summaryId = SessionNotificationIdentity.serverSummaryId(SERVER_ID)
        post(responseId, SessionNotificationIdentity.serverGroup(SERVER_ID))
        post(summaryId, SessionNotificationIdentity.serverGroup(SERVER_ID), isSummary = true)

        dismissResponseReadyNotification(context, SERVER_ID, SESSION_ID)

        assertFalse(notificationManager.activeNotifications.any { it.id == responseId })
        assertFalse(notificationManager.activeNotifications.any { it.id == summaryId })
    }

    @Test
    fun `reading remains successful when notification service is unavailable`() {
        val unavailableContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any {
                throw RuntimeException("notification service unavailable")
            }
        }

        dismissResponseReadyNotification(unavailableContext, SERVER_ID, SESSION_ID)
    }

    private fun post(id: Int, group: String, isSummary: Boolean = false) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("test")
            .setGroup(group)
            .setGroupSummary(isSummary)
            .build()
        notificationManager.notify(id, notification)
    }

    private companion object {
        const val CHANNEL_ID = "session_notification_test"
        const val SERVER_ID = "server"
        const val SESSION_ID = "session"
    }
}
