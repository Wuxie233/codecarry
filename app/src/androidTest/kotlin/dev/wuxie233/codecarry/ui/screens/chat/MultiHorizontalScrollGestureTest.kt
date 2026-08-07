package dev.wuxie233.codecarry.ui.screens.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Diagnostic + regression test for: "only the first horizontally-scrollable block in a chat
 * message can be dragged; later ones cannot." Each case hoists two independent ScrollStates so
 * we can read the real scroll offset after a programmatic horizontal swipe on EACH sibling.
 *
 * swipeLeft() drags content leftwards => horizontalScroll offset must increase (> 0).
 */
class MultiHorizontalScrollGestureTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun WideRow(tag: String, state: ScrollState) {
        Box(
            modifier = Modifier
                .testTag(tag)
                .width(200.dp)
                .horizontalScroll(state)
        ) {
            Box(
                modifier = Modifier
                    .width(2000.dp)
                    .height(60.dp)
                    .background(Color.Gray)
            )
        }
    }

    private fun swipe(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { swipeLeft() }
        rule.waitForIdle()
    }

    @Composable
    private fun WideText(tag: String, state: ScrollState) {
        Text(
            text = "0123456789abcdef ".repeat(80),
            modifier = Modifier
                .testTag(tag)
                .width(200.dp)
                .horizontalScroll(state)
                .padding(8.dp)
        )
    }

    @Composable
    private fun WideTableLike(tag: String, state: ScrollState) {
        Box(
            modifier = Modifier
                .testTag(tag)
                .width(200.dp)
                .horizontalScroll(state)
        ) {
            Column {
                repeat(2) { row ->
                    Row {
                        repeat(8) { col ->
                            Text(
                                text = "r$row-c$col-long-cell",
                                modifier = Modifier
                                    .width(176.dp)
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    /** CONTROL: plain Column (no SelectionContainer). Expectation: BOTH scroll. */
    @Test
    fun control_plainColumn_bothSiblingsScroll() {
        lateinit var s1: ScrollState
        lateinit var s2: ScrollState
        rule.setContent {
            s1 = rememberScrollState()
            s2 = rememberScrollState()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                WideRow("row1", s1)
                WideRow("row2", s2)
            }
        }
        swipe("row1")
        swipe("row2")
        assertTrue("row1 should scroll, was ${s1.value}", s1.value > 0)
        assertTrue("row2 should scroll, was ${s2.value}", s2.value > 0)
    }

    /** REPRO: both rows inside ONE SelectionContainer (mirrors the app). */
    @Test
    fun repro_singleSelectionContainer_bothSiblingsScroll() {
        lateinit var s1: ScrollState
        lateinit var s2: ScrollState
        rule.setContent {
            s1 = rememberScrollState()
            s2 = rememberScrollState()
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WideRow("row1", s1)
                    WideRow("row2", s2)
                }
            }
        }
        swipe("row1")
        swipe("row2")
        assertTrue("row1 should scroll, was ${s1.value}", s1.value > 0)
        assertTrue("row2 should scroll inside SelectionContainer, was ${s2.value}", s2.value > 0)
    }

    /** REPRO variant: rows wrapped in DisableSelection (mirrors table path). */
    @Test
    fun repro_selectionContainer_disableSelection_bothSiblingsScroll() {
        lateinit var s1: ScrollState
        lateinit var s2: ScrollState
        rule.setContent {
            s1 = rememberScrollState()
            s2 = rememberScrollState()
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DisableSelection { WideRow("row1", s1) }
                    DisableSelection { WideRow("row2", s2) }
                }
            }
        }
        swipe("row1")
        swipe("row2")
        assertTrue("row1 should scroll, was ${s1.value}", s1.value > 0)
        assertTrue("row2 (DisableSelection) should scroll, was ${s2.value}", s2.value > 0)
    }

    @Test
    fun repro_selectionContainer_textChildren_bothSiblingsScroll() {
        lateinit var s1: ScrollState
        lateinit var s2: ScrollState
        rule.setContent {
            s1 = rememberScrollState()
            s2 = rememberScrollState()
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WideText("text1", s1)
                    WideText("text2", s2)
                }
            }
        }
        swipe("text1")
        swipe("text2")
        assertTrue("text1 should scroll, was ${s1.value}", s1.value > 0)
        assertTrue("text2 should scroll inside SelectionContainer, was ${s2.value}", s2.value > 0)
    }

    @Test
    fun repro_selectionContainer_tableLikeDisableSelection_bothSiblingsScroll() {
        lateinit var s1: ScrollState
        lateinit var s2: ScrollState
        rule.setContent {
            s1 = rememberScrollState()
            s2 = rememberScrollState()
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DisableSelection { WideTableLike("table1", s1) }
                    DisableSelection { WideTableLike("table2", s2) }
                }
            }
        }
        swipe("table1")
        swipe("table2")
        assertTrue("table1 should scroll, was ${s1.value}", s1.value > 0)
        assertTrue("table2 should scroll, was ${s2.value}", s2.value > 0)
    }

    /** FIX CANDIDATE B: per-child SelectionContainer instead of one wrapping the column. */
    @Test
    fun fixB_perChildSelectionContainer_bothSiblingsScroll() {
        lateinit var s1: ScrollState
        lateinit var s2: ScrollState
        rule.setContent {
            s1 = rememberScrollState()
            s2 = rememberScrollState()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer { WideRow("row1", s1) }
                SelectionContainer { WideRow("row2", s2) }
            }
        }
        swipe("row1")
        swipe("row2")
        assertTrue("row1 should scroll, was ${s1.value}", s1.value > 0)
        assertTrue("row2 (per-child SelectionContainer) should scroll, was ${s2.value}", s2.value > 0)
    }

}
