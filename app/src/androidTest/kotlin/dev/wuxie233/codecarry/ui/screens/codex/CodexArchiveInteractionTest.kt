package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodexArchiveInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun consecutiveArchivesKeepSeparateUndoActions() {
        val queue = mutableStateOf(listOf(success(1, 1), success(2, 3)))
        val undone = mutableListOf<Long>()
        val presented = mutableListOf<Long>()
        rule.setContent { MaterialTheme {
            CodexArchiveFeedbackHost(queue.value.firstOrNull(), remember { SnackbarHostState() },
                onUndo = { id -> undone += id; queue.value = queue.value.filterNot { it.id == id } },
                onDismiss = { id -> queue.value = queue.value.filterNot { it.id == id } },
                onPresent = { id, _ -> presented += id },
            )
        } }
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.codex_archive_done, 1, 1)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.codex_archive_undo)).performClick()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.codex_archive_done, 3, 3)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.codex_archive_undo)).performClick()
        rule.runOnIdle {
            assertEquals(listOf(1L, 2L), undone)
            assertEquals(listOf(1L, 2L), presented)
        }
    }

    @Test fun leavingCompositionDoesNotAcknowledgeFeedback() {
        val visible = mutableStateOf(true)
        var dismissed = 0
        rule.setContent { MaterialTheme {
            if (visible.value) CodexArchiveFeedbackHost(success(1, 1), remember { SnackbarHostState() },
                onUndo = {}, onDismiss = { dismissed++ }, onPresent = { _, _ -> })
        } }
        rule.onNodeWithText(context.getString(R.string.codex_archive_undo)).assertIsDisplayed()
        rule.runOnIdle { visible.value = false }
        rule.runOnIdle { assertEquals(0, dismissed); visible.value = true }
        rule.onNodeWithText(context.getString(R.string.codex_archive_undo)).assertIsDisplayed()
    }

    @Test fun unknownResultDoesNotOfferUndoOrRetry() {
        rule.setContent { MaterialTheme {
            CodexArchiveFeedbackHost(
                CodexArchiveFeedback(1, CodexArchiveOperation.ARCHIVE, unknownCount = 2),
                remember { SnackbarHostState() }, onUndo = {}, onDismiss = {}, onPresent = { _, _ -> },
            )
        } }
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.codex_archive_unknown, 2, 2)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.codex_archive_undo)).assertDoesNotExist()
    }

    @Test fun partialArchiveShowsSuccessFailureAndUnknownCountsTogether() {
        rule.setContent { MaterialTheme {
            CodexArchiveFeedbackHost(
                CodexArchiveFeedback(1, CodexArchiveOperation.ARCHIVE, archivedCount = 2, failedCount = 1, unknownCount = 3, canUndo = true),
                remember { SnackbarHostState() }, onUndo = {}, onDismiss = {}, onPresent = { _, _ -> },
            )
        } }
        val expected = listOf(
            context.resources.getQuantityString(R.plurals.codex_archive_done, 2, 2),
            context.resources.getQuantityString(R.plurals.codex_archive_failed, 1, 1),
            context.resources.getQuantityString(R.plurals.codex_archive_unknown, 3, 3),
        ).joinToString("\n")
        rule.onNodeWithText(expected).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.codex_archive_undo)).assertIsDisplayed()
    }

    private fun success(id: Long, count: Int) = CodexArchiveFeedback(
        id, CodexArchiveOperation.ARCHIVE, archivedCount = count, canUndo = true,
    )
}
