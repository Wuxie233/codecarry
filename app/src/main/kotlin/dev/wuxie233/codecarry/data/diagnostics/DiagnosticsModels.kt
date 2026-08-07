package dev.wuxie233.codecarry.data.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticsLogType(
    val storageSegment: String,
    val defaultFileExtension: String,
) {
    @SerialName("app_event")
    APP_EVENT("app-event", "log"),

    @SerialName("session_diagnostic")
    SESSION_DIAGNOSTIC("session-diagnostic", "json"),

    @SerialName("network_diagnostic")
    NETWORK_DIAGNOSTIC("network-diagnostic", "json"),
}

@Serializable
data class DiagnosticsLogItem(
    val id: String,
    val type: DiagnosticsLogType,
    val displayName: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val relativePath: String,
    val sessionId: String? = null,
    val serverName: String? = null,
)

@Serializable
data class DiagnosticsUploadResponse(
    val id: String,
    val filename: String,
    val size: Long,
    @SerialName("stored_at") val storedAt: String,
    val sha256: String,
)

data class DiagnosticsUploadConfig(
    val uploadUrl: String,
    val bearerToken: String,
)

data class DiagnosticsUploadFile(
    val filename: String,
    val bytes: ByteArray,
    val contentType: String = "application/octet-stream",
) {
    init {
        require(filename.isNotBlank()) { "Diagnostics upload filename is required." }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiagnosticsUploadFile) return false

        if (filename != other.filename) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return contentType == other.contentType
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

sealed class DiagnosticsUploadException(message: String) : IllegalStateException(message) {
    class MissingUploadUrl : DiagnosticsUploadException("Diagnostics upload URL is required before uploading diagnostics.")
    class MissingBearerToken : DiagnosticsUploadException("Diagnostics bearer token is required before uploading diagnostics.")
}
