package dev.minios.ocremote.ui.screens.chat

import android.content.Context
import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.data.api.AgentInfo
import dev.minios.ocremote.data.api.CommandInfo
import dev.minios.ocremote.data.api.ModelSelection
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PiConnection
import dev.minios.ocremote.data.api.PiPersonaDto
import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.ProviderInfo
import dev.minios.ocremote.data.api.RoundtableSseEvent
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.preferences.SessionListPreferencesRepository
import dev.minios.ocremote.data.repository.DraftRepository
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.transport.PiRoundtableTransport
import dev.minios.ocremote.data.transport.PiRoundtableEventProcessor
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.transport.PiRoundtableRoom
import dev.minios.ocremote.domain.transport.PiTransportEvent
import dev.minios.ocremote.domain.transport.PiTransportEvent.AgentError
import dev.minios.ocremote.domain.transport.PiTransportEvent.AgentFallback
import dev.minios.ocremote.domain.transport.PiTransportEvent.AgentRetry
import dev.minios.ocremote.domain.transport.PiTransportEvent.AgentTurnStart
import dev.minios.ocremote.domain.transport.PiTransportEvent.AwaitingCommand
import dev.minios.ocremote.domain.transport.PiTransportEvent.AwaitingSkip
import dev.minios.ocremote.domain.transport.PiTransportEvent.MessageDelta
import dev.minios.ocremote.domain.transport.PiTransportEvent.MessageEnd
import dev.minios.ocremote.domain.transport.PiTransportEvent.RoundStart
import dev.minios.ocremote.domain.transport.TransportEvent
import dev.minios.ocremote.domain.transport.TransportMessagePart
import dev.minios.ocremote.domain.transport.TransportRoom
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import javax.inject.Inject

private const val TAG = "ChatViewModel"

private fun decodeRouteArg(value: String?): String {
    val raw = value.orEmpty()
    if (!raw.contains('%')) return raw
    var index = raw.indexOf('%')
    while (index >= 0) {
        if (index + 2 >= raw.length || !raw[index + 1].isDigitOrHex() || !raw[index + 2].isDigitOrHex()) {
            return raw
        }
        index = raw.indexOf('%', startIndex = index + 1)
    }
    return runCatching { URLDecoder.decode(raw, "UTF-8") }
        .getOrDefault(raw)
}

private fun Char.isDigitOrHex(): Boolean = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

data class ChatUiState(
    val sessionTitle: String = "",
    val serverName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val revert: Session.Revert? = null,
    val sessionStatus: SessionStatus = SessionStatus.Idle,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isSending: Boolean = false,
    val providers: List<ProviderInfo> = emptyList(),
    val hasServerModelCatalog: Boolean = false,
    val defaultModels: Map<String, String> = emptyMap(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val totalCost: Double = 0.0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "build",
    val variantNames: List<String> = emptyList(),
    val selectedVariant: String? = null,
    val commands: List<CommandInfo> = emptyList(),
    /** True when there are older messages on the server that haven't been loaded yet. */
    val hasOlderMessages: Boolean = false,
    /** True while a "load older" request is in flight. */
    val isLoadingOlder: Boolean = false,
    /** Share URL if session is shared, null otherwise. */
    val shareUrl: String? = null,
    /** Context window size of the current model (0 if unknown). */
    val contextWindow: Int = 0,
    /** Total tokens from the last assistant message with output > 0 (current context usage). */
    val lastContextTokens: Int = 0,
    val isPiRoundtable: Boolean = false,
    val roundtable: Roundtable? = null,
    val roundtableEvents: List<PiTransportEvent> = emptyList(),
    val activeRoster: List<Roundtable.RoleSummary> = emptyList(),
    val personaLibrary: List<PiPersonaDto> = emptyList(),
    val runState: RoundtableRunState = RoundtableRunState(),
)

data class RoundtableRunState(
    val cadence: RoundtableCadenceMode = RoundtableCadenceMode.ModeratorRouted,
    val awaitingCommand: PiTransportEvent.AwaitingCommand? = null,
    val awaitingSkip: PiTransportEvent.AwaitingSkip? = null,
    val roleStates: List<PiRoleRunState> = emptyList(),
)

data class PiRoleRunState(
    val personaId: String,
    val name: String,
    val role: String,
    val colorSeed: String,
    val liveState: PiRoleLiveState = PiRoleLiveState.Idle,
    val activeTurnId: String? = null,
    val retry: PiTransportEvent.AgentRetry? = null,
    val fallback: PiTransportEvent.AgentFallback? = null,
    val error: PiTransportEvent.AgentError? = null,
    val awaitingSkip: PiTransportEvent.AwaitingSkip? = null,
)

enum class PiRoleLiveState {
    Idle,
    Thinking,
    Speaking,
}

private data class PiRoundtableCatalogState(
    val commands: List<CommandInfo> = emptyList(),
    val personaLibrary: List<PiPersonaDto> = emptyList(),
)

enum class RoundtableCadenceMode(val wireName: String, val label: String) {
    ModeratorRouted("moderator_routed", "主持调度"),
    RoundRobin("round_robin", "轮流发言"),
    FreeRoundtable("free_roundtable", "自由讨论"),
    MentionReactive("mention_reactive", "点名响应"),
}

data class RevertedDraftPayload(
    val text: String,
    val attachmentUris: List<String> = emptyList(),
)

/**
 * A flattened chat message for the UI.
 * Combines Message info with its parts.
 */
data class ChatMessage(
    val message: Message,
    val parts: List<Part>
) {
    val isUser: Boolean get() = message is Message.User
    val isAssistant: Boolean get() = message is Message.Assistant
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val eventReducer: EventReducer,
    private val api: OpenCodeApi,
    private val piApi: PiApi,
    private val json: Json,
    private val draftRepository: DraftRepository,
    private val sessionListPreferencesRepository: SessionListPreferencesRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val serverUrl: String = decodeRouteArg(savedStateHandle.get<String>("serverUrl"))
    private val username: String = decodeRouteArg(savedStateHandle.get<String>("username"))
    private val password: String = decodeRouteArg(savedStateHandle.get<String>("password"))
    val serverName: String = decodeRouteArg(savedStateHandle.get<String>("serverName"))
    private val serverId: String = decodeRouteArg(savedStateHandle.get<String>("serverId"))
    val sessionId: String = decodeRouteArg(savedStateHandle.get<String>("sessionId"))
    private val routeDirectory: String? = decodeRouteArg(savedStateHandle.get<String>("directory")).takeIf { it.isNotBlank() }
    private val serverType: ServerType = runCatching {
        ServerType.valueOf(decodeRouteArg(savedStateHandle.get<String>("serverType")).ifBlank { ServerType.OPENCODE.name })
    }.getOrDefault(ServerType.OPENCODE)
    private val isPiRoundtable: Boolean = serverType == ServerType.PI_ROUNDTABLE

    private val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })
    private val piTransport: PiRoundtableTransport? = if (isPiRoundtable) {
        PiRoundtableTransport(
            server = ServerConfig(
                id = serverId,
                type = ServerType.PI_ROUNDTABLE,
                url = serverUrl,
                username = username.ifBlank { "pi" },
                password = null,
                token = password.takeIf { it.isNotBlank() },
                name = serverName.takeIf { it.isNotBlank() },
            ),
            api = piApi,
            json = json,
        )
    } else {
        null
    }

    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _isSending = MutableStateFlow(false)
    private val _allProviders = MutableStateFlow<List<ProviderInfo>>(emptyList())
    private val _providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    private val _hiddenModels = MutableStateFlow<Set<String>>(emptySet())
    private val _defaultModels = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _selectedProviderId = MutableStateFlow<String?>(null)
    private val _selectedModelId = MutableStateFlow<String?>(null)
    // Track if the model was explicitly selected by the user to avoid overwriting it with defaults/history
    private var isModelExplicitlySelected = false
    /** The directory of this session's project — sent as x-opencode-directory so the server resolves the correct project context. */
    private var sessionDirectory: String? = routeDirectory
    /** Signals when [loadSession] has finished (successfully or with error), so that terminal
     *  creation can wait for [sessionDirectory] to be populated. */
    private val sessionLoaded = CompletableDeferred<Unit>()
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    /** Pair(agentName, explicitlySelected) — using a single flow avoids race between flag and value */
    private val _selectedAgent = MutableStateFlow("build" to false)
    private val _selectedVariant = MutableStateFlow<String?>(null)
    private val _commands = MutableStateFlow<List<CommandInfo>>(emptyList())
    private var isCreatingSession = false
    private val terminalWorkspace = ServerTerminalRegistry.workspaceFor(serverId, api, conn).also {
        if (BuildConfig.DEBUG) {
            Log.d("TerminalZoom", "ChatViewModel init: workspaceId=${System.identityHashCode(it)} flowId=${System.identityHashCode(it.activeFontSizeSp)} serverId=$serverId vmId=${System.identityHashCode(this)}")
        }
    }
    val terminalTabs: StateFlow<List<TerminalTabUi>> = terminalWorkspace.tabList
    val activeTerminalTabId: StateFlow<String?> = terminalWorkspace.activeTabId
    /** Incremented on active terminal tab updates — observe to trigger recomposition. */
    val terminalVersion: StateFlow<Long> = terminalWorkspace.activeVersion
    val terminalConnected: StateFlow<Boolean> = terminalWorkspace.activeConnected
    val terminalFontSizeSp: StateFlow<Float> = terminalWorkspace.activeFontSizeSp
    val terminalEmulator: TerminalEmulator get() = terminalWorkspace.activeEmulator()

    // ============ Draft Persistence ============
    /** Draft text for the input field — survives navigation / app restart. */
    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText

    /** One-shot event: emits reverted draft payload (text + image attachments) for ChatScreen. */
    private val _revertedDraftEvent = MutableSharedFlow<RevertedDraftPayload>(extraBufferCapacity = 1)
    val revertedDraftEvent: SharedFlow<RevertedDraftPayload> = _revertedDraftEvent

    /** Draft attachment URIs (content:// URIs as strings) — survives navigation / app restart. */
    private val _draftAttachmentUris = MutableStateFlow<List<String>>(emptyList())
    val draftAttachmentUris: StateFlow<List<String>> = _draftAttachmentUris

    /** Set of file paths that have been confirmed by user selection from the popup */
    private val _confirmedFilePaths = MutableStateFlow<Set<String>>(emptySet())
    val confirmedFilePaths: StateFlow<Set<String>> = _confirmedFilePaths

    // ============ Settings (exposed for ChatScreen) ============
    val chatFontSize = settingsRepository.chatFontSize.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "medium"
    )
    val codeWordWrap = settingsRepository.codeWordWrap.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val confirmBeforeSend = settingsRepository.confirmBeforeSend.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val compactMessages = settingsRepository.compactMessages.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val collapseTools = settingsRepository.collapseTools.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val hapticFeedback = settingsRepository.hapticFeedback.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val keepScreenOn = settingsRepository.keepScreenOn.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val compressImageAttachments = settingsRepository.compressImageAttachments.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val imageAttachmentMaxLongSide = settingsRepository.imageAttachmentMaxLongSide.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1440
    )
    val imageAttachmentWebpQuality = settingsRepository.imageAttachmentWebpQuality.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 60
    )
    // ============ Pagination ============
    /** Current message limit (doubles each time user loads older messages). */
    private var currentMessageLimit = 50
    /** Whether there are more messages on the server beyond the current limit. */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** Whether a "load older" request is in flight. */
    private val _isLoadingOlder = MutableStateFlow(false)
    private val _piPersonas = MutableStateFlow<List<PiPersonaDto>>(emptyList())
    private var piEventStreamJob: Job? = null

    val uiState: StateFlow<ChatUiState> = combine(
        eventReducer.sessions,
        eventReducer.messages,
        eventReducer.parts,
        eventReducer.sessionStatuses,
        eventReducer.permissions,
        eventReducer.questions,
        eventReducer.roundtables,
        eventReducer.roundtableMessages,
        eventReducer.roundtableParts,
        eventReducer.roundtableEvents,
        _isLoading,
        _error,
        _isSending,
        _selectedProviderId,
        _selectedModelId,
        _allProviders,
        _providers,
        _defaultModels,
        _agents,
        _selectedAgent,
        _selectedVariant,
        combine(_commands, _piPersonas) { commands, piPersonas ->
            PiRoundtableCatalogState(commands = commands, personaLibrary = piPersonas)
        },
        _hasOlderMessages,
        _isLoadingOlder,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val allSessions = args[0] as List<Session>
        val allMessages = args[1] as Map<String, List<Message>>
        val allParts = args[2] as Map<String, List<Part>>
        val statuses = args[3] as Map<String, SessionStatus>
        val permissions = args[4] as Map<String, List<SseEvent.PermissionAsked>>
        val questions = args[5] as Map<String, List<SseEvent.QuestionAsked>>
        @Suppress("UNCHECKED_CAST")
        val roundtables = args[6] as Map<String, Roundtable>
        @Suppress("UNCHECKED_CAST")
        val roundtableMessages = args[7] as Map<String, List<Message>>
        @Suppress("UNCHECKED_CAST")
        val roundtableParts = args[8] as Map<String, List<Part>>
        @Suppress("UNCHECKED_CAST")
        val roundtableEvents = args[9] as Map<String, List<PiTransportEvent>>
        val loading = args[10] as Boolean
        val error = args[11] as String?
        val sending = args[12] as Boolean
        val selProviderId = args[13] as String?
        val selModelId = args[14] as String?
        val allProviders = args[15] as List<ProviderInfo>
        val providers = args[16] as List<ProviderInfo>
        val defaultModels = args[17] as Map<String, String>
        val agents = args[18] as List<AgentInfo>
        @Suppress("UNCHECKED_CAST")
        val agentSelection = args[19] as Pair<String, Boolean>
        val selectedAgent = agentSelection.first
        val isAgentExplicitlySelected = agentSelection.second
        val selectedVariant = args[20] as String?
        val catalogState = args[21] as PiRoundtableCatalogState
        val commands = catalogState.commands
        val hasOlderMessages = args[22] as Boolean
        val isLoadingOlder = args[23] as Boolean
        val piPersonas = catalogState.personaLibrary

        val session = allSessions.find { it.id == sessionId }
        val roundtable = roundtables[sessionId]
        val piEvents = roundtableEvents[sessionId].orEmpty()
        val sessionMessages = if (isPiRoundtable) {
            roundtableMessages[sessionId] ?: emptyList()
        } else {
            allMessages[sessionId] ?: emptyList()
        }
        val partsByMessage = if (isPiRoundtable) roundtableParts else allParts
        val revertState = session?.revert

        // While the REST call is still loading, suppress SSE-only messages to prevent
        // showing a flash of partial data (e.g., 1-2 messages from SSE when opening via
        // notification deep-link before the full history arrives).
        val hasUserMessage = sessionMessages.any { it is Message.User }
        val shouldSuppressPartialData = loading && sessionMessages.size < 3 && !hasUserMessage
        val chatMessages = if (shouldSuppressPartialData) {
            emptyList()
        } else {
            val sorted = sessionMessages.sortedBy { it.time.created }
            // Filter out reverted messages (at or after revert point)
            val visible = if (revertState != null) {
                sorted.filter { it.id < revertState.messageId }
            } else {
                sorted
            }
            suppressRepeatedPatchCards(visible.map { msg ->
                ChatMessage(
                    message = msg,
                    parts = partsByMessage[msg.id] ?: emptyList()
                )
            })
        }

        // Resolve model: explicit selection > last user message's model > provider default
        var effectiveProviderId = selProviderId
        var effectiveModelId = selModelId

        // If no explicit selection, try to resolve from history
        if (!isModelExplicitlySelected) {
             val lastUserWithModel = sessionMessages
                .filterIsInstance<Message.User>()
                .lastOrNull { it.model != null }
             if (lastUserWithModel?.model != null) {
                 effectiveProviderId = lastUserWithModel.model.providerId
                 effectiveModelId = lastUserWithModel.model.modelId
             } else if (effectiveModelId == null && defaultModels.isNotEmpty()) {
                 // Fallback to default if nothing in history and nothing selected
                 val entry = defaultModels.entries.first()
                 effectiveProviderId = entry.key
                 effectiveModelId = entry.value
             }
        }

        // Resolve agent from last user message if not explicitly changed
        val effectiveAgent = if (!isAgentExplicitlySelected) {
            val lastUserAgent = sessionMessages
                .filterIsInstance<Message.User>()
                .lastOrNull { it.agent != null }
                ?.agent
            lastUserAgent ?: selectedAgent
        } else {
            selectedAgent
        }

        // Keep raw state in sync so sendParts()/runShellCommand() always use the displayed value
        if (effectiveAgent != selectedAgent && !isAgentExplicitlySelected) {
            _selectedAgent.value = effectiveAgent to false
        }

        // Compute cost/token totals from assistant messages
        val assistantMessages = sessionMessages.filterIsInstance<Message.Assistant>()
        val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }
        val totalInputTokens = assistantMessages.sumOf { it.tokens?.input ?: 0 }
        val totalOutputTokens = assistantMessages.sumOf { it.tokens?.output ?: 0 }
        // Context usage: total tokens from the last assistant message with output > 0
        val lastWithOutput = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
        val lastContextTokens = lastWithOutput?.tokens?.let { t ->
            t.input + t.output + t.reasoning + t.cache.read + t.cache.write
        } ?: 0

        // Resolve available variants for the currently selected model.
        // If selected model is no longer visible (filtered out), fall back to first visible model.
        var currentModel = if (effectiveProviderId != null && effectiveModelId != null) {
            providers.find { it.id == effectiveProviderId }
                ?.models?.get(effectiveModelId)
        } else null
        if (currentModel == null) {
            val firstProvider = providers.firstOrNull()
            val firstModel = firstProvider?.models?.values?.firstOrNull()
            if (firstProvider != null && firstModel != null) {
                effectiveProviderId = firstProvider.id
                effectiveModelId = firstModel.id
                currentModel = firstModel
            }
        }

        // Keep raw model state in sync so sendParts()/runShellCommand() always use the displayed value
        if (!isModelExplicitlySelected) {
            if (_selectedProviderId.value != effectiveProviderId) {
                _selectedProviderId.value = effectiveProviderId
            }
            if (_selectedModelId.value != effectiveModelId) {
                _selectedModelId.value = effectiveModelId
            }
        }

        val availableVariants = currentModel?.variants?.keys?.toList()?.sorted() ?: emptyList()

        ChatUiState(
            sessionTitle = if (isPiRoundtable) {
                roundtable?.topic?.takeIf { it.isNotBlank() } ?: "Roundtable"
            } else {
                session?.title ?: "Chat"
            },
            serverName = serverName,
            messages = chatMessages,
            revert = revertState,
            sessionStatus = if (isPiRoundtable) SessionStatus.Idle else statuses[sessionId] ?: SessionStatus.Idle,
            pendingPermissions = if (isPiRoundtable) emptyList() else permissions[sessionId] ?: emptyList(),
            pendingQuestions = if (isPiRoundtable) emptyList() else questions[sessionId] ?: emptyList(),
            isLoading = loading,
            error = error,
            isSending = sending,
            providers = providers,
            hasServerModelCatalog = allProviders.any { it.models.isNotEmpty() },
            defaultModels = defaultModels,
            selectedProviderId = effectiveProviderId,
            selectedModelId = effectiveModelId,
            totalCost = totalCost,
            totalInputTokens = totalInputTokens,
            totalOutputTokens = totalOutputTokens,
            agents = agents.filter { it.mode != "subagent" && !it.hidden },
            selectedAgent = effectiveAgent,
            variantNames = availableVariants,
            selectedVariant = if (selectedVariant != null && selectedVariant in availableVariants) selectedVariant else null,
            commands = commands,
            hasOlderMessages = hasOlderMessages,
            isLoadingOlder = isLoadingOlder,
            shareUrl = session?.share?.url?.takeIf { it.isNotBlank() },
            contextWindow = currentModel?.limit?.context ?: 0,
            lastContextTokens = lastContextTokens,
            isPiRoundtable = isPiRoundtable,
            roundtable = roundtable,
            roundtableEvents = piEvents,
            activeRoster = resolveActiveRoster(roundtable, chatMessages, piEvents),
            personaLibrary = piPersonas,
            runState = buildRoundtableRunState(roundtable, chatMessages, piEvents),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ChatUiState()
    )

    init {
        eventReducer.setActiveSessionId(sessionId)

        // Route guard (issue #27): if sessionId is missing after URL decode,
        // surface an error state instead of triggering REST calls with an empty sessionId.
        if (sessionId.isBlank()) {
            Log.w(TAG, "ChatViewModel constructed with blank sessionId; entering error state")
            _isLoading.value = false
            _error.value = "Invalid session"
        } else if (isPiRoundtable) {
            sessionLoaded.complete(Unit)
            viewModelScope.launch {
                settingsRepository.terminalFontSize.collect { size ->
                    terminalWorkspace.setDefaultFontSize(size)
                }
            }
            loadPiRoundtable()
        } else {
            viewModelScope.launch {
                sessionListPreferencesRepository.markMainSessionRead(sessionId)
            }

            // Restore draft from disk
            val draft = draftRepository.getDraft(sessionId)
            if (draft != null) {
                _draftText.value = draft.text
                _draftAttachmentUris.value = draft.imageUris
                if (draft.confirmedFilePaths.isNotEmpty()) {
                    _confirmedFilePaths.value = draft.confirmedFilePaths.toSet()
                }
                if (!draft.selectedAgent.isNullOrBlank()) {
                    _selectedAgent.value = draft.selectedAgent to true
                }
                if (!draft.selectedVariant.isNullOrBlank()) {
                    _selectedVariant.value = draft.selectedVariant
                }
            }

            viewModelScope.launch {
                settingsRepository.hiddenModels(serverId).collect { hidden ->
                    _hiddenModels.value = hidden
                    applyProviderFilter()
                }
            }

            viewModelScope.launch {
                settingsRepository.terminalFontSize.collect { size ->
                    terminalWorkspace.setDefaultFontSize(size)
                }
            }

            // Load initial message count from settings, then load data
            viewModelScope.launch {
                currentMessageLimit = settingsRepository.initialMessageCount.first()
                loadSession()
                loadMessages()
                loadPendingQuestions()
            }
            loadProviders()
            loadAgents()
            loadCommands()
        }

    }

    fun loadPiRoundtable() {
        val transport = piTransport ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val piConnection = PiConnection.from(serverUrl, password.takeIf { it.isNotBlank() })
                val rooms = transport.listRooms().mapNotNull { room ->
                    (room as? TransportRoom.Pi)?.room?.toRoundtable()
                }
                _piPersonas.value = piApi.listPersonas(piConnection).filter { it.enabled }
                eventReducer.setRoundtables(serverId, rooms)
                val transcript = transport.getTranscript(sessionId)
                processPiTranscript(transcript.events)
                startPiRoundtableEventStream(transport, transcript.events)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load Pi roundtable", error)
                _error.value = error.message ?: "Failed to load roundtable"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun processPiTranscript(events: List<RoundtableSseEvent>) {
        val processor = PiRoundtableEventProcessor(json)
        processor.processSnapshot(events).forEach { event ->
            eventReducer.processEvent(TransportEvent.Pi(event), serverId)
        }
    }

    private fun startPiRoundtableEventStream(transport: PiRoundtableTransport, replayedEvents: List<RoundtableSseEvent>) {
        piEventStreamJob?.cancel()
        val lastSeenEventId = replayedEvents.maxOfOrNull { event -> event.eventId }
        piEventStreamJob = viewModelScope.launch {
            try {
                transport.openRoundtableEventStream(
                    roundId = sessionId,
                    lastSeenEventId = lastSeenEventId,
                    replayedEvents = replayedEvents,
                ).collect { event ->
                    eventReducer.processEvent(event, serverId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to stream Pi roundtable events", error)
                _error.value = error.message ?: "Failed to stream roundtable"
            }
        }
    }

    /** Load the session info to get its directory for correct project context. */
    private suspend fun loadSession() {
        try {
            val session = api.getSession(conn, sessionId, directory = sessionDirectory)
            if (session.directory.isNotBlank()) {
                sessionDirectory = session.directory
                if (BuildConfig.DEBUG) Log.d(TAG, "Session directory: ${session.directory}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session info", e)
        } finally {
            sessionLoaded.complete(Unit)
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val messages = api.listMessages(
                    conn = conn,
                    sessionId = sessionId,
                    limit = currentMessageLimit,
                    directory = sessionDirectory,
                )
                eventReducer.setMessages(sessionId, messages)
                // If we got exactly the limit, there are likely more messages on the server
                _hasOlderMessages.value = messages.size >= currentMessageLimit
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${messages.size} messages for session $sessionId (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load messages", e)
                // On OOM or other memory errors, retry with a smaller limit
                if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                    Log.w(TAG, "OOM loading messages, retrying with smaller limit")
                    currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                    try {
                        val messages = api.listMessages(
                            conn = conn,
                            sessionId = sessionId,
                            limit = currentMessageLimit,
                            directory = sessionDirectory,
                        )
                        eventReducer.setMessages(sessionId, messages)
                        _hasOlderMessages.value = messages.size >= currentMessageLimit
                        if (BuildConfig.DEBUG) Log.d(TAG, "Retry succeeded: loaded ${messages.size} messages (limit=$currentMessageLimit)")
                    } catch (retryEx: Exception) {
                        Log.e(TAG, "Retry also failed", retryEx)
                        _error.value = retryEx.message ?: "Failed to load messages"
                    }
                } else {
                    _error.value = e.message ?: "Failed to load messages"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load older messages by doubling the limit and reloading.
     * The server returns the N most recent messages, so we simply request more.
     */
    fun loadOlderMessages() {
        viewModelScope.launch {
            _isLoadingOlder.value = true
            currentMessageLimit *= 2
            try {
                val messages = api.listMessages(
                    conn = conn,
                    sessionId = sessionId,
                    limit = currentMessageLimit,
                    directory = sessionDirectory,
                )
                eventReducer.setMessages(sessionId, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded older: ${messages.size} messages (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load older messages", e)
                // Roll back the limit on failure
                currentMessageLimit /= 2
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }

    /**
     * Load pending questions from the server REST API.
     * Converts QuestionRequest DTOs to SseEvent.QuestionAsked domain objects.
     * Must be called after loadSession() so sessionDirectory is set.
     */
    private suspend fun loadPendingQuestions() {
        try {
            val allQuestions = api.listPendingQuestions(conn, directory = sessionDirectory)
            if (BuildConfig.DEBUG) Log.d(TAG, "loadPendingQuestions: ${allQuestions.size} total pending (directory=$sessionDirectory), filtering for session $sessionId")
            val sessionQuestions = allQuestions
                .filter { it.sessionId == sessionId }
                .map { req ->
                    SseEvent.QuestionAsked(
                        id = req.id,
                        sessionId = req.sessionId,
                        questions = req.questions.map { q ->
                            SseEvent.QuestionAsked.Question(
                                header = q.header,
                                question = q.question,
                                multiple = q.multiple,
                                custom = q.custom,
                                options = q.options.map { o ->
                                    SseEvent.QuestionAsked.Option(
                                        label = o.label,
                                        description = o.description
                                    )
                                }
                            )
                        },
                        tool = req.tool
                    )
                }
            if (sessionQuestions.isNotEmpty()) {
                eventReducer.setQuestions(sessionId, sessionQuestions)
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessionQuestions.size} pending questions for session $sessionId")
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "No pending questions for session $sessionId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending questions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // Removed initModelFromMessages as it's handled reactively

    private fun loadProviders() {
        viewModelScope.launch {
            try {
                val response = api.getProviders(conn)
                _allProviders.value = response.providers
                applyProviderFilter()
                _defaultModels.value = response.default
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${response.providers.size} providers, defaults: ${response.default}")
                // No need to set default here, combine block handles fallback
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load providers", e)
            }
        }
    }

    private fun applyProviderFilter() {
        val hidden = _hiddenModels.value
        val filtered = _allProviders.value
            .map { provider ->
                provider.copy(
                    models = provider.models.filterKeys { modelId ->
                        "${provider.id}:$modelId" !in hidden
                    }
                )
            }
            .filter { it.models.isNotEmpty() }
        _providers.value = filtered
    }

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                val agents = api.listAgents(conn)
                _agents.value = agents
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${agents.size} agents: ${agents.map { it.name }}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load agents", e)
            }
        }
    }

    fun selectAgent(name: String) {
        _selectedAgent.value = name to true
        saveDraft()
    }

    fun selectVariant(name: String?) {
        _selectedVariant.value = name
        saveDraft()
    }

    private fun loadCommands() {
        viewModelScope.launch {
            try {
                val commands = api.listCommands(conn)
                _commands.value = commands
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${commands.size} commands: ${commands.map { it.name }}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load commands", e)
            }
        }
    }

    /**
     * Cycle through available thinking effort variants for the current model.
     * Cycles: none -> first -> second -> ... -> last -> none (default).
     */
    fun cycleVariant() {
        val state = uiState.value
        val variants = state.variantNames
        if (variants.isEmpty()) return
        val current = _selectedVariant.value
        if (current == null || current !in variants) {
            _selectedVariant.value = variants.first()
        } else {
            val idx = variants.indexOf(current)
            _selectedVariant.value = if (idx == variants.lastIndex) null else variants[idx + 1]
        }
        saveDraft()
    }

    fun selectModel(providerId: String, modelId: String) {
        _selectedProviderId.value = providerId
        _selectedModelId.value = modelId
        isModelExplicitlySelected = true
    }

    // ============ @ File Mention Search ============

    /** File search results for @-autocomplete */
    private val _fileSearchResults = MutableStateFlow<List<String>>(emptyList())
    val fileSearchResults: StateFlow<List<String>> = _fileSearchResults

    /** Debounce job for file search */
    private var fileSearchJob: Job? = null

    /** Search files and directories for @-mention autocomplete. Debounced by 200ms. */
    fun searchFilesForMention(query: String) {
        fileSearchJob?.cancel()
        if (query.isEmpty()) {
            // Show recent/top files immediately with no debounce
            fileSearchJob = viewModelScope.launch {
                try {
                    val results = api.findFiles(
                        conn = conn,
                        query = "",
                        dirs = "true",
                        directory = sessionDirectory,
                        limit = 15
                    )
                    _fileSearchResults.value = results
                } catch (e: Exception) {
                    Log.e(TAG, "File search failed", e)
                    _fileSearchResults.value = emptyList()
                }
            }
            return
        }
        fileSearchJob = viewModelScope.launch {
            delay(150) // debounce
            try {
                val results = api.findFiles(
                    conn = conn,
                    query = query,
                    dirs = "true",
                    directory = sessionDirectory,
                    limit = 15
                )
                _fileSearchResults.value = results
            } catch (e: Exception) {
                Log.e(TAG, "File search failed for query '$query'", e)
                _fileSearchResults.value = emptyList()
            }
        }
    }

    /** Add a confirmed file path (user selected it from the popup) */
    fun confirmFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value + path
    }

    /** Remove a confirmed file path */
    fun removeFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value - path
    }

    /** Clear file search results (e.g. when popup is closed) */
    fun clearFileSearch() {
        fileSearchJob?.cancel()
        _fileSearchResults.value = emptyList()
    }

    /** Clear confirmed file paths (e.g. after sending a message) */
    fun clearConfirmedPaths() {
        _confirmedFilePaths.value = emptySet()
    }

    // ============ Draft Management ============

    /** Update the draft text (called on every keystroke). */
    fun updateDraftText(text: String) {
        _draftText.value = text
    }

    /** Add an attachment URI to the draft. */
    fun addDraftAttachment(uri: String) {
        _draftAttachmentUris.value = _draftAttachmentUris.value + uri
    }

    /** Remove an attachment URI from the draft by index. */
    fun removeDraftAttachment(index: Int) {
        val current = _draftAttachmentUris.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _draftAttachmentUris.value = current
        }
    }

    /** Clear all draft state (called after sending a message). */
    fun clearDraft() {
        _draftText.value = ""
        _draftAttachmentUris.value = emptyList()
        draftRepository.clearDraft(sessionId)
    }

    /** Persist current draft to disk. */
    private fun saveDraft() {
        val agentPair = _selectedAgent.value
        val draft = dev.minios.ocremote.data.repository.Draft(
            text = _draftText.value,
            imageUris = _draftAttachmentUris.value,
            confirmedFilePaths = _confirmedFilePaths.value.toList(),
            selectedAgent = agentPair.first.takeIf { agentPair.second },
            selectedVariant = _selectedVariant.value
        )
        draftRepository.saveDraft(sessionId, draft)
    }

    override fun onCleared() {
        eventReducer.clearActiveSessionId(sessionId)
        piEventStreamJob?.cancel()
        closeTerminalSession()
        super.onCleared()
        saveDraft()
    }

    /** Get the session directory for building file:// URLs */
    fun getSessionDirectory(): String? = sessionDirectory

    fun sendMessage(text: String, attachments: List<PromptPart> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        val parts = mutableListOf<PromptPart>()
        if (text.isNotBlank()) {
            parts.add(PromptPart(type = "text", text = text))
        }
        parts.addAll(attachments)
        sendParts(parts)
    }

    /** Send pre-built prompt parts (used when @-file mentions need structured parts). */
    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>) {
        val parts = promptParts + attachments
        if (parts.isEmpty()) return
        sendParts(parts)
    }

    private fun sendParts(parts: List<PromptPart>) {
        viewModelScope.launch {
            _isSending.value = true
            try {
                if (isPiRoundtable) {
                    val roundtableText = parts.joinToString(separator = "\n") { part ->
                        part.text ?: part.url ?: part.path ?: part.filename ?: ""
                    }.trim()
                    val transport = piTransport ?: error("Pi roundtable transport is unavailable")
                    transport.sendMessage(
                        roomId = sessionId,
                        parts = parts.map { part ->
                            TransportMessagePart(
                                type = part.type,
                                text = part.text,
                                path = part.path,
                                mime = part.mime,
                                url = part.url,
                                filename = part.filename,
                            )
                        },
                        directory = sessionDirectory,
                    )
                    eventReducer.appendRoundtableUserMessage(sessionId, roundtableText)
                    _error.value = null
                    return@launch
                }
                val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
                    ModelSelection(
                        providerId = _selectedProviderId.value!!,
                        modelId = _selectedModelId.value!!
                    )
                } else null

                api.promptAsync(
                    conn = conn,
                    sessionId = sessionId,
                    parts = parts,
                    model = model,
                    agent = uiState.value.selectedAgent,
                    variant = _selectedVariant.value,
                    directory = sessionDirectory
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $sessionId (${parts.size} parts)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                _error.value = roundtableErrorMessage(e, "Failed to send message")
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendRoundtableCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit = {}) {
        val transport = piTransport
        if (transport == null || command.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val ok = try {
                transport.sendCommand(
                    roomId = sessionId,
                    command = command,
                    arguments = arguments,
                    directory = sessionDirectory,
                ).also { accepted ->
                    if (accepted) _error.value = null
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to send roundtable command $command", error)
                _error.value = roundtableErrorMessage(error, "Failed to send roundtable command")
                false
            }
            onResult(ok)
        }
    }

    fun switchRoundtableCadence(cadence: RoundtableCadenceMode, onResult: (Boolean) -> Unit = {}) {
        sendRoundtableCommand("switch_cadence", cadence.wireName, onResult)
    }

    fun continueRoundtable(onResult: (Boolean) -> Unit = {}) {
        if (uiState.value.roundtable?.status != Roundtable.Status.AwaitingCommand) {
            onResult(false)
            return
        }
        sendRoundtableCommand("可", onResult = onResult)
    }

    fun stopRoundtable(onResult: (Boolean) -> Unit = {}) {
        sendRoundtableCommand("止", onResult = onResult)
    }

    fun deepenRoundtableSection(onResult: (Boolean) -> Unit = {}) {
        sendRoundtableCommand("深入", onResult = onResult)
    }

    fun mentionRoundtableRole(roleId: String, content: String, onResult: (Boolean) -> Unit = {}) {
        val arguments = listOf(roleId.trim(), content.trim()).filter { it.isNotBlank() }.joinToString(" ")
        sendRoundtableCommand("@mention", arguments, onResult)
    }

    fun injectAsParticipant(content: String, onResult: (Boolean) -> Unit = {}) {
        sendRoundtableCommand("inject", content.trim(), onResult)
    }

    /**
     * Add a user-authored supplement (explanation or guidance) for the whole table.
     * Product-facing wrapper that reuses the existing inject command path so the
     * roundtable receives the note as supplemental user content.
     */
    fun supplementRoundtableGuidance(content: String, onResult: (Boolean) -> Unit = {}) {
        injectAsParticipant(content, onResult)
    }

    fun introducePersona(personaId: String, onResult: (Boolean) -> Unit = {}) {
        sendRoundtableCommand("引入新人物", personaId.trim(), onResult)
    }

    fun skipAwaitingPersona(onResult: (Boolean) -> Unit = {}) {
        val awaitingSkip = uiState.value.runState.awaitingSkip
        if (awaitingSkip == null) {
            onResult(false)
            return
        }
        sendRoundtableCommand(awaitingSkip.skipCommand.ifBlank { "skip" }, awaitingSkip.personaId, onResult)
    }

    fun roundtableRoundIndexes(): List<Int> {
        return uiState.value.roundtableEvents
            .filterIsInstance<RoundStart>()
            .mapIndexed { index, _ -> index + 1 }
    }

    private fun roundtableErrorMessage(error: Exception, fallback: String): String {
        val message = error.message
        return if (isPiRoundtable && message?.isRoundtableTranscriptFullError() == true) {
            appContext.getString(R.string.chat_pi_transcript_full)
        } else {
            message ?: fallback
        }
    }

    /**
     * Reply to a permission request.
     * @param requestId The permission request ID
     * @param reply One of: "once", "always", "reject"
     */
    fun replyToPermission(requestId: String, reply: String) {
        viewModelScope.launch {
            try {
                api.replyToPermission(
                    conn = conn,
                    requestId = requestId,
                    reply = reply,
                    directory = sessionDirectory
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "Replied to permission $requestId with $reply")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply to permission", e)
            }
        }
    }

    fun abortSession() {
        viewModelScope.launch {
            val outcome: AbortOutcome = try {
                val ok = api.abortSession(conn, sessionId, directory = sessionDirectory)
                if (ok) AbortOutcome.Success else AbortOutcome.Unsuccessful
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
                AbortOutcome.Failed(e)
            }
            handleAbortResult(
                outcome = outcome,
                onIdle = {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                    eventReducer.updateSessionStatus(sessionId, SessionStatus.Idle)
                },
                onError = { message ->
                    _error.value = message
                },
            )
        }
    }

    /**
     * Reply to a question request.
     * @param requestId The question request ID
     * @param answers Answers for each question (list of selected labels per question)
     */
    fun replyToQuestion(requestId: String, answers: List<List<String>>) {
        viewModelScope.launch {
            try {
                val success = api.replyToQuestion(
                    conn = conn,
                    requestId = requestId,
                    answers = answers,
                    directory = sessionDirectory
                )
                if (success) {
                    // Optimistically remove the question card — SSE event may arrive late or not at all
                    eventReducer.removeQuestion(requestId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply to question $requestId: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * Reject a question request.
     */
    fun rejectQuestion(requestId: String) {
        viewModelScope.launch {
            try {
                val success = api.rejectQuestion(conn = conn, requestId = requestId, directory = sessionDirectory)
                if (success) {
                    // Optimistically remove the question card
                    eventReducer.removeQuestion(requestId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject question $requestId: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    // ============ Slash Command Actions ============

    /** Share the current session. Returns the share URL or null on failure. */
    fun shareSession(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val session = api.shareSession(conn, sessionId)
                val url = session.share?.url
                if (BuildConfig.DEBUG) Log.d(TAG, "Shared session $sessionId: $url")
                onResult(url)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to share session", e)
                onResult(null)
            }
        }
    }

    fun unshareSession(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                api.unshareSession(conn, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Unshared session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unshare session", e)
                onResult(false)
            }
        }
    }

    /** Compact (summarize) the current session. */
    fun compactSession(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val state = uiState.value
                val providerId = state.selectedProviderId
                val modelId = state.selectedModelId
                if (providerId == null || modelId == null) {
                    Log.e(TAG, "Cannot compact: no model selected")
                    onResult(false)
                    return@launch
                }
                api.summarizeSession(conn, sessionId, providerId, modelId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Compacted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compact session", e)
                onResult(false)
            }
        }
    }

    /**
     * Export the session as JSON directly to a file URI.
     * Streams API responses directly to the output stream to avoid OOM
     * on large sessions (messages can be 80+ MB).
     * Shows a notification with download progress.
     */
    fun exportSession(context: android.content.Context, uri: android.net.Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "opencode_export"
            val notificationId = 9999

            // Create notification channel
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    context.getString(R.string.menu_export_session),
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_export_progress)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.menu_export_session))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)

            try {
                Log.d(TAG, "exportSession: streaming to $uri")
                notificationManager.notify(notificationId, builder.build())

                var lastNotifyTime = 0L
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    api.exportSessionToStream(conn, sessionId, outputStream) { bytesWritten ->
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyTime > 500) { // throttle to 2 updates/sec
                            lastNotifyTime = now
                            val mb = String.format("%.1f MB", bytesWritten / 1_000_000.0)
                            builder.setContentText(mb)
                            notificationManager.notify(notificationId, builder.build())
                        }
                    }
                }

                Log.d(TAG, "exportSession: done")
                notificationManager.cancel(notificationId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export session", e)
                notificationManager.cancel(notificationId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false)
                }
            }
        }
    }

    /** Undo the last user message in the session, restoring its text to the input field. */
    fun undoMessage(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // Find the last user message (before any existing revert point)
                val messages = uiState.value.messages
                val lastUser = messages.lastOrNull { it.isUser }
                if (lastUser == null) {
                    onResult(false)
                    return@launch
                }
                api.revertSession(conn, sessionId, lastUser.message.id)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message ${lastUser.message.id}")
                // Restore the user message text to the input field
                restoreRevertedDraft(extractRevertedDraft(lastUser))
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert session", e)
                onResult(false)
            }
        }
    }

    /** Revert to a specific user message by ID, optionally restoring its text to the input field. */
    fun revertMessage(messageId: String, revertedText: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                api.revertSession(conn, sessionId, messageId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message $messageId")
                val targetMessage = uiState.value.messages
                    .lastOrNull { it.message.id == messageId && it.isUser }
                val fallbackPayload = RevertedDraftPayload(text = revertedText.orEmpty())
                restoreRevertedDraft(targetMessage?.let { extractRevertedDraft(it) } ?: fallbackPayload)
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert to message $messageId", e)
                onResult(false)
            }
        }
    }

    private fun extractRevertedDraft(message: ChatMessage): RevertedDraftPayload {
        val revertedText = message.parts
            .filterIsInstance<Part.Text>()
            .joinToString("\n") { it.text }

        val imageUris = message.parts
            .filterIsInstance<Part.File>()
            .mapNotNull { part ->
                val mime = part.mime.lowercase()
                if (mime.startsWith("image/") && !part.url.isNullOrBlank()) part.url else null
            }

        return RevertedDraftPayload(
            text = revertedText,
            attachmentUris = imageUris,
        )
    }

    private fun restoreRevertedDraft(payload: RevertedDraftPayload) {
        _draftText.value = payload.text
        _draftAttachmentUris.value = payload.attachmentUris
        _confirmedFilePaths.value = emptySet()
        _revertedDraftEvent.tryEmit(payload)
    }

    /** Redo the last undone message. */
    fun redoMessage(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                api.unrevertSession(conn, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Unreverted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unrevert session", e)
                onResult(false)
            }
        }
    }

    /**
     * Fork the current session.
     *
     * Inherits the source session's project directory via the existing
     * `x-opencode-directory` header so the forked session lands in the same
     * project rather than the server's root context. The returned session is
     * merged into [eventReducer] so the session list updates immediately,
     * mirroring [createNewSession].
     *
     * Returns the new session or `null` on failure.
     */
    fun forkSession(onResult: (Session?) -> Unit) {
        forkSession(messageId = null, onResult = onResult)
    }

    /** Fork the current session from a specific message. */
    fun forkSessionFromMessage(messageId: String, onResult: (Session?) -> Unit) {
        forkSession(messageId = messageId, onResult = onResult)
    }

    private fun forkSession(messageId: String?, onResult: (Session?) -> Unit) {
        viewModelScope.launch {
            try {
                if (!sessionLoaded.isCompleted) {
                    sessionLoaded.await()
                }
                val effectiveDirectory = ForkDirectoryResolver.resolve(
                    sessionDirectory = sessionDirectory,
                    sessionId = sessionId,
                    reducerSessions = eventReducer.sessions.value,
                )
                if (effectiveDirectory == null) {
                    Log.w(
                        TAG,
                        "Forking session $sessionId without directory context — server will use its default project"
                    )
                }
                val session = api.forkSession(
                    conn = conn,
                    sessionId = sessionId,
                    messageId = messageId,
                    directory = effectiveDirectory,
                )
                eventReducer.setSessions(serverId, listOf(session))
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Forked session $sessionId -> ${session.id} (directory=$effectiveDirectory)"
                    )
                }
                onResult(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fork session", e)
                onResult(null)
            }
        }
    }

    /** Rename the current session. */
    fun renameSession(title: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                api.updateSession(conn, sessionId, title)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to $title")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                onResult(false)
            }
        }
    }

    /** Execute a server-side command (e.g. /init, /review, MCP commands). */
    fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                if (!sessionLoaded.isCompleted) {
                    sessionLoaded.await()
                }
                if (sessionDirectory.isNullOrBlank()) {
                    loadSession()
                }

                val normalizedCommand = command.removePrefix("/").trim()
                val effectiveDirectory = sessionDirectory
                    ?: eventReducer.sessions.value
                        .firstOrNull { it.id == sessionId }
                        ?.directory
                        ?.takeIf { it.isNotBlank() }
                // /init: when arguments are omitted, rely on x-opencode-directory only.
                // Passing an explicit path (absolute or ".") can lead to duplicated or
                // malformed path text in the generated init prompt.
                val effectiveArguments = if (
                    normalizedCommand.equals("init", ignoreCase = true) && arguments.isBlank()
                ) {
                    ""
                } else {
                    arguments
                }

                val ok = api.executeCommand(
                    conn = conn,
                    sessionId = sessionId,
                    command = normalizedCommand,
                    arguments = effectiveArguments,
                    directory = effectiveDirectory
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Executed command /$normalizedCommand in session $sessionId: $ok (directory=$effectiveDirectory, arguments=$effectiveArguments)"
                    )
                }
                onResult(ok)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute command /$command", e)
                onResult(false)
            }
        }
    }

    /** Execute shell command in current session. */
    fun runShellCommand(command: String, onResult: (Boolean) -> Unit) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
                    ModelSelection(
                        providerId = _selectedProviderId.value!!,
                        modelId = _selectedModelId.value!!
                    )
                } else null
                val ok = api.runShellCommand(
                    conn = conn,
                    sessionId = sessionId,
                    command = trimmed,
                    agent = uiState.value.selectedAgent,
                    model = model,
                    directory = sessionDirectory
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "Executed shell command in session $sessionId: $ok")
                onResult(ok)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute shell command", e)
                onResult(false)
            }
        }
    }

    fun openTerminalSession(onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            // Wait for loadSession() to finish so sessionDirectory is populated.
            // This prevents the race condition where the PTY is created with directory=null
            // and then resize is attempted with the real directory.
            sessionLoaded.await()
            if (BuildConfig.DEBUG) Log.d(TAG, "openTerminalSession: sessionDirectory=$sessionDirectory")
            terminalWorkspace.ensureActiveTab(cwd = sessionDirectory, directory = sessionDirectory, onResult = onResult)
        }
    }

    fun createTerminalTab(onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            sessionLoaded.await()
            terminalWorkspace.createTab(cwd = sessionDirectory, directory = sessionDirectory, onResult = onResult)
        }
    }

    fun switchTerminalTab(tabId: String) {
        terminalWorkspace.switchTab(tabId)
    }

    fun closeTerminalTab(tabId: String) {
        terminalWorkspace.closeTab(tabId)
    }

    fun reconnectTerminalTab(tabId: String, onResult: (Boolean) -> Unit = {}) {
        terminalWorkspace.reconnectTab(tabId, onResult)
    }

    fun setTerminalFontSize(fontSizeSp: Float) {
        terminalWorkspace.setActiveFontSize(fontSizeSp)
    }

    fun sendTerminalInput(input: String) {
        terminalWorkspace.sendActiveInput(input)
    }

    fun clearTerminalBuffer() {
        terminalWorkspace.clearActiveBuffer()
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        terminalWorkspace.resizeActive(cols, rows)
    }

    fun closeTerminalSession() {
        // Global terminal workspaces are server-scoped and survive chat screen changes.
    }

    /** Create a new session and return it. */
    fun createNewSession(onResult: (Session?) -> Unit) {
        if (isCreatingSession) {
            onResult(null)
            return
        }
        isCreatingSession = true
        viewModelScope.launch {
            try {
                if (!sessionLoaded.isCompleted) {
                    sessionLoaded.await()
                }
                val requestedDirectory = sessionDirectory
                val session = api.createSession(conn, directory = requestedDirectory)
                val normalizedSession = if (session.directory.isBlank() && !requestedDirectory.isNullOrBlank()) {
                    session.copy(directory = requestedDirectory)
                } else {
                    session
                }
                eventReducer.setSessions(serverId, listOf(normalizedSession))
                if (BuildConfig.DEBUG) Log.d(TAG, "Created new session: ${normalizedSession.id}")
                onResult(normalizedSession)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                onResult(null)
            } finally {
                isCreatingSession = false
            }
        }
    }

    /** Connection parameters for navigation to other sessions. */
    fun getConnectionParams(): ConnectionParams = ConnectionParams(
        serverUrl = serverUrl,
        username = username,
        password = password,
        serverName = serverName,
        serverId = serverId
    )

    /** Get the last assistant message text for copying. */
    fun getLastAssistantText(): String? {
        val msgs = uiState.value.messages
        val last = msgs.lastOrNull { it.isAssistant } ?: return null
        return last.parts
            .filterIsInstance<Part.Text>()
            .joinToString("") { it.text }
            .ifBlank { null }
    }
}

private fun PiRoundtableRoom.toRoundtable(): Roundtable = Roundtable(
    id = id,
    topic = topic ?: title,
    status = status.toRoundtableStatus(),
    roundCount = roundCount,
    rosterSummary = roster.joinToString(", ") { role -> role.name },
    roster = roster,
    time = Roundtable.Time(
        created = createdAt,
        updated = updatedAt,
        completed = archivedAt,
    ),
)

private fun String?.toRoundtableStatus(): Roundtable.Status = when (this?.lowercase()) {
    "running" -> Roundtable.Status.Running
    "paused", "awaiting_command" -> Roundtable.Status.AwaitingCommand
    "awaiting", "awaiting_skip" -> Roundtable.Status.AwaitingSkip
    "ended", "completed", "cancelled" -> Roundtable.Status.Completed
    "archived" -> Roundtable.Status.Archived
    "error" -> Roundtable.Status.Error
    else -> Roundtable.Status.Unknown
}

private fun String.isRoundtableTranscriptFullError(): Boolean {
    val normalized = lowercase()
    return normalized.contains("maxtranscriptbytes") ||
        normalized.contains("injected content would exceed")
}

private fun resolveActiveRoster(
    roundtable: Roundtable?,
    messages: List<ChatMessage>,
    events: List<PiTransportEvent>,
): List<Roundtable.RoleSummary> {
    val roster = linkedMapOf<String, Roundtable.RoleSummary>()
    roundtable?.roster.orEmpty().forEach { role -> roster[role.id] = role }

    messages.mapNotNull { chatMessage -> chatMessage.message as? Message.Assistant }
        .forEach { assistant ->
            val id = assistant.senderId?.takeIf { it.isNotBlank() } ?: return@forEach
            roster.putIfAbsent(
                id,
                Roundtable.RoleSummary(
                    id = id,
                    name = assistant.senderName?.takeIf { it.isNotBlank() } ?: id,
                    role = assistant.senderRole?.takeIf { it.isNotBlank() } ?: "persona",
                    colorSeed = assistant.colorSeed?.takeIf { it.isNotBlank() } ?: id,
                ),
            )
        }

    events.forEach { event ->
        val author = event.envelope.author
        if (author.id.isNotBlank()) {
            roster.putIfAbsent(
                author.id,
                Roundtable.RoleSummary(
                    id = author.id,
                    name = author.name.ifBlank { author.id },
                    role = author.role.ifBlank { "persona" },
                    colorSeed = author.colorSeed.ifBlank { author.id },
                ),
            )
        }
    }
    return roster.values.filterNot { it.role.equals("moderator", ignoreCase = true) }
}

private fun buildRoundtableRunState(
    roundtable: Roundtable?,
    messages: List<ChatMessage>,
    events: List<PiTransportEvent>,
): RoundtableRunState {
    val latestAwaitingCommand = events.filterIsInstance<AwaitingCommand>().maxByOrNull { it.envelope.sequence }
    val latestAwaitingSkip = events.filterIsInstance<AwaitingSkip>().maxByOrNull { it.envelope.sequence }
    val latestRoundStart = events.filterIsInstance<RoundStart>().maxByOrNull { it.envelope.sequence }
    val activeRoster = resolveActiveRoster(roundtable, messages, events)

    val retryByPersona = events.filterIsInstance<AgentRetry>().latestByPersona { it.personaId }
    val fallbackByPersona = events.filterIsInstance<AgentFallback>().latestByPersona { it.personaId }
    val errorByPersona = events.filterIsInstance<AgentError>().latestByPersona { it.personaId }
    val skipByPersona = events.filterIsInstance<AwaitingSkip>().latestByPersona { it.personaId }
    val closedTurnIds = events.mapNotNull { event ->
        when (event) {
            is MessageEnd,
            is AgentError,
            is AwaitingSkip -> event.envelope.turnId
            else -> null
        }
    }.toSet()
    val speakingTurnIds = events.filterIsInstance<MessageDelta>()
        .mapNotNull { event -> event.envelope.turnId }
        .toSet()
    val activeTurnByPersona = events.filterIsInstance<AgentTurnStart>()
        .filter { event -> event.envelope.turnId != null && event.envelope.turnId !in closedTurnIds }
        .latestByPersona { event -> event.personaId.ifBlank { event.envelope.author.id } }
    val personaIds = (activeRoster.map { it.id } + retryByPersona.keys + fallbackByPersona.keys + errorByPersona.keys + skipByPersona.keys + activeTurnByPersona.keys)
        .distinct()

    val rolesById = activeRoster.associateBy { it.id }
    val roleStates = personaIds.map { personaId ->
        val role = rolesById[personaId]
        val activeTurn = activeTurnByPersona[personaId]
        val activeTurnId = activeTurn?.envelope?.turnId
        PiRoleRunState(
            personaId = personaId,
            name = role?.name ?: personaId,
            role = role?.role ?: "persona",
            colorSeed = role?.colorSeed ?: personaId,
            liveState = when {
                activeTurnId == null -> PiRoleLiveState.Idle
                activeTurnId in speakingTurnIds -> PiRoleLiveState.Speaking
                else -> PiRoleLiveState.Thinking
            },
            activeTurnId = activeTurnId,
            retry = retryByPersona[personaId],
            fallback = fallbackByPersona[personaId],
            error = errorByPersona[personaId],
            awaitingSkip = skipByPersona[personaId],
        )
    }

    return RoundtableRunState(
        cadence = latestRoundStart?.speakerPolicy?.mode?.toRoundtableCadenceMode() ?: RoundtableCadenceMode.ModeratorRouted,
        awaitingCommand = latestAwaitingCommand,
        awaitingSkip = latestAwaitingSkip,
        roleStates = roleStates,
    )
}

private inline fun <T : PiTransportEvent> List<T>.latestByPersona(personaId: (T) -> String): Map<String, T> {
    return groupBy(personaId).mapValues { (_, events) -> events.maxBy { it.envelope.sequence } }
}

private fun String.toRoundtableCadenceMode(): RoundtableCadenceMode =
    RoundtableCadenceMode.entries.firstOrNull { it.wireName == this } ?: RoundtableCadenceMode.ModeratorRouted

/** Holds server connection info for navigation purposes. */
data class ConnectionParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
)
