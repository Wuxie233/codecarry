package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.McpConfig
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
        val root = Json.parseToJsonElement(rawJson).jsonObject
        val mcpServers = root["mcpServers"]?.jsonObject ?: return null

        val servers = mcpServers.entries.associate { (name, element) ->
            val obj = element.jsonObject
            name to McpServer(
                name = name,
                type = obj["type"]?.jsonPrimitive?.contentOrNull,
                command = obj["command"]?.jsonPrimitive?.contentOrNull,
                args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList(),
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }

        return McpConfig(filePath = filePath, rawJson = rawJson, servers = servers)
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
