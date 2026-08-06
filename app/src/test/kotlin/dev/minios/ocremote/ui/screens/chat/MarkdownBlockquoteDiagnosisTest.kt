package dev.minios.ocremote.ui.screens.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkdownBlockquoteDiagnosisTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun blockquoteRendersParagraphAfterBlankQuotedLine() {
        compose.setContent {
            MaterialTheme {
                MessageMarkdownContent(
                    markdown = "> First paragraph.\n>\n> Second paragraph.",
                    textColor = Color.Black,
                    isUser = false,
                )
            }
        }

        compose.onNodeWithText("First paragraph.").assertExists()
        compose.onNodeWithText("Second paragraph.").assertExists()
    }

    @Test
    fun blockquoteRendersNestedStructuredChildren() {
        compose.setContent {
            MaterialTheme {
                MessageMarkdownContent(
                    markdown = """
                        > Intro
                        >
                        > 3. Quoted item
                        >
                        > > Nested quote
                        >
                        > ```text
                        > quoted code
                        > ```
                        >
                        > | Key | Value |
                        > | --- | --- |
                        > | A | B |
                    """.trimIndent(),
                    textColor = Color.Black,
                    isUser = false,
                )
            }
        }

        compose.onNodeWithText("Intro").assertExists()
        compose.onNodeWithText("3. ").assertExists()
        compose.onNodeWithText("Quoted item").assertExists()
        compose.onNodeWithText("Nested quote").assertExists()
        compose.onNodeWithText("quoted code", substring = true).assertExists()
        compose.onAllNodesWithText("Key", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("Value", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("A", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("B", useUnmergedTree = true).assertCountEquals(2)
    }
}
