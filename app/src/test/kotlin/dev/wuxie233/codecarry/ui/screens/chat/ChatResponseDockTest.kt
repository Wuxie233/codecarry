package dev.wuxie233.codecarry.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatResponseDockTest {

    private val chatScreenSource = File("src/main/kotlin/dev/wuxie233/codecarry/ui/screens/chat/ChatScreen.kt").readText()

    @Test
    fun `dock preserves response kind and server-owned request order`() {
        val items = buildChatResponseDockItems(
            hasRetry = true,
            permissionIds = listOf("permission-2", "permission-1"),
            questionIds = listOf("question-3", "question-1"),
        )

        assertEquals(
            listOf(
                ChatResponseDockKind.Retry,
                ChatResponseDockKind.Permission,
                ChatResponseDockKind.Permission,
                ChatResponseDockKind.Question,
                ChatResponseDockKind.Question,
            ),
            items.map { it.kind },
        )
        assertEquals(
            listOf(null, "permission-2", "permission-1", "question-3", "question-1"),
            items.map { it.ownershipId },
        )
        assertEquals(items.size, items.map { it.key }.distinct().size)
    }

    @Test
    fun `composer remains primary below a bounded response region`() {
        assertTrue(ChatComposerPrimaryMinWidth.value >= 160f)
        assertTrue(ChatResponseDockMaxHeight.value <= 280f)
    }

    @Test
    fun `integration retains owned request ids callbacks drafts and attachments`() {
        val dockIntegration = chatScreenSource
            .substringAfter("ChatResponseDock(")
            .substringBefore(") { padding ->")

        assertTrue(dockIntegration.contains("onOnce = { viewModel.replyToPermission(permission.id, \"once\") }"))
        assertTrue(dockIntegration.contains("onAlways = { viewModel.replyToPermission(permission.id, \"always\") }"))
        assertTrue(dockIntegration.contains("onReject = { viewModel.replyToPermission(permission.id, \"reject\") }"))
        assertTrue(dockIntegration.contains("onSubmit = { answers -> viewModel.replyToQuestion(question.id, answers) }"))
        assertTrue(dockIntegration.contains("onReject = { viewModel.rejectQuestion(question.id) }"))
        assertTrue(dockIntegration.contains("unlockToken = uiState.questionUnlockEpoch"))
        assertTrue(dockIntegration.contains("composerContent = {"))
        assertTrue(dockIntegration.contains("viewModel.updateDraftText(normalizedValue.text)"))
        assertTrue(dockIntegration.contains("attachments = if (uiState.supportsAttachments) attachments else emptyList()"))
        assertTrue(dockIntegration.contains("onRemoveAttachment = { index ->"))
    }
}
