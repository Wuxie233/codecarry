package dev.wuxie233.codecarry.data.codex

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Allocate the same private work/outputs directory layout used by desktop new chats. */
suspend fun CodexAppServerClient.prepareProjectlessWorkspace(prompt: String): String = withTimeout(30_000) {
    val host = connect()
    val windows = host.platformFamily == "windows"
    val separator = if (windows) '\\' else '/'
    fun join(parent: String, child: String) = parent.trimEnd(separator) + separator + child
    val config = request("config/read", buildJsonObject { put("includeLayers", false) }).objectOrEmpty()
    val desktop = (config["config"] as? JsonObject)?.get("desktop") as? JsonObject
    var configured = desktop?.get("projectlessWorkspaceRoot")
    // Desktop migrates this legacy setting into config.desktop on its next startup.
    if (configured == null) {
        host.codexHome?.let { codexHome ->
            val files = request("fs/readDirectory", buildJsonObject { put("path", codexHome) }).objectOrEmpty()
            val hasLegacy = (files["entries"] as? JsonArray).orEmpty().any {
                (it.objectOrEmpty()["fileName"] as? JsonPrimitive)?.contentOrNull == ".codex-global-state.json"
            }
            if (hasLegacy) configured = Json.parseToJsonElement(
                readTextFile(join(codexHome, ".codex-global-state.json")),
            ).objectOrEmpty()["projectlessWorkspaceRoot"]
        }
    }
    val configuredPath = configured?.takeUnless { it == JsonNull }?.let {
        require(it is JsonPrimitive && it.isString) { "Invalid projectless workspace setting" }
        it.content
    }
    val root = if (configuredPath != null) {
        requireNotNull(normalizeCodexDirectoryPath(configuredPath)) { "Projectless workspace must be absolute" }
    } else {
        // Read only the execution host's home, never infer it from CODEX_HOME.
        val command = if (windows) listOf("cmd.exe", "/d", "/c", "echo %USERPROFILE%")
        else listOf("/bin/sh", "-c", "printf '%s' \"\$HOME\"")
        val result = request("command/exec", buildJsonObject {
            put("command", JsonArray(command.map(::JsonPrimitive)))
            put("timeoutMs", 5_000)
            put("outputBytesCap", 16_384)
        }).objectOrEmpty()
        check((result["exitCode"] as? JsonPrimitive)?.intOrNull == 0) { "Unable to read remote home directory" }
        val home = requireNotNull(normalizeCodexDirectoryPath((result["stdout"] as? JsonPrimitive)?.contentOrNull.orEmpty().trimEnd('\r', '\n'))) {
            "Remote home directory is unavailable"
        }
        join(join(home, "Documents"), "Codex")
    }
    suspend fun create(path: String, recursive: Boolean) {
        request("fs/createDirectory", buildJsonObject { put("path", path); put("recursive", recursive) })
    }
    suspend fun verifyDirectory(path: String) {
        val metadata = request("fs/getMetadata", buildJsonObject { put("path", path) }).objectOrEmpty()
        check((metadata["isDirectory"] as? JsonPrimitive)?.booleanOrNull == true &&
            (metadata["isSymlink"] as? JsonPrimitive)?.booleanOrNull == false) {
            "Projectless workspace must be a real directory"
        }
    }
    create(root, true)
    verifyDirectory(root)
    val dateDirectory = join(root, LocalDate.now().toString())
    create(dateDirectory, true)
    verifyDirectory(dateDirectory)
    val slug = Regex("[a-z0-9]+").findAll(prompt.lowercase()).take(6)
        .joinToString("-") { it.value }.take(80).ifEmpty { "new-chat" }
    for (attempt in 0 until 105) {
        val suffix = when {
            attempt == 0 -> ""
            attempt < 100 -> "-${attempt + 1}"
            else -> "-${UUID.randomUUID()}"
        }
        val cwd = join(dateDirectory, slug + suffix)
        try {
            create(cwd, false)
        } catch (error: CodexRpcException) {
            // Only an existing real directory is a collision; all other failures remain errors.
            try {
                verifyDirectory(cwd)
            } catch (checkError: CancellationException) {
                throw checkError
            } catch (_: Exception) {
                throw error
            }
            continue
        }
        verifyDirectory(cwd)
        create(join(cwd, "outputs"), false)
        create(join(cwd, "work"), false)
        return@withTimeout cwd
    }
    error("Unable to create a unique projectless thread directory")
}
