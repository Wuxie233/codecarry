package dev.wuxie233.codecarry.ui.screens.codex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.data.codex.CodexModel
import dev.wuxie233.codecarry.data.codex.CodexReasoningEffortOption
import dev.wuxie233.codecarry.data.codex.CodexTurn
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import dev.wuxie233.codecarry.ui.screens.chat.ChatResponseDockItem
import dev.wuxie233.codecarry.ui.screens.chat.ChatResponseDockKind
import dev.wuxie233.codecarry.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class CodexChatVisualParityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lightChatKeepsLongModelAndExplicitSendVisible() = verifyChatShell(false)
    @Test fun darkChatKeepsLongModelAndExplicitSendVisible() = verifyChatShell(true)

    @Test fun runningComposerKeepsStopAvailableAtNarrowWidth() {
        var stopped = 0
        val draft = mutableStateOf(TextFieldValue())
        rule.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.width(320.dp)) {
                    CodexComposerSurface(
                        value = draft.value, onValueChange = { draft.value = it }, placeholder = "Follow up",
                        canSend = draft.value.text.isNotBlank(), isSending = false,
                        sendLabel = "Send", onSend = {}, canStop = true, stopLabel = "Stop",
                        onStop = { stopped++ },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Stop").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("Send").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performTextInput("A follow-up")
        rule.onNodeWithContentDescription("Stop").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, stopped) }
    }

    @Test fun constrainedQuestionDockScrollsWithoutHidingSend() {
        var sent = 0
        val draft = TextFieldValue("Continue")
        val pending = (1..5).map { ChatResponseDockItem(ChatResponseDockKind.Question, "$it") }
        rule.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                // A 260dp remaining viewport reproduces a narrow window above a keyboard.
                Box(Modifier.width(320.dp).height(260.dp)) {
                    CodexResponseDock(
                        items = pending,
                        responseContent = { item ->
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                Text("Question ${item.ownershipId}")
                                repeat(3) { Text("Choice ${it + 1}", Modifier.padding(vertical = 8.dp)) }
                            }
                        },
                        composerContent = {
                            CodexComposerSurface(value = draft, onValueChange = {}, placeholder = "Message",
                                canSend = true, isSending = false, sendLabel = "Send", onSend = { sent++ })
                        },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
        rule.onNodeWithText("Question 5").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, sent) }
    }

    @Test fun restoredAttachmentsNeedReviewBeforeExplicitSendAtNarrowWidth() {
        val needsReview = mutableStateOf(true)
        var sent = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.width(320.dp)) {
                    if (needsReview.value) CodexAttachmentRecoveryNotice(enabled = true,
                        onReviewed = { needsReview.value = false })
                    CodexComposerSurface(value = TextFieldValue("Recovered draft"), onValueChange = {},
                        placeholder = "Message", canSend = !needsReview.value, isSending = false,
                        sendLabel = "Send", onSend = { sent++ })
                }
            }
        }
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        rule.onNodeWithText(context.getString(dev.wuxie233.codecarry.R.string.codex_rewrite_attachments_reviewed))
            .assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(0, sent) }
        rule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, sent) }
    }

    private fun verifyChatShell(dark: Boolean) {
        val draft = mutableStateOf(TextFieldValue("Continue with the implementation"))
        var sent = 0
        val model = CodexModel("model", "model", "Codex Development Extended Model",
            supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high")))
        rule.setContent {
            OpenCodeTheme(darkTheme = dark, dynamicColor = false) {
                Scaffold(
                    topBar = {
                        CodexChatHeader("Improve the mobile experience", "/workspace/codecarry", "Ready",
                            onNavigateBack = {}, onOpenStatus = {}, onOpenOverflow = {})
                    },
                    bottomBar = {
                        CodexComposerSurface(
                            value = draft.value, onValueChange = { draft.value = it }, placeholder = "Message",
                            canSend = true, isSending = false, sendLabel = "Send", onSend = { sent++ },
                            modifier = Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                            controls = {
                                CodexComposerControlRow(
                                    state = CodexChatUiState(modelsState = CodexMetadataLoadState.Loaded(listOf(model)),
                                        selectedModel = model, selectedEffort = "high"),
                                    attachmentsEnabled = true, onAddAttachment = {}, onLoadSkills = {},
                                    onSearchFiles = {}, onModel = {}, onEffort = {},
                                )
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CodexTimelineItem(CodexThreadItem("user", type = "userMessage", text = "Keep the chat focused on the conversation."), {}, onEdit = {}, onQuote = {})
                        CodexTurnActivityHeader(CodexTurn(id = "turn", status = "completed"),
                            hasActivity = true, expanded = false, onToggle = {})
                        CodexTimelineItem(CodexThreadItem("assistant", type = "agentMessage", text = "The conversation now has more room.\n\nEdit a previous message or quote a reply to continue."), {}, onRetry = {}, onQuote = {})
                    }
                }
            }
        }
        rule.onNodeWithText("High").assertExists()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir("visual-review"), "codex-chat-redesign-${if (dark) "dark" else "light"}.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithContentDescription("Send").performClick()
        rule.runOnIdle { assertEquals(1, sent) }
        rule.onNodeWithText("Continue with the implementation").assertIsDisplayed()
    }
}
