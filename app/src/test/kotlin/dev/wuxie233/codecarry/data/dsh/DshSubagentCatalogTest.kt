package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DshSubagentCatalogTest {
    @Test
    fun `only direct catalog children with session identities are navigable`() {
        val state = DshEventState(sessions = mapOf(
            "parent" to catalog("parent", """[
                {"id":"child","createdAt":1,"mode":"continuable","label":"Worker"},
                {"id":"unknown","createdAt":2,"mode":"unknown"},
                {"mode":"continuable","label":"Sessionless"},
                {"id":"","mode":"one-shot"}
            ]"""),
            "child" to catalog("child", """[
                {"id":"grandchild","createdAt":3,"mode":"one-shot","label":"Nested"}
            ]"""),
            "unrelated" to DshSessionSnapshot("unrelated", blank = false),
        ))

        val children = state.directSubagentSessions("parent", "/repo")
        assertEquals(listOf("child", "unknown"), children.map { it.id })
        assertEquals("Worker", children.first().title)
        assertEquals("/repo", children.first().directory)
        assertEquals(DshSessionAddress.Subagent("parent", "child", "continuable"), state.catalogAddress("child"))
        assertEquals(DshSessionAddress.Subagent("parent", "unknown", "unknown"), state.catalogAddress("unknown"))
        assertEquals(DshSessionAddress.Subagent("child", "grandchild", "one-shot"), state.catalogAddress("grandchild"))
        assertNull(state.catalogAddress("unrelated"))
    }

    private fun catalog(id: String, rows: String) = DshSessionSnapshot(
        sessionId = id,
        projections = mapOf("subagentCatalog" to (10L to Json.parseToJsonElement(rows))),
    )
}
