package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.ServerType

internal data class ChatBackendCapabilities(
    val attachments: Boolean,
    val fileMentions: Boolean,
    val modelAndAgentSelection: Boolean,
    val sessionExtras: Boolean,
    val shellAndTerminal: Boolean,
    val slashCommands: Boolean,
)

internal fun chatBackendCapabilities(
    serverType: ServerType,
): ChatBackendCapabilities = when (serverType) {
    ServerType.OPENCODE -> ChatBackendCapabilities(
        attachments = true,
        fileMentions = true,
        modelAndAgentSelection = true,
        sessionExtras = true,
        shellAndTerminal = true,
        slashCommands = true,
    )
    ServerType.CODEX -> ChatBackendCapabilities(
        attachments = false, fileMentions = false, modelAndAgentSelection = false,
        sessionExtras = false, shellAndTerminal = false, slashCommands = false,
    )
    ServerType.DSH -> ChatBackendCapabilities(
        attachments = true,
        fileMentions = true,
        modelAndAgentSelection = true,
        sessionExtras = true,
        shellAndTerminal = false,
        slashCommands = true,
    )
}
