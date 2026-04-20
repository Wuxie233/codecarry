package dev.minios.ocremote.domain.model

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
