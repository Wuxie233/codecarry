package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Physical input exercises the viewport's drag ownership, not just its pure policy. */
class CodexTimelineViewportGestureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun browsingHistoryKeepsPositionWhenMessagesArriveAndReturnResumesFollowing() {
        val count = mutableIntStateOf(40)
        val state = LazyListState()
        rule.setContent {
            MaterialTheme {
                CodexTimelineViewport(count.intValue, Modifier.size(340.dp, 480.dp).testTag("timeline"), state) {
                    items((0 until count.intValue).toList(), key = { it }) { index ->
                        Text("Message $index", Modifier.fillMaxWidth().height(90.dp))
                    }
                }
            }
        }
        rule.runOnIdle { assertFalse("Initial history should open at its end", state.canScrollForward) }
        rule.onNodeWithTag("timeline").performTouchInput { swipeDown(durationMillis = 600) }
        rule.waitForIdle()
        val before = rule.runOnIdle {
            assertTrue("A real downward drag must leave the tail", state.canScrollForward)
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }
        rule.runOnIdle { count.intValue += 3 }
        rule.runOnIdle {
            assertEquals(before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        }
        returnToNewContent()
        rule.runOnIdle { assertFalse("Return must reach the actual bottom", state.canScrollForward) }
        rule.runOnIdle { count.intValue += 1 }
        rule.runOnIdle { assertFalse("Returning to the tail must resume stream following", state.canScrollForward) }
    }

    @Test fun oversizedSingleMessageReachesItsBottomAndDoesNotJumpDuringHistoryReading() {
        val messageHeight = mutableIntStateOf(1500)
        val state = LazyListState()
        rule.setContent {
            MaterialTheme {
                CodexTimelineViewport(messageHeight.intValue, Modifier.size(340.dp, 480.dp).testTag("timeline"), state) {
                    item(key = "long-message") {
                        Box(Modifier.fillMaxWidth().height(messageHeight.intValue.dp)) {
                            Text("Start of message", Modifier.align(Alignment.TopStart))
                            Text("End of message", Modifier.align(Alignment.BottomStart))
                        }
                    }
                }
            }
        }
        rule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertTrue("Single item must exceed the viewport", state.firstVisibleItemScrollOffset > 0)
            assertFalse("Initial scroll must align the long item's bottom", state.canScrollForward)
        }
        rule.onNodeWithText("End of message").assertIsDisplayed()
        rule.runOnIdle { messageHeight.intValue += 200 }
        rule.runOnIdle { assertFalse("Stream growth at the tail must remain followed", state.canScrollForward) }
        rule.onNodeWithTag("timeline").performTouchInput { swipeDown(durationMillis = 600) }
        rule.waitForIdle()
        val offset = rule.runOnIdle {
            assertTrue(state.canScrollForward)
            state.firstVisibleItemScrollOffset
        }
        rule.runOnIdle { messageHeight.intValue += 250 }
        rule.runOnIdle { assertEquals(offset, state.firstVisibleItemScrollOffset) }
        returnToNewContent()
        rule.runOnIdle { assertFalse("Return must not stop at the long item's first line", state.canScrollForward) }
        rule.onNodeWithText("End of message").assertIsDisplayed()
    }

    private fun newContentButton() = rule.onNodeWithText(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_new_content),
        useUnmergedTree = true,
    )

    private fun returnToNewContent() {
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_new_content)
        try {
            // Content reconciliation intentionally waits for two layout frames.
            // runOnIdle alone may observe the gap before that effect resumes.
            rule.waitUntil(timeoutMillis = 5_000) {
                rule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            newContentButton().assertIsDisplayed().performTouchInput { click() }
        } catch (failure: Throwable) {
            throw AssertionError("New content control is not reachable.\n" + rule.onRoot(useUnmergedTree = true).printToString(), failure)
        }
    }
}
