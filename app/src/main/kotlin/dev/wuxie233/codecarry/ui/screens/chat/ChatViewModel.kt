package dev.wuxie233.codecarry.ui.screens.chat

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.wuxie233.codecarry.BuildConfig
import dev.wuxie233.codecarry.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wuxie233.codecarry.data.api.AgentInfo
import dev.wuxie233.codecarry.data.api.CommandInfo
import dev.wuxie233.codecarry.data.api.ModelSelection
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.api.PromptPart
import dev.wuxie233.codecarry.data.api.ProviderInfo
import dev.wuxie233.codecarry.data.api.ProviderModel
import dev.wuxie233.codecarry.data.api.ModelCapabilities
import dev.wuxie233.codecarry.data.api.ModelLimit
import dev.wuxie233.codecarry.data.api.toPermissionAsked
import dev.wuxie233.codecarry.data.api.ServerConnection
import dev.wuxie233.codecarry.data.dsh.DshModelSelection
import dev.wuxie233.codecarry.data.dsh.dshProjectedModelSelection
import dev.wuxie233.codecarry.data.dsh.isCurrentDshModelReceipt
import dev.wuxie233.codecarry.data.dsh.DshAgentPresetEntry
import dev.wuxie233.codecarry.data.dsh.observeServerConnection
import dev.wuxie233.codecarry.data.dsh.canSelectDshPreset
import dev.wuxie233.codecarry.data.dsh.DshApiClient
import dev.wuxie233.codecarry.data.dsh.DshConnection
import dev.wuxie233.codecarry.data.dsh.DshConnectionManager
import dev.wuxie233.codecarry.data.dsh.DshEventState
import dev.wuxie233.codecarry.data.dsh.DshFollowFrame
import dev.wuxie233.codecarry.data.dsh.DshGenerationStatus
import dev.wuxie233.codecarry.data.dsh.DshSessionAddress
import dev.wuxie233.codecarry.data.dsh.historyAddress
import dev.wuxie233.codecarry.data.dsh.connectDshConversation
import dev.wuxie233.codecarry.data.dsh.DshQuestionAnswer
import dev.wuxie233.codecarry.data.dsh.DshRpcError
import dev.wuxie233.codecarry.data.dsh.DshRpcException
import dev.wuxie233.codecarry.data.dsh.DshRpcResult
import dev.wuxie233.codecarry.data.dsh.dshQuestionAnswer
import dev.wuxie233.codecarry.data.dsh.dshMessageSeq
import dev.wuxie233.codecarry.data.dsh.dshPromptRequest
import dev.wuxie233.codecarry.data.dsh.dshQueueItemText
import dev.wuxie233.codecarry.data.dsh.DshHistoryFolder
import dev.wuxie233.codecarry.data.dsh.mapDshApproval
import dev.wuxie233.codecarry.data.dsh.mapDshEventStateToSessions
import dev.wuxie233.codecarry.data.dsh.mapDshQuestion
import dev.wuxie233.codecarry.data.dsh.toSessionEvent
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.DraftRepository
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.*
import dev.wuxie233.codecarry.service.ForegroundResumeDispatcher
import dev.wuxie233.codecarry.service.dismissResponseReadyNotification
import kotlinx.coroutines.flow.update
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.util.UUID
import javax.inject.Inject

private const val TAG = "ChatViewModel"
private const val DSH_ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024

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
    val pendingSendCount: Int = 0,
    val pendingSendError: String? = null,
    val isRetryingNow: Boolean = false,
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
    val subagents: List<ChatSubagentItem> = emptyList(),
    val supportsAttachments: Boolean = false,
    val supportsPrompt: Boolean = false,
    val supportsAbort: Boolean = false,
    val supportsModelSelection: Boolean = false,
    val supportsThinkingSelection: Boolean = false,
    val supportsCompact: Boolean = false,
    val supportsFork: Boolean = false,
    val supportsRestore: Boolean = true,
    val supportsCommands: Boolean = false,
    val supportsRename: Boolean = false,
    val supportsSessionCreate: Boolean = false,
    val isDsh: Boolean = false,
    val queuedPrompts: List<ChatQueueItem> = emptyList(),
    val permissionAlwaysAvailable: Boolean = true,
    val supportsShell: Boolean = false,
    val supportsTerminal: Boolean = false,
    val questionUnlockEpoch: Int = 0,
)

data class DshChatPresetState(
    val presets: List<DshAgentPresetEntry> = emptyList(),
    val currentId: String? = null,
    val loading: Boolean = false,
    val selecting: Boolean = false,
    val ready: Boolean = false,
    val canSelect: Boolean = false,
    val error: String? = null,
)

data class ChatQueueItem(
    val id: String,
    val placement: String,
    val text: String,
)

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

internal fun reuseStableChatMessages(
    previous: List<ChatMessage>,
    next: List<ChatMessage>,
): List<ChatMessage> {
    if (previous === next) return previous
    if (next.isEmpty()) return next
    val previousById = previous.associateBy { it.message.id }
    var changed = previous.size != next.size
    val reused = ArrayList<ChatMessage>(next.size)
    for (candidate in next) {
        val existing = previousById[candidate.message.id]
        val keep = if (existing != null && existing.message == candidate.message && existing.parts == candidate.parts) {
            existing
        } else {
            changed = true
            candidate
        }
        reused += keep
    }
    return if (changed) reused else previous
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val eventReducer: EventReducer,
    private val api: OpenCodeApi,
    private val json: Json,
    private val draftRepository: DraftRepository,
    private val sessionListPreferencesRepository: SessionListPreferencesRepository,
    private val settingsRepository: SettingsRepository,
    private val dshApi: DshApiClient,
    private val dshConnectionManager: DshConnectionManager,
    private val foregroundResumeDispatcher: ForegroundResumeDispatcher,
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
    private val tokenArg: String = decodeRouteArg(savedStateHandle.get<String>("token") ?: "")
    private val isDsh: Boolean = serverType == ServerType.DSH
    private val dshCapabilities = chatBackendCapabilities(serverType)

    private val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })
    private val dshConn = DshConnection.from(serverUrl, password.ifEmpty { null }, tokenArg.ifEmpty { null })
    private val dshReducer = dshConnectionManager.reducer(serverId)
    private val foregroundResumeListener: () -> Unit = {
        if (sessionId.isNotBlank()) refreshOpenSessionOnForeground()
    }

    private val _isLoading = MutableStateFlow(true)
    private val _dshPresets = MutableStateFlow(DshChatPresetState())
    val dshPresets: StateFlow<DshChatPresetState> = _dshPresets
    private var dshPresetLoadJob: Job? = null
    private var dshSessionLoadJob: Job? = null
    private var dshModelCatalogDefault: DshModelSelection? = null
    private var dshModelCatalogGeneration: Long? = null
    private var dshModelMutationRevision = 0L
    private var dshModelMutationPending = false
    private var dshModelObservedGeneration: Long? = null
    private var dshModelObservedProjection: Pair<Long, kotlinx.serialization.json.JsonElement?>? = null
    private val _error = MutableStateFlow<String?>(null)
    private val _isSending = MutableStateFlow(false)
    private val _pendingSendCount = MutableStateFlow(0)
    private val _pendingSendError = MutableStateFlow<String?>(null)
    private val _isRetryingNow = MutableStateFlow(false)
    private val _retryNowFailureEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val retryNowFailureEvent: SharedFlow<Unit> = _retryNowFailureEvent
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
    private data class PendingOpenCodeSend(
        val parts: List<PromptPart>,
        val model: ModelSelection?,
        val agent: String,
        val variant: String?,
    )
    private val pendingOpenCodeSends = ArrayDeque<PendingOpenCodeSend>()
    private var pendingOpenCodeDrain: Job? = null
    private val dshResolvedAttachments = mutableMapOf<String, String>()
    private var dshAttachmentJob: Job? = null
    private var dshFollowJob: Job? = null
    private var dshFollowGeneration: Long = -1
    private var dshFollowAddress: DshSessionAddress? = null
    /** Last resolved address for this Chat; survives reducer reset so a child is not followed as `kind: session`. */
    private var dshKnownAddress: DshSessionAddress? = null
    private var dshAppliedEventSeq: Long = -1
    private val dshHistoryFolder = DshHistoryFolder()
    private var lastBuiltChatMessages: List<ChatMessage> = emptyList()
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
    private val _dshQueueTick = MutableStateFlow(0L)
    private val _questionUnlockEpoch = MutableStateFlow(0)
    val uiState: StateFlow<ChatUiState> = combine(
        listOf(
            eventReducer.serverSessionDetails,
            eventReducer.messages,
            eventReducer.parts,
            eventReducer.sessionStatuses,
            eventReducer.permissionsByServer,
            eventReducer.questionsByServer,
            _isLoading,
            _error,
            _isSending,
            _pendingSendCount,
            _pendingSendError,
            _isRetryingNow,
            _selectedProviderId,
            _selectedModelId,
            _allProviders,
            _providers,
            _defaultModels,
            _agents,
            _selectedAgent,
            _selectedVariant,
            _commands,
            _hasOlderMessages,
            _isLoadingOlder,
            _dshQueueTick,
            _questionUnlockEpoch,
        )
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val sessionsByServer = args[0] as Map<String, Map<String, Session>>
        val currentServerSessions = sessionsByServer[serverId].orEmpty()
        val allSessions = currentServerSessions.values.toList()
        val currentServerSessionIds = currentServerSessions.keys
        val allMessages = args[1] as Map<String, List<Message>>
        val allParts = args[2] as Map<String, List<Part>>
        val statuses = args[3] as Map<String, SessionStatus>
        val permissionsByServer = args[4] as Map<String, Map<String, List<SseEvent.PermissionAsked>>>
        val questionsByServer = args[5] as Map<String, Map<String, List<SseEvent.QuestionAsked>>>
        val permissions = permissionsByServer[serverId].orEmpty()
        val questions = questionsByServer[serverId].orEmpty()
        val loading = args[6] as Boolean
        val error = args[7] as String?
        val sending = args[8] as Boolean
        val pendingSendCount = args[9] as Int
        val pendingSendError = args[10] as String?
        val retryingNow = args[11] as Boolean
        val selProviderId = args[12] as String?
        val selModelId = args[13] as String?
        val allProviders = args[14] as List<ProviderInfo>
        val providers = args[15] as List<ProviderInfo>
        val defaultModels = args[16] as Map<String, String>
        val agents = args[17] as List<AgentInfo>
        @Suppress("UNCHECKED_CAST")
        val agentSelection = args[18] as Pair<String, Boolean>
        val selectedAgent = agentSelection.first
        val isAgentExplicitlySelected = agentSelection.second
        val selectedVariant = args[19] as String?
        val commands = args[20] as List<CommandInfo>
        val hasOlderMessages = args[21] as Boolean
        val isLoadingOlder = args[22] as Boolean
        @Suppress("UNUSED_VARIABLE")
        val dshQueueTick = args[23] as Long
        val questionUnlockEpoch = args[24] as Int

        val session = allSessions.find { it.id == sessionId && it.id in currentServerSessionIds }
        val subagents = buildDirectChatSubagents(
            parentSessionId = sessionId,
            sessions = allSessions,
            allowedSessionIds = currentServerSessionIds,
            statuses = statuses,
            questions = questions,
            permissions = permissions,
        )
        val sessionMessages = allMessages[sessionId] ?: emptyList()
        val partsByMessage = allParts
        val revertState = session?.revert

        val chatMessages = run {
            val sorted = sessionMessages.sortedWith(compareBy<Message> { it.time.created }.thenBy { it.id })
            val visible = if (revertState != null) {
                sorted.filter { it.id < revertState.messageId }
            } else {
                sorted
            }
            reuseStableChatMessages(
                lastBuiltChatMessages,
                suppressRepeatedPatchCards(visible.map { msg ->
                    ChatMessage(
                        message = msg,
                        parts = partsByMessage[msg.id] ?: emptyList()
                    )
                }),
            ).also { lastBuiltChatMessages = it }
        }

        var effectiveProviderId = selProviderId
        var effectiveModelId = selModelId

        if (!isModelExplicitlySelected) {
             val lastUserWithModel = sessionMessages
                .filterIsInstance<Message.User>()
                .lastOrNull { it.model != null }
             if (lastUserWithModel?.model != null) {
                 effectiveProviderId = lastUserWithModel.model.providerId
                 effectiveModelId = lastUserWithModel.model.modelId
             } else if (effectiveModelId == null && defaultModels.isNotEmpty()) {
                 val entry = defaultModels.entries.first()
                 effectiveProviderId = entry.key
                 effectiveModelId = entry.value
             }
        }

        val effectiveAgent = if (!isAgentExplicitlySelected) {
            val lastUserAgent = sessionMessages
                .filterIsInstance<Message.User>()
                .lastOrNull { it.agent != null }
                ?.agent
            lastUserAgent ?: selectedAgent
        } else {
            selectedAgent
        }

        if (effectiveAgent != selectedAgent && !isAgentExplicitlySelected) {
            _selectedAgent.value = effectiveAgent to false
        }

        val assistantMessages = sessionMessages.filterIsInstance<Message.Assistant>()
        val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }
        val totalInputTokens = assistantMessages.sumOf { it.tokens?.input ?: 0 }
        val totalOutputTokens = assistantMessages.sumOf { it.tokens?.output ?: 0 }
        val lastWithOutput = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
        val lastContextTokens = lastWithOutput?.tokens?.let { t ->
            t.input + t.output + t.reasoning + t.cache.read + t.cache.write
        } ?: 0

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
            sessionTitle = session?.title ?: "Chat",
            serverName = serverName,
            messages = chatMessages,
            revert = revertState,
            sessionStatus = statuses[sessionId] ?: SessionStatus.Idle,
            pendingPermissions = permissions[sessionId] ?: emptyList(),
            pendingQuestions = questions[sessionId] ?: emptyList(),
            isLoading = loading,
            error = error,
            isSending = sending,
            pendingSendCount = pendingSendCount,
            pendingSendError = pendingSendError,
            isRetryingNow = retryingNow,
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
            subagents = subagents,
            supportsAttachments = true,
            supportsPrompt = true,
            supportsAbort = true,
            supportsModelSelection = true,
            supportsThinkingSelection = true,
            supportsCompact = !isDsh,
            supportsFork = true,
            supportsRestore = !isDsh,
            supportsCommands = true,
            supportsRename = true,
            supportsSessionCreate = true,
            isDsh = isDsh,
            queuedPrompts = if (isDsh) {
                dshReducer.state.value.sessions[sessionId]?.queue.orEmpty().map { item ->
                    ChatQueueItem(id = item.id, placement = item.placement, text = dshQueueItemText(item))
                }
            } else {
                emptyList()
            },
            permissionAlwaysAvailable = !isDsh,
            supportsShell = dshCapabilities.shellAndTerminal,
            supportsTerminal = dshCapabilities.shellAndTerminal,
            questionUnlockEpoch = questionUnlockEpoch,
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
        } else {
            viewModelScope.launch {
                sessionListPreferencesRepository.markMainSessionRead(sessionId)
                dismissResponseReadyNotification(appContext, serverId, sessionId)
            }

            // Restore draft from disk
            restoreDraft()

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
                if (isDsh) {
                    observeDshState()
                } else {
                    if (routeDirectory != null) loadMessages()
                    loadSession()
                }
            }
            if (isDsh) {
                loadDshProviders()
            } else {
                loadProviders()
                loadAgents()
                loadCommands()
            }
            foregroundResumeDispatcher.addListener(foregroundResumeListener)
        }

    }

    private fun observeDshState() {
        viewModelScope.launch {
            dshReducer.state.collect { state ->
                applyDshState(state)
            }
        }
        viewModelScope.launch {
            dshConnectionManager.states.observeServerConnection(serverId).collect { generation ->
                val ready = generation?.status == DshGenerationStatus.Ready && generation.isReady
                _dshPresets.update { it.copy(ready = ready, canSelect = canSelectDshPreset(
                    dshReducer.state.value.sessions[sessionId], ready, sending = false,
                )) }
                if (ready) {
                    refreshDshPresets()
                    dshSessionLoadJob?.cancel()
                    dshSessionLoadJob = launch { loadDshSession() }
                } else {
                    dshSessionLoadJob?.cancel()
                    dshPresetLoadJob?.cancel()
                    _dshPresets.update { it.copy(loading = false, presets = emptyList()) }
                }
            }
        }
    }

    private fun dshHistoryAddress(): DshSessionAddress? {
        val snapshot = dshReducer.state.value.sessions[sessionId]
        val resolved = snapshot?.historyAddress()
        when (resolved) {
            is DshSessionAddress.Subagent -> {
                dshKnownAddress = resolved
                return resolved
            }
            is DshSessionAddress.Session -> {
                val known = dshKnownAddress
                if (known is DshSessionAddress.Subagent) return known
                dshKnownAddress = resolved
                return resolved
            }
            null -> {
                if (snapshot?.origin == "subagent") return null
                return dshKnownAddress
            }
        }
    }

    private fun startDshFollow(force: Boolean = false): Boolean {
        if (!isDsh || sessionId.isBlank()) return false
        val generation = dshConnectionManager.states.value[serverId] ?: return false
        if (!generation.isReady) return false
        val address = dshHistoryAddress() ?: return false
        if (!force &&
            dshFollowJob?.isActive == true &&
            dshFollowGeneration == generation.generation &&
            dshFollowAddress == address
        ) {
            return true
        }
        dshFollowJob?.cancel()
        dshFollowGeneration = generation.generation
        dshFollowAddress = address
        dshFollowJob = viewModelScope.launch {
            try {
                _error.value = null
                dshConnectionManager.openSessionFollow(
                    serverId = serverId,
                    address = address,
                    maxMessages = currentMessageLimit,
                ).collect { frame ->
                    when (frame) {
                        is DshFollowFrame.Snapshot -> {
                            dshReducer.applyFollowSnapshot(sessionId, frame)
                            _hasOlderMessages.value = frame.hasMore
                            applyDshState(dshReducer.state.value)
                            _isLoading.value = false
                        }
                        is DshFollowFrame.FollowEvent -> {
                            dshReducer.applyFollowEvent(sessionId, frame.event)
                            applyDshState(dshReducer.state.value)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "DSH session follow failed", e)
                _error.value = e.message ?: "Failed to load messages"
                _isLoading.value = false
                val diedGeneration = dshFollowGeneration
                val stillReady = dshConnectionManager.states.value[serverId]
                if (stillReady?.isReady == true && stillReady.generation == diedGeneration) {
                    delay(1_000)
                    val current = dshConnectionManager.states.value[serverId]
                    if (current?.isReady == true && current.generation == diedGeneration) {
                        startDshFollow(force = true)
                    }
                }
            }
        }
        return true
    }

    private fun applyDshState(state: DshEventState) {
        val mapped = mapDshEventStateToSessions(state)
        eventReducer.replaceSessions(serverId, mapped.sessions)
        mapped.statuses.forEach { (id, status) ->
            eventReducer.updateSessionStatus(id, status)
        }
        val snapshot = state.sessions[sessionId]
        applyDshProjectedModel()
        _dshPresets.update { it.copy(
            currentId = snapshot?.agentPreset,
            canSelect = canSelectDshPreset(snapshot, it.ready, sending = false),
        ) }
        val address = dshHistoryAddress()
        if (address != null && dshFollowAddress != address) {
            startDshFollow()
        }
        if (snapshot != null) {
            val folded = dshHistoryFolder.fold(sessionId, snapshot.events)
            val lastSeq = snapshot.events.maxOfOrNull { it.seq } ?: -1L
            eventReducer.setMessages(sessionId, applyCachedDshAttachments(folded))
            sessionDirectory = snapshot.cwd?.takeIf { it.isNotBlank() } ?: sessionDirectory
            dshAppliedEventSeq = lastSeq
            resolveMissingDshAttachments(folded, lastSeq)
        }
        eventReducer.setPermissions(
            serverId,
            sessionId,
            state.pendingApprovalsFor(sessionId).map(::mapDshApproval),
        )
        eventReducer.setQuestions(
            serverId,
            sessionId,
            state.pendingQuestionsFor(sessionId).map(::mapDshQuestion),
        )
        val queueTick = snapshot?.queue.orEmpty().hashCode().toLong()
        if (_dshQueueTick.value != queueTick) {
            _dshQueueTick.value = queueTick
        }
    }

    private suspend fun loadDshSession() {
        try {
            val list = dshApi.sessionList(dshConn)
            dshReducer.applySessionList(list.items)
            val current = list.items.firstOrNull { it.sessionId == sessionId }
            sessionDirectory = current?.cwd?.takeIf { it.isNotBlank() } ?: routeDirectory ?: sessionDirectory
            startDshFollow()
            loadDshProviders()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load DSH session", e)
            _error.value = e.message ?: "Failed to load session"
            _isLoading.value = false
        } finally {
            sessionLoaded.complete(Unit)
        }
    }

    private suspend fun loadDshOlderHistory() {
        val snapshot = dshReducer.state.value.sessions[sessionId]
        val address = snapshot?.historyAddress() ?: return
        val throughSeq = snapshot.pageThroughSeq ?: return
        val beforeSeq = snapshot.events.minOfOrNull { it.seq } ?: return
        try {
            val history = dshApi.sessionHistory(
                connection = dshConn,
                address = address,
                throughSeq = throughSeq,
                beforeSeq = beforeSeq,
                maxMessages = currentMessageLimit,
            )
            dshReducer.mergeHistory(
                sessionId = sessionId,
                events = history.events.map { it.event.toSessionEvent() },
                projections = history.projections,
                replace = false,
            )
            if (!history.hasMore) {
                _hasOlderMessages.value = false
            }
            applyDshState(dshReducer.state.value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load older DSH history", e)
        }
    }

    private fun applyCachedDshAttachments(messages: List<MessageWithParts>): List<MessageWithParts> {
        if (dshResolvedAttachments.isEmpty()) return messages
        return messages.map { message ->
            val parts = message.parts.map { part ->
                val file = part as? Part.File ?: return@map part
                val attachmentId = dshAttachmentId(file.url) ?: return@map part
                val cached = dshResolvedAttachments[attachmentId] ?: return@map part
                file.copy(url = cached)
            }
            message.copy(parts = parts)
        }
    }

    private fun resolveMissingDshAttachments(messages: List<MessageWithParts>, foldSeq: Long) {
        val missing = messages.flatMap { it.parts }.mapNotNull { part ->
            dshAttachmentId((part as? Part.File)?.url)
        }.filter { it !in dshResolvedAttachments }.distinct()
        if (missing.isEmpty()) return
        if (dshAttachmentJob?.isActive == true) return
        dshAttachmentJob = viewModelScope.launch {
            missing.forEach { attachmentId ->
                runCatching {
                    val fetched = dshApi.sessionAttachment(dshConn, sessionId, attachmentId)
                    if (fetched.data.length * 3 / 4 <= DSH_ATTACHMENT_MAX_BYTES) {
                        dshResolvedAttachments[attachmentId] =
                            "data:${fetched.attachment.mediaType};base64,${fetched.data}"
                    }
                }
            }
            val current = dshReducer.state.value.sessions[sessionId] ?: return@launch
            val currentSeq = current.events.maxOfOrNull { it.seq } ?: -1L
            if (currentSeq < foldSeq) return@launch
            eventReducer.setMessages(sessionId, applyCachedDshAttachments(dshHistoryFolder.fold(sessionId, current.events)))
            dshAppliedEventSeq = currentSeq
        }
    }

    private fun dshAttachmentId(url: String?): String? {
        val prefix = "dsh-attachment:"
        if (url.isNullOrBlank() || !url.startsWith(prefix)) return null
        return url.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    fun refreshDshPresets() {
        if (!isDsh || _dshPresets.value.selecting) return
        dshPresetLoadJob?.cancel()
        val connection = dshConnectionManager.states.value[serverId]
        if (connection?.status != DshGenerationStatus.Ready || !connection.isReady) {
            _dshPresets.update { it.copy(loading = false, ready = false, presets = emptyList()) }
            return
        }
        val generation = connection.generation
        _dshPresets.update { it.copy(loading = true, error = null) }
        dshPresetLoadJob = viewModelScope.launch {
            try {
                // Session state already arrives through session/control and session/follow.
                // Do not hold the roster behind an unrelated full session/list read.
                val presets = dshApi.agentPresetList(dshConn).presets
                val current = dshConnectionManager.states.value[serverId]
                if (current?.generation == generation && current.status == DshGenerationStatus.Ready && current.isReady) {
                    _dshPresets.update { it.copy(presets = presets, loading = false) }
                } else {
                    _dshPresets.update { it.copy(loading = false, presets = emptyList(), error = appContext.getString(R.string.dsh_preset_connection_changed)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _dshPresets.update { it.copy(loading = false, error = error.message ?: appContext.getString(R.string.dsh_preset_failed)) }
            }
        }
    }

    fun selectDshPreset(presetId: String, onResult: (Boolean) -> Unit = {}) {
        val state = _dshPresets.value
        val snapshot = dshReducer.state.value.sessions[sessionId]
        val generationState = dshConnectionManager.states.value[serverId]
        val ready = generationState?.status == DshGenerationStatus.Ready && generationState.isReady
        if (!isDsh || state.selecting || state.loading || dshModelMutationPending || !canSelectDshPreset(snapshot, ready, _isSending.value || pendingOpenCodeSends.isNotEmpty()) ||
            state.presets.none { it.id == presetId && it.broken == null }) {
            _dshPresets.update { it.copy(error = appContext.getString(R.string.dsh_preset_idle_required)) }
            onResult(false)
            return
        }
        if (snapshot?.agentPreset == presetId) {
            onResult(true)
            return
        }
        val generation = dshReducer.state.value.generation
        dshModelMutationRevision++
        _dshPresets.update { it.copy(selecting = true, error = null) }
        viewModelScope.launch {
            try {
                val selected = dshApi.agentPresetSelect(dshConn, sessionId, presetId)
                if (dshReducer.state.value.generation != generation ||
                    dshConnectionManager.states.value[serverId]?.status != DshGenerationStatus.Ready) {
                    _dshPresets.update { it.copy(error = appContext.getString(R.string.dsh_preset_connection_changed)) }
                    onResult(false)
                } else {
                    dshReducer.applyPresetSelection(sessionId, selected.agentPreset, generation)
                    loadDshProviders(refreshSelection = true)
                    onResult(true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _dshPresets.update { it.copy(error = error.message ?: appContext.getString(R.string.dsh_preset_failed)) }
                onResult(false)
            } finally {
                _dshPresets.update { it.copy(selecting = false) }
            }
        }
    }

    private fun applyDshModelSelection(selected: DshModelSelection) {
        _selectedProviderId.value = selected.provider
        _selectedModelId.value = selected.model
        _selectedVariant.value = selected.reasoningEffort
        isModelExplicitlySelected = true
    }

    private fun applyDshProjectedModel() {
        val state = dshReducer.state.value
        if (dshModelObservedGeneration != state.generation) {
            dshModelObservedGeneration = state.generation
            dshModelObservedProjection = null
            dshModelMutationPending = false
        }
        if (dshModelMutationPending) return
        val projection = state.sessions[sessionId]?.projections?.get("modelSelection") ?: return
        if (projection == dshModelObservedProjection) return
        val selected = dshProjectedModelSelection(
            projection.second,
            dshModelCatalogDefault.takeIf { dshModelCatalogGeneration == state.generation },
        ) ?: return
        dshModelObservedProjection = projection
        applyDshModelSelection(selected)
    }

    private fun submitDshModelSelection(providerId: String, modelId: String, reasoning: String?) {
        val generation = dshReducer.state.value.generation
        val revision = ++dshModelMutationRevision
        dshModelObservedGeneration = generation
        dshModelMutationPending = true
        viewModelScope.launch {
            try {
                val result = dshApi.sessionSelectModel(dshConn, sessionId, providerId, modelId, reasoning)
                if (isCurrentDshModelReceipt(generation, revision, dshReducer.state.value.generation,
                        dshModelMutationRevision, dshConnectionManager.states.value[serverId]?.status == DshGenerationStatus.Ready)) {
                    dshModelObservedProjection = dshReducer.state.value.sessions[sessionId]?.projections?.get("modelSelection")
                    applyDshModelSelection(result.selected)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == dshReducer.state.value.generation && revision == dshModelMutationRevision) {
                    _error.value = error.message ?: appContext.getString(R.string.dsh_model_selection_failed)
                    dshModelObservedProjection = null
                }
            } finally {
                if (generation == dshReducer.state.value.generation && revision == dshModelMutationRevision) {
                    dshModelMutationPending = false
                    applyDshProjectedModel()
                }
            }
        }
    }

    private fun loadDshProviders(refreshSelection: Boolean = false) {
        val generation = dshReducer.state.value.generation
        val revision = dshModelMutationRevision
        viewModelScope.launch {
            try {
                val models = dshApi.sessionModels(dshConn)
                if (generation != dshReducer.state.value.generation) return@launch
                val providers = models.groups.map { group ->
                    ProviderInfo(
                        id = group.id,
                        name = group.name,
                        models = group.models.associate { model ->
                            model.id to ProviderModel(
                                id = model.id,
                                providerId = group.id,
                                name = model.name,
                                variants = model.reasoning?.efforts?.associate { effort ->
                                    effort.id to JsonPrimitive(effort.name)
                                },
                            )
                        },
                    )
                }
                _allProviders.value = providers
                applyProviderFilter()
                dshModelCatalogDefault = models.default
                dshModelCatalogGeneration = generation
                // A catalog refresh never overwrites a session/user selection.
                applyDshProjectedModel()
                if (refreshSelection) {
                    val current = dshApi.sessionList(dshConn).items.firstOrNull { it.sessionId == sessionId }
                    if (isCurrentDshModelReceipt(generation, revision, dshReducer.state.value.generation,
                            dshModelMutationRevision, dshConnectionManager.states.value[serverId]?.status == DshGenerationStatus.Ready)) {
                        current?.projections?.let { projections ->
                            dshReducer.mergeHistory(sessionId, events = emptyList(), projections = projections, replace = false)
                        }
                        applyDshProjectedModel()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load DSH models", e)
            }
        }
    }

    private fun restoreDraft() {
        val draft = draftRepository.getDraft(sessionId) ?: return
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







    private suspend fun loadSession() {
        var resolvedDirectory = routeDirectory
        try {
            val session = api.getSession(conn, sessionId, directory = sessionDirectory)
            if (session.directory.isNotBlank()) {
                sessionDirectory = session.directory
                resolvedDirectory = session.directory
                if (BuildConfig.DEBUG) Log.d(TAG, "Session directory: ${session.directory}")
            }
            if (_pendingSendError.value == null) drainPendingOpenCodeSends()
            if (routeDirectory == null) loadMessages()
            viewModelScope.launch { loadPendingQuestions() }
            viewModelScope.launch { loadPendingPermissions() }
            viewModelScope.launch { loadSessionStatus() }
            if (!resolvedDirectory.isNullOrBlank()) {
                val projectSessions = api.listSessions(
                    conn = conn,
                    directory = sessionDirectory,
                    rootsOnly = false,
                )
                eventReducer.setSessions(serverId, projectSessions)
            } else if (pendingOpenCodeSends.isNotEmpty()) {
                val message = "Session directory is unavailable. Retry to send queued messages."
                _error.value = message
                _pendingSendError.value = message
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session info", e)
            if (sessionDirectory.isNullOrBlank()) {
                _error.value = e.message ?: "Failed to resolve session directory"
                _pendingSendError.value = _error.value
            }
        } finally {
            sessionLoaded.complete(Unit)
        }
    }

    fun loadMessages() {
        if (isDsh) {
            val generation = dshConnectionManager.states.value[serverId]
            if (generation?.isReady != true) {
                _isLoading.value = false
                _error.value = generation?.error ?: "DSH is not connected"
                return
            }
            _error.value = null
            _isLoading.value = true
            startDshFollow(force = true)
            return
        }
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
                eventReducer.mergeMessages(sessionId, messages)
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
                        eventReducer.mergeMessages(sessionId, messages)
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
        if (isDsh) {
            viewModelScope.launch {
                _isLoadingOlder.value = true
                loadDshOlderHistory()
                _isLoadingOlder.value = false
            }
            return
        }
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
                eventReducer.mergeMessages(sessionId, messages)
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
                eventReducer.setQuestions(serverId, sessionId, sessionQuestions)
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessionQuestions.size} pending questions for session $sessionId")
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "No pending questions for session $sessionId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending questions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Load pending permission requests for this session from REST on open, so a
     * permission asked before the chat opened is visible without waiting for an SSE push.
     * Merges additively (never wipes a permission that arrived live via SSE). Mirrors
     * [loadPendingQuestions]; must run after loadSession() so sessionDirectory is set.
     */
    private suspend fun loadPendingPermissions() {
        try {
            val sessionPermissions = api.listPendingPermissions(conn, directory = sessionDirectory)
                .filter { it.sessionId == sessionId }
                .map { it.toPermissionAsked() }
            eventReducer.mergePermissions(serverId, sessionId, sessionPermissions)
            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessionPermissions.size} pending permission(s) for session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending permissions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Surface this session's current status (retry/cooldown/busy) from REST on open, so the
     * status banner reflects an in-progress retry immediately instead of waiting for an SSE push.
     * Only applies a non-idle status; clearing a stale status is the connection service's job.
     */
    private suspend fun loadSessionStatus() {
        try {
            val status = api.getSessionStatuses(conn, directory = sessionDirectory)[sessionId] ?: return
            eventReducer.updateSessionStatus(sessionId, status)
            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded status $status for session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session status: ${e.javaClass.simpleName}: ${e.message}", e)
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
        if (isDsh && _dshPresets.value.selecting) return
        _selectedVariant.value = name
        saveDraft()
        if (isDsh) {
            val providerId = _selectedProviderId.value
            val modelId = _selectedModelId.value
            if (providerId != null && modelId != null) {
                submitDshModelSelection(providerId, modelId, name)
            }
        }
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
        if (isDsh && _dshPresets.value.selecting) return
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
        if (isDsh) {
            val providerId = _selectedProviderId.value
            val modelId = _selectedModelId.value
            if (providerId != null && modelId != null) {
                submitDshModelSelection(providerId, modelId, _selectedVariant.value)
            }
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        if (isDsh && _dshPresets.value.selecting) return
        _selectedProviderId.value = providerId
        _selectedModelId.value = modelId
        isModelExplicitlySelected = true
        if (isDsh) {
            submitDshModelSelection(providerId, modelId, _selectedVariant.value)
        }
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
        if (isDsh) {
            fileSearchJob = viewModelScope.launch {
                if (query.isNotEmpty()) delay(150)
                try {
                    val listing = dshApi.hostListDirectory(dshConn, sessionDirectory)
                    val needle = query.trim()
                    _fileSearchResults.value = listing.entries
                        .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                        .take(15)
                        .map { it.path }
                } catch (e: Exception) {
                    Log.e(TAG, "DSH file search failed", e)
                    _fileSearchResults.value = emptyList()
                }
            }
            return
        }
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
        val draft = dev.wuxie233.codecarry.data.repository.Draft(
            text = _draftText.value,
            imageUris = _draftAttachmentUris.value,
            confirmedFilePaths = _confirmedFilePaths.value.toList(),
            selectedAgent = agentPair.first.takeIf { agentPair.second },
            selectedVariant = _selectedVariant.value
        )
        draftRepository.saveDraft(sessionId, draft)
    }

    override fun onCleared() {
        foregroundResumeDispatcher.removeListener(foregroundResumeListener)
        dshFollowJob?.cancel()
        dshFollowAddress = null
        dshKnownAddress = null
        eventReducer.clearActiveSessionId(sessionId)
        closeTerminalSession()
        super.onCleared()
        saveDraft()
    }

    private fun refreshOpenSessionOnForeground() {
        viewModelScope.launch {
            if (isDsh) {
                // Live history rides session/follow; do not re-page while the
                // follow is open. Ready reconnect already reopens follow.
                if (dshConnectionManager.states.value[serverId]?.isReady == true) {
                    startDshFollow()
                }
            } else {
                runCatching { mergeOpenCodeHistorySilently() }
            }
        }
    }

    private suspend fun mergeOpenCodeHistorySilently() {
        val messages = api.listMessages(
            conn = conn,
            sessionId = sessionId,
            limit = currentMessageLimit,
            directory = sessionDirectory,
        )
        eventReducer.mergeMessages(sessionId, messages)
    }

    /** Get the session directory for building file:// URLs */
    fun getSessionDirectory(): String? = sessionDirectory

    fun sendMessage(text: String, attachments: List<PromptPart> = emptyList(), onResult: (Boolean) -> Unit = {}) {
        if (text.isBlank() && attachments.isEmpty()) {
            onResult(false)
            return
        }
        val parts = mutableListOf<PromptPart>()
        if (text.isNotBlank()) {
            parts.add(PromptPart(type = "text", text = text))
        }
        parts.addAll(attachments)
        sendParts(parts, onResult)
    }

    /** Send pre-built prompt parts (used when @-file mentions need structured parts). */
    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>, onResult: (Boolean) -> Unit = {}) {
        val parts = promptParts + attachments
        if (parts.isEmpty()) {
            onResult(false)
            return
        }
        sendParts(parts, onResult)
    }

    private fun sendParts(parts: List<PromptPart>, onResult: (Boolean) -> Unit = {}) {
        if (isDsh) {
            if (_dshPresets.value.selecting) {
                onResult(false)
                return
            }
            enqueueDshSend(parts, onResult)
            return
        }
        val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
            ModelSelection(_selectedProviderId.value!!, _selectedModelId.value!!)
        } else null
        pendingOpenCodeSends.addLast(
            PendingOpenCodeSend(
                parts = parts.toList(),
                model = model,
                agent = uiState.value.selectedAgent,
                variant = _selectedVariant.value,
            )
        )
        _pendingSendCount.value = pendingOpenCodeSends.size
        onResult(true)
        if (_pendingSendError.value == null) drainPendingOpenCodeSends()
    }

    fun retryPendingSend() {
        if (isDsh) {
            if (pendingOpenCodeSends.isEmpty()) return
            _pendingSendError.value = null
            _error.value = null
            drainPendingDshSends()
            return
        }
        if (pendingOpenCodeSends.isEmpty()) return
        _pendingSendError.value = null
        _error.value = null
        if (sessionDirectory.isNullOrBlank()) {
            viewModelScope.launch { loadSession() }
        } else {
            drainPendingOpenCodeSends()
        }
    }

    private fun enqueueDshSend(parts: List<PromptPart>, onResult: (Boolean) -> Unit = {}) {
        pendingOpenCodeSends.addLast(
            PendingOpenCodeSend(
                parts = parts.toList(),
                model = null,
                agent = uiState.value.selectedAgent,
                variant = _selectedVariant.value,
            ),
        )
        _pendingSendCount.value = pendingOpenCodeSends.size
        onResult(true)
        if (_pendingSendError.value == null) drainPendingDshSends()
    }

    private suspend fun sendDshPrompt(mode: String, content: kotlinx.serialization.json.JsonArray) {
        when (val address = dshHistoryAddress()) {
            is DshSessionAddress.Subagent -> dshApi.subagentPrompt(
                connection = dshConn,
                parentSessionId = address.parentSessionId,
                childSessionId = address.childSessionId,
                content = content,
            )
            is DshSessionAddress.Session -> dshApi.sessionPrompt(dshConn, sessionId, mode, content)
            null -> error("DSH session address is not ready")
        }
    }

    private suspend fun abortDshSession(): Boolean {
        return when (val address = dshHistoryAddress()) {
            is DshSessionAddress.Subagent -> dshApi.subagentInterrupt(
                connection = dshConn,
                parentSessionId = address.parentSessionId,
                childSessionId = address.childSessionId,
            ).accepted
            is DshSessionAddress.Session -> dshApi.sessionCancel(dshConn, sessionId).accepted
            null -> false
        }
    }

    private fun drainPendingDshSends() {
        if (pendingOpenCodeDrain?.isActive == true || pendingOpenCodeSends.isEmpty()) return
        pendingOpenCodeDrain = viewModelScope.launch {
            _isSending.value = true
            try {
                while (pendingOpenCodeSends.isNotEmpty() && _pendingSendError.value == null) {
                    val head = pendingOpenCodeSends.first()
                    try {
                        val steer = uiState.value.sessionStatus is SessionStatus.Busy
                        val request = dshPromptRequest(head.parts, steer)
                        sendDshPrompt(request.mode, request.content)
                        pendingOpenCodeSends.removeFirst()
                        _pendingSendCount.value = pendingOpenCodeSends.size
                        _pendingSendError.value = null
                        eventReducer.updateSessionStatus(sessionId, SessionStatus.Busy)
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to send DSH prompt", error)
                        _pendingSendError.value = error.message ?: "Failed to send queued message"
                        _error.value = _pendingSendError.value
                        break
                    }
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun updateDshQueue(itemId: String, action: String) {
        if (!isDsh) return
        viewModelScope.launch {
            runCatching {
                dshApi.sessionUpdateQueue(
                    dshConn,
                    sessionId,
                    itemId,
                    buildJsonObject { put("kind", action) },
                )
            }.onFailure { Log.e(TAG, "Failed to update DSH queue", it) }
        }
    }

    private fun drainPendingOpenCodeSends() {
        if (pendingOpenCodeDrain?.isActive == true || pendingOpenCodeSends.isEmpty()) return
        pendingOpenCodeDrain = viewModelScope.launch {
            val directory = sessionDirectory
            if (directory.isNullOrBlank()) return@launch
            _isSending.value = true
            try {
                while (pendingOpenCodeSends.isNotEmpty()) {
                    val head = pendingOpenCodeSends.first()
                    try {
                        api.promptAsync(
                            conn = conn,
                            sessionId = sessionId,
                            parts = head.parts,
                            model = head.model,
                            agent = head.agent,
                            variant = head.variant,
                            directory = directory,
                        )
                        pendingOpenCodeSends.removeFirst()
                        _pendingSendCount.value = pendingOpenCodeSends.size
                        _pendingSendError.value = null
                        _error.value = null
                        eventReducer.updateSessionStatus(sessionId, SessionStatus.Busy)
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to send queued message", error)
                        _pendingSendError.value = error.message ?: "Failed to send queued message"
                        _error.value = _pendingSendError.value
                        break
                    }
                }
            } finally {
                _isSending.value = false
            }
        }
    }



    fun replyToPermission(requestId: String, reply: String) {
        viewModelScope.launch {
            try {
                if (isDsh) {
                    val state = dshReducer.state.value
                    val approval = state.pendingApprovals[requestId] ?: return@launch
                    val clientId = state.eventsClientId ?: return@launch
                    val outcome = when (reply) {
                        "once", "always", "allowed-once" -> "allowed-once"
                        else -> "rejected"
                    }
                    dshApi.answerApproval(dshConn, clientId, approval.eventId, outcome)
                    dshReducer.removePendingApproval(approval.eventId)
                    applyDshState(dshReducer.state.value)
                    return@launch
                }
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
                val ok = if (isDsh) {
                    abortDshSession()
                } else {
                    api.abortSession(conn, sessionId, directory = sessionDirectory)
                }
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

    fun retrySessionNow() {
        if (!_isRetryingNow.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val wokeRetry = api.retrySessionNow(conn, sessionId, directory = sessionDirectory)
                if (!wokeRetry) {
                    _retryNowFailureEvent.tryEmit(Unit)
                    return@launch
                }
                eventReducer.sessionStatuses.first { statuses ->
                    statuses[sessionId] !is SessionStatus.Retry
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to retry session now", error)
                _retryNowFailureEvent.tryEmit(Unit)
            } finally {
                _isRetryingNow.value = false
            }
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
                if (isDsh) {
                    val state = dshReducer.state.value
                    val pending = state.pendingQuestions[requestId]
                        ?: error("question $requestId is no longer pending")
                    val clientId = state.eventsClientId
                        ?: error("question $requestId has no events client")
                    val payload = dshQuestionAnswer(pending.questions, answers)
                    dshApi.answerQuestion(
                        dshConn,
                        clientId,
                        pending.eventId,
                        json.encodeToJsonElement(DshQuestionAnswer.serializer(), payload),
                    )
                    dshReducer.removePendingQuestion(pending.eventId)
                    eventReducer.removeQuestion(serverId, requestId)
                    applyDshState(dshReducer.state.value)
                    return@launch
                }
                val success = api.replyToQuestion(
                    conn = conn,
                    requestId = requestId,
                    answers = answers,
                    directory = sessionDirectory
                )
                if (success) {
                    // Optimistically remove the question card — SSE event may arrive late or not at all
                    eventReducer.removeQuestion(serverId, requestId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply to question $requestId: ${e.javaClass.simpleName}: ${e.message}", e)
                _error.value = e.message ?: "Failed to reply to question"
                _questionUnlockEpoch.value += 1
            }
        }
    }

    /**
     * Reject a question request.
     */
    fun rejectQuestion(requestId: String) {
        viewModelScope.launch {
            try {
                if (isDsh) {
                    val state = dshReducer.state.value
                    val pending = state.pendingQuestions[requestId]
                        ?: error("question $requestId is no longer pending")
                    val clientId = state.eventsClientId
                        ?: error("question $requestId has no events client")
                    dshApi.rejectQuestion(dshConn, clientId, pending.eventId)
                    dshReducer.removePendingQuestion(pending.eventId)
                    eventReducer.removeQuestion(serverId, requestId)
                    applyDshState(dshReducer.state.value)
                    return@launch
                }
                val success = api.rejectQuestion(conn = conn, requestId = requestId, directory = sessionDirectory)
                if (success) {
                    // Optimistically remove the question card
                    eventReducer.removeQuestion(serverId, requestId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject question $requestId: ${e.javaClass.simpleName}: ${e.message}", e)
                _error.value = e.message ?: "Failed to reject question"
                _questionUnlockEpoch.value += 1
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
        if (isDsh) {
            onResult(false)
            return
        }
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
        if (isDsh) {
            onResult(false)
            return
        }
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
                if (isDsh) {
                    val atSeq = messageId?.let(::dshMessageSeq)
                    val forked = dshApi.sessionFork(dshConn, sessionId, atSeq)
                    val session = Session(
                        id = forked.sessionId,
                        directory = sessionDirectory.orEmpty(),
                        time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis()),
                    )
                    eventReducer.setSessions(serverId, listOf(session))
                    onResult(session)
                    return@launch
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
                if (isDsh) {
                    dshApi.sessionRename(dshConn, sessionId, title)
                    onResult(true)
                    return@launch
                }
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
                if (isDsh) {
                    val text = "/" + command.removePrefix("/").trim() + if (arguments.isBlank()) "" else " $arguments"
                    val request = dshPromptRequest(listOf(PromptPart(type = "text", text = text)), steer = false)
                    sendDshPrompt(request.mode, request.content)
                    onResult(true)
                    return@launch
                }
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
    fun createNewSession(agentPreset: String? = null, onResult: (Session?) -> Unit) {
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
                if (isDsh) {
                    val generationState = dshConnectionManager.states.value[serverId]
                    check(generationState?.status == DshGenerationStatus.Ready && generationState.isReady) {
                        appContext.getString(R.string.dsh_preset_connection_changed)
                    }
                    val generation = dshReducer.state.value.generation
                    val describe = generationState.describe
                    val connected = connectDshConversation(
                        client = dshApi,
                        connection = dshConn,
                        state = dshReducer.state.value,
                        path = requestedDirectory?.takeIf { it.isNotBlank() },
                        noRepoDirectory = describe?.cwd.orEmpty(),
                        agentPreset = agentPreset,
                    )
                    val currentGeneration = dshConnectionManager.states.value[serverId]
                    check(dshReducer.state.value.generation == generation && currentGeneration?.generation == generation &&
                        currentGeneration.status == DshGenerationStatus.Ready && currentGeneration.isReady) {
                        appContext.getString(R.string.dsh_preset_connection_changed)
                    }
                    connected.workspace?.let(dshReducer::applyWorkspace)
                    val session = Session(
                        id = connected.sessionId,
                        directory = connected.directory,
                        time = Session.Time(created = System.currentTimeMillis(), updated = System.currentTimeMillis()),
                    )
                    eventReducer.setSessions(serverId, listOf(session))
                    onResult(session)
                    return@launch
                }
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

/** Holds server connection info for navigation purposes. */
data class ConnectionParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
)
