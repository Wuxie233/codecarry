package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.data.codex.CodexThread
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodexProjectInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rightSwipeRenamesAndLeftSwipeArchivesWithoutDismissingRow() {
        val actions = mutableListOf<String>()
        showThread(archived = false, actions)
        val initialLeft = titleLeft()
        swipeThread(right = true)
        rule.runOnIdle { assertEquals(listOf("rename"), actions) }
        assertRowRestored(initialLeft)
        swipeThread(right = false)
        rule.runOnIdle { assertEquals(listOf("rename", "archive"), actions) }
        assertRowRestored(initialLeft)
    }

    @Test fun archivedThreadLeftSwipeRestoresInsteadOfArchiving() {
        val actions = mutableListOf<String>()
        showThread(archived = true, actions)
        val initialLeft = titleLeft()
        swipeThread(right = false)
        rule.runOnIdle { assertEquals(listOf("restore"), actions) }
        assertRowRestored(initialLeft)
    }

    @Test fun shortSlowSwipeDoesNotTriggerAnAction() {
        val actions = mutableListOf<String>()
        showThread(archived = false, actions)
        val initialLeft = titleLeft()
        rule.onNodeWithTag("codex_thread_swipe:thread-test").performTouchInput {
            swipe(Offset(width * 0.2f, centerY), Offset(width * 0.4f, centerY), durationMillis = 1_000)
        }
        rule.runOnIdle { assertEquals(emptyList<String>(), actions) }
        assertRowRestored(initialLeft)
    }

    private fun showThread(archived: Boolean, actions: MutableList<String>) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(340.dp)) {
                    CodexThreadRow(
                        thread = CodexThread(id = "thread-test", name = "Project conversation"),
                        archived = archived, pendingCount = 0,
                        onOpen = { actions.add("open") }, onRename = { actions.add("rename") },
                        onFork = { actions.add("fork") }, onArchive = { actions.add("archive") },
                        onRestore = { actions.add("restore") }, onDelete = { actions.add("delete") },
                    )
                }
            }
        }
    }

    private fun swipeThread(right: Boolean) {
        rule.onNodeWithTag("codex_thread_swipe:thread-test").performTouchInput {
            val start = if (right) 0.15f else 0.85f
            val end = if (right) 0.85f else 0.15f
            swipe(Offset(width * start, centerY), Offset(width * end, centerY), durationMillis = 600)
        }
        rule.waitForIdle()
    }

    private fun titleLeft() = rule.onNodeWithText("Project conversation", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.left

    private fun assertRowRestored(initialLeft: Float) {
        rule.onNodeWithText("Project conversation", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("Swipe action must spring back to the original position", initialLeft, titleLeft(), 1f)
    }
}
