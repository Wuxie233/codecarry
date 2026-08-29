package dev.wuxie233.codecarry.data.dsh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

internal fun shouldRefreshForegroundDsh(isReady: Boolean): Boolean = isReady

/**
 * Owns one Connection generation per server: cookie exchange, the single
 * `/api/remote.mux` socket, its three supervision streams (`$events`,
 * `session/control`, `workspace/follow`), and per-chat `session/follow`
 * streams demultiplexed off the same socket.
 */
class DshConnectionManager(
    private val client: DshApiClient,
    private val scope: CoroutineScope,
    private val reconnectInitialMillis: Long = 1_000L,
    private val reconnectMaxMillis: Long = 30_000L,
    private val mintStreamId: () -> String = DshRpc::mintStreamId,
) {
    private val generations = AtomicLong(0)
    private val jobs = mutableMapOf<String, Job>()
    private val connections = mutableMapOf<String, DshConnection>()
    private val _states = MutableStateFlow<Map<String, DshGenerationState>>(emptyMap())
    val states: StateFlow<Map<String, DshGenerationState>> = _states.asStateFlow()

    private val reducers = mutableMapOf<String, DshEventReducer>()

    private val muxDownlinks = mutableMapOf<String, DshDownlink>()
    private val muxChannels = mutableMapOf<String, MutableSharedFlow<DshMuxWireMessage>>()

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
        muxDownlinks.remove(serverId)
        muxChannels.remove(serverId)
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

    /** Session rows refresh; the workspace catalog is live from `workspace/follow`. */
    internal suspend fun refreshReadyCatalog(
        serverId: String,
        state: DshGenerationState? = states.value[serverId],
    ) {
        if (state == null || !shouldRefreshForegroundDsh(state.isReady)) return
        val connection = connections[serverId] ?: return
        val generation = state.generation
        try {
            val sessions = client.sessionList(connection)
            if (states.value[serverId]?.generation != generation) return
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

    /**
     * Open one `session/follow` logical stream on the live mux socket. The
     * flow ends when the Host ends the stream, the socket dies, or the
     * collector cancels (which sends a logical cancel frame).
     */
    fun openSessionFollow(
        serverId: String,
        sessionId: String,
        maxMessages: Int? = null,
    ): Flow<DshFollowFrame> = flow {
        val downlink = muxDownlinks[serverId]
            ?: throw DshTransportException("DSH mux for $serverId is not open")
        val channel = muxChannels[serverId]
            ?: throw DshTransportException("DSH mux channel for $serverId is missing")
        val streamId = mintStreamId()
        client.sendStreamOpen(
            downlink,
            DshRpc.SESSION_FOLLOW_ENDPOINT,
            streamId,
            buildJsonObject {
                put(
                    "request",
                    buildJsonObject {
                        put(
                            "address",
                            buildJsonObject {
                                put("kind", "session")
                                put("sessionId", sessionId)
                            },
                        )
                        maxMessages?.let { put("maxMessages", it) }
                    },
                )
            },
        )
        try {
            channel.collect { message ->
                when (message) {
                    is DshMuxWireMessage.Item -> if (message.streamId == streamId) {
                        parseFollowFrame(message.value)?.let { emit(it) }
                    }
                    is DshMuxWireMessage.WireError -> if (message.streamId == streamId) {
                        throw DshTransportException(
                            "session/follow error: ${message.error.code}: ${message.error.message}",
                        )
                    }
                    is DshMuxWireMessage.End -> if (message.streamId == streamId) {
                        throw DshTransportException("session/follow stream ended")
                    }
                }
            }
        } finally {
            runCatching { client.sendStreamCancel(downlink, streamId) }
        }
    }

    private suspend fun connectOnce(
        serverId: String,
        connection: DshConnection,
        generation: Long,
    ) {
        val authed = client.exchangeCookie(connection)
        var mux: DshDownlink? = null
        try {
            mux = client.openMux(authed)
            muxDownlinks[serverId] = mux
            val channel = MutableSharedFlow<DshMuxWireMessage>(
                extraBufferCapacity = 256,
                onBufferOverflow = BufferOverflow.SUSPEND,
            )
            muxChannels[serverId] = channel
            val eventsId = mintStreamId()
            val controlId = mintStreamId()
            val workspaceId = mintStreamId()
            client.sendStreamOpen(mux, DshRpc.EVENTS_ENDPOINT, eventsId, JsonObject(emptyMap()))
            client.sendStreamOpen(mux, DshRpc.SESSION_CONTROL_ENDPOINT, controlId, JsonObject(emptyMap()))
            client.sendStreamOpen(mux, DshRpc.WORKSPACE_FOLLOW_ENDPOINT, workspaceId, JsonObject(emptyMap()))
            setState(
                serverId,
                DshGenerationState(
                    generation = generation,
                    status = DshGenerationStatus.Connecting,
                    cookie = authed.cookie,
                    muxOpen = true,
                ),
            )
            coroutineScope {
                launch {
                    client.muxMessages(mux).collect { message ->
                        channel.emit(message)
                        routeFrame(serverId, generation, message, eventsId, controlId, workspaceId)
                    }
                    error("mux downlink closed")
                }
            }
        } finally {
            muxDownlinks.remove(serverId)
            muxChannels.remove(serverId)
            reducer(serverId).clearPending()
            runCatching { mux?.close() }
            setState(
                serverId,
                DshGenerationState(
                    generation = generation,
                    status = DshGenerationStatus.Failed,
                    muxOpen = false,
                    eventsReady = false,
                    controlReady = false,
                    workspaceReady = false,
                    error = "generation $generation closed",
                ),
            )
        }
    }

    private fun routeFrame(
        serverId: String,
        generation: Long,
        message: DshMuxWireMessage,
        eventsId: String,
        controlId: String,
        workspaceId: String,
    ) {
        val item = message as? DshMuxWireMessage.Item ?: return
        val current = states.value[serverId] ?: return
        if (current.generation != generation) return
        when (item.streamId) {
            eventsId -> when (val frame = parseEventsFrame(item.value)) {
                is DshEventsFrame.Ready -> {
                    reducer(serverId).applyEventsFrame(frame)
                    setState(
                        serverId,
                        current.copy(
                            describe = dshHostDescribeFromReady(frame.home),
                            eventsReady = true,
                            eventsClientId = frame.clientId,
                        ),
                    )
                }
                else -> frame?.let { reducer(serverId).applyEventsFrame(it) }
            }
            controlId -> when (val frame = parseControlFrame(item.value)) {
                is DshControlFrame.Baseline -> {
                    reducer(serverId).applyControlFrame(frame)
                    setState(serverId, current.copy(controlReady = true))
                }
                else -> frame?.let { reducer(serverId).applyControlFrame(it) }
            }
            workspaceId -> when (val frame = parseWorkspaceFrame(item.value)) {
                is DshWorkspaceFrame.Baseline -> {
                    reducer(serverId).applyWorkspaceFrame(frame)
                    setState(serverId, current.copy(workspaceReady = true))
                }
                else -> frame?.let { reducer(serverId).applyWorkspaceFrame(it) }
            }
            else -> Unit
        }
        promoteWhenReady(serverId, generation)
    }

    private fun promoteWhenReady(serverId: String, generation: Long) {
        val current = states.value[serverId] ?: return
        if (current.generation != generation) return
        if (!current.isReady) return
        if (current.status == DshGenerationStatus.Ready) return
        setState(serverId, current.copy(status = DshGenerationStatus.Ready))
    }

    private fun setState(serverId: String, state: DshGenerationState) {
        _states.update { it + (serverId to state) }
    }
}
