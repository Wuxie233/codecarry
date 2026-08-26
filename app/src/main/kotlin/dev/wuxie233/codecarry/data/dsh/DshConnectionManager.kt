package dev.wuxie233.codecarry.data.dsh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal fun shouldRefreshForegroundDsh(isReady: Boolean): Boolean = isReady

class DshConnectionManager(
    private val client: DshApiClient,
    private val scope: CoroutineScope,
    private val reconnectInitialMillis: Long = 1_000L,
    private val reconnectMaxMillis: Long = 30_000L,
) {
    private val generations = AtomicLong(0)
    private val jobs = mutableMapOf<String, Job>()
    private val connections = mutableMapOf<String, DshConnection>()
    private val _states = MutableStateFlow<Map<String, DshGenerationState>>(emptyMap())
    val states: StateFlow<Map<String, DshGenerationState>> = _states.asStateFlow()

    private val reducers = mutableMapOf<String, DshEventReducer>()

    fun reducer(serverId: String): DshEventReducer =
        reducers.getOrPut(serverId) { DshEventReducer() }

    fun connect(serverId: String, connection: DshConnection) {
        connections[serverId] = connection
        jobs.remove(serverId)?.cancel()
        jobs[serverId] = scope.launch {
            var delayMs = reconnectInitialMillis
            while (isActive) {
                val generation = generations.incrementAndGet()
                reducer(serverId).resetGeneration(generation)
                setState(serverId, DshGenerationState(generation = generation, status = DshGenerationStatus.Connecting))
                val connected = runCatching {
                    connectOnce(serverId, connection, generation)
                }
                if (connected.isSuccess) {
                    delayMs = reconnectInitialMillis
                } else {
                    val error = connected.exceptionOrNull()
                    if (error is CancellationException) throw error
                    if (error is DshAuthRequiredException) {
                        setState(
                            serverId,
                            DshGenerationState(
                                generation = generation,
                                status = DshGenerationStatus.Failed,
                                error = error.message,
                            ),
                        )
                        return@launch
                    }
                    setState(
                        serverId,
                        DshGenerationState(
                            generation = generation,
                            status = DshGenerationStatus.Failed,
                            error = error?.message,
                        ),
                    )
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(reconnectMaxMillis)
                }
            }
        }
    }

    fun disconnect(serverId: String) {
        jobs.remove(serverId)?.cancel()
        connections.remove(serverId)
        reducer(serverId).clearPending()
        setState(serverId, DshGenerationState(status = DshGenerationStatus.Disconnected))
    }

    fun refreshReadyCatalogs() {
        scope.launch {
            states.value.forEach { (serverId, state) ->
                refreshReadyCatalog(serverId, state)
            }
        }
    }

    internal suspend fun refreshReadyCatalog(
        serverId: String,
        state: DshGenerationState? = states.value[serverId],
    ) {
        if (state == null || !shouldRefreshForegroundDsh(state.isReady)) return
        val connection = connections[serverId] ?: return
        val generation = state.generation
        try {
            val workspaces = client.workspaceList(connection)
            val sessions = client.sessionList(connection)
            if (states.value[serverId]?.generation != generation) return
            reducer(serverId).applyWorkspaceList(workspaces)
            reducer(serverId).applySessionList(sessions.items)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val current = states.value[serverId]
            if (current?.generation == generation && current.isReady) {
                reconnect(serverId)
            }
        }
    }

    fun reconnect(serverId: String) {
        val connection = connections[serverId] ?: return
        connect(serverId, connection)
    }

    private suspend fun connectOnce(
        serverId: String,
        connection: DshConnection,
        generation: Long,
    ) {
        val describe = client.describe(connection)
        var mux: DshDownlink? = null
        var host: DshDownlink? = null
        try {
            mux = client.openMux(connection)
            host = client.openHost(connection)
            setState(
                serverId,
                DshGenerationState(
                    generation = generation,
                    status = DshGenerationStatus.Ready,
                    describe = describe,
                    muxOpen = true,
                    hostOpen = true,
                ),
            )
            coroutineScope {
                launch {
                    client.muxFrames(mux).collect { envelope ->
                        reducer(serverId).applyMux(envelope.rpcId, envelope.payload)
                    }
                    error("mux downlink closed")
                }
                launch {
                    client.hostFrames(host).collect { envelope ->
                        reducer(serverId).applyHost(envelope.payload)
                    }
                    error("host downlink closed")
                }
            }
        } finally {
            reducer(serverId).clearPending()
            runCatching { mux?.close() }
            runCatching { host?.close() }
            setState(
                serverId,
                DshGenerationState(
                    generation = generation,
                    status = DshGenerationStatus.Failed,
                    describe = describe,
                    muxOpen = false,
                    hostOpen = false,
                    error = "generation $generation closed",
                ),
            )
        }
    }

    private fun setState(serverId: String, state: DshGenerationState) {
        _states.update { it + (serverId to state) }
    }
}
