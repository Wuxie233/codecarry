package dev.wuxie233.codecarry.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusInterruptibleTest {

    @Test
    fun `Idle is not interruptible`() {
        assertFalse(SessionStatus.Idle.isInterruptible)
    }

    @Test
    fun `Busy is interruptible`() {
        assertTrue(SessionStatus.Busy.isInterruptible)
    }

    @Test
    fun `Retry is interruptible`() {
        val retry = SessionStatus.Retry(attempt = 1, message = "boom", next = 0L)
        assertTrue(retry.isInterruptible)
    }
}
