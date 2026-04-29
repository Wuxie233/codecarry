package dev.minios.ocremote.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.OpenCodeFileReadException
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
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject

sealed class McpUiState {
    data object Loading : McpUiState()

    data class Loaded(
        val config: McpConfig,
        val editedServers: Map<String, McpServer> = config.servers,
        val dirty: Boolean = false,
        val saveError: String? = null,
    ) : McpUiState()

    data class EmptyConfig(val filePath: String, val fallbackExhausted: Boolean = true) : McpUiState()

    data class MissingConfig(val checkedPaths: List<String>) : McpUiState()

    data class ReadError(val filePath: String?, val message: String) : McpUiState()

    data class ParseError(val filePath: String, val message: String) : McpUiState()

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
    private var pendingEdits: Map<String, McpServer>? = null

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
                    val pending = pendingEdits
                    val loaded = McpUiState.Loaded(config = loadState.config)
                    val resolvedLoaded = if (pending != null) {
                        if (pending.keys.all { it in loadState.config.servers.keys }) {
                            val mergedServers = loadState.config.servers.mapValues { (name, server) ->
                                pending[name]?.let { pendingServer ->
                                    server.copy(enabled = pendingServer.enabled)
                                } ?: server
                            }
                            val dirtyEntries = dirtyEdits(mergedServers, loadState.config.servers)
                            pendingEdits = dirtyEntries
                            loaded.copy(
                                editedServers = mergedServers,
                                dirty = dirtyEntries != null,
                            )
                        } else {
                            pendingEdits = null
                            loaded
                        }
                    } else {
                        loaded
                    }
                    lastLoaded = resolvedLoaded
                    _state.value = resolvedLoaded
                }

                is McpConfigLoadState.Empty -> {
                    lastLoaded = null
                    _state.value = McpUiState.EmptyConfig(
                        filePath = loadState.config.filePath,
                        fallbackExhausted = true
                    )
                }

                is McpConfigLoadState.NotFound -> {
                    lastLoaded = null
                    _state.value = McpUiState.MissingConfig(checkedPaths = loadState.checkedPaths)
                }

                is McpConfigLoadState.Error -> {
                    _state.value = loadState.toUiState()
                    if (_state.value !is McpUiState.ReadError) {
                        lastLoaded = null
                    }
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
        val dirtyEntries = dirtyEdits(updatedServers, current.config.servers)

        val newState = current.copy(
            editedServers = updatedServers,
            dirty = dirtyEntries != null,
            saveError = null,
        )
        pendingEdits = dirtyEntries
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
                    pendingEdits = null
                    _state.value = McpUiState.SaveSuccess
                }
                .onFailure { error ->
                    val restored = current.copy(saveError = error.message ?: "Save failed")
                    lastLoaded = restored
                    _state.value = restored
                }
        }
    }

    private fun dirtyEdits(
        editedServers: Map<String, McpServer>,
        baseServers: Map<String, McpServer>,
    ): Map<String, McpServer>? = editedServers
        .filter { (name, server) -> baseServers[name] != server }
        .takeIf { it.isNotEmpty() }

    private fun McpConfigLoadState.Error.toUiState(): McpUiState = when {
        cause is OpenCodeFileReadException || cause is IOException -> McpUiState.ReadError(filePath, message)
        isParseError() -> McpUiState.ParseError(filePath.orEmpty(), message)
        else -> McpUiState.ReadError(filePath, message)
    }

    private fun McpConfigLoadState.Error.isParseError(): Boolean {
        val lowerMessage = message.lowercase()
        return cause is SerializationException ||
            lowerMessage.contains("parse") ||
            lowerMessage.contains("invalid") ||
            lowerMessage.contains("missing required command")
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
