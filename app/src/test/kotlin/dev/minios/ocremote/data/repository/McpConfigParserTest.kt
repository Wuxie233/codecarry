package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.McpConfigLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigParserTest {

    private val validJson = """
        {
          "mcpServers": {
            "filesystem": {
              "type": "stdio",
              "command": "npx",
              "args": ["-y", "@mcp/server-filesystem"],
              "enabled": true
            },
            "disabled-server": {
              "command": "python",
              "args": ["-m", "mcp_server"],
              "enabled": false
            }
          },
          "providers": {}
        }
    """.trimIndent()

    @Test
    fun parseReturnsMcpConfigWithCorrectServers() {
        val config = McpConfigParser.parse("/tmp/config.json", validJson)

        assertNotNull(config)
        assertEquals(2, config!!.servers.size)

        val filesystem = config.servers["filesystem"]!!
        assertEquals("npx", filesystem.command)
        assertEquals(listOf("-y", "@mcp/server-filesystem"), filesystem.args)
        assertTrue(filesystem.enabled)
        assertFalse(config.servers["disabled-server"]!!.enabled)
    }

    @Test
    fun parseStateReturnsExplicitEmptyWhenNoMcpServersKey() {
        val json = """{"providers": {}}"""

        val result = McpConfigParser.parseState("/tmp/config.json", json)

        assertTrue(result is McpConfigLoadState.Empty)
    }

    @Test
    fun parseStateAcceptsTopLevelMcpKey() {
        val json = """{"mcp": {"x": {"command": "node"}}}"""

        val result = McpConfigParser.parseState("/tmp/config.json", json)

        assertTrue(result is McpConfigLoadState.Loaded)
        val config = (result as McpConfigLoadState.Loaded).config
        assertEquals(1, config.servers.size)
        assertEquals("node", config.servers["x"]!!.command)
    }

    @Test
    fun parseStateAcceptsRemoteServerWithoutCommand() {
        val json = """{"mcpServers": {"r": {"type": "remote", "url": "https://example"}}}"""

        val result = McpConfigParser.parseState("/tmp/config.json", json)

        assertTrue(result is McpConfigLoadState.Loaded)
        val config = (result as McpConfigLoadState.Loaded).config
        assertEquals("remote", config.servers["r"]!!.type)
        assertEquals(null, config.servers["r"]!!.command)
    }

    @Test
    fun serializePreservesUnknownFieldsAndUpdatesEnabled() {
        val config = McpConfigParser.parse("/tmp/config.json", validJson)!!
        val toggled = config.copy(
            servers = config.servers.mapValues { (name, server) ->
                if (name == "filesystem") server.copy(enabled = false) else server
            }
        )

        val output = McpConfigParser.serialize(toggled)
        val reparsed = McpConfigParser.parse("/tmp/config.json", output)!!

        assertFalse(reparsed.servers["filesystem"]!!.enabled)
        assertTrue(output.contains("\"providers\""))
    }

    @Test
    fun serializePreservesMcpAliasKey() {
        val json = """{"mcp": {"x": {"command": "node"}}}"""
        val config = McpConfigParser.parse("/tmp/config.json", json)!!

        val output = McpConfigParser.serialize(config)

        assertTrue(output.contains("\"mcp\""))
        assertFalse(output.contains("\"mcpServers\""))
        val reparsed = McpConfigParser.parse("/tmp/config.json", output)!!
        assertEquals("node", reparsed.servers["x"]!!.command)
    }

    @Test
    fun parseHandlesMissingEnabledFieldAsTrue() {
        val json = """{"mcpServers": {"s": {"command": "node"}}}"""

        val config = McpConfigParser.parse("/tmp/config.json", json)!!

        assertTrue(config.servers["s"]!!.enabled)
    }

    @Test
    fun parseStateReturnsExplicitEmptyWhenMcpServersIsEmpty() {
        val json = """{"mcpServers": {}, "providers": {}}"""

        val result = McpConfigParser.parseState("/tmp/config.json", json)

        assertTrue(result is McpConfigLoadState.Empty)
        assertEquals(0, (result as McpConfigLoadState.Empty).config.servers.size)
    }

    @Test
    fun parseStateReturnsErrorWhenServerEntryIsNull() {
        val json = """{"mcpServers": {"broken": null}}"""

        val result = McpConfigParser.parseState("/tmp/config.json", json)

        assertTrue(result is McpConfigLoadState.Error)
        assertEquals("/tmp/config.json", (result as McpConfigLoadState.Error).filePath)
    }

    @Test
    fun parseStateStillRejectsServerEntryThatIsNotJsonObject() {
        val json = """{"mcpServers": {"broken": true}}"""

        val result = McpConfigParser.parseState("/tmp/config.json", json)

        assertTrue(result is McpConfigLoadState.Error)
        assertEquals("/tmp/config.json", (result as McpConfigLoadState.Error).filePath)
    }
}
