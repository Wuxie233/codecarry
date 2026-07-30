package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.PiStackCapabilitiesDto
import dev.minios.ocremote.data.api.PiStackPermissionsCapabilityDto
import dev.minios.ocremote.data.api.PiStackQuestionCapabilityDto
import dev.minios.ocremote.data.api.PiStackRuntimeCapabilityDto
import dev.minios.ocremote.data.api.PiStackSelectionCapabilityDto
import dev.minios.ocremote.domain.model.ServerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackendCapabilitiesTest {

    @Test
    fun `pi stack keeps optional controls closed without server capability`() {
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
    fun `pi stack enables only controls advertised by server`() {
        val server = PiStackCapabilitiesDto(
            protocolVersion = 1,
            permissions = PiStackPermissionsCapabilityDto(false),
            runtime = PiStackRuntimeCapabilityDto(
                prompt = true,
                abort = true,
                retry = false,
                sessionPatch = listOf("title"),
                compact = true,
                attachments = true,
                commands = true,
            ),
            questions = PiStackQuestionCapabilityDto(true, true),
            models = PiStackSelectionCapabilityDto(list = true, select = true),
        )

        val capabilities = chatBackendCapabilities(ServerType.PI_STACK, server)

        assertTrue(capabilities.attachments)
        assertTrue(capabilities.modelAndAgentSelection)
        assertTrue(capabilities.sessionExtras)
        assertTrue(capabilities.slashCommands)
        assertFalse(capabilities.fileMentions)
        assertFalse(capabilities.shellAndTerminal)
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
