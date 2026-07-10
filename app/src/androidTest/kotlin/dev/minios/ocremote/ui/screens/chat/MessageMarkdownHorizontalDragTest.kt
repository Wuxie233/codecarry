package dev.minios.ocremote.ui.screens.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.lifecycle.lifecycleScope
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    fun lateCodeBlockInChunkedKatexWebViewsSupportsHorizontalAndVerticalDrag() {
        assertEquals(
            MessageMarkdownRoute.KatexWebView,
            resolveMessageMarkdownRoute(TallChunkedKatexMarkdown),
        )
        assertTrue(
            "expected a production-scale 29k fixture, length=${TallChunkedKatexMarkdown.length}",
            TallChunkedKatexMarkdown.length in 28_000..30_000,
        )
        val chatMessage = ChatMessage(
            message = Message.Assistant(
                id = TallMessageId,
                sessionId = TallSessionId,
                time = TimeInfo(created = 1L),
            ),
            parts = listOf(
                Part.Text(
                    id = TallTextPartId,
                    sessionId = TallSessionId,
                    messageId = TallMessageId,
                    text = TallChunkedKatexMarkdown,
                ),
            ),
        )
        val messageRows = planChatMessageRows(listOf(chatMessage))
        val targetRowIndex = messageRows.indexOfFirst { row ->
            row is ChatMessageRow.TextChunk && TallChunkedCodePrefix in row.markdown.chunk.source
        }
        val targetRow = messageRows[targetRowIndex] as ChatMessageRow.TextChunk
        assertTrue("expected 5-8 top-level rows, count=${messageRows.size}", messageRows.size in 5..8)
        assertTrue("expected target code in a later top-level row, index=$targetRowIndex", targetRowIndex > 0)

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
                        itemsIndexed(messageRows, key = { _, row -> row.key }) { _, row ->
                            val chunkRow = row as ChatMessageRow.TextChunk
                            MessageMarkdownContent(
                                markdown = chunkRow.markdown.chunk.source,
                                textColor = Color.Black,
                                isUser = false,
                                modifier = Modifier
                                    .testTag("$TopLevelRowTag:${row.key}")
                                    .fillMaxWidth(),
                                plannedChunk = chunkRow.markdown,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        waitForKatexWebView()

        lateinit var scrollJob: Job
        rule.runOnIdle {
            scrollJob = rule.activity.lifecycleScope.launch {
                parentScrollState.scrollToItem(targetRowIndex)
            }
        }
        rule.waitUntil(LoadTimeoutMillis) { scrollJob.isCompleted }
        rule.waitForIdle()

        val target = waitForChunkedTarget(
            targetPrefix = TallChunkedCodePrefix,
            parentScrollState = parentScrollState,
            targetRowIndex = targetRowIndex,
            targetRowKey = targetRow.key,
        )
        assertEquals("expected target WebView in its own top-level row", targetRow.key, target.rowKey)
        assertTrue(
            "expected bounded top-level target item, size=${target.rowSize}",
            target.rowSize in 1 until MaxTopLevelItemHeightPx,
        )
        val targetWebViewHeight = readViewHeight(target.webView)
        assertTrue(
            "expected bounded target WebView, height=$targetWebViewHeight",
            targetWebViewHeight in 1 until MaxChunkWebViewHeightPx,
        )
        assertEquals("target pre should start at the left edge", 0, target.metrics.scrollLeft)
        assertTrue(
            "expected the target pre near the top of its chunk, cssTop=${target.metrics.top}",
            target.metrics.top < MaxTargetPreCssTopPx,
        )

        val geometry = target.webView.preScreenGeometry(target.metrics)
        assertTrue(
            "expected the later-chunk target pre to be visible, geometry=$geometry",
            geometry.visibleBounds.contains(
                geometry.targetBounds.centerX(),
                geometry.targetBounds.centerY(),
            ),
        )

        injectSwipe(
            startX = geometry.targetBounds.right - GestureEdgeInsetPx,
            startY = geometry.targetBounds.centerY().toFloat(),
            endX = geometry.targetBounds.left + GestureEdgeInsetPx,
            endY = geometry.targetBounds.centerY().toFloat(),
        )
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val afterHorizontal = readTargetPreMetrics(target.webView, TallChunkedCodePrefix)
        assertTrue(
            "expected a physical horizontal drag on the later chunk pre to increase scrollLeft, " +
                "before=${target.metrics.scrollLeft}, after=${afterHorizontal.scrollLeft}, geometry=$geometry",
            afterHorizontal.scrollLeft > target.metrics.scrollLeft,
        )

        val parentPositionBeforeVertical = readPosition(parentScrollState)
        injectSwipe(
            startX = geometry.targetBounds.centerX().toFloat(),
            startY = geometry.targetBounds.bottom - GestureEdgeInsetPx,
            endX = geometry.targetBounds.centerX().toFloat(),
            endY = geometry.targetBounds.top + GestureEdgeInsetPx,
        )
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)
        assertTrue(
            "expected a vertical drag over the later KaTeX chunk to scroll the Compose parent, " +
                "before=$parentPositionBeforeVertical, after=${readPosition(parentScrollState)}",
            readPosition(parentScrollState) > parentPositionBeforeVertical,
        )
    }

    @Test
    fun verticalDragOnKatexWebViewScrollsComposeParent() {
        val fixture = setKatexWebViewContent()
        waitForWidePre(fixture.webView)
        waitUntilCanScrollForward(fixture.parentScrollState)
        assertEquals("parent should start at the top", 0, readPosition(fixture.parentScrollState).offset)

        val visibleBounds = fixture.webView.globalVisibleBounds()
        rule.onNodeWithTag(KatexMessageTag).performTouchInput {
            drag(
                start = Offset(centerX, visibleBounds.height() - GestureEdgeInsetPx),
                end = Offset(centerX, GestureEdgeInsetPx),
            )
        }
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        var itemSize: Int? = null
        var parentPosition = LazyPosition(0, 0)
        var canScrollForward = false
        rule.runOnIdle {
            itemSize = fixture.parentScrollState.layoutInfo.visibleItemsInfo.firstOrNull()?.size
            parentPosition = fixture.parentScrollState.position()
            canScrollForward = fixture.parentScrollState.canScrollForward
        }
        val fixtureWebViewHeight = readViewHeight(fixture.webView)
        assertTrue(
            "expected a vertical drag over the WebView to scroll its Compose parent, " +
                "parentScroll=${parentPosition.offset}, " +
                "canScrollForward=$canScrollForward, " +
                "itemSize=$itemSize, webViewHeight=$fixtureWebViewHeight, " +
                "visibleHeight=${visibleBounds.height()}",
            parentPosition.offset > 0,
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

    private fun readTargetPreMetrics(webView: WebView, targetPrefix: String): PreMetrics {
        val values = evaluateArray(
            webView,
            """
                (function() {
                  var pre = Array.from(document.querySelectorAll('pre')).find(function(node) {
                    return node.textContent.indexOf('$targetPrefix') >= 0;
                  });
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

    private fun readKatexNodeCount(webView: WebView): Int {
        val values = evaluateArray(webView, "[document.querySelectorAll('.katex').length]")
        return if (values.length() == 1) values.getInt(0) else 0
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
        var viewWidth = 0
        rule.runOnIdle {
            getLocationOnScreen(location)
            check(getGlobalVisibleRect(visibleBounds)) { "WebView should be visible" }
            viewWidth = width
        }
        val pageScale = viewWidth.toFloat() / metrics.viewportWidth
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

    private fun WebView.preScreenGeometry(metrics: PreMetrics): PreScreenGeometry {
        val location = IntArray(2)
        val visibleBounds = Rect()
        var viewWidth = 0
        rule.runOnIdle {
            getLocationOnScreen(location)
            getGlobalVisibleRect(visibleBounds)
            viewWidth = width
        }
        val pageScale = viewWidth.toFloat() / metrics.viewportWidth
        return PreScreenGeometry(
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
        val pointerProperties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val pointerCoords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            1,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        ).also { event ->
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

    private fun waitForKatexWebView(): WebView {
        val deadline = SystemClock.uptimeMillis() + LoadTimeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            var attached = emptyList<WebView>()
            rule.runOnIdle {
                attached = findWebViews(rule.activity.window.decorView).filter { it.height > 0 }
            }
            attached.firstOrNull { readKatexNodeCount(it) > 0 }?.let { return it }
            SystemClock.sleep(DomPollMillis)
        }
        throw AssertionError("expected an attached segmented WebView with rendered KaTeX")
    }

    private fun waitForChunkedTarget(
        targetPrefix: String,
        parentScrollState: LazyListState,
        targetRowIndex: Int,
        targetRowKey: String,
    ): ChunkedTarget {
        val deadline = SystemClock.uptimeMillis() + LoadTimeoutMillis
        var latest = emptyList<WebView>()
        var latestHeights = emptyList<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            lateinit var scrollJob: Job
            rule.runOnIdle {
                scrollJob = rule.activity.lifecycleScope.launch {
                    parentScrollState.scrollToItem(targetRowIndex)
                }
            }
            rule.waitUntil(LoadTimeoutMillis) { scrollJob.isCompleted }
            rule.waitForIdle()
            var targetItemSize: Int? = null
            rule.runOnIdle {
                latest = findWebViews(rule.activity.window.decorView)
                latestHeights = latest.map(WebView::getHeight)
                targetItemSize = parentScrollState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == targetRowKey }
                    ?.size
            }
            if (targetItemSize != null && latest.isNotEmpty()) {
                latest.forEach { webView ->
                    val metrics = readTargetPreMetrics(webView, targetPrefix)
                    if (metrics.clientWidth > 0 && metrics.scrollWidth > metrics.clientWidth) {
                        return ChunkedTarget(
                            webView = webView,
                            metrics = metrics,
                            rowKey = targetRowKey,
                            rowSize = requireNotNull(targetItemSize),
                        )
                    }
                }
            }
            SystemClock.sleep(DomPollMillis)
        }
        throw AssertionError(
            "expected an attached chunk WebView with a wide target pre, " +
                "actual=${latest.size}, heights=$latestHeights",
        )
    }

    private fun waitUntilCanScrollForward(state: LazyListState) {
        val deadline = SystemClock.uptimeMillis() + LoadTimeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            var canScrollForward = false
            rule.runOnIdle { canScrollForward = state.canScrollForward }
            if (canScrollForward) return
            SystemClock.sleep(DomPollMillis)
        }
        throw AssertionError("expected LazyColumn to become vertically scrollable")
    }

    private fun readPosition(state: LazyListState): LazyPosition {
        var position = LazyPosition(0, 0)
        rule.runOnIdle { position = state.position() }
        return position
    }

    private fun readViewHeight(view: View): Int {
        var height = 0
        rule.runOnIdle { height = view.height }
        return height
    }

    private fun findWebViews(view: View): List<WebView> {
        if (view is WebView) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return buildList {
            for (index in 0 until view.childCount) {
                addAll(findWebViews(view.getChildAt(index)))
            }
        }
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

    private data class ChunkedTarget(
        val webView: WebView,
        val metrics: PreMetrics,
        val rowKey: String,
        val rowSize: Int,
    )

    private data class LazyPosition(val index: Int, val offset: Int) : Comparable<LazyPosition> {
        override fun compareTo(other: LazyPosition): Int {
            return compareValuesBy(this, other, LazyPosition::index, LazyPosition::offset)
        }
    }

    private fun LazyListState.position(): LazyPosition =
        LazyPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)

    private data class PreScreenGeometry(
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
        const val MaxChunkWebViewHeightPx = 20_000
        const val MaxTopLevelItemHeightPx = 20_000
        const val MaxTargetPreCssTopPx = 200f
        const val MessageTag = "wide-markdown-message"
        const val KatexMessageTag = "katex-markdown-message"
        const val PreMetricCount = 10
        const val PhysicalGestureStepCount = 30
        const val PhysicalGestureStepMillis = 30L
        const val ReasoningTag = "wide-reasoning-message"
        const val VerticalParentTag = "vertical-markdown-parent"
        const val TallChunkedCodePrefix = "信号是连续的、网络只吃向量"
        const val TallMessageId = "assistant-production-scale"
        const val TallSessionId = "session-production-scale"
        const val TallTextPartId = "assistant-production-scale-text"
        const val TopLevelRowTag = "top-level-message-row"

        val KatexMarkdown = """
            ```text
            /root/CODE/oc-remote/${"0123456789abcdef".repeat(36)}
            ```

            Display math: \(x^2 + y^2 = z^2\).

            ${List(80) { index -> "Vertical content line $index keeps the WebView taller than its Compose viewport." }.joinToString("\n\n")}
        """.trimIndent()

        val TallChunkedKatexMarkdown = buildString {
            append("Display math: \\[x^2 + y^2 = z^2\\]\n\n")
            repeat(100) { index ->
                append("Substantial lead-in paragraph ")
                append(index)
                append(' ')
                append("content ".repeat(20))
                append("\n\n")
            }
            append("```text\n")
            append(TallChunkedCodePrefix)
            append("0123456789abcdef".repeat(36))
            append("\n```\n\n")
            repeat(45) { index ->
                append("Trailing paragraph ")
                append(index)
                append(' ')
                append("content ".repeat(20))
                if (index != 44) append("\n\n")
            }
        }
    }
}
