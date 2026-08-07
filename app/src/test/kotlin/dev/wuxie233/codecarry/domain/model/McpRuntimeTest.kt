package dev.wuxie233.codecarry.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class McpRuntimeTest {

    @Test
    fun parseMcpRuntimeStateReturnsConnected() {
        assertEquals(McpRuntimeState.CONNECTED, parseMcpRuntimeState("connected"))
    }

    @Test
    fun parseMcpRuntimeStateReturnsNeedsAuthForSnakeAndCamelCase() {
        assertEquals(McpRuntimeState.NEEDS_AUTH, parseMcpRuntimeState("needs_auth"))
        assertEquals(McpRuntimeState.NEEDS_AUTH, parseMcpRuntimeState("needsAuth"))
    }

    @Test
    fun parseMcpRuntimeStateReturnsNeedsClientRegistrationForSnakeAndCamelCase() {
        assertEquals(
            McpRuntimeState.NEEDS_CLIENT_REGISTRATION,
            parseMcpRuntimeState("needs_client_registration")
        )
        assertEquals(
            McpRuntimeState.NEEDS_CLIENT_REGISTRATION,
            parseMcpRuntimeState("needsClientRegistration")
        )
    }

    @Test
    fun parseMcpRuntimeStateReturnsUnknownForNull() {
        assertEquals(McpRuntimeState.UNKNOWN, parseMcpRuntimeState(null))
    }

    @Test
    fun parseMcpRuntimeStateReturnsUnknownForGarbage() {
        assertEquals(McpRuntimeState.UNKNOWN, parseMcpRuntimeState("garbage"))
    }

    @Test
    fun mcpRuntimeStatusUsesDataClassEquality() {
        val status = McpRuntimeStatus("foo", McpRuntimeState.CONNECTED)

        assertEquals(status, status)
    }
}
