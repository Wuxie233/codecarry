package dev.minios.ocremote.data.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
