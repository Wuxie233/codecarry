package dev.wuxie233.codecarry.ui.screens.roundtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundtableSummaryRenderingTest {

    @Test
    fun `large transcript is split into bounded render chunks with full export preserved elsewhere`() {
        val markdown = (1..60).joinToString(separator = "") { index -> "section-$index-".padEnd(20, 'x') }

        val chunks = splitTranscriptForRendering(markdown, maxChunkChars = 20, maxChunks = 3)

        val renderedChunks = chunks.filterNot { it.isOmissionNotice }
        assertEquals(3, renderedChunks.size)
        assertTrue(renderedChunks.all { it.text.length <= 256 })
        assertEquals(markdown.take(256 * 3), renderedChunks.joinToString(separator = "") { it.text })
        assertTrue(chunks.last().isOmissionNotice)
        val notice = chunks.last().omissionNotice
        assertNotNull(notice)
        assertEquals(256 * 3, notice!!.renderedChars)
        assertEquals(markdown.length - 256 * 3, notice.omittedChars)
    }

    @Test
    fun `blank transcript renders no chunks`() {
        assertTrue(splitTranscriptForRendering("\n\n  ").isEmpty())
    }

    @Test
    fun `small transcript renders a single non-notice chunk`() {
        val chunks = splitTranscriptForRendering("# Summary\nBody", maxChunkChars = 512, maxChunks = 3)

        assertEquals(1, chunks.size)
        assertEquals("# Summary\nBody", chunks.single().text)
        assertFalse(chunks.single().isOmissionNotice)
    }
}
