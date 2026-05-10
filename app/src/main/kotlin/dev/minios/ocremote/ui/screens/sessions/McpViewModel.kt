package dev.minios.ocremote.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.McpToggleException
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.domain.model.McpRuntimeSnapshot
import dev.minios.ocremote.domain.model.McpRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class McpUiState {
    data object Loading : McpUiState()

    /** Successful runtime load. Per-server pending names tracks in-flight toggles. */
    data class Runtime(
        val snapshot: McpRuntimeSnapshot,
        val pendingNames: Set<String> = emptySet(),
        val rowErrors: Map<String, String> = emptyMap(),
        val sheetError: String? = null,
    ) : McpUiState()

    /** Server lacks runtime control endpoints; fallback to read-only file config rows/diagnostics. */
    data class FallbackReadOnly(
        val snapshot: McpRuntimeSnapshot,
    ) : McpUiState()

    data class LoadError(val message: String) : McpUiState()

    data object Empty : McpUiState()
}

internal class McpRuntimeController(
    private val scope: CoroutineScope,
    private val loadRuntime: suspend (ServerConnection, String) -> Result<McpRuntimeSnapshot>,
    private val toggleRuntime: suspend (ServerConnection, String, String, McpRuntimeSnapshot) -> Result<McpRuntimeSnapshot>,
) {
    private val _state = MutableStateFlow<McpUiState>(McpUiState.Loading)
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    private var conn: ServerConnection? = null
    private var projectDir: String? = null

    fun load(conn: ServerConnection, projectDir: String) {
        this.conn = conn
        this.projectDir = projectDir
        loadInternal()
    }

    fun refresh() = loadInternal()

    private fun loadInternal() {
        val c = conn ?: return
        val p = projectDir ?: return
        _state.value = McpUiState.Loading

        scope.launch {
            loadRuntime(c, p)
                .onSuccess { snapshot ->
                    _state.value = when {
                        !snapshot.supportsRuntimeControl -> McpUiState.FallbackReadOnly(snapshot)
                        snapshot.servers.isEmpty() -> McpUiState.Empty
                        else -> McpUiState.Runtime(snapshot = snapshot)
                    }
                }
                .onFailure { error ->
                    _state.value = McpUiState.LoadError(
                        sanitizeForUi(error.message ?: "Failed to load MCP runtime")
                    )
                }
        }
    }

    /**
     * Web-parity toggle:
     * - CONNECTED → disconnect
     * - DISABLED / FAILED / UNKNOWN → connect
     * - NEEDS_AUTH / NEEDS_CLIENT_REGISTRATION → set row error, do NOT call API
     */
    fun toggle(name: String) {
        val c = conn ?: return
        val p = projectDir ?: return
        val current = (_state.value as? McpUiState.Runtime) ?: return
        val target = current.snapshot.servers.firstOrNull { it.name == name } ?: return

        if (target.state == McpRuntimeState.NEEDS_AUTH ||
            target.state == McpRuntimeState.NEEDS_CLIENT_REGISTRATION
        ) {
            _state.value = current.copy(
                rowErrors = current.rowErrors + (name to authRequiredHint(target.state)),
            )
            return
        }
        if (name in current.pendingNames) return

        _state.value = current.copy(
            pendingNames = current.pendingNames + name,
            rowErrors = current.rowErrors - name,
            sheetError = null,
        )

        scope.launch {
            toggleRuntime(c, p, name, current.snapshot)
                .onSuccess { refreshed ->
                    val nextState = (state.value as? McpUiState.Runtime) ?: McpUiState.Runtime(refreshed)
                    _state.value = McpUiState.Runtime(
                        snapshot = refreshed,
                        pendingNames = nextState.pendingNames - name,
                        rowErrors = nextState.rowErrors - name,
                        sheetError = null,
                    )
                }
                .onFailure { error ->
                    val previous = (error as? McpToggleException)?.previous ?: current.snapshot
                    val message = (error as? McpToggleException)?.cause?.message
                        ?: error.message
                        ?: "Toggle failed"
                    val safeMessage = sanitizeForUi(message)
                    val baseline = (state.value as? McpUiState.Runtime) ?: McpUiState.Runtime(previous)
                    _state.value = baseline.copy(
                        snapshot = previous,
                        pendingNames = baseline.pendingNames - name,
                        rowErrors = baseline.rowErrors + (name to safeMessage),
                    )
                }
        }
    }

    fun dismissRowError(name: String) {
        val current = (_state.value as? McpUiState.Runtime) ?: return
        _state.value = current.copy(rowErrors = current.rowErrors - name)
    }

    fun canReload(): Boolean =
        conn != null && projectDir != null && _state.value !is McpUiState.Loading

    fun hasReloadContext(): Boolean = conn != null && projectDir != null

    private fun authRequiredHint(state: McpRuntimeState): String = when (state) {
        McpRuntimeState.NEEDS_AUTH ->
            "需要 OAuth 授权，目前移动端暂不支持，请在 Web 端完成授权后刷新。"
        McpRuntimeState.NEEDS_CLIENT_REGISTRATION ->
            "需要客户端注册，目前移动端暂不支持，请在 Web 端完成后刷新。"
        else -> ""
    }

    private fun sanitizeForUi(raw: String): String =
        raw.lineSequence().firstOrNull().orEmpty().take(160)
}

@HiltViewModel
class McpViewModel @Inject constructor(
    repository: ServerRepository,
) : ViewModel() {
    private val controller = McpRuntimeController(
        scope = viewModelScope,
        loadRuntime = repository::loadMcpRuntime,
        toggleRuntime = repository::toggleMcpRuntime,
    )

    val state: StateFlow<McpUiState> = controller.state

    fun load(conn: ServerConnection, projectDir: String) = controller.load(conn, projectDir)

    fun refresh() = controller.refresh()

    fun retry() = controller.refresh()

    fun toggleServer(name: String) = controller.toggle(name)

    fun dismissRowError(name: String) = controller.dismissRowError(name)

    fun canReload(): Boolean = controller.canReload()

    fun hasReloadContext(): Boolean = controller.hasReloadContext()
}
