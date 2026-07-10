package dev.minios.ocremote.ui.screens.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageMarkdownHorizontalDragTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun katexWebViewCodeBlockCanBeDraggedHorizontallyInsideVerticalComposeParent() {
        val fixture = setKatexWebViewContent()
        val before = waitForWidePre(fixture.webView)

        assertEquals(MessageMarkdownRoute.KatexWebView, resolveMessageMarkdownRoute(KatexMarkdown))
        assertEquals("pre should start at the left edge", 0, before.scrollLeft)

        val target = fixture.webView.preDragCoordinates(before)
        rule.onNodeWithTag(KatexMessageTag).performTouchInput {
            drag(
                start = Offset(target.right, target.centerY),
                end = Offset(target.left, target.centerY),
            )
        }
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val after = readPreMetrics(fixture.webView)
        assertTrue(
            "expected a physical horizontal drag on the wide WebView <pre> to increase scrollLeft, " +
                "before=${before.scrollLeft}, after=${after.scrollLeft}, " +
                "clientWidth=${after.clientWidth}, scrollWidth=${after.scrollWidth}",
            after.scrollLeft > before.scrollLeft,
        )
    }

    @Test
    fun lateCodeBlockInVeryTallKatexWebViewCanBeDraggedHorizontally() {
        val fixture = setKatexWebViewContent(TallKatexMarkdown)
        val before = waitForWidePre(fixture.webView)
        val targetCenterY = fixture.webView.targetCenterY(before)
        val viewportCenterY = rule.onNodeWithTag(VerticalParentTag).fetchSemanticsNode().boundsInRoot.center.y
        assertEquals("expected exactly one intended <pre> in the tall DOM", 1, before.preCount)
        assertTrue(
            "expected the target <pre> late in the tall DOM, metrics=$before",
            before.top > TallTargetMinTopPx,
        )

        runBlocking {
            fixture.parentScrollState.scrollBy(targetCenterY - viewportCenterY)
        }
        rule.waitForIdle()

        val geometry = fixture.webView.screenGeometry(before)
        assertTrue(
            "expected real-message-scale WebView document height, metrics=$before, geometry=$geometry",
            before.documentHeight in TallDocumentMinHeightPx..TallDocumentMaxHeightPx,
        )
        assertTrue(
            "expected the tall WebView top far above the screen, geometry=$geometry",
            geometry.webViewTop < TallWebViewMaxTopPx,
        )
        assertTrue(
            "expected DOM <pre> rect to map into the visible WebView, geometry=$geometry",
            geometry.visibleBounds.contains(
                geometry.targetBounds.centerX(),
                geometry.targetBounds.centerY(),
            ),
        )
        assertEquals("pre should start at the left edge", 0, before.scrollLeft)

        injectSwipe(
            startX = geometry.targetBounds.right - GestureEdgeInsetPx,
            startY = geometry.targetBounds.centerY().toFloat(),
            endX = geometry.targetBounds.left + GestureEdgeInsetPx,
            endY = geometry.targetBounds.centerY().toFloat(),
        )
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val after = readPreMetrics(fixture.webView)
        assertTrue(
            "expected a physical horizontal drag on the late wide <pre> to increase scrollLeft, " +
                "before=${before.scrollLeft}, after=${after.scrollLeft}, geometry=$geometry",
            after.scrollLeft > before.scrollLeft,
        )
    }

    @Test
    fun verticalDragOnKatexWebViewScrollsComposeParent() {
        val fixture = setKatexWebViewContent()
        waitForWidePre(fixture.webView)
        rule.waitUntil(LoadTimeoutMillis) { fixture.parentScrollState.canScrollForward }
        assertEquals("parent should start at the top", 0, fixture.parentScrollState.firstVisibleItemScrollOffset)

        val visibleBounds = fixture.webView.globalVisibleBounds()
        rule.onNodeWithTag(KatexMessageTag).performTouchInput {
            drag(
                start = Offset(centerX, visibleBounds.height() - GestureEdgeInsetPx),
                end = Offset(centerX, GestureEdgeInsetPx),
            )
        }
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val itemSize = fixture.parentScrollState.layoutInfo.visibleItemsInfo.firstOrNull()?.size
        assertTrue(
            "expected a vertical drag over the WebView to scroll its Compose parent, " +
                "parentScroll=${fixture.parentScrollState.firstVisibleItemScrollOffset}, " +
                "canScrollForward=${fixture.parentScrollState.canScrollForward}, " +
                "itemSize=$itemSize, webViewHeight=${fixture.webView.height}, " +
                "visibleHeight=${visibleBounds.height()}",
            fixture.parentScrollState.firstVisibleItemScrollOffset > 0,
        )
    }

    @Test
    fun wideAssistantParagraphCanBeDraggedHorizontally() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = "→ ${"abcdefghijklmnopqrstuvwxyz".repeat(8)}",
                        textColor = Color.Black,
                        isUser = false,
                        modifier = Modifier
                            .testTag(MessageTag)
                            .width(220.dp)
                            .padding(8.dp),
                    )
                }
            }
        }

        val node = rule.onNodeWithTag(MessageTag)
        val before = node.captureToImage()
        node.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = node.captureToImage()

        assertTrue(
            "expected horizontal drag to visibly shift wide markdown content",
            changedPixels(before, after) > 250,
        )
    }

    @Test
    fun assistantParagraphWithMediumInlineCodeTokensCanBeDraggedHorizontally() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = """
                            已完成安装和验证。

                            `opencode debug skill` 已能列出全部 10 个新 skill；每个目录都只有 1 个 `SKILL.md`，且 frontmatter `name` 和目录名匹配。最后确认 `debug skill` 总计输出 80 个 skill，stderr 为空。没有改 `opencode.json` 或 `oh-my-openagent.json`，因为 OpenCode 本来就会扫描 `~/.config/opencode/skills/**/SKILL.md`。
                        """.trimIndent(),
                        textColor = Color.Black,
                        isUser = false,
                        modifier = Modifier
                            .testTag(MessageTag)
                            .width(220.dp)
                            .padding(8.dp),
                    )
                }
            }
        }

        val node = rule.onNodeWithTag(MessageTag)
        val before = node.captureToImage()
        node.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = node.captureToImage()
        val changed = changedPixels(before, after)

        assertTrue(
            "expected horizontal drag to shift assistant markdown with medium inline-code tokens, changed=$changed",
            changed > 250,
        )
    }

    @Test
    fun reviewStyleParagraphWithFileLinksKeepsWrappedLayout() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = """
                            **Findings**

                            1. High risk: the timeout path leaves the queued command behind.
                            [DispatchAsync](/root/CODE/RimWorld/RimWorldMod_RimWorldAI/RimWorldMCP/McpCommandQueue.cs:72) can still execute after the caller has already seen a timeout.
                        """.trimIndent(),
                        textColor = Color.Black,
                        isUser = false,
                        modifier = Modifier
                            .testTag(MessageTag)
                            .width(220.dp)
                            .padding(8.dp),
                    )
                }
            }
        }

        rule.onAllNodesWithTag(WidePlainTextTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun wideReasoningBlockCanBeDraggedHorizontally() {
        rule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .testTag(ReasoningTag)
                        .width(220.dp),
                ) {
                    ReasoningBlock("→ ${"abcdefghijklmnopqrstuvwxyz".repeat(8)}")
                }
            }
        }

        val node = rule.onNodeWithTag(ReasoningTag)
        val before = node.captureToImage()
        rule.onNodeWithTag(WidePlainTextTag).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = node.captureToImage()
        val changed = changedPixels(before, after)

        assertTrue(
            "expected horizontal drag to visibly shift wide reasoning content, changed=$changed",
            changed > 250,
        )
    }

    @Test
    fun wideCodeBlockCanBeDraggedHorizontally() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = """
                            Real response:

                            ```text
                            /root/CODE/hackathon/pulse/docs/design-system.md -> ${"0123456789abcdef".repeat(12)}
                            ```
                        """.trimIndent(),
                        textColor = Color.Black,
                        isUser = false,
                        modifier = Modifier
                            .testTag(MessageTag)
                            .width(220.dp)
                            .padding(8.dp),
                    )
                }
            }
        }

        val node = rule.onNodeWithTag(MessageTag)
        val before = node.captureToImage()
        node.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = node.captureToImage()
        val changed = changedPixels(before, after)

        assertTrue(
            "expected horizontal drag to visibly shift wide code block content, changed=$changed",
            changed > 250,
        )
    }

    private fun setKatexWebViewContent(markdown: String = KatexMarkdown): KatexFixture {
        lateinit var parentScrollState: LazyListState
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    parentScrollState = rememberLazyListState()
                    LazyColumn(
                        state = parentScrollState,
                        modifier = Modifier
                            .testTag(VerticalParentTag)
                            .width(340.dp)
                            .height(360.dp),
                    ) {
                        item {
                            MessageMarkdownContent(
                                markdown = markdown,
                                textColor = Color.Black,
                                isUser = false,
                                modifier = Modifier
                                    .testTag(KatexMessageTag)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        lateinit var webView: WebView
        rule.runOnIdle {
            webView = requireNotNull(findWebView(rule.activity.window.decorView)) {
                "expected MessageMarkdownContent to select the KaTeX WebView route"
            }
        }
        return KatexFixture(webView, parentScrollState)
    }

    private fun waitForWidePre(webView: WebView): PreMetrics {
        val deadline = SystemClock.uptimeMillis() + LoadTimeoutMillis
        var latest = PreMetrics.Empty
        while (SystemClock.uptimeMillis() < deadline) {
            latest = readPreMetrics(webView)
            if (latest.clientWidth > 0 && latest.scrollWidth > latest.clientWidth) {
                return latest
            }
            SystemClock.sleep(DomPollMillis)
        }
        throw AssertionError("expected a rendered wide <pre>, latest=$latest")
    }

    private fun readPreMetrics(webView: WebView): PreMetrics {
        val values = evaluateArray(
            webView,
            """
                (function() {
                  var pre = document.querySelector('pre');
                  if (!pre) return [];
                  var rect = pre.getBoundingClientRect();
                  return [
                    document.querySelectorAll('pre').length,
                    pre.scrollLeft,
                    pre.clientWidth,
                    pre.scrollWidth,
                    rect.left,
                    rect.top,
                    rect.width,
                    rect.height,
                    document.documentElement.clientWidth,
                    document.body.getBoundingClientRect().height
                  ];
                })()
            """.trimIndent(),
        )
        if (values.length() != PreMetricCount) return PreMetrics.Empty
        return PreMetrics(
            preCount = values.getInt(0),
            scrollLeft = values.getInt(1),
            clientWidth = values.getInt(2),
            scrollWidth = values.getInt(3),
            left = values.getDouble(4).toFloat(),
            top = values.getDouble(5).toFloat(),
            width = values.getDouble(6).toFloat(),
            height = values.getDouble(7).toFloat(),
            viewportWidth = values.getInt(8),
            documentHeight = values.getDouble(9).toFloat(),
        )
    }

    private fun evaluateArray(webView: WebView, script: String): JSONArray {
        var output: String? = null
        rule.runOnIdle {
            webView.evaluateJavascript(script) { value -> output = value }
        }
        rule.waitUntil(LoadTimeoutMillis) { output != null }
        return JSONArray(output)
    }

    private fun WebView.preDragCoordinates(metrics: PreMetrics): DragCoordinates {
        val location = IntArray(2)
        val visibleBounds = Rect()
        rule.runOnIdle {
            getLocationOnScreen(location)
            check(getGlobalVisibleRect(visibleBounds)) { "WebView should be visible" }
        }
        val pageScale = width.toFloat() / metrics.viewportWidth
        val centerY = location[1] + (metrics.top + metrics.height / 2f) * pageScale
        check(centerY in visibleBounds.top.toFloat()..visibleBounds.bottom.toFloat()) {
            "target <pre> should be visible, centerY=$centerY, visibleBounds=$visibleBounds"
        }
        return DragCoordinates(
            left = (metrics.left + metrics.width * 0.2f) * pageScale,
            right = (metrics.left + metrics.width * 0.8f) * pageScale,
            centerY = (metrics.top + metrics.height / 2f) * pageScale,
        )
    }

    private fun WebView.targetCenterY(metrics: PreMetrics): Float =
        (metrics.top + metrics.height / 2f) * width.toFloat() / metrics.viewportWidth

    private fun WebView.screenGeometry(metrics: PreMetrics): ScreenGeometry {
        val location = IntArray(2)
        val visibleBounds = Rect()
        rule.runOnIdle {
            getLocationOnScreen(location)
            check(getGlobalVisibleRect(visibleBounds)) { "WebView should be visible" }
        }
        val pageScale = width.toFloat() / metrics.viewportWidth
        return ScreenGeometry(
            webViewHeight = height,
            webViewTop = location[1],
            visibleBounds = visibleBounds,
            targetBounds = Rect(
                (location[0] + metrics.left * pageScale).toInt(),
                (location[1] + metrics.top * pageScale).toInt(),
                (location[0] + (metrics.left + metrics.width) * pageScale).toInt(),
                (location[1] + (metrics.top + metrics.height) * pageScale).toInt(),
            ),
        )
    }

    private fun WebView.globalVisibleBounds(): Rect = Rect().also { bounds ->
        rule.runOnIdle {
            check(getGlobalVisibleRect(bounds)) { "WebView should be visible" }
        }
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.drag(start: Offset, end: Offset) {
        down(start)
        repeat(GestureStepCount) { index ->
            val fraction = (index + 1f) / GestureStepCount
            advanceEventTime(GestureStepMillis)
            moveTo(
                Offset(
                    x = start.x + (end.x - start.x) * fraction,
                    y = start.y + (end.y - start.y) * fraction,
                ),
            )
        }
        advanceEventTime(GestureStepMillis)
        up()
    }

    private fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        uiAutomation.injectTouch(MotionEvent.ACTION_DOWN, downTime, startX, startY)
        repeat(PhysicalGestureStepCount) { index ->
            SystemClock.sleep(PhysicalGestureStepMillis)
            val fraction = (index + 1f) / PhysicalGestureStepCount
            uiAutomation.injectTouch(
                action = MotionEvent.ACTION_MOVE,
                downTime = downTime,
                x = startX + (endX - startX) * fraction,
                y = startY + (endY - startY) * fraction,
            )
        }
        uiAutomation.injectTouch(MotionEvent.ACTION_UP, downTime, endX, endY)
    }

    private fun android.app.UiAutomation.injectTouch(
        action: Int,
        downTime: Long,
        x: Float,
        y: Float,
    ) {
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also { event ->
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(injectInputEvent(event, true)) { "expected UiAutomation to inject action=$action" }
            event.recycle()
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun changedPixels(before: ImageBitmap, after: ImageBitmap): Int {
        val beforePixels = before.toPixelMap()
        val afterPixels = after.toPixelMap()
        var changed = 0
        for (x in 0 until minOf(before.width, after.width) step 4) {
            for (y in 0 until minOf(before.height, after.height) step 4) {
                if (beforePixels[x, y].toArgb() != afterPixels[x, y].toArgb()) {
                    changed++
                }
            }
        }
        return changed
    }

    private data class KatexFixture(
        val webView: WebView,
        val parentScrollState: LazyListState,
    )

    private data class PreMetrics(
        val preCount: Int,
        val scrollLeft: Int,
        val clientWidth: Int,
        val scrollWidth: Int,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val viewportWidth: Int,
        val documentHeight: Float,
    ) {
        companion object {
            val Empty = PreMetrics(0, 0, 0, 0, 0f, 0f, 0f, 0f, 1, 0f)
        }
    }

    private data class DragCoordinates(
        val left: Float,
        val right: Float,
        val centerY: Float,
    )

    private data class ScreenGeometry(
        val webViewHeight: Int,
        val webViewTop: Int,
        val visibleBounds: Rect,
        val targetBounds: Rect,
    )

    private companion object {
        const val DomPollMillis = 100L
        const val GestureEdgeInsetPx = 32f
        const val GestureSettleMillis = 300L
        const val GestureStepCount = 12
        const val GestureStepMillis = 16L
        const val LoadTimeoutMillis = 10_000L
        const val MessageTag = "wide-markdown-message"
        const val KatexMessageTag = "katex-markdown-message"
        const val PreMetricCount = 10
        const val PhysicalGestureStepCount = 30
        const val PhysicalGestureStepMillis = 30L
        const val ReasoningTag = "wide-reasoning-message"
        const val TallDocumentMaxHeightPx = 82_000f
        const val TallDocumentMinHeightPx = 65_000f
        const val TallWebViewMaxTopPx = -60_000
        const val TallTargetMinTopPx = 60_000
        const val VerticalParentTag = "vertical-markdown-parent"

        val KatexMarkdown = """
            ```text
            /root/CODE/oc-remote/${"0123456789abcdef".repeat(36)}
            ```

            Display math: \(x^2 + y^2 = z^2\).

            ${List(80) { index -> "Vertical content line $index keeps the WebView taller than its Compose viewport." }.joinToString("\n\n")}
        """.trimIndent()

        val TallKatexMarkdown = buildString {
            repeat(1_200) { index ->
                append("Tall document lead-in paragraph ")
                append(index)
                append(" keeps the target code block near the real message offset.\n\n")
            }
            append("```text\n")
            append("/root/CODE/oc-remote/")
            append("0123456789abcdef".repeat(36))
            append("\n```\n\n")
            append("Display math: \\(x^2 + y^2 = z^2\\).\n\n")
            repeat(180) { index ->
                append("Tall document trailing paragraph ")
                append(index)
                append(" keeps the full WebView near the real message height.\n\n")
            }
        }
    }
}
