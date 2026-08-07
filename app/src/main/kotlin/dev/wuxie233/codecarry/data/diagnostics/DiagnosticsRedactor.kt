package dev.wuxie233.codecarry.data.diagnostics

object DiagnosticsRedactor {
    private const val REDACTED = "<redacted>"

    private val bearerRegex = Regex("(?i)\\bBearer\\s+[^\\s,;\\\\\\\"'{}\\[\\]]+")
    private val assignmentSecretRegex = Regex(
        pattern = """(?i)\b(password|api[_-]?key|apikey|token|secret|upload[_-]?token)\b\s*([:=])\s*(\\"[^\\"\\]*(?:\\.[^\\"\\]*)*\\"|"[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|[^\s,;&\\"'{}\[\]]+)""",
    )
    private val headerSecretRegex = Regex(
        pattern = "(?im)^\\s*(authorization|cookie)\\s*:\\s*.+$",
    )
    private val cookiePairRegex = Regex("(?i)\\b(cookie)\\s*=\\s*([^\\s,;\\\\\\\"'{}\\[\\]]+)")
    private val querySecretRegex = Regex(
        pattern = "(?i)([?&](?:token|access_token|auth|authorization|password|api[_-]?key|apikey|key|secret|upload[_-]?token|cookie)=)[^&#\\s\\\\\\\"',}\\]]+",
    )

    fun redact(value: String): String = value
        .replace(headerSecretRegex) { match ->
            val name = match.groupValues[1]
            "${name}: $REDACTED"
        }
        .replace(bearerRegex, "Bearer $REDACTED")
        .replace(assignmentSecretRegex) { match ->
            val key = match.groupValues[1]
            val separator = match.groupValues[2]
            "$key$separator$REDACTED"
        }
        .replace(cookiePairRegex) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }
        .replace(querySecretRegex) { match ->
            "${match.groupValues[1]}$REDACTED"
        }
}
