package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexTimelineRemeasureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedAnswerMeasurementKeepsTailVisibleWithoutAnotherModelEvent() {
        val height = mutableIntStateOf(800)
        val state = LazyListState()
        compose.setContent {
            MaterialTheme {
                CodexTimelineViewport("unchanged-final-answer", Modifier.size(340.dp, 480.dp).testTag("timeline"), state) {
                    item("answer") {
                        Box(Modifier.fillMaxWidth().height(height.intValue.dp)) {
                            Text("Final answer end", Modifier.align(Alignment.BottomStart))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Final answer end").assertIsDisplayed()
        compose.runOnIdle { height.intValue = 1_800 }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("Late layout must still follow the final answer", state.canScrollForward) }
        compose.onNodeWithText("Final answer end").assertIsDisplayed()

        compose.onNodeWithTag("timeline").performTouchInput { swipeDown(durationMillis = 600) }
        compose.waitForIdle()
        val offset = compose.runOnIdle {
            assertTrue(state.canScrollForward)
            state.firstVisibleItemScrollOffset
        }
        compose.runOnIdle { height.intValue = 2_200 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Late measurement must not override a physical history drag", offset, state.firstVisibleItemScrollOffset) }
    }

    @Test fun expandingActivityKeepsItsReadingAnchorAcrossDelayedContentMeasurement() {
        val navigation = mutableIntStateOf(0)
        val answerHeight = mutableIntStateOf(80)
        val activityHeight = mutableIntStateOf(1_200)
        val state = LazyListState()
        compose.setContent {
            MaterialTheme {
                CodexTimelineViewport(
                    contentKey = navigation.intValue,
                    modifier = Modifier.size(340.dp, 480.dp),
                    listState = state,
                    manualNavigationKey = navigation.intValue,
                ) {
                    item("activity-header") {
                        Text("Expand activity", Modifier.fillMaxWidth().height(48.dp).clickable { navigation.intValue++ })
                    }
                    if (navigation.intValue > 0) item("activity") {
                        Text("Process details", Modifier.fillMaxWidth().height(activityHeight.intValue.dp))
                    }
                    item("answer") { Text("Final answer", Modifier.fillMaxWidth().height(answerHeight.intValue.dp)) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Expand activity").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Expand activity").assertIsDisplayed()
        compose.onNodeWithText("Process details").assertIsDisplayed()
        val anchor = compose.runOnIdle {
            assertTrue("Expanding process must not immediately jump to the final answer", state.canScrollForward)
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }
        compose.runOnIdle {
            activityHeight.intValue = 1_800
            answerHeight.intValue = 800
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(anchor, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset) }
        compose.onNodeWithText("Process details").assertIsDisplayed()
    }
}
