package dev.minios.ocremote.ui.screens.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt

private const val MessageAssetBaseUrl = "file:///android_asset/"

private object MarkdownRendererAssets {
    @Volatile private var markedJs: String? = null
    @Volatile private var katexJs: String? = null

    private fun read(context: android.content.Context, path: String): String =
        context.assets.open(path).use { it.readBytes().decodeToString() }.replace("</script", "<\\/script")

    fun marked(context: android.content.Context): String =
        markedJs ?: synchronized(this) { markedJs ?: read(context, "marked.min.js").also { markedJs = it } }

    fun katex(context: android.content.Context): String =
        katexJs ?: synchronized(this) { katexJs ?: read(context, "katex/katex.min.js").also { katexJs = it } }
}

@Composable
internal fun MarkdownMessageView(
    block: MarkdownRenderBlock,
    textColor: Color,
    codeBackground: Color,
    codeForeground: Color,
    linkColor: Color,
    bodyFontSizeSp: Int,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colorScheme = androidx.compose.material3.MaterialTheme.colorScheme
    val borderColor = colorScheme.outlineVariant.copy(alpha = 0.55f)
    val isDark = textColor.luminance() > 0.5f
    val markedJs = remember { MarkdownRendererAssets.marked(context.applicationContext) }
    val katexJs = remember { MarkdownRendererAssets.katex(context.applicationContext) }
    val renderedChunk = remember(
        block,
        textColor,
        codeBackground,
        codeForeground,
        linkColor,
        bodyFontSizeSp,
        isDark,
    ) {
        val html = buildMessageHtml(
            placeholderMarkdown = block.renderSource,
            math = block.math,
            textColor = textColor,
            codeBackground = codeBackground,
            codeForeground = codeForeground,
            linkColor = linkColor,
            borderColor = borderColor,
            bodyFontSizePx = bodyFontSizeSp,
            darkMode = isDark,
            markedJs = markedJs,
            katexJs = katexJs,
        )
        RenderedMarkdownPage(html = html, renderKey = block.key)
    }

    MarkdownMessageWebView(
        page = renderedChunk,
        onLinkClick = onLinkClick,
        modifier = modifier.fillMaxWidth(),
    )
}

private data class RenderedMarkdownPage(
    val html: String,
    val renderKey: String,
)

@Composable
private fun MarkdownMessageWebView(
    page: RenderedMarkdownPage,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var heightPx by remember(page.renderKey) { mutableStateOf(0) }
    val latestOnHeight by rememberUpdatedState<(String, Int) -> Unit> { key, px ->
        if (key == page.renderKey && px > 0) {
            heightPx = px
        }
    }
    val latestOnLink by rememberUpdatedState(onLinkClick)
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let(MarkdownWebViewPool::release)
            webView = null
        }
    }

    val heightDp = heightPx.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (heightPx > 0) Modifier.height(heightDp) else Modifier.heightIn(min = 24.dp)),
    ) {
        AndroidView(
            factory = {
                MarkdownWebViewPool.acquire(context.applicationContext) { url -> latestOnLink(url) }
                    .also { view -> webView = view }
            },
            update = { view ->
                view.removeJavascriptInterface("AndroidMarkdownBridge")
                view.addJavascriptInterface(
                    MarkdownJavascriptBridge(page.renderKey, latestOnHeight),
                    "AndroidMarkdownBridge",
                )
                if (view.tag != page.renderKey) {
                    view.tag = page.renderKey
                    view.loadDataWithBaseURL(MessageAssetBaseUrl, page.html, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier.fillMaxWidth().then(if (heightPx > 0) Modifier.height(heightDp) else Modifier),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private object MarkdownWebViewPool {
    private const val MaxPoolSize = 6
    private val pool = ArrayDeque<WebView>()
    private val touchArbitrators = mutableMapOf<WebView, MarkdownWebViewTouchArbitrator>()

    fun acquire(context: android.content.Context, onLink: (String) -> Unit): WebView {
        val view = if (pool.isEmpty()) WebView(context) else pool.removeFirst()
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        view.setBackgroundColor(Color.Transparent.toArgb())
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = true
        touchArbitrators.remove(view)?.detach(view)
        val touchArbitrator = MarkdownWebViewTouchArbitrator(view)
        touchArbitrators[view] = touchArbitrator
        view.setOnTouchListener(touchArbitrator)
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (isExternalMessageLinkScheme(uri.scheme)) {
                    onLink(uri.toString())
                }
                return true
            }
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowContentAccess = false
            allowFileAccess = true
            disableFileUrlCrossOriginAccess()
            cacheMode = WebSettings.LOAD_NO_CACHE
            loadWithOverviewMode = false
            useWideViewPort = false
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            textZoom = 100
            blockNetworkLoads = true
        }
        return view
    }

    fun release(webView: WebView) {
        touchArbitrators.remove(webView)?.detach(webView)
        webView.setOnTouchListener(null)
        webView.parent?.requestDisallowInterceptTouchEvent(false)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.tag = null
        webView.removeJavascriptInterface("AndroidMarkdownBridge")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl("about:blank")
        if (pool.size < MaxPoolSize) {
            pool.addLast(webView)
        } else {
            webView.destroy()
        }
    }
}

internal fun isExternalMessageLinkScheme(scheme: String?): Boolean =
    scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

@Suppress("DEPRECATION")
private fun WebSettings.disableFileUrlCrossOriginAccess() {
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
}

private class MarkdownWebViewTouchArbitrator(view: View) : View.OnTouchListener {
    private val nestedScrollingChild = NestedScrollingChildHelper(view).apply {
        isNestedScrollingEnabled = true
    }
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var direction: GestureDirection? = null
    private val consumedScroll = IntArray(2)

    init {
        ViewCompat.setNestedScrollingEnabled(view, true)
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                direction = null
                nestedScrollingChild.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (direction == null) {
                    val deltaX = kotlin.math.abs(event.x - downX)
                    val deltaY = kotlin.math.abs(event.y - downY)
                    if (maxOf(deltaX, deltaY) > touchSlop) {
                        val isHorizontal = deltaX > deltaY
                        direction = if (isHorizontal) GestureDirection.Horizontal else GestureDirection.Vertical
                        view.parent?.requestDisallowInterceptTouchEvent(isHorizontal)
                        if (isHorizontal) {
                            nestedScrollingChild.stopNestedScroll(ViewCompat.TYPE_TOUCH)
                        }
                    }
                }
                if (direction == GestureDirection.Vertical) {
                    val deltaY = (lastY - event.y).roundToInt()
                    if (deltaY != 0) {
                        consumedScroll.fill(0)
                        nestedScrollingChild.dispatchNestedPreScroll(
                            0,
                            deltaY,
                            consumedScroll,
                            null,
                            ViewCompat.TYPE_TOUCH,
                        )
                        val unconsumedY = deltaY - consumedScroll[1]
                        if (unconsumedY != 0) {
                            nestedScrollingChild.dispatchNestedScroll(
                                0,
                                0,
                                0,
                                unconsumedY,
                                null,
                                ViewCompat.TYPE_TOUCH,
                            )
                        }
                    }
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> reset(view)
        }
        return false
    }

    fun detach(view: View) {
        reset(view)
        nestedScrollingChild.isNestedScrollingEnabled = false
        ViewCompat.setNestedScrollingEnabled(view, false)
    }

    private fun reset(view: View) {
        direction = null
        nestedScrollingChild.stopNestedScroll(ViewCompat.TYPE_TOUCH)
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private enum class GestureDirection {
        Horizontal,
        Vertical,
    }
}

private class MarkdownJavascriptBridge(
    private val renderKey: String,
    private val onHeightReported: (String, Int) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onHeight(heightPx: Int) {
        mainHandler.post { onHeightReported(renderKey, heightPx) }
    }
}

internal fun openMessageLink(context: android.content.Context, url: String) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun messageCssColor(color: Color): String {
    val argb = color.toArgb()
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r,$g,$b,${"%.3f".format(a / 255.0)})"
}

internal fun buildMessageHtml(
    placeholderMarkdown: String,
    math: List<MarkdownMathPlaceholder>,
    textColor: Color,
    codeBackground: Color,
    codeForeground: Color,
    linkColor: Color,
    borderColor: Color,
    bodyFontSizePx: Int,
    darkMode: Boolean,
    markedJs: String,
    katexJs: String,
): String {
    val text = messageCssColor(textColor)
    val codeBg = messageCssColor(codeBackground)
    val codeFg = messageCssColor(codeForeground)
    val link = messageCssColor(linkColor)
    val border = messageCssColor(borderColor)
    val codeFontSize = (bodyFontSizePx - 1).coerceAtLeast(11)
    val webViewProseWraps = ChatOverflowPolicy.resolve(ChatOverflowContentKind.WebViewProse) == ChatOverflowTreatment.Wrap
    val horizontalScrollClass = ChatOverflowPolicy.webViewOverflowClass(ChatOverflowContentKind.WebViewStructuredBlock).orEmpty()
    val tableWrapperClasses = ChatOverflowPolicy.webViewTableWrapperClasses()
    val structuredScrollSelector = ChatOverflowPolicy.webViewStructuredScrollSelector()
    val sourceLiteral = jsStringLiteral(placeholderMarkdown)
    val mathJson = math.joinToString(prefix = "[", postfix = "]") { placeholder ->
        "{\"i\":${placeholder.id},\"s\":${jsStringLiteral(placeholder.source)},\"d\":${if (placeholder.display) "true" else "false"}}"
    }
    return """
        <!doctype html>
        <html class="${if (darkMode) "dark" else "light"}">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <link rel="stylesheet" href="katex/katex.min.css" />
          <style>
            :root { color-scheme: ${if (darkMode) "dark" else "light"}; }
            html, body { margin: 0; padding: 0; background: transparent; }
            body {
              color: $text;
              font-family: -apple-system, "Roboto", "Noto Sans", system-ui, sans-serif;
              font-size: ${bodyFontSizePx}px;
              line-height: 1.55;
              ${if (webViewProseWraps) "overflow-wrap: anywhere;" else ""}
              ${if (webViewProseWraps) "word-break: break-word;" else ""}
              -webkit-text-size-adjust: 100%;
            }
            #content { padding: 0; }
            #content > *:first-child { margin-top: 0; }
            #content > *:last-child { margin-bottom: 0; }
            p { margin: 0 0 8px; }
            a { color: $link; text-decoration: none; }
            h1, h2, h3, h4, h5, h6 { margin: 14px 0 8px; line-height: 1.3; font-weight: 600; }
            h1 { font-size: ${bodyFontSizePx + 6}px; }
            h2 { font-size: ${bodyFontSizePx + 4}px; }
            h3 { font-size: ${bodyFontSizePx + 2}px; }
            ul, ol { margin: 0 0 8px; padding-left: 22px; }
            li { margin: 2px 0; }
            blockquote { margin: 0 0 8px; padding: 2px 0 2px 12px; border-left: 3px solid $border; opacity: 0.85; }
            hr { border: none; border-top: 1px solid $border; margin: 12px 0; }
            code { font-family: "JetBrains Mono", "Roboto Mono", monospace; font-size: ${codeFontSize}px; }
            :not(pre) > code { background: $codeBg; color: $codeFg; padding: 1px 5px; border-radius: 4px; }
            .$horizontalScrollClass {
              max-width: 100%;
              overflow-x: auto;
              overflow-y: hidden;
              -webkit-overflow-scrolling: touch;
              touch-action: pan-x;
              overscroll-behavior-x: contain;
            }
            pre {
              background: $codeBg;
              color: $codeFg;
              padding: 10px 12px;
              border-radius: 8px;
              margin: 0 0 8px;
              white-space: pre;
            }
            pre code { background: transparent; padding: 0; }
            .table-scroll { margin: 0 0 8px; }
            table { border-collapse: collapse; width: max-content; }
            th, td { border: 1px solid $border; padding: 6px 10px; text-align: left; }
            th { background: $codeBg; }
            img { max-width: 100%; height: auto; border-radius: 6px; }
            .katex { color: $text; }
            .katex-display { margin: 8px 0; padding: 2px 0; }
            .katex-display > .katex { white-space: nowrap; }
          </style>
          <script>$markedJs</script>
          <script>$katexJs</script>
        </head>
        <body>
          <div id="content"></div>
          <script>
            (function() {
              var bridge = window.AndroidMarkdownBridge;
              var lastH = 0;
              function report() {
                requestAnimationFrame(function() {
                  var rect = document.body.getBoundingClientRect();
                  if (rect.width < 10) return;
                  var h = Math.ceil(rect.height);
                  if (h > 0 && h !== lastH) {
                    lastH = h;
                    if (bridge && bridge.onHeight) bridge.onHeight(h);
                  }
                });
              }
              function renderMath(token) {
                try {
                  return katex.renderToString(token.s, { displayMode: token.d, throwOnError: false, output: 'html' });
                } catch (e) {
                  return token.d ? ('$$' + token.s + '$$') : ('\\(' + token.s + '\\)');
                }
              }
              function prepareHorizontalScrollables() {
                document.querySelectorAll('table').forEach(function(table) {
                  if (table.parentElement && table.parentElement.classList.contains('table-scroll')) return;
                  var wrapper = document.createElement('div');
                  wrapper.className = '$tableWrapperClasses';
                  table.parentNode.insertBefore(wrapper, table);
                  wrapper.appendChild(table);
                });
                document.querySelectorAll('$structuredScrollSelector').forEach(function(node) {
                  node.classList.add('$horizontalScrollClass');
                });
              }
              try {
                if (window.marked && marked.setOptions) {
                  marked.setOptions({ gfm: true, breaks: false });
                }
                var source = $sourceLiteral;
                var mathList = $mathJson;
                var html = window.marked ? marked.parse(source) : source;
                for (var i = 0; i < mathList.length; i++) {
                  var ph = 'xMJXMATH' + mathList[i].i + 'HTAMXJMx';
                  html = html.split(ph).join(renderMath(mathList[i]));
                }
                document.getElementById('content').innerHTML = html;
                prepareHorizontalScrollables();
              } catch (err) {
                document.getElementById('content').textContent = $sourceLiteral;
              }
              if (window.ResizeObserver) {
                try { new ResizeObserver(report).observe(document.body); } catch (e) {}
              }
              if (document.fonts && document.fonts.ready) {
                document.fonts.ready.then(report);
              }
              report();
              setTimeout(report, 120);
              setTimeout(report, 400);
              setTimeout(report, 1000);
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
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
            '\u2028' -> builder.append("\\u2028")
            '\u2029' -> builder.append("\\u2029")
            '<' -> builder.append("\\u003C")
            '>' -> builder.append("\\u003E")
            '&' -> builder.append("\\u0026")
            else -> {
                if (char.code < 0x20) {
                    builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
    }
    builder.append('"')
    return builder.toString()
}
