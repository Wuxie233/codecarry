package dev.minios.ocremote.data.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class NetworkDiagnosticsRecorder @Inject constructor(
    private val logRepository: DiagnosticsLogRepository,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val lock = Any()
    private val summaries = ArrayDeque<NetworkDiagnosticSummary>()

    fun record(summary: NetworkDiagnosticSummary) {
        synchronized(lock) {
            if (summaries.size >= MAX_SUMMARIES) summaries.removeFirst()
            summaries.addLast(summary.sanitized())
        }
    }

    fun snapshot(): List<NetworkDiagnosticSummary> = synchronized(lock) { summaries.toList() }

    fun createArtifact(
        createdAtMillis: Long = System.currentTimeMillis(),
        serverId: String? = null,
        serverName: String? = null,
    ): DiagnosticsLogItem = createArtifact(
        summaries = snapshot(),
        createdAtMillis = createdAtMillis,
        serverId = serverId,
        serverName = serverName,
    )

    fun createArtifact(
        summaries: List<NetworkDiagnosticSummary>,
        createdAtMillis: Long = System.currentTimeMillis(),
        serverId: String? = summaries.lastOrNull()?.serverId,
        serverName: String? = summaries.lastOrNull()?.serverName,
    ): DiagnosticsLogItem {
        val content = buildArtifactContent(summaries, createdAtMillis)
        return logRepository.createLog(
            type = DiagnosticsLogType.NETWORK_DIAGNOSTIC,
            displayName = "Network diagnostic",
            content = content,
            createdAtMillis = createdAtMillis,
            sessionId = null,
            serverName = serverName,
        )
    }

    fun buildArtifactContent(
        summaries: List<NetworkDiagnosticSummary>,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): String {
        val payload = NetworkDiagnosticPayload(
            generatedAtMillis = generatedAtMillis,
            summaries = summaries.map { it.sanitized().toPayload() },
        )
        return DiagnosticsRedactor.redact(json.encodeToString(payload))
    }

    private fun NetworkDiagnosticSummary.sanitized(): NetworkDiagnosticSummary = copy(
        method = NetworkDiagnosticSanitizer.method(method),
        pathCategory = NetworkDiagnosticSanitizer.pathCategory(pathCategory),
        durationMillis = max(0L, durationMillis),
        failureType = failureType?.let(NetworkDiagnosticSanitizer::failureType),
        serverId = serverId?.redactedOrNull(),
        serverName = serverName?.redactedOrNull(),
    )

    private companion object {
        private const val MAX_SUMMARIES = 100
    }
}

data class NetworkDiagnosticSummary(
    val method: String,
    val pathCategory: String,
    val statusCode: Int? = null,
    val durationMillis: Long,
    val failureType: String? = null,
    val timestampMillis: Long,
    val serverId: String? = null,
    val serverName: String? = null,
)

object NetworkDiagnosticSummaryBuilder {
    fun success(
        method: String,
        path: String,
        statusCode: Int?,
        startedAtMillis: Long,
        completedAtMillis: Long = System.currentTimeMillis(),
        serverId: String? = null,
        serverName: String? = null,
    ): NetworkDiagnosticSummary = NetworkDiagnosticSummary(
        method = method,
        pathCategory = NetworkDiagnosticSanitizer.pathCategory(path),
        statusCode = statusCode,
        durationMillis = completedAtMillis - startedAtMillis,
        failureType = null,
        timestampMillis = completedAtMillis,
        serverId = serverId,
        serverName = serverName,
    )

    fun failure(
        method: String,
        path: String,
        statusCode: Int?,
        failure: Throwable,
        startedAtMillis: Long,
        completedAtMillis: Long = System.currentTimeMillis(),
        serverId: String? = null,
        serverName: String? = null,
    ): NetworkDiagnosticSummary = NetworkDiagnosticSummary(
        method = method,
        pathCategory = NetworkDiagnosticSanitizer.pathCategory(path),
        statusCode = statusCode,
        durationMillis = completedAtMillis - startedAtMillis,
        failureType = NetworkDiagnosticSanitizer.failureType(failure::class.simpleName ?: "Failure"),
        timestampMillis = completedAtMillis,
        serverId = serverId,
        serverName = serverName,
    )

    fun failure(
        method: String,
        path: String,
        statusCode: Int?,
        failureType: String,
        startedAtMillis: Long,
        completedAtMillis: Long = System.currentTimeMillis(),
        serverId: String? = null,
        serverName: String? = null,
    ): NetworkDiagnosticSummary = NetworkDiagnosticSummary(
        method = method,
        pathCategory = NetworkDiagnosticSanitizer.pathCategory(path),
        statusCode = statusCode,
        durationMillis = completedAtMillis - startedAtMillis,
        failureType = NetworkDiagnosticSanitizer.failureType(failureType),
        timestampMillis = completedAtMillis,
        serverId = serverId,
        serverName = serverName,
    )
}

object NetworkDiagnosticSanitizer {
    private val methodRegex = Regex("[^A-Z]")
    private val failureTypeRegex = Regex("[^A-Za-z0-9_.-]")

    fun method(value: String): String = value
        .trim()
        .substringBefore(' ')
        .uppercase()
        .replace(methodRegex, "")
        .take(12)
        .ifBlank { "UNKNOWN" }

    fun pathCategory(value: String): String {
        val path = value.substringBefore('?')
            .substringBefore('#')
            .substringAfter("://", missingDelimiterValue = value)
            .let { withoutScheme ->
                if (value.contains("://")) {
                    "/" + withoutScheme.substringAfter('/', missingDelimiterValue = "")
                } else {
                    withoutScheme
                }
            }
        val segments = path.trim().trim('/').split('/').filter { it.isNotBlank() }
        return when (segments.firstOrNull()) {
            "session" -> if (segments.size <= 1) "/session" else "/session/{id}"
            "event" -> "/event"
            "command" -> "/command"
            "upload" -> "/upload"
            else -> "unknown"
        }
    }

    fun failureType(value: String): String = DiagnosticsRedactor.redact(value)
        .substringBefore(':')
        .substringBefore('?')
        .replace(failureTypeRegex, "")
        .take(80)
        .ifBlank { "Failure" }
}

@Serializable
private data class NetworkDiagnosticPayload(
    val schema: String = "oc-remote.network-diagnostic.v1",
    @SerialName("generated_at_millis") val generatedAtMillis: Long,
    val summaries: List<NetworkDiagnosticSummaryPayload>,
)

@Serializable
private data class NetworkDiagnosticSummaryPayload(
    val method: String,
    @SerialName("path_category") val pathCategory: String,
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("duration_millis") val durationMillis: Long,
    @SerialName("failure_type") val failureType: String? = null,
    @SerialName("timestamp_millis") val timestampMillis: Long,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
)

private fun NetworkDiagnosticSummary.toPayload(): NetworkDiagnosticSummaryPayload = NetworkDiagnosticSummaryPayload(
    method = method,
    pathCategory = pathCategory,
    statusCode = statusCode,
    durationMillis = durationMillis,
    failureType = failureType,
    timestampMillis = timestampMillis,
    serverId = serverId,
    serverName = serverName,
)

private fun String.redactedOrNull(): String? = takeIf { it.isNotBlank() }
    ?.let(DiagnosticsRedactor::redact)
    ?.let { redacted -> if (redacted.contains("<redacted>")) "<redacted>" else redacted }
