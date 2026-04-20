package dev.minios.ocremote.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun parseReturnsNullWhenNoMcpServersKey() {
        val json = """{"providers": {}}"""

        assertNull(McpConfigParser.parse("/tmp/config.json", json))
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
    fun parseHandlesMissingEnabledFieldAsTrue() {
        val json = """{"mcpServers": {"s": {"command": "node"}}}"""

        val config = McpConfigParser.parse("/tmp/config.json", json)!!

        assertTrue(config.servers["s"]!!.enabled)
    }
}
