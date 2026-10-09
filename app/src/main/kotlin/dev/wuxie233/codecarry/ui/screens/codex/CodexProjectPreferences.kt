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
    val selectedProjectId: String? = null,
)

class CodexProjectPreferencesRepository @Inject constructor(
    @SessionListDataStore private val dataStore: DataStore<Preferences>,
) {
    private fun key(serverId: String, name: String) = stringSetPreferencesKey("codex_projects:$serverId:$name")
    private fun viewKey(serverId: String) = stringPreferencesKey("codex_projects:$serverId:view")
    fun observe(serverId: String) = dataStore.data.map { prefs ->
        CodexProjectPreferences(
            selectedProjectId = prefs[stringPreferencesKey("codex_projects:$serverId:selected")],
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
    suspend fun selectProject(serverId: String, projectId: String?) {
        dataStore.edit { prefs ->
            val key = stringPreferencesKey("codex_projects:$serverId:selected")
            if (projectId == null) prefs.remove(key) else prefs[key] = projectId
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
    val roots: List<CodexThreadNode> = emptyList(),
    val orphan: Boolean = false,
    val projectId: String? = null,
    val name: String? = null,
    val directories: List<String> = emptyList(),
)

internal fun buildCodexThreadProjects(
    threads: List<CodexThread>,
    preferences: CodexProjectPreferences,
    showHidden: Boolean,
    searching: Boolean = false,
): List<CodexThreadProject> = buildCodexTopologyProjects(buildCodexThreadTopology(threads), preferences, showHidden, searching)

internal fun buildCodexTopologyProjects(
    roots: List<CodexThreadNode>,
    preferences: CodexProjectPreferences,
    showHidden: Boolean,
    searching: Boolean = false,
): List<CodexThreadProject> = roots.groupBy { node -> node.orphan to if (node.orphan) "" else node.thread.cwd.orEmpty() }
    .filterKeys { (orphan, directory) -> orphan || showHidden || directory !in preferences.hidden }
    .map { (key, nodes) ->
        val (orphan, directory) = key
        CodexThreadProject(directory, nodes.map { it.thread }, !orphan && directory in preferences.pinned,
            !orphan && !searching && directory in preferences.collapsed, !orphan && directory in preferences.hidden,
            nodes, orphan)
    }
    .sortedWith(compareByDescending<CodexThreadProject> { it.pinned }
        .thenBy { it.orphan }
        .thenByDescending { it.roots.maxOfOrNull { node -> node.recency } ?: 0L }
        .thenBy { it.directory })

/** Catalog identity, never cwd equality, decides project membership. */
internal fun buildCodexCatalogProjects(
    catalog: List<dev.wuxie233.codecarry.data.codex.CodexProject>,
    roots: List<CodexThreadNode>,
    preferences: CodexProjectPreferences,
    constrained: Boolean,
    searchQuery: String = "",
): List<CodexThreadProject> {
    val ids = catalog.mapTo(hashSetOf()) { it.id }
    val grouped = roots.groupBy { it.thread.projectId?.takeIf(ids::contains) }
    val projects = catalog.mapNotNull { project ->
        val nodes = grouped[project.id].orEmpty()
        if (constrained && nodes.isEmpty() && (searchQuery.isBlank() || !project.name.contains(searchQuery, ignoreCase = true))) null else CodexThreadProject(
            directory = project.roots.firstOrNull()?.path.orEmpty(),
            threads = nodes.flatMap { it.members }, pinned = false,
            collapsed = !constrained && project.id in preferences.collapsed, hidden = false,
            roots = nodes, projectId = project.id, name = project.name,
            directories = project.roots.map { it.path },
        )
    }
    val unassigned = grouped[null].orEmpty()
    return if (unassigned.isEmpty()) projects else projects + CodexThreadProject(
        directory = "", threads = unassigned.flatMap { it.members }, pinned = false,
        collapsed = false, hidden = false, roots = unassigned,
    )
}
