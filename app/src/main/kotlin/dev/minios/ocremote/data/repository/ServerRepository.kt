package dev.minios.ocremote.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.OpenCodeFileNotFoundException
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PiConnection
import dev.minios.ocremote.data.api.McpRuntimeStatusResult
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpRuntimeSnapshot
import dev.minios.ocremote.domain.model.McpRuntimeState
import dev.minios.ocremote.domain.model.McpRuntimeStatus
import dev.minios.ocremote.domain.model.McpServer
import dev.minios.ocremote.domain.model.McpSource
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.ServerHealth
import dev.minios.ocremote.domain.model.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ServerRepository"
private const val SERVERS_KEY = "servers"
private const val RUNTIME_SOURCE_SENTINEL = "<runtime>"

/**
 * Server Repository - manages saved OpenCode servers
 *
 * Uses DataStore to persist server configurations
 */
@Singleton
class ServerRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: OpenCodeApi,
    private val piApi: PiApi,
    private val json: Json,
) {

    private val serversKey = stringPreferencesKey(SERVERS_KEY)

    /**
     * Get all saved servers as Flow
     */
    val servers: Flow<List<ServerConfig>> = dataStore.data.map { preferences ->
        val serversJson = preferences[serversKey] ?: "[]"
        try {
            json.decodeFromString<List<ServerConfig>>(serversJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode servers", e)
            emptyList()
        }
    }

    /**
     * Get all servers (alias for servers Flow)
     */
    fun getAllServers(): Flow<List<ServerConfig>> = servers

    /**
     * Add a new server
     */
    suspend fun addServer(
        url: String,
        type: ServerType = ServerType.OPENCODE,
        username: String = "opencode",
        password: String? = null,
        token: String? = null,
        name: String? = null,
        autoConnect: Boolean = false,
    ): ServerConfig {
        val server = ServerConfig(
            id = UUID.randomUUID().toString(),
            type = type,
            url = url.trimEnd('/'),
            username = username,
            password = password,
            token = token,
            name = name,
            autoConnect = autoConnect,
            lastConnected = null,
            isHealthy = false,
        )

        val currentServers = servers.firstOrNull() ?: emptyList()
        val updatedServers = currentServers + server

        saveServers(updatedServers)

        return server
    }

    /**
     * Update a server
     */
    suspend fun updateServer(server: ServerConfig) {
        val currentServers = servers.firstOrNull() ?: emptyList()
        val updatedServers = currentServers.map {
            if (it.id == server.id) server else it
        }

        saveServers(updatedServers)
    }

    suspend fun setAutoConnect(serverId: String, autoConnect: Boolean) {
        val server = getServer(serverId) ?: return
        updateServer(server.copy(autoConnect = autoConnect))
    }

    /**
     * Delete a server
     */
    suspend fun deleteServer(serverId: String) {
        val currentServers = servers.firstOrNull() ?: emptyList()
        val updatedServers = currentServers.filter { it.id != serverId }

        saveServers(updatedServers)
    }

    /**
     * Check server health
     */
    suspend fun checkHealth(server: ServerConfig): Result<ServerHealth> {
        return try {
            val health = when (server.type) {
                ServerType.OPENCODE -> {
                    val conn = ServerConnection.from(server.url, server.username, server.password)
                    api.getHealth(conn)
                }

                ServerType.PI_ROUNDTABLE -> {
                    val conn = PiConnection.from(server.url, server.token)
                    piApi.listRoundtables(conn)
                    ServerHealth(healthy = true)
                }
            }

            // Update server health status
            val updatedServer = server.copy(
                isHealthy = health.healthy,
                lastConnected = System.currentTimeMillis(),
            )
            updateServer(updatedServer)

            Result.success(health)
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Health check failed for ${server.url}", e) }

            // Mark as unhealthy
            val updatedServer = server.copy(isHealthy = false)
            updateServer(updatedServer)

            Result.failure(e)
        }
    }

    /**
     * Check server health (alias returning boolean)
     */
    suspend fun checkServerHealth(server: ServerConfig): Boolean {
        return checkHealth(server).isSuccess
    }

    /**
     * Get server by ID
     */
    suspend fun getServer(serverId: String): ServerConfig? {
        return servers.firstOrNull()?.find { it.id == serverId }
    }

    suspend fun readMcpConfig(
        conn: ServerConnection,
        projectDir: String,
    ): Result<McpConfig?> {
        return readMcpConfigResult(readMcpConfigState(conn, projectDir))
    }

    suspend fun readMcpConfigState(
        conn: ServerConnection,
        projectDir: String,
    ): McpConfigLoadState = readMcpConfigState(conn, projectDir, preferRuntimeStatus = true)

    private suspend fun readMcpConfigState(
        conn: ServerConnection,
        projectDir: String,
        preferRuntimeStatus: Boolean,
    ): McpConfigLoadState {
        val projectDirectory = projectDir.takeIf { it.isNotBlank() }

        val runtimeResult = if (preferRuntimeStatus) {
            api.getMcpStatus(conn, directory = projectDirectory)
        } else {
            McpRuntimeStatusResult.Unsupported
        }
        when (runtimeResult) {
            is McpRuntimeStatusResult.Success -> {
                if (runtimeResult.statuses.isNotEmpty()) {
                    return McpConfigLoadState.Loaded(
                        config = McpConfig(
                            filePath = RUNTIME_SOURCE_SENTINEL,
                            rawJson = "{}",
                            servers = runtimeResult.statuses.entries.associate { (name, status) ->
                                name to McpServer(
                                    name = name,
                                    type = null,
                                    command = null,
                                    args = emptyList(),
                                    url = null,
                                    enabled = status.status != "disabled" && status.status != "disconnected",
                                )
                            },
                        ),
                        source = McpSource.Runtime,
                    )
                }
            }

            is McpRuntimeStatusResult.Unsupported -> Unit
            is McpRuntimeStatusResult.Failed -> {
                runCatching {
                    Log.w(
                        TAG,
                        "getMcpStatus failed; falling back to file scan: ${runtimeResult.cause.javaClass.simpleName}",
                    )
                }
            }
        }

        val homeDir = runCatching { api.getServerPaths(conn, directory = projectDirectory).home }
            .getOrElse { error ->
                return McpConfigLoadState.Error(
                    filePath = null,
                    message = error.message ?: "Failed to resolve server paths",
                    cause = error,
                )
            }

        val candidates = buildList {
            val normalizedProjectDir = projectDir.trimEnd('/')
            if (normalizedProjectDir.isNotEmpty()) {
                add("$normalizedProjectDir/.opencode/opencode.json")
                add("$normalizedProjectDir/.opencode/config.json")
                add("$normalizedProjectDir/opencode.json")
            }

            val normalizedHomeDir = homeDir.trimEnd('/')
            if (normalizedHomeDir.isNotEmpty()) {
                add("$normalizedHomeDir/.config/opencode/opencode.json")
                add("$normalizedHomeDir/.config/opencode/config.json")
            }
        }

        val reads = mutableListOf<McpConfigCandidateRead>()
        for (path in candidates) {
            reads += McpConfigCandidateRead(
                path = path,
                readResult = runCatching { api.readFileText(conn, path, directory = projectDirectory) },
            )
            when (val state = resolveMcpConfigLoadState(reads)) {
                is McpConfigLoadState.Loaded -> return state
                is McpConfigLoadState.Error -> return state
                is McpConfigLoadState.Empty,
                is McpConfigLoadState.NotFound,
                is McpConfigLoadState.RuntimeUnavailable -> Unit
            }
        }
        val fileFallback = resolveMcpConfigLoadState(reads)

        return if (runtimeResult is McpRuntimeStatusResult.Failed &&
            (fileFallback is McpConfigLoadState.Empty || fileFallback is McpConfigLoadState.NotFound)
        ) {
            McpConfigLoadState.RuntimeUnavailable(fallback = fileFallback)
        } else {
            fileFallback
        }
    }

    suspend fun writeMcpConfig(
        conn: ServerConnection,
        config: McpConfig,
    ): Result<Unit> = runCatching {
        if (config.filePath == RUNTIME_SOURCE_SENTINEL) {
            throw IllegalStateException("Cannot persist edits when MCP source is runtime")
        }
        val updatedJson = McpConfigParser.serialize(config)
        val configDirectory = config.filePath.substringBeforeLast('/').takeIf { it.isNotBlank() }
        api.writeFile(conn, path = config.filePath, content = updatedJson, directory = configDirectory)
    }

    // ============ MCP Runtime ============

    /**
     * Load runtime MCP status for the current project, with file-config fallback
     * for older OpenCode servers that do not expose /mcp.
     *
     * Returns a snapshot whose [McpRuntimeSnapshot.supportsRuntimeControl] tells
     * the UI whether to render interactive switches or read-only file rows.
     */
    suspend fun loadMcpRuntime(
        conn: ServerConnection,
        projectDir: String,
    ): Result<McpRuntimeSnapshot> = runCatching {
        val directory = projectDir.takeIf { it.isNotBlank() }
        val runtime = try {
            api.getMcpRuntime(conn, directory = directory)
        } catch (error: Exception) {
            val fallbackState = readMcpConfigState(conn, projectDir, preferRuntimeStatus = false)
            return@runCatching fallbackState.toRuntimeSnapshot(runtimeUnavailable = true)
        }
        if (runtime != null) {
            return@runCatching McpRuntimeSnapshot(servers = runtime, supportsRuntimeControl = true)
        }

        readMcpConfigState(conn, projectDir, preferRuntimeStatus = false).toRuntimeSnapshot(
            runtimeUnavailable = false,
        )
    }

    private fun McpConfigLoadState.toRuntimeSnapshot(runtimeUnavailable: Boolean): McpRuntimeSnapshot = when (this) {
        is McpConfigLoadState.Loaded -> McpRuntimeSnapshot(
            servers = config.servers.values.map {
                McpRuntimeStatus(name = it.name, state = McpRuntimeState.UNKNOWN, errorMessage = null)
            },
            supportsRuntimeControl = false,
            runtimeUnavailable = runtimeUnavailable,
            fallbackExhausted = false,
        )
        is McpConfigLoadState.Empty -> McpRuntimeSnapshot(
            servers = emptyList(),
            supportsRuntimeControl = false,
            runtimeUnavailable = runtimeUnavailable,
            fallbackExhausted = true,
        )
        is McpConfigLoadState.NotFound -> McpRuntimeSnapshot(
            servers = emptyList(),
            supportsRuntimeControl = false,
            runtimeUnavailable = runtimeUnavailable,
            fallbackExhausted = true,
        )
        is McpConfigLoadState.RuntimeUnavailable -> fallback.toRuntimeSnapshot(runtimeUnavailable = true)
        is McpConfigLoadState.Error -> throw cause ?: IllegalStateException(message)
    }

    /**
     * Runtime toggle transaction:
     *   1. Inspect current [previous] for [name].
     *   2. CONNECTED → call disconnect; DISABLED, FAILED, UNKNOWN → call connect.
     *   3. After the API succeeds, refetch the full runtime list.
     *   4. On any toggle/refetch failure, return the original snapshot unchanged.
     */
    suspend fun toggleMcpRuntime(
        conn: ServerConnection,
        projectDir: String,
        name: String,
        previous: McpRuntimeSnapshot,
    ): Result<McpRuntimeSnapshot> {
        val directory = projectDir.takeIf { it.isNotBlank() }
        val target = previous.servers.firstOrNull { it.name == name }
            ?: return Result.failure(IllegalStateException("Unknown MCP server: $name"))

        return when (target.state) {
            McpRuntimeState.CONNECTED -> performToggle(conn, directory, name, previous) {
                api.disconnectMcp(conn, name, directory = directory)
            }
            McpRuntimeState.DISABLED,
            McpRuntimeState.FAILED,
            McpRuntimeState.UNKNOWN -> performToggle(conn, directory, name, previous) {
                api.connectMcp(conn, name, directory = directory)
            }
            McpRuntimeState.NEEDS_AUTH,
            McpRuntimeState.NEEDS_CLIENT_REGISTRATION -> Result.failure(
                McpAuthRequiredException(state = target.state, name = name)
            )
        }
    }

    private suspend fun performToggle(
        conn: ServerConnection,
        directory: String?,
        name: String,
        previous: McpRuntimeSnapshot,
        action: suspend () -> Boolean,
    ): Result<McpRuntimeSnapshot> = runCatching {
        val supported = action()
        if (!supported) {
            throw McpRuntimeUnsupportedException()
        }
        val refreshed = api.getMcpRuntime(conn, directory = directory)
            ?: throw McpRuntimeUnsupportedException()
        McpRuntimeSnapshot(servers = refreshed, supportsRuntimeControl = true)
    }.recoverCatching { error ->
        throw McpToggleException(name = name, previous = previous, cause = error)
    }

    // ============ Private ============

    private suspend fun saveServers(servers: List<ServerConfig>) {
        dataStore.edit { preferences ->
            val serversJson = json.encodeToString(servers)
            preferences[serversKey] = serversJson
        }
    }

    internal data class McpConfigCandidateRead(
        val path: String,
        val readResult: Result<String>,
    )

    private fun readMcpConfigResult(state: McpConfigLoadState): Result<McpConfig?> = when (state) {
        is McpConfigLoadState.Loaded -> Result.success(state.config)
        is McpConfigLoadState.Empty -> Result.success(state.config)
        is McpConfigLoadState.NotFound -> Result.success(null)
        is McpConfigLoadState.Error -> Result.failure(state.cause ?: IllegalStateException(state.message))
        is McpConfigLoadState.RuntimeUnavailable -> readMcpConfigResult(state.fallback)
    }

    internal fun resolveMcpConfigLoadState(
        candidateReads: List<McpConfigCandidateRead>,
    ): McpConfigLoadState {
        var rememberedEmpty: McpConfigLoadState.Empty? = null

        for ((path, readResult) in candidateReads) {
            if (readResult.isFailure) {
                val error = readResult.exceptionOrNull()!!
                if (error is OpenCodeFileNotFoundException) {
                    continue
                }
                return McpConfigLoadState.Error(
                    filePath = path,
                    message = error.message ?: "Failed to read MCP config",
                    cause = error,
                )
            }

            val raw = readResult.getOrThrow()

            val content = raw.takeIf { it.isNotBlank() }
            if (content == null) {
                if (rememberedEmpty == null) {
                    rememberedEmpty = McpConfigLoadState.Empty(
                        config = McpConfig(
                            filePath = path,
                            rawJson = raw,
                            servers = emptyMap(),
                        )
                    )
                }
                continue
            }

            when (val parsed = McpConfigParser.parseState(path, content)) {
                is McpConfigLoadState.Loaded -> return parsed
                is McpConfigLoadState.Empty -> {
                    if (rememberedEmpty == null) {
                        rememberedEmpty = parsed
                    }
                }

                is McpConfigLoadState.Error -> return parsed
                is McpConfigLoadState.NotFound,
                is McpConfigLoadState.RuntimeUnavailable -> Unit
            }
        }

        return rememberedEmpty ?: McpConfigLoadState.NotFound(candidateReads.map { it.path })
    }
}

class McpAuthRequiredException(
    val state: McpRuntimeState,
    val name: String,
) : RuntimeException("MCP server '$name' requires ${state.name.lowercase()}")

class McpRuntimeUnsupportedException :
    RuntimeException("OpenCode server does not support runtime MCP control")

class McpToggleException(
    val name: String,
    val previous: McpRuntimeSnapshot,
    cause: Throwable,
) : RuntimeException("Failed to toggle MCP server '$name': ${cause.message}", cause)
