package dev.wuxie233.codecarry.data.repository

import dev.wuxie233.codecarry.domain.model.McpConfigLoadState
import dev.wuxie233.codecarry.domain.model.McpSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigParserOfficialSchemaTest {

    @Test
    fun `command as string array splits into command plus args`() {
        val json = """{"mcp":{"x":{"command":["node","script.js","--flag"]}}}"""
        val state = McpConfigParser.parseState("/cfg", json)
        assertTrue(state is McpConfigLoadState.Loaded)
        val server = (state as McpConfigLoadState.Loaded).config.servers["x"]!!
        assertEquals("node", server.command)
        assertEquals(listOf("script.js", "--flag"), server.args)
    }

    @Test
    fun `command array combines with explicit args field`() {
        val json = """{"mcp":{"x":{"command":["node","a.js"],"args":["--b"]}}}"""
        val state = McpConfigParser.parseState("/cfg", json) as McpConfigLoadState.Loaded
        val server = state.config.servers["x"]!!
        assertEquals("node", server.command)
        // command-array tail comes first, then explicit args, preserving deterministic order.
        assertEquals(listOf("a.js", "--b"), server.args)
    }

    @Test
    fun `remote entry with url and no command parses successfully`() {
        val json = """{"mcp":{"r":{"type":"remote","url":"https://x.example/mcp","enabled":true}}}"""
        val state = McpConfigParser.parseState("/cfg", json) as McpConfigLoadState.Loaded
        val server = state.config.servers["r"]!!
        assertEquals("remote", server.type)
        assertEquals(null, server.command)
        assertEquals("https://x.example/mcp", server.url)
        assertEquals(true, server.enabled)
    }

    @Test
    fun `top-level mcp wins over mcpServers when both present`() {
        val json = """
            {
              "mcp": {"a": {"command": "node"}},
              "mcpServers": {"b": {"command": "python"}}
            }
        """.trimIndent()
        val state = McpConfigParser.parseState("/cfg", json) as McpConfigLoadState.Loaded
        assertEquals(setOf("a"), state.config.servers.keys)
        assertEquals(McpSource.File, state.source)
    }

    @Test
    fun `serialize defaults to mcp key when neither present originally`() {
        val empty = McpConfigParser.parse("/cfg", """{"providers":{}}""")
        // Empty parse returns Empty -> via parse() returns config with servers=emptyMap; we add a new server then serialize.
        val withServer = empty!!.copy(
            servers = mapOf(
                "n" to dev.wuxie233.codecarry.domain.model.McpServer(
                    name = "n", type = null, command = "node",
                    args = emptyList(), url = null, enabled = true,
                ),
            ),
        )
        val out = McpConfigParser.serialize(withServer)
        assertTrue("output should include canonical key", out.contains("\"mcp\""))
    }

    @Test
    fun `parse error message must not echo raw json that may contain secrets`() {
        // Force a parse failure with malformed JSON that still contains secret-looking raw text.
        val fakeSecret = "sk-fake-review-secret-12345"
        val rawToken = "RAW_REVIEW_SECRET_TOKEN"
        val json = """{"mcp": {"bad": {"command": "node", "apiKey": "$fakeSecret", $rawToken }}}"""
        val state = McpConfigParser.parseState("/cfg", json)
        assertTrue(state is McpConfigLoadState.Error)
        val err = state as McpConfigLoadState.Error
        val message = err.message ?: ""
        assertTrue("must not leak fake secret", !message.contains(fakeSecret))
        assertTrue("must not leak raw token", !message.contains(rawToken))
    }
}
