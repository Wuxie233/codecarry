package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.TimeInfo
import dev.wuxie233.codecarry.domain.model.ToolState
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageRowPlannerTest {
    @Test
    fun `29k single assistant math text expands into stable bounded rows`() {
        val message = assistantMessage("assistant-long", longMathText())

        val rows = planChatMessageRows(listOf(message))
        val chunks = rows.filterIsInstance<ChatMessageRow.TextChunk>()

        assertTrue("expected bounded rows, count=${rows.size}", rows.size in 5..10)
        assertEquals(rows.size, chunks.size)
        assertEquals(ChatMessageSegmentPosition.First, chunks.first().position)
        assertEquals(ChatMessageSegmentPosition.Last, chunks.last().position)
        assertTrue(chunks.drop(1).dropLast(1).all { it.position == ChatMessageSegmentPosition.Middle })
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        chunks.forEach { row ->
            assertTrue(row.key.contains("assistant-long"))
            assertTrue(row.key.contains("assistant-long-text"))
            assertTrue(row.key.contains("part-0"))
            assertTrue(row.key.contains("type-markdown-block:"))
            assertSame(message, row.chatMessage)
        }
    }

    @Test
    fun `expanded rows reconstruct normalized source exactly`() {
        val message = assistantMessage("assistant-source", longMathText())
        val text = (message.parts.single() as Part.Text).text

        val rows = planChatMessageRows(listOf(message)).filterIsInstance<ChatMessageRow.TextChunk>()

        assertEquals(text, rows.joinToString(separator = "") { it.markdown.source })
    }

    @Test
    fun `29k non math assistant markdown expands into bounded compose rows and reconstructs source`() {
        val source = longNonMathText()
        val message = assistantMessage("assistant-non-math", source)

        val rows = planChatMessageRows(listOf(message))
        val chunks = rows.filterIsInstance<ChatMessageRow.TextChunk>()

        assertTrue("fixture should be about 29k chars, length=${source.length}", source.length in 28_000..30_000)
        assertTrue("expected 4-6 rows, count=${rows.size}", rows.size in 4..6)
        assertEquals(rows.size, chunks.size)
        assertEquals(source, chunks.joinToString(separator = "") { it.markdown.source })
        assertTrue(chunks.all { it.markdown.source.length <= MarkdownRenderPlanTargetChars })
        assertTrue(chunks.all { it.markdown.math.isEmpty() })
        assertTrue(chunks.all { it.markdown.route == MarkdownRenderRoute.Compose })
    }

    @Test
    fun `mixed root structures still plan the later table into compose rows`() {
        val failingTable = buildString {
            append("| Lane | Scope | User involvement |\n")
            append("|---|---|---|\n")
            repeat(20) { index ->
                append("| L$index | ${"bounded delivery ".repeat(12)}| only for product decisions |\n")
            }
        }
        val source = buildString {
            append("Opening ${"context ".repeat(500)}\n\n")
            append("> **Simple work stays direct; complex work asks only for business decisions.**\n\n")
            append(failingTable)
            append('\n')
            repeat(13) { index -> append("```text\nworkflow stage $index\n```\n\n") }
            repeat(20) { index -> append("- workflow rule $index keeps its contract intact\n") }
            append('\n')
            append("<details>\n<summary>Evidence</summary>\n<p>Atomic HTML.</p>\n</details>\n\n")
            repeat(40) { index -> append("Closing paragraph $index ${"result ".repeat(20)}\n\n") }
        }

        val rows = planChatMessageRows(listOf(assistantMessage("assistant-mixed-structure", source)))
        val chunks = rows.filterIsInstance<ChatMessageRow.TextChunk>()

        assertTrue("expected multiple planned rows, count=${rows.size}", rows.size > 1)
        assertTrue("atomic boundaries may exceed the soft cap, count=${rows.size}", rows.size > 12)
        assertEquals(rows.size, chunks.size)
        assertEquals(source, chunks.joinToString(separator = "") { it.markdown.source })
        assertTrue(chunks.any { failingTable in it.markdown.source })
        assertTrue(chunks.all { it.markdown.route == MarkdownRenderRoute.Compose })
    }

    @Test
    fun `real Chinese three column payload plans table as its own text chunk`() {
        val source = buildString {
            append("先给结论：高质量和高效率来自按风险分层。\n\n")
            append("## 1. 建议的四条工作流\n\n")
            append("| 车道 | 适用任务 | 用户是否介入 |\n")
            append("|---|---|---|\n")
            append("| L0 直接执行 | 查询、机械修改、明确的原子修复 | 不介入 |\n")
            append("| L1 有界开发 | 目标明确、低风险、一个主要写入边界 | 不介入，Lead 内部做微计划 |\n")
            append("| L2 复杂协作 | 跨模块、多需求、多 Builder、需要恢复或并行 | 只有存在未决业务判断时介入 |\n")
            append("| L3 人机共同决策 | 产品方向、范围取舍、权限、数据、计费、破坏性操作、不可逆变更 | 必须介入 |\n\n")
            append("路由依据不是任务文字长短，而是风险和决策不确定性。\n\n")
            repeat(100) { index -> append("后续说明 $index ${"context ".repeat(20)}\n\n") }
        }
        val tableHeader = "| 车道 | 适用任务 | 用户是否介入 |"

        val rows = planChatMessageRows(listOf(assistantMessage("assistant-qoder", source)))
        val chunks = rows.filterIsInstance<ChatMessageRow.TextChunk>()

        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString(separator = "") { it.markdown.source })
        val tableChunk = chunks.single { tableHeader in it.markdown.source }
        assertTrue(tableChunk.markdown.source.startsWith(tableHeader))
        assertTrue(!tableChunk.markdown.source.contains("路由依据不是任务文字长短"))
    }

    @Test
    fun `ordinary assistant message remains one whole row`() {
        val message = assistantMessage("assistant-short", "Short answer with ${'$'}x${'$'}.")

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
        assertSame(message, rows.single().chatMessage)
    }

    @Test
    fun `short non math assistant message remains one whole row`() {
        val message = assistantMessage("assistant-short-non-math", "Short answer without math.")

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
        assertSame(message, rows.single().chatMessage)
    }

    @Test
    fun `user message remains one whole row`() {
        val message = ChatMessage(
            message = Message.User(id = "user-long", sessionId = SessionId, time = TimeInfo(created = 1L)),
            parts = listOf(textPart("user-long", "user-long-text", longMathText())),
        )

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
    }

    @Test
    fun `assistant with one text plus step parts expands tools as independent rows`() {
        val message = assistantMessage(
            id = "assistant-steps",
            text = longMathText(),
            extraParts = listOf(
                Part.StepStart("step-start", SessionId, "assistant-steps"),
                Part.Tool(id = "tool", sessionId = SessionId, messageId = "assistant-steps", tool = "bash"),
                Part.StepFinish("step-finish", SessionId, "assistant-steps"),
            ),
        )

        val rows = planChatMessageRows(listOf(message))
        val chunks = rows.filterIsInstance<ChatMessageRow.TextChunk>()
        val tools = rows.filterIsInstance<ChatMessageRow.Tool>()

        assertTrue(chunks.size > 1)
        assertEquals(1, tools.size)
        assertEquals("bash", tools.single().part.tool)
        assertTrue(chunks.all { !it.showsSteps })
        assertEquals(chunks + tools, rows)
    }

    @Test
    fun `reasoning after long text becomes an independent think row`() {
        val message = assistantMessage(
            id = "assistant-complex",
            text = longMathText(),
            extraParts = listOf(
                Part.Reasoning(
                    id = "reasoning",
                    sessionId = SessionId,
                    messageId = "assistant-complex",
                    text = "Reasoning must keep its original position.",
                ),
            ),
        )

        val rows = planChatMessageRows(listOf(message))
        val think = rows.filterIsInstance<ChatMessageRow.Think>()

        assertTrue(rows.filterIsInstance<ChatMessageRow.TextChunk>().size > 1)
        assertEquals(1, think.size)
        assertEquals("Reasoning must keep its original position.", think.single().part.text)
        assertTrue(rows.last() is ChatMessageRow.Think)
    }

    @Test
    fun `reasoning skill bash and text become independent rows in source order`() {
        val message = ChatMessage(
            message = Message.Assistant(id = "assistant-mix", sessionId = SessionId, time = TimeInfo(created = 1L)),
            parts = listOf(
                Part.Reasoning("think", SessionId, "assistant-mix", "Load the skill."),
                Part.Tool(
                    id = "skill-call",
                    sessionId = SessionId,
                    messageId = "assistant-mix",
                    tool = "skill",
                    state = ToolState.Completed(
                        input = mapOf("name" to JsonPrimitive("grill-with-docs")),
                        output = "<skill_content name=\"grill-with-docs\">body</skill_content>",
                    ),
                ),
                Part.Tool(id = "bash-call", sessionId = SessionId, messageId = "assistant-mix", tool = "bash"),
                textPart("assistant-mix", "assistant-mix-text", "Short answer without math."),
            ),
        )

        val rows = planChatMessageRows(listOf(message))

        assertEquals(
            listOf("think", "skill", "tool", "text"),
            rows.map { row ->
                when (row) {
                    is ChatMessageRow.Think -> "think"
                    is ChatMessageRow.Skill -> "skill"
                    is ChatMessageRow.Tool -> "tool"
                    is ChatMessageRow.TextChunk -> "text"
                    is ChatMessageRow.Whole -> "text"
                    else -> row::class.simpleName
                }
            },
        )
        assertEquals("grill-with-docs", skillRowName((rows[1] as ChatMessageRow.Skill).part))
        assertEquals("bash", (rows[2] as ChatMessageRow.Tool).part.tool)
    }

    @Test
    fun `skill-only assistant is a skill row not a whole bubble`() {
        val message = ChatMessage(
            message = Message.Assistant(id = "assistant-skill", sessionId = SessionId, time = TimeInfo(created = 1L)),
            parts = listOf(
                Part.Tool(
                    id = "skill-only",
                    sessionId = SessionId,
                    messageId = "assistant-skill",
                    tool = "skill",
                    state = ToolState.Completed(
                        input = mapOf("name" to JsonPrimitive("editing-cordis-compositions")),
                        output = "<skill_content name=\"editing-cordis-compositions\">",
                    ),
                ),
            ),
        )

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Skill)
        assertEquals("editing-cordis-compositions", skillRowName((rows.single() as ChatMessageRow.Skill).part))
    }

    @Test
    fun `duplicate tool parts with the same callId become one process row`() {
        val message = ChatMessage(
            message = Message.Assistant(id = "assistant-dup", sessionId = SessionId, time = TimeInfo(created = 1L)),
            parts = listOf(
                textPart("assistant-dup", "assistant-dup-text", "working"),
                Part.Tool(
                    id = "from-message",
                    sessionId = SessionId,
                    messageId = "assistant-dup",
                    callId = "c1",
                    tool = "bash",
                ),
                Part.Tool(
                    id = "from-mux",
                    sessionId = SessionId,
                    messageId = "assistant-dup",
                    callId = "c1",
                    tool = "bash",
                ),
            ),
        )

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.filterIsInstance<ChatMessageRow.Tool>().size)
        assertEquals("c1", rows.filterIsInstance<ChatMessageRow.Tool>().single().part.callId)
    }

    @Test
    fun `skill name can be recovered from pending raw JSON`() {
        assertEquals(
            "grill-with-docs",
            skillRowName(
                Part.Tool(
                    id = "raw-skill",
                    sessionId = SessionId,
                    messageId = "assistant-skill",
                    tool = "skill",
                    state = ToolState.Pending(raw = """{"name":"grill-with-docs"}"""),
                ),
            ),
        )
    }

    @Test
    fun `user messages stay whole even with long text`() {
        val message = ChatMessage(
            message = Message.User(id = "user-skill", sessionId = SessionId, time = TimeInfo(created = 1L)),
            parts = listOf(textPart("user-skill", "user-skill-text", "/skill:review")),
        )

        val rows = planChatMessageRows(listOf(message))

        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
    }

    @Test
    fun `todoread is omitted and mixed tool plus long text still chunks after the tool`() {
        val message = assistantMessage(
            id = "assistant-mixed-tool-text",
            text = longMathText(),
            extraParts = emptyList(),
        ).let { chat ->
            chat.copy(
                parts = listOf(
                    Part.Tool(id = "skip", sessionId = SessionId, messageId = chat.message.id, tool = "todoread"),
                    Part.Tool(id = "bash", sessionId = SessionId, messageId = chat.message.id, tool = "bash"),
                ) + chat.parts,
            )
        }

        val rows = planChatMessageRows(listOf(message))

        assertEquals(0, rows.count { it is ChatMessageRow.Skill })
        assertEquals(listOf("bash"), rows.filterIsInstance<ChatMessageRow.Tool>().map { it.part.tool })
        assertTrue(rows.filterIsInstance<ChatMessageRow.TextChunk>().size > 1)
        assertTrue(rows.first() is ChatMessageRow.Tool)
        assertTrue(rows.drop(1).all { it is ChatMessageRow.TextChunk })
    }

    @Test
    fun `long math bearing raw html is normalized before chunk planning`() {
        val rawHtml = buildString {
            append("<!doctype html><html><body><script>window.releaseBlocker = true</script>")
            append("<p>Display math: \\[x^2 + y^2 = z^2\\]</p>")
            repeat(500) { index -> append("<p>Payload $index ${"content ".repeat(8)}</p>") }
            append("</body></html>")
        }
        val document = parseMarkdownDocument(rawHtml).getOrThrow()
        val planned = planMarkdownDocument(document)

        assertTrue(document.normalizedSource.startsWith("```text\n<!doctype html>"))
        assertTrue(document.normalizedSource.endsWith("\n```"))
        assertTrue(document.math.isEmpty())
        assertEquals(listOf(document.normalizedSource), planned.blocks.map { it.source })
        assertTrue(planned.blocks.all { it.renderSource.startsWith("```text\n") })
        assertTrue(planned.blocks.none { it.renderSource.startsWith("<!doctype html>") })

        val rows = planChatMessageRows(listOf(assistantMessage("assistant-html", rawHtml)))
        assertEquals(1, rows.size)
        assertTrue(rows.single() is ChatMessageRow.Whole)
    }

    @Test
    fun `timeline indices count expanded rows and optional surrounding items`() {
        val expanded = assistantMessage("assistant-long", longMathText())
        val ordinary = assistantMessage("assistant-next", "Next response.")
        val rows = planChatMessageRows(listOf(expanded, ordinary))
        val expandedCount = rows.count { it.sourceMessageIndex == 0 }

        assertEquals(2 + expandedCount, timelineIndexForMessage(rows, 1, hasOlderMessages = true, hasRoster = true))
        assertEquals(
            2 + rows.size + 1,
            pendingTimelineStartIndex(
                rows = rows,
                hasOlderMessages = true,
                hasRoster = true,
                hasRevertBanner = true,
            ),
        )
    }

    @Test
    fun `auto follow observes last renderable text behind trailing step parts and row growth`() {
        val first = assistantMessage(
            id = "assistant-stream",
            text = streamingMathText(paragraphCount = 40),
            extraParts = listOf(Part.StepFinish("step-finish", SessionId, "assistant-stream")),
        )
        val firstRows = planChatMessageRows(listOf(first))
        val firstTarget = chatAutoFollowTarget(listOf(first), firstRows)
        val grown = assistantMessage(
            id = "assistant-stream",
            text = streamingMathText(paragraphCount = 80),
            extraParts = listOf(Part.StepFinish("step-finish", SessionId, "assistant-stream")),
        )
        val grownRows = planChatMessageRows(listOf(grown))
        val grownTarget = chatAutoFollowTarget(listOf(grown), grownRows)

        assertEquals("assistant-stream-text", firstTarget.partId)
        assertTrue(grownTarget.contentLength > firstTarget.contentLength)
        assertTrue(grownTarget.rowCount > firstTarget.rowCount)
        assertTrue(grownTarget.lastRowKey != firstTarget.lastRowKey)
    }

    @Test
    fun `streaming plans remain isolated when messages reuse a part id`() {
        val state = ChatMessageRowPlanningState()
        val sharedPartId = "shared-text"
        val first = assistantMessage("assistant-one", streamingMathText(40), partId = sharedPartId)
        val second = assistantMessage("assistant-two", longNonMathText(), partId = sharedPartId)

        val initialRows = planChatMessageRows(listOf(first, second), state)
        val firstPrefixKey = initialRows.first { it.sourceMessageIndex == 0 }.key
        val grownFirst = assistantMessage("assistant-one", streamingMathText(80), partId = sharedPartId)
        val grownRows = planChatMessageRows(listOf(grownFirst, second), state)

        assertEquals(firstPrefixKey, grownRows.first { it.sourceMessageIndex == 0 }.key)
        assertTrue(grownRows.filter { it.sourceMessageIndex == 0 }.all { "assistant-one" in it.key })
        assertTrue(grownRows.filter { it.sourceMessageIndex == 1 }.all { "assistant-two" in it.key })
    }

    private fun assistantMessage(
        id: String,
        text: String,
        extraParts: List<Part> = emptyList(),
        partId: String = "$id-text",
    ): ChatMessage = ChatMessage(
        message = Message.Assistant(id = id, sessionId = SessionId, time = TimeInfo(created = 1L)),
        parts = listOf(textPart(id, partId, text)) + extraParts,
    )

    private fun textPart(messageId: String, partId: String, text: String): Part.Text = Part.Text(
        id = partId,
        sessionId = SessionId,
        messageId = messageId,
        text = text,
    )

    private fun longMathText(): String = buildString {
        append("Display math: \\[x^2 + y^2 = z^2\\]\n\n")
        repeat(100) { index ->
            append("Lead paragraph $index ")
            append("content ".repeat(20))
            append("\n\n")
        }
        append("```text\n信号是连续的、网络只吃向量 ${"0123456789abcdef".repeat(36)}\n```\n\n")
        repeat(45) { index ->
            append("Trailing paragraph $index ")
            append("content ".repeat(20))
            if (index != 44) append("\n\n")
        }
    }

    private fun longNonMathText(): String = buildString {
        repeat(145) { index ->
            append("Paragraph $index ")
            append("content ".repeat(23))
            if (index != 144) append("\n\n")
        }
    }

    private fun streamingMathText(paragraphCount: Int): String = buildString {
        append("Display math: \\[x^2\\]\n\n")
        repeat(paragraphCount) { index ->
            append("Streaming paragraph $index ")
            append("content ".repeat(20))
            if (index != paragraphCount - 1) append("\n\n")
        }
    }

    private companion object {
        const val SessionId = "session"
    }
}
