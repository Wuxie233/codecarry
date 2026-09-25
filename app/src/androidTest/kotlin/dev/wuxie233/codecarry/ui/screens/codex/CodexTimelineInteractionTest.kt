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

    @Test fun messageActionsCopyMarkdownAndQuoteWithoutSubmitting() {
        val markdown = "A **useful** answer"
        var quoted: String? = null
        var retries = 0
        rule.setContent {
            MaterialTheme {
                CodexTimelineItem(CodexThreadItem("answer", type = "agentMessage", text = markdown,
                    phase = "final_answer"), {}, onQuote = { quoted = it }, onRetry = { retries++ })
            }
        }
        rule.onNodeWithContentDescription(label(R.string.message_action_copy_markdown)).performClick()
        rule.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(markdown, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        rule.onNodeWithContentDescription(label(R.string.message_action_quote)).performClick()
        rule.runOnIdle {
            assertEquals(markdown, quoted)
            assertEquals(0, retries)
        }
        rule.onNodeWithContentDescription(label(R.string.codex_message_retry)).performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun editIsAnExplicitMessageAction() {
        var edits = 0
        rule.setContent {
            MaterialTheme {
                CodexTimelineItem(CodexThreadItem("user", type = "userMessage", text = "Original prompt"), {},
                    onEdit = { edits++ })
            }
        }
        rule.onNodeWithText("Original prompt").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, edits) }
        rule.onNodeWithContentDescription(label(R.string.codex_message_edit)).performClick()
        rule.runOnIdle { assertEquals(1, edits) }
    }

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
