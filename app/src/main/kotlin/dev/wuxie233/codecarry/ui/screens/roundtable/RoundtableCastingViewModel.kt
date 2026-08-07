package dev.wuxie233.codecarry.ui.screens.roundtable

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.api.PiApi
import dev.wuxie233.codecarry.data.api.PiCastingTurnDto
import dev.wuxie233.codecarry.data.api.PiConnection
import dev.wuxie233.codecarry.data.api.PiLineupProposalDto
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.domain.model.ServerConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

private const val MIN_CASTING_LINEUP_SIZE = 3

enum class CastingMessageRole {
    User,
    Moderator,
}

data class CastingChatMessage(
    val role: CastingMessageRole,
    val content: String,
)

data class RoundtableCastingUiState(
    val serverName: String = "",
    val topic: String = "",
    val input: String = "",
    val castingId: String? = null,
    val status: String? = null,
    val messages: List<CastingChatMessage> = emptyList(),
    val proposal: PiLineupProposalDto? = null,
    val isLoadingServer: Boolean = true,
    val isTurnInFlight: Boolean = false,
    val isConfirming: Boolean = false,
    val error: String? = null,
    val canRetry: Boolean = false,
) {
    val canStart: Boolean = proposal?.items.orEmpty().size >= MIN_CASTING_LINEUP_SIZE && castingId != null && !isTurnInFlight && !isConfirming
    val canSend: Boolean = input.isNotBlank() && !isTurnInFlight && !isConfirming && !isLoadingServer
}

@HiltViewModel
class RoundtableCastingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val api: PiApi,
    private val serverRepository: ServerRepository,
) : ViewModel() {
    private val serverId: String = decodeRouteArg(savedStateHandle.get<String>("serverId"))
    private val initialCastingId: String = decodeRouteArg(savedStateHandle.get<String>("castingId"))
    private val initialTopic: String = decodeRouteArg(savedStateHandle.get<String>("topic")).trim()
    private var resolvedServer: ServerConfig? = null
    private var pendingRetry: PendingCastingRequest? = null

    private val _uiState = MutableStateFlow(
        RoundtableCastingUiState(serverName = context.getString(R.string.roundtable_default_name)),
    )
    val uiState: StateFlow<RoundtableCastingUiState> = _uiState.asStateFlow()

    private val _confirmedTarget = MutableSharedFlow<RoundtableChatTarget>()
    val confirmedTarget: SharedFlow<RoundtableChatTarget> = _confirmedTarget.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                resolveConnection()
                _uiState.update { state -> state.copy(isLoadingServer = false) }
                if (initialCastingId.isNotBlank()) {
                    loadCasting(initialCastingId)
                } else if (initialTopic.isNotBlank()) {
                    createCasting(initialTopic, appendUserMessage = false)
                }
            } catch (error: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isLoadingServer = false,
                        error = error.message ?: context.getString(R.string.roundtable_error_saved_server_missing),
                    )
                }
            }
        }
    }

    fun updateInput(value: String) {
        _uiState.update { state -> state.copy(input = value, error = null) }
    }

    fun sendInput() {
        val content = _uiState.value.input.trim()
        if (content.isBlank()) return
        _uiState.update { state ->
            state.copy(
                input = "",
                topic = state.topic.ifBlank { content },
                messages = state.messages + CastingChatMessage(CastingMessageRole.User, content),
                error = null,
                canRetry = false,
            )
        }
        val castingId = _uiState.value.castingId
        if (castingId == null) {
            createCasting(content, appendUserMessage = false)
        } else {
            sendCastingMessage(castingId, content)
        }
    }

    fun retry() {
        when (val request = pendingRetry) {
            is PendingCastingRequest.Create -> createCasting(request.topic, appendUserMessage = false)
            is PendingCastingRequest.Message -> sendCastingMessage(request.castingId, request.content)
            PendingCastingRequest.Confirm -> confirmCasting()
            null -> Unit
        }
    }

    fun confirmCasting() {
        val state = _uiState.value
        val castingId = state.castingId ?: return
        if (!state.canStart) return
        pendingRetry = PendingCastingRequest.Confirm
        viewModelScope.launch {
            _uiState.update { it.copy(isConfirming = true, error = null, canRetry = false) }
            try {
                val conn = resolveConnection()
                val roundtable = api.confirmCasting(conn, castingId)
                val roundtableId = roundtable.id ?: roundtable.roundId ?: error(context.getString(R.string.roundtable_casting_error_missing_roundtable))
                val server = resolvedServer ?: error(context.getString(R.string.roundtable_error_saved_server_missing))
                pendingRetry = null
                _confirmedTarget.emit(
                    RoundtableChatTarget(
                        serverUrl = server.url,
                        token = server.token.orEmpty(),
                        serverName = server.displayName.ifBlank { context.getString(R.string.roundtable_default_name) },
                        serverId = server.id,
                        roundtableId = roundtableId,
                    ),
                )
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        error = error.message ?: context.getString(R.string.roundtable_casting_error_confirm),
                        canRetry = true,
                    )
                }
            } finally {
                _uiState.update { it.copy(isConfirming = false) }
            }
        }
    }

    fun cancelCasting() {
        val castingId = _uiState.value.castingId ?: return
        viewModelScope.launch {
            runCatching { api.cancelCasting(resolveConnection(), castingId) }
        }
    }

    private suspend fun loadCasting(castingId: String) {
        val casting = api.getCasting(resolveConnection(), castingId)
        val messages = casting.messages.mapNotNull { message ->
            val role = when (message.role.lowercase()) {
                "user" -> CastingMessageRole.User
                "moderator" -> CastingMessageRole.Moderator
                else -> return@mapNotNull null
            }
            CastingChatMessage(role, message.content)
        }
        _uiState.update { state ->
            state.copy(
                topic = casting.topic,
                castingId = casting.id,
                status = casting.status,
                messages = messages,
                proposal = casting.proposal.copy(topic = casting.proposal.topic.ifBlank { casting.topic }),
                error = null,
                canRetry = false,
            )
        }
    }

    private fun createCasting(topic: String, appendUserMessage: Boolean) {
        pendingRetry = PendingCastingRequest.Create(topic)
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    topic = topic,
                    messages = if (appendUserMessage) state.messages + CastingChatMessage(CastingMessageRole.User, topic) else state.messages,
                    isTurnInFlight = true,
                    error = null,
                    canRetry = false,
                )
            }
            try {
                val turn = api.createCasting(resolveConnection(), topic)
                applyTurn(turn)
                pendingRetry = null
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        error = error.message ?: context.getString(R.string.roundtable_casting_error_create),
                        canRetry = true,
                    )
                }
            } finally {
                _uiState.update { it.copy(isTurnInFlight = false, isLoadingServer = false) }
            }
        }
    }

    private fun sendCastingMessage(castingId: String, content: String) {
        pendingRetry = PendingCastingRequest.Message(castingId, content)
        viewModelScope.launch {
            _uiState.update { it.copy(isTurnInFlight = true, error = null, canRetry = false) }
            try {
                val turn = api.sendCastingMessage(resolveConnection(), castingId, content)
                applyTurn(turn)
                pendingRetry = null
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        error = error.message ?: context.getString(R.string.roundtable_casting_error_message),
                        canRetry = true,
                    )
                }
            } finally {
                _uiState.update { it.copy(isTurnInFlight = false) }
            }
        }
    }

    private fun applyTurn(turn: PiCastingTurnDto) {
        _uiState.update { state ->
            val proposalTopic = turn.proposal.topic.takeIf { it.isNotBlank() } ?: state.topic
            state.copy(
                castingId = turn.castingId ?: state.castingId,
                status = turn.status ?: state.status,
                topic = proposalTopic,
                proposal = turn.proposal.copy(topic = proposalTopic),
                messages = state.messages + CastingChatMessage(CastingMessageRole.Moderator, turn.message),
                error = null,
                canRetry = false,
            )
        }
    }

    private suspend fun resolveConnection(): PiConnection {
        val server = serverRepository.getServer(serverId) ?: error(context.getString(R.string.roundtable_error_saved_server_missing))
        resolvedServer = server
        _uiState.update { state ->
            state.copy(
                serverName = server.displayName.ifBlank { context.getString(R.string.roundtable_default_name) },
            )
        }
        return PiConnection.from(server.url, server.token)
    }
}

private sealed class PendingCastingRequest {
    data class Create(val topic: String) : PendingCastingRequest()
    data class Message(val castingId: String, val content: String) : PendingCastingRequest()
    data object Confirm : PendingCastingRequest()
}

private fun decodeRouteArg(value: String?): String {
    val raw = value.orEmpty()
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}
