package dev.wuxie233.codecarry.domain.model

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
enum class ServerType {
    OPENCODE,
    CODEX,
    PI_ROUNDTABLE,
    PI_STACK,
}

internal fun normalizePiStackControlUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return runCatching {
        val uri = URI(trimmed)
        require(uri.scheme in setOf("http", "https") && uri.host != null)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        URI(
            uri.scheme,
            null,
            uri.host,
            uri.port,
            path.ifEmpty { "/control" },
            null,
            null,
        ).toString()
    }.getOrDefault(trimmed)
}

/**
 * Server Configuration - stored server connection details
 */
@Serializable
data class ServerConfig(
    val id: String, // UUID
    val type: ServerType = ServerType.OPENCODE,
    val url: String, // e.g. http://192.168.1.100:4096
    val username: String = "opencode",
    val password: String? = null,
    val token: String? = null, // Pi token or Codex app-server bearer token
    val name: String? = null, // User-friendly name
    val autoConnect: Boolean = false,
    val lastConnected: Long? = null,
    val isHealthy: Boolean = false
) {
    val displayName: String
        get() = name ?: url
    
    val host: String
        get() = try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url.substringAfter("://").substringBefore(":")
        }
    
    val port: Int
        get() = try {
            val parsed = java.net.URL(url)
            val explicitPort = parsed.port
            if (explicitPort != -1) {
                explicitPort
            } else {
                parsed.defaultPort // 80 for http, 443 for https
            }
        } catch (e: Exception) {
            url.substringAfterLast(":").toIntOrNull() ?: 80
        }
}

/**
 * Server Health - result of health check
 */
@Serializable
data class ServerHealth(
    val healthy: Boolean,
    val version: String? = null
)
