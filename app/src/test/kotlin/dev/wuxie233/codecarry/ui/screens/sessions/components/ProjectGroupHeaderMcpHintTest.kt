package dev.wuxie233.codecarry.ui.screens.sessions.components

import dev.wuxie233.codecarry.domain.model.McpRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectGroupHeaderMcpHintTest {

    @Test
    fun mcpHintLabelMapsNullToNull() {
        assertNull(mcpHintLabel(null, false))
    }

    @Test
    fun mcpHintLabelMapsZeroToDisabledLabel() {
        assertEquals("未启用", mcpHintLabel(0, false))
    }

    @Test
    fun mcpHintLabelMapsOneToNumericLabel() {
        assertEquals("1", mcpHintLabel(1, false))
    }

    @Test
    fun mcpHintLabelMapsLargerCountToNumericLabel() {
        assertEquals("42", mcpHintLabel(42, false))
    }

    @Test
    fun mcpHintLabelRuntimeSupportedTwoConnectedOneDisabled() {
        assertEquals(
            "MCP: 2",
            runtimeMcpHintLabel(
                listOf(
                    McpRuntimeState.CONNECTED,
                    McpRuntimeState.CONNECTED,
                    McpRuntimeState.DISABLED,
                ),
                supportsRuntimeControl = true,
                fallbackCount = null,
            ),
        )
    }

    @Test
    fun mcpHintLabelRuntimeSupportedZeroConnected() {
        assertNull(
            runtimeMcpHintLabel(
                listOf(McpRuntimeState.DISABLED, McpRuntimeState.FAILED),
                supportsRuntimeControl = true,
                fallbackCount = null,
            ),
        )
    }

    @Test
    fun mcpHintLabelRuntimeUnsupportedFallback() {
        assertEquals(
            "1",
            runtimeMcpHintLabel(
                listOf(McpRuntimeState.CONNECTED, McpRuntimeState.DISABLED),
                supportsRuntimeControl = false,
                fallbackCount = 1,
            ),
        )
    }

    @Test
    fun mcpHintLabelLoadErrorFallbackUsesNonRuntimeBehavior() {
        assertEquals("未启用", mcpHintLabel(0, false))
    }

    @Test
    fun mcpHintContentDescriptionMapsCountToNonEmptyDescription() {
        val description = mcpHintContentDescription(2)

        assertTrue(description.orEmpty().isNotBlank())
        assertEquals("该项目已配置 2 个 MCP 服务器", description)
    }
}
