package dev.minios.ocremote.ui.screens.settings

import dev.minios.ocremote.domain.model.GitHubRelease
import java.io.File

sealed class AppUpdateUiState {
    object Idle : AppUpdateUiState()
    object Checking : AppUpdateUiState()
    object UpToDate : AppUpdateUiState()
    data class UpdateAvailable(
        val release: GitHubRelease,
        val selectedAsset: GitHubRelease.Asset,
    ) : AppUpdateUiState()
    data class Downloading(
        val progressPercent: Int = 0,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long? = null,
    ) : AppUpdateUiState()
    data class ReadyToInstall(
        val apkFile: File,
        val release: GitHubRelease,
    ) : AppUpdateUiState()
    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : AppUpdateUiState()
}
