package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.data.api.CommandInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandMergeTest {

    @Test
    fun mergeSurfacesMcpSourceLabel() {
        val merged = mergeSlashCommands(
            client = emptyList(),
            server = listOf(
                CommandInfo(name = "deploy", description = "Deploy app", source = "mcp"),
                CommandInfo(name = "review", description = "Review changes", source = "command"),
            ),
        )

        assertEquals("mcp", merged.first { it.name == "deploy" }.source)
        assertEquals("command", merged.first { it.name == "review" }.source)
        assertEquals("server", merged.first { it.name == "deploy" }.type)
    }

    @Test
    fun mergeFiltersOutSkillSource() {
        val merged = mergeSlashCommands(
            client = emptyList(),
            server = listOf(
                CommandInfo(name = "skill-command", description = "Hidden", source = "skill"),
                CommandInfo(name = "mcp-command", description = "Visible", source = "mcp"),
            ),
        )

        assertEquals(listOf("mcp-command"), merged.map { it.name })
    }

    @Test
    fun mergeDeduplicatesByName() {
        val client = listOf(SlashCommand(name = "new", description = "New session", type = "client"))

        val merged = mergeSlashCommands(
            client = client,
            server = listOf(
                CommandInfo(name = "new", description = "Server duplicate", source = "command"),
                CommandInfo(name = "deploy", description = "Deploy app", source = "mcp"),
            ),
        )

        assertEquals(listOf("new", "deploy"), merged.map { it.name })
        assertEquals("client", merged.first { it.name == "new" }.type)
    }

    @Test
    fun mergeWithEmptyServerListReturnsClientOnly() {
        val client = listOf(
            SlashCommand(name = "new", description = "New session", type = "client"),
            SlashCommand(name = "compact", description = "Compact session", type = "client"),
        )

        val merged = mergeSlashCommands(client = client, server = emptyList())

        assertEquals(client, merged)
        assertNull(merged.first().source)
    }
}
