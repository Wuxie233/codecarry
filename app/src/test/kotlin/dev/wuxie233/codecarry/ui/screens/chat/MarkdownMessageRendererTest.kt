package dev.wuxie233.codecarry.ui.screens.chat

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
            math = listOf(
                MarkdownMathPlaceholder(
                    id = 0,
                    parserRange = SourceRange(7, 25),
                    normalizedRange = SourceRange(7, 10),
                    source = "x",
                    display = false,
                    delimiter = "${'$'}",
                ),
            ),
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

    @Test
    fun `only http and https schemes can leave a markdown WebView`() {
        assertTrue(isExternalMessageLinkScheme("http"))
        assertTrue(isExternalMessageLinkScheme("HTTPS"))
        assertFalse(isExternalMessageLinkScheme("file"))
        assertFalse(isExternalMessageLinkScheme("javascript"))
        assertFalse(isExternalMessageLinkScheme("content"))
        assertFalse(isExternalMessageLinkScheme(null))
    }
}
