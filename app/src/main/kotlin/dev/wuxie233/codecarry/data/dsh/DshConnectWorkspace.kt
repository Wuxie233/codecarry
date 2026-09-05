package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DshConnectWorkspaceResult(
    val sessionId: String,
    val directory: String,
    val reused: Boolean,
    val workspace: DshWorkspaceView? = null,
)

fun DshWorkspaceView.toJsonObject() = buildJsonObject {
    put("workspaceId", workspaceId)
    put("path", path)
    put("title", title)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("sessionIds", buildJsonArray {
        sessionIds.forEach { add(JsonPrimitive(it)) }
    })
}

fun DshEventState.withWorkspace(view: DshWorkspaceView): DshEventState {
    val id = view.workspaceId
    return copy(
        workspaces = workspaces + (id to view.toJsonObject()),
        workspaceOrder = if (id in workspaceOrder) workspaceOrder else workspaceOrder + id,
    )
}

/**
 * Web `connectWorkspace` reuse rule: the session must already belong to this
 * workspace, share its canonical cwd, still be blank, and not be archived.
 */
fun reusableBlankSessionId(
    state: DshEventState,
    workspace: DshWorkspaceView,
): String? {
    val archived = state.archivedSessionIds
    val path = workspace.path
    return workspace.sessionIds.firstOrNull { sessionId ->
        if (sessionId in archived) return@firstOrNull false
        val snapshot = state.sessions[sessionId] ?: return@firstOrNull false
        snapshot.blank &&
            !snapshot.running &&
            snapshot.queue.isEmpty() &&
            snapshot.origin != "subagent" &&
            snapshot.parentSessionId == null &&
            snapshot.cwd == path
    }
}

fun directoryParentPath(
    path: String,
    crumbs: List<DshDirectoryEntry> = emptyList(),
): String? {
    crumbs.getOrNull(crumbs.lastIndex - 1)?.path?.let { return it }
    val trimmed = path.trimEnd('/')
    return when {
        trimmed.isEmpty() || trimmed == "/" -> null
        !trimmed.contains('/') -> "/"
        else -> trimmed.substringBeforeLast('/').ifBlank { "/" }
    }
}

fun visibleDirectoryEntries(listing: DshDirectoryListing): List<DshDirectoryEntry> =
    listing.entries.filterNot { it.hidden }

suspend fun connectDshConversation(
    client: DshApiClient,
    connection: DshConnection,
    state: DshEventState,
    path: String?,
    noRepoDirectory: String,
    agentPreset: String? = null,
): DshConnectWorkspaceResult {
    if (path.isNullOrBlank()) {
        val created = client.sessionCreate(connection, agentPreset = agentPreset)
        return DshConnectWorkspaceResult(
            sessionId = created.sessionId,
            directory = noRepoDirectory,
            reused = false,
        )
    }
    val registered = client.workspaceCreate(connection, path)
    val workspace = registered.workspace
    val reusable = reusableBlankSessionId(state.withWorkspace(workspace), workspace)
    if (reusable != null && (agentPreset != null || state.sessions[reusable]?.agentPreset == null)) {
        if (agentPreset != null && state.sessions[reusable]?.agentPreset != agentPreset) {
            client.agentPresetSelect(connection, reusable, agentPreset)
        }
        return DshConnectWorkspaceResult(
            sessionId = reusable,
            directory = workspace.path,
            reused = true,
            workspace = workspace,
        )
    }
    val created = client.sessionCreate(connection, workspaceId = workspace.workspaceId, agentPreset = agentPreset)
    return DshConnectWorkspaceResult(
        sessionId = created.sessionId,
        directory = workspace.path,
        reused = false,
        workspace = workspace,
    )
}
