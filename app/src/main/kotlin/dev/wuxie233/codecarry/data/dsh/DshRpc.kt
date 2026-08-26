package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.UUID

object DshRpc {
    const val API_PREFIX = "/api/"
    const val RESPOND_PATH = "/api/respond"
    const val MUX_EVENTS_PATH = "/api/events.mux"
    const val HOST_EVENTS_PATH = "/api/events.host"

    val LOOPBACK_ONLY_METHODS: Set<String> = setOf(
        "agentPreset.read",
        "agentPreset.copy",
        "agentPreset.openDocument",
        "agentPreset.remove",
        "host.pickDirectory",
        "host.openPath",
        "settings.openDocument",
        "credentials.describe",
        "credentials.set",
        "credentials.unset",
        "llm.discoverModels",
    )

    fun mintRpcId(): String = UUID.randomUUID().toString()

    fun unaryPath(method: String): String = "$API_PREFIX$method"

    fun isLoopbackOnly(method: String): Boolean = method in LOOPBACK_ONLY_METHODS
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

fun dshPublicHostRequiresPassword(url: String): Boolean = !isDshLoopbackUrl(url)

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
) {
    companion object {
        fun from(url: String, password: String? = null): DshConnection {
            val trimmed = url.trim().trimEnd('/')
            return DshConnection(
                baseUrl = trimmed,
                password = password?.takeIf { it.isNotBlank() },
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
}

@Serializable
data class DshHostDescribe(
    val version: String,
    val cwd: String,
    val provider: String? = null,
    val model: String? = null,
    val attachedSessions: Int,
    val home: String,
    val canOpenPath: Boolean,
)

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
    val hostOpen: Boolean = false,
    val error: String? = null,
) {
    val isReady: Boolean
        get() = status == DshGenerationStatus.Ready &&
            describe != null &&
            muxOpen &&
            hostOpen
}
