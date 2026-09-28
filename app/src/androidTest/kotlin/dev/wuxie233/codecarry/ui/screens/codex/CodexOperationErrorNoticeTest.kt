package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.ui.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test

class CodexOperationErrorNoticeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun longFailureStaysVisibleWhileBrowsingHistoryAndCanBeInspectedAndDismissed() {
        val message = "Unable to send. " + "Remote diagnostic details. ".repeat(100)
        val error = mutableStateOf<String?>(message)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    Scaffold(bottomBar = {
                        CodexComposerSurface(
                            value = TextFieldValue("Keep my draft"), onValueChange = {}, placeholder = "Message",
                            canSend = true, isSending = false, sendLabel = "Send", onSend = {},
                        )
                    }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            error.value?.let {
                                CodexOperationErrorNotice(it, onDismiss = { error.value = null },
                                    modifier = Modifier.testTag("operation-error"))
                            }
                            LazyColumn(Modifier.weight(1f).testTag("history")) {
                                items(80) { Text("Message $it", Modifier.padding(12.dp)) }
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("history").performScrollToIndex(79)
        rule.onNodeWithText("Message 79").assertIsDisplayed()
        rule.onNodeWithTag("operation-error").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.chat_failure_details)).performClick()
        rule.onAllNodesWithText(message).onLast().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.close)).performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        rule.onNodeWithTag("operation-error").assertDoesNotExist()
        rule.onNodeWithText("Keep my draft").assertIsDisplayed()
        rule.onNodeWithText("Message 79").assertIsDisplayed()
    }
}
