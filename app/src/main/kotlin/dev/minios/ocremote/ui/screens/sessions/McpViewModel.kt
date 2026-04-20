package dev.minios.ocremote.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpServer
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

    data object NoConfig : McpUiState()

    data class Error(val message: String) : McpUiState()

    data object Saving : McpUiState()

    data object SaveSuccess : McpUiState()
}

@HiltViewModel
class McpViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<McpUiState>(McpUiState.Loading)
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    private var currentConn: ServerConnection? = null
    private var lastLoaded: McpUiState.Loaded? = null

    fun load(conn: ServerConnection, projectDir: String) {
        currentConn = conn
        _state.value = McpUiState.Loading

        viewModelScope.launch {
            repository.readMcpConfig(conn, projectDir)
                .onSuccess { config ->
                    if (config == null) {
                        lastLoaded = null
                        _state.value = McpUiState.NoConfig
                    } else {
                        val loaded = McpUiState.Loaded(config = config)
                        lastLoaded = loaded
                        _state.value = loaded
                    }
                }
                .onFailure { error ->
                    lastLoaded = null
                    _state.value = McpUiState.Error(error.message ?: "Unknown error")
                }
        }
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

        viewModelScope.launch {
            repository.writeMcpConfig(conn, updatedConfig)
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
