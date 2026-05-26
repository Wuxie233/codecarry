package dev.minios.ocremote.ui.screens.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.data.diagnostics.DiagnosticsRedactor
import dev.minios.ocremote.data.diagnostics.DiagnosticsSelectedFile
import dev.minios.ocremote.data.diagnostics.DiagnosticsUploadFile
import dev.minios.ocremote.data.diagnostics.DiagnosticsUploadRepository
import dev.minios.ocremote.data.diagnostics.DiagnosticsUploadState
import dev.minios.ocremote.data.repository.AppUpdateLogic
import dev.minios.ocremote.data.repository.AppUpdateRepository
import dev.minios.ocremote.data.repository.LocalServerManager
import dev.minios.ocremote.data.repository.SettingsRepository
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val diagnosticsUploadRepository: DiagnosticsUploadRepository,
) : ViewModel() {
    
    val appLanguage = settingsRepository.appLanguage.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val appTheme = settingsRepository.appTheme.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "system"
    )

    val dynamicColor = settingsRepository.dynamicColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val chatFontSize = settingsRepository.chatFontSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "medium"
    )

    val notificationsEnabled = settingsRepository.notificationsEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val initialMessageCount = settingsRepository.initialMessageCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 50
    )

    val codeWordWrap = settingsRepository.codeWordWrap.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val confirmBeforeSend = settingsRepository.confirmBeforeSend.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val amoledDark = settingsRepository.amoledDark.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val compactMessages = settingsRepository.compactMessages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val collapseTools = settingsRepository.collapseTools.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val showHistoricalSubagents = settingsRepository.showHistoricalSubagents.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val hapticFeedback = settingsRepository.hapticFeedback.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val reconnectMode = settingsRepository.reconnectMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "normal"
    )

    val keepScreenOn = settingsRepository.keepScreenOn.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val compressImageAttachments = settingsRepository.compressImageAttachments.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val imageAttachmentMaxLongSide = settingsRepository.imageAttachmentMaxLongSide.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 1440
    )

    val imageAttachmentWebpQuality = settingsRepository.imageAttachmentWebpQuality.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 60
    )

    val showLocalRuntime = settingsRepository.showLocalRuntime.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val silentNotifications = settingsRepository.silentNotifications.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val terminalFontSize = settingsRepository.terminalFontSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 13f
    )

    val localProxyEnabled = settingsRepository.localProxyEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    val localProxyUrl = settingsRepository.localProxyUrl.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "",
    )

    val localProxyNoProxy = settingsRepository.localProxyNoProxy.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LocalServerManager.DEFAULT_NO_PROXY_LIST,
    )

    val localServerAllowLan = settingsRepository.localServerAllowLan.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    val localServerUsername = settingsRepository.localServerUsername.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "",
    )

    val localServerPassword = settingsRepository.localServerPassword.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "",
    )

    val localServerRunInBackground = settingsRepository.localServerRunInBackground.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true,
    )

    val localServerAutoStart = settingsRepository.localServerAutoStart.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    val localServerStartupTimeoutSec = settingsRepository.localServerStartupTimeoutSec.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 30,
    )

    fun setLanguage(languageCode: String) {
        viewModelScope.launch {
            settingsRepository.setAppLanguage(languageCode)
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch {
            settingsRepository.setAppTheme(theme)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColor(enabled)
        }
    }

    fun setChatFontSize(size: String) {
        viewModelScope.launch {
            settingsRepository.setChatFontSize(size)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationsEnabled(enabled)
        }
    }

    fun setInitialMessageCount(count: Int) {
        viewModelScope.launch {
            settingsRepository.setInitialMessageCount(count)
        }
    }

    fun setCodeWordWrap(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCodeWordWrap(enabled)
        }
    }

    fun setConfirmBeforeSend(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setConfirmBeforeSend(enabled)
        }
    }

    fun setAmoledDark(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAmoledDark(enabled)
        }
    }

    fun setCompactMessages(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCompactMessages(enabled)
        }
    }

    fun setCollapseTools(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCollapseTools(enabled)
        }
    }

    fun setShowHistoricalSubagents(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowHistoricalSubagents(enabled)
        }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHapticFeedback(enabled)
        }
    }

    fun setReconnectMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setReconnectMode(mode)
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepScreenOn(enabled)
        }
    }

    fun setSilentNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSilentNotifications(enabled)
        }
    }

    fun setCompressImageAttachments(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCompressImageAttachments(enabled)
        }
    }

    fun setImageAttachmentMaxLongSide(px: Int) {
        viewModelScope.launch {
            settingsRepository.setImageAttachmentMaxLongSide(px)
        }
    }

    fun setImageAttachmentWebpQuality(quality: Int) {
        viewModelScope.launch {
            settingsRepository.setImageAttachmentWebpQuality(quality)
        }
    }

    fun setShowLocalRuntime(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowLocalRuntime(enabled)
        }
    }

    fun setTerminalFontSize(size: Float) {
        viewModelScope.launch {
            settingsRepository.setTerminalFontSize(size)
        }
    }

    fun setLocalProxyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyEnabled(enabled)
        }
    }

    fun setLocalProxyUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyUrl(url)
        }
    }

    fun setLocalProxyNoProxy(value: String) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyNoProxy(value)
        }
    }

    fun setLocalServerAllowLan(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalServerAllowLan(enabled)
        }
    }

    fun setLocalServerUsername(value: String) {
        viewModelScope.launch {
            settingsRepository.setLocalServerUsername(value)
        }
    }

    fun setLocalServerPassword(value: String) {
        viewModelScope.launch {
            settingsRepository.setLocalServerPassword(value)
        }
    }

    fun setLocalServerRunInBackground(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalServerRunInBackground(enabled)
            if (!enabled) {
                settingsRepository.setLocalServerAutoStart(false)
            }
        }
    }

    fun setLocalServerAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalServerAutoStart(enabled)
        }
    }

    fun setLocalServerStartupTimeoutSec(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLocalServerStartupTimeoutSec(value)
        }
    }

    private val _appUpdateUiState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val appUpdateUiState: StateFlow<AppUpdateUiState> = _appUpdateUiState.asStateFlow()

    val diagnosticsUploadState: StateFlow<DiagnosticsUploadState> = diagnosticsUploadRepository.state

    val debugUpdateApiUrl = settingsRepository.debugUpdateApiUrl.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "",
    )

    val diagnosticsUploadUrl = settingsRepository.diagnosticsUploadUrl.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "",
    )

    val diagnosticsUploadToken = settingsRepository.diagnosticsUploadToken.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "",
    )

    fun setDebugUpdateApiUrl(url: String) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch { settingsRepository.setDebugUpdateApiUrl(url) }
    }

    fun setDiagnosticsUploadUrl(url: String) {
        viewModelScope.launch { settingsRepository.setDiagnosticsUploadUrl(url) }
    }

    fun setDiagnosticsUploadToken(token: String) {
        viewModelScope.launch { settingsRepository.setDiagnosticsUploadToken(token) }
    }

    fun selectDiagnosticsUploadFile(file: DiagnosticsUploadFile) {
        diagnosticsUploadRepository.selectFile(file)
    }

    fun selectDiagnosticsUploadFile(
        file: DiagnosticsSelectedFile,
        openFile: suspend () -> DiagnosticsUploadFile,
    ) {
        diagnosticsUploadRepository.selectFile(file, openFile)
    }

    fun selectDiagnosticsUploadUri(
        contentResolver: ContentResolver,
        uri: Uri,
        unsupportedFileMessage: String,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { contentResolver.readDiagnosticsFileMetadata(uri) }
            }.onSuccess { metadata ->
                if (metadata == null) {
                    diagnosticsUploadRepository.showError(unsupportedFileMessage)
                    return@onSuccess
                }
                diagnosticsUploadRepository.selectFile(metadata) {
                    withContext(Dispatchers.IO) { contentResolver.readDiagnosticsUploadFile(uri, metadata) }
                }
            }.onFailure { error ->
                diagnosticsUploadRepository.showError(
                    DiagnosticsRedactor.redact(error.message ?: unsupportedFileMessage).ifBlank { unsupportedFileMessage },
                )
            }
        }
    }

    fun onDiagnosticsUploadPickerCanceled() = Unit

    fun showDiagnosticsUploadError(message: String) {
        diagnosticsUploadRepository.showError(message)
    }

    fun clearDiagnosticsUploadFile() {
        diagnosticsUploadRepository.clearSelection()
    }

    fun uploadSelectedDiagnosticsFile() {
        viewModelScope.launch { diagnosticsUploadRepository.uploadSelectedFile() }
    }

    fun checkForAppUpdates() {
        val cur = _appUpdateUiState.value
        if (cur is AppUpdateUiState.Checking || cur is AppUpdateUiState.Downloading) return
        viewModelScope.launch {
            _appUpdateUiState.value = AppUpdateUiState.Checking
            try {
                val override = if (BuildConfig.DEBUG) debugUpdateApiUrl.value else ""
                val url = AppUpdateLogic.resolveLatestReleaseApiUrl(override, BuildConfig.DEBUG)
                val release = appUpdateRepository.fetchLatestRelease(url)
                val asset = AppUpdateLogic.selectApkAsset(release.assets, preferDebug = BuildConfig.DEBUG)
                if (asset != null && AppUpdateLogic.isRemoteVersionNewer(BuildConfig.VERSION_NAME, release.tagName)) {
                    _appUpdateUiState.value = AppUpdateUiState.UpdateAvailable(release, asset)
                } else {
                    _appUpdateUiState.value = AppUpdateUiState.UpToDate
                }
            } catch (e: Exception) {
                _appUpdateUiState.value = AppUpdateUiState.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    fun downloadAvailableUpdate() {
        val cur = _appUpdateUiState.value as? AppUpdateUiState.UpdateAvailable ?: return
        viewModelScope.launch {
            _appUpdateUiState.value = AppUpdateUiState.Downloading()
            val startMs = System.currentTimeMillis()
            try {
                val apkFile = appUpdateRepository.downloadApkAsset(cur.selectedAsset) { downloadedBytes, totalBytes ->
                    val elapsedMs = System.currentTimeMillis() - startMs
                    val speedBytesPerSec = if (elapsedMs > 0) downloadedBytes * 1000L / elapsedMs else 0L
                    val progressPercent = totalBytes?.let { ((downloadedBytes * 100) / it).toInt() } ?: 0
                    _appUpdateUiState.value = AppUpdateUiState.Downloading(
                        progressPercent = progressPercent,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speedBytesPerSec,
                    )
                }
                _appUpdateUiState.value = AppUpdateUiState.ReadyToInstall(apkFile, cur.release)
            } catch (e: Exception) {
                _appUpdateUiState.value = AppUpdateUiState.Error(e.message ?: "Download failed", e)
            }
        }
    }

    fun dismissAppUpdateDialog() {
        _appUpdateUiState.value = AppUpdateUiState.Idle
    }

    private fun ContentResolver.readDiagnosticsFileMetadata(uri: Uri): DiagnosticsSelectedFile? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val contentType = getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        var displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        var sizeBytes = 0L

        query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: displayName
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                }
            }
        }

        val filename = displayName?.takeIf { it.isNotBlank() } ?: return null
        return DiagnosticsSelectedFile(
            filename = filename,
            sizeBytes = sizeBytes,
            contentType = contentType.takeUnless { it == "application/octet-stream" }
                ?: contentTypeForDiagnosticsFilename(filename),
        )
    }

    private fun ContentResolver.readDiagnosticsUploadFile(
        uri: Uri,
        metadata: DiagnosticsSelectedFile,
    ): DiagnosticsUploadFile {
        val bytes = openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to read selected diagnostics file.")
        return DiagnosticsUploadFile(
            filename = metadata.filename,
            bytes = bytes,
            contentType = metadata.contentType,
        )
    }

    private fun contentTypeForDiagnosticsFilename(filename: String): String = when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "zip" -> "application/zip"
        "json" -> "application/json"
        "log", "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
