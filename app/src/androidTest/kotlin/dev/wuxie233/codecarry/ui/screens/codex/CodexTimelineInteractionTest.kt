package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexCollabAgentCall
import dev.wuxie233.codecarry.data.codex.CodexCollabAgentState
import dev.wuxie233.codecarry.data.codex.CodexFileChange
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodexTimelineInteractionTest {
    @get:Rule val rule = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun reasoningIsCollapsedUntilRequestedAndCanCollapseAgain() {
        rule.setContent {
            MaterialTheme {
                CodexTimelineItem(CodexThreadItem(id = "reason", type = "reasoning",
                    reasoningSummary = listOf("Inspect the implementation")), onOpenThread = {})
            }
        }
        rule.onNodeWithText("Inspect the implementation").assertDoesNotExist()
        rule.onNodeWithContentDescription(label(R.string.codex_timeline_expand)).performClick()
        rule.onNodeWithText("Inspect the implementation").assertIsDisplayed()
        rule.onNodeWithContentDescription(label(R.string.codex_timeline_collapse)).performClick()
        rule.onNodeWithText("Inspect the implementation").assertDoesNotExist()
    }

    @Test fun subagentNavigationCarriesExactChildThreadId() {
        val opened = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CodexTimelineItem(CodexThreadItem(id = "delegation", type = "collabAgentToolCall",
                    collabAgentCall = CodexCollabAgentCall("spawnAgent", "parent", listOf("child-123"),
                        null, mapOf("child-123" to CodexCollabAgentState("running", null)))),
                    onOpenThread = { opened.add(it) })
            }
        }
        rule.onNodeWithText("child-123").assertDoesNotExist()
        rule.onNodeWithContentDescription(label(R.string.codex_timeline_expand)).performClick()
        rule.onNodeWithText("child-123").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf("child-123"), opened) }
    }

    @Test fun fileDiffOnlyAppearsAfterOpeningItsFile() {
        val diff = "@@ -1 +1 @@\n-old value\n+new value"
        rule.setContent {
            MaterialTheme {
                CodexFileChangeRow(CodexFileChange(path = "src/Main.kt", kind = "update", movePath = null, diff = diff))
            }
        }
        rule.onNodeWithText(diff).assertDoesNotExist()
        rule.onNodeWithText("src/Main.kt").performClick()
        rule.onNodeWithText(diff).assertIsDisplayed()
        rule.onNodeWithText("src/Main.kt").performClick()
        rule.onNodeWithText(diff).assertDoesNotExist()
    }
}
