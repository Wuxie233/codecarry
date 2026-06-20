package dev.minios.ocremote.ui.screens.chat

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownMessageWebViewScrollTest {
    @Test
    fun webViewHtmlMakesEveryPreTableAndMathBlockIndependentlyScrollable() {
        val html = buildMessageHtml(
            placeholderMarkdown = "xMJXMATH0HTAMXJMx",
            math = listOf(MarkdownMathSegment.Math("x", display = true, delimiter = "${'$'}${'$'}")),
            textColor = Color.Black,
            codeBackground = Color.LightGray,
            codeForeground = Color.Black,
            linkColor = Color.Blue,
            borderColor = Color.Gray,
            bodyFontSizePx = 14,
            darkMode = false,
            markedJs = """
                window.marked={
                  setOptions:function(){},
                  parse:function(source){return '<pre><code>'+('a'.repeat(400))+'</code></pre><table><tr><td>'+('b'.repeat(400))+'</td></tr></table><pre><code>'+('c'.repeat(400))+'</code></pre><table><tr><td>'+('d'.repeat(400))+'</td></tr></table>'+source;}
                };
            """.trimIndent(),
            katexJs = """
                window.katex={
                  renderToString:function(source, options){
                    var content = '<span>'+('e'.repeat(400))+'</span>';
                    return options && options.displayMode ? '<span class="katex-display">'+content+'</span>' : content;
                  }
                };
            """.trimIndent(),
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val main = Handler(Looper.getMainLooper())
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        main.post {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.countDown()
                }
            }
            webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
        }

        assertTrue("WebView page should load", loaded.await(5, TimeUnit.SECONDS))

        val result = evaluate(webView, """
            (function(){
              var nodes = Array.prototype.slice.call(document.querySelectorAll('pre, .table-scroll, .katex-display'));
              nodes.forEach(function(node){ node.scrollLeft = 64; });
              return 'count=' + nodes.length + ';scrolled=' + nodes.map(function(node){ return node.scrollLeft > 0; }).join(',');
            })()
        """.trimIndent())

        assertTrue("expected four markdown blocks plus one math block: $result", result.contains("count=5"))
        assertEquals("expected every horizontal block to accept scrollLeft: $result", 5, Regex("true").findAll(result).count())

        main.post { webView.destroy() }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var output = ""
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(script) { value ->
                output = value ?: ""
                latch.countDown()
            }
        }
        assertTrue("JS should evaluate", latch.await(5, TimeUnit.SECONDS))
        return output
    }
}
