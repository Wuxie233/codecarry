package dev.wuxie233.codecarry.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ordered ids of assistant messages that carry readable output for the user
 * (non-blank text or reasoning parts, in timeline order). Tool-only messages and
 * assistant errors without any text body are intentionally excluded: stop,
 * failure, or tool-only activity must not fabricate unread output.
 */
fun readableAssistantOutputIds(
    messages: List<Message>,
    partsById: (String) -> List<Part>,
): List<String> = messages.mapNotNull { message ->
    if (message !is Message.Assistant) return@mapNotNull null
    val readable = partsById(message.id).any { part ->
        when (part) {
            is Part.Text -> part.text.isNotBlank()
            is Part.Reasoning -> part.text.isNotBlank()
            else -> false
        }
    }
    if (readable) message.id else null
}

/**
 * Unread derivation for the shared read model: a conversation is unread when new
 * readable assistant output exists strictly beyond the persisted last-read anchor.
 *
 * [readableAssistantMessageIdsInOrder] must be timeline-ordered (see
 * [readableAssistantOutputIds]). A missing anchor means "never read here", so any
 * readable output counts. An anchor that no longer resolves inside the known
 * history (message removed, truncated reload) is treated conservatively as
 * unread; the mark self-heals as soon as the chat presents the tail again and
 * advances the anchor.
 */
fun hasUnreadReplyBeyondReadAnchor(
    anchor: String?,
    readableAssistantMessageIdsInOrder: List<String>,
): Boolean {
    if (readableAssistantMessageIdsInOrder.isEmpty()) return false
    if (anchor == null) return true
    val anchorIndex = readableAssistantMessageIdsInOrder.indexOf(anchor)
    if (anchorIndex < 0) return true
    return anchorIndex < readableAssistantMessageIdsInOrder.lastIndex
}

@Singleton
class SessionListPreferencesRepository @Inject constructor(
    @SessionListDataStore private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val COLLAPSED_DIRS_KEY = stringSetPreferencesKey("collapsed_dirs")
        private val PINNED_DIRS_KEY = stringSetPreferencesKey("pinned_dirs")
        private val PINNED_DIRS_ORDER_KEY = stringPreferencesKey("pinned_dirs_order")
        private val HIDDEN_DIRS_KEY = stringSetPreferencesKey("hidden_dirs")
        private val SORT_KEY = stringPreferencesKey("sort")
        private val FILTER_KEY = stringPreferencesKey("filter")
        private val SCOPE_KEY = stringPreferencesKey("scope")

        /**
         * Legacy pre-server-scope unread set. Kept only for migration; see
         * [LEGACY_UNREAD_MAIN_SESSION_IDS_KEY] below for the strategy.
         */
        private val LEGACY_UNREAD_MAIN_SESSION_IDS_KEY = stringSetPreferencesKey("unread_main_session_ids")

        /** Per-server unread sets: `unread_main_session_ids/<serverId>`. */
        private const val UNREAD_SERVER_KEY_PREFIX = "unread_main_session_ids/"

        /**
         * Per-conversation last-read anchors: `read_anchor/<serverId>\u0000<conversationId>`.
         * The NUL separator keeps (serverId, conversationId) pairs collision-free because
         * ids may contain `/` but never NUL. Anchor values are opaque backend message ids.
         */
        private const val READ_ANCHOR_KEY_PREFIX = "read_anchor/"
        private const val READ_ANCHOR_SEPARATOR = '\u0000'

        // Old filter constant that no longer exists in the SessionFilter enum.
        private const val LEGACY_FILTER_ARCHIVED = "ARCHIVED"

        private fun unreadServerKey(serverId: String): Preferences.Key<Set<String>> =
            stringSetPreferencesKey("$UNREAD_SERVER_KEY_PREFIX$serverId")

        private fun readAnchorKey(serverId: String, conversationId: String): Preferences.Key<String> =
            stringPreferencesKey("$READ_ANCHOR_KEY_PREFIX$serverId$READ_ANCHOR_SEPARATOR$conversationId")
    }

    /**
     * Best-effort one-shot writer: when the preferences flow first observes a legacy
     * `filter=ARCHIVED` row, we kick off a rewrite to `scope=ARCHIVED, filter=ALL`.
     * Subsequent observations are no-ops thanks to [migrationDone].
     *
     * We intentionally use a private supervisor scope rather than a coroutine
     * captured from a caller — DataStore's `edit { }` is suspending and we don't
     * want to block the [preferences] flow's downstream collectors.
     */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val migrationDone = AtomicBoolean(false)

    val preferences: Flow<SessionListPreferences> = dataStore.data.map { prefs ->
        val rawFilter = prefs[FILTER_KEY]
        val needsLegacyMigration = rawFilter == LEGACY_FILTER_ARCHIVED

        if (needsLegacyMigration && migrationDone.compareAndSet(false, true)) {
            migrationScope.launch {
                dataStore.edit { mutable ->
                    mutable[FILTER_KEY] = SessionFilter.ALL.name
                    mutable[SCOPE_KEY] = SessionScope.ARCHIVED.name
                }
            }
        }

        val collapsedDirs = prefs[COLLAPSED_DIRS_KEY] ?: emptySet()
        val pinnedDirsSet = prefs[PINNED_DIRS_KEY] ?: emptySet()
        val pinnedDirsOrder = prefs[PINNED_DIRS_ORDER_KEY] ?: ""
        val pinnedDirs = if (pinnedDirsOrder.isBlank()) {
            pinnedDirsSet.toList()
        } else {
            pinnedDirsOrder.split(",")
                .filter { it.isNotBlank() && it in pinnedDirsSet }
        }
        val sort = prefs[SORT_KEY]?.let {
            runCatching { SessionSort.valueOf(it) }.getOrNull()
        } ?: SessionSort.RECENT_UPDATED

        // The migrated filter is reported to UI immediately, even before the rewrite
        // lands on disk. This means the in-memory state is consistent with what the
        // user sees on screen on the very first frame.
        val filter = if (needsLegacyMigration) {
            SessionFilter.ALL
        } else {
            rawFilter?.let { runCatching { SessionFilter.valueOf(it) }.getOrNull() }
                ?: SessionFilter.ALL
        }

        val scope = if (needsLegacyMigration) {
            SessionScope.ARCHIVED
        } else {
            prefs[SCOPE_KEY]?.let { runCatching { SessionScope.valueOf(it) }.getOrNull() }
                ?: SessionScope.INBOX
        }

        val hiddenDirs = prefs[HIDDEN_DIRS_KEY] ?: emptySet()
        SessionListPreferences(
            collapsedDirs = collapsedDirs,
            pinnedDirs = pinnedDirs,
            hiddenDirs = hiddenDirs,
            sort = sort,
            filter = filter,
            scope = scope,
        )
    }

    // ============ Shared read model (unread marks + read anchors) ============
    //
    // One persistence owner for every backend (OpenCode, DSH, Codex). All state is
    // keyed by `serverId + conversationId` (Codex: threadId), so duplicate
    // conversation ids on two servers never share or clear each other's state.

    /**
     * Observable unread conversation ids for one server.
     *
     * Legacy migration (spec C2 / issue #34): the historical store persisted one
     * global `unread_main_session_ids` set without server attribution. That
     * information cannot be recovered, so on read the legacy ids are attributed to
     * EVERY server's effective set (this flow unions the legacy set in). The
     * over-attribution is harmless: consumers only query pairs that actually exist
     * on that server, and marks self-heal — [markConversationRead] removes the id
     * from both the server's own set and the legacy set, so the first read anywhere
     * retires the legacy mark everywhere. Marks written after this release never
     * enter the legacy set.
     */
    fun unreadConversationIds(serverId: String): Flow<Set<String>> = dataStore.data.map { prefs ->
        val own = prefs[unreadServerKey(serverId)].orEmpty()
        val legacy = prefs[LEGACY_UNREAD_MAIN_SESSION_IDS_KEY].orEmpty()
        if (legacy.isEmpty()) own else own + legacy
    }

    /** Observe the persisted last-read anchor (id of the latest assistant reply the user actually saw). */
    fun readAnchor(serverId: String, conversationId: String): Flow<String?> =
        dataStore.data.map { prefs -> prefs[readAnchorKey(serverId, conversationId)] }

    /** Persist the last-read anchor for one conversation. `null` clears the anchor. */
    suspend fun setReadAnchor(serverId: String, conversationId: String, anchor: String?) {
        val key = readAnchorKey(serverId, conversationId)
        dataStore.edit { prefs ->
            if (anchor == null) {
                if (key in prefs) prefs.remove(key)
            } else if (prefs[key] != anchor) {
                prefs[key] = anchor
            }
        }
    }

    /** Mark one conversation unread on one server. Never touches other servers' sets. */
    suspend fun markConversationUnread(serverId: String, conversationId: String) {
        val key = unreadServerKey(serverId)
        dataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            if (conversationId !in current) {
                prefs[key] = current + conversationId
            }
        }
    }

    /**
     * Mark one conversation read on one server. Also heals the legacy global set
     * (see [unreadConversationIds]); new per-server marks on other servers survive.
     */
    suspend fun markConversationRead(serverId: String, conversationId: String) {
        val key = unreadServerKey(serverId)
        dataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            if (conversationId in current) {
                val next = current - conversationId
                if (next.isEmpty()) prefs.remove(key) else prefs[key] = next
            }
            val legacy = prefs[LEGACY_UNREAD_MAIN_SESSION_IDS_KEY]
            if (legacy != null && conversationId in legacy) {
                val next = legacy - conversationId
                if (next.isEmpty()) prefs.remove(LEGACY_UNREAD_MAIN_SESSION_IDS_KEY)
                else prefs[LEGACY_UNREAD_MAIN_SESSION_IDS_KEY] = next
            }
        }
    }

    fun viewMode(serverId: String): Flow<SessionListViewMode> {
        val key = stringPreferencesKey("view_mode:$serverId")
        return dataStore.data.map { prefs ->
            prefs[key]
                ?.let { runCatching { SessionListViewMode.valueOf(it) }.getOrNull() }
                ?: SessionListViewMode.PROJECTS
        }
    }

    suspend fun setViewMode(serverId: String, viewMode: SessionListViewMode) {
        val key = stringPreferencesKey("view_mode:$serverId")
        dataStore.edit { prefs ->
            prefs[key] = viewMode.name
        }
    }

    suspend fun setCollapsed(dir: String, collapsed: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[COLLAPSED_DIRS_KEY] ?: emptySet()
            prefs[COLLAPSED_DIRS_KEY] = if (collapsed) current + dir else current - dir
        }
    }

    suspend fun togglePinned(dir: String) {
        dataStore.edit { prefs ->
            val currentSet = prefs[PINNED_DIRS_KEY] ?: emptySet()
            val currentOrder = prefs[PINNED_DIRS_ORDER_KEY] ?: ""
            val currentList = if (currentOrder.isBlank()) {
                currentSet.toList()
            } else {
                currentOrder.split(",").filter { it.isNotBlank() && it in currentSet }
            }
            val newList = if (dir in currentSet) {
                currentList - dir
            } else {
                currentList + dir
            }
            prefs[PINNED_DIRS_KEY] = newList.toSet()
            prefs[PINNED_DIRS_ORDER_KEY] = newList.joinToString(",")
        }
    }

    suspend fun addPinned(dir: String): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val key = PINNED_DIRS_KEY
            val orderKey = PINNED_DIRS_ORDER_KEY
            val currentSet = prefs[key] ?: emptySet()
            val currentOrder = prefs[orderKey] ?: ""
            if (dir !in currentSet) {
                val newList = (currentOrder.split(",")
                    .filter { it.isNotBlank() && it in currentSet } + dir)
                    .distinct()
                prefs[key] = newList.toSet()
                prefs[orderKey] = newList.joinToString(",")
                changed = true
            }
        }
        return changed
    }

    suspend fun setSort(sort: SessionSort) {
        dataStore.edit { prefs ->
            prefs[SORT_KEY] = sort.name
        }
    }

    suspend fun setFilter(filter: SessionFilter) {
        dataStore.edit { prefs ->
            prefs[FILTER_KEY] = filter.name
        }
    }

    suspend fun setScope(scope: SessionScope) {
        dataStore.edit { prefs ->
            prefs[SCOPE_KEY] = scope.name
        }
    }

    suspend fun clearCollapsed() {
        dataStore.edit { prefs ->
            prefs[COLLAPSED_DIRS_KEY] = emptySet()
        }
    }

    suspend fun toggleHidden(dir: String) {
        dataStore.edit { prefs ->
            val current = prefs[HIDDEN_DIRS_KEY] ?: emptySet()
            prefs[HIDDEN_DIRS_KEY] = if (dir in current) current - dir else current + dir
        }
    }
}
