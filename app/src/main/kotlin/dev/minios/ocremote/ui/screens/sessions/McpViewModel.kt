package dev.minios.ocremote.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class McpUiState {
    data object Loading : McpUiState()

    data class Loaded(
        val config: McpConfig,
        val editedServers: Map<String, McpServer> = config.servers,
        val dirty: Boolean = false,
        val saveError: String? = null,
    ) : McpUiState()

    data class Empty(
        val title: String,
        val message: String,
    ) : McpUiState()

    data class Error(val message: String) : McpUiState()

    data object Saving : McpUiState()

    data object SaveSuccess : McpUiState()
}

internal class McpStateController(
    private val scope: CoroutineScope,
    private val readMcpConfigState: suspend (ServerConnection, String) -> McpConfigLoadState,
    private val writeMcpConfig: suspend (ServerConnection, McpConfig) -> Result<Unit>,
) {

    private val _state = MutableStateFlow<McpUiState>(McpUiState.Loading)
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    private var currentConn: ServerConnection? = null
    private var currentProjectDir: String? = null
    private var lastLoaded: McpUiState.Loaded? = null

    fun load(conn: ServerConnection, projectDir: String) {
        currentConn = conn
        currentProjectDir = projectDir
        loadCurrentConfig()
    }

    fun refresh() {
        loadCurrentConfig()
    }

    fun retry() {
        loadCurrentConfig()
    }

    private fun loadCurrentConfig() {
        val conn = currentConn ?: return
        val projectDir = currentProjectDir ?: return
        _state.value = McpUiState.Loading

        scope.launch {
            when (val loadState = readMcpConfigState(conn, projectDir)) {
                is McpConfigLoadState.Loaded -> {
                    val loaded = McpUiState.Loaded(config = loadState.config)
                    lastLoaded = loaded
                    _state.value = loaded
                }

                is McpConfigLoadState.Empty -> {
                    lastLoaded = null
                    _state.value = McpUiState.Empty(
                        title = "暂无 MCP 服务器",
                        message = "已找到 MCP 配置，但当前没有配置任何 MCP 服务器。",
                    )
                }

                is McpConfigLoadState.NotFound -> {
                    lastLoaded = null
                    _state.value = McpUiState.Empty(
                        title = "未找到 MCP 配置",
                        message = "此项目当前没有可读取的 MCP 配置文件。",
                    )
                }

                is McpConfigLoadState.Error -> {
                    lastLoaded = null
                    _state.value = McpUiState.Error(loadState.message)
                }
            }
        }
    }

    fun canReload(): Boolean {
        return currentConn != null &&
            currentProjectDir != null &&
            _state.value !is McpUiState.Loading &&
            _state.value !is McpUiState.Saving
    }

    fun hasReloadContext(): Boolean {
        return currentConn != null && currentProjectDir != null
    }

    fun toggleServer(name: String) {
        val current = (_state.value as? McpUiState.Loaded) ?: lastLoaded ?: return
        val updatedServers = current.editedServers.toMutableMap()
        val server = updatedServers[name] ?: return
        updatedServers[name] = server.copy(enabled = !server.enabled)

        val newState = current.copy(
            editedServers = updatedServers,
            dirty = updatedServers != current.config.servers,
            saveError = null,
        )
        lastLoaded = newState
        _state.value = newState
    }

    fun save() {
        val current = (_state.value as? McpUiState.Loaded) ?: lastLoaded ?: return
        val conn = currentConn ?: return
        val updatedConfig = current.config.copy(servers = current.editedServers)

        _state.value = McpUiState.Saving

        scope.launch {
            writeMcpConfig(conn, updatedConfig)
                .onSuccess {
                    _state.value = McpUiState.SaveSuccess
                }
                .onFailure { error ->
                    val restored = current.copy(saveError = error.message ?: "Save failed")
                    lastLoaded = restored
                    _state.value = restored
                }
        }
    }
}

@HiltViewModel
class McpViewModel @Inject constructor(
    repository: ServerRepository,
) : ViewModel() {
    private val controller = McpStateController(
        scope = viewModelScope,
        readMcpConfigState = repository::readMcpConfigState,
        writeMcpConfig = repository::writeMcpConfig,
    )

    val state: StateFlow<McpUiState> = controller.state

    fun load(conn: ServerConnection, projectDir: String) {
        controller.load(conn, projectDir)
    }

    fun refresh() {
        controller.refresh()
    }

    fun retry() {
        controller.retry()
    }

    fun canReload(): Boolean {
        return controller.canReload()
    }

    fun hasReloadContext(): Boolean {
        return controller.hasReloadContext()
    }

    fun toggleServer(name: String) {
        controller.toggleServer(name)
    }

    fun save() {
        controller.save()
    }
}
