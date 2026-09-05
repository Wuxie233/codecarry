package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class CodexDirectoryEntry(val name: String, val path: String)

data class CodexDirectoryListing(
    val path: String,
    val parentPath: String?,
    val directories: List<CodexDirectoryEntry>,
)

/** Remote paths must never be resolved using Android's filesystem semantics. */
fun normalizeCodexDirectoryPath(path: String): String? {
    if (path.isBlank() || '\u0000' in path) return null
    val windows = Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path) || path.startsWith("\\\\")
    val value = if (windows) path.replace('\\', '/') else path
    val root: String
    val suffix: String
    when {
        Regex("^[A-Za-z]:/").containsMatchIn(value) -> {
            root = value.take(3)
            suffix = value.drop(3)
        }
        value.startsWith("//") -> {
            val parts = value.drop(2).split('/').filter(String::isNotEmpty)
            if (parts.size < 2 || parts.take(2).any { it == "." || it == ".." }) return null
            root = "//${parts[0]}/${parts[1]}"
            suffix = parts.drop(2).joinToString("/")
        }
        value.startsWith('/') -> {
            root = "/"
            suffix = value.drop(1)
        }
        else -> return null
    }
    // Preserve dot components: lexical collapse of symlink/.. changes remote meaning.
    val tail = suffix.trimEnd('/')
    val normalized = if (tail.isEmpty()) root else root.trimEnd('/') + "/" + tail
    return if (windows) normalized.replace('/', '\\') else normalized
}

fun codexDirectoryParent(path: String): String? {
    val normalized = normalizeCodexDirectoryPath(path) ?: return null
    val separator = if (normalized.startsWith("\\\\") || Regex("^[A-Za-z]:\\\\").containsMatchIn(normalized)) '\\' else '/'
    val root = when {
        normalized.startsWith("\\\\") -> normalized.split('\\').filter(String::isNotEmpty).take(2).joinToString("\\", prefix = "\\\\")
        normalized.startsWith("//") -> normalized.drop(2).split('/').take(2).joinToString("/", prefix = "//")
        separator == '\\' -> normalized.take(3)
        else -> "/"
    }
    if (normalized == root) return null
    val parent = normalized.substringBeforeLast(separator)
    return if (parent.length < root.length) root else parent
}

internal fun parseCodexDirectoryListing(path: String, response: JsonObject): CodexDirectoryListing {
    val entries = response["entries"] as? JsonArray
        ?: error("Invalid Codex fs/readDirectory response")
    val separator = if (path.startsWith("\\\\") || Regex("^[A-Za-z]:\\\\").containsMatchIn(path)) '\\' else '/'
    val directories = entries.map { element ->
        val entry = element as? JsonObject ?: error("Invalid Codex directory entry")
        val name = (entry["fileName"] as? JsonPrimitive)?.contentOrNull
            ?: error("Missing Codex directory entry name")
        val isDirectory = (entry["isDirectory"] as? JsonPrimitive)?.booleanOrNull
            ?: error("Missing Codex directory entry type")
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name && (separator != '\\' || '\\' !in name)) {
            "Invalid Codex directory entry name"
        }
        if (isDirectory) CodexDirectoryEntry(name, path.trimEnd(separator) + separator + name) else null
    }.filterNotNull().sortedWith(compareBy<CodexDirectoryEntry> { it.name.lowercase() }.thenBy { it.name })
    return CodexDirectoryListing(path, codexDirectoryParent(path), directories)
}
