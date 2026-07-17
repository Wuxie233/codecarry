package dev.minios.ocremote.data.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class CodexInitializeResult(
    val userAgent: String,
    val codexHome: String? = null,
    val platformFamily: String? = null,
    val platformOs: String? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): CodexInitializeResult {
            val objectValue = value.objectOrEmpty()
            return CodexInitializeResult(
                userAgent = objectValue.string("userAgent").orEmpty(),
                codexHome = objectValue.string("codexHome"),
                platformFamily = objectValue.string("platformFamily"),
                platformOs = objectValue.string("platformOs"),
                extra = objectValue.without("userAgent", "codexHome", "platformFamily", "platformOs"),
                raw = objectValue,
            )
        }
    }
}

data class CodexThreadListPage(
    val threads: List<CodexThread>,
    val nextCursor: String? = null,
    val backwardsCursor: String? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): CodexThreadListPage {
            val objectValue = value.objectOrEmpty()
            val threads = (objectValue["data"] as? JsonArray)
                .orEmpty()
                .mapNotNull { element -> (element as? JsonObject)?.let(CodexThread::fromJson) }
            return CodexThreadListPage(
                threads = threads,
                nextCursor = objectValue.string("nextCursor"),
                backwardsCursor = objectValue.string("backwardsCursor"),
                extra = objectValue.without("data", "nextCursor", "backwardsCursor"),
                raw = objectValue,
            )
        }
    }
}

data class CodexThreadSession(
    val thread: CodexThread,
    val cwd: String? = null,
    val model: String? = null,
    val modelProvider: String? = null,
    val reasoningEffort: String? = null,
    val approvalPolicy: JsonElement? = null,
    val sandbox: JsonElement? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): CodexThreadSession {
            val objectValue = value.objectOrEmpty()
            return CodexThreadSession(
                thread = CodexThread.fromJson(objectValue["thread"].objectOrEmpty()),
                cwd = objectValue.string("cwd"),
                model = objectValue.string("model"),
                modelProvider = objectValue.string("modelProvider"),
                reasoningEffort = objectValue.string("reasoningEffort"),
                approvalPolicy = objectValue["approvalPolicy"].nonNull(),
                sandbox = objectValue["sandbox"].nonNull(),
                extra = objectValue.without(
                    "thread",
                    "cwd",
                    "model",
                    "modelProvider",
                    "reasoningEffort",
                    "approvalPolicy",
                    "sandbox",
                ),
                raw = objectValue,
            )
        }
    }
}

data class CodexThread(
    val id: String,
    val sessionId: String? = null,
    val name: String? = null,
    val preview: String = "",
    val cwd: String? = null,
    val modelProvider: String? = null,
    val cliVersion: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val recencyAt: Long? = null,
    val ephemeral: Boolean = false,
    val status: CodexThreadStatus = CodexThreadStatus(),
    val turns: List<CodexTurn> = emptyList(),
    val source: JsonElement? = null,
    val parentThreadId: String? = null,
    val forkedFromId: String? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexThread {
            val turns = (value["turns"] as? JsonArray)
                .orEmpty()
                .mapNotNull { element -> (element as? JsonObject)?.let(CodexTurn::fromJson) }
            return CodexThread(
                id = value.string("id").orEmpty(),
                sessionId = value.string("sessionId"),
                name = value.string("name"),
                preview = value.string("preview").orEmpty(),
                cwd = value.string("cwd"),
                modelProvider = value.string("modelProvider"),
                cliVersion = value.string("cliVersion"),
                createdAt = value.long("createdAt"),
                updatedAt = value.long("updatedAt"),
                recencyAt = value.long("recencyAt"),
                ephemeral = value.boolean("ephemeral") ?: false,
                status = CodexThreadStatus.fromJson(value["status"]),
                turns = turns,
                source = value["source"].nonNull(),
                parentThreadId = value.string("parentThreadId"),
                forkedFromId = value.string("forkedFromId"),
                extra = value.without(
                    "id",
                    "sessionId",
                    "name",
                    "preview",
                    "cwd",
                    "modelProvider",
                    "cliVersion",
                    "createdAt",
                    "updatedAt",
                    "recencyAt",
                    "ephemeral",
                    "status",
                    "turns",
                    "source",
                    "parentThreadId",
                    "forkedFromId",
                ),
                raw = value,
            )
        }
    }
}

data class CodexThreadStatus(
    val type: String = "unknown",
    val activeFlags: List<String> = emptyList(),
    val raw: JsonElement = JsonNull,
) {
    companion object {
        fun fromJson(value: JsonElement?): CodexThreadStatus {
            val objectValue = value as? JsonObject
            val type = objectValue?.string("type")
                ?: (value as? JsonPrimitive)?.contentOrNull
                ?: "unknown"
            val activeFlags = (objectValue?.get("activeFlags") as? JsonArray)
                .orEmpty()
                .mapNotNull { element ->
                    (element as? JsonPrimitive)?.contentOrNull
                        ?: (element as? JsonObject)?.string("type")
                }
            return CodexThreadStatus(type = type, activeFlags = activeFlags, raw = value ?: JsonNull)
        }
    }
}

data class CodexTurn(
    val id: String,
    val status: String = "unknown",
    val items: List<CodexThreadItem> = emptyList(),
    val itemsView: String = "full",
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val durationMs: Long? = null,
    val error: JsonElement? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexTurn {
            val items = (value["items"] as? JsonArray)
                .orEmpty()
                .mapNotNull { element -> (element as? JsonObject)?.let(CodexThreadItem::fromJson) }
            return CodexTurn(
                id = value.string("id").orEmpty(),
                status = value.string("status") ?: "unknown",
                items = items,
                itemsView = value.string("itemsView") ?: "full",
                startedAt = value.long("startedAt"),
                completedAt = value.long("completedAt"),
                durationMs = value.long("durationMs"),
                error = value["error"].nonNull(),
                extra = value.without(
                    "id",
                    "status",
                    "items",
                    "itemsView",
                    "startedAt",
                    "completedAt",
                    "durationMs",
                    "error",
                ),
                raw = value,
            )
        }
    }
}

data class CodexThreadItem(
    val id: String? = null,
    val clientId: String? = null,
    val type: String = "unknown",
    val text: String? = null,
    val status: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val output: String? = null,
    val reasoningSummary: List<String> = emptyList(),
    val reasoningContent: List<String> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexThreadItem {
            val type = value.string("type") ?: "unknown"
            val text = value.string("text") ?: when (type) {
                "userMessage" -> (value["content"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it.objectOrNull()?.string("text") }
                    .joinToString("\n")
                    .ifBlank { null }
                "reasoning" -> (value["summary"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .joinToString("\n")
                    .ifBlank { null }
                else -> null
            }
            return CodexThreadItem(
                id = value.string("id"),
                clientId = value.string("clientId"),
                type = type,
                text = text,
                status = value.string("status"),
                command = value.string("command"),
                cwd = value.string("cwd"),
                output = value.string("aggregatedOutput") ?: value.string("output"),
                reasoningSummary = value.stringList("summary"),
                reasoningContent = value.stringList("content"),
                extra = value.without(
                    "id",
                    "clientId",
                    "type",
                    "text",
                    "status",
                    "command",
                    "cwd",
                    "aggregatedOutput",
                    "output",
                    "summary",
                    "content",
                ),
                raw = value,
            )
        }
    }
}

data class CodexGoal(
    val threadId: String,
    val objective: String,
    val status: String,
    val tokenBudget: Long? = null,
    val tokensUsed: Long = 0,
    val timeUsedSeconds: Long = 0,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexGoal = CodexGoal(
            threadId = value.string("threadId").orEmpty(),
            objective = value.string("objective").orEmpty(),
            status = value.string("status") ?: "unknown",
            tokenBudget = value.long("tokenBudget"),
            tokensUsed = value.long("tokensUsed") ?: 0,
            timeUsedSeconds = value.long("timeUsedSeconds") ?: 0,
            createdAt = value.long("createdAt"),
            updatedAt = value.long("updatedAt"),
            extra = value.without(
                "threadId",
                "objective",
                "status",
                "tokenBudget",
                "tokensUsed",
                "timeUsedSeconds",
                "createdAt",
                "updatedAt",
            ),
            raw = value,
        )
    }
}

data class CodexModelListPage(
    val models: List<CodexModel>,
    val nextCursor: String? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): CodexModelListPage {
            val objectValue = value.objectOrEmpty()
            return CodexModelListPage(
                models = (objectValue["data"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { element -> (element as? JsonObject)?.let(CodexModel::fromJson) },
                nextCursor = objectValue.string("nextCursor"),
                extra = objectValue.without("data", "nextCursor"),
                raw = objectValue,
            )
        }
    }
}

data class CodexModel(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String = "",
    val isDefault: Boolean = false,
    val hidden: Boolean = false,
    val defaultReasoningEffort: String? = null,
    val supportedReasoningEfforts: List<CodexReasoningEffortOption> = emptyList(),
    val inputModalities: List<String> = emptyList(),
    val supportsPersonality: Boolean = false,
    val defaultServiceTier: String? = null,
    val serviceTiers: List<CodexModelServiceTier> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexModel = CodexModel(
            id = value.string("id").orEmpty(),
            model = value.string("model").orEmpty(),
            displayName = value.string("displayName").orEmpty(),
            description = value.string("description").orEmpty(),
            isDefault = value.boolean("isDefault") ?: false,
            hidden = value.boolean("hidden") ?: false,
            defaultReasoningEffort = value.string("defaultReasoningEffort"),
            supportedReasoningEfforts = (value["supportedReasoningEfforts"] as? JsonArray)
                .orEmpty()
                .mapNotNull { element ->
                    (element as? JsonObject)?.let(CodexReasoningEffortOption::fromJson)
                },
            inputModalities = value.stringList("inputModalities"),
            supportsPersonality = value.boolean("supportsPersonality") ?: false,
            defaultServiceTier = value.string("defaultServiceTier"),
            serviceTiers = (value["serviceTiers"] as? JsonArray)
                .orEmpty()
                .mapNotNull { element ->
                    (element as? JsonObject)?.let(CodexModelServiceTier::fromJson)
                },
            extra = value.without(
                "id",
                "model",
                "displayName",
                "description",
                "isDefault",
                "hidden",
                "defaultReasoningEffort",
                "supportedReasoningEfforts",
                "inputModalities",
                "supportsPersonality",
                "defaultServiceTier",
                "serviceTiers",
            ),
            raw = value,
        )
    }
}

data class CodexReasoningEffortOption(
    val reasoningEffort: String,
    val description: String = "",
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexReasoningEffortOption = CodexReasoningEffortOption(
            reasoningEffort = value.string("reasoningEffort").orEmpty(),
            description = value.string("description").orEmpty(),
            extra = value.without("reasoningEffort", "description"),
        )
    }
}

data class CodexModelServiceTier(
    val id: String,
    val name: String,
    val description: String = "",
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexModelServiceTier = CodexModelServiceTier(
            id = value.string("id").orEmpty(),
            name = value.string("name").orEmpty(),
            description = value.string("description").orEmpty(),
            extra = value.without("id", "name", "description"),
        )
    }
}

data class CodexNotification(
    val method: String,
    val params: JsonObject,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val threadId: String?
        get() = params.string("threadId") ?: params.objectOrNull("thread")?.string("id")

    val turnId: String?
        get() = params.string("turnId") ?: params.objectOrNull("turn")?.string("id")

    val item: CodexThreadItem?
        get() = params.objectOrNull("item")?.let(CodexThreadItem::fromJson)

    val thread: CodexThread?
        get() = params.objectOrNull("thread")?.let(CodexThread::fromJson)

    val turn: CodexTurn?
        get() = params.objectOrNull("turn")?.let(CodexTurn::fromJson)

    val goal: CodexGoal?
        get() = params.objectOrNull("goal")?.let(CodexGoal::fromJson)

    val itemId: String?
        get() = params.string("itemId")

    val delta: String?
        get() = params.string("delta")

    companion object {
        fun fromJson(value: JsonObject): CodexNotification = CodexNotification(
            method = value.string("method").orEmpty(),
            params = value["params"].objectOrEmpty(),
            extra = value.without("jsonrpc", "method", "params"),
            raw = value,
        )
    }
}

enum class CodexApprovalKind {
    COMMAND_EXECUTION,
    FILE_CHANGE,
    PERMISSIONS,
    UNKNOWN,
}

data class CodexApprovalRequest(
    val requestId: JsonPrimitive,
    internal val connectionGeneration: Long = 0,
    val kind: CodexApprovalKind,
    val method: String,
    val threadId: String? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val approvalId: String? = null,
    val reason: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val startedAtMs: Long? = null,
    val availableDecisions: JsonArray? = null,
    val permissions: JsonElement? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun allowsDecision(decision: String): Boolean {
        if (kind == CodexApprovalKind.PERMISSIONS) {
            return decision == "decline" ||
                decision == "cancel" ||
                decision == "accept" ||
                decision == "acceptForSession"
        }
        return availableDecisions
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.let { decisions -> decision in decisions }
            ?: (decision == "decline" || decision == "cancel")
    }

    companion object {
        internal fun fromRequest(
            requestId: JsonPrimitive,
            connectionGeneration: Long,
            method: String,
            params: JsonObject,
        ): CodexApprovalRequest? {
            val kind = when (method) {
                "item/commandExecution/requestApproval" -> CodexApprovalKind.COMMAND_EXECUTION
                "item/fileChange/requestApproval" -> CodexApprovalKind.FILE_CHANGE
                "item/permissions/requestApproval" -> CodexApprovalKind.PERMISSIONS
                else -> return null
            }
            return CodexApprovalRequest(
                requestId = requestId,
                connectionGeneration = connectionGeneration,
                kind = kind,
                method = method,
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                itemId = params.string("itemId"),
                approvalId = params.string("approvalId"),
                reason = params.string("reason"),
                command = params.string("command"),
                cwd = params.string("cwd"),
                startedAtMs = params.long("startedAtMs"),
                availableDecisions = params["availableDecisions"] as? JsonArray,
                permissions = params["permissions"].nonNull(),
                extra = params.without(
                    "threadId",
                    "turnId",
                    "itemId",
                    "approvalId",
                    "reason",
                    "command",
                    "cwd",
                    "startedAtMs",
                    "availableDecisions",
                    "permissions",
                ),
                raw = params,
            )
        }
    }
}

data class CodexServerRequest(
    val id: JsonPrimitive,
    val method: String,
    val params: JsonObject,
    internal val connectionGeneration: Long = 0,
    val approval: CodexApprovalRequest? = null,
    val userInput: CodexToolUserInputRequest? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject, connectionGeneration: Long = 0): CodexServerRequest? {
            val id = value["id"] as? JsonPrimitive ?: return null
            val method = value.string("method") ?: return null
            val params = value["params"].objectOrEmpty()
            return CodexServerRequest(
                id = id,
                method = method,
                params = params,
                connectionGeneration = connectionGeneration,
                approval = CodexApprovalRequest.fromRequest(id, connectionGeneration, method, params),
                userInput = CodexToolUserInputRequest.fromRequest(id, connectionGeneration, method, params),
                extra = value.without("jsonrpc", "id", "method", "params"),
                raw = value,
            )
        }
    }
}

data class CodexToolUserInputRequest(
    val requestId: JsonPrimitive,
    internal val connectionGeneration: Long = 0,
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val questions: List<CodexToolUserInputQuestion>,
    val autoResolutionMs: Long? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        internal fun fromRequest(
            requestId: JsonPrimitive,
            connectionGeneration: Long,
            method: String,
            params: JsonObject,
        ): CodexToolUserInputRequest? {
            if (method != "item/tool/requestUserInput") return null
            return CodexToolUserInputRequest(
                requestId = requestId,
                connectionGeneration = connectionGeneration,
                threadId = params.string("threadId").orEmpty(),
                turnId = params.string("turnId").orEmpty(),
                itemId = params.string("itemId").orEmpty(),
                questions = (params["questions"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { element ->
                        (element as? JsonObject)?.let(CodexToolUserInputQuestion::fromJson)
                    },
                autoResolutionMs = params.long("autoResolutionMs"),
                extra = params.without(
                    "threadId",
                    "turnId",
                    "itemId",
                    "questions",
                    "autoResolutionMs",
                ),
                raw = params,
            )
        }
    }
}

data class CodexToolUserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<CodexToolUserInputOption> = emptyList(),
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexToolUserInputQuestion = CodexToolUserInputQuestion(
            id = value.string("id").orEmpty(),
            header = value.string("header").orEmpty(),
            question = value.string("question").orEmpty(),
            options = (value["options"] as? JsonArray)
                .orEmpty()
                .mapNotNull { element ->
                    (element as? JsonObject)?.let(CodexToolUserInputOption::fromJson)
                },
            isOther = value.boolean("isOther") ?: false,
            isSecret = value.boolean("isSecret") ?: false,
            extra = value.without("id", "header", "question", "options", "isOther", "isSecret"),
        )
    }
}

data class CodexToolUserInputOption(
    val label: String,
    val description: String,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonObject): CodexToolUserInputOption = CodexToolUserInputOption(
            label = value.string("label").orEmpty(),
            description = value.string("description").orEmpty(),
            extra = value.without("label", "description"),
        )
    }
}

enum class CodexPermissionGrantScope(val wireValue: String) {
    TURN("turn"),
    SESSION("session"),
}

data class CodexPermissionGrant(
    val permissions: JsonObject,
    val scope: CodexPermissionGrantScope = CodexPermissionGrantScope.TURN,
    val strictAutoReview: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("permissions", permissions)
        put("scope", scope.wireValue)
        strictAutoReview?.let { put("strictAutoReview", it) }
    }
}

sealed interface CodexUserInput {
    fun toJson(): JsonObject

    data class Text(val text: String) : CodexUserInput {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "text")
            put("text", text)
        }
    }

    data class Image(val url: String, val detail: String? = null) : CodexUserInput {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "image")
            put("url", url)
            detail?.let { put("detail", it) }
        }
    }

    data class LocalImage(val path: String, val detail: String? = null) : CodexUserInput {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "localImage")
            put("path", path)
            detail?.let { put("detail", it) }
        }
    }

    data class Skill(val name: String, val path: String) : CodexUserInput {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "skill")
            put("name", name)
            put("path", path)
        }
    }

    data class Mention(val name: String, val path: String) : CodexUserInput {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "mention")
            put("name", name)
            put("path", path)
        }
    }

    data class Raw(val value: JsonObject) : CodexUserInput {
        override fun toJson(): JsonObject = value
    }
}

enum class CodexMemoryMode(val wireValue: String) {
    ENABLED("enabled"),
    DISABLED("disabled"),
}

internal fun List<CodexUserInput>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { input -> add(input.toJson()) }
}

internal fun JsonElement?.objectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.nonNull(): JsonElement? = this?.takeUnless { it is JsonNull }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeUnless { this[key] is JsonNull }

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringList(key: String): List<String> = (this[key] as? JsonArray)
    .orEmpty()
    .mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.without(vararg keys: String): JsonObject {
    val excluded = keys.toSet()
    return JsonObject(filterKeys { key -> key !in excluded })
}
