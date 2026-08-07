package dev.wuxie233.codecarry.service

import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the permission-notification quick-action contract.
 *
 * The full Service intent-dispatch path requires Robolectric/instrumentation,
 * which is heavier than the risk justifies. The risk surface here is:
 *   1) the action constant and extras keys agreed between
 *      OpenCodeConnectionService.showPermissionNotification (producer) and
 *      OpenCodeConnectionService.onStartCommand (consumer);
 *   2) the reply-value strings ("once" / "always" / "reject") which must match
 *      OpenCodeApi.replyToPermission's documented body contract;
 *   3) EventReducer.removePermission being correctly invoked on success.
 *
 * This test asserts (1) and (2) via the public companion constants and
 * exercises (3) directly against EventReducer.
 */
class OpenCodeConnectionServicePermissionActionTest {

    @Test
    fun permissionActionAndExtraKeysAreStable() {
        // These constants are the wire format between the notification producer
        // and the service consumer. Changing them silently breaks already-posted
        // notifications when the app updates.
        assertEquals(
            "dev.wuxie233.codecarry.PERMISSION_REPLY",
            OpenCodeConnectionService.ACTION_PERMISSION_REPLY,
        )
        assertEquals("server_id", OpenCodeConnectionService.EXTRA_SERVER_ID)
        assertEquals("sessionId", OpenCodeConnectionService.EXTRA_SESSION_ID)
        assertEquals(
            "permission_request_id",
            OpenCodeConnectionService.EXTRA_PERMISSION_REQUEST_ID,
        )
        assertEquals(
            "permission_reply_value",
            OpenCodeConnectionService.EXTRA_PERMISSION_REPLY_VALUE,
        )
    }

    @Test
    fun replyValueConstantsMatchServerContract() {
        // These three string values are the body the server's
        // POST /permission/{requestId}/reply expects under the `reply` field.
        // OpenCodeApi.replyToPermission already uses them; the notification
        // path must use the SAME strings, not a parallel enum.
        assertEquals("once", OpenCodeConnectionService.PERMISSION_REPLY_ONCE)
        assertEquals("always", OpenCodeConnectionService.PERMISSION_REPLY_ALWAYS)
        assertEquals("reject", OpenCodeConnectionService.PERMISSION_REPLY_REJECT)
    }

    @Test
    fun reducerOptimisticRemovalClearsThePermissionSeenByChat() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )
        assertFalse(reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().isEmpty())

        // This is exactly what handlePermissionAction does on a successful reply.
        reducer.removePermission("server-1", "perm-1")

        assertTrue(reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().isEmpty())
    }

    @Test
    fun reducerOptimisticRemovalIsSafeWhenSseReplyArrivesLater() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )

        reducer.removePermission("server-1", "perm-1")
        // Late-arriving SSE reply — must not crash or resurrect the entry.
        reducer.processEvent(
            SseEvent.PermissionReplied(sessionId = "ses-1", requestId = "perm-1"),
            serverId = "server-1",
        )

        assertTrue(reducer.permissionsByServer.value["server-1"]?.get("ses-1").orEmpty().isEmpty())
    }
}
