package dev.wuxie233.codecarry.ui.screens.codex

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.wuxie233.codecarry.data.preferences.SessionListDataStore
import dev.wuxie233.codecarry.data.preferences.SessionListViewMode
import dev.wuxie233.codecarry.data.codex.CodexThread
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Each server owns its project preferences even when directory paths coincide. */
data class CodexProjectPreferences(
    val collapsed: Set<String> = emptySet(),
    val pinned: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val viewMode: SessionListViewMode = SessionListViewMode.PROJECTS,
)

class CodexProjectPreferencesRepository @Inject constructor(
    @SessionListDataStore private val dataStore: DataStore<Preferences>,
) {
    private fun key(serverId: String, name: String) = stringSetPreferencesKey("codex_projects:$serverId:$name")
    private fun viewKey(serverId: String) = stringPreferencesKey("codex_projects:$serverId:view")
    fun observe(serverId: String) = dataStore.data.map { prefs ->
        CodexProjectPreferences(
            collapsed = prefs[key(serverId, "collapsed")].orEmpty(),
            pinned = prefs[key(serverId, "pinned")].orEmpty(),
            hidden = prefs[key(serverId, "hidden")].orEmpty(),
            viewMode = prefs[viewKey(serverId)]?.let { runCatching { SessionListViewMode.valueOf(it) }.getOrNull() }
                ?: SessionListViewMode.PROJECTS,
        )
    }
    suspend fun toggle(serverId: String, name: String, directory: String) {
        require(name in setOf("collapsed", "pinned", "hidden"))
        dataStore.edit { prefs ->
            val key = key(serverId, name)
            val current = prefs[key].orEmpty()
            prefs[key] = if (directory in current) current - directory else current + directory
        }
    }
    suspend fun setViewMode(serverId: String, mode: SessionListViewMode) {
        dataStore.edit { it[viewKey(serverId)] = mode.name }
    }
}

data class CodexThreadProject(
    val directory: String,
    val threads: List<CodexThread>,
    val pinned: Boolean,
    val collapsed: Boolean,
    val hidden: Boolean,
)

internal fun buildCodexThreadProjects(
    threads: List<CodexThread>,
    preferences: CodexProjectPreferences,
    showHidden: Boolean,
    searching: Boolean = false,
): List<CodexThreadProject> = threads.groupBy { it.cwd.orEmpty() }
    .filterKeys { showHidden || it !in preferences.hidden }
    .map { (directory, members) ->
        CodexThreadProject(directory, members, directory in preferences.pinned,
            !searching && directory in preferences.collapsed, directory in preferences.hidden)
    }
    .sortedWith(compareByDescending<CodexThreadProject> { it.pinned }
        .thenByDescending { it.threads.maxOfOrNull { thread -> thread.recencyAt ?: thread.updatedAt ?: thread.createdAt ?: 0L } ?: 0L }
        .thenBy { it.directory })
