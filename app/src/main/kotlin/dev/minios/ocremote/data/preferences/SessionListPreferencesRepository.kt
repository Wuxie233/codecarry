package dev.minios.ocremote.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        private val SORT_KEY = stringPreferencesKey("sort")
        private val FILTER_KEY = stringPreferencesKey("filter")
    }

    val preferences: Flow<SessionListPreferences> = dataStore.data.map { prefs ->
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
        val filter = prefs[FILTER_KEY]?.let {
            runCatching { SessionFilter.valueOf(it) }.getOrNull()
        } ?: SessionFilter.ALL
        SessionListPreferences(
            collapsedDirs = collapsedDirs,
            pinnedDirs = pinnedDirs,
            sort = sort,
            filter = filter,
        )
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

    suspend fun clearCollapsed() {
        dataStore.edit { prefs ->
            prefs[COLLAPSED_DIRS_KEY] = emptySet()
        }
    }
}
