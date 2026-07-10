package dev.minios.ocremote.ui.screens.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMessageRendererTest {
    @Test
    fun `buildMessageHtml prepares every overflow block for independent horizontal drag`() {
        val html = buildMessageHtml(
            placeholderMarkdown = """
                | Before ${'$'}x${'$'}
                |
                | | A | B |
                | | --- | --- |
                | | one | two |
                |
                | ```kotlin
                | val reallyLongLine = "${"x".repeat(120)}"
                | ```
            """.trimMargin(),
            math = listOf(MarkdownMathSegment.Math("x", display = false, delimiter = "${'$'}")),
            textColor = Color.Black,
            codeBackground = Color.LightGray,
            codeForeground = Color.Black,
            linkColor = Color.Blue,
            borderColor = Color.Gray,
            bodyFontSizePx = 14,
            darkMode = false,
            markedJs = "window.marked={parse:function(s){return s},setOptions:function(){}};",
            katexJs = "window.katex={renderToString:function(){return '<span>x</span>'}};",
        )

        assertTrue(html.contains("markdown-horizontal-scroll"))
        assertTrue(html.contains("touch-action: pan-x"))
        assertTrue(html.contains("function prepareHorizontalScrollables"))
        assertTrue(html.contains("querySelectorAll('table')"))
        assertTrue(html.contains("wrapper.className = '${ChatOverflowPolicy.webViewTableWrapperClasses()}'"))
        assertTrue(html.contains("querySelectorAll('${ChatOverflowPolicy.webViewStructuredScrollSelector()}')"))
        assertFalse(html.contains("querySelectorAll('p')"))
    }
}
