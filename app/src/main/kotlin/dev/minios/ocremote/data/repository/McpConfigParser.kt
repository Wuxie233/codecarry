package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object McpConfigParser {

    private val prettyJson = Json { prettyPrint = true }

    fun parse(filePath: String, rawJson: String): McpConfig? {
        return when (val state = parseState(filePath, rawJson)) {
            is McpConfigLoadState.Loaded -> state.config
            is McpConfigLoadState.Empty -> state.config
            is McpConfigLoadState.Error -> throw state.cause ?: IllegalArgumentException(state.message)
            is McpConfigLoadState.NotFound -> null
        }
    }

    fun parseState(filePath: String, rawJson: String): McpConfigLoadState {
        return try {
            val root = Json.parseToJsonElement(rawJson).jsonObject
            val mcpServersElement = root["mcpServers"]
                ?: return McpConfigLoadState.Empty(
                    McpConfig(filePath = filePath, rawJson = rawJson, servers = emptyMap())
                )
            val mcpServers = mcpServersElement as? JsonObject
                ?: return McpConfigLoadState.Error(filePath, "Invalid mcpServers section")

            val servers = buildMap {
                for ((name, element) in mcpServers.entries) {
                    val obj = element as? JsonObject
                        ?: return McpConfigLoadState.Error(filePath, "Invalid MCP server entry: $name")
                    val command = obj["command"]?.jsonPrimitive?.contentOrNull
                        ?: return McpConfigLoadState.Error(filePath, "MCP server '$name' is missing required command")
                    put(
                        name,
                        McpServer(
                            name = name,
                            type = obj["type"]?.jsonPrimitive?.contentOrNull,
                            command = command,
                            args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList(),
                            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        )
                    )
                }
            }

            val config = McpConfig(filePath = filePath, rawJson = rawJson, servers = servers)
            if (servers.isEmpty()) McpConfigLoadState.Empty(config) else McpConfigLoadState.Loaded(config)
        } catch (error: Exception) {
            McpConfigLoadState.Error(
                filePath = filePath,
                message = error.message ?: "Failed to parse MCP config",
                cause = error,
            )
        }
    }

    fun serialize(config: McpConfig): String {
        val root = Json.parseToJsonElement(config.rawJson).jsonObject.toMutableMap()
        val mcpServers = root["mcpServers"]?.jsonObject?.toMutableMap() ?: mutableMapOf()

        config.servers.forEach { (name, server) ->
            val existing = mcpServers[name]?.jsonObject?.toMutableMap() ?: mutableMapOf()
            existing["enabled"] = JsonPrimitive(server.enabled)
            mcpServers[name] = JsonObject(existing)
        }

        root["mcpServers"] = JsonObject(mcpServers)
        return prettyJson.encodeToString(JsonObject(root))
    }
}
