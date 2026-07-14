package dev.minios.ocremote.ui.screens.codex

import dev.minios.ocremote.data.codex.CodexApprovalKind
import dev.minios.ocremote.data.codex.CodexServerRequest
import dev.minios.ocremote.data.codex.CodexThread
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class CodexApprovalDetailKind {
    WORKING_DIRECTORY,
    ENVIRONMENT,
    NETWORK_TARGET,
    ADDITIONAL_PERMISSIONS,
    FILE_CHANGE,
    GRANT_ROOT,
    PERMISSIONS,
}

internal data class CodexApprovalDetail(
    val kind: CodexApprovalDetailKind,
    val value: String,
)

internal data class CodexApprovalPresentation(
    val decisions: List<String>,
    val details: List<CodexApprovalDetail>,
    val canApprove: Boolean,
)

private val supportedApprovalDecisions = setOf(
    "decline",
    "cancel",
    "accept",
    "acceptForSession",
)

internal fun codexApprovalPresentation(
    request: CodexServerRequest,
    thread: CodexThread?,
): CodexApprovalPresentation {
    val approval = request.approval
        ?: return CodexApprovalPresentation(emptyList(), emptyList(), canApprove = false)
    val details = buildList {
        approval.cwd?.takeIf(String::isNotBlank)?.let {
            add(CodexApprovalDetail(CodexApprovalDetailKind.WORKING_DIRECTORY, it))
        }
        request.params.stringValue("environmentId")?.takeIf(String::isNotBlank)?.let {
            add(CodexApprovalDetail(CodexApprovalDetailKind.ENVIRONMENT, it))
        }
        request.params.objectValue("networkApprovalContext")?.let { context ->
            val host = context.stringValue("host")
            val protocol = context.stringValue("protocol")
            if (!host.isNullOrBlank()) {
                add(
                    CodexApprovalDetail(
                        CodexApprovalDetailKind.NETWORK_TARGET,
                        listOfNotNull(protocol?.takeIf(String::isNotBlank), host).joinToString("://"),
                    ),
                )
            }
        }
        request.params["additionalPermissions"]
            ?.takeUnless { it.toString() == "null" }
            ?.let { add(CodexApprovalDetail(CodexApprovalDetailKind.ADDITIONAL_PERMISSIONS, it.toString())) }
        request.params.stringValue("grantRoot")?.takeIf(String::isNotBlank)?.let {
            add(CodexApprovalDetail(CodexApprovalDetailKind.GRANT_ROOT, it))
        }
        if (approval.kind == CodexApprovalKind.FILE_CHANGE) {
            findApprovalItem(thread, approval.turnId, approval.itemId)
                ?.raw
                ?.get("changes")
                ?.let { it as? JsonArray }
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .forEach { change ->
                    val path = change.stringValue("path") ?: return@forEach
                    val kind = change.objectValue("kind")?.stringValue("type")
                        ?: change.stringValue("kind")
                        ?: "update"
                    add(CodexApprovalDetail(CodexApprovalDetailKind.FILE_CHANGE, "$kind: $path"))
                }
        }
        if (approval.kind == CodexApprovalKind.PERMISSIONS) {
            approval.permissions?.let {
                add(CodexApprovalDetail(CodexApprovalDetailKind.PERMISSIONS, it.toString()))
            }
        }
    }
    val decisions = if (approval.kind == CodexApprovalKind.PERMISSIONS) {
        listOf("decline", "accept", "acceptForSession")
    } else {
        approval.availableDecisions
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it in supportedApprovalDecisions }
            ?.takeIf(List<String>::isNotEmpty)
            ?: listOf("decline", "cancel")
    }
    val canApprove = when (approval.kind) {
        CodexApprovalKind.COMMAND_EXECUTION ->
            !approval.command.isNullOrBlank() || details.any {
                it.kind == CodexApprovalDetailKind.NETWORK_TARGET ||
                    it.kind == CodexApprovalDetailKind.ADDITIONAL_PERMISSIONS
            }
        CodexApprovalKind.FILE_CHANGE -> details.any { it.kind == CodexApprovalDetailKind.FILE_CHANGE }
        CodexApprovalKind.PERMISSIONS -> approval.permissions != null
        CodexApprovalKind.UNKNOWN -> false
    }
    return CodexApprovalPresentation(decisions, details, canApprove)
}

internal fun isCodexApprovalDecision(decision: String): Boolean =
    decision == "accept" || decision == "acceptForSession"

private fun findApprovalItem(thread: CodexThread?, turnId: String?, itemId: String?) =
    thread?.turns
        ?.firstOrNull { turn -> turn.id == turnId }
        ?.items
        ?.firstOrNull { item -> item.id == itemId }

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
