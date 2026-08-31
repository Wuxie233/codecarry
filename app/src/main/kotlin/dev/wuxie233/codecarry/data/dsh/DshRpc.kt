package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object DshRpc {
    const val API_PREFIX = "/api/"
    const val REMOTE_MUX_PATH = "/api/remote.mux"
    const val EVENTS_ENDPOINT = "\$events"
    const val EVENTS_RESULT_ENDPOINT = "\$events/result"
    const val SESSION_CONTROL_ENDPOINT = "session/control"
    const val SESSION_FOLLOW_ENDPOINT = "session/follow"
    const val WORKSPACE_FOLLOW_ENDPOINT = "workspace/follow"

    val LOOPBACK_ONLY_METHODS: Set<String> = setOf(
        "agentPresets/read",
        "agentPresets/copy",
        "agentPresets/deletePreset",
        "settings/openAgentPresetDirectory",
        "directoryPicker/pick",
        "session/openWorkspacePath",
        "settings/openSettingsDocument",
        "credentials/describe",
        "credentials/set",
        "credentials/unset",
        "llm/discoverModels",
    )

    fun mintRpcId(): String = UUID.randomUUID().toString()

    fun mintRequestId(): String = mintRpcId()

    fun mintStreamId(): String = mintRpcId()

    fun unaryPath(method: String): String = "$API_PREFIX$method"

    fun isLoopbackOnly(method: String): Boolean = method in LOOPBACK_ONLY_METHODS

    fun argsPayload(args: JsonObject = JsonObject(emptyMap())): JsonObject =
        buildJsonObject { put("args", args) }

    fun requestArgs(request: JsonObject): JsonObject =
        buildJsonObject { put("request", request) }

    fun listRequestArgs(cursor: String? = null): JsonObject = buildJsonObject {
        put(
            "_request",
            buildJsonObject {
                cursor?.let { put("cursor", it) }
            },
        )
    }
}

fun isDshLoopbackHostname(hostname: String): Boolean {
    val host = hostname.trim().lowercase().removePrefix("[").removeSuffix("]")
    if (host == "localhost" || host == "::1") return true
    val parts = host.split('.')
    return parts.size == 4 &&
        parts[0] == "127" &&
        parts.all { part -> part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255 }
}

fun isDshLoopbackUrl(url: String): Boolean {
    return runCatching {
        val uri = URI(url.trim())
        val host = uri.host ?: uri.authority?.substringBefore('%')?.substringBefore(':') ?: return false
        isDshLoopbackHostname(host)
    }.getOrDefault(false)
}

fun dshHttpToWebSocketUrl(httpUrl: String, path: String): String {
    val trimmed = httpUrl.trim().trimEnd('/')
    val wsBase = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.removePrefix("https://").removePrefix("HTTPS://")
        trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
        else -> trimmed
    }.trimEnd('/')
    val suffix = if (path.startsWith("/")) path else "/$path"
    return wsBase + suffix
}

fun dshIndexUrl(baseUrl: String, token: String?): String {
    val root = baseUrl.trim().trimEnd('/') + "/"
    val secret = token?.takeIf { it.isNotBlank() } ?: return root
    return root + "?token=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
}

fun dshCookiePair(setCookie: String): String? {
    val first = setCookie.substringBefore(';').trim()
    val at = first.indexOf('=')
    if (at <= 0) return null
    val name = first.substring(0, at).trim()
    val value = first.substring(at + 1).trim()
    if (name.isEmpty() || value.isEmpty()) return null
    return "$name=$value"
}

internal fun parseQueryPairs(query: String?): List<Pair<String, String>> {
    if (query.isNullOrBlank()) return emptyList()
    return query.split('&').mapNotNull { part ->
        if (part.isBlank()) return@mapNotNull null
        val at = part.indexOf('=')
        if (at < 0) {
            URLDecoder.decode(part, StandardCharsets.UTF_8) to ""
        } else {
            URLDecoder.decode(part.substring(0, at), StandardCharsets.UTF_8) to
                URLDecoder.decode(part.substring(at + 1), StandardCharsets.UTF_8)
        }
    }
}

internal fun stripTokenQuery(url: String): Pair<String, String?> {
    val trimmed = url.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.trimEnd('/') to null
    val pairs = parseQueryPairs(uri.rawQuery ?: uri.query)
    val token = pairs.firstOrNull { it.first == "token" }?.second?.takeIf { it.isNotBlank() }
    val kept = pairs.filterNot { it.first == "token" }
    val query = kept.takeIf { it.isNotEmpty() }?.joinToString("&") { (key, value) ->
        val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
        if (value.isEmpty()) encodedKey
        else "$encodedKey=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }
    val stripped = URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, query, uri.fragment).toString()
    return stripped.trimEnd('/') to token
}

@Serializable
data class DshClientRequest(
    val type: String = "client-request",
    val rpcId: String,
    val method: String,
    val payload: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class DshServerResponse(
    val type: String = "server-response",
    val rpcId: String,
    val result: DshRpcResult,
)

@Serializable
data class DshServerRequest(
    val type: String = "server-request",
    val rpcId: String,
    val method: String,
    val payload: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class DshClientResponse(
    val type: String = "client-response",
    val rpcId: String,
    val result: DshRpcResult,
)

@Serializable
data class DshRpcResult(
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: DshRpcError? = null,
)

@Serializable
data class DshRpcError(
    val code: String,
    val message: String,
    val details: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class DshRpcReceipt(
    val accepted: Boolean,
    val reason: String? = null,
)

class DshRpcException(
    val rpcId: String?,
    val error: DshRpcError,
) : RuntimeException("${error.code}: ${error.message}")

class DshLoopbackUnavailableException(
    val method: String,
) : RuntimeException("method $method is loopback-only and unavailable for this Host")

class DshAuthRequiredException(
    message: String = "DSH authentication failed",
) : RuntimeException(message)

@Serializable
data class DshConnection(
    val baseUrl: String,
    val password: String? = null,
    val token: String? = null,
    @Transient val cookie: String? = null,
) {
    companion object {
        fun from(url: String, password: String? = null, token: String? = null): DshConnection {
            val (stripped, queryToken) = stripTokenQuery(url)
            return DshConnection(
                baseUrl = stripped,
                password = password?.takeIf { it.isNotBlank() },
                token = token?.takeIf { it.isNotBlank() } ?: queryToken,
            )
        }
    }

    val hasBasicAuth: Boolean get() = !password.isNullOrBlank()

    /**
     * Loopback URL, or a password-authenticated public proxy that rewrites
     * Host to loopback (dsh-auth). Passwordless LAN stays non-loopback.
     */
    val isLoopback: Boolean get() = isDshLoopbackUrl(baseUrl) || hasBasicAuth

    val basicAuthorization: String? get() {
        val secret = password ?: return null
        val token = java.util.Base64.getEncoder().encodeToString(":$secret".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }

    fun withCookie(cookie: String?): DshConnection = copy(cookie = cookie?.takeIf { it.isNotBlank() })
}

@Serializable
data class DshHostDescribe(
    val version: String = "",
    val cwd: String = "",
    val provider: String? = null,
    val model: String? = null,
    val attachedSessions: Int = 0,
    val home: String = "",
    val canOpenPath: Boolean = false,
)

fun dshHostDescribeFromReady(home: String): DshHostDescribe =
    DshHostDescribe(home = home, cwd = home)

enum class DshGenerationStatus {
    Disconnected,
    Connecting,
    Ready,
    Failed,
}

data class DshGenerationState(
    val generation: Long = 0,
    val status: DshGenerationStatus = DshGenerationStatus.Disconnected,
    val describe: DshHostDescribe? = null,
    val muxOpen: Boolean = false,
    val eventsReady: Boolean = false,
    val controlReady: Boolean = false,
    val workspaceReady: Boolean = false,
    val cookie: String? = null,
    val eventsClientId: String? = null,
    val error: String? = null,
) {
    /** Pure readiness of the supervision streams; `status` mirrors it for UI. */
    val isReady: Boolean
        get() = describe != null &&
            muxOpen &&
            eventsReady &&
            controlReady &&
            workspaceReady
}
