package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DshSessionPresetProjectionTest {
    private fun block(seq: Long, preset: String?) = DshProjectionsBlock(seq, buildJsonObject {
        put("agentPreset", preset?.let(::JsonPrimitive) ?: JsonNull)
    })

    @Test
    fun `existing session list uses current projected preset instead of creation identity`() {
        val reducer = DshEventReducer()
        reducer.applySessionList(listOf(DshSessionSummary("s", 1, false, false,
            agentPreset = "creation", projections = block(10, "selected"))))
        assertEquals("selected", reducer.state.value.sessions.getValue("s").currentAgentPreset)
    }

    @Test
    fun `follow snapshot and control updates supply current preset without a session list`() {
        val reducer = DshEventReducer()
        reducer.applyFollowSnapshot("s", DshFollowFrame.Snapshot(
            buildJsonObject { put("agentPreset", "creation") }, 10, emptyList(), false, block(10, "selected")))
        assertEquals("selected", reducer.state.value.sessions.getValue("s").currentAgentPreset)
        reducer.applyControlFrame(DshControlFrame.Projection("s", "agentPreset", JsonPrimitive("another"), 11))
        reducer.applySessionList(listOf(DshSessionSummary("s", 1, false, false, projections = block(9, "old"))))
        assertEquals("another", reducer.state.value.sessions.getValue("s").currentAgentPreset)
    }

    @Test
    fun `explicit null stays unknown instead of substituting creation or global default`() {
        val reducer = DshEventReducer()
        reducer.applySessionList(listOf(DshSessionSummary("s", 1, false, false,
            agentPreset = "creation", projections = block(10, null))))
        assertNull(reducer.state.value.sessions.getValue("s").currentAgentPreset)
    }

    @Test
    fun `creation header is not mistaken for the current preset before projection arrives`() {
        val reducer = DshEventReducer()
        reducer.applyFollowSnapshot("s", DshFollowFrame.Snapshot(
            buildJsonObject { put("agentPreset", "deleted-preset") }, 10, emptyList(), false))
        assertNull(reducer.state.value.sessions.getValue("s").currentAgentPreset)
        reducer.applyControlFrame(DshControlFrame.Baseline(emptyMap(), emptyMap(), mapOf("s" to block(10, "current"))))
        assertEquals("current", reducer.state.value.sessions.getValue("s").currentAgentPreset)
    }

    @Test
    fun `selection event advances preset but older history cannot roll it back`() {
        val reducer = DshEventReducer()
        reducer.applySessionList(listOf(DshSessionSummary("s", 1, false, true, projections = block(10, "first"))))
        reducer.applyFollowEvent("s", DshSessionEvent("agent-preset/selected", 11, 1,
            buildJsonObject { put("agentPreset", "selected") }))
        reducer.mergeHistory("s", listOf(DshSessionEvent("agent-preset/selected", 5, 1,
            buildJsonObject { put("agentPreset", "old") })), block(9, "old"))
        assertEquals("selected", reducer.state.value.sessions.getValue("s").currentAgentPreset)
    }

    @Test
    fun `selection receipt survives stale snapshot until a newer projection arrives`() {
        val reducer = DshEventReducer(DshEventState(generation = 3))
        reducer.applySessionList(listOf(DshSessionSummary("s", 1, false, true, projections = block(10, "first"))))
        reducer.applyPresetSelection("s", "selected", 3, observedSeq = 10)
        reducer.applySessionList(listOf(DshSessionSummary("s", 1, false, true, projections = block(10, "first"))))
        assertEquals("selected", reducer.state.value.sessions.getValue("s").currentAgentPreset)
        reducer.applyControlFrame(DshControlFrame.Projection("s", "agentPreset", JsonPrimitive("external"), 12))
        assertEquals("external", reducer.state.value.sessions.getValue("s").currentAgentPreset)
        reducer.resetGeneration(4)
        reducer.applySessionList(listOf(DshSessionSummary("s", 1, false, true, projections = block(12, "external"))))
        reducer.applyPresetSelection("s", "obsolete", 3)
        assertEquals("external", reducer.state.value.sessions.getValue("s").currentAgentPreset)
    }
    @Test
    fun `a late selection receipt cannot hide a newer projection received during the request`() {
        val reducer = DshEventReducer(DshEventState(generation = 3))
        reducer.applySessionList(listOf(DshSessionSummary("s", 1, false, true, projections = block(10, "first"))))
        reducer.applyControlFrame(DshControlFrame.Projection("s", "agentPreset", JsonPrimitive("external"), 12))
        reducer.applyPresetSelection("s", "selected", 3, observedSeq = 10)
        assertEquals("external", reducer.state.value.sessions.getValue("s").currentAgentPreset)
    }

}
