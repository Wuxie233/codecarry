package dev.wuxie233.codecarry.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.util.ApkInstaller

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}

private fun formatDownloadStatus(state: AppUpdateUiState.Downloading): String {
    val downloaded = formatBytes(state.downloadedBytes)
    val speed = if (state.speedBytesPerSec > 0) formatBytes(state.speedBytesPerSec) + "/s" else ""
    return if (state.totalBytes != null && state.totalBytes > 0) {
        val total = formatBytes(state.totalBytes)
        if (speed.isNotEmpty()) "$downloaded / $total  $speed" else "$downloaded / $total"
    } else {
        if (speed.isNotEmpty()) "$downloaded  $speed" else downloaded
    }
}

@Composable
fun AppUpdateDialog(
    state: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is AppUpdateUiState.Idle) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (state !is AppUpdateUiState.Checking && state !is AppUpdateUiState.Downloading) onDismiss() },
        title = { Text(stringResource(R.string.settings_check_for_updates)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (state) {
                    is AppUpdateUiState.Checking -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.settings_update_checking))
                    }
                    is AppUpdateUiState.UpToDate -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(stringResource(R.string.settings_update_up_to_date))
                    }
                    is AppUpdateUiState.UpdateAvailable -> {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(stringResource(R.string.settings_update_available, state.release.tagName))
                        state.release.body?.takeIf { it.isNotBlank() }?.let { notes ->
                            Text(
                                text = notes.take(500),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    is AppUpdateUiState.Downloading -> {
                        if (state.totalBytes != null && state.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { state.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = formatDownloadStatus(state),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is AppUpdateUiState.ReadyToInstall -> {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(stringResource(R.string.settings_update_ready, state.release.tagName))
                    }
                    is AppUpdateUiState.Error -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateUiState.UpdateAvailable -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.settings_update_download))
                }
                is AppUpdateUiState.ReadyToInstall -> Button(onClick = {
                    ApkInstaller.installApk(context, state.apkFile)
                }) {
                    Text(stringResource(R.string.settings_update_install))
                }
                is AppUpdateUiState.Error -> Button(onClick = onCheckForUpdates) {
                    Text(stringResource(R.string.retry))
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is AppUpdateUiState.Checking && state !is AppUpdateUiState.Downloading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    )
}
