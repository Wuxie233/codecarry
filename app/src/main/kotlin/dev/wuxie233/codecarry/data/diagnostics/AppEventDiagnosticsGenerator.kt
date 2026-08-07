package dev.wuxie233.codecarry.data.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppEventDiagnosticsGenerator @Inject constructor(
    private val logRepository: DiagnosticsLogRepository,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun createArtifact(
        breadcrumbs: List<AppEventBreadcrumb>,
        createdAtMillis: Long = System.currentTimeMillis(),
        sessionId: String? = null,
        serverName: String? = null,
    ): DiagnosticsLogItem {
        val content = buildArtifactContent(breadcrumbs)
        return logRepository.createLog(
            type = DiagnosticsLogType.APP_EVENT,
            displayName = "App event breadcrumbs",
            content = content,
            createdAtMillis = createdAtMillis,
            sessionId = sessionId,
            serverName = serverName,
        )
    }

    fun buildArtifactContent(breadcrumbs: List<AppEventBreadcrumb>): String {
        val payload = AppEventDiagnosticsPayload(
            breadcrumbs = breadcrumbs.map { breadcrumb ->
                AppEventBreadcrumbPayload(
                    name = breadcrumb.name.wireName,
                    timestampMillis = breadcrumb.timestampMillis,
                    sessionId = breadcrumb.sessionId?.redactedOrNull(),
                    serverId = breadcrumb.serverId?.redactedOrNull(),
                    serverName = breadcrumb.serverName?.redactedOrNull(),
                    directory = breadcrumb.directory?.redactedOrNull(),
                    details = breadcrumb.details.redactedMap(),
                )
            },
        )
        return DiagnosticsRedactor.redact(json.encodeToString(payload))
    }
}

data class AppEventBreadcrumb(
    val name: AppEventName,
    val timestampMillis: Long,
    val sessionId: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val directory: String? = null,
    val details: Map<String, String> = emptyMap(),
)

enum class AppEventName(val wireName: String) {
    APP_START("app_start"),
    SERVER_CONNECT("server_connect"),
    SERVER_DISCONNECT("server_disconnect"),
    SESSION_LIST_OPENED("session_list_opened"),
    CREATE_NEW_TAPPED("create_new_tapped"),
    CREATE_NEW_SUCCESS("create_new_success"),
    CREATE_NEW_FAILURE("create_new_failure"),
    NAVIGATION_TO_CHAT_REQUESTED("navigation_to_chat_requested"),
    UPLOAD_STARTED("upload_started"),
    UPLOAD_SUCCEEDED("upload_succeeded"),
    UPLOAD_FAILED("upload_failed"),
}

@Serializable
private data class AppEventDiagnosticsPayload(
    val schema: String = "oc-remote.app-events.v1",
    val breadcrumbs: List<AppEventBreadcrumbPayload>,
)

@Serializable
private data class AppEventBreadcrumbPayload(
    val name: String,
    @SerialName("timestamp_millis") val timestampMillis: Long,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    val directory: String? = null,
    val details: Map<String, String> = emptyMap(),
)

private fun String.redactedOrNull(): String? = takeIf { it.isNotBlank() }?.let(DiagnosticsRedactor::redact)

private fun Map<String, String>.redactedMap(): Map<String, String> = entries
    .filter { it.key.isNotBlank() }
    .associate { (key, value) -> DiagnosticsRedactor.redact(key) to DiagnosticsRedactor.redact(value) }
