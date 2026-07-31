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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
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
@androidx.compose.material3.ExperimentalMaterial3Api
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
    fun laterRealMarkdownTableCanBePhysicallyDraggedInMathMessage() {
        setKatexWebViewContent(TwoTableKatexMarkdown)
        val first = waitForVisibleTableTarget(FirstTableMarker)
        val target = waitForVisibleTableTarget(LaterTableMarker)

        assertEquals(MessageMarkdownRoute.KatexWebView, resolveMessageMarkdownRoute(TwoTableKatexMarkdown))
        assertEquals("expected marked to render both GFM tables", 2, readTotalTableCount())
        assertTrue(
            "expected the target table after the first table in production render order, first=$first, target=$target",
            target.renderOrder > first.renderOrder || target.metrics.targetTableIndex > first.metrics.targetTableIndex,
        )
        assertTrue("expected KaTeX to render the message math", readTotalKatexCount() > 0)
        assertTrue("expected the later table to use the production scroll wrapper", target.metrics.hasScrollWrapper)
        assertEquals("later table should start at the left edge", 0, target.metrics.scrollLeft)

        val swipe = prepareTableSwipe(target, LaterTableMarker)
        injectSwipe(swipe.startX, swipe.startY, swipe.endX, swipe.endY)
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val after = readTableMetrics(target.webView, LaterTableMarker)
        assertTrue(
            "expected a physical finger drag on the later real table to increase scrollLeft, " +
                "before=${target.metrics.scrollLeft}, after=${after.scrollLeft}, swipe=$swipe, target=$target",
            after.scrollLeft > target.metrics.scrollLeft,
        )
    }

    @Test
    fun firstRealMarkdownTableCanBePhysicallyDraggedInMathMessage() {
        setKatexWebViewContent(TwoTableKatexMarkdown)
        val target = waitForVisibleTableTarget(FirstTableMarker)

        assertEquals(MessageMarkdownRoute.KatexWebView, resolveMessageMarkdownRoute(TwoTableKatexMarkdown))
        assertEquals("expected the first real table in DOM order", 0, target.metrics.targetTableIndex)
        assertTrue("expected the first table to use the production scroll wrapper", target.metrics.hasScrollWrapper)
        assertEquals("first table should start at the left edge", 0, target.metrics.scrollLeft)

        val swipe = prepareTableSwipe(target, FirstTableMarker)
        injectSwipe(swipe.startX, swipe.startY, swipe.endX, swipe.endY)
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val after = readTableMetrics(target.webView, FirstTableMarker)
        assertTrue(
            "expected a physical finger drag on the first real table to increase scrollLeft, " +
                "before=${target.metrics.scrollLeft}, after=${after.scrollLeft}, swipe=$swipe, target=$target",
            after.scrollLeft > 0,
        )
    }

    @Test
    fun firstRealMarkdownTableCanBeDraggedInsideUserSwipeToDismissBubble() {
        lateinit var dismissState: androidx.compose.material3.SwipeToDismissBoxState
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { false },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            Box(modifier = Modifier.fillMaxWidth().height(360.dp))
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .testTag(SwipeDismissMessageTag)
                                .width(340.dp)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            MessageMarkdownContent(
                                markdown = TwoTableKatexMarkdown,
                                textColor = Color.Black,
                                isUser = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        val target = waitForVisibleTableTarget(FirstTableMarker)
        assertEquals("expected the first real table in DOM order", 0, target.metrics.targetTableIndex)
        assertTrue("expected the first table to use the production scroll wrapper", target.metrics.hasScrollWrapper)
        assertEquals("first table should start at the left edge", 0, target.metrics.scrollLeft)

        val swipe = prepareTableSwipe(target, FirstTableMarker)
        injectSwipe(swipe.startX, swipe.startY, swipe.endX, swipe.endY)
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val after = readTableMetrics(target.webView, FirstTableMarker)
        var finalDismissValue = SwipeToDismissBoxValue.Settled
        rule.runOnIdle { finalDismissValue = dismissState.currentValue }
        assertTrue(
            "expected a physical drag on the first table inside the user bubble to increase scrollLeft, " +
                "before=${target.metrics.scrollLeft}, after=${after.scrollLeft}, swipe=$swipe, target=$target",
            after.scrollLeft > target.metrics.scrollLeft,
        )
        assertEquals("table drag should not dismiss the user bubble", SwipeToDismissBoxValue.Settled, finalDismissValue)
    }

    @Test
    fun firstAndSecondComposeMarkdownTablesCanBeDraggedIndependently() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = TwoComposeTableMarkdown,
                        textColor = Color.Black,
                        isUser = false,
                        modifier = Modifier
                            .testTag(ComposeTablesMessageTag)
                            .width(340.dp)
                            .padding(12.dp),
                    )
                }
            }
        }
        rule.waitForIdle()

        val horizontalScrollMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val scrollNodes = rule.onAllNodes(horizontalScrollMatcher, useUnmergedTree = true)
        scrollNodes.assertCountEquals(2)
        val before = scrollNodes.fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        }

        scrollNodes[0].performTouchInput { swipeLeft() }
        scrollNodes[1].performTouchInput { swipeLeft() }
        rule.waitForIdle()

        val after = scrollNodes.fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        }
        assertTrue("first Compose table should scroll horizontally, before=$before after=$after", after[0] > before[0])
        assertTrue("second Compose table should scroll horizontally, before=$before after=$after", after[1] > before[1])
    }

    @Test
    fun longPlannedComposeRowsKeepLaterTablePhysicallyScrollableAndParentVerticalScroll() {
        val message = ChatMessage(
            message = Message.Assistant(
                id = LongComposeMessageId,
                sessionId = LongComposeSessionId,
                time = TimeInfo(created = 1L),
            ),
            parts = listOf(
                Part.Text(
                    id = LongComposeTextPartId,
                    sessionId = LongComposeSessionId,
                    messageId = LongComposeMessageId,
                    text = LongComposeMarkdown,
                ),
            ),
        )
        val rows = planChatMessageRows(listOf(message))
        val targetRowIndex = rows.indexOfFirst { row ->
            row is ChatMessageRow.TextChunk && LongComposeTableMarker in row.markdown.chunk.source
        }
        assertTrue("expected a long fixture to split into Compose rows", rows.size > 1)
        assertTrue("expected the table in a later Compose row, rows=$rows", targetRowIndex > 0)
        assertTrue(
            "expected a non-math table row",
            rows[targetRowIndex] is ChatMessageRow.TextChunk &&
                (rows[targetRowIndex] as ChatMessageRow.TextChunk).markdown.math.isEmpty(),
        )

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
                            .testTag(LongComposeParentTag)
                            .width(340.dp)
                            .height(360.dp),
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                            val chunkRow = row as ChatMessageRow.TextChunk
                            MessageMarkdownContent(
                                markdown = chunkRow.markdown.chunk.source,
                                textColor = Color.Black,
                                isUser = false,
                                modifier = Modifier
                                    .testTag("$LongComposeRowTag:${row.key}")
                                    .fillMaxWidth(),
                                plannedChunk = chunkRow.markdown,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        scrollToItem(parentScrollState, targetRowIndex)
        rule.waitForIdle()
        rule.onNodeWithText(LongComposeTableMarker, useUnmergedTree = true)
            .assertExists()
            .performScrollTo()
        rule.waitForIdle()

        val horizontalMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val tableNode = rule.onAllNodes(horizontalMatcher)
            .fetchSemanticsNodes()
            .filter { node -> node.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f }
            .maxByOrNull { node -> node.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() }
            ?: throw AssertionError("expected a horizontal scroll semantics node for the later table")
        assertTrue("expected visible table bounds, bounds=${tableNode.boundsInRoot}", tableNode.boundsInRoot.width > 24f)
        val beforeRange = tableNode.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val maxRange = tableNode.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue()
        assertTrue("expected a wide later table, maxRange=$maxRange", maxRange > beforeRange)
        val tableBounds = tableNode.boundsInRoot
        val screenBounds = tableBounds.toScreenBounds()
        check(screenBounds.width() > 24 && screenBounds.height() > 24) {
            "expected a usable table semantics bounds, screenBounds=$screenBounds"
        }
        val horizontalStartX = screenBounds.right - TableGestureInsetPx
        val horizontalEndX = screenBounds.left + TableGestureInsetPx
        val horizontalY = screenBounds.centerY().toFloat()
        injectTimedSwipe(
            startX = horizontalStartX,
            startY = horizontalY,
            endX = horizontalEndX,
            endY = horizontalY,
        )
        rule.waitForIdle()
        val afterHorizontalNode = rule.onAllNodes(horizontalMatcher)
            .fetchSemanticsNodes()
            .filter { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f }
            .maxByOrNull { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() }
            ?: throw AssertionError("expected the later table semantics node after horizontal drag")
        val afterHorizontal = afterHorizontalNode.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue(
            "expected physical drag over the later Compose table to advance horizontal range, " +
                "before=$beforeRange, after=$afterHorizontal, bounds=$screenBounds",
            afterHorizontal > beforeRange,
        )

        waitUntilCanScrollForward(parentScrollState)
        val parentBefore = readPosition(parentScrollState)
        val verticalBounds = rule.onAllNodes(horizontalMatcher)
            .fetchSemanticsNodes()
            .filter { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f }
            .maxByOrNull { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() }
            ?.boundsInRoot
            ?.toScreenBounds()
            ?: throw AssertionError("expected the later table semantics node before vertical drag")
        injectTimedSwipe(
            startX = verticalBounds.centerX().toFloat(),
            startY = verticalBounds.bottom - TableGestureInsetPx,
            endX = verticalBounds.centerX().toFloat(),
            endY = verticalBounds.top + TableGestureInsetPx,
        )
        rule.waitForIdle()
        val parentAfter = readPosition(parentScrollState)
        assertTrue(
            "expected vertical drag over Compose table to advance parent LazyColumn, " +
                "before=$parentBefore, after=$parentAfter, bounds=$verticalBounds",
            parentAfter > parentBefore,
        )
    }

    @Test
    fun composeMarkdownPreservesSeparatedAndNestedOrderedListSemantics() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = SeparatedOrderedListsMarkdown,
                        textColor = Color.Black,
                        isUser = false,
                        modifier = Modifier
                            .testTag(OrderedListMessageTag)
                            .width(340.dp),
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("first ordered item", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("second ordered item", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("third ordered item", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("nested first item", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("nested second item", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1. ", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("2. ", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3. ", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("7. ", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("8. ", useUnmergedTree = true).assertExists()

        assertTrue(
            "expected three separated ordered-list item groups",
            rule.onAllNodesWithText("ordered item", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().size >= 3,
        )
    }

    @Test
    fun deepRowOfOversizedRealMarkdownTableCanBePhysicallyDraggedInMathMessage() {
        assertEquals(
            MessageMarkdownRoute.KatexWebView,
            resolveMessageMarkdownRoute(OversizedTableKatexMarkdown),
        )
        val chatMessage = ChatMessage(
            message = Message.Assistant(
                id = OversizedTableMessageId,
                sessionId = OversizedTableSessionId,
                time = TimeInfo(created = 1L),
            ),
            parts = listOf(
                Part.Text(
                    id = OversizedTableTextPartId,
                    sessionId = OversizedTableSessionId,
                    messageId = OversizedTableMessageId,
                    text = OversizedTableKatexMarkdown,
                ),
            ),
        )
        val messageRows = planChatMessageRows(listOf(chatMessage))
        val targetRowIndex = messageRows.indexOfFirst { row ->
            row is ChatMessageRow.TextChunk && OversizedTableDeepRowMarker in row.markdown.chunk.source
        }
        assertTrue("expected the deep table row in a later top-level chunk", targetRowIndex > 0)
        val targetRow = messageRows[targetRowIndex] as ChatMessageRow.TextChunk

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
                            .testTag(OversizedTableParentTag)
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
        val mathWebView = waitForKatexWebView()
        assertTrue("expected KaTeX to render before navigating to a deep table chunk", readKatexNodeCount(mathWebView) > 0)

        val target = waitForChunkedTableTarget(
            targetMarker = OversizedTableDeepRowMarker,
            parentScrollState = parentScrollState,
            targetRowIndex = targetRowIndex,
            targetRowKey = targetRow.key,
        )
        assertTrue("expected a real marked table", target.metrics.tableCount > 0)
        assertTrue("expected multiple rendered rows around the deep source row", target.metrics.rowCount > 3)
        assertTrue(
            "expected the deep source row to remain a later rendered row in its table chunk, " +
                "index=${target.metrics.targetRowIndex}",
            target.metrics.targetRowIndex > 2,
        )
        assertTrue("expected the production table scroll wrapper", target.metrics.hasScrollWrapper)
        assertEquals("deep-row table should start at the left edge", 0, target.metrics.scrollLeft)

        val swipe = prepareTableSwipe(target, OversizedTableDeepRowMarker)
        injectSwipe(swipe.startX, swipe.startY, swipe.endX, swipe.endY)
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val after = readTableMetrics(target.webView, OversizedTableDeepRowMarker)
        assertTrue(
            "expected a physical finger drag over the deep table row to increase scrollLeft, " +
                "before=${target.metrics.scrollLeft}, after=${after.scrollLeft}, swipe=$swipe, target=$target",
            after.scrollLeft > target.metrics.scrollLeft,
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
        assertTrue(
            "expected bounded target WebView, target=$target",
            target.view.height in 1 until MaxChunkWebViewHeightPx,
        )
        assertEquals("target pre should start at the left edge", 0, target.metrics.scrollLeft)
        assertTrue(
            "expected the target pre near the top of its chunk, cssTop=${target.metrics.top}",
            target.metrics.top < MaxTargetPreCssTopPx,
        )

        val horizontalSwipe = prepareChunkedSwipe(
            stableTarget = target,
            targetPrefix = TallChunkedCodePrefix,
            parentScrollState = parentScrollState,
            targetRowKey = targetRow.key,
            direction = SwipeDirection.Horizontal,
        )

        injectSwipe(
            startX = horizontalSwipe.startX,
            startY = horizontalSwipe.startY,
            endX = horizontalSwipe.endX,
            endY = horizontalSwipe.endY,
        )
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)

        val afterHorizontal = readTargetPreMetrics(target.webView, TallChunkedCodePrefix)
        assertTrue(
            "expected a physical horizontal drag on the later chunk pre to increase scrollLeft, " +
                "before=${target.metrics.scrollLeft}, after=${afterHorizontal.scrollLeft}, " +
                "swipe=$horizontalSwipe, stableTarget=$target",
            afterHorizontal.scrollLeft > target.metrics.scrollLeft,
        )

        val verticalTarget = waitForChunkedTarget(
            targetPrefix = TallChunkedCodePrefix,
            parentScrollState = parentScrollState,
            targetRowIndex = targetRowIndex,
            targetRowKey = targetRow.key,
        )
        val verticalSwipe = prepareChunkedSwipe(
            stableTarget = verticalTarget,
            targetPrefix = TallChunkedCodePrefix,
            parentScrollState = parentScrollState,
            targetRowKey = targetRow.key,
            direction = SwipeDirection.Vertical,
        )
        val parentPositionBeforeVertical = readPosition(parentScrollState)
        injectSwipe(
            startX = verticalSwipe.startX,
            startY = verticalSwipe.startY,
            endX = verticalSwipe.endX,
            endY = verticalSwipe.endY,
        )
        rule.waitForIdle()
        SystemClock.sleep(GestureSettleMillis)
        val parentPositionAfterVertical = readPosition(parentScrollState)
        assertTrue(
            "expected a vertical drag over the later KaTeX chunk to scroll the Compose parent, " +
                "before=$parentPositionBeforeVertical, after=$parentPositionAfterVertical, " +
                "swipe=$verticalSwipe, stableTarget=$verticalTarget",
            parentPositionAfterVertical > parentPositionBeforeVertical,
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
    fun releaseUrlWrapsWithoutHorizontalDrag() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = ReleaseUrlMarkdown,
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

        assertEquals(
            ChatOverflowTreatment.Wrap,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.MarkdownParagraph,
                text = ReleaseUrlMarkdown,
            ),
        )
        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).assertCountEquals(0)

        val message = rule.onNodeWithTag(MessageTag)
        val messageBounds = message.fetchSemanticsNode().boundsInRoot
        val urlBounds = rule.onNodeWithText(ReleaseUrl, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "expected the URL layout to stay within the message width, message=$messageBounds, url=$urlBounds",
            urlBounds.width <= messageBounds.width,
        )

        val before = message.captureToImage()
        message.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = message.captureToImage()
        assertEquals(
            "expected horizontal swipe not to shift wrapped release URL pixels",
            0,
            changedPixels(before, after),
        )
    }

    @Test
    fun assistantParagraphWithLongInlineTokensWrapsWithoutHorizontalDrag() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = """
                            部署数据库时请使用 `registry.example.com/team/postgres:18-alpine` 镜像，并将版本更新为 `1.151-regression-fix-202607111622`。完成后继续检查 service health。
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

        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).assertCountEquals(0)

        val node = rule.onNodeWithTag(MessageTag)
        val before = node.captureToImage()
        node.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = node.captureToImage()

        assertEquals(
            "expected horizontal swipe not to shift mixed-language prose pixels",
            0,
            changedPixels(before, after),
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

    private fun readTableMetrics(webView: WebView, targetMarker: String): TableMetrics {
        val values = evaluateArray(
            webView,
            """
                (function() {
                  var row = Array.from(document.querySelectorAll('tr')).find(function(node) {
                    return node.textContent.indexOf('$targetMarker') >= 0;
                  });
                  if (!row) return [];
                  var table = row.closest('table');
                  var wrapper = table && table.parentElement;
                  if (!table || !wrapper) return [];
                  var rowRect = row.getBoundingClientRect();
                  var wrapperRect = wrapper.getBoundingClientRect();
                  return [
                    document.querySelectorAll('table').length,
                    document.querySelectorAll('.katex').length,
                    Array.from(document.querySelectorAll('table')).indexOf(table),
                    Array.from(table.querySelectorAll('tr')).indexOf(row),
                    table.querySelectorAll('tr').length,
                    wrapper.scrollLeft,
                    wrapper.clientWidth,
                    wrapper.scrollWidth,
                    wrapperRect.left,
                    rowRect.top,
                    wrapperRect.width,
                    rowRect.height,
                    document.documentElement.clientWidth,
                    wrapper.classList.contains('table-scroll') && wrapper.classList.contains('markdown-horizontal-scroll')
                  ];
                })()
            """.trimIndent(),
        )
        if (values.length() != TableMetricCount) return TableMetrics.Empty
        return TableMetrics(
            tableCount = values.getInt(0),
            katexCount = values.getInt(1),
            targetTableIndex = values.getInt(2),
            targetRowIndex = values.getInt(3),
            rowCount = values.getInt(4),
            scrollLeft = values.getInt(5),
            clientWidth = values.getInt(6),
            scrollWidth = values.getInt(7),
            left = values.getDouble(8).toFloat(),
            top = values.getDouble(9).toFloat(),
            width = values.getDouble(10).toFloat(),
            height = values.getDouble(11).toFloat(),
            viewportWidth = values.getInt(12),
            hasScrollWrapper = values.getBoolean(13),
        )
    }

    private fun readTotalTableCount(): Int = findAttachedWebViews().sumOf { webView ->
        val values = evaluateArray(webView, "[document.querySelectorAll('table').length]")
        if (values.length() == 1) values.getInt(0) else 0
    }

    private fun readTotalKatexCount(): Int = findAttachedWebViews().sumOf(::readKatexNodeCount)

    private fun waitForVisibleTableTarget(
        targetMarker: String,
        expectedWebView: WebView? = null,
        rowKey: String? = null,
    ): TableTarget {
        val deadline = SystemClock.uptimeMillis() + LoadTimeoutMillis
        var latest: TableTarget? = null
        var stableSamples = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = findAttachedWebViews()
            val sample = candidates.mapIndexedNotNull { index, webView ->
                if (expectedWebView != null && webView !== expectedWebView) return@mapIndexedNotNull null
                val metrics = readTableMetrics(webView, targetMarker)
                if (metrics == TableMetrics.Empty) null else TableTarget(
                    webView = webView,
                    metrics = metrics,
                    view = snapshot(webView),
                    renderOrder = index,
                    rowKey = rowKey,
                )
            }.firstOrNull { it.isReady() }
            if (sample != null) {
                stableSamples = if (latest?.isStableWith(sample) == true) stableSamples + 1 else 1
                latest = sample
                if (stableSamples >= RequiredStableSamples) return sample
            } else {
                stableSamples = 0
            }
            SystemClock.sleep(DomPollMillis)
        }
        throw AssertionError(
            "expected a stable visible real table containing '$targetMarker', latest=$latest",
        )
    }

    private fun waitForChunkedTableTarget(
        targetMarker: String,
        parentScrollState: LazyListState,
        targetRowIndex: Int,
        targetRowKey: String,
    ): TableTarget {
        val deadline = SystemClock.uptimeMillis() + LoadTimeoutMillis
        var latest: TableTarget? = null
        var stableSamples = 0
        var targetScrollOffset = 0
        while (SystemClock.uptimeMillis() < deadline) {
            scrollToItem(parentScrollState, targetRowIndex, targetScrollOffset)
            val sample = findAttachedWebViews().mapIndexedNotNull { index, webView ->
                val metrics = readTableMetrics(webView, targetMarker)
                if (metrics == TableMetrics.Empty) null else TableTarget(
                    webView = webView,
                    metrics = metrics,
                    view = snapshot(webView),
                    renderOrder = index,
                    rowKey = targetRowKey,
                )
            }.firstOrNull { it.isReady() }
            if (sample != null && sample.targetIsVisible()) {
                stableSamples = if (latest?.isStableWith(sample) == true) stableSamples + 1 else 1
                latest = sample
                if (stableSamples >= RequiredStableSamples) return sample
            } else if (sample != null) {
                val scale = sample.view.width.toFloat() / sample.metrics.viewportWidth
                targetScrollOffset = maxOf(0, (sample.metrics.top * scale - TargetViewportInsetPx).toInt())
                stableSamples = 0
                latest = sample
            } else {
                stableSamples = 0
            }
            SystemClock.sleep(DomPollMillis)
        }
        throw AssertionError(
            "expected a stable deep-row table target, rowKey=$targetRowKey, latest=$latest",
        )
    }

    private fun findAttachedWebViews(): List<WebView> {
        var views = emptyList<WebView>()
        rule.runOnIdle {
            views = findWebViews(rule.activity.window.decorView).filter { it.isAttachedToWindow && it.height > 0 }
        }
        return views
    }

    private fun snapshot(webView: WebView): ViewSnapshot {
        lateinit var snapshot: ViewSnapshot
        rule.runOnIdle { snapshot = webView.snapshotOnUiThread() }
        return snapshot
    }

    private fun prepareTableSwipe(stableTarget: TableTarget, targetMarker: String): ScreenSwipe {
        val current = waitForVisibleTableTarget(targetMarker, stableTarget.webView, stableTarget.rowKey)
        check(stableTarget.isStableWith(current)) {
            "table target changed before physical input, stable=$stableTarget, current=$current"
        }
        val geometry = current.screenGeometry()
        val intersection = Rect(geometry.targetBounds)
        check(intersection.intersect(geometry.visibleBounds)) {
            "table row has no visible intersection before physical input, target=$current"
        }
        check(intersection.width() > GestureEdgeInsetPx * 2 && intersection.height() > TableGestureInsetPx * 2) {
            "table row intersection lacks a gesture interior, intersection=$intersection, target=$current"
        }
        val swipe = ScreenSwipe(
            startX = intersection.right - GestureEdgeInsetPx,
            startY = intersection.centerY().toFloat(),
            endX = intersection.left + GestureEdgeInsetPx,
            endY = intersection.centerY().toFloat(),
            geometry = geometry,
        )
        val hit = readTableTargetHit(current, targetMarker, swipe.startX, swipe.startY)
        check(hit.hitsTarget) {
            "physical input point does not hit the target table row, hit=$hit, swipe=$swipe, target=$current"
        }
        return swipe
    }

    private fun readTableTargetHit(
        target: TableTarget,
        targetMarker: String,
        screenX: Float,
        screenY: Float,
    ): TargetHit {
        val scale = target.view.width.toFloat() / target.metrics.viewportWidth
        val clientX = (screenX - target.view.bounds.left) / scale
        val clientY = (screenY - target.view.bounds.top) / scale
        val values = evaluateArray(
            target.webView,
            """
                (function() {
                  var row = Array.from(document.querySelectorAll('tr')).find(function(node) {
                    return node.textContent.indexOf('$targetMarker') >= 0;
                  });
                  var hit = document.elementFromPoint($clientX, $clientY);
                  return [!!row && !!hit && (hit === row || row.contains(hit)), hit ? hit.tagName : '', hit ? hit.className : ''];
                })()
            """.trimIndent(),
        )
        return TargetHit(
            hitsTarget = values.length() == TargetHitMetricCount && values.getBoolean(0),
            tagName = if (values.length() == TargetHitMetricCount) values.getString(1) else "",
            className = if (values.length() == TargetHitMetricCount) values.getString(2) else "",
            clientX = clientX,
            clientY = clientY,
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

    private fun injectTimedSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        uiAutomation.injectTouch(MotionEvent.ACTION_DOWN, downTime, startX, startY)
        repeat(TimedGestureStepCount) { index ->
            SystemClock.sleep(TimedGestureStepMillis)
            val fraction = (index + 1f) / TimedGestureStepCount
            uiAutomation.injectTouch(
                action = MotionEvent.ACTION_MOVE,
                downTime = downTime,
                x = startX + (endX - startX) * fraction,
                y = startY + (endY - startY) * fraction,
            )
        }
        uiAutomation.injectTouch(MotionEvent.ACTION_UP, downTime, endX, endY)
    }

    private fun ComposeRect.toScreenBounds(): Rect {
        val rootLocation = IntArray(2)
        rule.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(rootLocation)
        return Rect(
            (left + rootLocation[0]).toInt(),
            (top + rootLocation[1]).toInt(),
            (right + rootLocation[0]).toInt(),
            (bottom + rootLocation[1]).toInt(),
        )
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
        var latest: ChunkedTarget? = null
        var stableSamples = 0
        while (SystemClock.uptimeMillis() < deadline) {
            scrollToItem(parentScrollState, targetRowIndex)
            val sample = captureChunkedTarget(parentScrollState, targetRowKey, targetPrefix)
            if (sample != null && sample.isReady()) {
                stableSamples = if (latest?.isStableWith(sample) == true) stableSamples + 1 else 1
                latest = sample
                if (stableSamples >= RequiredStableSamples) return sample
            } else {
                stableSamples = 0
                latest = sample
            }
            SystemClock.sleep(DomPollMillis)
        }
        throw AssertionError(
            "expected $RequiredStableSamples consecutive aligned/stable target samples, " +
                "targetRowKey=$targetRowKey, targetRowIndex=$targetRowIndex, " +
                "stableSamples=$stableSamples, latest=$latest",
        )
    }

    private fun scrollToItem(state: LazyListState, index: Int, scrollOffset: Int = 0) {
        lateinit var scrollJob: Job
        rule.runOnIdle {
            scrollJob = rule.activity.lifecycleScope.launch { state.scrollToItem(index, scrollOffset) }
        }
        rule.waitUntil(LoadTimeoutMillis) { scrollJob.isCompleted }
        rule.waitForIdle()
    }

    private fun captureChunkedTarget(
        state: LazyListState,
        targetRowKey: String,
        targetPrefix: String,
        expectedWebView: WebView? = null,
    ): ChunkedTarget? {
        var listSnapshot: ListSnapshot? = null
        var candidates = emptyList<ViewSnapshot>()
        rule.runOnIdle {
            val layoutInfo = state.layoutInfo
            val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetRowKey }
            listSnapshot = item?.let {
                ListSnapshot(
                    rowKey = targetRowKey,
                    rowOffset = it.offset,
                    rowSize = it.size,
                    viewportStartOffset = layoutInfo.viewportStartOffset,
                    viewportEndOffset = layoutInfo.viewportEndOffset,
                    position = state.position(),
                )
            }
            candidates = findWebViews(rule.activity.window.decorView)
                .asSequence()
                .filter { expectedWebView == null || it === expectedWebView }
                .map { it.snapshotOnUiThread() }
                .toList()
        }
        val list = listSnapshot ?: return null
        candidates.forEach { view ->
            val metrics = readTargetPreMetrics(view.webView, targetPrefix)
            if (metrics != PreMetrics.Empty) {
                var currentView: ViewSnapshot? = null
                var currentList: ListSnapshot? = null
                rule.runOnIdle {
                    currentView = view.webView.snapshotOnUiThread()
                    val layoutInfo = state.layoutInfo
                    currentList = layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == targetRowKey }
                        ?.let {
                            ListSnapshot(
                                rowKey = targetRowKey,
                                rowOffset = it.offset,
                                rowSize = it.size,
                                viewportStartOffset = layoutInfo.viewportStartOffset,
                                viewportEndOffset = layoutInfo.viewportEndOffset,
                                position = state.position(),
                            )
                        }
                }
                val verifiedView = currentView
                val verifiedList = currentList
                if (verifiedView != null && verifiedList != null && view == verifiedView && list == verifiedList) {
                    return ChunkedTarget(verifiedView.webView, metrics, verifiedList, verifiedView)
                }
            }
        }
        return null
    }

    private fun WebView.snapshotOnUiThread(): ViewSnapshot {
        val bounds = Rect()
        val visibleBounds = Rect()
        getGlobalVisibleRect(visibleBounds)
        getGlobalBounds(bounds)
        return ViewSnapshot(
            webView = this,
            identity = System.identityHashCode(this),
            tag = tag?.toString(),
            width = width,
            height = height,
            bounds = bounds,
            visibleBounds = visibleBounds,
            isAttached = isAttachedToWindow,
            hasWindowFocus = hasWindowFocus(),
            isShown = isShown,
        )
    }

    private fun View.getGlobalBounds(outBounds: Rect) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        outBounds.set(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun prepareChunkedSwipe(
        stableTarget: ChunkedTarget,
        targetPrefix: String,
        parentScrollState: LazyListState,
        targetRowKey: String,
        direction: SwipeDirection,
    ): ScreenSwipe {
        val current = captureChunkedTarget(
            state = parentScrollState,
            targetRowKey = targetRowKey,
            targetPrefix = targetPrefix,
            expectedWebView = stableTarget.webView,
        ) ?: throw AssertionError("target disappeared before $direction injection, stableTarget=$stableTarget")
        check(stableTarget.isStableWith(current)) {
            "target changed after stable readiness and before $direction injection, " +
                "stable=$stableTarget, current=$current"
        }
        check(current.isReady()) { "target no longer ready before $direction injection, current=$current" }

        val geometry = current.screenGeometry()
        val intersection = Rect(geometry.targetBounds)
        check(intersection.intersect(geometry.visibleBounds)) {
            "target has no visible intersection before $direction injection, current=$current, geometry=$geometry"
        }
        check(intersection.width() > GestureEdgeInsetPx * 2 && intersection.height() > GestureEdgeInsetPx * 2) {
            "target intersection lacks gesture interior, intersection=$intersection, current=$current"
        }
        val swipe = when (direction) {
            SwipeDirection.Horizontal -> ScreenSwipe(
                startX = intersection.right - GestureEdgeInsetPx,
                startY = intersection.centerY().toFloat(),
                endX = intersection.left + GestureEdgeInsetPx,
                endY = intersection.centerY().toFloat(),
                geometry = geometry,
            )
            SwipeDirection.Vertical -> ScreenSwipe(
                startX = intersection.centerX().toFloat(),
                startY = intersection.bottom - GestureEdgeInsetPx,
                endX = intersection.centerX().toFloat(),
                endY = intersection.top + GestureEdgeInsetPx,
                geometry = geometry,
            )
        }
        val hit = readTargetHit(current, targetPrefix, swipe.startX, swipe.startY)
        check(hit.hitsTarget) {
            "injection point does not hit target pre for $direction, hit=$hit, swipe=$swipe, current=$current"
        }
        return swipe
    }

    private fun readTargetHit(
        target: ChunkedTarget,
        targetPrefix: String,
        screenX: Float,
        screenY: Float,
    ): TargetHit {
        val scale = target.view.width.toFloat() / target.metrics.viewportWidth
        val clientX = (screenX - target.view.bounds.left) / scale
        val clientY = (screenY - target.view.bounds.top) / scale
        val values = evaluateArray(
            target.webView,
            """
                (function() {
                  var pre = Array.from(document.querySelectorAll('pre')).find(function(node) {
                    return node.textContent.indexOf('$targetPrefix') >= 0;
                  });
                  var hit = document.elementFromPoint($clientX, $clientY);
                  return [!!pre && !!hit && (hit === pre || pre.contains(hit)), hit ? hit.tagName : '', hit ? hit.className : ''];
                })()
            """.trimIndent(),
        )
        return TargetHit(
            hitsTarget = values.length() == TargetHitMetricCount && values.getBoolean(0),
            tagName = if (values.length() == TargetHitMetricCount) values.getString(1) else "",
            className = if (values.length() == TargetHitMetricCount) values.getString(2) else "",
            clientX = clientX,
            clientY = clientY,
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
        fun isStableWith(other: PreMetrics): Boolean =
            preCount == other.preCount &&
                scrollLeft == other.scrollLeft &&
                clientWidth == other.clientWidth &&
                scrollWidth == other.scrollWidth &&
                kotlin.math.abs(left - other.left) <= GeometryTolerancePx &&
                kotlin.math.abs(top - other.top) <= GeometryTolerancePx &&
                kotlin.math.abs(width - other.width) <= GeometryTolerancePx &&
                kotlin.math.abs(height - other.height) <= GeometryTolerancePx &&
                viewportWidth == other.viewportWidth &&
                kotlin.math.abs(documentHeight - other.documentHeight) <= GeometryTolerancePx

        companion object {
            val Empty = PreMetrics(0, 0, 0, 0, 0f, 0f, 0f, 0f, 1, 0f)
        }
    }

    private data class TableMetrics(
        val tableCount: Int,
        val katexCount: Int,
        val targetTableIndex: Int,
        val targetRowIndex: Int,
        val rowCount: Int,
        val scrollLeft: Int,
        val clientWidth: Int,
        val scrollWidth: Int,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val viewportWidth: Int,
        val hasScrollWrapper: Boolean,
    ) {
        fun isStableWith(other: TableMetrics): Boolean =
            tableCount == other.tableCount &&
                katexCount == other.katexCount &&
                targetTableIndex == other.targetTableIndex &&
                targetRowIndex == other.targetRowIndex &&
                rowCount == other.rowCount &&
                scrollLeft == other.scrollLeft &&
                clientWidth == other.clientWidth &&
                scrollWidth == other.scrollWidth &&
                kotlin.math.abs(left - other.left) <= GeometryTolerancePx &&
                kotlin.math.abs(top - other.top) <= GeometryTolerancePx &&
                kotlin.math.abs(width - other.width) <= GeometryTolerancePx &&
                kotlin.math.abs(height - other.height) <= GeometryTolerancePx &&
                viewportWidth == other.viewportWidth &&
                hasScrollWrapper == other.hasScrollWrapper

        companion object {
            val Empty = TableMetrics(0, 0, -1, -1, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 1, false)
        }
    }

    private data class TableTarget(
        val webView: WebView,
        val metrics: TableMetrics,
        val view: ViewSnapshot,
        val renderOrder: Int,
        val rowKey: String? = null,
    ) {
        fun isReady(): Boolean =
            view.width > 0 &&
                view.height > 0 &&
                view.isAttached &&
                view.hasWindowFocus &&
                view.isShown &&
                !view.visibleBounds.isEmpty &&
                metrics.clientWidth > 0 &&
                metrics.scrollWidth > metrics.clientWidth &&
                metrics.viewportWidth > 0 &&
                metrics.height > 0f &&
                metrics.hasScrollWrapper

        fun isStableWith(other: TableTarget): Boolean =
            webView === other.webView &&
                renderOrder == other.renderOrder &&
                rowKey == other.rowKey &&
                view.isStableWith(other.view) &&
                metrics.isStableWith(other.metrics)

        fun targetIsVisible(): Boolean {
            val geometry = screenGeometry()
            return Rect(geometry.targetBounds).intersect(geometry.visibleBounds)
        }

        fun screenGeometry(): PreScreenGeometry {
            val pageScale = view.width.toFloat() / metrics.viewportWidth
            return PreScreenGeometry(
                visibleBounds = Rect(view.visibleBounds),
                targetBounds = Rect(
                    (view.bounds.left + metrics.left * pageScale).toInt(),
                    (view.bounds.top + metrics.top * pageScale).toInt(),
                    (view.bounds.left + (metrics.left + metrics.width) * pageScale).toInt(),
                    (view.bounds.top + (metrics.top + metrics.height) * pageScale).toInt(),
                ),
            )
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
        val list: ListSnapshot,
        val view: ViewSnapshot,
    ) {
        val rowKey: String get() = list.rowKey
        val rowSize: Int get() = list.rowSize

        fun isReady(): Boolean =
            kotlin.math.abs(list.rowOffset - list.viewportStartOffset) <= RowAlignmentTolerancePx &&
                list.rowSize in 1 until MaxTopLevelItemHeightPx &&
                view.width > 0 &&
                view.height in 1 until MaxChunkWebViewHeightPx &&
                view.isAttached &&
                view.hasWindowFocus &&
                view.isShown &&
                !view.visibleBounds.isEmpty &&
                metrics.clientWidth > 0 &&
                metrics.scrollWidth > metrics.clientWidth &&
                metrics.viewportWidth > 0 &&
                metrics.documentHeight > 0f &&
                metrics.height > 0f

        fun isStableWith(other: ChunkedTarget): Boolean =
            isSameGeneration(other) &&
                list == other.list &&
                view.isStableWith(other.view) &&
                metrics.isStableWith(other.metrics)

        fun isSameGeneration(other: ChunkedTarget): Boolean =
            webView === other.webView &&
                view.identity == other.view.identity &&
                view.tag == other.view.tag &&
                list.rowKey == other.list.rowKey

        fun screenGeometry(): PreScreenGeometry {
            val pageScale = view.width.toFloat() / metrics.viewportWidth
            return PreScreenGeometry(
                visibleBounds = Rect(view.visibleBounds),
                targetBounds = Rect(
                    (view.bounds.left + metrics.left * pageScale).toInt(),
                    (view.bounds.top + metrics.top * pageScale).toInt(),
                    (view.bounds.left + (metrics.left + metrics.width) * pageScale).toInt(),
                    (view.bounds.top + (metrics.top + metrics.height) * pageScale).toInt(),
                ),
            )
        }
    }

    private data class ListSnapshot(
        val rowKey: String,
        val rowOffset: Int,
        val rowSize: Int,
        val viewportStartOffset: Int,
        val viewportEndOffset: Int,
        val position: LazyPosition,
    )

    private data class ViewSnapshot(
        val webView: WebView,
        val identity: Int,
        val tag: String?,
        val width: Int,
        val height: Int,
        val bounds: Rect,
        val visibleBounds: Rect,
        val isAttached: Boolean,
        val hasWindowFocus: Boolean,
        val isShown: Boolean,
    ) {
        fun isStableWith(other: ViewSnapshot): Boolean =
            webView === other.webView &&
                identity == other.identity &&
                tag == other.tag &&
                width == other.width &&
                height == other.height &&
                bounds == other.bounds &&
                visibleBounds == other.visibleBounds &&
                isAttached == other.isAttached &&
                hasWindowFocus == other.hasWindowFocus &&
                isShown == other.isShown
    }

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

    private data class ScreenSwipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val geometry: PreScreenGeometry,
    )

    private data class TargetHit(
        val hitsTarget: Boolean,
        val tagName: String,
        val className: String,
        val clientX: Float,
        val clientY: Float,
    )

    private enum class SwipeDirection {
        Horizontal,
        Vertical,
    }

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
        const val SwipeDismissMessageTag = "swipe-dismiss-markdown-message"
        const val ComposeTablesMessageTag = "compose-tables-markdown-message"
        const val LaterTableMarker = "LATER_TABLE_ROW"
        const val FirstTableMarker = "FIRST_TABLE_ROW"
        const val PreMetricCount = 10
        const val PhysicalGestureStepCount = 30
        const val PhysicalGestureStepMillis = 30L
        const val TimedGestureStepCount = 10
        const val TimedGestureStepMillis = 20L
        const val RequiredStableSamples = 3
        const val RowAlignmentTolerancePx = 2
        const val ReasoningTag = "wide-reasoning-message"
        const val OversizedTableDeepRowIndex = 65
        const val OversizedTableDeepRowMarker = "DEEP_TABLE_ROW_065"
        const val OversizedTableMessageId = "assistant-oversized-table"
        const val OversizedTableParentTag = "oversized-table-parent"
        const val OversizedTableSessionId = "session-oversized-table"
        const val OversizedTableTextPartId = "assistant-oversized-table-text"
        const val TableGestureInsetPx = 4f
        const val TableMetricCount = 14
        const val TargetViewportInsetPx = 80f
        const val ReleaseUrl = "https://github.com/Wuxie233/oc-remote/releases/tag/v1.7.42"
        const val ReleaseUrlMarkdown = "v1.7.42 已发布:\n\n$ReleaseUrl"
        const val TargetHitMetricCount = 3
        const val VerticalParentTag = "vertical-markdown-parent"
        const val TallChunkedCodePrefix = "信号是连续的、网络只吃向量"
        const val TallMessageId = "assistant-production-scale"
        const val TallSessionId = "session-production-scale"
        const val TallTextPartId = "assistant-production-scale-text"
        const val TopLevelRowTag = "top-level-message-row"
        const val LongComposeMessageId = "assistant-long-compose"
        const val LongComposeParentTag = "long-compose-parent"
        const val LongComposeSessionId = "session-long-compose"
        const val LongComposeTableMarker = "LATE_COMPOSE_TABLE_ROW"
        const val LongComposeTextPartId = "assistant-long-compose-text"
        const val LongComposeRowTag = "long-compose-row"
        const val OrderedListMessageTag = "ordered-list-markdown-message"
        const val GeometryTolerancePx = 0.5f

        val KatexMarkdown = """
            ```text
            /root/CODE/oc-remote/${"0123456789abcdef".repeat(36)}
            ```

            Display math: \(x^2 + y^2 = z^2\).

            ${List(80) { index -> "Vertical content line $index keeps the WebView taller than its Compose viewport." }.joinToString("\n\n")}
        """.trimIndent()

        val TwoTableKatexMarkdown = """
            Display math: \[x^2 + y^2 = z^2\]

            | Marker | Long payload A | Long payload B | Long payload C |
            | --- | --- | --- | --- |
            | $FirstTableMarker | ${"first-column-".repeat(8)} | ${"first-second-".repeat(8)} | ${"first-third-".repeat(8)} |

            Text separating the two real GFM tables.

            | Marker | Long payload A | Long payload B | Long payload C |
            | --- | --- | --- | --- |
            | $LaterTableMarker | ${"later-column-".repeat(8)} | ${"later-second-".repeat(8)} | ${"later-third-".repeat(8)} |
        """.trimIndent()

        val TwoComposeTableMarkdown = """
            | Marker | Long payload A | Long payload B | Long payload C |
            | --- | --- | --- | --- |
            | FIRST_COMPOSE_TABLE_ROW | ${"first-column-".repeat(8)} | ${"first-second-".repeat(8)} | ${"first-third-".repeat(8)} |

            Text separating the two real Compose GFM tables.

            | Marker | Long payload A | Long payload B | Long payload C |
            | --- | --- | --- | --- |
            | SECOND_COMPOSE_TABLE_ROW | ${"second-column-".repeat(8)} | ${"second-second-".repeat(8)} | ${"second-third-".repeat(8)} |
        """.trimIndent()

        val OversizedTableKatexMarkdown = buildString {
            append("Display math: \\[a^2 + b^2 = c^2\\]\n\n")
            append("| Marker | Long payload A | Long payload B | Long payload C |\n")
            append("| --- | --- | --- | --- |\n")
            repeat(72) { index ->
                val marker = if (index == OversizedTableDeepRowIndex) {
                    OversizedTableDeepRowMarker
                } else {
                    "TABLE_ROW_${index.toString().padStart(3, '0')}"
                }
                append("| $marker | ")
                append("payload-a-$index-".repeat(5))
                append(" | ")
                append("payload-b-$index-".repeat(5))
                append(" | ")
                append("payload-c-$index-".repeat(5))
                append(" |\n")
            }
        }

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

        val LongComposeMarkdown = buildString {
            repeat(145) { index ->
                append("Paragraph $index ")
                append("content ".repeat(23))
                append("\n\n")
            }
            append("| Marker | Long payload A | Long payload B | Long payload C |\n")
            append("| --- | --- | --- | --- |\n")
            append("| $LongComposeTableMarker | ")
            append("late-column-".repeat(40))
            append(" | ")
            append("late-second-".repeat(40))
            append(" | ")
            append("late-third-".repeat(40))
            append(" |\n\n")
            append("The final paragraph remains after the later table.")
        }

        val SeparatedOrderedListsMarkdown = """
            1. first ordered item

            Intervening prose keeps the next list as a distinct ordered list starting at 2.

            2. second ordered item

            More prose keeps the third start independent.

            3. third ordered item

               7. nested first item
               8. nested second item
        """.trimIndent()
    }
}
