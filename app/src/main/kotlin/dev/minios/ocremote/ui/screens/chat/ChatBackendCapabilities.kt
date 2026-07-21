package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.ServerType

internal data class ChatBackendCapabilities(
    val attachments: Boolean,
    val fileMentions: Boolean,
    val modelAndAgentSelection: Boolean,
    val sessionExtras: Boolean,
    val shellAndTerminal: Boolean,
    val slashCommands: Boolean,
)

internal fun chatBackendCapabilities(serverType: ServerType): ChatBackendCapabilities = when (serverType) {
    ServerType.PI_STACK -> ChatBackendCapabilities(
        attachments = false,
        fileMentions = false,
        modelAndAgentSelection = false,
        sessionExtras = false,
        shellAndTerminal = false,
        slashCommands = false,
    )
    ServerType.PI_ROUNDTABLE -> ChatBackendCapabilities(
        attachments = true,
        fileMentions = false,
        modelAndAgentSelection = false,
        sessionExtras = true,
        shellAndTerminal = true,
        slashCommands = true,
    )
    ServerType.OPENCODE, ServerType.CODEX -> ChatBackendCapabilities(
        attachments = true,
        fileMentions = true,
        modelAndAgentSelection = true,
        sessionExtras = true,
        shellAndTerminal = true,
        slashCommands = true,
    )
}
