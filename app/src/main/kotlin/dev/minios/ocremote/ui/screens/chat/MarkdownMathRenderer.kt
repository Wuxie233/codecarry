package dev.minios.ocremote.ui.screens.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

private const val MathAssetBaseUrl = "file:///android_asset/"
private const val MathAssetScript = "mathjax-tex-svg-full.js"
private const val MathRenderTimeoutMillis = 4_000L
private const val MathWebViewPoolSize = 8

internal sealed interface MarkdownMathSegment {
    data class Markdown(val text: String) : MarkdownMathSegment
    data class Math(val source: String, val display: Boolean, val delimiter: String) : MarkdownMathSegment
}

internal fun splitMarkdownMathSegments(markdown: String): List<MarkdownMathSegment> {
    if (markdown.isEmpty()) return emptyList()
    val segments = mutableListOf<MarkdownMathSegment>()
    val markdownBuffer = StringBuilder()
    var index = 0
    var inlineCode = false

    fun flushMarkdown() {
        if (markdownBuffer.isNotEmpty()) {
            segments += MarkdownMathSegment.Markdown(markdownBuffer.toString())
            markdownBuffer.clear()
        }
    }

    while (index < markdown.length) {
        val lineStart = index == 0 || markdown[index - 1] == '\n'
        if (lineStart) {
            val fence = detectFenceStart(markdown, index)
            if (fence != null) {
                val closeIndex = findFenceClose(markdown, index, fence)
                val endIndex = if (closeIndex >= 0) {
                    markdown.indexOf('\n', closeIndex).let { if (it >= 0) it + 1 else markdown.length }
                } else {
                    markdown.length
                }
                markdownBuffer.append(markdown, index, endIndex)
                index = endIndex
                continue
            }
        }

        val char = markdown[index]
        if (char == '`') {
            val tickEnd = index + countRepeated(markdown, index, '`')
            inlineCode = !inlineCode
            markdownBuffer.append(markdown, index, tickEnd)
            index = tickEnd
            continue
        }
        if (!inlineCode && char == '\\' && index + 1 < markdown.length) {
            val next = markdown[index + 1]
            if (next == '[' || next == '(') {
                val close = if (next == '[') "\\]" else "\\)"
                val closeIndex = markdown.indexOf(close, startIndex = index + 2)
                if (closeIndex >= 0) {
                    val source = markdown.substring(index + 2, closeIndex).trim()
                    if (source.isNotEmpty()) {
                        flushMarkdown()
                        segments += MarkdownMathSegment.Math(source, display = next == '[', delimiter = "\\$next")
                    }
                    index = closeIndex + close.length
                    continue
                }
            }
            markdownBuffer.append(char)
            markdownBuffer.append(next)
            index += 2
            continue
        }
        if (!inlineCode && char == '$' && !isEscaped(markdown, index)) {
            val display = index + 1 < markdown.length && markdown[index + 1] == '$'
            val delimiter = if (display) "$$" else "$"
            if (!display && isCurrencyLikeDollar(markdown, index)) {
                markdownBuffer.append(char)
                index++
                continue
            }
            val sourceStart = index + delimiter.length
            val closeIndex = findDollarClose(markdown, sourceStart, display)
            if (closeIndex >= 0) {
                val source = markdown.substring(sourceStart, closeIndex).trim()
                if (source.isNotEmpty()) {
                    flushMarkdown()
                    segments += MarkdownMathSegment.Math(source, display = display, delimiter = delimiter)
                }
                index = closeIndex + delimiter.length
                continue
            }
        }

        markdownBuffer.append(char)
        index++
    }

    flushMarkdown()
    return segments.mergeAdjacentMarkdown()
}

private fun List<MarkdownMathSegment>.mergeAdjacentMarkdown(): List<MarkdownMathSegment> {
    val merged = mutableListOf<MarkdownMathSegment>()
    for (segment in this) {
        val previous = merged.lastOrNull()
        if (previous is MarkdownMathSegment.Markdown && segment is MarkdownMathSegment.Markdown) {
            merged[merged.lastIndex] = MarkdownMathSegment.Markdown(previous.text + segment.text)
        } else {
            merged += segment
        }
    }
    return merged.filterNot { it is MarkdownMathSegment.Markdown && it.text.isEmpty() }
}

private fun detectFenceStart(text: String, index: Int): String? {
    var cursor = index
    while (cursor < text.length && (text[cursor] == ' ' || text[cursor] == '\t')) cursor++
    if (cursor >= text.length) return null
    val marker = text[cursor]
    if (marker != '`' && marker != '~') return null
    val count = countRepeated(text, cursor, marker)
    return if (count >= 3) marker.toString().repeat(count) else null
}

private fun findFenceClose(text: String, start: Int, fence: String): Int {
    var cursor = text.indexOf('\n', start).let { if (it >= 0) it + 1 else return -1 }
    while (cursor < text.length) {
        val lineEnd = text.indexOf('\n', cursor).let { if (it >= 0) it else text.length }
        if (text.substring(cursor, lineEnd).trim() == fence) return cursor
        cursor = if (lineEnd < text.length) lineEnd + 1 else text.length
    }
    return -1
}

private fun countRepeated(text: String, start: Int, char: Char): Int {
    var cursor = start
    while (cursor < text.length && text[cursor] == char) cursor++
    return cursor - start
}

private fun isEscaped(text: String, index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        slashCount++
        cursor--
    }
    return slashCount % 2 == 1
}

private fun isCurrencyLikeDollar(text: String, index: Int): Boolean {
    val next = text.getOrNull(index + 1)
    return next != null && next.isDigit()
}

private fun findDollarClose(text: String, start: Int, display: Boolean): Int {
    val delimiter = if (display) "$$" else "$"
    var cursor = start
    while (cursor < text.length) {
        val close = text.indexOf(delimiter, cursor)
        if (close < 0) return -1
        if (!isEscaped(text, close) && (display || !isCurrencyLikeDollar(text, close))) return close
        cursor = close + delimiter.length
    }
    return -1
}

@Composable
fun MarkdownMathFormula(
    source: String,
    display: Boolean,
    textColor: Color,
    fallbackStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = if (display) colorScheme.surface else Color.Transparent
    val borderColor = colorScheme.outlineVariant.copy(alpha = 0.65f)
    val isDark = colorScheme.surface.luminance() < 0.35f
    var html by remember(source, textColor, backgroundColor, isDark, display) { mutableStateOf<String?>(null) }
    var renderState by remember(source, display) { mutableStateOf<MathWebRenderState>(MathWebRenderState.Preparing) }
    var currentRenderKey by remember(source, display) { mutableStateOf(mathRenderKeyFor(source, display)) }
    val latestOnRendered by rememberUpdatedState<(String, Int) -> Unit> { key, heightPx ->
        if (key == currentRenderKey) {
            renderState = MathWebRenderState.Rendered(heightPx.coerceIn(if (display) 48 else 24, 720).dp)
        }
    }
    val latestOnFailed by rememberUpdatedState<(String, String?) -> Unit> { key, _ ->
        if (key == currentRenderKey) renderState = MathWebRenderState.Fallback
    }

    LaunchedEffect(source, textColor, backgroundColor, isDark, display) {
        renderState = MathWebRenderState.Preparing
        val key = mathRenderKeyFor(source, display)
        currentRenderKey = key
        html = withContext(Dispatchers.Default) {
            buildMathRenderHtml(
                source = source,
                display = display,
                renderKey = key,
                backgroundColor = backgroundColor,
                textColor = textColor,
                darkMode = isDark,
            )
        }
    }

    LaunchedEffect(html, currentRenderKey) {
        if (html != null) {
            delay(MathRenderTimeoutMillis)
            if (renderState == MathWebRenderState.Preparing || renderState == MathWebRenderState.Loading) {
                renderState = MathWebRenderState.Fallback
            }
        }
    }

    if (renderState == MathWebRenderState.Fallback) {
        MathFallbackFormula(source = source, display = display, textColor = textColor, style = fallbackStyle, modifier = modifier)
        return
    }

    val height = when (val state = renderState) {
        is MathWebRenderState.Rendered -> state.height
        MathWebRenderState.Loading,
        MathWebRenderState.Preparing,
        MathWebRenderState.Fallback -> if (display) 96.dp else 36.dp
    }
    val shape = RoundedCornerShape(10.dp)
    val containerModifier = if (display) {
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .heightIn(min = 48.dp, max = 720.dp)
            .height(height)
            .clip(shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .background(backgroundColor)
    } else {
        modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp, max = 160.dp)
            .height(height)
    }

    Box(modifier = containerModifier) {
        val renderHtml = html
        if (renderHtml == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = colorScheme.primary,
            )
        } else {
            PooledMathWebView(
                html = renderHtml,
                backgroundColor = backgroundColor,
                onRendered = latestOnRendered,
                onFailed = latestOnFailed,
                onLoading = { renderState = MathWebRenderState.Loading },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun MathFallbackFormula(
    source: String,
    display: Boolean,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val fallbackText = if (display) "$$\n$source\n$$" else "\\($source\\)"
    Text(
        text = fallbackText,
        style = style.copy(fontFamily = FontFamily.Monospace),
        color = textColor,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (display) 6.dp else 0.dp),
    )
}

@Composable
private fun PooledMathWebView(
    html: String,
    backgroundColor: Color,
    onRendered: (String, Int) -> Unit,
    onFailed: (String, String?) -> Unit,
    onLoading: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let(MathWebViewPool::release)
            webView = null
        }
    }

    AndroidView(
        factory = {
            MathWebViewPool.acquire(context.applicationContext).also { view -> webView = view }
        },
        update = { view ->
            view.removeJavascriptInterface("AndroidMathBridge")
            view.addJavascriptInterface(
                MathJavascriptBridge(onRendered = onRendered, onFailed = onFailed),
                "AndroidMathBridge",
            )
            view.setBackgroundColor(backgroundColor.toArgb())
            if (view.tag != html) {
                view.tag = html
                onLoading()
                view.loadDataWithBaseURL(
                    MathAssetBaseUrl,
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
        modifier = modifier,
    )
}

private sealed interface MathWebRenderState {
    data object Preparing : MathWebRenderState
    data object Loading : MathWebRenderState
    data object Fallback : MathWebRenderState
    data class Rendered(val height: Dp) : MathWebRenderState
}

private object MathWebViewPool {
    private val pool = ArrayDeque<WebView>()

    @SuppressLint("SetJavaScriptEnabled")
    fun acquire(context: android.content.Context): WebView {
        val view = if (pool.isEmpty()) WebView(context) else pool.removeFirst()
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        view.setBackgroundColor(Color.Transparent.toArgb())
        view.webViewClient = WebViewClient()
        view.webChromeClient = WebChromeClient()
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowContentAccess = false
            allowFileAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            textZoom = 100
            blockNetworkLoads = true
        }
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = true
        return view
    }

    fun release(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.tag = null
        webView.removeJavascriptInterface("AndroidMathBridge")
        webView.loadUrl("about:blank")
        if (pool.size < MathWebViewPoolSize) {
            pool.addLast(webView)
        } else {
            webView.destroy()
        }
    }
}

private class MathJavascriptBridge(
    private val onRendered: (String, Int) -> Unit,
    private val onFailed: (String, String?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun rendered(renderKey: String, heightPx: Int) {
        mainHandler.post { onRendered(renderKey, heightPx) }
    }

    @JavascriptInterface
    fun failed(renderKey: String, message: String?) {
        mainHandler.post { onFailed(renderKey, message) }
    }
}

private fun buildMathRenderHtml(
    source: String,
    display: Boolean,
    renderKey: String,
    backgroundColor: Color,
    textColor: Color,
    darkMode: Boolean,
): String {
    val sourceLiteral = mathJsStringLiteral(source)
    val keyLiteral = mathJsStringLiteral(renderKey)
    val background = mathCssColor(backgroundColor)
    val text = mathCssColor(textColor)
    val displayMode = if (display) "true" else "false"
    val padding = if (display) "12px" else "0"
    val containerDisplay = if (display) "block" else "inline-block"
    val themeClass = if (darkMode) "dark" else "light"
    return """
        <!doctype html>
        <html class="$themeClass">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <script>
            window.MathJax = {
              startup: { typeset: false },
              tex: {
                inlineMath: [['\$', '\$'], ['\\(', '\\)']],
                displayMath: [['\$\$', '\$\$'], ['\\[', '\\]']],
                packages: {'[+]': ['ams', 'noerrors', 'noundefined']}
              },
              svg: { fontCache: 'none' },
              options: { enableMenu: false }
            };
          </script>
          <script src="$MathAssetScript"></script>
          <style>
            html, body { margin: 0; padding: 0; background: $background; color: $text; overflow-x: auto; overflow-y: hidden; }
            body { font-family: sans-serif; }
            #container { box-sizing: border-box; display: $containerDisplay; min-width: ${if (display) "100%" else "0"}; padding: $padding; color: $text; }
            mjx-container[jax="SVG"] { color: $text; }
            mjx-container svg { display: inline-block; max-width: none; }
          </style>
        </head>
        <body>
          <div id="container" aria-label="LaTeX formula"></div>
          <script>
            (function() {
              const source = $sourceLiteral;
              const renderKey = $keyLiteral;
              const displayMode = $displayMode;
              const bridge = window.AndroidMathBridge;
              function fail(error) {
                const message = error && (error.message || error.toString()) || 'Math render failed';
                if (bridge && bridge.failed) bridge.failed(renderKey, message);
              }
              function rendered() {
                requestAnimationFrame(function() {
                  const doc = document.documentElement;
                  const body = document.body;
                  const height = Math.ceil(Math.max(doc.scrollHeight, body.scrollHeight, displayMode ? 48 : 24));
                  if (bridge && bridge.rendered) bridge.rendered(renderKey, height);
                });
              }
              function render() {
                try {
                  if (!window.MathJax || !MathJax.tex2svgPromise) {
                    fail('MathJax asset was not loaded');
                    return;
                  }
                  MathJax.tex2svgPromise(source, {display: displayMode})
                    .then(function(node) {
                      const container = document.getElementById('container');
                      container.innerHTML = '';
                      container.appendChild(node);
                      rendered();
                    })
                    .catch(fail);
                } catch (error) {
                  fail(error);
                }
              }
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', render);
              } else {
                render();
              }
              setTimeout(function() { fail('Math render timed out'); }, 3500);
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun mathRenderKeyFor(source: String, display: Boolean): String {
    return (source + display.toString()).hashCode().absoluteValue.toString(36)
}

private fun mathCssColor(color: Color): String {
    if (color == Color.Transparent || color == Color.Unspecified) return "transparent"
    val argb = color.toArgb()
    val red = argb shr 16 and 0xFF
    val green = argb shr 8 and 0xFF
    val blue = argb and 0xFF
    return "#" + listOf(red, green, blue).joinToString("") { channel ->
        channel.toString(16).padStart(2, '0')
    }
}

private fun mathJsStringLiteral(value: String): String {
    val builder = StringBuilder(value.length + 2)
    builder.append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '<' -> builder.append("\\u003C")
            '>' -> builder.append("\\u003E")
            '&' -> builder.append("\\u0026")
            else -> {
                if (char.code < 0x20) {
                    builder.append("\\u")
                    builder.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
    }
    builder.append('"')
    return builder.toString()
}
