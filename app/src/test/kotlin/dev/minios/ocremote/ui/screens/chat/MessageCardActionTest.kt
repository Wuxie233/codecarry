package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCardActionTest {

    @Test
    fun `stable user message shows every action enabled when session is ready and idle`() {
        val actions = buildMessageCardActions(
            chatMessage = userMessage(id = "msg_user", text = "hello"),
            selectedMessageStreaming = false,
            sessionBusy = false,
            sessionReady = true,
        )

        assertEquals(
            listOf(
                MessageCardAction.ForkFromHere,
                MessageCardAction.CopyText,
                MessageCardAction.CopyMarkdown,
                MessageCardAction.QuoteIntoInput,
                MessageCardAction.RestoreToHere,
            ),
            actions.map { it.action },
        )
        assertTrue(actions.all { it.enabled })
        assertTrue(actions.all { it.disabledReason == null })
    }

    @Test
    fun `assistant message hides restore but keeps fork and text actions enabled`() {
        val actions = buildMessageCardActions(
            chatMessage = assistantMessage(id = "msg_assistant", text = "answer"),
            selectedMessageStreaming = false,
            sessionBusy = false,
            sessionReady = true,
        )

        assertEquals(
            listOf(
                MessageCardAction.ForkFromHere,
                MessageCardAction.CopyText,
                MessageCardAction.CopyMarkdown,
                MessageCardAction.QuoteIntoInput,
            ),
            actions.map { it.action },
        )
        assertTrue(actions.all { it.enabled })
        assertFalse(actions.any { it.action == MessageCardAction.RestoreToHere })
    }

    @Test
    fun `streaming user message disables session mutations but keeps copy actions enabled`() {
        val actions = buildMessageCardActions(
            chatMessage = userMessage(id = "msg_user", text = "copy me"),
            selectedMessageStreaming = true,
            sessionBusy = false,
            sessionReady = true,
        ).associateBy { it.action }

        assertDisabledForStreaming(actions.getValue(MessageCardAction.ForkFromHere))
        assertDisabledForStreaming(actions.getValue(MessageCardAction.RestoreToHere))
        assertEnabled(actions.getValue(MessageCardAction.CopyText))
        assertEnabled(actions.getValue(MessageCardAction.CopyMarkdown))
        assertEnabled(actions.getValue(MessageCardAction.QuoteIntoInput))
    }

    @Test
    fun `busy user message disables session mutations but keeps copy actions enabled`() {
        val actions = buildMessageCardActions(
            chatMessage = userMessage(id = "msg_user", text = "copy me"),
            selectedMessageStreaming = false,
            sessionBusy = true,
            sessionReady = true,
        ).associateBy { it.action }

        assertDisabledForStreaming(actions.getValue(MessageCardAction.ForkFromHere))
        assertDisabledForStreaming(actions.getValue(MessageCardAction.RestoreToHere))
        assertEnabled(actions.getValue(MessageCardAction.CopyText))
        assertEnabled(actions.getValue(MessageCardAction.CopyMarkdown))
        assertEnabled(actions.getValue(MessageCardAction.QuoteIntoInput))
    }

    @Test
    fun `missing message id hides session mutating actions but keeps copy actions`() {
        val actions = buildMessageCardActions(
            chatMessage = userMessage(id = "", text = "copyable"),
            selectedMessageStreaming = false,
            sessionBusy = false,
            sessionReady = true,
        )

        assertEquals(
            listOf(
                MessageCardAction.CopyText,
                MessageCardAction.CopyMarkdown,
                MessageCardAction.QuoteIntoInput,
            ),
            actions.map { it.action },
        )
        assertTrue(actions.all { it.enabled })
    }

    @Test
    fun `session not ready disables session mutating actions without disabling copy actions`() {
        val actions = buildMessageCardActions(
            chatMessage = userMessage(id = "msg_user", text = "copyable"),
            selectedMessageStreaming = false,
            sessionBusy = false,
            sessionReady = false,
        ).associateBy { it.action }

        assertFalse(actions.getValue(MessageCardAction.ForkFromHere).enabled)
        assertNull(actions.getValue(MessageCardAction.ForkFromHere).disabledReason)
        assertFalse(actions.getValue(MessageCardAction.RestoreToHere).enabled)
        assertNull(actions.getValue(MessageCardAction.RestoreToHere).disabledReason)
        assertEnabled(actions.getValue(MessageCardAction.CopyText))
        assertEnabled(actions.getValue(MessageCardAction.CopyMarkdown))
        assertEnabled(actions.getValue(MessageCardAction.QuoteIntoInput))
    }

    @Test
    fun `messagePlainText joins visible text parts and ignores non text hidden or ignored parts`() {
        val message = userMessage(
            id = "msg_user",
            parts = listOf(
                textPart("msg_user", "  first visible  "),
                Part.Reasoning(id = "reason", sessionId = SESSION_ID, messageId = "msg_user", text = "reasoning-secret"),
                Part.Tool(id = "tool", sessionId = SESSION_ID, messageId = "msg_user", tool = "bash-secret"),
                Part.File(id = "file", sessionId = SESSION_ID, messageId = "msg_user", url = "file-secret"),
                textPart("msg_user", "synthetic-secret", synthetic = true),
                textPart("msg_user", "ignored-secret", ignored = true),
                textPart("msg_user", "second visible"),
            ),
        )

        val plainText = messagePlainText(message)

        assertEquals("first visible\n\nsecond visible", plainText)
        assertFalse(plainText.contains("reasoning-secret"))
        assertFalse(plainText.contains("bash-secret"))
        assertFalse(plainText.contains("file-secret"))
        assertFalse(plainText.contains("synthetic-secret"))
        assertFalse(plainText.contains("ignored-secret"))
    }

    @Test
    fun `messagePlainText falls back to user summary body then title when no visible text parts`() {
        assertEquals(
            "body text",
            messagePlainText(userMessage(id = "msg_user", text = "", summaryTitle = "title text", summaryBody = "body text")),
        )
        assertEquals(
            "title text",
            messagePlainText(userMessage(id = "msg_user", text = "", summaryTitle = "title text", summaryBody = "")),
        )
        assertEquals("", messagePlainText(assistantMessage(id = "msg_assistant", text = "")))
    }

    @Test
    fun `messageMarkdown uses role header and only plain text content`() {
        val markdown = messageMarkdown(
            assistantMessage(
                id = "msg_assistant",
                parts = listOf(
                    textPart("msg_assistant", "assistant answer"),
                    Part.Reasoning(id = "reason", sessionId = SESSION_ID, messageId = "msg_assistant", text = "hidden-reasoning"),
                ),
            ),
        )

        assertEquals("> Assistant\n\nassistant answer", markdown)
        assertFalse(markdown.contains("hidden-reasoning"))
    }

    @Test
    fun `quoteMessageText quotes plain text deterministically and caps long source`() {
        val text = (1..41).joinToString("\n") { "line $it" }
        val quote = quoteMessageText(userMessage(id = "msg_user", text = text))

        assertTrue(quote.startsWith("> line 1\n> line 2"))
        assertTrue(quote.contains("> line 40"))
        assertFalse(quote.contains("line 41"))
        assertTrue(quote.endsWith("> …\n\n"))
        assertEquals(41, quote.lines().count { it.startsWith("> ") })
    }

    private fun assertEnabled(action: MessageCardActionState) {
        assertTrue(action.enabled)
        assertNull(action.disabledReason)
    }

    private fun assertDisabledForStreaming(action: MessageCardActionState) {
        assertFalse(action.enabled)
        assertEquals(MessageCardActionDisabledReason.StreamingOrBusy, action.disabledReason)
    }

    private fun userMessage(
        id: String,
        text: String = "prompt",
        summaryTitle: String? = null,
        summaryBody: String? = null,
        parts: List<Part> = if (text.isBlank()) emptyList() else listOf(textPart(id, text)),
    ): ChatMessage {
        return ChatMessage(
            message = Message.User(
                id = id,
                sessionId = SESSION_ID,
                time = TimeInfo(created = 1L),
                summary = Message.User.UserSummary(title = summaryTitle, body = summaryBody),
            ),
            parts = parts,
        )
    }

    private fun assistantMessage(
        id: String,
        text: String = "answer",
        parts: List<Part> = if (text.isBlank()) emptyList() else listOf(textPart(id, text)),
    ): ChatMessage {
        return ChatMessage(
            message = Message.Assistant(
                id = id,
                sessionId = SESSION_ID,
                time = TimeInfo(created = 1L),
            ),
            parts = parts,
        )
    }

    private fun textPart(
        messageId: String,
        text: String,
        synthetic: Boolean? = null,
        ignored: Boolean? = null,
    ): Part.Text {
        return Part.Text(
            id = "part-$messageId-${text.hashCode()}",
            sessionId = SESSION_ID,
            messageId = messageId,
            text = text,
            synthetic = synthetic,
            ignored = ignored,
        )
    }

    private companion object {
        const val SESSION_ID = "ses_test"
    }
}
