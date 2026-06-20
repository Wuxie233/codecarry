package dev.minios.ocremote.ui.screens.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
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
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import dev.snipme.highlights.Highlights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import kotlin.math.absoluteValue

private const val MermaidAssetBaseUrl = "file:///android_asset/"
private const val MermaidAssetScript = "mermaid.min.js"
private const val MermaidRenderTimeoutMillis = 4_000L

internal val mermaidAwareCodeFence: MarkdownComponent = {
    MermaidAwareMarkdownCodeFence(it.content, it.node)
}

internal enum class MermaidFenceRenderMode {
    RenderDiagram,
    CodeBlockFallback,
    NotMermaid,
}

internal data class MermaidFenceDecision(
    val mode: MermaidFenceRenderMode,
    val normalizedLanguage: String?,
    val reason: String?,
)

internal data class MermaidFenceBlock(
    val code: String,
    val language: String,
    val startLine: Int,
    val endLine: Int,
)

@Composable
fun MermaidAwareMarkdownCodeFence(
    content: String,
    node: ASTNode,
    highlights: Highlights.Builder = Highlights.Builder(),
) {
    DisableSelection {
        MarkdownCodeFence(content, node) { code, language ->
            val decision = remember(code, language) { decideMermaidFenceRendering(code, language) }
            if (decision.mode == MermaidFenceRenderMode.RenderDiagram) {
                MermaidMarkdownDiagram(
                    source = code,
                    fallbackContent = {
                        SafeMarkdownHighlightedCode(code, language, highlights)
                    },
                )
            } else {
                SafeMarkdownHighlightedCode(code, language, highlights)
            }
        }
    }
}

@Composable
fun MermaidMarkdownDiagram(
    source: String,
    modifier: Modifier = Modifier,
    fallbackContent: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = colorScheme.surface
    val borderColor = colorScheme.outlineVariant.copy(alpha = 0.65f)
    val isDark = backgroundColor.luminance() < 0.35f
    var html by remember(source, backgroundColor, colorScheme.onSurface, colorScheme.primary) {
        mutableStateOf<String?>(null)
    }
    var renderState by remember(source) { mutableStateOf<MermaidWebRenderState>(MermaidWebRenderState.Preparing) }
    var currentRenderKey by remember(source) { mutableStateOf(renderKeyFor(source)) }
    val latestOnRendered by rememberUpdatedState<(String, Int) -> Unit> { key, heightPx ->
        if (key == currentRenderKey) {
            renderState = MermaidWebRenderState.Rendered(heightPx.coerceIn(140, 720).dp)
        }
    }
    val latestOnFailed by rememberUpdatedState<(String, String?) -> Unit> { key, _ ->
        if (key == currentRenderKey) {
            renderState = MermaidWebRenderState.Fallback
        }
    }

    LaunchedEffect(source, backgroundColor, colorScheme.onSurface, colorScheme.primary) {
        renderState = MermaidWebRenderState.Preparing
        val key = renderKeyFor(source)
        currentRenderKey = key
        html = withContext(Dispatchers.Default) {
            buildMermaidRenderHtml(
                source = source,
                renderKey = key,
                darkMode = isDark,
                backgroundColor = backgroundColor,
                textColor = colorScheme.onSurface,
                primaryColor = colorScheme.primary,
            )
        }
    }

    LaunchedEffect(html, currentRenderKey) {
        if (html != null) {
            delay(MermaidRenderTimeoutMillis)
            if (renderState == MermaidWebRenderState.Preparing || renderState == MermaidWebRenderState.Loading) {
                renderState = MermaidWebRenderState.Fallback
            }
        }
    }

    if (renderState == MermaidWebRenderState.Fallback) {
        fallbackContent()
        return
    }

    val shape = RoundedCornerShape(10.dp)
    val height = when (val state = renderState) {
        is MermaidWebRenderState.Rendered -> state.height
        MermaidWebRenderState.Loading,
        MermaidWebRenderState.Preparing,
        MermaidWebRenderState.Fallback -> 180.dp
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .heightIn(min = 140.dp, max = 720.dp)
            .height(height)
            .clip(shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .background(backgroundColor),
    ) {
        val renderHtml = html
        if (renderHtml == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = colorScheme.primary,
            )
        } else {
            PooledMermaidWebView(
                html = renderHtml,
                backgroundColor = backgroundColor,
                onRendered = latestOnRendered,
                onFailed = latestOnFailed,
                onLoading = { renderState = MermaidWebRenderState.Loading },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun PooledMermaidWebView(
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
            webView?.let(MermaidWebViewPool::release)
            webView = null
        }
    }

    AndroidView(
        factory = {
            MermaidWebViewPool.acquire(context.applicationContext).also { view ->
                webView = view
            }
        },
        update = { view ->
            view.removeJavascriptInterface("AndroidMermaidBridge")
            view.addJavascriptInterface(
                MermaidJavascriptBridge(onRendered = onRendered, onFailed = onFailed),
                "AndroidMermaidBridge",
            )
            view.setBackgroundColor(backgroundColor.toArgb())
            if (view.tag != html) {
                view.tag = html
                onLoading()
                view.loadDataWithBaseURL(
                    MermaidAssetBaseUrl,
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

private sealed interface MermaidWebRenderState {
    data object Preparing : MermaidWebRenderState
    data object Loading : MermaidWebRenderState
    data object Fallback : MermaidWebRenderState
    data class Rendered(val height: Dp) : MermaidWebRenderState
}

private object MermaidWebViewPool {
    private const val MaxPoolSize = 2
    private val pool = ArrayDeque<WebView>()

    @SuppressLint("SetJavaScriptEnabled")
    fun acquire(context: android.content.Context): WebView {
        val view = if (pool.isEmpty()) WebView(context) else pool.removeFirst()
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
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
            cacheMode = WebSettings.LOAD_NO_CACHE
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
        webView.removeJavascriptInterface("AndroidMermaidBridge")
        webView.loadUrl("about:blank")
        if (pool.size < MaxPoolSize) {
            pool.addLast(webView)
        } else {
            webView.destroy()
        }
    }
}

private class MermaidJavascriptBridge(
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

internal fun decideMermaidFenceRendering(code: String, language: String?): MermaidFenceDecision {
    val normalizedLanguage = normalizeMermaidLanguage(language)
    if (normalizedLanguage != "mermaid") {
        return MermaidFenceDecision(
            mode = MermaidFenceRenderMode.NotMermaid,
            normalizedLanguage = normalizedLanguage,
            reason = "language is not mermaid",
        )
    }
    val normalizedCode = code.trim()
    if (normalizedCode.isEmpty()) {
        return MermaidFenceDecision(
            mode = MermaidFenceRenderMode.CodeBlockFallback,
            normalizedLanguage = normalizedLanguage,
            reason = "empty mermaid block",
        )
    }
    if (!startsWithSupportedMermaidDirective(normalizedCode)) {
        return MermaidFenceDecision(
            mode = MermaidFenceRenderMode.CodeBlockFallback,
            normalizedLanguage = normalizedLanguage,
            reason = "missing mermaid diagram directive",
        )
    }
    return MermaidFenceDecision(
        mode = MermaidFenceRenderMode.RenderDiagram,
        normalizedLanguage = normalizedLanguage,
        reason = null,
    )
}

internal fun findMermaidFenceBlocks(markdown: String): List<MermaidFenceBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<MermaidFenceBlock>()
    var lineIndex = 0
    while (lineIndex < lines.size) {
        val startMatch = fenceStartRegex.matchEntire(lines[lineIndex])
        if (startMatch == null) {
            lineIndex++
            continue
        }
        val fenceMarker = startMatch.groupValues[1]
        val language = startMatch.groupValues.getOrNull(2).orEmpty()
        val closesWith = fenceMarker.first().toString().repeat(fenceMarker.length)
        val startLine = lineIndex
        val codeLines = mutableListOf<String>()
        lineIndex++
        while (lineIndex < lines.size && lines[lineIndex].trim() != closesWith) {
            codeLines += lines[lineIndex]
            lineIndex++
        }
        if (lineIndex < lines.size && normalizeMermaidLanguage(language) == "mermaid") {
            blocks += MermaidFenceBlock(
                code = codeLines.joinToString("\n"),
                language = language,
                startLine = startLine,
                endLine = lineIndex,
            )
        }
        lineIndex++
    }
    return blocks
}

private val fenceStartRegex = Regex("^\\s*(`{3,}|~{3,})\\s*([^\\s`]*)?.*$")

private fun normalizeMermaidLanguage(language: String?): String? {
    return language
        ?.trim()
        ?.removePrefix("{")
        ?.removeSuffix("}")
        ?.substringBefore(' ')
        ?.substringBefore(',')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
}

private fun startsWithSupportedMermaidDirective(code: String): Boolean {
    val firstLine = code
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("%%") }
        ?: return false
    return supportedMermaidPrefixes.any { prefix ->
        firstLine.equals(prefix, ignoreCase = true) || firstLine.startsWith("$prefix ", ignoreCase = true)
    }
}

private val supportedMermaidPrefixes = listOf(
    "flowchart",
    "graph",
    "sequenceDiagram",
    "classDiagram",
    "stateDiagram",
    "stateDiagram-v2",
    "erDiagram",
    "journey",
    "gantt",
    "pie",
    "gitGraph",
    "mindmap",
    "timeline",
    "quadrantChart",
    "xyChart",
    "sankey",
    "block",
    "packet",
    "architecture",
    "kanban",
    "radar",
    "treemap",
)

private fun buildMermaidRenderHtml(
    source: String,
    renderKey: String,
    darkMode: Boolean,
    backgroundColor: Color,
    textColor: Color,
    primaryColor: Color,
): String {
    val theme = if (darkMode) "dark" else "default"
    val sourceLiteral = jsStringLiteral(source)
    val keyLiteral = jsStringLiteral(renderKey)
    val background = cssColor(backgroundColor)
    val text = cssColor(textColor)
    val primary = cssColor(primaryColor)
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <script src="$MermaidAssetScript"></script>
          <style>
            html, body { margin: 0; padding: 0; background: $background; color: $text; overflow-x: auto; overflow-y: hidden; }
            body { font-family: sans-serif; }
            #container { box-sizing: border-box; display: inline-block; min-width: 100%; padding: 12px; }
            #container svg { display: block; max-width: none; height: auto; }
            .edgeLabel, .nodeLabel, text { color: $text; }
          </style>
        </head>
        <body>
          <div id="container" aria-label="Mermaid diagram"></div>
          <script>
            (function() {
              const source = $sourceLiteral;
              const renderKey = $keyLiteral;
              const bridge = window.AndroidMermaidBridge;
              function fail(error) {
                const message = error && (error.message || error.toString()) || 'Mermaid render failed';
                if (bridge && bridge.failed) bridge.failed(renderKey, message);
              }
              function rendered() {
                requestAnimationFrame(function() {
                  const doc = document.documentElement;
                  const body = document.body;
                  const height = Math.ceil(Math.max(doc.scrollHeight, body.scrollHeight, 140));
                  if (bridge && bridge.rendered) bridge.rendered(renderKey, height);
                });
              }
              function render() {
                try {
                  if (!window.mermaid) {
                    fail('Mermaid asset was not loaded');
                    return;
                  }
                  mermaid.initialize({
                    startOnLoad: false,
                    securityLevel: 'strict',
                    theme: '$theme',
                    themeVariables: {
                      background: '$background',
                      primaryColor: '$background',
                      primaryTextColor: '$text',
                      primaryBorderColor: '$primary',
                      lineColor: '$primary',
                      textColor: '$text'
                    },
                    flowchart: { htmlLabels: false, useMaxWidth: false },
                    sequence: { useMaxWidth: false },
                    gantt: { useMaxWidth: false }
                  });
                  Promise.resolve(mermaid.render('mermaid-diagram-' + renderKey, source))
                    .then(function(result) {
                      document.getElementById('container').innerHTML = result.svg;
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
              setTimeout(function() { fail('Mermaid render timed out'); }, 3500);
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun renderKeyFor(source: String): String {
    return source.hashCode().absoluteValue.toString(36)
}

private fun cssColor(color: Color): String {
    val argb = color.toArgb()
    val red = argb shr 16 and 0xFF
    val green = argb shr 8 and 0xFF
    val blue = argb and 0xFF
    return "#" + listOf(red, green, blue).joinToString("") { channel ->
        channel.toString(16).padStart(2, '0')
    }
}

private fun jsStringLiteral(value: String): String {
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
