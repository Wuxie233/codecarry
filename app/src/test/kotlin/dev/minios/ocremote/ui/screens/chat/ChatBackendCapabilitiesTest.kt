package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.ServerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackendCapabilitiesTest {

    @Test
    fun `pi stack exposes only text chat controls`() {
        val piStack = ServerType.entries.first { it.name == "PI_STACK" }

        val capabilities = chatBackendCapabilities(piStack)

        assertFalse(capabilities.attachments)
        assertFalse(capabilities.fileMentions)
        assertFalse(capabilities.modelAndAgentSelection)
        assertFalse(capabilities.sessionExtras)
        assertFalse(capabilities.shellAndTerminal)
        assertFalse(capabilities.slashCommands)
    }

    @Test
    fun `opencode retains all existing chat controls`() {
        val capabilities = chatBackendCapabilities(ServerType.OPENCODE)

        assertTrue(capabilities.attachments)
        assertTrue(capabilities.fileMentions)
        assertTrue(capabilities.modelAndAgentSelection)
        assertTrue(capabilities.sessionExtras)
        assertTrue(capabilities.shellAndTerminal)
        assertTrue(capabilities.slashCommands)
    }

    @Test
    fun `roundtable preserves its existing composer and terminal behavior`() {
        val capabilities = chatBackendCapabilities(ServerType.PI_ROUNDTABLE)

        assertTrue(capabilities.attachments)
        assertFalse(capabilities.fileMentions)
        assertFalse(capabilities.modelAndAgentSelection)
        assertTrue(capabilities.sessionExtras)
        assertTrue(capabilities.shellAndTerminal)
        assertTrue(capabilities.slashCommands)
    }
}
