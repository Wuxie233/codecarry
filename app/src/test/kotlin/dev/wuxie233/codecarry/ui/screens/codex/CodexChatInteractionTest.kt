package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexServerRequest
import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import dev.wuxie233.codecarry.data.codex.CodexTurn
import dev.wuxie233.codecarry.data.codex.requestKey
import dev.wuxie233.codecarry.ui.screens.chat.ChatResponseDockKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import androidx.lifecycle.Lifecycle

class CodexChatInteractionTest {
    @Test
    fun `attachment recovery keeps daemon metadata but never saves image bytes`() {
        val daemonImage = CodexComposerAttachment("daemon", "photo", dev.wuxie233.codecarry.data.codex.CodexUserInput.LocalImage("/daemon/photo.png", "original"))
        val restored = requireNotNull(restoreCodexAttachment(requireNotNull(persistableCodexAttachment(daemonImage))))
        assertEquals(daemonImage.input.toJson(), restored.input.toJson())
        assertEquals("daemon", restored.id)
        val binaryImage = CodexComposerAttachment("binary", "photo", dev.wuxie233.codecarry.data.codex.CodexUserInput.Image("data:image/png;base64,AQID"))
        assertNull(persistableCodexAttachment(binaryImage))
    }

    @Test
    fun `history editing counts following turns and preserves attachment wire fields`() {
        val original = CodexThreadItem.fromJson(Json.parseToJsonElement("""{
            "id":"user-1","type":"userMessage","content":[
            {"type":"text","text":"first"},
            {"type":"image","url":"data:image/png;base64,AQID","detail":"original"},
            {"type":"mention","name":"README","path":"/repo/README.md"}]}
        """).jsonObject)
        val thread = CodexThread(id = "thread", turns = listOf(
            CodexTurn(id = "keep", status = "completed"),
            CodexTurn(id = "edit", status = "canceled", items = listOf(original)),
            CodexTurn(id = "remove", status = "completed"),
        ))
        val plan = requireNotNull(planCodexMessageEdit(thread, "user-1"))
        assertEquals(2, plan.removedTurnCount)
        assertEquals(listOf("keep"), plan.retainedTurnIds)
        assertEquals("original", plan.attachments.first().input.toJson()["detail"]?.jsonPrimitive?.content)
        assertEquals("/repo/README.md", plan.attachments.last().input.toJson()["path"]?.jsonPrimitive?.content)
        for (running in listOf("inProgress", "in_progress", "running")) {
            assertNull(planCodexMessageEdit(thread.copy(turns = thread.turns + CodexTurn("live", running)), "user-1"))
        }
    }

    @Test
    fun `history editing rejects unknown attachments instead of dropping them`() {
        val original = CodexThreadItem.fromJson(Json.parseToJsonElement("""{
            "id":"user-1","type":"userMessage","content":[
            {"type":"text","text":"first"},{"type":"futureInput","value":"preserve me"}]}
        """).jsonObject)
        val thread = CodexThread(id = "thread", turns = listOf(CodexTurn(
            id = "edit", status = "completed", items = listOf(original),
        )))
        assertNull(planCodexMessageEdit(thread, "user-1"))
    }

    @Test
    fun `quote keeps the existing draft and quotes every line without sending`() {
        assertEquals("draft\n\n> first\n> second\n\n", appendCodexQuote("draft", "first\nsecond"))
        assertEquals("draft", appendCodexQuote("draft", "  "))
    }

    @Test
    fun `draft clears only after the matching content is accepted`() {
        assertTrue(shouldClearCodexDraft("  ship it  ", CodexSendResult("ship it", true)))
        assertFalse(shouldClearCodexDraft("ship it", CodexSendResult("ship it", false)))
        assertFalse(shouldClearCodexDraft("newer draft", CodexSendResult("ship it", true)))
    }

    @Test
    fun `MCP form validation enforces numeric and multi-select bounds`() {
        val number = buildJsonObject {
            put("type", "integer")
            put("minimum", 2)
            put("maximum", 4)
        }
        assertTrue(validateMcpFormValue(number, JsonPrimitive(3), required = true))
        assertFalse(validateMcpFormValue(number, JsonPrimitive(5), required = true))

        val choices = buildJsonObject {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", 2)
        }
        assertFalse(validateMcpFormValue(choices, JsonArray(emptyList()), required = true))
        assertTrue(
            validateMcpFormValue(
                choices,
                JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))),
                required = true,
            ),
        )
    }

    @Test
    fun `MCP elicitation response uses the Codex wire shape`() {
        val content = buildJsonObject { put("project", "oc-remote") }

        val accepted = codexElicitationResponse("accept", content)
        val declined = codexElicitationResponse("decline")

        assertTrue(accepted["action"]?.jsonPrimitive?.content == "accept")
        assertTrue(accepted["content"]?.jsonObject?.get("project")?.jsonPrimitive?.content == "oc-remote")
        assertTrue(declined["action"]?.jsonPrimitive?.content == "decline")
        assertFalse(declined.containsKey("content"))
    }

    @Test
    fun `chat visibility follows foreground lifecycle`() {
        assertTrue(codexChatVisibilityForEvent(Lifecycle.Event.ON_START) == true)
        assertTrue(codexChatVisibilityForEvent(Lifecycle.Event.ON_STOP) == false)
        assertTrue(codexChatVisibilityForEvent(Lifecycle.Event.ON_PAUSE) == null)
    }

    @Test
    fun `command approval preserves server decision order including cancel`() {
        val request = approvalRequest(
            """{"id":"approval-1","method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","command":"curl example.com","cwd":"/workspace","environmentId":"production","availableDecisions":["accept","cancel"]}}""",
        )

        val presentation = codexApprovalPresentation(request, thread = null)

        assertTrue(presentation.decisions == listOf("accept", "cancel"))
        assertTrue(presentation.canApprove)
        assertTrue(presentation.details.any { it.kind == CodexApprovalDetailKind.WORKING_DIRECTORY })
        assertTrue(presentation.details.any {
            it.kind == CodexApprovalDetailKind.ENVIRONMENT && it.value == "production"
        })
    }

    @Test
    fun `network approval exposes host and additional permissions`() {
        val request = approvalRequest(
            """{"id":"approval-2","method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","networkApprovalContext":{"host":"api.example.com","protocol":"https"},"additionalPermissions":{"network":{"enabled":true}},"availableDecisions":["cancel","accept"]}}""",
        )

        val presentation = codexApprovalPresentation(request, thread = null)

        assertTrue(presentation.canApprove)
        assertTrue(presentation.details.any { it.value == "https://api.example.com" })
        assertTrue(presentation.details.any { it.kind == CodexApprovalDetailKind.ADDITIONAL_PERMISSIONS })
    }

    @Test
    fun `file approval requires matching file change paths before approval`() {
        val request = approvalRequest(
            """{"id":"approval-3","method":"item/fileChange/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","availableDecisions":["accept","cancel"]}}""",
        )
        val missing = codexApprovalPresentation(request, thread = null)
        val item = CodexThreadItem.fromJson(
            Json.parseToJsonElement(
                """{"id":"item-1","type":"fileChange","status":"inProgress","changes":[{"path":"src/Auth.kt","kind":{"type":"update"},"diff":"+secure"}]}""",
            ).jsonObject,
        )
        val thread = CodexThread(
            id = "thread-1",
            turns = listOf(CodexTurn(id = "turn-1", items = listOf(item))),
        )

        val visible = codexApprovalPresentation(request, thread)

        assertFalse(missing.canApprove)
        assertTrue(visible.canApprove)
        assertTrue(visible.details.any { it.value == "update: src/Auth.kt" })
    }

    @Test
    fun `permission approval exposes turn and session grants without available decisions`() {
        val request = approvalRequest(
            """{"id":"approval-4","method":"item/permissions/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","environmentId":"production","cwd":"/workspace","startedAtMs":10,"permissions":{"network":{"enabled":true}}}}""",
        )

        val presentation = codexApprovalPresentation(request, thread = null)

        assertEquals(listOf("decline", "accept", "acceptForSession"), presentation.decisions)
        assertTrue(presentation.canApprove)
    }

    @Test
    fun `uncertain delivery blocks another send until accepted`() {
        val ids = ArrayDeque(listOf("first", "second"))
        val tracker = CodexSendIdentityTracker(createId = { ids.removeFirst() })

        val first = requireNotNull(tracker.begin("ship it"))
        tracker.markUncertain("ship it", first)
        val blocked = tracker.begin("ship it")
        tracker.markAccepted("ship it", first)
        val next = tracker.begin("ship it")

        assertEquals("first", first)
        assertEquals(null, blocked)
        assertEquals("second", next)
    }

    @Test
    fun `restored uncertain delivery remains blocked after recreation`() {
        val tracker = CodexSendIdentityTracker(
            createId = { "new" },
            initialContent = "ship it",
            initialId = "persisted",
        )

        assertEquals(CodexPendingSend("ship it", "persisted"), tracker.uncertain())
        assertEquals(null, tracker.begin("ship it"))
    }

    @Test
    fun `thread lookup reconciles accepted client message id`() {
        val item = CodexThreadItem.fromJson(
            Json.parseToJsonElement(
                """{"id":"item-1","clientId":"client-message-1","type":"userMessage","content":[{"type":"text","text":"ship it"}]}""",
            ).jsonObject,
        )
        val thread = CodexThread(
            id = "thread-1",
            turns = listOf(CodexTurn(id = "turn-1", items = listOf(item))),
        )

        assertTrue(thread.hasClientMessage("client-message-1"))
        assertFalse(thread.hasClientMessage("missing"))
    }

    @Test
    fun `new authoritative turn releases the post-accept send lock`() {
        val baseline = setOf("existing-turn")
        val unchanged = CodexThread(
            id = "thread-1",
            turns = listOf(CodexTurn(id = "existing-turn", status = "completed")),
        )
        val updated = unchanged.copy(
            turns = unchanged.turns + CodexTurn(id = "actual-turn", status = "inProgress"),
        )

        assertFalse(unchanged.hasTurnAfter(baseline))
        assertTrue(updated.hasTurnAfter(baseline))
    }

    @Test
    fun `authoritative turn lock survives recreation until a new turn arrives`() {
        val tracker = CodexAuthoritativeTurnTracker(setOf("existing-turn"))

        assertTrue(tracker.isAwaiting)
        assertFalse(tracker.observe(setOf("existing-turn")))
        assertTrue(tracker.observe(setOf("existing-turn", "actual-turn")))
        assertFalse(tracker.isAwaiting)
    }

    @Test
    fun `accepted delivery remains recoverable until an authoritative turn arrives`() {
        val tracker = CodexSendIdentityTracker(createId = { "client-message-1" })
        val id = requireNotNull(tracker.begin("ship it"))

        assertTrue(tracker.markAccepted("ship it", id, awaitAuthoritativeTurn = true))
        assertEquals(CodexPendingSend("ship it", id), tracker.pendingConfirmation())
        assertEquals(null, tracker.begin("another message"))

        tracker.confirmAuthoritative()
        assertEquals(null, tracker.pendingConfirmation())
        assertEquals("client-message-1", tracker.begin("another message"))
    }

    @Test
    fun `response dock lists every pending approval and question`() {
        val approval = approvalRequest(
            """{"id":"approval-1","method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","command":"ls"}}""",
        )
        val firstQuestion = userInputRequest(
            """{"id":"input-1","method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","questions":[{"id":"q1","header":"One","question":"Pick one","options":[{"label":"A","description":"First"}]}]}}""",
        )
        val secondQuestion = userInputRequest(
            """{"id":"input-2","method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-2","questions":[{"id":"q2","header":"Two","question":"Pick two","options":[{"label":"B","description":"Second"}]}]}}""",
        )
        val elicitation = requireNotNull(
            CodexServerRequest.fromJson(
                Json.parseToJsonElement(
                    """{"id":"elicit-1","method":"mcpServer/elicitation/request","params":{"serverName":"docs","mode":"form"}}""",
                ).jsonObject,
            ),
        )

        val mixed = buildCodexResponseDockItems(listOf(firstQuestion, approval, secondQuestion, elicitation))
        val items = buildCodexResponseDockItems(listOf(approval, firstQuestion, secondQuestion, elicitation))

        assertEquals(
            listOf(
                ChatResponseDockKind.Question,
                ChatResponseDockKind.Permission,
                ChatResponseDockKind.Question,
                ChatResponseDockKind.Question,
            ),
            mixed.map { it.kind },
        )
        assertEquals(
            listOf(firstQuestion, approval, secondQuestion, elicitation).map { it.id.requestKey() },
            mixed.map { it.ownershipId },
        )
        assertEquals(
            listOf(
                ChatResponseDockKind.Permission,
                ChatResponseDockKind.Question,
                ChatResponseDockKind.Question,
                ChatResponseDockKind.Question,
            ),
            items.map { it.kind },
        )
        assertEquals(
            listOf(approval, firstQuestion, secondQuestion, elicitation).map { it.id.requestKey() },
            items.map { it.ownershipId },
        )
        assertEquals(approval, items[0].codexRequest(listOf(approval, firstQuestion, secondQuestion, elicitation)))
        assertEquals(secondQuestion, items[2].codexRequest(listOf(approval, firstQuestion, secondQuestion, elicitation)))
    }

    @Test
    fun `single select option submits labeled answers immediately`() {
        val questions = userInputRequest(
            """{"id":"input-1","method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","questions":[{"id":"target","header":"Target","question":"Where?","options":[{"label":"Local","description":"Here"},{"label":"Remote","description":"There"}]}]}}""",
        ).userInput!!.questions

        assertTrue(codexUserInputAllowsInstantSubmit(questions))
        assertEquals(
            mapOf("target" to listOf("Local")),
            codexInstantOptionAnswer(questions, "target", "Local"),
        )
        assertNull(codexInstantCustomAnswer(questions, "target", "somewhere else"))
    }

    @Test
    fun `custom and batch questions require explicit submit payloads`() {
        val custom = userInputRequest(
            """{"id":"input-1","method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","questions":[{"id":"target","header":"Target","question":"Where?","options":[{"label":"Local","description":"Here"}],"isOther":true}]}}""",
        ).userInput!!.questions
        val batch = userInputRequest(
            """{"id":"input-2","method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-2","questions":[{"id":"one","header":"One","question":"First","options":[{"label":"A","description":""}]},{"id":"two","header":"Two","question":"Second","options":[{"label":"B","description":""}]}]}}""",
        ).userInput!!.questions

        assertTrue(codexUserInputNeedsExplicitSubmit(custom))
        assertNull(codexInstantOptionAnswer(custom, "target", "Local"))
        assertEquals(
            mapOf("target" to listOf("somewhere else")),
            codexUserInputAnswerPayload(custom, mapOf("target" to listOf(" somewhere else "))),
        )
        assertTrue(codexUserInputNeedsExplicitSubmit(batch))
        assertFalse(codexUserInputDraftComplete(batch, mapOf("one" to listOf("A"))))
        assertTrue(codexUserInputDraftComplete(batch, mapOf("one" to listOf("A"), "two" to listOf("B"))))
        assertEquals(
            mapOf("one" to listOf("A"), "two" to listOf("B")),
            codexUserInputAnswerPayload(batch, mapOf("one" to listOf("A"), "two" to listOf("B"))),
        )
    }

    @Test
    fun `file preview error keeps the path copyable`() {
        val loading = CodexFilePreviewState(path = "/workspace/handoff.txt", isLoading = true)
        val failed = CodexFilePreviewState(
            path = "/workspace/handoff.txt",
            isLoading = false,
            error = "Remote file exceeds 1 MiB",
        )
        val ready = CodexFilePreviewState(
            path = "/workspace/handoff.txt",
            isLoading = false,
            contents = "hello",
        )

        assertEquals("/workspace/handoff.txt", loading.path)
        assertTrue(loading.isLoading)
        assertNull(loading.error)
        assertEquals("/workspace/handoff.txt", failed.path)
        assertEquals("Remote file exceeds 1 MiB", failed.error)
        assertNull(failed.contents)
        assertEquals("hello", ready.contents)
        assertNull(ready.error)
    }

    @Test
    fun `rejected reply unlocks the same request id`() {
        val first = codexRequestUnlockToken("string:input-1", mapOf("string:input-1" to "rejected"))
        val same = codexRequestUnlockToken("string:input-1", mapOf("string:input-1" to "rejected"))
        val retried = codexRequestUnlockToken("string:input-1", mapOf("string:input-1" to "still rejected"))
        val other = codexRequestUnlockToken("string:input-2", mapOf("string:input-1" to "rejected"))

        assertTrue(first != 0)
        assertEquals(first, same)
        assertNotEquals(first, retried)
        assertEquals(0, other)
    }

    private fun approvalRequest(raw: String): CodexServerRequest = requireNotNull(
        CodexServerRequest.fromJson(Json.parseToJsonElement(raw).jsonObject),
    )

    private fun userInputRequest(raw: String): CodexServerRequest = requireNotNull(
        CodexServerRequest.fromJson(Json.parseToJsonElement(raw).jsonObject),
    )
}
