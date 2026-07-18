package dev.minios.ocremote.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

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
        private val UNREAD_MAIN_SESSION_IDS_KEY = stringSetPreferencesKey("unread_main_session_ids")
        private const val VIEW_MODE_KEY_PREFIX = "view_mode:"

        // Old filter constant that no longer exists in the SessionFilter enum.
        private const val LEGACY_FILTER_ARCHIVED = "ARCHIVED"
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
        val unreadMainSessionIds = prefs[UNREAD_MAIN_SESSION_IDS_KEY] ?: emptySet()
        SessionListPreferences(
            collapsedDirs = collapsedDirs,
            pinnedDirs = pinnedDirs,
            hiddenDirs = hiddenDirs,
            sort = sort,
            filter = filter,
            scope = scope,
            unreadMainSessionIds = unreadMainSessionIds,
        )
    }

    fun viewMode(serverId: String): Flow<SessionListViewMode> {
        val key = stringPreferencesKey("$VIEW_MODE_KEY_PREFIX$serverId")
        return dataStore.data.map { prefs ->
            prefs[key]
                ?.let { runCatching { SessionListViewMode.valueOf(it) }.getOrNull() }
                ?: SessionListViewMode.PROJECTS
        }
    }

    suspend fun setViewMode(serverId: String, viewMode: SessionListViewMode) {
        val key = stringPreferencesKey("$VIEW_MODE_KEY_PREFIX$serverId")
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

    suspend fun markMainSessionUnread(sessionId: String) {
        dataStore.edit { prefs ->
            val current = prefs[UNREAD_MAIN_SESSION_IDS_KEY] ?: emptySet()
            prefs[UNREAD_MAIN_SESSION_IDS_KEY] = current + sessionId
        }
    }

    suspend fun markMainSessionRead(sessionId: String) {
        dataStore.edit { prefs ->
            val current = prefs[UNREAD_MAIN_SESSION_IDS_KEY] ?: emptySet()
            prefs[UNREAD_MAIN_SESSION_IDS_KEY] = current - sessionId
        }
    }

    suspend fun markMainSessionsRead(sessionIds: Collection<String>) {
        if (sessionIds.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[UNREAD_MAIN_SESSION_IDS_KEY] ?: emptySet()
            prefs[UNREAD_MAIN_SESSION_IDS_KEY] = current - sessionIds.toSet()
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
