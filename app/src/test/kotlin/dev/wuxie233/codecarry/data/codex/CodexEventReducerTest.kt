package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexEventReducerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `delta before item started survives placeholder upsert and completed snapshot wins`() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "thread-1")))
        reducer.process(notification("""
            {"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress","items":[]}}}
        """))
        reducer.process(notification("""
            {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","delta":"Hel"}}
        """))
        reducer.process(notification("""
            {"method":"item/started","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"item-1","type":"agentMessage","text":""}}}
        """))
        reducer.process(notification("""
            {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","delta":"lo"}}
        """))

        assertEquals("Hello", reducer.item("thread-1", "turn-1", "item-1").text)

        reducer.process(notification("""
            {"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"item-1","type":"agentMessage","text":"Hello!"}}}
        """))
        assertEquals("Hello!", reducer.item("thread-1", "turn-1", "item-1").text)
    }

    @Test
    fun `reasoning and command deltas aggregate by stable item id`() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "thread-1")))
        reducer.process(notification("""
            {"method":"item/reasoning/summaryTextDelta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"reason-1","summaryIndex":1,"delta":"Second"}}
        """))
        reducer.process(notification("""
            {"method":"item/reasoning/summaryTextDelta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"reason-1","summaryIndex":0,"delta":"First"}}
        """))
        reducer.process(notification("""
            {"method":"item/reasoning/textDelta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"reason-1","contentIndex":0,"delta":"Detail"}}
        """))
        reducer.process(notification("""
            {"method":"item/commandExecution/outputDelta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-1","delta":"line 1\n"}}
        """))
        reducer.process(notification("""
            {"method":"item/commandExecution/outputDelta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-1","delta":"line 2"}}
        """))

        val reasoning = reducer.item("thread-1", "turn-1", "reason-1")
        assertEquals(listOf("First", "Second"), reasoning.reasoningSummary)
        assertEquals(listOf("Detail"), reasoning.reasoningContent)
        assertEquals("First\nSecond\nDetail", reasoning.text)
        assertEquals(
            "line 1\nline 2",
            reducer.item("thread-1", "turn-1", "command-1").output,
        )
    }

    @Test
    fun `metadata update with empty turns does not discard loaded conversation`() {
        val loaded = CodexThread(
            id = "thread-1",
            name = "Old",
            turns = listOf(
                CodexTurn(
                    id = "turn-1",
                    status = "completed",
                    items = listOf(CodexThreadItem(id = "item-1", type = "agentMessage", text = "Done")),
                ),
            ),
        )
        val reducer = CodexEventReducer(listOf(loaded))

        reducer.upsertThread(CodexThread(id = "thread-1", name = "New", turns = emptyList()))

        val result = reducer.state.value.threads.getValue("thread-1")
        assertEquals("New", result.name)
        assertEquals("Done", result.turns.single().items.single().text)
    }

    @Test
    fun `stale read snapshot does not overwrite newer streamed delta`() {
        val streamed = CodexThread(
            id = "thread-1",
            turns = listOf(
                CodexTurn(
                    id = "turn-1",
                    status = "inProgress",
                    items = listOf(
                        CodexThreadItem(
                            id = "item-1",
                            type = "agentMessage",
                            text = "Hello from the stream",
                        ),
                    ),
                ),
            ),
        )
        val reducer = CodexEventReducer(listOf(streamed))

        reducer.upsertThread(
            streamed.copy(
                turns = listOf(
                    streamed.turns.single().copy(
                        items = listOf(streamed.turns.single().items.single().copy(text = "Hello")),
                    ),
                ),
            ),
        )

        assertEquals(
            "Hello from the stream",
            reducer.item("thread-1", "turn-1", "item-1").text,
        )
    }

    @Test
    fun `completed turn with unloaded items preserves streamed content`() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "thread-1")))
        reducer.process(notification("""
            {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","delta":"Streamed answer"}}
        """))

        reducer.process(notification("""
            {"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[],"itemsView":"notLoaded"}}}
        """))

        val turn = reducer.state.value.threads.getValue("thread-1").turns.single()
        assertEquals("completed", turn.status)
        assertEquals("Streamed answer", turn.items.single().text)
    }

    @Test
    fun `completed turn with empty default item view preserves streamed content`() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "thread-1")))
        reducer.process(notification("""
            {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","delta":"Streamed answer"}}
        """))

        reducer.process(notification("""
            {"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}}
        """))

        val turn = reducer.state.value.threads.getValue("thread-1").turns.single()
        assertEquals("completed", turn.status)
        assertEquals("Streamed answer", turn.items.single().text)
    }

    @Test
    fun `item events establish authoritative turn when turn started is absent`() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "thread-1")))
        reducer.process(notification("""
            {"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"actual-turn","itemId":"item-1","delta":"Streamed answer"}}
        """))

        val active = reducer.state.value.threads.getValue("thread-1").turns.single()
        assertEquals("actual-turn", active.id)
        assertEquals("inProgress", active.status)

        reducer.process(notification("""
            {"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"actual-turn","status":"completed","items":[],"itemsView":"notLoaded"}}}
        """))

        val completed = reducer.state.value.threads.getValue("thread-1").turns.single()
        assertEquals("actual-turn", completed.id)
        assertEquals("completed", completed.status)
        assertEquals("Streamed answer", completed.items.single().text)
    }

    @Test
    fun `thread and goal lifecycle notifications update state`() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "thread-1")))
        reducer.process(notification("""
            {"method":"thread/archived","params":{"threadId":"thread-1"}}
        """))
        reducer.process(notification("""
            {"method":"thread/goal/updated","params":{"threadId":"thread-1","goal":{"threadId":"thread-1","objective":"Ship","status":"active","tokensUsed":4,"timeUsedSeconds":2,"createdAt":1,"updatedAt":2}}}
        """))
        assertTrue("thread-1" in reducer.state.value.archivedThreadIds)
        assertEquals("Ship", reducer.state.value.goals["thread-1"]?.objective)

        reducer.process(notification("""
            {"method":"thread/unarchived","params":{"threadId":"thread-1"}}
        """))
        reducer.process(notification("""
            {"method":"thread/goal/cleared","params":{"threadId":"thread-1"}}
        """))
        assertFalse("thread-1" in reducer.state.value.archivedThreadIds)
        assertFalse(reducer.state.value.goals.containsKey("thread-1"))
        assertTrue("thread-1" in reducer.state.value.knownGoalThreadIds)
    }

    @Test
    fun `authoritative refresh removes threads absent from server snapshot`() {
        val old = CodexThread(id = "old")
        val reducer = CodexEventReducer(listOf(old))
        val baseline = reducer.state.value

        reducer.reconcileThreads(emptyList(), emptyList(), baseline)

        assertTrue(reducer.state.value.threads.isEmpty())
    }

    @Test
    fun `authoritative refresh preserves threads started during load`() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "existing")))
        val baseline = reducer.state.value
        reducer.process(notification("""
            {"method":"thread/started","params":{"thread":{"id":"live","name":"Live"}}}
        """))

        reducer.reconcileThreads(listOf(CodexThread(id = "existing", name = "Fresh")), emptyList(), baseline)

        assertEquals(setOf("existing", "live"), reducer.state.value.threads.keys)
        assertEquals("Fresh", reducer.state.value.threads.getValue("existing").name)
    }

    @Test
    fun `authoritative refresh does not resurrect thread deleted during load`() {
        val old = CodexThread(id = "old")
        val reducer = CodexEventReducer(listOf(old))
        val baseline = reducer.state.value
        reducer.process(notification("""
            {"method":"thread/deleted","params":{"threadId":"old"}}
        """))

        reducer.reconcileThreads(listOf(old.copy(name = "Stale")), emptyList(), baseline)

        assertFalse(reducer.state.value.threads.containsKey("old"))
    }

    private fun CodexEventReducer.item(
        threadId: String,
        turnId: String,
        itemId: String,
    ): CodexThreadItem = state.value.threads.getValue(threadId)
        .turns.first { turn -> turn.id == turnId }
        .items.first { item -> item.id == itemId }

    private fun notification(value: String): CodexNotification = CodexNotification.fromJson(
        json.parseToJsonElement(value.trimIndent()).jsonObject,
    )
}
