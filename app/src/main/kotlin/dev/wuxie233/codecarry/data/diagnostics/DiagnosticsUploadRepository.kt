package dev.wuxie233.codecarry.data.diagnostics

import dev.wuxie233.codecarry.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

@Singleton
class DiagnosticsUploadRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val uploadClient: DiagnosticsUploadClient,
) {
    private val mutableState = MutableStateFlow<DiagnosticsUploadState>(DiagnosticsUploadState.Idle)
    val state: StateFlow<DiagnosticsUploadState> = mutableState.asStateFlow()

    private var selectedFile: PendingDiagnosticsUploadFile? = null

    fun selectFile(file: DiagnosticsUploadFile) {
        selectFile(file.toSelectedFile()) { file }
    }

    fun selectFile(
        file: DiagnosticsSelectedFile,
        openFile: suspend () -> DiagnosticsUploadFile,
    ) {
        selectedFile = PendingDiagnosticsUploadFile(file, openFile)
        mutableState.value = DiagnosticsUploadState.FileSelected(file)
    }

    fun clearSelection() {
        selectedFile = null
        mutableState.value = DiagnosticsUploadState.Idle
    }

    fun showError(message: String) {
        val selected = selectedFile?.file
        mutableState.value = DiagnosticsUploadState.Error(
            message = DiagnosticsRedactor.redact(message),
            file = selected,
        )
    }

    suspend fun uploadSelectedFile(): DiagnosticsUploadState {
        val pendingFile = selectedFile ?: return mutableState.value
        val selected = pendingFile.file
        val uploadUrl = settingsRepository.diagnosticsUploadUrl.first().trim()
        val bearerToken = settingsRepository.diagnosticsUploadToken.first().trim()

        if (uploadUrl.isBlank()) {
            val error = DiagnosticsUploadState.Error(
                message = DiagnosticsUploadException.MissingUploadUrl().message.orEmpty(),
                file = selected,
            )
            mutableState.value = error
            return error
        }

        if (bearerToken.isBlank()) {
            val error = DiagnosticsUploadState.Error(
                message = DiagnosticsUploadException.MissingBearerToken().message.orEmpty(),
                file = selected,
            )
            mutableState.value = error
            return error
        }

        mutableState.value = DiagnosticsUploadState.Uploading(selected)

        return try {
            val file = pendingFile.openFile()
            val response = uploadClient.upload(
                config = DiagnosticsUploadConfig(
                    uploadUrl = uploadUrl,
                    bearerToken = bearerToken,
                ),
                file = file,
            )
            val success = DiagnosticsUploadState.Success(reportId = response.id, file = selected)
            selectedFile = null
            mutableState.value = success
            success
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val failure = DiagnosticsUploadState.Error(
                message = DiagnosticsRedactor.redact(error.message ?: "Diagnostics upload failed."),
                file = selected,
            )
            mutableState.value = failure
            failure
        }
    }

    private fun DiagnosticsUploadFile.toSelectedFile(): DiagnosticsSelectedFile = DiagnosticsSelectedFile(
        filename = filename,
        sizeBytes = bytes.size.toLong(),
        contentType = contentType,
    )
}

private data class PendingDiagnosticsUploadFile(
    val file: DiagnosticsSelectedFile,
    val openFile: suspend () -> DiagnosticsUploadFile,
)

data class DiagnosticsSelectedFile(
    val filename: String,
    val sizeBytes: Long,
    val contentType: String,
)

sealed class DiagnosticsUploadState {
    object Idle : DiagnosticsUploadState()
    data class FileSelected(val file: DiagnosticsSelectedFile) : DiagnosticsUploadState()
    data class Uploading(val file: DiagnosticsSelectedFile) : DiagnosticsUploadState()
    data class Success(
        val reportId: String,
        val file: DiagnosticsSelectedFile,
    ) : DiagnosticsUploadState()
    data class Error(
        val message: String,
        val file: DiagnosticsSelectedFile? = null,
    ) : DiagnosticsUploadState()
}
