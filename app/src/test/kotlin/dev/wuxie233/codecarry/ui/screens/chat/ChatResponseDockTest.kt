package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.Roundtable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatResponseDockTest {

    private val chatScreenSource = File("src/main/kotlin/dev/wuxie233/codecarry/ui/screens/chat/ChatScreen.kt").readText()

    @Test
    fun `dock preserves response kind and server-owned request order`() {
        val items = buildChatResponseDockItems(
            hasRetry = true,
            roundtableStatus = Roundtable.Status.AwaitingCommand,
            hasAwaitingSkip = false,
            permissionIds = listOf("permission-2", "permission-1"),
            questionIds = listOf("question-3", "question-1"),
        )

        assertEquals(
            listOf(
                ChatResponseDockKind.Retry,
                ChatResponseDockKind.Roundtable,
                ChatResponseDockKind.Permission,
                ChatResponseDockKind.Permission,
                ChatResponseDockKind.Question,
                ChatResponseDockKind.Question,
            ),
            items.map { it.kind },
        )
        assertEquals(
            listOf(null, null, "permission-2", "permission-1", "question-3", "question-1"),
            items.map { it.ownershipId },
        )
        assertEquals(items.size, items.map { it.key }.distinct().size)
    }

    @Test
    fun `awaiting command alone offers continue`() {
        assertEquals(
            listOf(RoundtableDockAction.Continue),
            roundtableDockActions(Roundtable.Status.AwaitingCommand, hasAwaitingSkip = false),
        )
    }

    @Test
    fun `awaiting skip never offers continue`() {
        val actions = roundtableDockActions(
            status = Roundtable.Status.AwaitingSkip,
            hasAwaitingSkip = true,
        )

        assertEquals(listOf(RoundtableDockAction.Skip), actions)
        assertFalse(actions.contains(RoundtableDockAction.Continue))
    }

    @Test
    fun `awaiting skip status without an owned skip request exposes no response action`() {
        assertTrue(
            buildChatResponseDockItems(
                hasRetry = false,
                roundtableStatus = Roundtable.Status.AwaitingSkip,
                hasAwaitingSkip = false,
                permissionIds = emptyList(),
                questionIds = emptyList(),
            ).isEmpty(),
        )
        assertTrue(roundtableDockActions(Roundtable.Status.AwaitingSkip, hasAwaitingSkip = false).isEmpty())
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
        assertTrue(dockIntegration.contains("composerContent = {"))
        assertTrue(dockIntegration.contains("viewModel.updateDraftText(normalizedValue.text)"))
        assertTrue(dockIntegration.contains("attachments = if (uiState.supportsAttachments) attachments else emptyList()"))
        assertTrue(dockIntegration.contains("onRemoveAttachment = { index ->"))
    }
}
