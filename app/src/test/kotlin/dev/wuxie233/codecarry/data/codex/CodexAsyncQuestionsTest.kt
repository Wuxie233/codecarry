package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAsyncQuestionsTest {
    @Test
    fun `async final answer is decoded as structured questions with Web compatible IDs`() {
        val thread = thread(question())
        val questions = codexAsyncQuestions(thread)
        assertEquals("final_answer", thread.turns.single().items.single().phase)
        assertEquals(2, questions.size)
        assertEquals("[\"request_user_input_async\",\"call-question\",0]", questions[0].id)
        assertEquals(listOf("First", "Second"), questions[0].options)
        assertTrue(questions.all { it.canAnswer })
    }

    @Test
    fun `batch reply preserves exact question ID title and custom text in steer wrapper`() {
        val thread = thread(question())
        val questions = codexAsyncQuestions(thread)
        val answers = linkedMapOf(questions[0].id to "First", questions[1].id to "Custom\n\"quoted\" answer")
        val text = codexAsyncQuestionReplyText(thread, "turn-1", answers)!!
        assertTrue(text.startsWith("<send_user_message_question_reply>\n["))
        assertTrue(text.endsWith("\n</send_user_message_question_reply>"))
        val payload = Json.parseToJsonElement(text.substringAfter('\n').substringBeforeLast('\n')) as JsonArray
        assertEquals(questions[0].id, payload[0].jsonObject["questionItemId"]!!.jsonPrimitive.content)
        assertEquals("Which option?", payload[0].jsonObject["question"]!!.jsonPrimitive.content)
        assertEquals("Custom\n\"quoted\" answer", payload[1].jsonObject["answer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `completed interrupted absent and wrong turns cannot be answered`() {
        val active = thread(question())
        val answers = mapOf(codexAsyncQuestions(active).first().id to "First")
        for (status in listOf("completed", "interrupted", "failed", "unknown")) {
            val expired = active.copy(turns = active.turns.map { it.copy(status = status) })
            assertFalse(codexAsyncQuestions(expired).any { it.canAnswer })
            assertNull(codexAsyncQuestionReplyText(expired, "turn-1", answers))
        }
        assertNull(codexAsyncQuestionReplyText(active, "another-turn", answers))
        assertNull(codexAsyncQuestionReplyText(null, "turn-1", answers))
        assertNull(codexAsyncQuestionReplyText(active, "turn-1", emptyMap()))
        assertNull(codexAsyncQuestionReplyText(active, "turn-1", mapOf(answers.keys.single() to " ")))
        assertNull(codexAsyncQuestionReplyText(active, "turn-1", mapOf("foreign-question" to "yes")))
    }

    @Test
    fun `only accepted steering or materialized user message marks the same turn question answered`() {
        val question = question()
        val original = thread(question)
        val id = codexAsyncQuestions(original).first().id
        val text = codexAsyncQuestionReplyText(original, "turn-1", mapOf(id to "First"))!!
        for (status in listOf("pending", "rejected")) {
            assertNull(codexAsyncQuestions(thread(question, reply(text, "steeringUserMessage", status))).first().answer)
        }
        for (reply in listOf(reply(text), reply(text, "steeringUserMessage", "accepted"))) {
            val questions = codexAsyncQuestions(thread(question, reply))
            assertEquals("First", questions.first().answer)
            assertFalse(questions.first().canAnswer)
            assertTrue(questions[1].canAnswer)
            assertNull(codexAsyncQuestionReplyText(thread(question, reply), "turn-1", mapOf(id to "Second")))
        }
        val otherTurn = original.copy(turns = original.turns + CodexTurn("turn-2", items = listOf(reply(text))))
        assertNull(codexAsyncQuestions(otherTurn).first().answer)
    }

    @Test
    fun `question without structured entries falls back to item ID and text`() {
        val item = CodexThreadItem.fromJson(Json.parseToJsonElement(
            """{"type":"agentMessage","id":"legacy","delivery":"async","text":"What next?"}""",
        ).jsonObject)
        val question = codexAsyncQuestions(thread(item)).single()
        assertEquals("legacy", question.id)
        assertEquals("What next?", question.title)
        assertTrue(question.options.isEmpty())
        assertTrue(codexAsyncQuestions(thread(item.copy(delivery = null))).isEmpty())
    }

    @Test
    fun `history renders wrapper as question and answer and keeps ordinary messages intact`() {
        val thread = thread(question())
        val id = codexAsyncQuestions(thread).first().id
        val text = codexAsyncQuestionReplyText(thread, "turn-1", mapOf(id to "First"))!!
        assertEquals("Which option?\nFirst", codexAsyncReplyDisplayText(reply(text)))
        assertNull(codexAsyncReplyDisplayText(reply("ordinary user text")))
        assertNull(codexAsyncReplyDisplayText(reply("<send_user_message_question_reply>[]</send_user_message_question_reply>")))
        assertNull(codexAsyncReplyDisplayText(reply("<send_user_message_question_reply>{\"questionItemId\":1,\"question\":\"Q\",\"answer\":\"A\"}</send_user_message_question_reply>")))
        assertNull(codexAsyncReplyDisplayText(CodexThreadItem(type = "agentMessage", text = text)))
    }

    @Test
    fun `Web singleton historical reply is recognized but multipart user content is not hidden`() {
        val text = """<send_user_message_question_reply>{"questionItemId":"legacy","question":"Q","answer":"A"}</send_user_message_question_reply>"""
        assertEquals("Q\nA", codexAsyncReplyDisplayText(reply(text)))
        val item = reply(text)
        val content = item.raw["content"] as JsonArray
        val multipart = item.copy(raw = buildJsonObject {
            put("content", JsonArray(content + buildJsonObject { put("type", "text"); put("text", "Additional text") }))
        })
        assertNull(codexAsyncReplyDisplayText(multipart))
    }

    @Test
    fun `late materialized answer cannot replace a newer accepted steering answer`() {
        val question = question()
        val original = thread(question)
        val id = codexAsyncQuestions(original).first().id
        val first = codexAsyncQuestionReplyText(original, "turn-1", mapOf(id to "First"))!!
        val second = codexAsyncQuestionReplyText(original, "turn-1", mapOf(id to "Second"))!!
        val items = thread(
            question,
            reply(first, "steeringUserMessage", "accepted"),
            reply(second, "steeringUserMessage", "accepted"),
            reply(first),
        )
        assertEquals("Second", codexAsyncQuestions(items).first().answer)
    }

    private fun question() = CodexThreadItem.fromJson(Json.parseToJsonElement(
        """{"type":"agentMessage","id":"call-question","text":"Two questions","phase":"final_answer","delivery":"async","questions":[{"title":"Which option?","options":["First","Second"]},{"title":"Details?","options":[]}]}""",
    ).jsonObject)

    private fun thread(vararg items: CodexThreadItem) = CodexThread(
        id = "thread-1",
        turns = listOf(CodexTurn("turn-1", status = "inProgress", items = items.toList())),
    )

    private fun reply(text: String, type: String = "userMessage", status: String? = null): CodexThreadItem =
        CodexThreadItem.fromJson(buildJsonObject {
            put("id", "reply")
            put("type", type)
            status?.let { put("status", it) }
            put(if (type == "userMessage") "content" else "input", JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", text)
            })))
        })
}
