package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.ToolRef
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionRequestMapperTest {

    @Test
    fun `toPermissionAsked copies every field so the bootstrap snapshot matches an SSE permission_asked`() {
        val request = PermissionRequest(
            id = "perm-1",
            sessionId = "ses-1",
            permission = "write",
            patterns = listOf("src/**", "docs/**"),
            metadata = mapOf("reason" to JsonPrimitive("edit file")),
            always = listOf("read"),
            tool = ToolRef(messageId = "msg-1", callId = "call-1"),
        )

        val asked = request.toPermissionAsked()

        assertEquals(request.id, asked.id)
        assertEquals(request.sessionId, asked.sessionId)
        assertEquals(request.permission, asked.permission)
        assertEquals(request.patterns, asked.patterns)
        assertEquals(request.metadata, asked.metadata)
        assertEquals(request.always, asked.always)
        assertEquals(request.tool, asked.tool)
    }
}
