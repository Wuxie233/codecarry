package dev.minios.ocremote.data.transport

import dev.minios.ocremote.data.api.ModelSelection
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.data.api.toPermissionAsked
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.transport.AgentTransport
import dev.minios.ocremote.domain.transport.TransportEvent
import dev.minios.ocremote.domain.transport.TransportMessagePart
import dev.minios.ocremote.domain.transport.TransportModelSelection
import dev.minios.ocremote.domain.transport.TransportRoom
import dev.minios.ocremote.domain.transport.TransportRoomScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OpenCodeTransport(
    server: ServerConfig,
    private val api: OpenCodeApi,
    private val sseClient: SseClient,
) : AgentTransport {
    private val conn = ServerConnection.from(server.url, server.username, server.password)

    override suspend fun listRooms(directory: String?, rootsOnly: Boolean): List<TransportRoom> =
        api.listSessions(conn, directory = directory, rootsOnly = rootsOnly)
            .map { session -> TransportRoom.OpenCode(session) }

    override suspend fun listRoomScopes(): List<TransportRoomScope> =
        api.listProjects(conn).map { project -> TransportRoomScope.OpenCode(project) }

    override fun openEventStream(directory: String?): Flow<TransportEvent> =
        sseClient.connectToGlobalEvents(conn, directory = directory)
            .map { event -> TransportEvent.OpenCode(event) }

    override suspend fun getSessionStatuses(directory: String?): Map<String, SessionStatus> =
        api.getSessionStatuses(conn, directory = directory)

    override suspend fun listPendingPermissions(): List<SseEvent.PermissionAsked> =
        api.listPendingPermissions(conn).map { it.toPermissionAsked() }

    override suspend fun sendMessage(
        roomId: String,
        parts: List<TransportMessagePart>,
        model: TransportModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?,
    ) {
        api.promptAsync(
            conn = conn,
            sessionId = roomId,
            parts = parts.map { part -> part.toPromptPart() },
            model = model?.let { ModelSelection(providerId = it.providerId, modelId = it.modelId) },
            agent = agent,
            variant = variant,
            directory = directory,
        )
    }

    override suspend fun sendCommand(
        roomId: String,
        command: String,
        arguments: String,
        directory: String?,
    ): Boolean = api.executeCommand(
        conn = conn,
        sessionId = roomId,
        command = command,
        arguments = arguments,
        directory = directory,
    )

    override suspend fun sendShellCommand(
        roomId: String,
        command: String,
        agent: String,
        model: TransportModelSelection?,
        directory: String?,
    ): Boolean = api.runShellCommand(
        conn = conn,
        sessionId = roomId,
        command = command,
        agent = agent,
        model = model?.let { ModelSelection(providerId = it.providerId, modelId = it.modelId) },
        directory = directory,
    )

    override suspend fun replyToPermission(
        requestId: String,
        reply: String,
        message: String?,
        directory: String?,
    ): Boolean = api.replyToPermission(
        conn = conn,
        requestId = requestId,
        reply = reply,
        message = message,
        directory = directory,
    )

    private fun TransportMessagePart.toPromptPart(): PromptPart = PromptPart(
        type = type,
        text = text,
        path = path,
        mime = mime,
        url = url,
        filename = filename,
    )
}
