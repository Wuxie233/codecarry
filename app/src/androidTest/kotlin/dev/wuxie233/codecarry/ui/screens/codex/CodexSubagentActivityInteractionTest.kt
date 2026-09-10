package dev.wuxie233.codecarry.ui.screens.codex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class CodexSubagentActivityInteractionTest {
    @get:Rule val rule = createComposeRule()
    private fun label(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun activity(kind: String = "interacted", threadId: String? = "child-exact-id") = CodexThreadItem(
        id = "activity-stable-id",
        type = "subAgentActivity",
        raw = buildJsonObject {
            put("agentPath", "/root/dispatch_service")
            put("kind", kind)
            threadId?.let { put("agentThreadId", it) }
        },
    )

    @Test fun activityUsesCollapsedToolDisclosureAndOpensExactChild() {
        val opened = mutableListOf<String>()
        rule.setContent {
            MaterialTheme { CodexTimelineItem(activity(), onOpenThread = { opened.add(it) }) }
        }
        rule.onNodeWithText(label(R.string.codex_timeline_named_subagent_activity, "dispatch_service")).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.codex_timeline_subagent_interacted)).assertIsDisplayed()
        rule.onNodeWithText("/root/dispatch_service").assertDoesNotExist()
        rule.onNodeWithText(label(R.string.codex_timeline_open_subagent)).assertDoesNotExist()
        rule.onNodeWithContentDescription(label(R.string.codex_timeline_expand)).performClick()
        rule.onNodeWithText("/root/dispatch_service").assertIsDisplayed()
        rule.onNodeWithText(label(R.string.codex_timeline_open_subagent)).performClick()
        rule.runOnIdle { assertEquals(listOf("child-exact-id"), opened) }
        rule.onNodeWithContentDescription(label(R.string.codex_timeline_collapse)).performClick()
        rule.onNodeWithText("/root/dispatch_service").assertDoesNotExist()
    }

    @Test fun eventUpdatesKeepExpansionAndDoNotInventRunningState() {
        val item = mutableStateOf(activity("started"))
        rule.setContent { MaterialTheme { CodexTimelineItem(item.value, onOpenThread = {}) } }
        rule.onNodeWithText(label(R.string.codex_timeline_subagent_started)).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.codex_timeline_running)).assertDoesNotExist()
        rule.onNodeWithContentDescription(label(R.string.codex_timeline_expand)).performClick()
        rule.runOnIdle { item.value = activity("completed") }
        rule.onNodeWithText("/root/dispatch_service").assertIsDisplayed()
        rule.onNodeWithText(label(R.string.codex_timeline_completed)).assertIsDisplayed()
        rule.onNodeWithContentDescription(label(R.string.codex_timeline_collapse)).assertIsDisplayed()
    }

    @Test fun activityWithoutChildIdHasNoNavigationAction() {
        rule.setContent { MaterialTheme { CodexTimelineItem(activity(threadId = null), onOpenThread = {}) } }
        rule.onNodeWithContentDescription(label(R.string.codex_timeline_expand)).performClick()
        rule.onNodeWithText("/root/dispatch_service").assertIsDisplayed()
        rule.onNodeWithText(label(R.string.codex_timeline_open_subagent)).assertDoesNotExist()
    }

    @Test fun toolAndActivityCardsVisualReview() {
        rule.setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CodexTimelineItem(CodexThreadItem(id = "tool", type = "commandExecution",
                            command = "/bin/bash -lc git diff --check", status = "completed"), onOpenThread = {})
                        CodexTimelineItem(activity(), onOpenThread = {})
                    }
                }
            }
        }
        capture("codex-subagent-activity-collapsed.png")
        rule.onNodeWithText(label(R.string.codex_timeline_named_subagent_activity, "dispatch_service")).performClick()
        rule.onNodeWithText("/root/dispatch_service").assertIsDisplayed()
        capture("codex-subagent-activity-expanded.png")
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        try {
            val image = rule.onRoot().captureToImage().asAndroidBitmap()
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("visual-review")!!
            directory.mkdirs()
            File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }
}
