package dev.wuxie233.codecarry.service

import dev.wuxie233.codecarry.data.codex.CodexApprovalKind
import dev.wuxie233.codecarry.data.codex.CodexClientConnectionState
import dev.wuxie233.codecarry.data.codex.CodexManagedConnection
import dev.wuxie233.codecarry.data.codex.CodexNotification
import dev.wuxie233.codecarry.data.codex.CodexServerRequest
import dev.wuxie233.codecarry.data.codex.CodexThreadKey
import dev.wuxie233.codecarry.data.codex.requestKey
import dev.wuxie233.codecarry.domain.model.ServerConfig
import kotlinx.coroutines.Job

internal data class CodexOwnedConnection(
    val config: ServerConfig,
    val generation: Long,
    val connectJob: Job,
)

internal class CodexOwnershipRegistry {
    private val lock = Any()
    private val owners = mutableMapOf<String, CodexOwnedConnection>()
    private var generation = 0L

    fun register(
        server: ServerConfig,
        createJob: (generation: Long) -> Job,
    ): CodexOwnedConnection? = synchronized(lock) {
        if (server.id in owners) return@synchronized null
        val nextGeneration = ++generation
        CodexOwnedConnection(server, nextGeneration, createJob(nextGeneration)).also { owner ->
            owners[server.id] = owner
        }
    }

    fun remove(serverId: String): CodexOwnedConnection? = synchronized(lock) {
        owners.remove(serverId)
    }

    fun clear(): List<CodexOwnedConnection> = synchronized(lock) {
        owners.values.toList().also { owners.clear() }
    }

    fun snapshot(): Map<String, CodexOwnedConnection> = synchronized(lock) { owners.toMap() }

    fun current(serverId: String): CodexOwnedConnection? = synchronized(lock) { owners[serverId] }

    fun isCurrent(serverId: String, ownerGeneration: Long): Boolean = synchronized(lock) {
        owners[serverId]?.generation == ownerGeneration
    }

    fun isEmpty(): Boolean = synchronized(lock) { owners.isEmpty() }
}

internal data class CodexServiceConnectionState(
    val connected: Boolean,
    val connecting: Boolean,
    val error: String? = null,
)

internal fun codexServiceConnectionState(
    managed: CodexManagedConnection?,
): CodexServiceConnectionState = when (val state = managed?.state) {
    is CodexClientConnectionState.Connected -> CodexServiceConnectionState(
        connected = true,
        connecting = false,
    )
    is CodexClientConnectionState.Failed -> CodexServiceConnectionState(
        connected = false,
        connecting = true,
        error = state.error.message ?: "Codex connection failed",
    )
    else -> CodexServiceConnectionState(
        connected = false,
        connecting = true,
    )
}

internal fun mergeCodexConnectionErrors(
    current: Map<String, String>,
    codexServerIds: Set<String>,
    codexErrors: Map<String, String>,
): Map<String, String> = current.filterKeys { it !in codexServerIds } + codexErrors

internal fun shouldCancelServerSummary(
    activeChildren: Collection<Pair<Int, String?>>,
    serverId: String,
    dismissedId: Int,
): Boolean {
    val group = SessionNotificationIdentity.serverGroup(serverId)
    return activeChildren.none { (id, childGroup) -> id != dismissedId && childGroup == group }
}

internal enum class CodexTurnNotificationDecision {
    IGNORE,
    SUPPRESS_ACTIVE,
    POST,
}

internal fun codexTurnNotificationDecision(
    serverId: String,
    notification: CodexNotification,
    activeThreads: Set<CodexThreadKey>,
    notificationsEnabled: Boolean,
): CodexTurnNotificationDecision {
    if (notification.method != "turn/completed" || !notificationsEnabled) {
        return CodexTurnNotificationDecision.IGNORE
    }
    val threadId = notification.threadId ?: return CodexTurnNotificationDecision.IGNORE
    val turn = notification.turn ?: return CodexTurnNotificationDecision.IGNORE
    if (turn.status != "completed" || turn.error != null) return CodexTurnNotificationDecision.IGNORE
    return if (CodexThreadKey(serverId, threadId) in activeThreads) {
        CodexTurnNotificationDecision.SUPPRESS_ACTIVE
    } else {
        CodexTurnNotificationDecision.POST
    }
}

internal class BoundedNotificationKeys(
    private val capacity: Int,
) {
    private val keys = linkedSetOf<String>()

    fun add(key: String): Boolean = synchronized(keys) {
        if (!keys.add(key)) return@synchronized false
        while (keys.size > capacity) keys.remove(keys.first())
        true
    }

    internal fun size(): Int = synchronized(keys) { keys.size }
}

internal enum class CodexRequestNotificationKind {
    APPROVAL,
    USER_INPUT,
    MCP_ELICITATION,
}

internal fun CodexServerRequest.notificationKind(): CodexRequestNotificationKind? = when {
    approval != null -> CodexRequestNotificationKind.APPROVAL
    userInput != null -> CodexRequestNotificationKind.USER_INPUT
    method == "mcpServer/elicitation/request" -> CodexRequestNotificationKind.MCP_ELICITATION
    else -> null
}

internal fun CodexServerRequest.notificationTitle(): String = when {
    approval?.kind == CodexApprovalKind.COMMAND_EXECUTION -> "Command approval required"
    approval?.kind == CodexApprovalKind.FILE_CHANGE -> "File approval required"
    approval?.kind == CodexApprovalKind.PERMISSIONS -> "Permission required"
    userInput != null -> "Codex needs input"
    method == "mcpServer/elicitation/request" -> "MCP needs input"
    else -> "Codex request"
}

internal object CodexNotificationIdentity {
    fun responseReadyId(serverId: String, threadId: String): Int =
        ("codex_response:$serverId:$threadId").hashCode()

    fun requestId(serverId: String, request: CodexServerRequest): Int =
        ("codex_request:$serverId:${request.id.requestKey()}").hashCode()
}
