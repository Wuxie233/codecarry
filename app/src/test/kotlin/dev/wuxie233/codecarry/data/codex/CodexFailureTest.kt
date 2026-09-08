package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexFailureTest {
    private fun event(raw: String) = CodexNotification.fromJson(Json.parseToJsonElement(raw).jsonObject)

    @Test
    fun `unscoped retry after start retires on completion and resumed terminal history`() {
        for (snapshot in listOf(false, true)) {
            val reducer = CodexEventReducer()
            reducer.process(event("""{"method":"turn/started","params":{"threadId":"t","turn":{"id":"one","status":"inProgress","items":[]}}}"""))
            reducer.process(event("""{"method":"error","params":{"threadId":"t","willRetry":true,"error":{"message":"retrying"}}}"""))
            assertTrue(reducer.state.value.threadFailures.getValue("t").willRetry)
            if (snapshot) {
                reducer.upsertThread(CodexThread(id = "t", turns = listOf(CodexTurn(id = "one", status = "completed"))))
            } else {
                reducer.process(event("""{"method":"turn/completed","params":{"threadId":"t","turn":{"id":"one","status":"completed","items":[]}}}"""))
            }
            assertNull(reducer.state.value.threadFailures["t"])
        }
    }

    @Test
    fun `terminal evidence preserves non retry failure and retry while newer turn runs`() {
        val reducer = CodexEventReducer(listOf(CodexThread(id = "t", turns = listOf(
            CodexTurn(id = "old", status = "completed"), CodexTurn(id = "new", status = "inProgress"),
        ))))
        reducer.process(event("""{"method":"error","params":{"threadId":"t","willRetry":true,"error":{"message":"retrying new turn"}}}"""))
        reducer.process(event("""{"method":"turn/completed","params":{"threadId":"t","turn":{"id":"old","status":"completed","items":[]}}}"""))
        assertEquals("retrying new turn", reducer.state.value.threadFailures["t"]?.message)
        reducer.process(event("""{"method":"error","params":{"threadId":"t","willRetry":false,"error":{"message":"persistent failure"}}}"""))
        reducer.upsertThread(CodexThread(id = "t", turns = listOf(CodexTurn(id = "new", status = "failed"))))
        assertEquals("persistent failure", reducer.state.value.threadFailures["t"]?.message)
        reducer.process(event("""{"method":"turn/started","params":{"threadId":"t","turn":{"id":"next","status":"inProgress","items":[]}}}"""))
        assertEquals("persistent failure", reducer.state.value.threadFailures["t"]?.message)
    }

    @Test
    fun `retry notification retains turn ownership and additional details`() {
        val reducer = CodexEventReducer()
        reducer.process(event("""{"method":"error","params":{"threadId":"t","turnId":"one","willRetry":true,"error":{"message":"busy","additionalDetails":"try later","codexErrorInfo":"serverOverloaded"}}}"""))
        val failure = reducer.state.value.failuresForThread("t").getValue("one")
        assertTrue(failure.willRetry)
        assertEquals("serverOverloaded", failure.code)
        assertEquals("try later", failure.detail)
        assertNull(reducer.state.value.threadFailures["t"])
        assertTrue(reducer.state.value.failuresForThread("other").isEmpty())
        reducer.process(event("""{"method":"turn/completed","params":{"threadId":"t","turn":{"id":"one","status":"completed","items":[]}}}"""))
        assertTrue(reducer.state.value.failuresForThread("t").isEmpty())
    }

    @Test
    fun `terminal failure remains on its turn when the next turn starts`() {
        val reducer = CodexEventReducer()
        reducer.process(event("""{"method":"error","params":{"threadId":"t","turnId":"one","willRetry":false,"error":{"message":"failed"}}}"""))
        reducer.process(event("""{"method":"turn/started","params":{"threadId":"t","turn":{"id":"two","status":"inProgress","items":[]}}}"""))
        assertEquals("failed", reducer.state.value.failuresForThread("t")["one"]?.message)
        reducer.removeThread("t")
        assertTrue(reducer.state.value.failuresForThread("t").isEmpty())
    }

    @Test
    fun `resume snapshot restores failure and stale started notification cannot erase it`() {
        val reducer = CodexEventReducer()
        reducer.upsertThread(CodexThread.fromJson(Json.parseToJsonElement("""{"id":"t","turns":[{"id":"one","status":"failed","items":[],"error":{"message":"rate limited","codexErrorInfo":{"httpConnectionFailed":{"httpStatusCode":429}}}}]}""").jsonObject))
        reducer.process(event("""{"method":"turn/started","params":{"threadId":"t","turn":{"id":"one","status":"inProgress","items":[]}}}"""))
        val failure = reducer.state.value.failuresForThread("t").getValue("one")
        assertEquals("rate limited", failure.message)
        assertEquals("httpConnectionFailed", failure.code)
        assertFalse(failure.willRetry)
        assertEquals("failed", reducer.state.value.threads["t"]?.turns?.single()?.status)
    }
}
