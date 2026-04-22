package dev.minios.ocremote.domain.model

sealed interface McpConfigLoadState {
    data class Loaded(val config: McpConfig) : McpConfigLoadState
    data class Empty(val config: McpConfig) : McpConfigLoadState
    data class Error(
        val filePath: String?,
        val message: String,
        val cause: Throwable? = null,
    ) : McpConfigLoadState
    data class NotFound(val checkedPaths: List<String>) : McpConfigLoadState
}

data class McpConfig(
    val filePath: String,
    val rawJson: String,
    val servers: Map<String, McpServer>
)

data class McpServer(
    val name: String,
    val type: String?,
    val command: String?,
    val args: List<String> = emptyList(),
    val enabled: Boolean = true
)
