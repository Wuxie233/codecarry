package dev.minios.ocremote.ui.screens.sessions.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectGroupHeaderMcpHintTest {

    @Test
    fun mcpHintLabelMapsNullToNull() {
        assertNull(mcpHintLabel(null))
    }

    @Test
    fun mcpHintLabelMapsZeroToDisabledLabel() {
        assertEquals("未启用", mcpHintLabel(0))
    }

    @Test
    fun mcpHintLabelMapsOneToNumericLabel() {
        assertEquals("1", mcpHintLabel(1))
    }

    @Test
    fun mcpHintLabelMapsLargerCountToNumericLabel() {
        assertEquals("42", mcpHintLabel(42))
    }
}
