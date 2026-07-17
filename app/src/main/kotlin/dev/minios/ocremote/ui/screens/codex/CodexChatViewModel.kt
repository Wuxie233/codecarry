package dev.minios.ocremote.ui.screens.codex

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.codex.CodexApprovalKind
import dev.minios.ocremote.data.codex.CodexAppServerClient
import dev.minios.ocremote.data.codex.CodexDisconnectedException
import dev.minios.ocremote.data.codex.CodexConnectionLease
import dev.minios.ocremote.data.codex.CodexConnectionManager
import dev.minios.ocremote.data.codex.CodexGoal
import dev.minios.ocremote.data.codex.CodexMemoryMode
import dev.minios.ocremote.data.codex.CodexModel
import dev.minios.ocremote.data.codex.CodexServerRequest
import dev.minios.ocremote.data.codex.CodexThread
import dev.minios.ocremote.data.codex.CodexServerConnection
import dev.minios.ocremote.data.codex.requestKey
import dev.minios.ocremote.data.codex.CodexPermissionGrant
import dev.minios.ocremote.data.codex.CodexPermissionGrantScope
import dev.minios.ocremote.data.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID
import javax.inject.Inject

data class CodexChatUiState(
    val thread: CodexThread? = null,
    val goal: CodexGoal? = null,
    val activeTurnId: String? = null,
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val isAwaitingAuthoritativeTurn: Boolean = false,
    val isSendConfirmationPending: Boolean = false,
    val memoryMode: CodexMemoryMode? = null,
    val models: List<CodexModel> = emptyList(),
    val selectedModel: CodexModel? = null,
    val selectedEffort: String? = null,
    val pendingRequests: List<CodexServerRequest> = emptyList(),
    val submittingRequestKeys: Set<String> = emptySet(),
    val error: String? = null,
)

data class CodexSendResult(val content: String, val accepted: Boolean)

internal fun beginCodexRequestSubmission(state: CodexChatUiState, key: String): CodexChatUiState? =
    if (key in state.submittingRequestKeys) null else state.copy(
        submittingRequestKeys = state.submittingRequestKeys + key,
        error = null,
    )

@HiltViewModel
class CodexChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val connectionManager: CodexConnectionManager,
    private val serverRepository: ServerRepository,
) : ViewModel() {
    val serverId: String = decodeCodexRouteArg(savedStateHandle["serverId"])
    val threadId: String = decodeCodexRouteArg(savedStateHandle["threadId"])

    private val restoredPendingSendContent = savedStateHandle.get<String>(PENDING_SEND_CONTENT_KEY)
    private val restoredPendingSendId = savedStateHandle.get<String>(PENDING_SEND_ID_KEY)
    private val restoredPendingSendAccepted = savedStateHandle.get<Boolean>(PENDING_SEND_ACCEPTED_KEY) == true
    private val authoritativeTurnTracker = CodexAuthoritativeTurnTracker(
        savedStateHandle.get<ArrayList<String>>(AWAITING_TURN_BASELINE_KEY)?.toSet(),
    )
    private val _uiState = MutableStateFlow(
        CodexChatUiState(
            isSendConfirmationPending = restoredPendingSendContent != null && restoredPendingSendId != null,
            isAwaitingAuthoritativeTurn = authoritativeTurnTracker.isAwaiting,
        ),
    )
    val uiState: StateFlow<CodexChatUiState> = _uiState.asStateFlow()
    private val _sendResults = MutableSharedFlow<CodexSendResult>(extraBufferCapacity = 1)
    val sendResults: SharedFlow<CodexSendResult> = _sendResults.asSharedFlow()
    private var client: CodexAppServerClient? = null
    private var connection: CodexServerConnection? = null
    private var lease: CodexConnectionLease? = null
    private var eventsJob: Job? = null
    private var requestsJob: Job? = null
    private var activeThreadToken: Closeable? = null
    private var loadJob: Job? = null
    private val connectionMutex = Mutex()
    private val sendIdentity = CodexSendIdentityTracker(
        createId = { UUID.randomUUID().toString() },
        initialContent = restoredPendingSendContent,
        initialId = restoredPendingSendId,
        initialAccepted = restoredPendingSendAccepted,
    )

    init {
        connectAndLoad()
        observePendingRequests()
    }

    fun connectAndLoad() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val server = serverRepository.getServer(serverId) ?: error("Codex server is no longer configured")
                val acquired = acquireCurrentConnection(server)
                if (authoritativeTurnTracker.isAwaiting) {
                    connectionManager.retainProvisionalTurn(serverId, threadId)
                }
                val connected = acquired.connection.client
                connected.connect()
                val resumed = connected.resumeThread(threadId, excludeTurns = true)
                val thread = connected.readThread(threadId, includeTurns = true)
                val goal = runCatching { connected.getGoal(threadId) }.getOrNull()
                val models = runCatching { connected.listModels(limit = 100).models }.getOrDefault(emptyList())
                val selectedModel = models.firstOrNull { it.model == resumed.model || it.id == resumed.model }
                    ?: resumed.model?.let { model ->
                        CodexModel(id = model, model = model, displayName = model)
                    }
                    ?: models.firstOrNull { it.isDefault }
                    ?: models.firstOrNull()
                val visibleModels = if (selectedModel != null && models.none { it.model == selectedModel.model }) {
                    listOf(selectedModel) + models
                } else {
                    models
                }
                acquired.connection.reducer.upsertThread(thread)
                val mergedThread = acquired.connection.events.value.threads[threadId] ?: thread
                val receivedAuthoritativeTurn = consumeAuthoritativeTurn(mergedThread)
                _uiState.update {
                    it.copy(
                        thread = mergedThread,
                        goal = goal,
                        models = visibleModels,
                        selectedModel = selectedModel,
                        selectedEffort = resumed.reasoningEffort ?: selectedModel?.defaultReasoningEffort,
                        activeTurnId = mergedThread.turns.lastOrNull { turn -> turn.status == "inProgress" }?.id,
                        isAwaitingAuthoritativeTurn = if (receivedAuthoritativeTurn) {
                            false
                        } else {
                            it.isAwaitingAuthoritativeTurn
                        },
                        isLoading = false,
                        error = null,
                    )
                }
                confirmPendingSendFromThread(mergedThread)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to open Codex thread")
                }
            }
        }
    }

    fun setChatVisible(visible: Boolean) {
        if (visible && activeThreadToken == null) {
            activeThreadToken = connectionManager.activateThread(
                dev.minios.ocremote.data.codex.CodexThreadKey(serverId, threadId),
            )
        } else if (!visible) {
            activeThreadToken?.close()
            activeThreadToken = null
        }
    }

    fun sendMessage(text: String) {
        val content = text.trim()
        if (
            content.isEmpty() ||
            _uiState.value.isLoading ||
            _uiState.value.thread == null ||
            _uiState.value.isSending ||
            _uiState.value.isAwaitingAuthoritativeTurn ||
            _uiState.value.isSendConfirmationPending
        ) return
        val clientUserMessageId = sendIdentity.begin(content) ?: return
        savePendingSend(content, clientUserMessageId)
        _uiState.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            try {
                val activeTurnId = _uiState.value.activeTurnId
                if (activeTurnId != null) {
                    requireClient().steerTurn(
                        threadId = threadId,
                        expectedTurnId = activeTurnId,
                        text = content,
                        clientUserMessageId = clientUserMessageId,
                    )
                    markSendAccepted(content, clientUserMessageId, awaitAuthoritativeTurn = false)
                    return@launch
                }
                val selected = _uiState.value.selectedModel
                beginAwaitingAuthoritativeTurn()
                connectionManager.retainProvisionalTurn(serverId, threadId)
                _uiState.update { it.copy(isAwaitingAuthoritativeTurn = true) }
                requireClient().startTurn(
                    threadId = threadId,
                    text = content,
                    model = selected?.model,
                    effort = _uiState.value.selectedEffort,
                    clientUserMessageId = clientUserMessageId,
                )
                markSendAccepted(
                    content,
                    clientUserMessageId,
                    awaitAuthoritativeTurn = authoritativeTurnTracker.isAwaiting,
                )
            } catch (error: TimeoutCancellationException) {
                reconcileUncertainSend(content, clientUserMessageId, error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isUncertainCodexSendFailure(error)) {
                    reconcileUncertainSend(content, clientUserMessageId, error)
                } else {
                    clearAwaitingAuthoritativeTurn()
                    connectionManager.releaseProvisionalTurn(serverId, threadId)
                    sendIdentity.markDefinitiveFailure(content, clientUserMessageId)
                    clearSavedPendingSend()
                    _uiState.update {
                        it.copy(
                            isAwaitingAuthoritativeTurn = false,
                            error = error.message ?: "Failed to start Codex turn",
                        )
                    }
                    _sendResults.emit(CodexSendResult(content, accepted = false))
                }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun recheckPendingSend() {
        val pending = sendIdentity.pendingConfirmation() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, error = null) }
            try {
                val thread = requireClient().readThread(threadId, includeTurns = true)
                requireConnection().reducer.upsertThread(thread)
                val receivedAuthoritativeTurn = consumeAuthoritativeTurn(thread)
                _uiState.update { state ->
                    state.copy(
                        thread = thread,
                        isAwaitingAuthoritativeTurn = if (receivedAuthoritativeTurn) {
                            false
                        } else {
                            state.isAwaitingAuthoritativeTurn
                        },
                    )
                }
                if (thread.hasClientMessage(pending.id)) {
                    markSendAccepted(
                        pending.content,
                        pending.id,
                        awaitAuthoritativeTurn = authoritativeTurnTracker.isAwaiting,
                    )
                } else {
                    _uiState.update { it.copy(error = "Codex has not confirmed this message") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "Failed to check message status") }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun allowPendingResend() {
        val pending = sendIdentity.pendingConfirmation() ?: return
        sendIdentity.markDefinitiveFailure(pending.content, pending.id)
        clearSavedPendingSend()
        clearAwaitingAuthoritativeTurn()
        connectionManager.releaseProvisionalTurn(serverId, threadId)
        _uiState.update {
            it.copy(
                isSendConfirmationPending = false,
                isAwaitingAuthoritativeTurn = false,
                error = null,
            )
        }
    }

    private suspend fun markSendAccepted(
        content: String,
        clientUserMessageId: String,
        awaitAuthoritativeTurn: Boolean,
    ) {
        val firstAcceptance = sendIdentity.markAccepted(
            content,
            clientUserMessageId,
            awaitAuthoritativeTurn,
        )
        if (awaitAuthoritativeTurn) {
            savedStateHandle[PENDING_SEND_ACCEPTED_KEY] = true
        } else {
            clearSavedPendingSend()
        }
        _uiState.update {
            it.copy(isSendConfirmationPending = awaitAuthoritativeTurn, error = null)
        }
        if (firstAcceptance) _sendResults.emit(CodexSendResult(content, accepted = true))
    }

    private suspend fun reconcileUncertainSend(
        content: String,
        clientUserMessageId: String,
        error: Throwable,
    ) {
        sendIdentity.markUncertain(content, clientUserMessageId)
        _uiState.update { it.copy(isSendConfirmationPending = true) }
        val accepted = runCatching {
            val connected = requireClient()
            connected.connect()
            val thread = connected.readThread(threadId, includeTurns = true)
            requireConnection().reducer.upsertThread(thread)
            val receivedAuthoritativeTurn = consumeAuthoritativeTurn(thread)
            _uiState.update { state ->
                state.copy(
                    thread = thread,
                    isAwaitingAuthoritativeTurn = if (receivedAuthoritativeTurn) {
                        false
                    } else {
                        state.isAwaitingAuthoritativeTurn
                    },
                )
            }
            thread.hasClientMessage(clientUserMessageId)
        }.getOrDefault(false)
        if (accepted) {
            markSendAccepted(
                content,
                clientUserMessageId,
                awaitAuthoritativeTurn = authoritativeTurnTracker.isAwaiting,
            )
        } else {
            _uiState.update {
                it.copy(
                    isSendConfirmationPending = true,
                    error = error.message ?: "Connection lost before Codex confirmed the message",
                )
            }
        }
    }

    fun interruptTurn() {
        val turnId = _uiState.value.activeTurnId ?: return
        viewModelScope.launch {
            runCatching { requireClient().interruptTurn(threadId, turnId) }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun compactThread() = launchAction { it.compactThread(threadId) }

    fun renameThread(name: String) = launchAction { client ->
        client.setThreadName(threadId, name.trim())
        _uiState.update { state -> state.copy(thread = state.thread?.copy(name = name.trim())) }
    }

    fun setGoal(objective: String, status: String = "active", tokenBudget: Long? = null) {
        viewModelScope.launch {
            runCatching { requireClient().setGoal(threadId, objective.trim(), status, tokenBudget) }
                .onSuccess { goal -> _uiState.update { it.copy(goal = goal, error = null) } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun clearGoal() {
        viewModelScope.launch {
            runCatching { requireClient().clearGoal(threadId) }
                .onSuccess { _uiState.update { it.copy(goal = null, error = null) } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun setMemoryMode(mode: CodexMemoryMode) {
        viewModelScope.launch {
            runCatching { requireClient().setMemoryMode(threadId, mode) }
                .onSuccess { _uiState.update { it.copy(memoryMode = mode, error = null) } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun selectModel(model: CodexModel) {
        _uiState.update {
            it.copy(
                selectedModel = model,
                selectedEffort = model.defaultReasoningEffort,
            )
        }
    }

    fun selectEffort(effort: String) {
        _uiState.update { it.copy(selectedEffort = effort) }
    }

    fun answerApproval(request: CodexServerRequest, decision: String) {
        submitRequest(request) {
            val approval = requireNotNull(request.approval)
            when (approval.kind) {
                    CodexApprovalKind.PERMISSIONS -> {
                        if (decision == "accept" || decision == "acceptForSession") {
                            val permissions = approval.permissions as? JsonObject ?: JsonObject(emptyMap())
                            requireConnection().replyPermissionApproval(
                                approval,
                                decision,
                                CodexPermissionGrant(
                                    permissions = permissions,
                                    scope = if (decision == "acceptForSession") {
                                        CodexPermissionGrantScope.SESSION
                                    } else {
                                        CodexPermissionGrantScope.TURN
                                    },
                                ),
                            )
                        } else {
                            requireConnection().replyPermissionApproval(
                                approval,
                                decision,
                                CodexPermissionGrant(JsonObject(emptyMap())),
                            )
                        }
                    }
                    else -> requireConnection().replyApproval(approval, decision)
            }
        }
    }

    fun answerUserInput(request: CodexServerRequest, answers: Map<String, List<String>>) {
        submitRequest(request) {
            val userInput = requireNotNull(request.userInput)
            requireConnection().replyUserInput(userInput, answers)
        }
    }

    fun answerElicitation(request: CodexServerRequest, action: String, content: JsonElement? = null) {
        submitRequest(request) {
            requireConnection().reply(request, codexElicitationResponse(action, content))
        }
    }

    fun cancelRequest(request: CodexServerRequest) {
        submitRequest(request) {
            val connected = requireConnection()
            when {
                    request.approval?.kind == CodexApprovalKind.PERMISSIONS -> connected
                        .replyPermissionApproval(
                            requireNotNull(request.approval),
                            request.approval.negativeDecision(),
                            CodexPermissionGrant(JsonObject(emptyMap())),
                        )
                    request.approval != null -> connected.replyApproval(
                        requireNotNull(request.approval),
                        "cancel",
                    )
                    request.userInput != null -> connected.replyUserInput(
                        requireNotNull(request.userInput),
                        emptyMap(),
                    )
                    request.method == "mcpServer/elicitation/request" -> connected.reply(
                        request,
                        buildJsonObject { put("action", "cancel") },
                    )
                    else -> connected.replyError(request, -32601, "Unsupported request in OC Remote")
            }
        }
    }

    private fun submitRequest(request: CodexServerRequest, block: suspend () -> Unit) {
        val key = request.id.requestKey()
        while (true) {
            val current = _uiState.value
            val started = beginCodexRequestSubmission(current, key) ?: return
            if (_uiState.compareAndSet(current, started)) break
        }
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            submittingRequestKeys = it.submittingRequestKeys - key,
                            error = error.message,
                        )
                    }
                }
        }
    }

    private fun observeEvents(connected: CodexServerConnection) {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            connected.events.collect { eventState ->
                val thread = eventState.threads[threadId] ?: _uiState.value.thread
                confirmPendingSendFromThread(thread)
                val receivedAuthoritativeTurn = consumeAuthoritativeTurn(thread)
                _uiState.update {
                    it.copy(
                        thread = thread,
                        activeTurnId = thread?.turns?.lastOrNull { turn -> turn.status == "inProgress" }?.id,
                        isAwaitingAuthoritativeTurn = if (receivedAuthoritativeTurn) {
                            false
                        } else {
                            it.isAwaitingAuthoritativeTurn
                        },
                        goal = if (threadId in eventState.knownGoalThreadIds) {
                            eventState.goals[threadId]
                        } else {
                            it.goal
                        },
                        error = eventState.threadErrors[threadId],
                    )
                }
            }
        }
    }

    private fun consumeAuthoritativeTurn(thread: CodexThread?): Boolean {
        val turnIds = thread?.turns.orEmpty().mapTo(mutableSetOf()) { it.id }
        val consumed = authoritativeTurnTracker.observe(turnIds)
        if (consumed) {
            connectionManager.releaseProvisionalTurn(serverId, threadId)
            sendIdentity.confirmAuthoritative()
            clearSavedPendingSend()
            savedStateHandle.remove<ArrayList<String>>(AWAITING_TURN_BASELINE_KEY)
        }
        return consumed
    }

    private fun beginAwaitingAuthoritativeTurn() {
        val baseline = _uiState.value.thread?.turns.orEmpty().mapTo(mutableSetOf()) { it.id }
        authoritativeTurnTracker.begin(baseline)
        savedStateHandle[AWAITING_TURN_BASELINE_KEY] = ArrayList(baseline)
    }

    private fun clearAwaitingAuthoritativeTurn() {
        authoritativeTurnTracker.clear()
        savedStateHandle.remove<ArrayList<String>>(AWAITING_TURN_BASELINE_KEY)
    }

    private suspend fun confirmPendingSendFromThread(thread: CodexThread?) {
        val pending = sendIdentity.pendingConfirmation() ?: return
        if (thread?.hasClientMessage(pending.id) != true) return
        markSendAccepted(
            pending.content,
            pending.id,
            awaitAuthoritativeTurn = authoritativeTurnTracker.isAwaiting,
        )
    }

    private fun savePendingSend(content: String, id: String) {
        savedStateHandle[PENDING_SEND_CONTENT_KEY] = content
        savedStateHandle[PENDING_SEND_ID_KEY] = id
    }

    private fun clearSavedPendingSend() {
        savedStateHandle.remove<String>(PENDING_SEND_CONTENT_KEY)
        savedStateHandle.remove<String>(PENDING_SEND_ID_KEY)
        savedStateHandle.remove<Boolean>(PENDING_SEND_ACCEPTED_KEY)
    }

    private fun observePendingRequests() {
        requestsJob = viewModelScope.launch {
            connectionManager.connections.collect { connections ->
                _uiState.update { state ->
                    val pending = connections[serverId]?.pendingRequests
                        .orEmpty()
                        .filter { request -> request.params.string("threadId") == threadId }
                    val pendingKeys = pending.mapTo(mutableSetOf()) { it.id.requestKey() }
                    state.copy(
                        pendingRequests = pending,
                        submittingRequestKeys = state.submittingRequestKeys.intersect(pendingKeys),
                    )
                }
            }
        }
    }

    private fun launchAction(block: suspend (CodexAppServerClient) -> Unit) {
        viewModelScope.launch {
            runCatching { block(requireClient()) }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    private suspend fun requireClient(): CodexAppServerClient {
        val server = serverRepository.getServer(serverId) ?: error("Codex server is no longer configured")
        return acquireCurrentConnection(server).connection.client
    }

    private suspend fun requireConnection(): CodexServerConnection {
        val server = serverRepository.getServer(serverId) ?: error("Codex server is no longer configured")
        return acquireCurrentConnection(server).connection
    }

    private suspend fun acquireCurrentConnection(server: dev.minios.ocremote.domain.model.ServerConfig): CodexConnectionLease {
        return connectionMutex.withLock {
            val currentLease = lease
            if (currentLease != null && connectionManager.isCurrent(currentLease.connection)) {
                return@withLock currentLease
            }
            resetConnectionLocked()
            val acquired = connectionManager.acquire(server, threadId)
            lease = acquired
            connection = acquired.connection
            client = acquired.connection.client
            observeEvents(acquired.connection)
            acquired
        }
    }

    private fun resetConnectionLocked() {
        eventsJob?.cancel()
        eventsJob = null
        lease?.close()
        lease = null
        connection = null
        client = null
    }

    override fun onCleared() {
        setChatVisible(false)
        loadJob?.cancel()
        eventsJob?.cancel()
        lease?.close()
        lease = null
        connection = null
        client = null
        super.onCleared()
    }

    private companion object {
        const val PENDING_SEND_CONTENT_KEY = "codexPendingSendContent"
        const val PENDING_SEND_ID_KEY = "codexPendingSendId"
        const val PENDING_SEND_ACCEPTED_KEY = "codexPendingSendAccepted"
        const val AWAITING_TURN_BASELINE_KEY = "codexAwaitingTurnBaseline"
    }
}

internal fun decodeCodexRouteArg(value: String?): String =
    runCatching { URLDecoder.decode(value.orEmpty(), "UTF-8") }.getOrDefault(value.orEmpty())

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun codexElicitationResponse(action: String, content: JsonElement? = null): JsonObject =
    buildJsonObject {
        put("action", action)
        content?.let { put("content", it) }
    }

internal class CodexSendIdentityTracker(
    private val createId: () -> String,
    initialContent: String? = null,
    initialId: String? = null,
    initialAccepted: Boolean = false,
) {
    private var pendingContent: String? = initialContent
    private var pendingId: String? = initialId
    private var uncertain = initialContent != null && initialId != null && !initialAccepted
    private var acceptedAwaitingAuthoritative = initialContent != null && initialId != null && initialAccepted

    @Synchronized
    fun begin(content: String): String? {
        if (pendingId != null) return null
        return createId().also { id ->
            pendingContent = content
            pendingId = id
        }
    }

    @Synchronized
    fun markUncertain(content: String, id: String) {
        if (content == pendingContent && id == pendingId) uncertain = true
    }

    @Synchronized
    fun uncertain(): CodexPendingSend? = if (uncertain) {
        CodexPendingSend(requireNotNull(pendingContent), requireNotNull(pendingId))
    } else {
        null
    }

    @Synchronized
    fun pendingConfirmation(): CodexPendingSend? = if (uncertain || acceptedAwaitingAuthoritative) {
        CodexPendingSend(requireNotNull(pendingContent), requireNotNull(pendingId))
    } else {
        null
    }

    @Synchronized
    fun markAccepted(
        content: String,
        id: String,
        awaitAuthoritativeTurn: Boolean = false,
    ): Boolean {
        if (content != pendingContent || id != pendingId) return false
        val firstAcceptance = !acceptedAwaitingAuthoritative
        uncertain = false
        if (awaitAuthoritativeTurn) {
            acceptedAwaitingAuthoritative = true
        } else {
            clear()
        }
        return firstAcceptance
    }

    @Synchronized
    fun confirmAuthoritative() {
        if (acceptedAwaitingAuthoritative) clear()
    }

    @Synchronized
    fun markDefinitiveFailure(content: String, id: String) = clearIfMatching(content, id)

    private fun clearIfMatching(content: String, id: String) {
        if (content != pendingContent || id != pendingId) return
        clear()
    }

    private fun clear() {
        pendingContent = null
        pendingId = null
        uncertain = false
        acceptedAwaitingAuthoritative = false
    }
}

internal data class CodexPendingSend(val content: String, val id: String)

internal class CodexAuthoritativeTurnTracker(initialBaseline: Set<String>? = null) {
    private var baseline: Set<String>? = initialBaseline

    val isAwaiting: Boolean
        get() = baseline != null

    fun begin(turnIds: Set<String>) {
        baseline = turnIds
    }

    fun observe(turnIds: Set<String>): Boolean {
        val existing = baseline ?: return false
        if (turnIds.none { it !in existing }) return false
        baseline = null
        return true
    }

    fun clear() {
        baseline = null
    }
}

internal fun isUncertainCodexSendFailure(error: Throwable): Boolean =
    generateSequence(error) { cause -> cause.cause }
        .any { cause -> cause is CodexDisconnectedException || cause is IOException }

internal fun CodexThread.hasClientMessage(clientId: String): Boolean =
    turns.any { turn -> turn.items.any { item -> item.type == "userMessage" && item.clientId == clientId } }

internal fun CodexThread.hasTurnAfter(baseline: Set<String>): Boolean = turns.any { it.id !in baseline }

private fun dev.minios.ocremote.data.codex.CodexApprovalRequest.negativeDecision(): String =
    when {
        allowsDecision("cancel") -> "cancel"
        allowsDecision("decline") -> "decline"
        else -> error("Codex permission request offers no safe negative decision")
    }
