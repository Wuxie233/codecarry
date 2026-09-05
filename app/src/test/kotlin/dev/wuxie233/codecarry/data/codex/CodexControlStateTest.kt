package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexControlStateTest {
    private fun event(method: String, params: String) = CodexNotification.fromJson(
        Json.parseToJsonElement("""{"method":"$method","params":$params}""").jsonObject,
    )

    @Test
    fun `resume invalidates old controls but preserves events received during request`() {
        val reducer = CodexEventReducer()
        reducer.process(event("turn/diff/updated", """{"threadId":"a","turnId":"1","diff":"old"}"""))
        reducer.process(event("turn/plan/updated", """{"threadId":"a","turnId":"1","explanation":null,"plan":[{"step":"work","status":"inProgress"}]}"""))
        reducer.invalidateThreadControlState("a")
        assertTrue(reducer.state.value.turnPlans.isEmpty())
        assertTrue(reducer.state.value.turnDiffs.isEmpty())
        reducer.process(event("turn/diff/updated", """{"threadId":"a","turnId":"2","diff":"live"}"""))
        reducer.upsertThreadAuthoritative(CodexThread(id = "a"))
        assertEquals(mapOf("2" to "live"), reducer.state.value.turnDiffs["a"])
    }

    @Test
    fun `completed snapshot fills child links and final patches but old started snapshot cannot regress them`() {
        val reducer = CodexEventReducer()
        val started = CodexThreadItem(id = "child", type = "collabAgentToolCall", status = "inProgress",
            collabAgentCall = CodexCollabAgentCall("spawnAgent", "a", emptyList(), null, emptyMap()))
        val completed = started.copy(status = "completed", collabAgentCall = started.collabAgentCall!!.copy(receiverThreadIds = listOf("b")))
        val oldPatch = CodexThreadItem(id = "file", type = "fileChange", status = "inProgress", fileChanges = listOf(CodexFileChange("a.kt", "update", null, "old")))
        val finalPatch = oldPatch.copy(status = "completed", fileChanges = listOf(CodexFileChange("a.kt", "update", null, "final")))
        fun snapshot(vararg items: CodexThreadItem) = CodexThread(id = "a", turns = listOf(CodexTurn(id = "1", items = items.toList())))
        reducer.upsertThread(snapshot(started, oldPatch))
        reducer.upsertThread(snapshot(completed, finalPatch))
        reducer.upsertThread(snapshot(started, oldPatch))
        val items = reducer.state.value.threads.getValue("a").turns.single().items
        assertEquals(listOf("b"), items[0].collabAgentCall?.receiverThreadIds)
        assertEquals("completed", items[0].status)
        assertEquals("final", items[1].fileChanges.single().diff)
    }

    @Test
    fun `control events stay scoped by thread and turn and survive snapshots`() {
        val reducer = CodexEventReducer()
        reducer.process(event("turn/plan/updated", """{"threadId":"a","turnId":"1","explanation":"why","plan":[{"step":"build","status":"inProgress"}]}"""))
        reducer.process(event("turn/diff/updated", """{"threadId":"a","turnId":"1","diff":"first"}"""))
        reducer.process(event("turn/diff/updated", """{"threadId":"a","turnId":"2","diff":"second"}"""))
        reducer.process(event("turn/diff/updated", """{"threadId":"b","turnId":"1","diff":"other"}"""))
        reducer.upsertThread(CodexThread(id = "a"))
        assertEquals("inProgress", reducer.state.value.turnPlans["a"]?.get("1")?.steps?.single()?.status)
        assertEquals(mapOf("1" to "first", "2" to "second"), reducer.state.value.turnDiffs["a"])
        reducer.removeThread("a")
        assertFalse(reducer.state.value.turnPlans.containsKey("a"))
        assertEquals(mapOf("1" to "other"), reducer.state.value.turnDiffs["b"])
        reducer.clear()
        assertTrue(reducer.state.value.turnDiffs.isEmpty())
    }

    @Test
    fun `token usage keeps last and total distinct with nullable context window`() {
        val reducer = CodexEventReducer()
        reducer.process(event("thread/tokenUsage/updated", """{"threadId":"a","turnId":"1","tokenUsage":{"total":{"totalTokens":5000,"inputTokens":4000},"last":{"totalTokens":1000,"cachedInputTokens":200},"modelContextWindow":32000}}"""))
        val usage = requireNotNull(reducer.state.value.tokenUsage["a"])
        assertEquals(5000L, usage.total.totalTokens)
        assertEquals(1000L, usage.last.totalTokens)
        assertEquals(200L, usage.last.cachedInputTokens)
        assertEquals(32000L, usage.modelContextWindow)
        assertEquals(usage, reducer.state.value.turnTokenUsage["a"]?.get("1"))
        val baseline = reducer.state.value
        reducer.reconcileThreads(emptyList(), emptyList(), baseline)
        assertTrue(reducer.state.value.tokenUsage.isEmpty())
        assertTrue(reducer.state.value.turnTokenUsage.isEmpty())
    }

    @Test
    fun `live patch beats late item start and snapshot while completed patch is authoritative`() {
        val reducer = CodexEventReducer()
        reducer.process(event("item/fileChange/patchUpdated", """{"threadId":"a","turnId":"1","itemId":"f","changes":[{"path":"old.kt","kind":{"type":"update","move_path":"new.kt"},"diff":"live"}]}"""))
        val stale = Json.parseToJsonElement("""{"id":"f","type":"fileChange","changes":[{"path":"old.kt","kind":{"type":"update","move_path":null},"diff":"stale"}]}""").jsonObject
        reducer.process(CodexNotification(method = "item/started", params = Json.parseToJsonElement("""{"threadId":"a","turnId":"1","item":$stale}""").jsonObject))
        reducer.upsertThread(CodexThread(id = "a", turns = listOf(CodexTurn(id = "1", items = listOf(CodexThreadItem.fromJson(stale))))))
        val change = reducer.state.value.threads.getValue("a").turns.single().items.single().fileChanges.single()
        assertEquals("new.kt", change.movePath)
        assertEquals("live", change.diff)
        reducer.process(CodexNotification(method = "item/completed", params = Json.parseToJsonElement("""{"threadId":"a","turnId":"1","item":$stale}""").jsonObject))
        assertEquals("stale", reducer.state.value.threads.getValue("a").turns.single().items.single().fileChanges.single().diff)
    }

    @Test
    fun `collab calls expose child links and remote file paths preserve host separators`() {
        val item = CodexThreadItem.fromJson(Json.parseToJsonElement("""{"type":"collabAgentToolCall","tool":"spawnAgent","senderThreadId":"parent","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"running","message":null}}}""").jsonObject)
        assertEquals(listOf("child"), item.collabAgentCall?.receiverThreadIds)
        assertEquals("running", item.collabAgentCall?.agentsStates?.get("child")?.status)
        assertEquals("C:\\repo\\src\\a.kt", CodexFileMatch("C:\\repo", "src\\a.kt", "a.kt", 1, "file").absolutePath)
        assertEquals("/repo/a.kt", CodexFileMatch("/repo", "a.kt", "a.kt", 1, "file").absolutePath)
    }
}
