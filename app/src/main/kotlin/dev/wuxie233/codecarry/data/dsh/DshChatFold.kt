package dev.wuxie233.codecarry.data.dsh

import dev.wuxie233.codecarry.data.api.PromptPart
import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.MessageWithParts
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.TimeInfo
import dev.wuxie233.codecarry.domain.model.ToolState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val DSH_PROMPT_QUEUE = "queue"
internal const val DSH_PROMPT_STEER = "steer"

data class DshSurfaceOp(
    val append: Boolean,
    val start: Long? = null,
    val end: Long? = null,
)

data class DshPromptRequest(
    val mode: String,
    val content: JsonArray,
    val isSlashCommand: Boolean,
)

fun dshPromptMode(steer: Boolean): String = if (steer) DSH_PROMPT_STEER else DSH_PROMPT_QUEUE

fun dshPromptRequest(parts: List<PromptPart>, steer: Boolean): DshPromptRequest {
    val content = buildJsonArray {
        parts.forEach { part ->
            when {
                part.type == "text" && !part.text.isNullOrBlank() -> add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", part.text)
                    },
                )
                part.type == "file" || part.type == "image" -> {
                    val parsed = parseDataUrl(part.url.orEmpty())
                    if (parsed != null) {
                        add(
                            buildJsonObject {
                                put("type", "image")
                                put("mediaType", part.mime ?: parsed.mediaType)
                                put("data", parsed.data)
                                part.filename?.let { put("name", it) }
                            },
                        )
                    } else {
                        val mention = part.path?.takeIf { it.isNotBlank() }
                            ?: part.filename?.takeIf { it.isNotBlank() }
                            ?: part.url?.removePrefix("file:///")?.takeIf { it.isNotBlank() }
                        if (!mention.isNullOrBlank()) {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", mention)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    return DshPromptRequest(
        mode = dshPromptMode(steer),
        content = content,
        isSlashCommand = isDshSlashCommand(content),
    )
}

fun isDshSlashCommand(content: JsonArray): Boolean {
    if (content.size != 1) return false
    val block = content.first() as? JsonObject ?: return false
    if (block.str("type") != "text") return false
    return block.str("text").orEmpty().startsWith("/")
}

fun parseDshSurfaceOp(element: JsonElement?): DshSurfaceOp? {
    return when (element) {
        is JsonPrimitive -> if (element.contentOrNull == "append") DshSurfaceOp(append = true) else null
        is JsonObject -> {
            if (element.str("op") != "replace") return null
            val start = element.long("start") ?: return null
            val end = element.long("end") ?: return null
            DshSurfaceOp(append = false, start = start, end = end)
        }
        else -> null
    }
}

fun DshSessionEventDto.toSessionEvent(): DshSessionEvent = DshSessionEvent(
    type = type,
    seq = seq,
    time = time,
    data = data,
    sourceEventSeqs = sourceEventSeqs,
    surfaceOp = surfaceOp,
    ignorable = ignorable,
    raw = JsonObject(emptyMap()),
)

fun dshMessageSeq(messageId: String): Long? =
    messageId.substringAfterLast('-', missingDelimiterValue = "").toLongOrNull()

fun foldDshHistory(sessionId: String, events: List<DshSessionEvent>): List<MessageWithParts> {
    val ordered = events.sortedBy { it.seq }
    val messages = linkedMapOf<String, MessageWithParts>()
    val seqToMessageId = mutableMapOf<Long, String>()
    val streamByStep = mutableMapOf<String, StreamAssembler>()
    val toolOwners = mutableMapOf<String, String>()

    fun removeRange(start: Long, end: Long) {
        val ids = seqToMessageId.filter { (seq, _) -> seq in start..end }.values.toSet()
        ids.forEach { messages.remove(it) }
        seqToMessageId.entries.removeAll { it.value in ids }
    }

    for (event in ordered) {
        when (event.type) {
            "user/message" -> {
                val op = parseDshSurfaceOp(event.surfaceOp) ?: continue
                val sourceKind = event.data?.obj()?.obj("source")?.str("kind")
                val isUserRewrite = !op.append && sourceKind == "user"
                if (!op.append && !isUserRewrite) continue
                if (isUserRewrite) {
                    val start = op.start ?: continue
                    val end = op.end ?: continue
                    removeRange(start, end)
                }
                val message = userMessage(sessionId, event) ?: continue
                val messageId = dshFoldedMessageId(event.seq, message.info.id)
                messages[messageId] = message.copy(info = (message.info as Message.User).copy(id = messageId))
                seqToMessageId[event.seq] = messageId
            }
            "assistant/chunk" -> {
                val data = event.data?.obj() ?: continue
                val key = stepKey(data)
                val assembler = streamByStep.getOrPut(key) {
                    StreamAssembler(
                        sessionId = sessionId,
                        turn = data.long("turn") ?: 0L,
                        step = data.long("step") ?: 0L,
                    )
                }
                assembler.applyChunk(event)
                messages[assembler.messageId] = assembler.toMessage()
                seqToMessageId[event.seq] = assembler.messageId
            }
            "assistant/message" -> {
                val data = event.data?.obj() ?: continue
                val stream = streamByStep.remove(stepKey(data))
                if (stream != null) {
                    messages.remove(stream.messageId)
                    seqToMessageId.entries.removeAll { it.value == stream.messageId }
                }
                val message = assistantMessage(sessionId, event) ?: continue
                val messageId = dshFoldedMessageId(event.seq, message.info.id)
                val remapped = message.copy(
                    info = (message.info as Message.Assistant).copy(id = messageId),
                    parts = message.parts.map { part ->
                        when (part) {
                            is Part.Text -> part.copy(messageId = messageId)
                            is Part.Reasoning -> part.copy(messageId = messageId)
                            is Part.File -> part.copy(messageId = messageId)
                            is Part.Tool -> part.copy(messageId = messageId)
                            else -> part
                        }
                    },
                )
                messages[messageId] = remapped
                seqToMessageId[event.seq] = messageId
                remapped.parts.filterIsInstance<Part.Tool>().forEach { tool ->
                    if (tool.callId.isNotBlank()) toolOwners[tool.callId] = messageId
                }
            }
            "tool/call" -> {
                val data = event.data?.obj() ?: continue
                val callId = data.str("callId").orEmpty()
                if (callId.isBlank()) continue
                val ownerId = currentAssistantId(messages, data) ?: continue
                toolOwners[callId] = ownerId
                val owner = messages[ownerId] ?: continue
                if (owner.parts.any { it is Part.Tool && it.callId == callId }) continue
                messages[ownerId] = owner.copy(
                    parts = owner.parts + Part.Tool(
                        id = "dsh-tool-$callId",
                        sessionId = sessionId,
                        messageId = ownerId,
                        callId = callId,
                        tool = data.str("name").orEmpty(),
                        state = ToolState.Pending(raw = data.str("arguments")),
                    ),
                )
            }
            "tool/result" -> {
                val data = event.data?.obj() ?: continue
                val resultMessage = data.obj("message")
                val callId = data.str("callId")
                    ?: resultMessage?.arr("content")?.firstOrNull()
                        ?.let { it as? JsonObject }
                        ?.str("toolCallId")
                    ?: continue
                val ownerId = toolOwners[callId] ?: currentAssistantId(messages, data) ?: continue
                val owner = messages[ownerId] ?: continue
                val output = toolResultText(resultMessage)
                val isError = resultMessage?.bool("isError") == true || data.bool("isError")
                val updatedParts = owner.parts.map { part ->
                    if (part is Part.Tool && part.callId == callId) {
                        part.copy(
                            state = if (isError) {
                                ToolState.Error(error = output)
                            } else {
                                ToolState.Completed(output = output)
                            },
                        )
                    } else {
                        part
                    }
                }.let { parts ->
                    if (parts.any { it is Part.Tool && it.callId == callId }) {
                        parts
                    } else {
                        parts + Part.Tool(
                            id = "dsh-tool-$callId",
                            sessionId = sessionId,
                            messageId = ownerId,
                            callId = callId,
                            tool = data.str("name").orEmpty().ifBlank { "tool" },
                            state = if (isError) ToolState.Error(error = output) else ToolState.Completed(output = output),
                        )
                    }
                }
                messages[ownerId] = owner.copy(parts = updatedParts)
            }
        }
    }

    return messages.values.sortedWith(
        compareBy<MessageWithParts> { it.info.time.created }.thenBy { it.info.id },
    )
}

fun dshQueueItemText(item: DshQueuedInboxItem): String {
    val content = (item.message as? JsonObject)?.arr("content") ?: return ""
    return content.mapNotNull { block ->
        (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
    }.joinToString("").trim()
}

fun dshProjectionTitle(projections: Map<String, Pair<Long, JsonElement?>>): String? {
    val value = projections["title"]?.second ?: return null
    return (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

fun dshWorkspaceTitle(workspace: JsonObject): String =
    workspace.str("title")?.takeIf { it.isNotBlank() }
        ?: workspace.str("path")?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: workspace.str("workspaceId").orEmpty()

fun dshWorkspacePath(workspace: JsonObject): String =
    workspace.str("path")?.ifBlank { null } ?: workspace.str("workspaceId").orEmpty()

fun dshWorkspaceSessionIds(workspace: JsonObject): List<String> =
    workspace.arr("sessionIds")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

private data class StreamAssembler(
    val sessionId: String,
    val turn: Long,
    val step: Long,
    val messageId: String = "dsh-stream-$turn-$step",
    private val text: StringBuilder = StringBuilder(),
    private val reasoning: StringBuilder = StringBuilder(),
    private var time: Long = 0L,
) {
    fun applyChunk(event: DshSessionEvent) {
        if (time == 0L) time = event.time
        val chunk = event.data?.obj()?.obj("chunk") ?: return
        when (chunk.str("type")) {
            "text-delta" -> text.append(chunk.str("text").orEmpty())
            "reasoning-delta" -> reasoning.append(chunk.str("text").orEmpty())
        }
    }

    fun toMessage(): MessageWithParts {
        val parts = buildList {
            if (reasoning.isNotEmpty()) {
                add(
                    Part.Reasoning(
                        id = "$messageId-reasoning",
                        sessionId = sessionId,
                        messageId = messageId,
                        text = reasoning.toString(),
                    ),
                )
            }
            if (text.isNotEmpty()) {
                add(
                    Part.Text(
                        id = "$messageId-text",
                        sessionId = sessionId,
                        messageId = messageId,
                        text = text.toString(),
                    ),
                )
            }
        }
        return MessageWithParts(
            info = Message.Assistant(
                id = messageId,
                sessionId = sessionId,
                time = TimeInfo(created = time),
            ),
            parts = parts,
        )
    }
}

private fun userMessage(sessionId: String, event: DshSessionEvent): MessageWithParts? {
    val data = event.data?.obj() ?: return null
    val content = data.arr("content") ?: JsonArray(emptyList())
    val id = data.str("id")?.ifBlank { null } ?: "dsh-user-${event.seq}"
    val parts = contentBlocksToParts(sessionId, id, content)
    if (parts.isEmpty()) return null
    return MessageWithParts(
        info = Message.User(
            id = id,
            sessionId = sessionId,
            time = TimeInfo(created = event.time),
        ),
        parts = parts,
    )
}

private fun assistantMessage(sessionId: String, event: DshSessionEvent): MessageWithParts? {
    val data = event.data?.obj() ?: return null
    val nested = data.obj("message") ?: return null
    val content = nested.arr("content") ?: JsonArray(emptyList())
    if (content.isEmpty()) return null
    val id = nested.str("id")?.ifBlank { null } ?: "dsh-asst-${event.seq}"
    val usage = data.obj("usage")
    val tokens = usage?.let {
        Message.Assistant.Tokens(
            input = it.int("inputTokens") ?: 0,
            output = it.int("outputTokens") ?: 0,
            reasoning = it.int("reasoningTokens") ?: 0,
        )
    }
    val source = nested.obj("source")
    return MessageWithParts(
        info = Message.Assistant(
            id = id,
            sessionId = sessionId,
            time = TimeInfo(
                created = event.time,
                completed = if (data.bool("interrupted") == true) null else event.time,
            ),
            providerId = source?.str("provider"),
            modelId = source?.str("model"),
            tokens = tokens,
        ),
        parts = contentBlocksToParts(sessionId, id, content),
    )
}

private fun contentBlocksToParts(sessionId: String, messageId: String, content: JsonArray): List<Part> {
    return content.mapIndexedNotNull { index, element ->
        val block = element as? JsonObject ?: return@mapIndexedNotNull null
        val partId = "$messageId-$index"
        when (block.str("type")) {
            "text" -> Part.Text(
                id = partId,
                sessionId = sessionId,
                messageId = messageId,
                text = block.str("text").orEmpty(),
            )
            "reasoning" -> Part.Reasoning(
                id = partId,
                sessionId = sessionId,
                messageId = messageId,
                text = block.str("text").orEmpty(),
            )
            "image" -> {
                val attachment = block.obj("attachment")
                Part.File(
                    id = partId,
                    sessionId = sessionId,
                    messageId = messageId,
                    mime = attachment?.str("mediaType") ?: "image/*",
                    filename = attachment?.str("name"),
                    url = attachment?.str("attachmentId")?.let { "dsh-attachment:$it" },
                )
            }
            "tool-call" -> Part.Tool(
                id = partId,
                sessionId = sessionId,
                messageId = messageId,
                callId = block.str("id").orEmpty(),
                tool = block.str("name").orEmpty(),
                state = ToolState.Pending(raw = block.str("arguments")),
            )
            else -> null
        }
    }
}

private fun dshFoldedMessageId(seq: Long, rawId: String): String =
    if (rawId.endsWith("-$seq")) rawId else "$rawId-$seq"

private fun currentAssistantId(messages: Map<String, MessageWithParts>, data: JsonObject): String? {
    val turn = data.long("turn")
    val step = data.long("step")
    if (turn != null && step != null) {
        messages["dsh-stream-$turn-$step"]?.let { return it.info.id }
    }
    return messages.values.lastOrNull { it.info is Message.Assistant }?.info?.id
}

private fun stepKey(data: JsonObject): String = "${data.long("turn") ?: 0}:${data.long("step") ?: 0}"

private fun toolResultText(message: JsonObject?): String {
    val content = message?.arr("content") ?: return ""
    return content.mapNotNull { block ->
        val obj = block as? JsonObject ?: return@mapNotNull null
        when (obj.str("type")) {
            "text" -> obj.str("text")
            "tool-result" -> toolResultText(obj)
            else -> null
        }
    }.joinToString("\n").trim()
}

private data class DataUrl(val mediaType: String, val data: String)

private fun parseDataUrl(value: String): DataUrl? {
    val match = Regex("^data:([^;]+);base64,(.+)$", RegexOption.IGNORE_CASE).find(value.trim()) ?: return null
    return DataUrl(mediaType = match.groupValues[1], data = match.groupValues[2])
}

private fun JsonElement.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true
private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull ?: this[key]?.jsonPrimitive?.intOrNull?.toLong()
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
