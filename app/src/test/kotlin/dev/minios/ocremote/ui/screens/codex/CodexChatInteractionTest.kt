package dev.minios.ocremote.ui.screens.codex

import dev.minios.ocremote.data.codex.CodexServerRequest
import dev.minios.ocremote.data.codex.CodexThread
import dev.minios.ocremote.data.codex.CodexThreadItem
import dev.minios.ocremote.data.codex.CodexTurn
import dev.minios.ocremote.data.codex.CodexToolUserInputOption
import dev.minios.ocremote.data.codex.CodexToolUserInputQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.lifecycle.Lifecycle

class CodexChatInteractionTest {
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
    fun `timeline presentation keeps prose unbounded and technical items independent`() {
        val user = CodexThreadItem.fromJson(
            Json.parseToJsonElement("""{"id":"user","type":"userMessage","content":[{"type":"text","text":"ship it"}]}""").jsonObject,
        )
        val assistant = CodexThreadItem.fromJson(
            Json.parseToJsonElement("""{"id":"assistant","type":"agentMessage","text":"Done"}""").jsonObject,
        )
        val emptyAssistant = CodexThreadItem.fromJson(
            Json.parseToJsonElement("""{"id":"stream","type":"agentMessage","text":""}""").jsonObject,
        )
        val reasoning = CodexThreadItem.fromJson(
            Json.parseToJsonElement("""{"id":"reasoning","type":"reasoning","text":"Inspecting"}""").jsonObject,
        )
        val tool = CodexThreadItem.fromJson(
            Json.parseToJsonElement("""{"id":"tool","type":"commandExecution","command":"./gradlew test"}""").jsonObject,
        )

        assertEquals(CodexTimelinePresentation.USER_PROMPT, codexTimelinePresentation(user))
        assertEquals(CodexTimelinePresentation.ASSISTANT_PROSE, codexTimelinePresentation(assistant))
        assertEquals(CodexTimelinePresentation.ASSISTANT_PROSE, codexTimelinePresentation(emptyAssistant))
        assertEquals(CodexTimelinePresentation.REASONING, codexTimelinePresentation(reasoning))
        assertEquals(CodexTimelinePresentation.WORK_UNIT, codexTimelinePresentation(tool))
    }

    @Test
    fun `pending request submission begins only once`() {
        val state = CodexChatUiState()
        val started = requireNotNull(beginCodexRequestSubmission(state, "request-1"))

        assertTrue("request-1" in started.submittingRequestKeys)
        assertEquals(null, beginCodexRequestSubmission(started, "request-1"))
    }

    @Test
    fun `multi-select answers preserve option order and append custom input`() {
        val question = CodexToolUserInputQuestion(
            id = "targets",
            header = "Targets",
            question = "Where?",
            options = listOf(
                CodexToolUserInputOption("Local", ""),
                CodexToolUserInputOption("Remote", ""),
            ),
            multiSelect = true,
            isOther = true,
        )

        val answers = buildCodexUserInputAnswers(
            questions = listOf(question),
            selections = mapOf("targets" to listOf("Remote", "Local")),
            customValues = mapOf("targets" to "  Staging  "),
        )

        assertEquals(listOf("Local", "Remote", "Staging"), answers["targets"])
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

    private fun approvalRequest(raw: String): CodexServerRequest = requireNotNull(
        CodexServerRequest.fromJson(Json.parseToJsonElement(raw).jsonObject),
    )
}
