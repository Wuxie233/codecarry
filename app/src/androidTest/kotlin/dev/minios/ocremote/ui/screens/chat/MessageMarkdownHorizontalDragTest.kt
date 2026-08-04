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
    fun mixedCodeAndMathMessageRoutesOnlyTheMathBlockToKatex() {
        val plan = planMarkdownDocument(parseMarkdownDocument(KatexMarkdown).getOrThrow())
        val fixture = setKatexWebViewContent()

        assertTrue(plan.blocks.any {
            it.kind == MarkdownRenderBlockKind.CodeFence && it.route == MarkdownRenderRoute.Compose
        })
        assertTrue(plan.blocks.any { it.route == MarkdownRenderRoute.Katex })
        assertTrue("expected KaTeX output in the planned math block", readKatexNodeCount(fixture.webView) > 0)
    }

    @Test
    fun laterTableInMathMessageUsesItsOwnComposeScroll() {
        setKatexWebViewContent(TwoTableKatexMarkdown)
        val plan = planMarkdownDocument(parseMarkdownDocument(TwoTableKatexMarkdown).getOrThrow())
        assertEquals(2, plan.blocks.count { it.kind == MarkdownRenderBlockKind.Table })
        assertTrue(plan.blocks.filter { it.kind == MarkdownRenderBlockKind.Table }
            .all { it.route == MarkdownRenderRoute.Compose })
        assertTrue(plan.blocks.any { it.route == MarkdownRenderRoute.Katex })
        waitForKatexWebView()

        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val tables = rule.onAllNodes(matcher, useUnmergedTree = true)
        tables.assertCountEquals(2)
        val before = tables.fetchSemanticsNodes()[1]
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        tables[1].performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = tables.fetchSemanticsNodes()[1]
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("later Compose table should scroll independently, before=$before after=$after", after > before)
    }

    @Test
    fun firstTableInMathMessageUsesItsOwnComposeScroll() {
        setKatexWebViewContent(TwoTableKatexMarkdown)
        waitForKatexWebView()
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val tables = rule.onAllNodes(matcher, useUnmergedTree = true)
        tables.assertCountEquals(2)
        val before = tables.fetchSemanticsNodes()[0]
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        tables[0].performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = tables.fetchSemanticsNodes()[0]
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("first Compose table should scroll independently, before=$before after=$after", after > before)
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

        waitForKatexWebView()
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val tables = rule.onAllNodes(matcher, useUnmergedTree = true)
        tables.assertCountEquals(2)
        val before = tables.fetchSemanticsNodes()[0]
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        tables[0].performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = tables.fetchSemanticsNodes()[0]
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        var finalDismissValue = SwipeToDismissBoxValue.Settled
        rule.runOnIdle { finalDismissValue = dismissState.currentValue }
        assertTrue(
            "expected the first table inside the user bubble to scroll, before=$before after=$after",
            after > before,
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
            row is ChatMessageRow.TextChunk && LongComposeTableMarker in row.markdown.source
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
                                markdown = chunkRow.markdown.source,
                                textColor = Color.Black,
                                isUser = false,
                                modifier = Modifier
                                    .testTag("$LongComposeRowTag:${row.key}")
                                    .fillMaxWidth(),
                                plannedBlock = chunkRow.markdown,
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
    fun deepRowOfOversizedTableInMathMessageUsesComposeScroll() {
        val plan = planMarkdownDocument(parseMarkdownDocument(OversizedTableKatexMarkdown).getOrThrow())
        val tableBlock = plan.blocks.single { it.kind == MarkdownRenderBlockKind.Table }
        assertEquals(MarkdownRenderRoute.Compose, tableBlock.route)
        assertTrue(plan.blocks.any { it.route == MarkdownRenderRoute.Katex })
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
            row is ChatMessageRow.TextChunk && OversizedTableDeepRowMarker in row.markdown.source
        }
        assertTrue("expected the deep table in a later planned block", targetRowIndex > 0)
        val targetRow = messageRows[targetRowIndex] as ChatMessageRow.TextChunk
        assertEquals(MarkdownRenderBlockKind.Table, targetRow.markdown.kind)

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
                                markdown = chunkRow.markdown.source,
                                textColor = Color.Black,
                                isUser = false,
                                modifier = Modifier
                                    .testTag("$TopLevelRowTag:${row.key}")
                                    .fillMaxWidth(),
                                plannedBlock = chunkRow.markdown,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        scrollToItem(parentScrollState, targetRowIndex)
        rule.onNodeWithText(OversizedTableDeepRowMarker, useUnmergedTree = true)
            .assertExists()
            .performScrollTo()
        rule.waitForIdle()
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val tableNode = rule.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxByOrNull { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() }
            ?: throw AssertionError("expected horizontal scroll semantics for the oversized Compose table")
        val before = tableNode.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val bounds = tableNode.boundsInRoot.toScreenBounds()
        injectTimedSwipe(
            startX = bounds.right - TableGestureInsetPx,
            startY = bounds.centerY().toFloat(),
            endX = bounds.left + TableGestureInsetPx,
            endY = bounds.centerY().toFloat(),
        )
        rule.waitForIdle()
        val after = rule.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxOf { it.config[SemanticsProperties.HorizontalScrollAxisRange].value() }
        assertTrue(
            "expected a physical drag over the deep Compose table row to advance horizontal scroll, " +
                "before=$before after=$after bounds=$bounds",
            after > before,
        )
    }

    @Test
    fun lateCodeBlockInMathMessageUsesComposeScrollAndParentVerticalDrag() {
        val plan = planMarkdownDocument(parseMarkdownDocument(TallChunkedKatexMarkdown).getOrThrow())
        assertTrue(plan.blocks.any { it.route == MarkdownRenderRoute.Katex })
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
            row is ChatMessageRow.TextChunk && TallChunkedCodePrefix in row.markdown.source
        }
        val targetRow = messageRows[targetRowIndex] as ChatMessageRow.TextChunk
        assertTrue("expected bounded top-level rows, count=${messageRows.size}", messageRows.size in 5..10)
        assertTrue("expected target code in a later top-level row, index=$targetRowIndex", targetRowIndex > 0)
        assertEquals(MarkdownRenderBlockKind.CodeFence, targetRow.markdown.kind)
        assertEquals(MarkdownRenderRoute.Compose, targetRow.markdown.route)

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
                                markdown = chunkRow.markdown.source,
                                textColor = Color.Black,
                                isUser = false,
                                modifier = Modifier
                                    .testTag("$TopLevelRowTag:${row.key}")
                                    .fillMaxWidth(),
                                plannedBlock = chunkRow.markdown,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        scrollToItem(parentScrollState, targetRowIndex)
        rule.onNodeWithText(TallChunkedCodePrefix, substring = true, useUnmergedTree = true)
            .assertExists()
            .performScrollTo()
        rule.waitForIdle()
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val codeNode = rule.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxByOrNull { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() }
            ?: throw AssertionError("expected horizontal scroll semantics for the Compose code block")
        val beforeHorizontal = codeNode.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val codeBounds = codeNode.boundsInRoot.toScreenBounds()
        injectTimedSwipe(
            startX = codeBounds.right - GestureEdgeInsetPx,
            startY = codeBounds.centerY().toFloat(),
            endX = codeBounds.left + GestureEdgeInsetPx,
            endY = codeBounds.centerY().toFloat(),
        )
        rule.waitForIdle()
        val afterHorizontal = rule.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxOf { it.config[SemanticsProperties.HorizontalScrollAxisRange].value() }
        assertTrue(
            "expected a physical drag on the later Compose code block to advance horizontal scroll, " +
                "before=$beforeHorizontal after=$afterHorizontal bounds=$codeBounds",
            afterHorizontal > beforeHorizontal,
        )

        waitUntilCanScrollForward(parentScrollState)
        val parentPositionBeforeVertical = readPosition(parentScrollState)
        injectTimedSwipe(
            startX = codeBounds.centerX().toFloat(),
            startY = codeBounds.bottom - GestureEdgeInsetPx,
            endX = codeBounds.centerX().toFloat(),
            endY = codeBounds.top + GestureEdgeInsetPx,
        )
        rule.waitForIdle()
        val parentPositionAfterVertical = readPosition(parentScrollState)
        assertTrue(
            "expected a vertical drag over the later Compose code block to scroll the parent, " +
                "before=$parentPositionBeforeVertical, after=$parentPositionAfterVertical, " +
                "bounds=$codeBounds",
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

    private fun scrollToItem(state: LazyListState, index: Int, scrollOffset: Int = 0) {
        lateinit var scrollJob: Job
        rule.runOnIdle {
            scrollJob = rule.activity.lifecycleScope.launch { state.scrollToItem(index, scrollOffset) }
        }
        rule.waitUntil(LoadTimeoutMillis) { scrollJob.isCompleted }
        rule.waitForIdle()
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

    private data class LazyPosition(val index: Int, val offset: Int) : Comparable<LazyPosition> {
        override fun compareTo(other: LazyPosition): Int {
            return compareValuesBy(this, other, LazyPosition::index, LazyPosition::offset)
        }
    }

    private fun LazyListState.position(): LazyPosition =
        LazyPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)

    private companion object {
        const val DomPollMillis = 100L
        const val GestureEdgeInsetPx = 32f
        const val GestureSettleMillis = 300L
        const val GestureStepCount = 12
        const val GestureStepMillis = 16L
        const val LoadTimeoutMillis = 10_000L
        const val MessageTag = "wide-markdown-message"
        const val KatexMessageTag = "katex-markdown-message"
        const val SwipeDismissMessageTag = "swipe-dismiss-markdown-message"
        const val ComposeTablesMessageTag = "compose-tables-markdown-message"
        const val LaterTableMarker = "LATER_TABLE_ROW"
        const val FirstTableMarker = "FIRST_TABLE_ROW"
        const val PreMetricCount = 10
        const val TimedGestureStepCount = 10
        const val TimedGestureStepMillis = 20L
        const val ReasoningTag = "wide-reasoning-message"
        const val OversizedTableDeepRowIndex = 65
        const val OversizedTableDeepRowMarker = "DEEP_TABLE_ROW_065"
        const val OversizedTableMessageId = "assistant-oversized-table"
        const val OversizedTableParentTag = "oversized-table-parent"
        const val OversizedTableSessionId = "session-oversized-table"
        const val OversizedTableTextPartId = "assistant-oversized-table-text"
        const val TableGestureInsetPx = 4f
        const val ReleaseUrl = "https://github.com/Wuxie233/oc-remote/releases/tag/v1.7.42"
        const val ReleaseUrlMarkdown = "v1.7.42 已发布:\n\n$ReleaseUrl"
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
