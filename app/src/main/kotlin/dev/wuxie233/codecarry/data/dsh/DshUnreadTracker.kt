package dev.wuxie233.codecarry.data.dsh

import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.preferences.hasUnreadReplyBeyondReadAnchor
import dev.wuxie233.codecarry.data.preferences.readableAssistantOutputIds
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.domain.model.Part
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Delay before evaluating a session, so a live `api-session/status` frame cannot
 *  outrun the follow-stream items that carry the completed reply. */
internal const val DSH_UNREAD_SETTLE_MS = 300L

/** Bounded window for the one-shot history probe. */
internal const val DSH_UNREAD_PROBE_MAX_MESSAGES = 50
internal const val DSH_UNREAD_PROBE_TIMEOUT_MS = 5_000L

/**
 * A session participates in DSH unread evaluation when it is a main (non-child,
 * non-subagent-origin) session, has list identity (`blank = false`), and is not
 * mid-turn. Blank sessions are not shown in the generic session list; children
 * never own an unread mark of their own.
 */
internal fun isDshUnreadCandidate(session: DshSessionSnapshot): Boolean =
    !session.blank &&
        !session.running &&
        session.parentSessionId == null &&
        session.origin != "subagent"

/**
 * Decide whether the folded events in the reducer are trustworthy for the
 * cursor comparison, or whether a bounded one-shot history probe is needed.
 *
 * With no folded events a probe is the only way to learn the session's output.
 * With folded events and no anchor (never read here) the events already answer
 * the question — any readable output is unread. With an anchor, probe only when
 * `api-session/activity` moved the session past the newest event we know (the
 * completion happened while no follow stream was accumulating).
 */
internal fun shouldProbeDshHistory(anchor: String?, session: DshSessionSnapshot): Boolean {
    if (session.events.isEmpty()) return true
    if (anchor == null) return false
    val newestKnownEventTime = session.events.maxOfOrNull { it.time } ?: 0L
    return session.updatedAt > newestKnownEventTime
}

/** Fold DSH session events and keep only assistant messages with readable output, in order. */
internal fun dshReadableOutputIds(sessionId: String, events: List<DshSessionEvent>): List<String> {
    val folded = foldDshHistory(sessionId, events)
    val partsById = HashMap<String, List<Part>>(folded.size)
    folded.forEach { partsById[it.info.id] = it.parts }
    return readableAssistantOutputIds(folded.map { it.info }) { id -> partsById[id].orEmpty() }
}

/**
 * DSH producer for the shared read model (issue #31): a DSH turn completion
 * with new readable assistant output marks the conversation unread through the
 * shared `SessionListPreferencesRepository`, keyed by serverId + sessionId.
 *
 * Everything is derived from DSH's own state — the `$events` stream
 * (`api-session/status` running transitions, `api-session/activity`),
 * `session/list` merges, and per-session folded history — never from OpenCode
 * SSE assumptions. Coverage:
 * - Live completion: a session observed running in this generation that goes
 *   idle is evaluated after a short settle delay (follow items may land after
 *   the status frame on separate logical streams).
 * - Reconnect: `DshEventReducer.resetGeneration` starts a new generation, and
 *   the per-generation memo re-evaluates every session once.
 * - Restart: the tracker is cheap to recreate; un-memoized sessions are
 *   re-evaluated on the next state emission or [recomputeNow].
 *
 * Unread derivation reuses the U1 helpers (`readableAssistantOutputIds`,
 * `hasUnreadReplyBeyondReadAnchor`): unread iff readable assistant output
 * exists strictly beyond the persisted read anchor. Tool-only and error-only
 * activity never fabricates unread. Sessions never seen on this device (no
 * anchor, no folded history, no live completion observed) are skipped so old
 * pre-install sessions are not mass-marked. A chat whose screen is currently
 * visible (`EventReducer.isSessionVisible`) never gets marked; its anchor
 * advances through the shared ChatViewModel read-advancement path instead.
 */
class DshUnreadTracker(
    private val connectionManager: DshConnectionManager,
    private val preferences: SessionListPreferencesRepository,
    private val eventReducer: EventReducer,
    private val probeHistory: suspend (serverId: String, sessionId: String) -> List<DshSessionEvent>? =
        { serverId, sessionId -> probeFollowHistory(connectionManager, serverId, sessionId) },
) {
    private val lock = Any()
    private var rootJob: Job? = null
    private var trackingScope: CoroutineScope? = null
    private val serverJobs = mutableMapOf<String, Job>()

    /** Last `updatedAt` evaluated per `generation\u0000serverId\u0000sessionId`. */
    private val evaluatedAt = ConcurrentHashMap<String, Long>()

    /** Sessions observed running in the current generation (`generation\u0000serverId\u0000sessionId`). */
    private val observedRunning = ConcurrentHashMap.newKeySet<String>()

    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (rootJob?.isActive == true) return
            rootJob = scope.launch {
                // Children inherit the caller's dispatcher (IO for the service,
                // the test scheduler in tests) so settle delays follow that clock.
                val tracking = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
                synchronized(lock) { trackingScope = tracking }
                try {
                    connectionManager.states.collect { states ->
                        states.forEach { (serverId, state) ->
                            if (state.isReady) trackServer(tracking, serverId)
                        }
                    }
                } finally {
                    tracking.cancel()
                    synchronized(lock) {
                        if (trackingScope === tracking) trackingScope = null
                        serverJobs.clear()
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            rootJob?.cancel()
            rootJob = null
            trackingScope?.cancel()
            trackingScope = null
            serverJobs.values.forEach(Job::cancel)
            serverJobs.clear()
        }
    }

    /**
     * Re-run evaluation for the current state of every attached server. Cheap:
     * memoized sessions are skipped, so this only re-evaluates sessions whose
     * last pass was skipped (for example while their chat screen was visible).
     */
    fun recomputeNow() {
        val scope = synchronized(lock) { trackingScope } ?: return
        val servers = synchronized(lock) { serverJobs.keys.toList() }
        scope.launch {
            servers.forEach { serverId ->
                evaluateState(serverId, connectionManager.reducer(serverId).state.value)
            }
        }
    }

    private fun trackServer(scope: CoroutineScope, serverId: String) {
        synchronized(lock) {
            if (serverJobs.containsKey(serverId)) return
            serverJobs[serverId] = scope.launch {
                connectionManager.reducer(serverId).state.collect { state ->
                    evaluateState(serverId, state)
                }
            }
        }
    }

    private suspend fun evaluateState(serverId: String, state: DshEventState) {
        for ((sessionId, session) in state.sessions) {
            val liveKey = "${state.generation}\u0000$serverId\u0000$sessionId"
            if (session.running) {
                observedRunning.add(liveKey)
                continue
            }
            if (!isDshUnreadCandidate(session)) {
                observedRunning.remove(liveKey)
                continue
            }
            // A visible chat never gets marked; keep the live-completion flag so
            // the pass can finish once its screen closes (recomputeNow).
            if (eventReducer.isSessionVisible(serverId, sessionId)) continue
            val completedWhileWatched = observedRunning.remove(liveKey) ?: false

            val memoKey = liveKey
            if (evaluatedAt[memoKey] == session.updatedAt) continue
            // Never-seen sessions (no anchor, no folded history, no live
            // completion observed) are skipped so old pre-install sessions are
            // not mass-marked; an existing read anchor still qualifies.
            if (!completedWhileWatched && session.events.isEmpty()) {
                val anchor = preferences.readAnchor(serverId, sessionId).first()
                if (anchor == null) {
                    evaluatedAt[memoKey] = session.updatedAt
                    continue
                }
            }
            evaluatedAt[memoKey] = session.updatedAt
            scope()?.launch {
                delay(DSH_UNREAD_SETTLE_MS)
                evaluateSession(serverId, sessionId)
            }
        }
    }

    private fun scope(): CoroutineScope? = synchronized(lock) { trackingScope }

    private suspend fun evaluateSession(serverId: String, sessionId: String) {
        // Visibility may have changed while the settle delay was running.
        if (eventReducer.isSessionVisible(serverId, sessionId)) return
        val session = connectionManager.reducer(serverId).state.value.sessions[sessionId] ?: return
        if (!isDshUnreadCandidate(session)) return

        val anchor = preferences.readAnchor(serverId, sessionId).first()
        if (!shouldProbeDshHistory(anchor, session)) {
            applyDecision(serverId, sessionId, anchor, dshReadableOutputIds(sessionId, session.events))
            return
        }
        val probed = try {
            probeHistory(serverId, sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // Fail closed: a failed probe never clears or sets a mark.
        if (probed != null) {
            applyDecision(serverId, sessionId, anchor, dshReadableOutputIds(sessionId, probed))
        }
    }

    private suspend fun applyDecision(serverId: String, sessionId: String, anchor: String?, readable: List<String>) {
        if (hasUnreadReplyBeyondReadAnchor(anchor, readable)) {
            preferences.markConversationUnread(serverId, sessionId)
        } else {
            preferences.markConversationRead(serverId, sessionId)
        }
    }
}

/** One-shot history probe: open `session/follow`, take the opening snapshot, cancel. */
private suspend fun probeFollowHistory(
    connectionManager: DshConnectionManager,
    serverId: String,
    sessionId: String,
): List<DshSessionEvent>? {
    val snapshot = withTimeoutOrNull(DSH_UNREAD_PROBE_TIMEOUT_MS) {
        try {
            connectionManager.openSessionFollow(
                serverId = serverId,
                address = DshSessionAddress.Session(sessionId = sessionId),
                maxMessages = DSH_UNREAD_PROBE_MAX_MESSAGES,
            ).first { it is DshFollowFrame.Snapshot } as DshFollowFrame.Snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    } ?: return null
    return snapshot.records.map { it.event.toSessionEvent() }
}
