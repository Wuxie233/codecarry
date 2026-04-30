package dev.minios.ocremote.domain.model

enum class McpRuntimeState {
    CONNECTED,
    DISABLED,
    FAILED,
    NEEDS_AUTH,
    NEEDS_CLIENT_REGISTRATION,
    UNKNOWN,
}

data class McpRuntimeStatus(
    val name: String,
    val state: McpRuntimeState,
    val errorMessage: String? = null,
)

data class McpRuntimeSnapshot(
    val servers: List<McpRuntimeStatus>,
    val supportsRuntimeControl: Boolean,
)

fun parseMcpRuntimeState(raw: String?): McpRuntimeState = when (raw) {
    "connected" -> McpRuntimeState.CONNECTED
    "disabled" -> McpRuntimeState.DISABLED
    "failed" -> McpRuntimeState.FAILED
    "needs_auth", "needsAuth" -> McpRuntimeState.NEEDS_AUTH
    "needs_client_registration", "needsClientRegistration" -> McpRuntimeState.NEEDS_CLIENT_REGISTRATION
    else -> McpRuntimeState.UNKNOWN
}
