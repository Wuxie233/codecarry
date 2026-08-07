package dev.wuxie233.codecarry.ui.screens.chat

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentReadLimitTest {

    @Test
    fun `reads content at the exact byte limit`() {
        val bytes = ByteArray(32) { it.toByte() }

        assertArrayEquals(bytes, readAttachmentBytes(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test
    fun `stops after the first byte beyond the limit`() {
        val input = CountingInputStream(ByteArray(64))

        assertNull(readAttachmentBytes(input, 32))
        assertEquals(33, input.bytesRead)
    }

    @Test
    fun `reads an empty stream`() {
        assertArrayEquals(ByteArray(0), readAttachmentBytes(ByteArrayInputStream(ByteArray(0)), 32))
    }
}

private class CountingInputStream(private val bytes: ByteArray) : InputStream() {
    var bytesRead: Int = 0
        private set

    override fun read(): Int {
        if (bytesRead >= bytes.size) return -1
        return bytes[bytesRead++].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRead >= bytes.size) return -1
        val count = minOf(length, bytes.size - bytesRead)
        bytes.copyInto(buffer, offset, bytesRead, bytesRead + count)
        bytesRead += count
        return count
    }
}
