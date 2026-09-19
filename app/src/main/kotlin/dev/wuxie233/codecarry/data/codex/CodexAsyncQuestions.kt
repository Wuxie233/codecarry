package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CodexAsyncQuestionDefinition(val title: String, val options: List<String> = emptyList())

data class CodexAsyncQuestion(
    val id: String,
    val sourceItemId: String,
    val turnId: String,
    val title: String,
    val options: List<String>,
    val answer: String? = null,
    val canAnswer: Boolean = false,
)

private data class AsyncReply(val questionItemId: String, val question: String, val answer: String)

private const val REPLY_START = "<send_user_message_question_reply>"
private const val REPLY_END = "</send_user_message_question_reply>"

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Async agent messages are questions even when their phase is final_answer. */
fun codexAsyncQuestions(thread: CodexThread?): List<CodexAsyncQuestion> = thread?.turns.orEmpty().flatMap { turn ->
    val answers = linkedMapOf<String, Pair<Int, String>>()
    val replyItems = turn.items.mapIndexedNotNull { index, item ->
        asyncReplies(item)?.let { Triple(index, item, it) }
    }
    replyItems.forEach { (index, item, replies) ->
        if (item.type == "steeringUserMessage" && item.status != "accepted") return@forEach
        // A materialized user message is the same answer as its earlier steering receipt.
        // Its later position must not overwrite a subsequent, revised answer.
        val answerIndex = if (item.type == "userMessage") {
            replyItems.firstOrNull { (priorIndex, priorItem, _) ->
                priorIndex < index && priorItem.type == "steeringUserMessage" && when {
                    priorItem.raw.string("serverUserMessageId") != null -> priorItem.raw.string("serverUserMessageId") == item.id
                    priorItem.raw.string("clientUserMessageId") != null && item.clientId != null ->
                        priorItem.raw.string("clientUserMessageId") == item.clientId
                    else -> priorItem.raw["input"] != null && priorItem.raw["input"] == item.raw["content"]
                }
            }?.first ?: index
        } else index
        replies.forEach { reply ->
            if (answerIndex > (answers[reply.questionItemId]?.first ?: -1)) {
                answers[reply.questionItemId] = answerIndex to reply.answer
            }
        }
    }
    turn.items.filter { it.type == "agentMessage" && it.delivery == "async" }.flatMap itemLoop@ { item ->
        val itemId = item.id ?: return@itemLoop emptyList()
        val definitions = item.questions.ifEmpty { listOf(CodexAsyncQuestionDefinition(item.text.orEmpty())) }
        definitions.mapIndexed { index, question ->
            val id = if (item.questions.isEmpty()) itemId else JsonArray(
                listOf(JsonPrimitive("request_user_input_async"), JsonPrimitive(itemId), JsonPrimitive(index)),
            ).toString()
            CodexAsyncQuestion(
                id = id,
                sourceItemId = itemId,
                turnId = turn.id,
                title = question.title,
                options = question.options,
                answer = answers[id]?.second,
                canAnswer = turn.status == "inProgress" && !answers.containsKey(id),
            )
        }
    }
}

/** The caller must steer this exact turn; a null result must never fall back to turn/start. */
fun codexAsyncQuestionReplyText(
    thread: CodexThread?,
    turnId: String,
    answers: Map<String, String>,
): String? {
    if (answers.isEmpty() || answers.values.any(String::isBlank)) return null
    val questions = codexAsyncQuestions(thread).filter { it.turnId == turnId && it.canAnswer }.associateBy { it.id }
    if (answers.keys.any { it !in questions }) return null
    val payload = JsonArray(answers.map { (id, answer) ->
        buildJsonObject {
            put("questionItemId", id)
            put("question", questions.getValue(id).title)
            put("answer", answer)
        }
    })
    return "$REPLY_START\n$payload\n$REPLY_END"
}

/** Keep internal wrapper markup out of the user's reply bubble. */
fun codexAsyncReplyDisplayText(item: CodexThreadItem): String? = asyncReplies(item)?.joinToString("\n\n") {
    "${it.question}\n${it.answer}"
}

private fun asyncReplies(item: CodexThreadItem): List<AsyncReply>? {
    if (item.type != "userMessage" && item.type != "steeringUserMessage") return null
    val contentKey = if (item.type == "userMessage") "content" else "input"
    val content = item.raw[contentKey] as? JsonArray
    val text = if (content != null) {
        val block = content.singleOrNull() as? JsonObject ?: return null
        if (block.string("type") != "text") return null
        block.string("text") ?: return null
    } else item.text ?: return null
    val trimmed = text.trim()
    if (!trimmed.startsWith(REPLY_START) || !trimmed.endsWith(REPLY_END)) return null
    val parsed = runCatching {
        Json.parseToJsonElement(trimmed.substring(REPLY_START.length, trimmed.length - REPLY_END.length))
    }.getOrNull() ?: return null
    val replies = when (parsed) {
        is JsonObject -> listOf(parsed)
        is JsonArray -> parsed.takeIf { it.isNotEmpty() } ?: return null
        else -> return null
    }
    return replies.map { value ->
        val reply = value as? JsonObject ?: return null
        fun stringValue(key: String): String? = (reply[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        AsyncReply(
            questionItemId = stringValue("questionItemId") ?: return null,
            question = stringValue("question") ?: return null,
            answer = stringValue("answer") ?: return null,
        )
    }
}
