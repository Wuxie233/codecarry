package dev.wuxie233.codecarry.ui.screens.chat

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.wuxie233.codecarry.data.api.PromptPart
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.dsh.DshApiClient
import dev.wuxie233.codecarry.data.dsh.DshConnection
import dev.wuxie233.codecarry.data.dsh.DshConnectionManager
import dev.wuxie233.codecarry.data.dsh.DshDownlink
import dev.wuxie233.codecarry.data.dsh.DshDownlinkFactory
import dev.wuxie233.codecarry.data.dsh.FakeDownlink
import dev.wuxie233.codecarry.data.dsh.historyAddress
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.DraftRepository
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelDshFollowTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)
    private val viewModels = mutableListOf<ChatViewModel>()
    private val collectJobs = mutableListOf<Job>()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        collectJobs.forEach(Job::cancel)
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `skills retry and disconnect preserve session scoped request lifetime`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        var fail = true
        val harness = dshHarness(mux, unary, beforeSkillList = {
            if (fail) error("skills unavailable")
        })
        val vm = newViewModel(harness.client, harness.manager)
        assertFalse(vm.dshSkills.value.available)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        vm.dshSkills.first { it.failed }
        fail = false
        vm.refreshDshSkills()
        assertEquals("grill", vm.dshSkills.first { it.skills.isNotEmpty() }.skills.single().name)
        assertEquals("compact", vm.dshSkills.value.commands.single().name)
        val commandRequest = json.parseToJsonElement(unary.last { it.contains("\"commands/list\"") }).jsonObject
        assertEquals(SESSION_ID, commandRequest["payload"]!!.jsonObject["args"]!!.jsonObject["agentId"]!!.jsonPrimitive.content)
        val request = json.parseToJsonElement(unary.last { it.contains("\"skills/list\"") }).jsonObject
        assertEquals(SESSION_ID, request["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        harness.manager.disconnect(SERVER_ID)
        runCurrent()
        assertFalse(vm.dshSkills.value.available)
        assertTrue(vm.dshSkills.value.skills.isEmpty())
        assertTrue(vm.dshSkills.value.commands.isEmpty())
    }

    @Test
    fun `skill selection validates deduplicates removes and persists without sending`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary, skillList = """[
            {"name":"grill","description":"Discuss","modelInvocable":false},
            {"name":"compact","description":"Skill","modelInvocable":true},
            {"name":"bad\nname","description":"Bad","modelInvocable":true}]
        """)
        val files = tmpFolder.newFolder()
        val context = object : ContextWrapper(null) { override fun getFilesDir() = files }
        val vm = newViewModel(harness.client, harness.manager, drafts = DraftRepository(context))
        assertFalse(vm.selectDshSkill("grill"))
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent(); pushBaselines(mux)
        vm.dshSkills.first { it.skills.isNotEmpty() }
        vm.updateDraftText("before /query after")
        assertTrue(vm.selectDshSkill("grill"))
        assertTrue(vm.selectDshSkill("grill"))
        assertTrue(vm.selectDshSkill("compact"))
        assertFalse(vm.selectDshSkill("missing"))
        assertFalse(vm.selectDshSkill("bad\nname"))
        assertFalse(vm.selectDshSkill("/compact"))
        assertEquals(listOf("grill", "compact"), vm.dshSelectedSkillNames.value)
        vm.removeDshSkill("grill")
        assertEquals(listOf("compact"), vm.dshSelectedSkillNames.value)
        val restored = newViewModel(harness.client, harness.manager, drafts = DraftRepository(context))
        assertEquals("before /query after", restored.draftText.value)
        assertEquals(listOf("compact"), restored.dshSelectedSkillNames.value)
        val other = newViewModel(harness.client, harness.manager, sessionId = "other", drafts = DraftRepository(context))
        assertTrue(other.dshSelectedSkillNames.value.isEmpty())
        val otherServer = newViewModel(harness.client, harness.manager, serverId = "other-server", drafts = DraftRepository(context))
        assertTrue(otherServer.dshSelectedSkillNames.value.isEmpty())
        assertEquals("", otherServer.draftText.value)
        assertFalse(unary.any { it.contains("commands/execute") || it.contains("session/prompt") })
        harness.manager.disconnect(SERVER_ID)
        assertFalse(vm.selectDshSkill("grill"))
        restored.clearDraft()
        assertTrue(newViewModel(harness.client, harness.manager, drafts = DraftRepository(context)).dshSelectedSkillNames.value.isEmpty())
    }

    @Test
    fun `legacy draft restores and chip-only draft survives reload`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val harness = dshHarness(mux, CopyOnWriteArrayList())
        val files = tmpFolder.newFolder()
        val context = object : ContextWrapper(null) { override fun getFilesDir() = files }
        java.io.File(files, "session_drafts.json").writeText("""{"$SESSION_ID":{"text":"legacy"}}""")
        val vm = newViewModel(harness.client, harness.manager, drafts = DraftRepository(context))
        assertEquals("legacy", vm.draftText.value)
        assertTrue(vm.dshSelectedSkillNames.value.isEmpty())
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent(); pushBaselines(mux)
        vm.dshSkills.first { it.skills.isNotEmpty() }
        vm.updateDraftText("")
        assertTrue(vm.selectDshSkill("grill"))
        val restored = newViewModel(harness.client, harness.manager, drafts = DraftRepository(context))
        assertEquals("", restored.draftText.value)
        assertEquals(listOf("grill"), restored.dshSelectedSkillNames.value)
    }

    @Test
    fun `skill-only compact is a retryable prompt snapshot and compact action keeps draft`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        var fail = true
        val attempted = CompletableDeferred<Unit>()
        val command = Channel<Unit>(Channel.UNLIMITED)
        val harness = dshHarness(mux, unary,
            skillList = """[{"name":"compact","description":"Skill","modelInvocable":false}]""",
            beforePrompt = { attempted.complete(Unit); if (fail) error("offline") },
            onCommandExecute = { command.trySend(Unit) })
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent(); pushBaselines(mux)
        vm.dshSkills.first { it.skills.isNotEmpty() }
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        assertTrue(vm.selectDshSkill("compact"))
        vm.sendMessage("") { accepted -> assertTrue(accepted); vm.clearDraft() }
        attempted.await()
        vm.uiState.first { it.pendingSendError != null }
        assertTrue(vm.dshSelectedSkillNames.value.isEmpty())
        assertTrue(vm.selectDshSkill("compact"))
        vm.removeDshSkill("compact")
        fail = false
        vm.retryPendingSend()
        vm.uiState.first { it.pendingSendCount == 0 }
        val requests = unary.filter { it.contains("\"session/prompt\"") }.map {
            json.parseToJsonElement(it).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        }
        assertEquals(2, requests.size)
        assertEquals(requests[0]["requestId"], requests[1]["requestId"])
        requests.forEach { assertEquals("/compact", it["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content) }
        assertEquals(0, unary.count { it.contains("commands/execute") })
        vm.sendMessage("/compact")
        command.receive()
        vm.uiState.first { it.pendingSendCount == 0 && !it.isSending }
        assertEquals(1, unary.count { it.contains("commands/execute") })
        assertTrue(vm.selectDshSkill("compact"))
        vm.updateDraftText("keep me")
        vm.compactDshSession()
        command.receive()
        assertEquals("keep me", vm.draftText.value)
        assertEquals(listOf("compact"), vm.dshSelectedSkillNames.value)
        assertEquals(2, unary.count { it.contains("commands/execute") })
        assertEquals(2, unary.count { it.contains("\"session/prompt\"") })
    }

    @Test
    fun `structured send freezes skills and preserves text and image`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        val attempted = Channel<Unit>(Channel.UNLIMITED)
        val harness = dshHarness(mux, unary, beforePrompt = { attempted.send(Unit); gate.await() })
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent(); pushBaselines(mux)
        vm.dshSkills.first { it.skills.isNotEmpty() }
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        assertTrue(vm.selectDshSkill("grill"))
        vm.sendMessage(listOf(PromptPart(type = "text", text = " exact text ")),
            listOf(PromptPart(type = "file", mime = "image/png", url = "data:image/png;base64,YWJj"))) {
            assertTrue(it); vm.clearDraft()
        }
        attempted.receive()
        assertFalse(vm.selectDshSkill("grill"))
        gate.complete(Unit)
        vm.uiState.first { it.pendingSendCount == 0 }
        val request = json.parseToJsonElement(unary.single { it.contains("\"session/prompt\"") }).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        val content = request["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals(" exact text ", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("YWJj", content[1].jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", content[1].jsonObject["mediaType"]!!.jsonPrimitive.content)
        assertEquals("/grill", content[2].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(vm.selectDshSkill("grill"))
        vm.sendMessage(emptyList(), emptyList()) { assertTrue(it); vm.clearDraft() }
        attempted.receive()
        vm.uiState.first { it.pendingSendCount == 0 }
        assertEquals(2, unary.count { it.contains("\"session/prompt\"") })
    }

    @Test
    fun `opening a DSH chat follows the snapshot and never pages with a sentinel`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        runCurrent()

        assertTrue(vm.uiState.value.queuedPrompts.isEmpty())
        val followOpen = mux.sent.last { it.contains("\"session/follow\"") }
        val followId = json.parseToJsonElement(followOpen).jsonObject.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(
            item(
                followId,
                """{"type":"snapshot","header":null,"cursor":12072,"records":[{"type":"event","event":{"type":"user/message","seq":12070,"time":1,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}],"hasMore":true}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()

        val state = vm.uiState.first { !it.isLoading && it.error == null && it.messages.isNotEmpty() }
        assertEquals("hello", (state.messages.single().parts.single() as Part.Text).text)
        assertTrue(state.hasOlderMessages)
        assertFalse(unary.any { it.contains("session/page") })
        assertFalse(unary.any { it.contains("9007199254740991") })
        assertEquals(
            12072L,
            harness.manager.reducer(SERVER_ID).state.value.sessions.getValue(SESSION_ID).pageThroughSeq,
        )

        vm.loadOlderMessages()
        runCurrent()
        advanceUntilIdle()

        val pageBodies = unary.filter { it.contains("\"session/page\"") }
        assertEquals(1, pageBodies.size)
        val request = json.parseToJsonElement(pageBodies.single()).jsonObject
            .getValue("payload").jsonObject
            .getValue("args").jsonObject
            .getValue("request").jsonObject
        assertEquals(12072L, request.getValue("throughSeq").jsonPrimitive.content.toLong())
        assertEquals(12070L, request.getValue("beforeSeq").jsonPrimitive.content.toLong())
        val address = request.getValue("address").jsonObject
        assertEquals("session", address.getValue("kind").jsonPrimitive.content)
        assertEquals(SESSION_ID, address.getValue("sessionId").jsonPrimitive.content)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `subagent list item follows with parent child and mode`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(
            mux,
            unary,
            listItem = """{"sessionId":"$SESSION_ID","updatedAt":2,"running":false,"blank":false,"cwd":"/root/CODE/Minecraft","origin":"subagent","parentSessionId":"$PARENT_ID","projections":{"asOfSeq":1,"values":{"subagent":{"mode":"continuable"}}}}""",
        )
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        runCurrent()

        val followOpen = mux.sent.last { it.contains("\"session/follow\"") }
        val followObj = json.parseToJsonElement(followOpen).jsonObject
        val address = followObj.getValue("payload").jsonObject
            .getValue("args").jsonObject
            .getValue("request").jsonObject
            .getValue("address").jsonObject
        assertEquals("subagent", address.getValue("kind").jsonPrimitive.content)
        assertEquals(PARENT_ID, address.getValue("parentSessionId").jsonPrimitive.content)
        assertEquals(SESSION_ID, address.getValue("childSessionId").jsonPrimitive.content)
        assertEquals("continuable", address.getValue("mode").jsonPrimitive.content)

        val followId = followObj.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(
            item(
                followId,
                """{"type":"snapshot","header":{"parentSession":"$PARENT_ID","origin":"subagent"},"cursor":12072,"records":[{"type":"event","event":{"type":"user/message","seq":12070,"time":1,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}],"hasMore":true}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()
        vm.uiState.first { !it.isLoading && it.error == null && it.messages.isNotEmpty() }

        vm.loadOlderMessages()
        runCurrent()
        advanceUntilIdle()

        val pageBodies = unary.filter { it.contains("\"session/page\"") }
        assertEquals(1, pageBodies.size)
        val pageAddress = json.parseToJsonElement(pageBodies.single()).jsonObject
            .getValue("payload").jsonObject
            .getValue("args").jsonObject
            .getValue("request").jsonObject
            .getValue("address").jsonObject
        assertEquals("subagent", pageAddress.getValue("kind").jsonPrimitive.content)
        assertEquals(PARENT_ID, pageAddress.getValue("parentSessionId").jsonPrimitive.content)
        assertEquals(SESSION_ID, pageAddress.getValue("childSessionId").jsonPrimitive.content)
        assertEquals("continuable", pageAddress.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `parent catalog opens unlisted child and control updates its drawer`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val compactRequested = CompletableDeferred<Unit>()
        val harness = dshHarness(mux, unary, onCommandExecute = { compactRequested.complete(Unit) })
        val parent = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { parent.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        runCurrent()
        val follow = json.parseToJsonElement(mux.sent.last { it.contains("\"session/follow\"") }).jsonObject
        val streamId = follow.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(item(streamId, """{"type":"snapshot","header":{},"cursor":10,"records":[],"hasMore":false,"projections":{"asOfSeq":10,"values":{"subagentCatalog":[{"id":"child","createdAt":1,"mode":"continuable","label":"Worker"}]}}}"""))
        runCurrent()
        assertEquals(listOf("child"), parent.uiState.value.subagents.map { it.id })
        assertEquals("Worker", parent.uiState.value.subagents.single().title)

        val child = newViewModel(harness.client, harness.manager, "child")
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { child.uiState.collect {} }
        runCurrent()
        val childOpen = json.parseToJsonElement(mux.sent.last { it.contains("\"session/follow\"") }).jsonObject
        val address = childOpen.getValue("payload").jsonObject.getValue("args").jsonObject
            .getValue("request").jsonObject.getValue("address").jsonObject
        assertEquals("subagent", address.getValue("kind").jsonPrimitive.content)
        assertEquals(SESSION_ID, address.getValue("parentSessionId").jsonPrimitive.content)
        assertEquals("child", address.getValue("childSessionId").jsonPrimitive.content)
        assertEquals("continuable", address.getValue("mode").jsonPrimitive.content)
        assertEquals("compact", child.dshSkills.first { it.commands.isNotEmpty() }.commands.single().name)
        assertEquals("grill", child.dshSkills.first { it.skills.isNotEmpty() }.skills.single().name)
        child.compactDshSession()
        compactRequested.await()
        runCurrent()
        val compact = json.parseToJsonElement(unary.single { it.contains("\"commands/execute\"") }).jsonObject
            .getValue("payload").jsonObject.getValue("args").jsonObject
        assertEquals("child", compact.getValue("agentId").jsonPrimitive.content)
        assertEquals("/compact", compact.getValue("line").jsonPrimitive.content)
        assertNull(child.uiState.value.error)

        mux.incoming.trySend(item("st-1", """{"type":"projection","sessionId":"$SESSION_ID","key":"subagentCatalog","seq":11,"value":[{"id":"second","createdAt":2,"mode":"one-shot","label":"Second"}]}"""))
        runCurrent()
        assertEquals(listOf("second"), parent.uiState.value.subagents.map { it.id })
        assertFalse(unary.any { it.contains("subagents/list") })
    }

    @Test
    fun `idle child send uses queue delivery`() = childSendDelivery(false, "queue")

    @Test
    fun `busy child send uses steer delivery`() = childSendDelivery(true, "steer")

    private fun childSendDelivery(running: Boolean, delivery: String) = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(
            mux,
            unary,
            listItem = """{"sessionId":"$SESSION_ID","updatedAt":2,"running":$running,"blank":false,"cwd":"/root/CODE/Minecraft","origin":"subagent","parentSessionId":"$PARENT_ID"}""",
        )
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        runCurrent()

        var sent: Boolean? = null
        vm.dshSkills.first { it.skills.isNotEmpty() }
        assertTrue(vm.selectDshSkill("grill"))
        vm.sendMessage("continue") { sent = it }
        runCurrent()

        assertEquals(true, sent)
        val body = unary.single { it.contains("\"subagents/prompt\"") }
        val request = json.parseToJsonElement(body).jsonObject.getValue("payload").jsonObject
            .getValue("args").jsonObject.getValue("request").jsonObject
        assertEquals("/grill", request.getValue("content").jsonArray.last().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(delivery, request.getValue("delivery").jsonPrimitive.content)
        assertEquals("continuable", request.getValue("mode").jsonPrimitive.content)
        assertEquals(PARENT_ID, request.getValue("parentSessionId").jsonPrimitive.content)
        assertEquals(SESSION_ID, request.getValue("childSessionId").jsonPrimitive.content)
        assertFalse(unary.any { it.contains("\"session/prompt\"") })
    }

    @Test
    fun `subagent origin without parent does not follow as session`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(
            mux,
            unary,
            listItem = """{"sessionId":"$SESSION_ID","updatedAt":2,"running":false,"blank":false,"cwd":"/root/CODE/Minecraft","origin":"subagent"}""",
        )
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        runCurrent()
        advanceUntilIdle()

        assertFalse(mux.sent.any { it.contains("\"session/follow\"") })
        assertFalse(unary.any { it.contains("session/page") })
        val snapshot = harness.manager.reducer(SERVER_ID).state.value.sessions[SESSION_ID]
        assertEquals("subagent", snapshot?.origin)
        assertNull(snapshot?.parentSessionId)
        assertNull(snapshot?.historyAddress())
    }

    @Test
    fun `load older without a follow cut does not page`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        vm.loadOlderMessages()
        runCurrent()
        advanceUntilIdle()
        assertFalse(unary.any { it.contains("session/page") })
    }

    @Test
    fun `retry without a ready mux surfaces an error instead of spinning`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        advanceUntilIdle()

        vm.loadMessages()
        runCurrent()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals("DSH is not connected", state.error)
        assertFalse(mux.sent.any { it.contains("\"session/follow\"") })
    }

    @Test
    fun `follow end while ready reopens session follow`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        runCurrent()

        val firstFollow = mux.sent.last { it.contains("\"session/follow\"") }
        val followId = json.parseToJsonElement(firstFollow).jsonObject.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(
            item(
                followId,
                """{"type":"snapshot","header":null,"cursor":12072,"records":[{"type":"event","event":{"type":"user/message","seq":12070,"time":1,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}],"hasMore":false}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()
        vm.uiState.first { !it.isLoading && it.messages.isNotEmpty() }

        mux.incoming.trySend("""{"type":"end","streamId":"$followId"}""")
        runCurrent()
        assertEquals("session/follow stream ended", vm.uiState.value.error)

        val opensBefore = mux.sent.count { it.contains("\"session/follow\"") }
        advanceTimeBy(1_000)
        runCurrent()

        val opensAfter = mux.sent.count { it.contains("\"session/follow\"") }
        assertEquals(opensBefore + 1, opensAfter)
        val secondFollow = mux.sent.last { it.contains("\"session/follow\"") }
        val secondId = json.parseToJsonElement(secondFollow).jsonObject.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(
            item(
                secondId,
                """{"type":"snapshot","header":null,"cursor":12072,"records":[{"type":"event","event":{"type":"user/message","seq":12070,"time":1,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}],"hasMore":false}""",
            ),
        )
        runCurrent()
        advanceUntilIdle()
        vm.uiState.first { it.error == null && it.messages.isNotEmpty() }
    }

    @Test
    fun `existing session displays its projected preset independently of roster default`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val harness = dshHarness(mux, CopyOnWriteArrayList(), listItem =
            """{"sessionId":"$SESSION_ID","updatedAt":2,"running":false,"blank":false,"projections":{"asOfSeq":10,"values":{"agentPreset":"custom-existing"}}}""")
        val vm = newViewModel(harness.client, harness.manager)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        val state = vm.dshPresets.first { it.currentId != null && !it.loading }
        assertEquals("custom-existing", state.currentId)
        assertEquals("standard", state.presets.single().id)
    }

    @Test
    fun `preset roster is visible while the unrelated session list remains pending`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val listStarted = CompletableDeferred<Unit>()
        val listResponse = CompletableDeferred<Unit>()
        val harness = dshHarness(mux, unary, beforeSessionList = {
            listStarted.complete(Unit)
            listResponse.await()
        })
        val vm = newViewModel(harness.client, harness.manager)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        listStarted.await()

        val presets = vm.dshPresets.first { it.presets.isNotEmpty() && !it.loading }
        assertEquals("standard", presets.presets.single().id)
        assertNull(presets.error)
        assertFalse(listResponse.isCompleted)
        assertEquals(1, unary.count { it.contains("\"agentPresets/list\"") })
    }

    @Test
    fun `other server state changes do not restart the chat preset request`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val presetStarted = CompletableDeferred<Unit>()
        val presetResponse = CompletableDeferred<Unit>()
        var requestCount = 0
        val harness = dshHarness(mux, unary, beforePresetList = {
            requestCount++
            presetStarted.complete(Unit)
            presetResponse.await()
        })
        val vm = newViewModel(harness.client, harness.manager)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        presetStarted.await()
        assertTrue(vm.dshPresets.value.loading)
        repeat(4) { index ->
            harness.manager.disconnect("other-$index")
            runCurrent()
        }
        assertEquals(1, requestCount)
        presetResponse.complete(Unit)
        val loaded = vm.dshPresets.first { it.presets.isNotEmpty() && !it.loading }
        assertNull(loaded.error)
        assertEquals(1, requestCount)
    }

    @Test
    fun `failed preset request ends loading and refresh can recover`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        var fail = true
        val harness = dshHarness(mux, unary, beforePresetList = {
            if (fail) error("preset transport unavailable")
        })
        val vm = newViewModel(harness.client, harness.manager)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        val failed = vm.dshPresets.first { it.error != null && !it.loading }
        assertTrue(failed.presets.isEmpty())
        assertFalse(failed.error.isNullOrBlank())
        fail = false
        vm.refreshDshPresets()
        val loaded = vm.dshPresets.first { it.presets.isNotEmpty() && !it.loading }
        assertNull(loaded.error)
    }

    @Test
    fun `preset deadline releases loading and a new request can succeed`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val requestStarted = CompletableDeferred<Unit>()
        val stalledResponse = CompletableDeferred<Unit>()
        var stall = true
        val harness = dshHarness(mux, unary, beforePresetList = {
            requestStarted.complete(Unit)
            if (stall) stalledResponse.await()
        })
        val vm = newViewModel(harness.client, harness.manager)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        requestStarted.await()
        assertTrue(vm.dshPresets.value.loading)

        // Ktor MockEngine and the bounded metadata read use real IO dispatch.
        // Await the actual deadline rather than racing a separate virtual clock.
        val timedOut = vm.dshPresets.first { !it.loading && it.error != null }
        assertTrue(timedOut.error!!.contains("timed out"))
        assertTrue(timedOut.presets.isEmpty())
        assertFalse(stalledResponse.isCompleted)

        stall = false
        vm.refreshDshPresets()
        val loaded = vm.dshPresets.first { !it.loading && it.presets.isNotEmpty() }
        assertNull(loaded.error)
        assertEquals("standard", loaded.presets.single().id)
        assertEquals(2, unary.count { it.contains("\"agentPresets/list\"") })
    }

    @Test
    fun `disconnect cancels a pending preset request without turning it into a timeout`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Unit>()
        val harness = dshHarness(mux, unary, beforePresetList = {
            started.complete(Unit)
            try {
                pending.await()
            } finally {
                cancelled.complete(Unit)
            }
        })
        val vm = newViewModel(harness.client, harness.manager)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        started.await()
        harness.manager.disconnect(SERVER_ID)
        cancelled.await()
        runCurrent()
        assertFalse(vm.dshPresets.value.loading)
        assertFalse(vm.dshPresets.value.ready)
        assertNull(vm.dshPresets.value.error)
    }

    @Test
    fun `reconnect catalog metadata cannot clear visible chat before follow snapshot`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val harness = dshHarness(mux, CopyOnWriteArrayList())
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        runCurrent()
        val followId = json.parseToJsonElement(mux.sent.last { it.contains("\"session/follow\"") })
            .jsonObject.getValue("streamId").jsonPrimitive.content
        mux.incoming.trySend(item(followId,
            """{"type":"snapshot","header":null,"cursor":1,"records":[{"type":"event","event":{"type":"user/message","seq":1,"time":1,"data":{"id":"u1","content":[{"type":"text","text":"keep visible"}],"source":{"kind":"user"}},"surfaceOp":"append"}}],"hasMore":false}"""))
        vm.uiState.first { it.messages.isNotEmpty() }
        val reducer = harness.manager.reducer(SERVER_ID)
        val metadata = dev.wuxie233.codecarry.data.dsh.DshSessionSummary(
            sessionId = SESSION_ID, updatedAt = 2, running = false, blank = false,
        )
        reducer.resetGeneration(reducer.state.value.generation + 1)
        runCurrent()
        reducer.applySessionList(listOf(metadata))
        runCurrent()
        assertEquals("keep visible", (vm.uiState.value.messages.single().parts.single() as Part.Text).text)
        reducer.applyFollowSnapshot(SESSION_ID, dev.wuxie233.codecarry.data.dsh.DshFollowFrame.Snapshot(
            header = null, cursor = -1, records = emptyList(), hasMore = false,
        ))
        runCurrent()
        assertTrue("An authoritative empty history may clear cached content", vm.uiState.value.messages.isEmpty())
    }

    @Test
    fun `ready reconnect follows known chat even when session catalog fails`() = runTest(dispatcher) {
        val firstMux = FakeDownlink()
        val secondMux = FakeDownlink()
        var opens = 0
        var failList = false
        val listFailed = CompletableDeferred<Unit>()
        val harness = dshHarness(firstMux, CopyOnWriteArrayList(),
            beforeSessionList = {
                if (failList) { listFailed.complete(Unit); error("catalog unavailable") }
            },
            openMux = { if (opens++ == 0) firstMux else secondMux },
        )
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(firstMux)
        harness.manager.reducer(SERVER_ID).state.first { it.sessions.containsKey(SESSION_ID) }
        runCurrent()
        assertTrue(firstMux.sent.any { it.contains("\"session/follow\"") })
        failList = true
        harness.manager.reconnect(SERVER_ID)
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true && opens == 2 }
        val ids = secondMux.sent.map { json.parseToJsonElement(it).jsonObject.getValue("streamId").jsonPrimitive.content }
        secondMux.incoming.trySend(item(ids[0], """{"type":"ready","clientId":"client-2","host":{"home":"/root"}}"""))
        secondMux.incoming.trySend(item(ids[1], """{"type":"baseline","value":{"projections":{}}}"""))
        secondMux.incoming.trySend(item(ids[2], """{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":[]}}"""))
        listFailed.await()
        runCurrent()
        assertTrue("Known conversation history must not depend on catalog recovery",
            secondMux.sent.any { it.contains("\"session/follow\"") })
    }

    @Test
    fun `preset selection waits for host confirmation`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val mux = FakeDownlink()
        val harness = dshHarness(mux, CopyOnWriteArrayList(), beforePresetSelect = {
            started.complete(Unit)
            response.await()
        }, listItem = """{"sessionId":"$SESSION_ID","updatedAt":2,"running":false,"blank":false,"projections":{"asOfSeq":10,"values":{"agentPreset":"old"}}}""")
        val vm = newViewModel(harness.client, harness.manager)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        vm.dshPresets.first { it.currentId == "old" && !it.loading }
        val result = CompletableDeferred<Boolean>()
        vm.selectDshPreset("standard") { result.complete(it) }
        started.await()
        assertTrue(vm.dshPresets.value.selecting)
        assertEquals("old", vm.dshPresets.value.currentId)
        assertFalse(result.isCompleted)
        response.complete(Unit)
        assertTrue(result.await())
        vm.dshPresets.first { it.currentId == "standard" && !it.selecting }
    }

    @Test
    fun `rejected preset selection retains the original preset and reports failure`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val harness = dshHarness(mux, CopyOnWriteArrayList(), presetSelectResult =
            """{"ok":false,"error":{"message":"Preset rejected","code":"preset/rejected"}}""",
            listItem = """{"sessionId":"$SESSION_ID","updatedAt":2,"running":false,"blank":false,"projections":{"asOfSeq":10,"values":{"agentPreset":"old"}}}""")
        val vm = newViewModel(harness.client, harness.manager)
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        vm.dshPresets.first { it.currentId == "old" && !it.loading }
        val result = CompletableDeferred<Boolean>()
        vm.selectDshPreset("standard") { result.complete(it) }
        assertFalse(result.await())
        val failed = vm.dshPresets.first { it.error != null && !it.selecting }
        assertEquals("old", failed.currentId)
        assertTrue(failed.error!!.contains("Preset rejected"))
    }

    private data class DshHarness(
        val client: DshApiClient,
        val manager: DshConnectionManager,
    )

    @Test
    fun `rewind waits for success then restores text and can rewind earlier messages`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val receipt = CompletableDeferred<String>()
        val earlierReceipt = CompletableDeferred<String>()
        var executions = 0
        val harness = dshHarness(mux, unary,
            commandList = """[{"name":"rewind","description":"Rewind","input":{"hint":"@<seq> chat|both"}}]""",
            executeCommand = { if (executions++ == 0) receipt.await() else earlierReceipt.await() })
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        vm.dshSkills.first { it.commands.any { command -> command.name == "rewind" } }
        runCurrent()
        val reducer = harness.manager.reducer(SERVER_ID)
        reducer.applyFollowSnapshot(SESSION_ID, dev.wuxie233.codecarry.data.dsh.DshFollowFrame.Snapshot(
            header = null, cursor = 3, records = emptyList(), hasMore = false,
        ))
        reducer.mergeHistory(SESSION_ID, listOf(rewindUser(1), rewindUser(2), rewindUser(3)))
        runCurrent()
        vm.updateDraftText("unsent")
        assertTrue(vm.selectDshSkill("grill"))
        val target = vm.uiState.value.messages[1].message.id
        assertTrue(vm.dshRewind.value.messageSeqs.contains(2))
        vm.rewindDshMessage(target)
        vm.rewindDshMessage(target)
        runCurrent()
        assertTrue(vm.dshRewind.value.inFlight)
        assertFalse(vm.selectDshSkill("grill"))
        vm.removeDshSkill("grill")
        assertEquals(listOf("grill"), vm.dshSelectedSkillNames.value)
        assertEquals(3, vm.uiState.value.messages.size)
        assertEquals("unsent", vm.draftText.value)
        reducer.mergeHistory(SESSION_ID, listOf(rewindMarker(4, 2, 3)))
        runCurrent()
        assertEquals(3, vm.uiState.value.messages.size)
        receipt.complete("""{"commandId":"cmd-1","result":{"kind":"success","sourceEventSeq":4}}""")
        vm.draftText.first { it == "prompt 2" }
        runCurrent()
        assertFalse(vm.dshRewind.value.inFlight)
        assertEquals(1, vm.uiState.value.messages.size)
        assertEquals("prompt 2", vm.draftText.value)
        assertTrue(vm.dshSelectedSkillNames.value.isEmpty())
        assertEquals(setOf(1L), vm.dshRewind.value.messageSeqs)
        val calls = unary.filter { it.contains("\"commands/execute\"") }
        assertEquals(1, calls.size)
        val args = json.parseToJsonElement(calls.single()).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("/rewind @2 chat", args["line"]!!.jsonPrimitive.content)
        assertEquals(SESSION_ID, args["agentId"]!!.jsonPrimitive.content)
        assertFalse(unary.any { it.contains("session/prompt") || it.contains("session/cancel") })
        vm.rewindDshMessage(vm.uiState.value.messages.single().message.id)
        runCurrent()
        reducer.mergeHistory(SESSION_ID, listOf(rewindMarker(5, 1, 4)))
        earlierReceipt.complete("""{"commandId":"cmd-2","result":{"kind":"success","sourceEventSeq":5}}""")
        vm.draftText.first { it == "prompt 1" }
        runCurrent()
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertTrue(vm.dshRewind.value.messageSeqs.isEmpty())
        assertEquals(2, unary.count { it.contains("\"commands/execute\"") })
    }

    @Test
    fun `rewind restores ordered images only for the open conversation`() = checkRewindImages()

    @Test
    fun `rewind keeps readable images and names failed images and omitted files`() = checkRewindImages(
        extraContent = """,{"type":"image","attachment":{"attachmentId":"missing","name":"missing.png"}},
            {"type":"image","attachment":{"attachmentId":"transport","name":"transport.png"}},
            {"type":"image","attachment":{"attachmentId":"invalid","name":"invalid.png"}},
            {"type":"image","attachment":{"attachmentId":"slow","name":"slow.png"}},
            {"type":"image","attachment":{"name":"no-id.png"}},
            {"type":"file","attachment":{"attachmentId":"document","mediaType":"application/pdf","name":"notes.pdf"}}""",
        notices = listOf("missing.png", "transport.png", "invalid.png", "slow.png", "no-id.png", "notes.pdf"),
    )

    @Test
    fun `image only rewind replaces unsent text with empty text`() = checkRewindImages(text = "")

    private fun checkRewindImages(
        text: String = "pictures",
        extraContent: String = "",
        notices: List<String> = emptyList(),
    ) = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val drafts = draftRepository()
        val harness = dshHarness(mux, unary,
            commandList = """[{"name":"rewind","description":"Rewind"}]""",
            executeCommand = { """{"commandId":"rewind","result":{"kind":"success","sourceEventSeq":2}}""" },
            readAttachment = { attachmentId ->
                when (attachmentId) {
                    "missing" -> return@dshHarness "null"
                    "transport" -> throw java.io.IOException("attachment transport failed")
                    "slow" -> kotlinx.coroutines.delay(16_000)
                }
                val data = if (attachmentId == "second") "Yg==" else "YQ=="
                """{"attachment":{"attachmentId":"$attachmentId","mediaType":"image/png","bytes":1,"width":1,"height":1},"data":"${if (attachmentId == "invalid") "%%%" else data}"}"""
            })
        val vm = newViewModel(harness.client, harness.manager, drafts = drafts)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent()
        pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        vm.dshSkills.first { it.commands.any { command -> command.name == "rewind" } }
        runCurrent()
        val reducer = harness.manager.reducer(SERVER_ID)
        reducer.applyFollowSnapshot(SESSION_ID, dev.wuxie233.codecarry.data.dsh.DshFollowFrame.Snapshot(
            header = null, cursor = 1, records = emptyList(), hasMore = false,
        ))
        reducer.mergeHistory(SESSION_ID, listOf(rewindUser(1).copy(data = json.parseToJsonElement(
            """{"source":{"kind":"user"},"content":[${if (text.isEmpty()) "" else """{"type":"text","text":"$text"},"""}{"type":"image","attachment":{"attachmentId":"second","mediaType":"image/png","name":"second.png"}}$extraContent,{"type":"image","attachment":{"attachmentId":"first","mediaType":"image/png","name":"first.png"}}]}"""
        ))))
        runCurrent()
        vm.updateDraftText("unsent")
        vm.addDraftAttachment("content://old")
        vm.rewindDshMessage(vm.uiState.value.messages.single().message.id)
        reducer.mergeHistory(SESSION_ID, listOf(rewindMarker(2, 1, 1)))
        runCurrent()
        vm.draftText.first { it == text }
        vm.dshRewind.first { !it.inFlight }
        runCurrent()
        assertEquals(listOf("data:image/png;base64,Yg==", "data:image/png;base64,YQ=="), vm.draftAttachmentUris.value)
        assertTrue(vm.uiState.value.messages.isEmpty())
        notices.forEach { name -> assertTrue("Missing notice for $name: ${vm.uiState.value.error}", vm.uiState.value.error.orEmpty().contains(name)) }
        if (notices.isEmpty()) assertNull(vm.uiState.value.error)
        vm.updateDraftText("edited pictures")
        vm.selectAgent("test")
        val reopened = newViewModel(harness.client, harness.manager, drafts = drafts)
        assertEquals("edited pictures", reopened.draftText.value)
        assertTrue(reopened.draftAttachmentUris.value.isEmpty())
    }

    @Test fun `unmatched rewind keeps conversation and draft`() = checkRewindFailure { "null" }
    @Test fun `rejected rewind keeps conversation and draft`() = checkRewindFailure {
        """{"commandId":"cmd","result":{"kind":"error","text":"rejected"}}"""
    }
    @Test fun `uncompleted rewind times out without changing conversation or draft`() = checkRewindFailure {
        kotlinx.coroutines.awaitCancellation()
    }
    @Test fun `success without history marker does not change conversation or draft`() = checkRewindFailure {
        """{"commandId":"cmd","result":{"kind":"success","sourceEventSeq":4}}"""
    }

    private fun checkRewindFailure(execute: suspend () -> String) = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val commandStarted = CompletableDeferred<Unit>()
        val harness = dshHarness(mux, unary,
            commandList = """[{"name":"rewind","description":"Rewind"}]""", executeCommand = {
                commandStarted.complete(Unit)
                execute()
            })
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent(); pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        vm.dshSkills.first { it.commands.any { command -> command.name == "rewind" } }
        runCurrent()
        val reducer = harness.manager.reducer(SERVER_ID)
        reducer.applyFollowSnapshot(SESSION_ID, dev.wuxie233.codecarry.data.dsh.DshFollowFrame.Snapshot(
            header = null, cursor = 3, records = emptyList(), hasMore = false,
        ))
        reducer.mergeHistory(SESSION_ID, listOf(rewindUser(1), rewindUser(2)))
        runCurrent()
        vm.updateDraftText("keep this")
        val before = vm.uiState.value.messages
        vm.rewindDshMessage(before.last().message.id)
        // Ktor's MockEngine runs on IO: wait for dispatch before advancing the
        // virtual deadline, otherwise the timeout can precede the fake request.
        commandStarted.await()
        advanceUntilIdle()
        assertEquals(before, vm.uiState.value.messages)
        assertEquals("keep this", vm.draftText.value)
        assertFalse(vm.dshRewind.value.inFlight)
        assertEquals(org.robolectric.RuntimeEnvironment.getApplication().getString(
            dev.wuxie233.codecarry.R.string.dsh_rewind_failed), vm.uiState.value.error)
        assertEquals(1, unary.count { it.contains("\"commands/execute\"") })
        reducer.mergeHistory(SESSION_ID, listOf(rewindMarker(4, 2, 2)))
        runCurrent()
        assertEquals(setOf(1L), vm.dshRewind.value.messageSeqs)
        assertEquals(before, vm.uiState.value.messages)
        assertEquals("keep this", vm.draftText.value)
    }

    @Test fun `archived compact reports recovery action and hides candidate until restored`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent(); pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        vm.dshSkills.first { it.commands.isNotEmpty() }
        runCurrent()
        assertTrue(vm.dshSkills.value.matchesCompact("co"))
        val reducer = harness.manager.reducer(SERVER_ID)
        reducer.applyWorkspaceFrame(dev.wuxie233.codecarry.data.dsh.DshWorkspaceFrame.Archived(listOf(SESSION_ID)))
        runCurrent()
        vm.compactDshSession()
        runCurrent()
        assertEquals("Archived. Restore the session first.", vm.uiState.value.error)
        assertFalse(vm.dshSkills.value.matchesCompact("co"))
        assertTrue(vm.dshSkills.value.matching("").isNotEmpty())
        assertFalse(unary.any { it.contains("commands/execute") })
        reducer.applyWorkspaceFrame(dev.wuxie233.codecarry.data.dsh.DshWorkspaceFrame.Archived(emptyList()))
        runCurrent()
        assertTrue(vm.dshSkills.value.matchesCompact("co"))
    }

    @Test fun `rewind entry follows capability archive origin and current model surface`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary,
            commandList = """[{"name":"rewind","description":"Rewind"}]""")
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent(); pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        vm.dshSkills.first { it.commands.any { command -> command.name == "rewind" } }
        runCurrent()
        val reducer = harness.manager.reducer(SERVER_ID)
        reducer.applyFollowSnapshot(SESSION_ID, dev.wuxie233.codecarry.data.dsh.DshFollowFrame.Snapshot(
            header = null, cursor = 3, records = emptyList(), hasMore = false,
        ))
        reducer.mergeHistory(SESSION_ID, listOf(rewindUser(1), rewindUser(2), rewindUser(3)))
        runCurrent()
        assertEquals(setOf(1L, 2L, 3L), vm.dshRewind.value.messageSeqs)
        // A compaction replacement shadows old human prompts even though their log is retained.
        reducer.mergeHistory(SESSION_ID, listOf(rewindMarker(4, 1, 2).copy(
            data = json.parseToJsonElement("""{"source":{"kind":"compaction"},"content":[]}"""))))
        runCurrent()
        assertEquals(setOf(3L), vm.dshRewind.value.messageSeqs)
        reducer.applyWorkspaceFrame(dev.wuxie233.codecarry.data.dsh.DshWorkspaceFrame.Archived(listOf(SESSION_ID)))
        runCurrent()
        assertTrue(vm.dshRewind.value.messageSeqs.isEmpty())
        reducer.applyWorkspaceFrame(dev.wuxie233.codecarry.data.dsh.DshWorkspaceFrame.Archived(emptyList()))
        runCurrent()
        assertEquals(setOf(3L), vm.dshRewind.value.messageSeqs)
        reducer.applySessionList(listOf(dev.wuxie233.codecarry.data.dsh.DshSessionSummary(
            SESSION_ID, 5, false, false, origin = "subagent", parentSessionId = "parent")))
        runCurrent()
        assertTrue(vm.dshRewind.value.messageSeqs.isEmpty())
        vm.rewindDshMessage(vm.uiState.value.messages.last().message.id)
        assertFalse(unary.any { it.contains("commands/execute") })
    }

    @Test fun `missing rewind command leaves all loaded prompts without an entry`() = runTest(dispatcher) {
        val mux = FakeDownlink()
        val unary = CopyOnWriteArrayList<String>()
        val harness = dshHarness(mux, unary)
        val vm = newViewModel(harness.client, harness.manager)
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        harness.manager.connect(SERVER_ID, DshConnection.from("http://127.0.0.1:3080", token = "launch-token"))
        runCurrent(); pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        vm.dshSkills.first { it.commands.isNotEmpty() }
        runCurrent()
        val reducer = harness.manager.reducer(SERVER_ID)
        reducer.applyFollowSnapshot(SESSION_ID, dev.wuxie233.codecarry.data.dsh.DshFollowFrame.Snapshot(
            header = null, cursor = 1, records = emptyList(), hasMore = false))
        reducer.mergeHistory(SESSION_ID, listOf(rewindUser(1)))
        runCurrent()
        assertTrue(vm.dshRewind.value.messageSeqs.isEmpty())
        vm.rewindDshMessage(vm.uiState.value.messages.single().message.id)
        runCurrent()
        assertFalse(unary.any { it.contains("commands/execute") })
    }

    private fun rewindUser(seq: Long) = dev.wuxie233.codecarry.data.dsh.DshSessionEvent(
        type = "user/message", seq = seq, time = seq,
        data = json.parseToJsonElement("""{"source":{"kind":"user"},"content":[{"type":"text","text":"prompt $seq"}]}"""),
        surfaceOp = kotlinx.serialization.json.JsonPrimitive("append"),
    )

    private fun rewindMarker(seq: Long, start: Long, end: Long) = dev.wuxie233.codecarry.data.dsh.DshSessionEvent(
        type = "user/message", seq = seq, time = seq,
        data = json.parseToJsonElement("""{"source":{"kind":"dsh-rewind"},"content":[{"type":"text","text":"(empty message)"}]}"""),
        surfaceOp = json.parseToJsonElement("""{"op":"replace","startSeq":$start,"endSeq":$end}"""),
    )

    private fun dshHarness(
        mux: FakeDownlink,
        unary: MutableList<String>,
        beforeSessionList: suspend () -> Unit = {},
        beforePresetList: suspend () -> Unit = {},
        beforeSkillList: suspend () -> Unit = {},
        beforePrompt: suspend () -> Unit = {},
        skillList: String = """[{"name":"grill","description":"Discuss","modelInvocable":true}]""",
        beforePresetSelect: suspend () -> Unit = {},
        onCommandExecute: () -> Unit = {},
        commandList: String = """[{"name":"compact","description":"Compact history"}]""",
        executeCommand: suspend () -> String = { """{"commandId":"compact","result":{"kind":"success"}}""" },
        readAttachment: suspend (String) -> String = { error("attachment unavailable") },
        presetSelectResult: String = """{"ok":true,"value":"standard"}""",
        openMux: () -> DshDownlink = { mux },
        listItem: String = """{"sessionId":"$SESSION_ID","updatedAt":2,"running":false,"blank":false,"cwd":"/root/CODE/Minecraft"}""",
    ): DshHarness {
        var nextStream = 0
        val streamIds = listOf("st-0", "st-1", "st-2", "st-3", "st-4", "st-5")
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/" -> respond(
                    content = "",
                    status = HttpStatusCode.SeeOther,
                    headers = headersOf(
                        HttpHeaders.SetCookie,
                        "dsh-auth-zz=v1.body; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict",
                    ),
                )
                "/api/session/list" -> {
                    unary += (request.body as TextContent).text
                    beforeSessionList()
                    respond(
                        content = ByteReadChannel(
                            """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"items":[$listItem]}}}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/commands/list" -> {
                    unary += (request.body as TextContent).text
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":$commandList}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/commands/execute" -> {
                    unary += (request.body as TextContent).text
                    onCommandExecute()
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":${executeCommand()}}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/session/attachment" -> {
                    val body = (request.body as TextContent).text
                    unary += body
                    val args = json.parseToJsonElement(body).jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
                    assertEquals(SESSION_ID, args["sessionId"]!!.jsonPrimitive.content)
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":${readAttachment(args["attachmentId"]!!.jsonPrimitive.content)}}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/skills/list" -> {
                    unary += (request.body as TextContent).text
                    beforeSkillList()
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"skills":$skillList}}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/agentPresets/list" -> {
                    unary += (request.body as TextContent).text
                    beforePresetList()
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"presets":[{"id":"standard","isDefault":true}]}}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/agentPresets/select" -> {
                    unary += (request.body as TextContent).text
                    beforePresetSelect()
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":$presetSelectResult}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/session/modelCatalog" -> {
                    unary += (request.body as TextContent).text
                    respond(
                        content = ByteReadChannel(
                            """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"default":{"provider":"p","model":"m"},"groups":[]}}}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/session/prompt" -> {
                    unary += (request.body as TextContent).text
                    beforePrompt()
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"accepted":true}}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/subagents/prompt" -> {
                    unary += (request.body as TextContent).text
                    respond(
                        content = """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"messageId":"child-message"}}}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                "/api/session/page" -> {
                    unary += (request.body as TextContent).text
                    respond(
                        content = ByteReadChannel(
                            """{"type":"server-response","rpcId":"fixed","result":{"ok":true,"value":{"records":[],"hasMore":false}}}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error(request.url.encodedPath)
            }
        }
        val http = HttpClient(engine) {
            followRedirects = false
            install(ContentNegotiation) { json(json) }
        }
        val client = DshApiClient(
            http,
            json,
            mintRpcId = { "fixed" },
            downlinkFactory = object : DshDownlinkFactory {
                override suspend fun openMux(connection: DshConnection): DshDownlink = openMux()
            },
        )
        val manager = DshConnectionManager(
            client = client,
            scope = testScope.backgroundScope,
            mintStreamId = { streamIds[nextStream++ % streamIds.size] },
        )
        return DshHarness(client, manager)
    }

    private fun newViewModel(dshApi: DshApiClient, manager: DshConnectionManager, sessionId: String = SESSION_ID, drafts: DraftRepository = draftRepository(), serverId: String = SERVER_ID): ChatViewModel {
        return ChatViewModel(
            appContext = org.robolectric.RuntimeEnvironment.getApplication(),
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "serverUrl" to "http://127.0.0.1:3080",
                    "username" to "",
                    "password" to "",
                    "serverName" to "DSH",
                    "serverId" to serverId,
                    "sessionId" to sessionId,
                    "directory" to "/root/CODE/Minecraft",
                    "serverType" to ServerType.DSH.name,
                    "token" to "launch-token",
                ),
            ),
            eventReducer = EventReducer(),
            api = unusedOpenCodeApi(),
            json = json,
            draftRepository = drafts,
            sessionListPreferencesRepository = sessionListPreferencesRepository(),
            settingsRepository = settingsRepository(),
            dshApi = dshApi,
            dshConnectionManager = manager,
            foregroundResumeDispatcher = dev.wuxie233.codecarry.service.ForegroundResumeDispatcher(),
        ).also(viewModels::add)
    }

    private fun unusedOpenCodeApi(): OpenCodeApi {
        val engine = MockEngine { error("opencode unused: ${it.url.encodedPath}") }
        return OpenCodeApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, json)
    }

    private fun draftRepository(): DraftRepository {
        val filesDir = tmpFolder.newFolder("drafts-${System.nanoTime()}")
        return DraftRepository(object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
        })
    }

    private fun sessionListPreferencesRepository(): SessionListPreferencesRepository =
        SessionListPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tmpFolder.newFile("session-list-${System.nanoTime()}.preferences_pb") },
            ),
        )

    private fun settingsRepository(): SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("settings-${System.nanoTime()}.preferences_pb") },
        ),
        object : ContextWrapper(null) {},
    )

    private fun pushBaselines(mux: FakeDownlink) {
        mux.incoming.trySend(item("st-0", """{"type":"ready","clientId":"client-1","host":{"home":"/root"}}"""))
        mux.incoming.trySend(item("st-1", """{"type":"baseline","value":{"projections":{}}}"""))
        mux.incoming.trySend(
            item("st-2", """{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":[]}}"""),
        )
    }

    private fun item(streamId: String, value: String): String =
        """{"type":"item","streamId":"$streamId","value":$value}"""

    private companion object {
        private const val SERVER_ID = "dsh-1"
        private const val SESSION_ID = "ses_minecraft"
        private const val PARENT_ID = "ses_parent"
    }
}
