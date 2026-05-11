package dev.minios.ocremote.domain.model

enum class McpSource { Runtime, File }

sealed interface McpConfigLoadState {
    data class Loaded(
        val config: McpConfig,
        val source: McpSource = McpSource.File,
    ) : McpConfigLoadState

    data class Empty(
        val config: McpConfig,
        val source: McpSource = McpSource.File,
    ) : McpConfigLoadState

    data class Error(
        val filePath: String?,
        val message: String,
        val cause: Throwable? = null,
    ) : McpConfigLoadState

    data class NotFound(val checkedPaths: List<String>) : McpConfigLoadState

    /**
     * The OpenCode server's `GET /mcp` endpoint is not available (older build
     * returning 404/405/501). The repository will then attempt file fallback
     * and may return a different state. RuntimeUnavailable surfaces only when
     * file fallback has also been exhausted and produced no usable result, so
     * the UI can tell the user runtime status is missing AND fallback was
     * empty/missing.
     */
    data class RuntimeUnavailable(
        val fallback: McpConfigLoadState,
    ) : McpConfigLoadState
}

data class McpConfig(
    val filePath: String,
    val rawJson: String,
    val servers: Map<String, McpServer>,
)

data class McpServer(
    val name: String,
    val type: String?,
    val command: String?,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val enabled: Boolean = true,
)
