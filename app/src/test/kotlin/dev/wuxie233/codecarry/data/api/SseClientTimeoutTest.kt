package dev.wuxie233.codecarry.data.api

import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SseClientTimeoutTest {

    @Test
    fun `socket read timeout is finite and follows heartbeat deadline`() {
        assertTrue(SSE_SOCKET_TIMEOUT_MS > HEARTBEAT_TIMEOUT_MS)
        assertTrue(SSE_SOCKET_TIMEOUT_MS <= HEARTBEAT_TIMEOUT_MS + 10_000L)
    }

    @Test
    fun `silent stream read times out with reconnectable connection error`() = runTest {
        val silentChannel = ByteChannel(autoFlush = true)

        assertThrows(SseConnectionException::class.java) {
            kotlinx.coroutines.runBlocking {
                readSseLine(silentChannel, timeoutMs = 10L)
            }
        }
    }
}
