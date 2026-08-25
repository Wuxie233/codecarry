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

sealed interface DshMuxFrame {
    val type: String

    data class SessionEvent(
        val sessionId: String,
        val event: DshSessionEvent,
        val view: JsonElement? = null,
    ) : DshMuxFrame {
        override val type: String = "session/event"
    }

    data class SessionSubscribed(
        val sessionId: String,
        val lastSeq: Long,
    ) : DshMuxFrame {
        override val type: String = "session/subscribed"
    }

    data class ApprovalRequested(
        val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val callId: String? = null,
        val reason: String? = null,
    ) : DshMuxFrame {
        override val type: String = "approval/requested"
    }

    data class ApprovalResolved(
        val sessionId: String,
        val approvalId: String,
        val outcome: String,
    ) : DshMuxFrame {
        override val type: String = "approval/resolved"
    }

    data class QuestionRequested(
        val sessionId: String,
        val questions: List<DshQuestionItem>,
    ) : DshMuxFrame {
        override val type: String = "question/requested"
    }

    data class QuestionResolved(
        val sessionId: String,
        val questionRpcId: String,
        val outcome: String,
    ) : DshMuxFrame {
        override val type: String = "question/resolved"
    }

    data class SessionQueue(
        val sessionId: String,
        val items: List<DshQueuedInboxItem>,
    ) : DshMuxFrame {
        override val type: String = "session/queue"
    }

    data class SessionJobs(
        val sessionId: String,
        val jobs: List<DshJobView>,
    ) : DshMuxFrame {
        override val type: String = "session/jobs"
    }

    data class SessionProjection(
        val sessionId: String,
        val key: String,
        val value: JsonElement?,
        val seq: Long,
    ) : DshMuxFrame {
        override val type: String = "session/projection"
    }

    data class StreamError(val error: DshRpcError) : DshMuxFrame {
        override val type: String = "stream/error"
    }

    data class Unknown(val rawType: String, val payload: JsonObject) : DshMuxFrame {
        override val type: String = rawType
    }
}

sealed interface DshHostFrame {
    val type: String

    data class SessionAdded(
        val sessionId: String,
        val blank: Boolean,
        val parentSessionId: String? = null,
        val origin: String? = null,
        val cwd: String? = null,
        val agentPreset: String? = null,
    ) : DshHostFrame {
        override val type: String = "host/session-added"
    }

    data class SessionRemoved(val sessionId: String) : DshHostFrame {
        override val type: String = "host/session-removed"
    }

    data class SessionStatus(val sessionId: String, val running: Boolean) : DshHostFrame {
        override val type: String = "host/session-status"
    }

    data class AgentError(val sessionId: String, val message: String) : DshHostFrame {
        override val type: String = "host/agent-error"
    }

    data class WorkspaceChanged(val workspace: JsonObject) : DshHostFrame {
        override val type: String = "host/workspace-changed"
    }

    data class WorkspaceRemoved(val workspaceId: String) : DshHostFrame {
        override val type: String = "host/workspace-removed"
    }

    data class WorkspaceOrderChanged(val workspaceIds: List<String>) : DshHostFrame {
        override val type: String = "host/workspace-order-changed"
    }

    data class ArchivedSessionsChanged(val archivedSessionIds: List<String>) : DshHostFrame {
        override val type: String = "host/archived-sessions-changed"
    }

    data class HiddenWorkspacesChanged(val hiddenWorkspaceIds: List<String>) : DshHostFrame {
        override val type: String = "host/hidden-workspaces-changed"
    }

    data class RemoteEvent(val event: String, val args: JsonArray) : DshHostFrame {
        override val type: String = "host/remote-event"
    }

    data class StreamError(val error: DshRpcError) : DshHostFrame {
        override val type: String = "stream/error"
    }

    data class Unknown(val rawType: String, val payload: JsonObject) : DshHostFrame {
        override val type: String = rawType
    }
}

data class DshEnvelope<T>(
    val rpcId: String,
    val payload: T,
)

private fun JsonElement.obj(): JsonObject = jsonObject
private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.reqStr(key: String): String = str(key).orEmpty()
private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true
private fun JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull
        ?: this[key]?.jsonPrimitive?.intOrNull?.toLong()
        ?: 0L

fun parseMuxFrame(payload: JsonElement): DshMuxFrame {
    val obj = payload.obj()
    return when (val type = obj.reqStr("type")) {
        "session/event" -> DshMuxFrame.SessionEvent(
            sessionId = obj.reqStr("sessionId"),
            event = parseSessionEvent(obj["event"]),
            view = obj["view"],
        )
        "session/subscribed" -> DshMuxFrame.SessionSubscribed(
            sessionId = obj.reqStr("sessionId"),
            lastSeq = obj.long("lastSeq"),
        )
        "approval/requested" -> DshMuxFrame.ApprovalRequested(
            sessionId = obj.reqStr("sessionId"),
            approvalId = obj.reqStr("approvalId"),
            toolName = obj.reqStr("toolName"),
            callId = obj.str("callId"),
            reason = obj.str("reason"),
        )
        "approval/resolved" -> DshMuxFrame.ApprovalResolved(
            sessionId = obj.reqStr("sessionId"),
            approvalId = obj.reqStr("approvalId"),
            outcome = obj.reqStr("outcome"),
        )
        "question/requested" -> DshMuxFrame.QuestionRequested(
            sessionId = obj.reqStr("sessionId"),
            questions = (obj["questions"] as? JsonArray).orEmpty().mapNotNull { parseQuestionItem(it) },
        )
        "question/resolved" -> DshMuxFrame.QuestionResolved(
            sessionId = obj.reqStr("sessionId"),
            questionRpcId = obj.reqStr("questionRpcId"),
            outcome = obj.reqStr("outcome"),
        )
        "session/queue" -> DshMuxFrame.SessionQueue(
            sessionId = obj.reqStr("sessionId"),
            items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { parseQueueItem(it) },
        )
        "session/jobs" -> DshMuxFrame.SessionJobs(
            sessionId = obj.reqStr("sessionId"),
            jobs = (obj["jobs"] as? JsonArray).orEmpty().mapNotNull { parseJob(it) },
        )
        "session/projection" -> DshMuxFrame.SessionProjection(
            sessionId = obj.reqStr("sessionId"),
            key = obj.reqStr("key"),
            value = obj["value"],
            seq = obj.long("seq"),
        )
        "stream/error" -> DshMuxFrame.StreamError(parseRpcError(obj["error"]))
        else -> DshMuxFrame.Unknown(type, obj)
    }
}

fun parseHostFrame(payload: JsonElement): DshHostFrame {
    val obj = payload.obj()
    return when (val type = obj.reqStr("type")) {
        "host/session-added" -> DshHostFrame.SessionAdded(
            sessionId = obj.reqStr("sessionId"),
            blank = obj.bool("blank"),
            parentSessionId = obj.str("parentSessionId"),
            origin = obj.str("origin"),
            cwd = obj.str("cwd"),
            agentPreset = obj.str("agentPreset"),
        )
        "host/session-removed" -> DshHostFrame.SessionRemoved(obj.reqStr("sessionId"))
        "host/session-status" -> DshHostFrame.SessionStatus(
            sessionId = obj.reqStr("sessionId"),
            running = obj.bool("running"),
        )
        "host/agent-error" -> DshHostFrame.AgentError(
            sessionId = obj.reqStr("sessionId"),
            message = obj.reqStr("message"),
        )
        "host/workspace-changed" -> DshHostFrame.WorkspaceChanged(
            workspace = (obj["workspace"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
        "host/workspace-removed" -> DshHostFrame.WorkspaceRemoved(obj.reqStr("workspaceId"))
        "host/workspace-order-changed" -> DshHostFrame.WorkspaceOrderChanged(stringList(obj["workspaceIds"]))
        "host/archived-sessions-changed" -> DshHostFrame.ArchivedSessionsChanged(stringList(obj["archivedSessionIds"]))
        "host/hidden-workspaces-changed" -> DshHostFrame.HiddenWorkspacesChanged(stringList(obj["hiddenWorkspaceIds"]))
        "host/remote-event" -> DshHostFrame.RemoteEvent(
            event = obj.reqStr("event"),
            args = obj["args"] as? JsonArray ?: JsonArray(emptyList()),
        )
        "stream/error" -> DshHostFrame.StreamError(parseRpcError(obj["error"]))
        else -> DshHostFrame.Unknown(type, obj)
    }
}

private fun parseSessionEvent(element: JsonElement?): DshSessionEvent {
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

private fun parseRpcError(element: JsonElement?): DshRpcError {
    val obj = element as? JsonObject
    return DshRpcError(
        code = obj?.reqStr("code").orEmpty().ifBlank { "internal" },
        message = obj?.reqStr("message").orEmpty(),
        details = obj?.get("details") ?: JsonObject(emptyMap()),
    )
}

private fun stringList(element: JsonElement?): List<String> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
