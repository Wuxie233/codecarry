package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAbortResultHandlerTest {

    @Test
    fun `success calls idle and not error`() {
        var idleCalled = false
        var errorCalled = false

        handleAbortResult(
            outcome = AbortOutcome.Success,
            onIdle = { idleCalled = true },
            onError = { errorCalled = true }
        )

        assertTrue(idleCalled)
        assertFalse(errorCalled)
    }

    @Test
    fun `unsuccessful does not call idle and emits default message`() {
        var idleCalled = false
        var errorMessage: String? = null

        handleAbortResult(
            outcome = AbortOutcome.Unsuccessful,
            onIdle = { idleCalled = true },
            onError = { errorMessage = it }
        )

        assertFalse(idleCalled)
        assertEquals(ABORT_FAILED_MESSAGE, errorMessage)
    }

    @Test
    fun `exception with message emits that message`() {
        var idleCalled = false
        var errorMessage: String? = null
        val exceptionMessage = "Custom error message"

        handleAbortResult(
            outcome = AbortOutcome.Failed(Exception(exceptionMessage)),
            onIdle = { idleCalled = true },
            onError = { errorMessage = it }
        )

        assertFalse(idleCalled)
        assertEquals(exceptionMessage, errorMessage)
    }

    @Test
    fun `exception with null message emits default`() {
        var idleCalled = false
        var errorMessage: String? = null

        handleAbortResult(
            outcome = AbortOutcome.Failed(Exception()),
            onIdle = { idleCalled = true },
            onError = { errorMessage = it }
        )

        assertFalse(idleCalled)
        assertEquals(ABORT_FAILED_MESSAGE, errorMessage)
    }
}
