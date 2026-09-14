package dev.wuxie233.codecarry.data.dsh

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.repository.EventReducer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Focused tests for the DSH unread producer (issue #31): DSH turn completion
 * with new readable output marks the conversation unread through the shared
 * read model, derived from DSH's own state — never OpenCode SSE assumptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshUnreadTrackerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    private val connection = DshConnection.from("http://127.0.0.1:3080", token = "launch-token")

    // ============ Pure decision helpers ============

    @Test
    fun `candidate requires main listed idle session`() {
        assertTrue(isDshUnreadCandidate(DshSessionSnapshot(sessionId = "s1", blank = false)))
        assertFalse(isDshUnreadCandidate(DshSessionSnapshot(sessionId = "s1", blank = true)))
        assertFalse(isDshUnreadCandidate(DshSessionSnapshot(sessionId = "s1", blank = false, running = true)))
        assertFalse(isDshUnreadCandidate(DshSessionSnapshot(sessionId = "s1", blank = false, parentSessionId = "p")))
        assertFalse(isDshUnreadCandidate(DshSessionSnapshot(sessionId = "s1", blank = false, origin = "subagent")))
    }

    @Test
    fun `probe decision follows anchor and event freshness`() {
        // Nothing folded: only a probe can answer.
        assertTrue(shouldProbeDshHistory(anchor = null, session = DshSessionSnapshot(sessionId = "s1")))
        // Never read here, but events exist: they already answer the question.
        assertFalse(
            shouldProbeDshHistory(
                anchor = null,
                session = DshSessionSnapshot(sessionId = "s1", events = listOf(event(5L))),
            ),
        )
        // Read before, no folded history in this process: probe.
        assertTrue(
            shouldProbeDshHistory(
                anchor = "m1",
                session = DshSessionSnapshot(sessionId = "s1", updatedAt = 10L, events = listOf(event(5L))),
            ),
        )
        // Read before, folded history reaches the session's last activity: no probe.
        assertFalse(
            shouldProbeDshHistory(
                anchor = "m1",
                session = DshSessionSnapshot(sessionId = "s1", updatedAt = 5L, events = listOf(event(5L))),
            ),
        )
        // Session moved past the newest event we know: probe.
        assertTrue(
            shouldProbeDshHistory(
                anchor = "m1",
                session = DshSessionSnapshot(sessionId = "s1", updatedAt = 99L, events = listOf(event(5L))),
            ),
        )
    }

    @Test
    fun `tool-only output never counts as readable`() {
        val events = listOf(
            DshSessionEvent(
                type = "assistant/message",
                seq = 21L,
                time = 21L,
                data = json.parseToJsonElement(
                    """{"message":{"id":"toolonly","content":[{"type":"tool-call","id":"c1","name":"bash"}]}}""",
                ),
            ),
        )
        assertTrue(dshReadableOutputIds("s1", events).isEmpty())
    }

    // ============ Live tracker flows (real manager + fake mux) ============

    @Test
    fun `live completion with readable output marks unread via a bounded probe`() = runTest {
        val mux = FakeDownlink()
        val harness = harness(mux)
        harness.manager.connect(SERVER_ID, connection)
        // The three supervision opens land after the (real-IO) cookie exchange.
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        val events = pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.tracker.start(backgroundScope)
        runCurrent()

        emitAdded(mux, events, """{"sessionId":"$SESSION_ID","updatedAt":1,"running":true,"blank":false,"cwd":"/tmp"}""")
        runCurrent()
        emitStatus(mux, events, SESSION_ID, running = false)
        runCurrent()
        pumpSettle()

        // The tracker opened a one-shot session/follow probe with a bounded window.
        val probeOpen = mux.sent.last { it.contains("\"session/follow\"") }
        assertTrue(probeOpen.contains("\"maxMessages\""))
        respondSnapshot(mux, probeOpen, userRecord(10L), assistantRecord("msg_a", 21L, "turn finished"))

        assertTrue(SESSION_ID in harness.repo.unreadConversationIds(SERVER_ID).first { SESSION_ID in it })
        // Server isolation: another server's set never sees this mark.
        assertFalse(SESSION_ID in harness.repo.unreadConversationIds("dsh-other").first())
    }

    @Test
    fun `live completion with tool-only output does not fabricate unread`() = runTest {
        val mux = FakeDownlink()
        val harness = harness(mux)
        harness.manager.connect(SERVER_ID, connection)
        // The three supervision opens land after the (real-IO) cookie exchange.
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        val events = pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.tracker.start(backgroundScope)
        runCurrent()

        emitAdded(mux, events, """{"sessionId":"$SESSION_ID","updatedAt":1,"running":true,"blank":false,"cwd":"/tmp"}""")
        runCurrent()
        emitStatus(mux, events, SESSION_ID, running = false)
        runCurrent()
        pumpSettle()

        val probeOpen = mux.sent.last { it.contains("\"session/follow\"") }
        respondSnapshot(mux, probeOpen, userRecord(10L), assistantToolOnlyRecord(21L))
        flushRepo(harness.repo)

        assertFalse(SESSION_ID in harness.repo.unreadConversationIds(SERVER_ID).first())
    }

    @Test
    fun `read anchor at the probed latest reply keeps the conversation read`() = runTest {
        val mux = FakeDownlink()
        val harness = harness(mux)
        harness.manager.connect(SERVER_ID, connection)
        // The three supervision opens land after the (real-IO) cookie exchange.
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        val events = pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        // The user already read through the newest reply.
        harness.repo.setReadAnchor(SERVER_ID, SESSION_ID, "msg_a-21")
        harness.tracker.start(backgroundScope)
        runCurrent()

        // Restart-style discovery: the idle session arrives from a list merge.
        emitAdded(mux, events, """{"sessionId":"$SESSION_ID","updatedAt":21,"running":false,"blank":false,"cwd":"/tmp"}""")
        runCurrent()
        pumpSettle()

        val probeOpen = mux.sent.last { it.contains("\"session/follow\"") }
        respondSnapshot(mux, probeOpen, userRecord(10L), assistantRecord("msg_a", 21L, "seen already"))
        flushRepo(harness.repo)

        assertFalse(SESSION_ID in harness.repo.unreadConversationIds(SERVER_ID).first())
    }

    @Test
    fun `probe failure fails closed and never clears a live mark`() = runTest {
        val mux = FakeDownlink()
        val harness = harness(mux)
        harness.manager.connect(SERVER_ID, connection)
        // The three supervision opens land after the (real-IO) cookie exchange.
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        val events = pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.repo.setReadAnchor(SERVER_ID, SESSION_ID, "msg_old-5")
        harness.repo.markConversationUnread(SERVER_ID, SESSION_ID)
        harness.tracker.start(backgroundScope)
        runCurrent()

        emitAdded(mux, events, """{"sessionId":"$SESSION_ID","updatedAt":21,"running":false,"blank":false,"cwd":"/tmp"}""")
        runCurrent()
        pumpSettle()

        val probeOpen = mux.sent.last { it.contains("\"session/follow\"") }
        val probeId = streamIdOf(probeOpen)
        mux.incoming.trySend("""{"type":"error","streamId":"$probeId","error":{"code":"boom","message":"probe failed"}}""")
        runCurrent()
        advanceUntilIdle()

        // Failed probe: the pre-existing unread mark survives.
        assertTrue(SESSION_ID in harness.repo.unreadConversationIds(SERVER_ID).first())
    }

    @Test
    fun `visible chat is never marked and evaluates after the screen closes`() = runTest {
        val mux = FakeDownlink()
        val harness = harness(mux)
        harness.manager.connect(SERVER_ID, connection)
        // The three supervision opens land after the (real-IO) cookie exchange.
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        val events = pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.tracker.start(backgroundScope)
        runCurrent()

        // The chat screen is visible while the turn completes.
        harness.reducer.setVisibleSession(SERVER_ID, SESSION_ID)
        emitAdded(mux, events, """{"sessionId":"$SESSION_ID","updatedAt":1,"running":true,"blank":false,"cwd":"/tmp"}""")
        runCurrent()
        emitStatus(mux, events, SESSION_ID, running = false)
        runCurrent()
        advanceUntilIdle()

        assertFalse(mux.sent.any { it.contains("\"session/follow\"") })
        assertFalse(SESSION_ID in harness.repo.unreadConversationIds(SERVER_ID).first())

        // The user leaves the chat; the foreground recompute finishes the pass.
        harness.reducer.clearVisibleSession(SERVER_ID, SESSION_ID)
        harness.tracker.recomputeNow()
        runCurrent()
        pumpSettle()

        val probeOpen = mux.sent.last { it.contains("\"session/follow\"") }
        respondSnapshot(mux, probeOpen, assistantRecord("msg_a", 21L, "finished while watched"))

        assertTrue(SESSION_ID in harness.repo.unreadConversationIds(SERVER_ID).first { SESSION_ID in it })
    }

    @Test
    fun `fresh folded history evaluates without a probe`() = runTest {
        val mux = FakeDownlink()
        val harness = harness(mux)
        harness.manager.connect(SERVER_ID, connection)
        // The three supervision opens land after the (real-IO) cookie exchange.
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        val events = pushBaselines(mux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        harness.repo.setReadAnchor(SERVER_ID, SESSION_ID, "msg_a-10")
        harness.tracker.start(backgroundScope)
        runCurrent()

        // Folded history first (the open chat's follow stream keeps it current),
        // then the list merge supplies identity and the update stamp.
        val dshReducer = harness.manager.reducer(SERVER_ID)
        dshReducer.mergeHistory(
            SESSION_ID,
            listOf(
                eventRecord(10L, """{"message":{"id":"msg_a","content":[{"type":"text","text":"earlier"}]}}"""),
                eventRecord(20L, """{"message":{"id":"msg_b","content":[{"type":"text","text":"new reply"}]}}"""),
            ),
        )
        runCurrent()
        dshReducer.applySessionList(
            listOf(DshSessionSummary(sessionId = SESSION_ID, updatedAt = 20L, running = false, blank = false)),
        )
        runCurrent()
        advanceUntilIdle()

        // Unread derived purely from folded events; no probe follow was opened.
        assertTrue(SESSION_ID in harness.repo.unreadConversationIds(SERVER_ID).first { SESSION_ID in it })
        assertFalse(mux.sent.any { it.contains("\"session/follow\"") })
    }

    @Test
    fun `reconnect resets the generation memo and recomputes idempotently`() = runTest {
        val harness = harness()
        harness.manager.connect(SERVER_ID, connection)
        harness.manager.states.first { it[SERVER_ID]?.muxOpen == true }
        val firstMux = harness.sockets.first()
        pushBaselines(firstMux)
        harness.manager.states.first { it[SERVER_ID]?.isReady == true }
        // The user read through the newest reply before the connection dropped.
        harness.repo.setReadAnchor(SERVER_ID, SESSION_ID, "msg_a-21")
        harness.tracker.start(backgroundScope)
        runCurrent()

        firstMux.incoming.close()
        harness.manager.states.first { it[SERVER_ID]?.status == DshGenerationStatus.Failed }
        harness.manager.states.first { state ->
            state[SERVER_ID]?.muxOpen == true && state[SERVER_ID]!!.generation > 1L
        }
        val nextMux = harness.sockets.last()
        val events = pushBaselines(nextMux)
        harness.manager.states.first { state ->
            state[SERVER_ID]?.isReady == true && state[SERVER_ID]!!.generation > 1L
        }
        emitAdded(nextMux, events, """{"sessionId":"$SESSION_ID","updatedAt":21,"running":false,"blank":false,"cwd":"/tmp"}""")
        runCurrent()
        pumpSettle()

        // The reconnect re-evaluation probes once for the anchored session.
        val probeOpen = nextMux.sent.last { it.contains("\"session/follow\"") }
        respondSnapshot(nextMux, probeOpen, assistantRecord("msg_a", 21L, "same snapshot"))
        flushRepo(harness.repo)

        // Same snapshot → same result: nothing beyond the anchor stays unread.
        assertFalse(SESSION_ID in harness.repo.unreadConversationIds(SERVER_ID).first())
    }

    @Test
    fun `duplicate session ids on two servers never share unread marks`() = runTest {
        val harness = harness()
        harness.manager.connect("dsh-a", connection)
        harness.manager.connect("dsh-b", connection)
        harness.manager.states.first { it["dsh-a"]?.muxOpen == true && it["dsh-b"]?.muxOpen == true }
        val muxA = harness.sockets[0]
        val muxB = harness.sockets[1]
        val eventsA = pushBaselines(muxA)
        val eventsB = pushBaselines(muxB)
        harness.manager.states.first { it["dsh-a"]?.isReady == true && it["dsh-b"]?.isReady == true }
        harness.tracker.start(backgroundScope)
        runCurrent()

        for (mux in listOf(muxA to eventsA, muxB to eventsB)) {
            emitAdded(mux.first, mux.second, """{"sessionId":"$SESSION_ID","updatedAt":1,"running":true,"blank":false,"cwd":"/tmp"}""")
        }
        runCurrent()
        for (mux in listOf(muxA to eventsA, muxB to eventsB)) {
            emitStatus(mux.first, mux.second, SESSION_ID, running = false)
        }
        runCurrent()
        pumpSettle()

        for (mux in listOf(muxA, muxB)) {
            val probeOpen = mux.sent.last { it.contains("\"session/follow\"") }
            respondSnapshot(mux, probeOpen, assistantRecord("msg_a", 21L, "reply"))
        }

        assertTrue(SESSION_ID in harness.repo.unreadConversationIds("dsh-a").first { SESSION_ID in it })
        assertTrue(SESSION_ID in harness.repo.unreadConversationIds("dsh-b").first { SESSION_ID in it })

        // Reading on one server never clears the other server's mark.
        harness.repo.markConversationRead("dsh-a", SESSION_ID)
        assertFalse(SESSION_ID in harness.repo.unreadConversationIds("dsh-a").first())
        assertTrue(SESSION_ID in harness.repo.unreadConversationIds("dsh-b").first())
    }

    // ============ Harness ============

    private class TrackerHarness(
        val manager: DshConnectionManager,
        val repo: SessionListPreferencesRepository,
        val reducer: EventReducer,
        val tracker: DshUnreadTracker,
        val sockets: MutableList<FakeDownlink>,
    )

    private fun TestScope.harness(shared: FakeDownlink? = null): TrackerHarness {
        val sockets = mutableListOf<FakeDownlink>()
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
                override suspend fun openMux(connection: DshConnection): DshDownlink =
                    shared ?: FakeDownlink().also { sockets += it }
            },
        )
        var nextStream = 0
        val manager = DshConnectionManager(
            client = client,
            scope = backgroundScope,
            mintStreamId = { "st-${nextStream++}" },
        )
        val repo = SessionListPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tmpFolder.newFile("unread-${System.nanoTime()}.preferences_pb") },
            ),
        )
        val reducer = EventReducer()
        val tracker = DshUnreadTracker(
            connectionManager = manager,
            preferences = repo,
            eventReducer = reducer,
        )
        return TrackerHarness(manager, repo, reducer, tracker, sockets)
    }

    private fun event(time: Long): DshSessionEvent =
        DshSessionEvent(type = "assistant/message", seq = time, time = time)

    private fun eventRecord(seq: Long, data: String): DshSessionEvent =
        DshSessionEvent(
            type = "assistant/message",
            seq = seq,
            time = seq,
            data = json.parseToJsonElement(data),
        )

    private fun userRecord(seq: Long): String =
        """{"type":"event","event":{"type":"user/message","seq":$seq,"time":$seq,"data":{"id":"u1","content":[{"type":"text","text":"hello"}],"source":{"kind":"user"}},"surfaceOp":"append"}}"""

    private fun assistantRecord(id: String, seq: Long, text: String): String =
        """{"type":"event","event":{"type":"assistant/message","seq":$seq,"time":$seq,"data":{"message":{"id":"$id","content":[{"type":"text","text":"$text"}]}}}}"""

    private fun assistantToolOnlyRecord(seq: Long): String =
        """{"type":"event","event":{"type":"assistant/message","seq":$seq,"time":$seq,"data":{"message":{"id":"toolsonly","content":[{"type":"tool-call","id":"c1","name":"bash"}]}}}}"""

    /** Stream ids of the latest generation's three supervision opens, in order. */
    private fun supervisionStreams(mux: FakeDownlink): List<String> {
        val opens = mux.sent.filter { it.contains("\"type\":\"open\"") }.map(::streamIdOf)
        return opens.takeLast(3)
    }

    private fun emitAdded(mux: FakeDownlink, events: String, summary: String) {
        mux.incoming.trySend(item(events, """{"type":"emit","event":"api-session/added","args":[$summary]}"""))
    }

    private fun emitStatus(mux: FakeDownlink, events: String, sessionId: String, running: Boolean) {
        mux.incoming.trySend(
            item(events, """{"type":"emit","event":"api-session/status","args":["$sessionId","$running"]}"""),
        )
    }

    private fun respondSnapshot(mux: FakeDownlink, probeOpen: String, vararg records: String) {
        val probeId = streamIdOf(probeOpen)
        mux.incoming.trySend(
            item(
                probeId,
                """{"type":"snapshot","header":null,"cursor":21,"records":[${records.joinToString(",")}],"hasMore":false}""",
            ),
        )
    }

    /** Returns the `$events` stream id for later emits. */
    private fun pushBaselines(mux: FakeDownlink): String {
        val (events, control, workspace) = supervisionStreams(mux)
        mux.incoming.trySend(item(events, """{"type":"ready","clientId":"client-1","host":{"home":"/root"}}"""))
        mux.incoming.trySend(item(control, """{"type":"baseline","value":{"queues":{},"jobs":{},"projections":{}}}"""))
        mux.incoming.trySend(
            item(workspace, """{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"hiddenWorkspaceIds":[]}}"""),
        )
        return events
    }

    private fun item(streamId: String, value: String): String =
        """{"type":"item","streamId":"$streamId","value":$value}"""

    /**
     * Advance virtual time just past the tracker's settle delay so the probe
     * opens, but never past the probe's 5s timeout — a full advanceUntilIdle
     * would cancel the probe before the test can answer its snapshot.
     */
    private fun kotlinx.coroutines.test.TestScope.pumpSettle() {
        advanceTimeBy(DSH_UNREAD_SETTLE_MS + 100)
        runCurrent()
    }

    /**
     * Serialize against any pending tracker write: DataStore edits run through
     * one FIFO actor, so completing a later edit proves earlier ones are durable.
     */
    private suspend fun flushRepo(repo: SessionListPreferencesRepository) {
        val key = "ses-flush-${flushCounter++}"
        repo.setReadAnchor("srv-flush", key, "m")
        check(repo.readAnchor("srv-flush", key).first { it == "m" } == "m")
    }

    private var flushCounter = 0

    private fun streamIdOf(sent: String): String =
        json.parseToJsonElement(sent).jsonObject.getValue("streamId").jsonPrimitive.content

    private companion object {
        private const val SERVER_ID = "dsh-1"
        private const val SESSION_ID = "ses_shared"
    }
}
