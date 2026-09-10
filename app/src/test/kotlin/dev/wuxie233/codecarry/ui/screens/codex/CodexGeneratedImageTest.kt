package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexEventReducer
import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexTurn
import dev.wuxie233.codecarry.data.codex.CodexNotification
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexGeneratedImageTest {
    @Test fun `completed live image survives a later failed turn`() {
        val reducer = CodexEventReducer()
        listOf(
            """{"method":"item/started","params":{"threadId":"thread","turnId":"turn","item":{"id":"image","type":"imageGeneration","status":"inProgress","result":""}}}""",
            """{"method":"item/completed","params":{"threadId":"thread","turnId":"turn","item":{"id":"image","type":"imageGeneration","status":"completed","result":"aW1hZ2U=","savedPath":"/remote/image.png"}}}""",
            """{"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"failed","items":[]}}}""",
        ).forEach { reducer.process(CodexNotification.fromJson(Json.parseToJsonElement(it).jsonObject)) }
        val item = reducer.state.value.threads.getValue("thread").turns.single().items.single()
        assertEquals(listOf(CodexTimelineImage("generatedImage", "aW1hZ2U=")), item.timelineImages())
    }

    @Test fun `historical item can use saved path when inline result is empty`() {
        val item = item(""""result":"", "savedPath":"/remote/generated/image.png"""")
        assertEquals(listOf(CodexTimelineImage("localImage", "/remote/generated/image.png")), item.timelineImages())
    }

    @Test fun `pending item without output does not attempt to decode an empty image`() {
        assertTrue(item("\"result\":\"\"").timelineImages().isEmpty())
    }

    @Test fun `late in-progress resume cannot erase completed image bytes`() {
        val pending = item("\"result\":\"\",\"status\":\"inProgress\"")
        val completed = item("\"result\":\"aW1hZ2U=\",\"status\":\"completed\"")
        val baseline = CodexThread(id = "thread", turns = listOf(CodexTurn(id = "turn", items = listOf(pending))))
        val reducer = CodexEventReducer(listOf(baseline.copy(turns = listOf(CodexTurn(id = "turn", items = listOf(completed))))))
        reducer.upsertThread(baseline)
        val restored = reducer.state.value.threads.getValue("thread").turns.single().items.single()
        assertEquals("completed", restored.status)
        assertEquals(completed.timelineImages(), restored.timelineImages())
    }

    private fun item(fields: String) = CodexThreadItem.fromJson(Json.parseToJsonElement(
        """{"id":"image","type":"imageGeneration",$fields}""",
    ).jsonObject)
}
