package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexSnapshotOrderTest {
    @Test
    fun `omitted live tools keep their position before the final answer after resume`() {
        val progress = item("progress", "Checking the deployment")
        val tool = CodexThreadItem(id = "tool", type = "dynamicToolCall", status = "inProgress")
        val final = item("answer", "Deployment complete", "final_answer")
        val baseline = thread(progress, tool)
        val reducer = CodexEventReducer(listOf(baseline))
        reducer.process(completed(final))
        // A persisted snapshot does not necessarily contain dynamic tools.
        reducer.upsertThreadSnapshot(thread(progress, final), baseline)
        val merged = reducer.state.value.threads.getValue("thread").turns.single()
        assertEquals(listOf("progress", "tool", "answer"), merged.items.map { it.id })
        assertEquals("Deployment complete", merged.items.last().text)
        reducer.upsertThread(thread(progress, final))
        assertEquals(listOf("progress", "tool", "answer"), reducer.state.value.threads.getValue("thread").turns.single().items.map { it.id })
    }

    @Test
    fun `snapshot-only later content stays after old live-only suffix`() {
        val progress = item("progress", "Checking")
        val oldTool = CodexThreadItem(id = "tool", type = "dynamicToolCall")
        val reducer = CodexEventReducer(listOf(thread(progress, oldTool)))
        reducer.upsertThread(thread(item("older", "Earlier"), progress, item("answer", "Finished", "final_answer")))
        assertEquals(listOf("older", "progress", "tool", "answer"), reducer.state.value.threads.getValue("thread").turns.single().items.map { it.id })
    }

    @Test
    fun `anonymous items keep each occurrence without collapsing or accumulating duplicates`() {
        val anonymous = CodexThreadItem(type = "reasoning", text = "detail")
        val first = item("first", "Before")
        val last = item("last", "Answer", "final_answer")
        val original = thread(first, anonymous, anonymous, last)
        val reducer = CodexEventReducer(listOf(original))
        reducer.upsertThread(thread(first, anonymous, last))
        assertEquals(original.turns.single().items, reducer.state.value.threads.getValue("thread").turns.single().items)
        reducer.upsertThread(original)
        assertEquals(original.turns.single().items, reducer.state.value.threads.getValue("thread").turns.single().items)
    }

    @Test
    fun `late snapshot cannot replace revised live answer or erase final metadata`() {
        val initial = item("answer", "Draft", "commentary")
        val baseline = thread(initial)
        val reducer = CodexEventReducer(listOf(baseline))
        val final = item("answer", "Verified final answer", "final_answer")
        reducer.process(completed(final))
        reducer.upsertThreadSnapshot(thread(initial), baseline)
        val result = reducer.state.value.threads.getValue("thread").turns.single().items.single()
        assertEquals("Verified final answer", result.text)
        assertEquals(final.raw["phase"], result.raw["phase"])
        assertEquals("final_answer", result.phase)
        reducer.upsertThread(thread(CodexThreadItem(id = "answer", type = "agentMessage", text = "Verified final answer")))
        assertEquals(final.raw["phase"], reducer.state.value.threads.getValue("thread").turns.single().items.single().raw["phase"])
    }

    @Test
    fun `answer created during resume retains its final text against a late draft snapshot`() {
        // Run for both a pre-existing turn and a turn first observed during the read.
        for (baselineHasTurn in listOf(false, true)) {
            val baseline = if (baselineHasTurn) thread(item("progress", "Checking")) else CodexThread(id = "thread")
            val reducer = CodexEventReducer(listOf(baseline))
            val final = item("answer", "Verified final result", "final_answer")
            reducer.process(completed(final))
            reducer.upsertThreadSnapshot(thread(item("answer", "An earlier draft", "commentary")), baseline)
            val result = reducer.state.value.threads.getValue("thread").turns.single().items.last()
            assertEquals("Verified final result", result.text)
            assertEquals("final_answer", result.phase)
            assertEquals(final.raw["phase"], result.raw["phase"])
        }
    }

    @Test
    fun `late in-progress image snapshot preserves completed output bytes`() {
        val pending = CodexThreadItem.fromJson(Json.parseToJsonElement("""{"id":"image","type":"imageGeneration","status":"inProgress","result":null}""").jsonObject)
        val complete = CodexThreadItem.fromJson(Json.parseToJsonElement("""{"id":"image","type":"imageGeneration","status":"completed","result":"aW1hZ2U=","savedPath":"/tmp/output.png"}""").jsonObject)
        val reducer = CodexEventReducer(listOf(thread(complete)))
        reducer.upsertThread(thread(pending))
        val result = reducer.state.value.threads.getValue("thread").turns.single().items.single()
        assertEquals("completed", result.status)
        assertEquals(complete.raw, result.raw)
        assertEquals(complete.extra, result.extra)
    }

    private fun item(id: String, text: String, phase: String = "commentary") =
        CodexThreadItem.fromJson(Json.parseToJsonElement("""{"id":"$id","type":"agentMessage","text":"$text","phase":"$phase"}""").jsonObject)

    private fun thread(vararg items: CodexThreadItem) = CodexThread(
        id = "thread", turns = listOf(CodexTurn(id = "turn", items = items.toList())),
    )

    private fun completed(item: CodexThreadItem) = CodexNotification.fromJson(Json.parseToJsonElement(
        """{"method":"item/completed","params":{"threadId":"thread","turnId":"turn","item":${item.raw}}}""",
    ).jsonObject)
}
