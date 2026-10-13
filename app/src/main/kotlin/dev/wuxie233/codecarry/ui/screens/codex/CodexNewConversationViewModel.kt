package dev.wuxie233.codecarry.ui.screens.codex

import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.*
import dev.wuxie233.codecarry.data.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

/** A draft has no daemon thread until Send. Dispatch journals prevent replay after process death. */
@HiltViewModel
class CodexNewConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val servers: ServerRepository,
    private val manager: CodexConnectionManager,
) : ViewModel() {
    val serverId: String = decodeCodexRouteArg(savedStateHandle["serverId"])
    val projectId: String? = decodeCodexRouteArg(savedStateHandle["projectId"]).ifEmpty { null }
    private val preferences = context.getSharedPreferences("codex_new_conversations", Context.MODE_PRIVATE)
    private val key = "${serverId.length}:$serverId:${projectId.orEmpty()}"
    private fun saved(name: String) = preferences.getString("$key:$name", null)
    private var phase = saved("phase")
    private var createdThreadId = saved("threadId")
    private var clientMessageId = saved("clientMessageId")
    private var creationRequestId = saved("creationRequestId")
    private var modelChoice = saved("model")
    private var selectionRevision = 0L
    private var modelReadRevision = 0L
    private val _state = MutableStateFlow(CodexChatUiState(
        composerValue = TextFieldValue(saved("text").orEmpty()),
        selectedEffort = saved("effort"),
        fastEnabled = preferences.getBoolean("$key:fast", false),
        isSendConfirmationPending = phase != null,
        isHistoryAttachmentRecoveryRequired = preferences.getBoolean("$key:attachments", false),
        error = if (phase != null) context.getString(R.string.codex_new_unknown) else null,
    ))
    val state = _state.asStateFlow()
    private val _projectName = MutableStateFlow<String?>(null)
    val projectName = _projectName.asStateFlow()
    private val _openThread = MutableStateFlow<String?>(null)
    val openThread = _openThread.asStateFlow()
    private var roots = emptyList<String>()
    private var lease: CodexConnectionLease? = null
    private val connectionMutex = Mutex()

    init { reload() }

    private suspend fun connection(): CodexServerConnection = connectionMutex.withLock {
        lease?.takeIf { manager.isCurrent(it.connection) }?.connection ?: run {
            lease?.close()
            manager.acquire(servers.getServer(serverId) ?: error("Codex server is no longer configured"))
                .also { lease = it }.connection
        }
    }

    fun reload() {
        viewModelScope.launch {
            try {
                val connected = connection()
                connected.client.connect()
                val generation = connected.client.currentConnectionGeneration()
                if (projectId != null) {
                    val project = connected.client.readProject(projectId)
                    check(manager.isCurrent(connected) && connected.client.currentConnectionGeneration() == generation)
                    roots = project.roots.map { it.path }
                    _projectName.value = project.name
                }
                _state.update { it.copy(isLoading = false, isConnected = true) }
                loadModels()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.update { it.copy(isLoading = false, error = error.message) } }
        }
    }

    fun loadModels() {
        val readRevision = ++modelReadRevision
        val choiceRevision = selectionRevision
        viewModelScope.launch {
            try {
                val connected = connection()
                val generation = connected.client.currentConnectionGeneration()
                val models = manager.listModels(connected)
                if (readRevision != modelReadRevision || !manager.isCurrent(connected) ||
                    generation != connected.client.currentConnectionGeneration()) return@launch
                if (choiceRevision != selectionRevision) {
                    _state.update { it.copy(modelsState = CodexMetadataLoadState.Loaded(models)) }
                    return@launch
                }
                val selected = models.firstOrNull { it.model == modelChoice }
                    ?: models.firstOrNull { it.isDefault } ?: models.firstOrNull()
                _state.update { it.copy(modelsState = CodexMetadataLoadState.Loaded(models), selectedModel = selected,
                    selectedEffort = it.selectedEffort?.takeIf { effort -> selected?.supportedReasoningEfforts?.any { option -> option.reasoningEffort == effort } == true }
                        ?: selected?.defaultReasoningEffort) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (readRevision == modelReadRevision) _state.update {
                    it.copy(modelsState = it.modelsState.failedKeepingValue(error.message.orEmpty()))
                }
            }
        }
    }

    fun updateDraft(value: TextFieldValue) {
        if (_state.value.isSending || phase != null) return
        _state.update { it.copy(composerValue = value) }
        preferences.edit().putString("$key:text", value.text).apply()
    }

    fun selectModel(model: CodexModel) {
        if (_state.value.isSending || phase != null) return
        selectionRevision++
        modelChoice = model.model
        val effort = _state.value.selectedEffort?.takeIf { current -> model.supportedReasoningEfforts.any { it.reasoningEffort == current } }
            ?: model.defaultReasoningEffort
        _state.update { it.copy(selectedModel = model, selectedEffort = effort) }
        preferences.edit().putString("$key:model", model.model).putString("$key:effort", effort).apply()
    }

    fun selectEffort(effort: String) {
        if (_state.value.isSending || phase != null) return
        selectionRevision++
        _state.update { it.copy(selectedEffort = effort) }
        preferences.edit().putString("$key:effort", effort).apply()
    }

    fun toggleFast() {
        if (_state.value.isSending || phase != null) return
        _state.update { it.copy(fastEnabled = !it.fastEnabled) }
        preferences.edit().putBoolean("$key:fast", _state.value.fastEnabled).apply()
    }

    fun addAttachment(attachment: CodexComposerAttachment) {
        if (_state.value.isSending || phase != null) return
        _state.update {
            val next = it.composerAttachments.filterNot { item -> item.id == attachment.id } + attachment
            if (next.size > 8 || next.sumOf { item -> item.input.toJson().toString().toByteArray(Charsets.UTF_8).size.toLong() } > 12 * 1024 * 1024)
                it.copy(attachmentLimitReached = true)
            else it.copy(composerAttachments = next, attachmentLimitReached = false)
        }
        preferences.edit().putBoolean("$key:attachments", true).apply()
    }

    fun removeAttachment(id: String) {
        if (_state.value.isSending || phase != null) return
        _state.update { it.copy(composerAttachments = it.composerAttachments.filterNot { item -> item.id == id }, attachmentLimitReached = false) }
        preferences.edit().putBoolean("$key:attachments", _state.value.composerAttachments.isNotEmpty()).apply()
    }

    fun reviewMissingAttachments() {
        _state.update { it.copy(isHistoryAttachmentRecoveryRequired = false) }
        preferences.edit().putBoolean("$key:attachments", _state.value.composerAttachments.isNotEmpty()).apply()
    }

    fun loadSkills() = viewModelScope.launch {
        _state.update { it.copy(skillsLoading = true, skillsError = null) }
        try {
            val cwd = roots.firstOrNull() ?: error(context.getString(R.string.codex_new_workspace_after_send))
            val result = connection().client.listSkillsResult(cwd)
            _state.update { it.copy(skills = result.skills) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _state.update { it.copy(skillsError = error.message) } }
        finally { _state.update { it.copy(skillsLoading = false) } }
    }

    fun searchFiles(query: String) = viewModelScope.launch {
        _state.update { it.copy(attachmentsLoading = true, attachmentsError = null) }
        try {
            check(roots.isNotEmpty()) { context.getString(R.string.codex_new_workspace_after_send) }
            val files = connection().client.searchFiles(query, roots)
            _state.update { it.copy(files = files) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _state.update { it.copy(attachmentsError = error.message) } }
        finally { _state.update { it.copy(attachmentsLoading = false) } }
    }

    private fun journal(next: String?) {
        // Commit before dispatch: a killed process must not replay an unknown write.
        check(preferences.edit().putString("$key:phase", next).putString("$key:threadId", createdThreadId)
            .putString("$key:clientMessageId", clientMessageId).commit())
        phase = next
    }

    private suspend fun acceptCreation(client: CodexAppServerClient, initial: JsonObject?) {
        var receipt = initial
        repeat(120) {
            val value = receipt ?: error("Creation receipt is not available; the request will not be replayed")
            check(value["requestId"]?.jsonPrimitive?.content == creationRequestId) { "Creation receipt identity mismatch" }
            when (value["status"]?.jsonPrimitive?.content) {
                "created" -> {
                    val threadId = value["threadId"]?.jsonPrimitive?.contentOrNull
                    check(!threadId.isNullOrBlank()) { "Created thread receipt is missing its thread ID" }
                    complete(threadId)
                    return
                }
                "failed" -> {
                    journal(null)
                    preferences.edit().remove("$key:creationRequestId").commit()
                    creationRequestId = null
                    error(value["error"]?.jsonPrimitive?.contentOrNull ?: "Thread creation failed")
                }
                "outcome-unknown" -> error(value["error"]?.jsonPrimitive?.contentOrNull ?: "Thread creation outcome is unknown")
                "queued" -> Unit
                else -> error("Invalid creation receipt status")
            }
            delay(1_000)
            receipt = client.readCoordinatedCreation(checkNotNull(creationRequestId))
        }
        error("Thread creation is still pending; check its receipt again")
    }

    fun send() {
        val snapshot = _state.value
        if (snapshot.isSending || snapshot.isLoading || phase != null || snapshot.isHistoryAttachmentRecoveryRequired ||
            (snapshot.draft.isBlank() && snapshot.composerAttachments.isEmpty())) return
        if (snapshot.draft.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
            _state.update { it.copy(error = context.getString(R.string.codex_new_text_limit)) }
            return
        }
        _state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            var dispatched = false
            var connected: CodexServerConnection? = null
            try {
                connected = connection()
                connected.client.connect()
                val generation = connected.client.currentConnectionGeneration()
                if (createdThreadId == null && connected.client.supportsCoordinatedCreation()) {
                    creationRequestId = UUID.randomUUID().toString()
                    check(preferences.edit().putString("$key:creationRequestId", creationRequestId).commit())
                    val command = buildJsonObject {
                        put("requestId", creationRequestId)
                        put("target", buildJsonObject {
                            if (projectId != null) {
                                put("type", "project")
                                put("projectId", projectId)
                                put("environment", buildJsonObject { put("type", "local") })
                            } else put("type", "projectless")
                        })
                        put("input", JsonArray(buildList {
                            if (snapshot.draft.isNotBlank()) add(CodexUserInput.Text(snapshot.draft.trim()).toJson())
                            addAll(snapshot.composerAttachments.map { it.input.toJson() })
                        }))
                        (snapshot.selectedModel?.model ?: modelChoice)?.let { put("model", it) }
                        snapshot.selectedEffort?.let { put("effort", it) }
                        codexFastTurnParams(snapshot.selectedModel, snapshot.fastEnabled, true).forEach { (name, value) -> put(name, value) }
                    }
                    journal("coordinator")
                    dispatched = true
                    acceptCreation(connected.client, connected.client.createCoordinatedThread(command))
                    return@launch
                }
                if (createdThreadId == null) {
                    val currentRoots = if (projectId != null) connected.client.readProject(projectId).roots.map { it.path }
                        else listOf(saved("cwd") ?: connected.client.prepareProjectlessWorkspace(snapshot.draft).also { cwd ->
                            check(preferences.edit().putString("$key:cwd", cwd).commit())
                        })
                    check(currentRoots.isNotEmpty()) { context.getString(R.string.codex_new_no_roots) }
                    check(manager.isCurrent(connected) && generation == connected.client.currentConnectionGeneration())
                    roots = currentRoots
                    journal("creating")
                    dispatched = true
                    val created = connected.client.startThread(cwd = roots.first(), model = snapshot.selectedModel?.model ?: modelChoice,
                        projectId = projectId, workspaceRoots = roots)
                    createdThreadId = created.thread.id
                    journal(null)
                    dispatched = false
                    check(manager.isCurrent(connected) && generation == connected.client.currentConnectionGeneration())
                    connected.reducer.upsertThread(created.thread)
                }
                val threadId = checkNotNull(createdThreadId)
                clientMessageId = UUID.randomUUID().toString()
                journal("sending")
                dispatched = true
                manager.retainProvisionalTurn(serverId, threadId)
                val turn = connected.client.startTurn(threadId = threadId,
                    input = buildList {
                        if (snapshot.draft.isNotBlank()) add(CodexUserInput.Text(snapshot.draft.trim()))
                        addAll(snapshot.composerAttachments.map { it.input })
                    }, model = snapshot.selectedModel?.model ?: modelChoice, effort = snapshot.selectedEffort,
                    clientUserMessageId = clientMessageId,
                    extraParams = codexFastTurnParams(snapshot.selectedModel, snapshot.fastEnabled, true))
                if (manager.isCurrent(connected) && generation == connected.client.currentConnectionGeneration() && turn != null) {
                    connected.reducer.acceptTurnStart(threadId, turn)
                    manager.releaseProvisionalTurn(serverId, threadId)
                }
                complete(threadId)
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                _state.update { it.copy(isSendConfirmationPending = phase != null,
                    error = if (phase != null) context.getString(R.string.codex_new_unknown) else cancelled.message) }
            } catch (error: Exception) {
                if (phase != "coordinator" && (!dispatched || !isUncertainCodexSendFailure(error))) {
                    journal(null)
                    createdThreadId?.let { manager.releaseProvisionalTurn(serverId, it) }
                }
                _state.update { it.copy(isSendConfirmationPending = phase != null,
                    error = if (phase != null) context.getString(R.string.codex_new_unknown) else error.message) }
            } finally { _state.update { it.copy(isSending = false, isSendConfirmationPending = phase != null) } }
        }
    }

    fun recheck() = viewModelScope.launch {
        if (createdThreadId == null && creationRequestId == null) return@launch
        _state.update { it.copy(isSending = true) }
        try {
            val connected = connection()
            connected.client.connect()
            if (phase == "coordinator") {
                acceptCreation(connected.client, connected.client.readCoordinatedCreation(checkNotNull(creationRequestId)))
                return@launch
            }
            val threadId = checkNotNull(createdThreadId)
            val session = manager.resumeThread(connected, threadId, forceRefresh = true)
            if (clientMessageId?.let { session.thread.hasClientMessage(it) } == true) {
                manager.releaseProvisionalTurn(serverId, threadId)
                complete(threadId)
            }
            else _state.update { it.copy(error = context.getString(R.string.codex_new_unknown)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _state.update { it.copy(error = error.message) } }
        finally { _state.update { it.copy(isSending = false, isSendConfirmationPending = phase != null) } }
    }

    val canRecheck: Boolean get() = createdThreadId != null || creationRequestId != null

    val canAbandonWait: Boolean get() = phase != null
    val submissionMayHaveSent: Boolean get() = phase == "sending" || phase == "coordinator"

    /** Explicit user acknowledgement abandons only the local wait, never replays the unknown request. */
    fun abandonWait() {
        if (!canAbandonWait || _state.value.isSending) return
        check(preferences.edit().remove("$key:phase").remove("$key:cwd").remove("$key:clientMessageId").remove("$key:threadId").remove("$key:creationRequestId").commit())
        createdThreadId?.let { manager.releaseProvisionalTurn(serverId, it) }
        createdThreadId = null
        creationRequestId = null
        phase = null
        clientMessageId = null
        _state.update { it.copy(isSendConfirmationPending = false, error = null) }
    }

    private fun complete(threadId: String) {
        check(preferences.edit().remove("$key:phase").remove("$key:threadId").remove("$key:clientMessageId")
            .remove("$key:text").remove("$key:attachments").remove("$key:cwd").remove("$key:creationRequestId").commit())
        creationRequestId = null
        phase = null
        _state.update { it.copy(composerValue = TextFieldValue(), composerAttachments = emptyList(), isSendConfirmationPending = false) }
        _openThread.value = threadId
    }

    override fun onCleared() { lease?.close(); super.onCleared() }
}
