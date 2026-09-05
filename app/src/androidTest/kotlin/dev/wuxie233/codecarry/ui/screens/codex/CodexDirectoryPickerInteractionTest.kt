package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexDirectoryEntry
import dev.wuxie233.codecarry.data.codex.CodexDirectoryListing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodexDirectoryPickerInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun browseChildReturnToParentAndSelectCurrentDirectory() {
        val requests = mutableListOf<String>()
        val selected = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CodexDirectoryPicker(
                    recentDirectories = emptyList(), defaultDirectory = { "/workspace" },
                    readDirectory = { path ->
                        requests.add(path)
                        when (path) {
                            "/workspace" -> CodexDirectoryListing(path, "/", listOf(CodexDirectoryEntry("mobile", "/workspace/mobile")))
                            "/workspace/mobile" -> CodexDirectoryListing(path, "/workspace", listOf(CodexDirectoryEntry("src", "/workspace/mobile/src")))
                            else -> error("Unexpected path $path")
                        }
                    },
                    onDismiss = {}, onSelect = { selected.add(it) },
                )
            }
        }
        rule.onNodeWithText("mobile").assertIsDisplayed().performClick()
        rule.onNodeWithText("src").assertIsDisplayed()
        rule.onNodeWithContentDescription(label(R.string.codex_directory_parent)).performClick()
        rule.onNodeWithText("mobile").assertIsDisplayed()
        rule.onNodeWithText(label(R.string.codex_directory_select)).assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(listOf("/workspace", "/workspace/mobile", "/workspace"), requests)
            assertEquals(listOf("/workspace"), selected)
        }
    }

    @Test fun failedChildBrowseCannotSelectThePreviousDirectory() {
        val selected = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CodexDirectoryPicker(
                    recentDirectories = emptyList(), defaultDirectory = { "/workspace" },
                    readDirectory = { path ->
                        if (path != "/workspace") error("Permission denied")
                        CodexDirectoryListing(path, "/", listOf(CodexDirectoryEntry("restricted", "/workspace/restricted")))
                    },
                    onDismiss = {}, onSelect = { selected.add(it) },
                )
            }
        }
        rule.onNodeWithText("restricted").performClick()
        rule.onNodeWithText(label(R.string.codex_directory_failed)).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.codex_directory_select)).assertIsNotEnabled()
        rule.runOnIdle { assertEquals(emptyList<String>(), selected) }
    }

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
