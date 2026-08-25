package dev.wuxie233.codecarry.data.dsh

import dev.wuxie233.codecarry.data.api.PromptPart
import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.ToolState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshChatFoldTest {
    @Test
    fun `history plus mux chunks fold into user and streaming assistant`() {
        val history = listOf(
            DshSessionEvent(
                type = "user/message",
                seq = 1,
                time = 10,
                data = buildJsonObject {
                    put("id", "u1")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "hello") })
                    })
                    put("source", buildJsonObject { put("kind", "user") })
                },
                surfaceOp = JsonPrimitive("append"),
            ),
        )
        val foldedHistory = foldDshHistory("s1", history)
        assertEquals(1, foldedHistory.size)
        assertTrue(foldedHistory.single().info is Message.User)
        assertEquals("hello", (foldedHistory.single().parts.single() as Part.Text).text)

        val live = history + DshSessionEvent(
            type = "assistant/chunk",
            seq = 2,
            time = 11,
            data = buildJsonObject {
                put("turn", 1)
                put("step", 1)
                put("chunk", buildJsonObject {
                    put("type", "text-delta")
                    put("text", "hi")
                })
            },
        ) + DshSessionEvent(
            type = "assistant/message",
            seq = 3,
            time = 12,
            data = buildJsonObject {
                put("turn", 1)
                put("step", 1)
                put("message", buildJsonObject {
                    put("id", "a1")
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "hi there") })
                    })
                })
            },
            surfaceOp = JsonPrimitive("append"),
        )
        val foldedLive = foldDshHistory("s1", live)
        assertEquals(2, foldedLive.size)
        assertEquals("a1", foldedLive.last().info.id)
        assertEquals("hi there", (foldedLive.last().parts.single() as Part.Text).text)
    }

    @Test
    fun `user rewrite replace shadows earlier surface messages`() {
        val events = listOf(
            DshSessionEvent(
                type = "user/message",
                seq = 1,
                time = 10,
                data = buildJsonObject {
                    put("id", "u1")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "old") })
                    })
                    put("source", buildJsonObject { put("kind", "user") })
                },
                surfaceOp = JsonPrimitive("append"),
            ),
            DshSessionEvent(
                type = "assistant/message",
                seq = 2,
                time = 11,
                data = buildJsonObject {
                    put("message", buildJsonObject {
                        put("id", "a1")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", "reply") })
                        })
                    })
                },
                surfaceOp = JsonPrimitive("append"),
            ),
            DshSessionEvent(
                type = "user/message",
                seq = 3,
                time = 12,
                data = buildJsonObject {
                    put("id", "u2")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "rewritten") })
                    })
                    put("source", buildJsonObject { put("kind", "user") })
                },
                surfaceOp = buildJsonObject {
                    put("op", "replace")
                    put("start", 1)
                    put("end", 2)
                },
            ),
        )
        val folded = foldDshHistory("s1", events)
        assertEquals(listOf("u2"), folded.map { it.info.id })
        assertEquals("rewritten", (folded.single().parts.single() as Part.Text).text)
    }

    @Test
    fun `tool call then result updates the assistant tool part`() {
        val events = listOf(
            DshSessionEvent(
                type = "assistant/message",
                seq = 1,
                time = 10,
                data = buildJsonObject {
                    put("turn", 1)
                    put("step", 1)
                    put("message", buildJsonObject {
                        put("id", "a1")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", "working") })
                            add(buildJsonObject {
                                put("type", "tool-call")
                                put("id", "c1")
                                put("name", "bash")
                                put("arguments", "{}")
                            })
                        })
                    })
                },
                surfaceOp = JsonPrimitive("append"),
            ),
            DshSessionEvent(
                type = "tool/result",
                seq = 2,
                time = 11,
                data = buildJsonObject {
                    put("turn", 1)
                    put("step", 1)
                    put("callId", "c1")
                    put("message", buildJsonObject {
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", "ok") })
                        })
                    })
                },
            ),
        )
        val folded = foldDshHistory("s1", events)
        val tool = folded.single().parts.filterIsInstance<Part.Tool>().single()
        assertEquals("c1", tool.callId)
        assertTrue(tool.state is ToolState.Completed)
        assertEquals("ok", (tool.state as ToolState.Completed).output)
    }

    @Test
    fun `prompt modes distinguish queue steer and slash commands`() {
        val queued = dshPromptRequest(listOf(PromptPart(type = "text", text = "hello")), steer = false)
        assertEquals("queue", queued.mode)
        assertEquals(false, queued.isSlashCommand)

        val steered = dshPromptRequest(listOf(PromptPart(type = "text", text = "nudge")), steer = true)
        assertEquals("steer", steered.mode)

        val slash = dshPromptRequest(listOf(PromptPart(type = "text", text = "/rename New")), steer = false)
        assertEquals(true, slash.isSlashCommand)
        assertEquals("queue", slash.mode)
    }
}
