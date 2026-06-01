package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownTableParseTest {

    @Test
    fun parseMarkdownTableParsesBasicTableAndPreservesLongCell() {
        val longText = "This is a deliberately long table cell that should remain fully intact without being shortened or replaced by an ellipsis character."
        val table = """
            | Name | Details |
            | --- | --- |
            | Alpha | $longText |
            | Beta | Short value |
        """.trimIndent()

        val parsed = parseMarkdownTable(table)

        assertEquals(listOf("Name", "Details"), parsed!!.first)
        assertEquals(2, parsed.first.size)
        assertEquals(2, parsed.second.size)
        assertEquals("Alpha", parsed.second[0][0])
        assertEquals(longText, parsed.second[0][1])
        assertFalse(parsed.second[0][1].contains("…"))
        assertEquals(listOf("Beta", "Short value"), parsed.second[1])
    }

    @Test
    fun parseMarkdownTableReturnsNullForPlainParagraphOrCodeBlock() {
        assertNull(parseMarkdownTable("This is a plain paragraph, not a markdown table."))

        val codeBlock = """
            ```kotlin
            val text = "| not | a table |"
            ```
        """.trimIndent()
        assertNull(parseMarkdownTable(codeBlock))
    }

    @Test
    fun splitMarkdownTableRowKeepsEscapedPipeInSingleCell() {
        val cells = splitMarkdownTableRow("| a \\| b | c |")

        assertEquals(listOf("a \\| b", "c"), cells)
        assertEquals("a | b", cleanInlineTableMarkdown(cells.first()))
    }

    @Test
    fun cleanInlineTableMarkdownConvertsBrVariantsToNewlines() {
        assertEquals("first\nsecond", cleanInlineTableMarkdown("first<br>second"))
        assertEquals("first\nsecond", cleanInlineTableMarkdown("first<br/>second"))
        assertEquals("first\nsecond", cleanInlineTableMarkdown("first<br />second"))
    }
}
