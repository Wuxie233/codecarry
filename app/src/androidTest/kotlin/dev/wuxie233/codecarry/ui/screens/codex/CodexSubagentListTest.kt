package dev.wuxie233.codecarry.ui.screens.codex

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexThreadStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodexSubagentListTest {
    @get:Rule val rule = createComposeRule()

    @Test fun runningChildrenOpenByDefaultAndHistoryExpandsIndependently() {
        val opened = mutableListOf<String>()
        show(opened)
        scrollTo("codex_thread_swipe:worker").assertIsDisplayed()
        rule.onNodeWithText("Worker", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(listOf("worker"), opened) }
        rule.onNodeWithTag("codex_thread_swipe:history").assertDoesNotExist()
        scrollTo("codex_subagents:parent:true").performClick()
        scrollTo("codex_thread_swipe:history").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        try {
            val image = rule.onRoot().captureToImage().asAndroidBitmap()
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("visual-review")!!
            directory.mkdirs()
            File(directory, "codex-subagent-list-expanded.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.onNodeWithText("Reviewer", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(listOf("worker", "history"), opened) }
        scrollTo("codex_subagents:parent:false").performClick()
        rule.onNodeWithTag("codex_thread_swipe:worker").assertDoesNotExist()
        scrollTo("codex_thread_swipe:history").assertIsDisplayed()
    }

    @Test fun searchingHistoricalChildRevealsItAndRetainsItsParent() {
        show(mutableListOf(), query = "Reviewer")
        scrollTo("codex_thread_swipe:parent").assertIsDisplayed()
        scrollTo("codex_thread_swipe:history").assertIsDisplayed()
    }

    @Test fun nestedRunningAndOrphanAgentsRemainNavigable() {
        show(mutableListOf(), extra = listOf(
            CodexThread("nested", name = "Nested worker", parentThreadId = "worker", status = CodexThreadStatus("active")),
            CodexThread("orphan", name = "Orphan worker", parentThreadId = "missing", status = CodexThreadStatus("active")),
        ))
        scrollTo("codex_thread_swipe:nested").assertIsDisplayed()
        scrollTo("codex_thread_swipe:orphan").assertIsDisplayed()
    }

    private fun scrollTo(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        rule.onNodeWithTag("session_projects_queue").performScrollToNode(hasTestTag(tag))
        return rule.onNodeWithTag(tag)
    }

    private fun show(opened: MutableList<String>, query: String = "", extra: List<CodexThread> = emptyList()) {
        val threads = listOf(
            CodexThread("parent", name = "Main task", status = CodexThreadStatus("active")),
            CodexThread("worker", agentNickname = "Worker", parentThreadId = "parent", status = CodexThreadStatus("active")),
            CodexThread("history", agentNickname = "Reviewer", parentThreadId = "parent"),
        ) + extra
        rule.setContent {
            MaterialTheme {
                CodexThreadListContent(
                    state = CodexThreadListUiState(activeThreads = threads, isLoading = false, searchQuery = query),
                    onNavigateBack = {}, onOpenThread = { opened.add(it) }, actions = CodexThreadListActions(),
                )
            }
        }
    }
}
