package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class DshSessionEvent(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement? = null,
    val sourceEventSeqs: List<Long>? = null,
    val surfaceOp: JsonElement? = null,
    val ignorable: Boolean? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class DshQueuedInboxItem(
    val id: String,
    val placement: String,
    val message: JsonElement,
)

data class DshJobView(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val detail: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
)

data class DshQuestionIntent(
    val kind: String,
    val approve: String? = null,
)

data class DshQuestionItem(
    val id: String,
    val question: String,
    val header: String? = null,
    val detail: String? = null,
    val options: List<DshQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val intent: DshQuestionIntent? = null,
)

data class DshQuestionOption(
    val label: String,
    val description: String? = null,
)

/** Frames carried by the Gateway-internal `$events` logical stream. */
sealed interface DshEventsFrame {
    data class Ready(
        val clientId: String,
        val home: String,
    ) : DshEventsFrame

    data class Emit(
        val event: String,
        val args: List<JsonElement>,
    ) : DshEventsFrame

    data class Waterfall(
        val eventId: String,
        val event: String,
        val agentId: String,
        val request: JsonObject,
    ) : DshEventsFrame

    data class CancelEvent(val eventId: String) : DshEventsFrame
}

/** Frames carried by the `session/control` logical stream. */
sealed interface DshControlFrame {
    data class Baseline(
        val queues: Map<String, List<DshQueuedInboxItem>>,
        val jobs: Map<String, List<DshJobView>>,
        val projections: Map<String, DshProjectionsBlock>,
    ) : DshControlFrame

    data class Queue(
        val sessionId: String,
        val items: List<DshQueuedInboxItem>,
    ) : DshControlFrame

    data class Jobs(
        val sessionId: String,
        val jobs: List<DshJobView>,
    ) : DshControlFrame

    data class Projection(
        val sessionId: String,
        val key: String,
        val value: JsonElement?,
        val seq: Long,
    ) : DshControlFrame
}

/** Frames carried by the `workspace/follow` logical stream. */
sealed interface DshWorkspaceFrame {
    data class Baseline(val value: DshWorkspaceListValue) : DshWorkspaceFrame

    data class Upsert(val workspace: DshWorkspaceView) : DshWorkspaceFrame
    data class Remove(val workspaceId: String) : DshWorkspaceFrame
    data class Order(val workspaceIds: List<String>) : DshWorkspaceFrame
    data class Archived(val archivedSessionIds: List<String>) : DshWorkspaceFrame
    data class Hidden(val hiddenWorkspaceIds: List<String>) : DshWorkspaceFrame
}

/** Frames carried by one addressed `session/follow` logical stream. */
sealed interface DshFollowFrame {
    data class Snapshot(
        val header: JsonObject?,
        val cursor: Long,
        val records: List<DshHistoryRecord>,
        val hasMore: Boolean,
        val projections: DshProjectionsBlock? = null,
    ) : DshFollowFrame

    data class FollowEvent(val event: DshSessionEvent) : DshFollowFrame
}

data class DshEnvelope<T>(
    val rpcId: String,
    val payload: T,
)

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.reqStr(key: String): String = str(key).orEmpty()
private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true
private fun JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull
        ?: this[key]?.jsonPrimitive?.intOrNull?.toLong()
        ?: 0L

/** Parse one `$events` stream item. */
fun parseEventsFrame(value: JsonElement?): DshEventsFrame? {
    val obj = (value as? JsonObject) ?: return null
    return when (obj.reqStr("type")) {
        "ready" -> DshEventsFrame.Ready(
            clientId = obj.reqStr("clientId"),
            home = obj["host"]?.jsonObject?.str("home").orEmpty(),
        )
        "emit" -> DshEventsFrame.Emit(
            event = obj.reqStr("event"),
            args = (obj["args"] as? JsonArray).orEmpty(),
        )
        "waterfall" -> DshEventsFrame.Waterfall(
            eventId = obj.reqStr("eventId"),
            event = obj.reqStr("event"),
            agentId = obj.reqStr("agentId"),
            request = obj["request"] as? JsonObject ?: JsonObject(emptyMap()),
        )
        "cancel" -> DshEventsFrame.CancelEvent(obj.reqStr("eventId"))
        else -> null
    }
}

/** Parse one `session/control` stream item. */
fun parseControlFrame(value: JsonElement?): DshControlFrame? {
    val obj = (value as? JsonObject) ?: return null
    return when (obj.reqStr("type")) {
        "baseline" -> {
            val base = obj["value"]?.jsonObject ?: JsonObject(emptyMap())
            DshControlFrame.Baseline(
                queues = recordOfLists(base["queues"]) { parseQueueItem(it) },
                jobs = recordOfLists(base["jobs"]) { parseJob(it) },
                projections = base["projections"]?.jsonObject?.entries?.associate { (sessionId, block) ->
                    sessionId to (
                        runCatching { parseProjectionsBlock(block) }.getOrNull()
                            ?: DshProjectionsBlock(asOfSeq = 0L)
                        )
                }.orEmpty(),
            )
        }
        "queue" -> DshControlFrame.Queue(
            sessionId = obj.reqStr("sessionId"),
            items = (obj["items"] as? JsonArray).orEmpty().mapNotNull(::parseQueueItem),
        )
        "jobs" -> DshControlFrame.Jobs(
            sessionId = obj.reqStr("sessionId"),
            jobs = (obj["jobs"] as? JsonArray).orEmpty().mapNotNull(::parseJob),
        )
        "projection" -> DshControlFrame.Projection(
            sessionId = obj.reqStr("sessionId"),
            key = obj.reqStr("key"),
            value = obj["value"],
            seq = obj.long("seq"),
        )
        else -> null
    }
}

/** Parse one `workspace/follow` stream item. */
fun parseWorkspaceFrame(value: JsonElement?): DshWorkspaceFrame? {
    val obj = (value as? JsonObject) ?: return null
    return when (obj.reqStr("type")) {
        "baseline" -> DshWorkspaceFrame.Baseline(
            value = parseWorkspaceListValue(obj["value"]),
        )
        "upsert" -> DshWorkspaceFrame.Upsert(parseWorkspaceView(obj["workspace"]) ?: return null)
        "remove" -> DshWorkspaceFrame.Remove(obj.reqStr("workspaceId"))
        "order" -> DshWorkspaceFrame.Order(stringList(obj["workspaceIds"]))
        "archived" -> DshWorkspaceFrame.Archived(stringList(obj["archivedSessionIds"]))
        "hidden" -> DshWorkspaceFrame.Hidden(stringList(obj["hiddenWorkspaceIds"]))
        else -> null
    }
}

/** Parse one `session/follow` stream item. */
fun parseFollowFrame(value: JsonElement?): DshFollowFrame? {
    val obj = (value as? JsonObject) ?: return null
    return when (obj.reqStr("type")) {
        "snapshot" -> DshFollowFrame.Snapshot(
            header = obj["header"] as? JsonObject,
            cursor = obj.long("cursor"),
            records = (obj["records"] as? JsonArray).orEmpty().mapNotNull(::parseHistoryRecord),
            hasMore = obj.bool("hasMore"),
            projections = runCatching { parseProjectionsBlock(obj["projections"]) }.getOrNull(),
        )
        "event" -> DshFollowFrame.FollowEvent(parseSessionEvent(obj["event"]))
        else -> null
    }
}

/** History records are `{type: "event", event}` or packed `{type: "chunks", event}`. */
fun parseHistoryRecord(element: JsonElement?): DshHistoryRecord? {
    val obj = (element as? JsonObject) ?: return null
    val event = obj["event"] ?: return null
    return DshHistoryRecord(
        type = obj.reqStr("type").ifBlank { "event" },
        event = parseSessionEventDto(event),
    )
}

private fun parseSessionEventDto(element: JsonElement): DshSessionEventDto {
    val obj = (element as? JsonObject) ?: JsonObject(emptyMap())
    val seqs = (obj["sourceEventSeqs"] as? JsonArray)?.mapNotNull {
        it.jsonPrimitive.longOrNull ?: it.jsonPrimitive.intOrNull?.toLong()
    }
    return DshSessionEventDto(
        type = obj.reqStr("type"),
        seq = obj.long("seq"),
        time = obj.long("time"),
        data = obj["data"],
        sourceEventSeqs = seqs,
        surfaceOp = obj["surfaceOp"],
        ignorable = obj["ignorable"]?.jsonPrimitive?.booleanOrNull,
    )
}

fun parseSessionEvent(element: JsonElement?): DshSessionEvent {
    val obj = (element as? JsonObject) ?: JsonObject(emptyMap())
    val seqs = (obj["sourceEventSeqs"] as? JsonArray)?.mapNotNull {
        it.jsonPrimitive.longOrNull ?: it.jsonPrimitive.intOrNull?.toLong()
    }
    return DshSessionEvent(
        type = obj.reqStr("type"),
        seq = obj.long("seq"),
        time = obj.long("time"),
        data = obj["data"],
        sourceEventSeqs = seqs,
        surfaceOp = obj["surfaceOp"],
        ignorable = obj["ignorable"]?.jsonPrimitive?.booleanOrNull,
        raw = obj,
    )
}

private fun parseWorkspaceListValue(element: JsonElement?): DshWorkspaceListValue {
    val obj = (element as? JsonObject) ?: JsonObject(emptyMap())
    return DshWorkspaceListValue(
        items = (obj["items"] as? JsonArray).orEmpty().mapNotNull(::parseWorkspaceView),
        archivedSessionIds = stringList(obj["archivedSessionIds"]),
        hiddenWorkspaceIds = stringList(obj["hiddenWorkspaceIds"]),
    )
}

private fun parseWorkspaceView(element: JsonElement?): DshWorkspaceView? {
    val obj = (element as? JsonObject) ?: return null
    return DshWorkspaceView(
        workspaceId = obj.reqStr("workspaceId"),
        path = obj.reqStr("path"),
        folders = stringList(obj["folders"]),
        title = obj.reqStr("title"),
        sessionIds = stringList(obj["sessionIds"]),
        createdAt = obj.reqStr("createdAt"),
        updatedAt = obj.reqStr("updatedAt"),
    )
}

private fun parseProjectionsBlock(element: JsonElement?): DshProjectionsBlock? {
    val obj = (element as? JsonObject) ?: return null
    return DshProjectionsBlock(
        asOfSeq = obj.long("asOfSeq"),
        values = obj["values"] as? JsonObject ?: JsonObject(emptyMap()),
    )
}

private fun <T> recordOfLists(
    element: JsonElement?,
    parse: (JsonElement) -> T?,
): Map<String, List<T>> {
    val obj = (element as? JsonObject) ?: return emptyMap()
    return obj.entries.associate { (sessionId, rows) ->
        sessionId to (rows as? JsonArray).orEmpty().mapNotNull(parse)
    }
}

private fun parseQuestionItem(element: JsonElement): DshQuestionItem? {
    val obj = element as? JsonObject ?: return null
    val options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { option ->
        val optionObj = option as? JsonObject ?: return@mapNotNull null
        DshQuestionOption(
            label = optionObj.reqStr("label"),
            description = optionObj.str("description"),
        )
    }
    val intentObj = obj["intent"] as? JsonObject
    return DshQuestionItem(
        id = obj.reqStr("id"),
        question = obj.reqStr("question"),
        header = obj.str("header"),
        detail = obj.str("detail"),
        options = options,
        multiSelect = obj.bool("multiSelect"),
        intent = intentObj?.let {
            DshQuestionIntent(
                kind = it.reqStr("kind"),
                approve = it.str("approve"),
            )
        },
    )
}

fun parseQuestionItems(element: JsonElement?): List<DshQuestionItem> =
    (element as? JsonArray).orEmpty().mapNotNull(::parseQuestionItem)

private fun parseQueueItem(element: JsonElement): DshQueuedInboxItem? {
    val obj = element as? JsonObject ?: return null
    return DshQueuedInboxItem(
        id = obj.reqStr("id"),
        placement = obj.reqStr("placement"),
        message = obj["message"] ?: JsonObject(emptyMap()),
    )
}

private fun parseJob(element: JsonElement): DshJobView? {
    val obj = element as? JsonObject ?: return null
    return DshJobView(
        id = obj.reqStr("id"),
        kind = obj.reqStr("kind"),
        label = obj.reqStr("label"),
        status = obj.reqStr("status"),
        detail = obj.str("detail"),
        startedAt = obj.long("startedAt"),
        finishedAt = obj["finishedAt"]?.jsonPrimitive?.longOrNull
            ?: obj["finishedAt"]?.jsonPrimitive?.intOrNull?.toLong(),
    )
}

private fun stringList(element: JsonElement?): List<String> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
