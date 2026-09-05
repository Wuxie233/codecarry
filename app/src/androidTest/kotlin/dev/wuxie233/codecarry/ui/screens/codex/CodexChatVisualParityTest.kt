package dev.wuxie233.codecarry.ui.screens.codex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.data.codex.CodexModel
import dev.wuxie233.codecarry.data.codex.CodexReasoningEffortOption
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import dev.wuxie233.codecarry.ui.screens.chat.ChatHeader
import dev.wuxie233.codecarry.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class CodexChatVisualParityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nativeChatShellKeepsLongModelEffortAndExplicitSendVisible() {
        val draft = mutableStateOf("Continue with the implementation")
        var sent = 0
        val model = CodexModel("model", "model", "Codex Development Extended Model",
            supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high")))
        rule.setContent {
            OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                Scaffold(
                    topBar = {
                        ChatHeader("Improve the mobile experience", "/workspace/codecarry", "Codex", "Ready",
                            null, false, false, 0, false, true, {}, {}, {}, {}, {}, {})
                    },
                    bottomBar = {
                        CodexComposerSurface(
                            value = draft.value, onValueChange = { draft.value = it }, placeholder = "Message",
                            canSend = true, isSending = false, sendLabel = "Send", onSend = { sent++ },
                            modifier = Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                            controls = {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    IconButton(onClick = {}) { Icon(Icons.Default.AttachFile, "Attach", Modifier.size(20.dp)) }
                                    CodexModelControls(CodexChatUiState(models = listOf(model), selectedModel = model,
                                        selectedEffort = "high"), {}, {})
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CodexTimelineItem(CodexThreadItem("user", type = "userMessage", text = "Make Codex look consistent with the other chat screens."), {})
                        CodexTimelineItem(CodexThreadItem("reason", type = "reasoning", reasoningSummary = listOf("Check the native components.")), {})
                        CodexTimelineItem(CodexThreadItem("assistant", type = "agentMessage", text = "I’ll use the same compact header and rounded composer.\n\nImages and project actions remain available."), {})
                    }
                }
            }
        }
        rule.onNodeWithText("high").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir("visual-review"), "codex-chat-native-parity.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithContentDescription("Send").performClick()
        rule.runOnIdle { assertEquals(1, sent) }
        rule.onNodeWithText("Continue with the implementation").assertIsDisplayed()
    }
}
