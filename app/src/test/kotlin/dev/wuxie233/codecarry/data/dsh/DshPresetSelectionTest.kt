package dev.wuxie233.codecarry.data.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPresetSelectionTest {
    private val idle = DshSessionSnapshot(sessionId = "s1", agentPreset = "old")

    @Test
    fun `only connected idle ordinary sessions can change presets`() {
        assertTrue(canSelectDshPreset(idle, ready = true, sending = false))
        assertFalse(canSelectDshPreset(null, ready = true, sending = false))
        assertFalse(canSelectDshPreset(idle, ready = false, sending = false))
        assertFalse(canSelectDshPreset(idle, ready = true, sending = true))
        assertFalse(canSelectDshPreset(idle.copy(running = true), ready = true, sending = false))
        assertFalse(canSelectDshPreset(idle.copy(origin = "subagent"), ready = true, sending = false))
        assertFalse(canSelectDshPreset(idle.copy(parentSessionId = "parent"), ready = true, sending = false))
    }

    @Test
    fun `search matches case insensitive name id and description and preserves catalog order`() {
        val first = DshAgentPresetEntry("coder", "system", true, "Engineering", "Build applications")
        val second = DshAgentPresetEntry("writer", "user", false, "Writing", "Explain ideas")
        val presets = listOf(first, second)
        assertEquals(listOf(first), filterDshPresets(presets, "  CODER  "))
        assertEquals(listOf(first), filterDshPresets(presets, "engineer"))
        assertEquals(listOf(second), filterDshPresets(presets, "ideas"))
        assertEquals(presets, filterDshPresets(presets, " "))
        assertTrue(filterDshPresets(presets, "missing").isEmpty())
    }

    @Test
    fun `preset receipt preserves history status and sibling sessions`() {
        val sibling = idle.copy(sessionId = "s2", agentPreset = "sibling")
        val reducer = DshEventReducer(DshEventState(generation = 4, sessions = mapOf("s1" to idle, "s2" to sibling)))
        reducer.applyPresetSelection("s1", "new", 4)
        assertEquals(idle.copy(agentPreset = "new"), reducer.state.value.sessions["s1"])
        assertEquals(sibling, reducer.state.value.sessions["s2"])
    }

    @Test
    fun `receipt cannot cross reconnect generation or resurrect removed sessions`() {
        val initial = DshEventState(generation = 5, sessions = mapOf("s1" to idle))
        val reducer = DshEventReducer(initial)
        reducer.applyPresetSelection("s1", "stale", 4)
        reducer.applyPresetSelection("removed", "new", 5)
        assertEquals(initial, reducer.state.value)
    }

    @Test
    fun `receipt stays scoped to the owning server reducer`() {
        val initial = DshEventState(generation = 1, sessions = mapOf("s1" to idle))
        val first = DshEventReducer(initial)
        val second = DshEventReducer(initial)
        first.applyPresetSelection("s1", "new", 1)
        assertEquals("new", first.state.value.sessions["s1"]?.agentPreset)
        assertEquals("old", second.state.value.sessions["s1"]?.agentPreset)
    }
}
