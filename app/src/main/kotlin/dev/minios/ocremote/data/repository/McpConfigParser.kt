package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpServer
import dev.minios.ocremote.domain.model.McpSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object McpConfigParser {

    private val prettyJson = Json { prettyPrint = true }

    fun parse(filePath: String, rawJson: String): McpConfig? {
        return when (val state = parseState(filePath, rawJson)) {
            is McpConfigLoadState.Loaded -> state.config
            is McpConfigLoadState.Empty -> state.config
            is McpConfigLoadState.Error -> throw IllegalArgumentException(state.message)
            is McpConfigLoadState.NotFound -> null
            is McpConfigLoadState.RuntimeUnavailable -> null
        }
    }

    fun parseState(filePath: String, rawJson: String): McpConfigLoadState {
        return try {
            val root = Json.parseToJsonElement(rawJson).jsonObject
            val mcpKey = when {
                root.containsKey("mcp") -> "mcp"
                root.containsKey("mcpServers") -> "mcpServers"
                else -> null
            }
            val mcpElement = mcpKey?.let { root[it] }
                ?: return McpConfigLoadState.Empty(
                    McpConfig(filePath = filePath, rawJson = rawJson, servers = emptyMap()),
                    source = McpSource.File,
                )
            val mcpObject = mcpElement as? JsonObject
                ?: return McpConfigLoadState.Error(filePath, "Invalid $mcpKey section")

            val servers = buildMap {
                for ((name, element) in mcpObject.entries) {
                    val obj = element as? JsonObject
                        ?: return McpConfigLoadState.Error(filePath, "Invalid MCP server entry: $name")
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull
                    val (command, commandArgs) = parseCommand(obj["command"])
                    val explicitArgs = parseStringArray(obj["args"])
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    val hasRemoteUrl = url != null && (type == "remote" || command == null)
                    if (command == null && !hasRemoteUrl) {
                        return McpConfigLoadState.Error(
                            filePath,
                            "MCP server '$name' is missing required command (or remote url)",
                        )
                    }
                    put(
                        name,
                        McpServer(
                            name = name,
                            type = type,
                            command = command,
                            args = commandArgs + explicitArgs,
                            url = url,
                            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        )
                    )
                }
            }

            val config = McpConfig(filePath = filePath, rawJson = rawJson, servers = servers)
            if (servers.isEmpty()) {
                McpConfigLoadState.Empty(config, source = McpSource.File)
            } else {
                McpConfigLoadState.Loaded(config, source = McpSource.File)
            }
        } catch (error: Exception) {
            McpConfigLoadState.Error(
                filePath = filePath,
                message = sanitizedParseErrorMessage(),
                cause = error,
            )
        }
    }

    private fun parseCommand(element: JsonElement?): Pair<String?, List<String>> {
        if (element == null) return null to emptyList()
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.let { it to emptyList() } ?: (null to emptyList())
            is JsonArray -> {
                val parts = element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (parts.isEmpty()) null to emptyList() else parts.first() to parts.drop(1)
            }
            else -> null to emptyList()
        }
    }

    private fun parseStringArray(element: JsonElement?): List<String> {
        return (element as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
    }

    private fun sanitizedParseErrorMessage(): String {
        return "Failed to parse MCP config"
    }

    fun serialize(config: McpConfig): String {
        val root = Json.parseToJsonElement(config.rawJson).jsonObject.toMutableMap()
        val mcpKey = when {
            root.containsKey("mcp") -> "mcp"
            root.containsKey("mcpServers") -> "mcpServers"
            else -> "mcp"
        }
        val mcpServers = (root[mcpKey] as? JsonObject)?.toMutableMap() ?: mutableMapOf()

        config.servers.forEach { (name, server) ->
            val existing = mcpServers[name]?.jsonObject?.toMutableMap() ?: mutableMapOf()
            if (server.type != null && "type" !in existing) {
                existing["type"] = JsonPrimitive(server.type)
            }
            if (server.command != null && "command" !in existing) {
                existing["command"] = JsonPrimitive(server.command)
            }
            if (server.args.isNotEmpty() && "args" !in existing) {
                existing["args"] = JsonArray(server.args.map { JsonPrimitive(it) })
            }
            if (server.url != null && "url" !in existing) {
                existing["url"] = JsonPrimitive(server.url)
            }
            existing["enabled"] = JsonPrimitive(server.enabled)
            mcpServers[name] = JsonObject(existing)
        }

        root[mcpKey] = JsonObject(mcpServers)
        return prettyJson.encodeToString(JsonObject(root))
    }
}
