package dev.wuxie233.codecarry.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFixtureLoadTest {
    @Test
    fun `runtime status fixture contains the seven servers`() {
        val raw = javaClass.getResource("/mcp/runtime-status-seven-servers.json")!!.readText()
        val expected = listOf("aceTool", "autoinfo", "exa", "fetch", "github", "playwright", "stitch")
        for (name in expected) {
            assertTrue("expected key $name in fixture", raw.contains("\"$name\""))
        }
        // No accidental secret-looking field
        assertEquals(false, raw.contains("token"))
        assertEquals(false, raw.contains("Authorization"))
    }
}
