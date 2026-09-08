package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import dev.wuxie233.codecarry.data.codex.CodexTurn
import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExportTest {
    @Test fun `native export includes offscreen messages and collapsed tool output`() {
        val messages = (0..200).map { index ->
            ChatMessage(Message.User("$index", "s"), listOf(Part.Text("p$index", "s", "$index", "message $index")))
        } + ChatMessage(Message.User("last", "s"), listOf(
            Part.Tool("tool", "s", "last", tool = "shell", state = ToolState.Completed(output = "nested ``` fence")),
            Part.File("image", "s", "last", mime = "image/png", url = "data:image/png;base64,private"),
        ))
        val export = chatConversationDocument(messages).toMarkdown()
        assertTrue(export.indexOf("message 0") < export.indexOf("message 200"))
        assertTrue(export.contains("````\nnested ``` fence\n````"))
        assertTrue(export.contains("[Attachment: image/png]"))
        assertFalse(export.contains("base64"))
    }

    @Test fun `codex exports markdown reasoning and turn errors without dumping images`() {
        val image = Json.parseToJsonElement("""{"content":[{"type":"image","url":"data:image/png;base64,private"}]}""").jsonObject
        val export = codexConversationDocument(listOf(CodexTurn(
            id = "t",
            items = listOf(
                CodexThreadItem(type = "userMessage", text = "look", raw = image),
                CodexThreadItem(type = "reasoning", reasoningSummary = listOf("summary"), reasoningContent = listOf("detail")),
                CodexThreadItem(type = "agentMessage", text = "| A |\n| --- |\n| **B** |"),
            ),
            error = Json.parseToJsonElement("""{"message":"turn failed"}"""),
        ))).toMarkdown()
        assertTrue(export.contains("[Image]"))
        assertTrue(export.contains("summary\n\ndetail"))
        assertTrue(export.contains("| **B** |"))
        assertTrue(export.contains("turn failed"))
        assertFalse(export.contains("base64"))
    }

    @Test fun `native and codex preserve the same assistant markdown`() {
        val source = "# Title\n\nFirst\n\n```kt\nval x = 1\n```\n\nLast"
        val native = chatConversationDocument(listOf(ChatMessage(
            Message.Assistant("a", "s"), listOf(Part.Text("p", "s", "a", source)),
        ))).toMarkdown()
        val codex = codexConversationDocument(listOf(CodexTurn("t", items = listOf(
            CodexThreadItem(type = "agentMessage", text = source),
        )))).toMarkdown()
        org.junit.Assert.assertEquals(native, codex)
    }
}
