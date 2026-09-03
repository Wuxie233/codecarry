package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshSessionAddressTest {
    @Test
    fun `ordinary snapshot encodes kind session`() {
        val address = DshSessionSnapshot(sessionId = "s1", listed = true).historyAddress()
        assertTrue(address is DshSessionAddress.Session)
        val json = address!!.toJson()
        assertEquals("session", json.getValue("kind").jsonPrimitive.content)
        assertEquals("s1", json.getValue("sessionId").jsonPrimitive.content)
    }

    @Test
    fun `unlisted origin-less snapshot does not follow as session`() {
        assertNull(DshSessionSnapshot(sessionId = "s1").historyAddress())
    }

    @Test
    fun `subagent origin without parent is null`() {
        assertNull(
            DshSessionSnapshot(
                sessionId = "child-1",
                origin = "subagent",
            ).historyAddress(),
        )
    }

    @Test
    fun `subagent origin with parent encodes durable address and defaults mode`() {
        val address = DshSessionSnapshot(
            sessionId = "child-1",
            origin = "subagent",
            parentSessionId = "parent-1",
        ).historyAddress() as DshSessionAddress.Subagent
        assertEquals("parent-1", address.parentSessionId)
        assertEquals("child-1", address.childSessionId)
        assertEquals("continuable", address.mode)
        val json = address.toJson()
        assertEquals("subagent", json.getValue("kind").jsonPrimitive.content)
        assertEquals("parent-1", json.getValue("parentSessionId").jsonPrimitive.content)
        assertEquals("child-1", json.getValue("childSessionId").jsonPrimitive.content)
        assertEquals("continuable", json.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `subagent mode comes from projections values`() {
        val address = DshSessionSnapshot(
            sessionId = "child-1",
            origin = "subagent",
            parentSessionId = "parent-1",
            projections = mapOf(
                "subagent" to Pair(
                    1L,
                    buildJsonObject { put("mode", "one-shot") },
                ),
            ),
        ).historyAddress() as DshSessionAddress.Subagent
        assertEquals("one-shot", address.mode)
    }
}
