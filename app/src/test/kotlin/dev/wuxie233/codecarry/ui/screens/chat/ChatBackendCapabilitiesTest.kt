package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.ServerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackendCapabilitiesTest {

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
    fun `dsh keeps chat controls except shell and terminal`() {
        val capabilities = chatBackendCapabilities(ServerType.DSH)

        assertTrue(capabilities.attachments)
        assertTrue(capabilities.fileMentions)
        assertTrue(capabilities.modelAndAgentSelection)
        assertTrue(capabilities.sessionExtras)
        assertFalse(capabilities.shellAndTerminal)
        assertTrue(capabilities.slashCommands)
    }
}
