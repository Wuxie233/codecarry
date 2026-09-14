package dev.wuxie233.codecarry.service

import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused JVM tests for the OpenCode unread recompute decision helpers
 * (issue #35): which sessions need a fresh history fetch before the
 * read-anchor cursor comparison is trustworthy.
 */
class OpenCodeUnreadRecomputeTest {

    private fun message(id: String, created: Long, completed: Long? = null) =
        Message.User(id = id, sessionId = "ses", time = TimeInfo(created = created, completed = completed))

    @Test
    fun `no known history always needs a fetch`() {
        assertTrue(
            shouldFetchUnreadHistory(
                sessionUpdated = 0L,
                knownMessages = emptyList(),
                transitionedToIdle = false,
                lastFetchedSessionUpdated = null,
            ),
        )
    }

    @Test
    fun `fresh fetch memo suppresses repeated fetches for unchanged sessions`() {
        // Idempotency: the same snapshot must not fetch again.
        assertFalse(
            shouldFetchUnreadHistory(
                sessionUpdated = 100L,
                knownMessages = listOf(message("m1", 90L)),
                transitionedToIdle = false,
                lastFetchedSessionUpdated = 100L,
            ),
        )
    }

    @Test
    fun `session updated beyond the newest known message needs a fetch`() {
        assertTrue(
            shouldFetchUnreadHistory(
                sessionUpdated = 200L,
                knownMessages = listOf(message("m1", 90L), message("m2", 100L, completed = 150L)),
                transitionedToIdle = false,
                lastFetchedSessionUpdated = null,
            ),
        )
    }

    @Test
    fun `session not updated beyond known history does not fetch`() {
        assertFalse(
            shouldFetchUnreadHistory(
                sessionUpdated = 100L,
                knownMessages = listOf(message("m1", 90L), message("m2", 100L, completed = 100L)),
                transitionedToIdle = false,
                lastFetchedSessionUpdated = null,
            ),
        )
    }

    @Test
    fun `busy to idle transition through a snapshot forces a refresh even with a fresh memo`() {
        assertTrue(
            shouldFetchUnreadHistory(
                sessionUpdated = 100L,
                knownMessages = listOf(message("m1", 90L)),
                transitionedToIdle = true,
                lastFetchedSessionUpdated = 100L,
            ),
        )
    }

    @Test
    fun `stale memo older than the session update needs a fetch`() {
        assertTrue(
            shouldFetchUnreadHistory(
                sessionUpdated = 300L,
                knownMessages = listOf(message("m1", 90L)),
                transitionedToIdle = false,
                lastFetchedSessionUpdated = 100L,
            ),
        )
    }

    @Test
    fun `unread fetch keys keep duplicate session ids on two servers apart`() {
        assertFalse(unreadFetchKey("srv-a", "ses") == unreadFetchKey("srv-b", "ses"))
        assertEquals(unreadFetchKey("srv-a", "ses"), unreadFetchKey("srv-a", "ses"))
    }
}
