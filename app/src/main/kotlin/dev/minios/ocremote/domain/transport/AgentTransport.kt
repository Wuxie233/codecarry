package dev.minios.ocremote.domain.transport

import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.Flow

interface AgentTransport {
    suspend fun listRooms(directory: String? = null, rootsOnly: Boolean = true): List<TransportRoom>

    suspend fun listRoomScopes(): List<TransportRoomScope>

    fun openEventStream(directory: String? = null): Flow<TransportEvent>

    suspend fun getSessionStatuses(directory: String? = null): Map<String, SessionStatus> = emptyMap()

    suspend fun listPendingPermissions(): List<SseEvent.PermissionAsked> = emptyList()

    suspend fun sendMessage(
        roomId: String,
        parts: List<TransportMessagePart>,
        model: TransportModelSelection? = null,
        agent: String? = null,
        variant: String? = null,
        directory: String? = null,
    )

    suspend fun sendCommand(
        roomId: String,
        command: String,
        arguments: String = "",
        directory: String? = null,
    ): Boolean

    suspend fun sendShellCommand(
        roomId: String,
        command: String,
        agent: String,
        model: TransportModelSelection? = null,
        directory: String? = null,
    ): Boolean

    suspend fun replyToPermission(
        requestId: String,
        reply: String,
        message: String? = null,
        directory: String? = null,
    ): Boolean
}

sealed interface TransportEvent {
    data class OpenCode(val event: SseEvent) : TransportEvent
    data class Pi(val event: PiTransportEvent) : TransportEvent
}

sealed interface TransportRoom {
    val id: String
    val directory: String
    val parentId: String?

    data class OpenCode(val session: Session) : TransportRoom {
        override val id: String = session.id
        override val directory: String = session.directory
        override val parentId: String? = session.parentId
    }

    data class Pi(val room: PiRoundtableRoom) : TransportRoom {
        override val id: String = room.id
        override val directory: String = room.directory.orEmpty()
        override val parentId: String? = null
    }
}

sealed interface TransportRoomScope {
    val directory: String
    val displayName: String

    data class OpenCode(val project: Project) : TransportRoomScope {
        override val directory: String = project.worktree
        override val displayName: String = project.displayName
    }
}

data class TransportMessagePart(
    val type: String,
    val text: String? = null,
    val path: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
)

data class TransportModelSelection(
    val providerId: String,
    val modelId: String,
)
