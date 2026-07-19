package dev.minios.ocremote.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.diagnostics.AppEventBreadcrumb
import dev.minios.ocremote.data.diagnostics.AppEventDiagnosticsGenerator
import dev.minios.ocremote.data.diagnostics.AppEventName
import dev.minios.ocremote.data.diagnostics.DiagnosticsBundleRepository
import dev.minios.ocremote.data.diagnostics.DiagnosticsLogItem
import dev.minios.ocremote.data.diagnostics.DiagnosticsLogRepository
import dev.minios.ocremote.data.diagnostics.DiagnosticsRedactor
import dev.minios.ocremote.data.diagnostics.DiagnosticsUploadRepository
import dev.minios.ocremote.data.diagnostics.DiagnosticsUploadState
import dev.minios.ocremote.data.diagnostics.NetworkDiagnosticsRecorder
import dev.minios.ocremote.data.diagnostics.SessionDiagnosticInput
import dev.minios.ocremote.data.diagnostics.SessionDiagnosticsGenerator
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Session
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val logRepository: DiagnosticsLogRepository,
    private val bundleRepository: DiagnosticsBundleRepository,
    private val uploadRepository: DiagnosticsUploadRepository,
    private val appEventDiagnosticsGenerator: AppEventDiagnosticsGenerator,
    private val networkDiagnosticsRecorder: NetworkDiagnosticsRecorder,
    private val sessionDiagnosticsGenerator: SessionDiagnosticsGenerator,
    private val eventReducer: EventReducer,
) : ViewModel() {
    private var currentLogs: List<DiagnosticsLogItem> = emptyList()
    private var currentSelectedLogIds: Set<String> = emptySet()
    private var isRefreshing = false
    private var isGenerating = false
    private var isUploading = false
    private var statusMessage: String? = null
    private var errorMessage: String? = null
    private var uploadUrlConfigured = false
    private var uploadTokenConfigured = false
    private var uploadInProgress = false

    private val mutableUiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.diagnosticsUploadUrl,
                settingsRepository.diagnosticsUploadToken,
            ) { uploadUrl, uploadToken ->
                uploadUrl.trim().isNotBlank() to uploadToken.trim().isNotBlank()
            }.collect { (urlConfigured, tokenConfigured) ->
                uploadUrlConfigured = urlConfigured
                uploadTokenConfigured = tokenConfigured
                publishState()
            }
        }
        refresh()
    }

    fun toggleSelection(logId: String) {
        if (currentLogs.none { it.id == logId }) return
        currentSelectedLogIds = if (logId in currentSelectedLogIds) {
            currentSelectedLogIds - logId
        } else {
            currentSelectedLogIds + logId
        }
        clearTransientMessages()
    }

    fun selectAll() {
        currentSelectedLogIds = currentLogs.map { it.id }.toSet()
        clearTransientMessages()
    }

    fun clearSelection() {
        currentSelectedLogIds = emptySet()
        clearTransientMessages()
    }

    fun refresh() {
        viewModelScope.launch { refreshNow(showRefreshing = true) }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = currentSelectedLogIds
            if (ids.isEmpty()) return@launch
            try {
                val deleted = logRepository.deleteLogs(ids)
                currentSelectedLogIds = emptySet()
                refreshNow(showRefreshing = false)
                statusMessage = "Deleted $deleted diagnostics artifact(s)."
                errorMessage = null
                publishState()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                errorMessage = safeErrorMessage(error, "Failed to delete selected diagnostics.")
                statusMessage = null
                publishState()
            }
        }
    }

    fun generateNow() {
        viewModelScope.launch {
            if (isGenerating) return@launch
            isGenerating = true
            statusMessage = null
            errorMessage = null
            publishState()
            try {
                val nowMillis = System.currentTimeMillis()
                val latestSession = latestTopLevelSession()
                val serverId = latestSession?.serverId
                val serverName = latestSession?.serverName
                appEventDiagnosticsGenerator.createArtifact(
                    breadcrumbs = listOf(
                        AppEventBreadcrumb(
                            name = AppEventName.APP_START,
                            timestampMillis = nowMillis,
                            sessionId = latestSession?.session?.id,
                            serverId = serverId,
                            serverName = serverName,
                            details = mapOf("reason" to "manual-generation"),
                        ),
                    ),
                    createdAtMillis = nowMillis,
                    sessionId = latestSession?.session?.id,
                    serverName = serverName,
                )
                networkDiagnosticsRecorder.createArtifact(
                    createdAtMillis = nowMillis + 1,
                    serverId = serverId,
                    serverName = serverName,
                )
                if (latestSession == null) {
                    sessionDiagnosticsGenerator.createNoSessionArtifact(
                        reason = "no-session-available",
                        createdAtMillis = nowMillis + 2,
                    )
                } else {
                    sessionDiagnosticsGenerator.createArtifact(
                        input = latestSession.toDiagnosticInput(),
                        createdAtMillis = nowMillis + 2,
                    )
                }
                refreshNow(showRefreshing = false)
                statusMessage = "Generated diagnostics artifacts."
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                errorMessage = safeErrorMessage(error, "Failed to generate diagnostics.")
            } finally {
                isGenerating = false
                publishState()
            }
        }
    }

    fun uploadSelected(): Job? {
        if (uploadInProgress) return null
        if (currentSelectedLogIds.isEmpty()) return null
        uploadInProgress = true
        return viewModelScope.launch {
            try {
                val selectedItems = selectedItems()
                if (selectedItems.isEmpty()) return@launch
                val uploadUrl = settingsRepository.diagnosticsUploadUrl.first().trim()
                val uploadToken = settingsRepository.diagnosticsUploadToken.first().trim()
                if (uploadUrl.isBlank()) {
                    errorMessage = "Diagnostics upload URL is required before uploading diagnostics."
                    statusMessage = null
                    publishState()
                    return@launch
                }
                if (uploadToken.isBlank()) {
                    errorMessage = "Diagnostics bearer token is required before uploading diagnostics."
                    statusMessage = null
                    publishState()
                    return@launch
                }

                isUploading = true
                statusMessage = null
                errorMessage = null
                publishState()
                val bundle = bundleRepository.createBundle(selectedItems, nowMillis = System.currentTimeMillis())
                uploadRepository.selectFile(bundle)
                when (val result = uploadRepository.uploadSelectedFile()) {
                    is DiagnosticsUploadState.Success -> {
                        currentSelectedLogIds = emptySet()
                        statusMessage = "Uploaded ${result.file.filename} as ${result.reportId}."
                        errorMessage = null
                        refreshNow(showRefreshing = false)
                    }
                    is DiagnosticsUploadState.Error -> {
                        errorMessage = DiagnosticsRedactor.redact(result.message).ifBlank { "Diagnostics upload failed." }
                        statusMessage = null
                    }
                    else -> Unit
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                errorMessage = safeErrorMessage(error, "Diagnostics upload failed.")
                statusMessage = null
            } finally {
                uploadInProgress = false
                isUploading = false
                publishState()
            }
        }
    }

    private fun selectedItems(): List<DiagnosticsLogItem> {
        if (currentSelectedLogIds.isEmpty()) return emptyList()
        return currentLogs.filter { it.id in currentSelectedLogIds }
    }

    private fun refreshNow(showRefreshing: Boolean) {
        if (showRefreshing) {
            isRefreshing = true
            publishState()
        }
        try {
            currentLogs = logRepository.listLogs()
            val availableIds = currentLogs.map { it.id }.toSet()
            currentSelectedLogIds = currentSelectedLogIds.intersect(availableIds)
        } finally {
            if (showRefreshing) isRefreshing = false
            publishState()
        }
    }

    private fun clearTransientMessages() {
        statusMessage = null
        errorMessage = null
        publishState()
    }

    private fun publishState() {
        mutableUiState.value = DiagnosticsUiState(
            logs = currentLogs,
            selectedLogIds = currentSelectedLogIds,
            uploadUrlConfigured = uploadUrlConfigured,
            uploadTokenConfigured = uploadTokenConfigured,
            isRefreshing = isRefreshing,
            isGenerating = isGenerating,
            isUploading = isUploading,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )
    }

    private fun latestTopLevelSession(): LatestSession? {
        val sessions = eventReducer.sessions.value
            .filter { it.parentId == null }
            .sortedWith(compareByDescending<Session> { it.time.updated }.thenByDescending { it.time.created })
        val session = sessions.firstOrNull() ?: return null
        val serverId = eventReducer.serverSessions.value.entries.firstOrNull { (_, ids) -> session.id in ids }?.key
        return LatestSession(session = session, serverId = serverId, serverName = null)
    }

    private fun LatestSession.toDiagnosticInput(): SessionDiagnosticInput {
        val sessionMessages = eventReducer.messages.value[session.id].orEmpty()
        val partsByMessageId = eventReducer.parts.value
        val messagesWithParts = sessionMessages.map { message ->
            MessageWithParts(
                info = message,
                parts = partsByMessageId[message.id].orEmpty(),
            )
        }
        return SessionDiagnosticInput(
            session = session,
            serverId = serverId,
            serverName = serverName,
            status = eventReducer.sessionStatuses.value[session.id],
            messages = messagesWithParts,
            pendingPermissionCount = serverId?.let { eventReducer.permissionsByServer.value[it]?.get(session.id) }.orEmpty().size,
            pendingQuestionCount = serverId?.let { eventReducer.questionsByServer.value[it]?.get(session.id) }.orEmpty().size,
            todoCount = eventReducer.todos.value[session.id].orEmpty().size,
            diffFileCount = eventReducer.sessionDiffs.value[session.id].orEmpty().size,
            isActiveSession = eventReducer.activeSessionId.value == session.id,
        )
    }

    private fun safeErrorMessage(error: Throwable, fallback: String): String {
        return DiagnosticsRedactor.redact(error.message ?: fallback).ifBlank { fallback }
    }

    private data class LatestSession(
        val session: Session,
        val serverId: String?,
        val serverName: String?,
    )
}
