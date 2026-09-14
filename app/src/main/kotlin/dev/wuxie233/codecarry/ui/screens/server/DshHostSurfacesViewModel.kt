package dev.wuxie233.codecarry.ui.screens.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wuxie233.codecarry.data.dsh.DshAgentPresetListValue
import dev.wuxie233.codecarry.data.dsh.DshAutomationListValue
import dev.wuxie233.codecarry.data.dsh.DshConnection
import dev.wuxie233.codecarry.data.dsh.DshDirectoryListing
import dev.wuxie233.codecarry.data.dsh.DshHostSurfaceCatalog
import dev.wuxie233.codecarry.data.dsh.DshHostSurfaceController
import dev.wuxie233.codecarry.data.dsh.DshLlmModelsValue
import dev.wuxie233.codecarry.data.dsh.DshLlmProvidersValue
import dev.wuxie233.codecarry.data.dsh.DshSessionGitView
import dev.wuxie233.codecarry.data.dsh.DshSettingsDescribeValue
import dev.wuxie233.codecarry.data.dsh.DshSkillCatalogValue
import dev.wuxie233.codecarry.data.dsh.DshSubagentCatalog
import dev.wuxie233.codecarry.data.dsh.DshSystemPromptListValue
import dev.wuxie233.codecarry.data.dsh.DshGoalRefValue
import dev.wuxie233.codecarry.data.dsh.DshSettingsNamespaceView
import dev.wuxie233.codecarry.data.dsh.DshWorkspaceCreateValue
import dev.wuxie233.codecarry.data.dsh.DshWorkspaceListValue
import dev.wuxie233.codecarry.data.dsh.dshHostSurfaceCatalog
import dev.wuxie233.codecarry.data.dsh.mapDshEventStateToSessions
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.domain.model.ServerType
import dev.wuxie233.codecarry.domain.model.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URLDecoder
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Loading/error/data state of one independently loaded host-surface module.
 * `data` survives a failed refresh so already-obtained results stay visible.
 */
data class DshSurfaceModuleState<T>(
    val loading: Boolean = false,
    val error: String? = null,
    val data: T? = null,
)

/** Host-surface modules that can be retried individually. */
enum class DshHostModule {
    SESSIONS,
    DIRECTORY,
    GIT,
    PRESETS,
    AUTOMATION,
    SETTINGS,
    PROVIDERS,
    MODELS,
    SYSTEM_PROMPT,
    SKILLS,
    SUBAGENTS,
}

/** One selectable session target for the session-scoped host surfaces. */
data class DshSessionOption(
    val sessionId: String,
    val title: String,
    val running: Boolean,
    val updatedAt: Long,
)

/** Editable simple types derived from a settings namespace schema. */
enum class DshSettingsFieldKind { TEXT, NUMBER, BOOLEAN }

data class DshSettingsFormField(
    val key: String,
    val kind: DshSettingsFieldKind,
    val textValue: String? = null,
    val booleanValue: Boolean? = null,
)

/** Client-side settings validation failures, mapped to resources by the screen. */
enum class DshSettingsFormError {
    NO_NAMESPACE,
    INVALID_NUMBER,
    EMPTY_PATCH,
    INVALID_JSON_ARRAY,
}

data class DshHostSurfacesUiState(
    val isDsh: Boolean = false,
    val serverName: String = "",
    val catalog: DshHostSurfaceCatalog? = null,
    val workspaces: DshWorkspaceListValue? = null,
    val directory: DshSurfaceModuleState<DshDirectoryListing> = DshSurfaceModuleState(),
    val skills: DshSurfaceModuleState<DshSkillCatalogValue> = DshSurfaceModuleState(),
    val git: DshSurfaceModuleState<DshSessionGitView?> = DshSurfaceModuleState(),
    val presets: DshSurfaceModuleState<DshAgentPresetListValue> = DshSurfaceModuleState(),
    val automation: DshSurfaceModuleState<DshAutomationListValue> = DshSurfaceModuleState(),
    val settings: DshSurfaceModuleState<DshSettingsDescribeValue> = DshSurfaceModuleState(),
    val providers: DshSurfaceModuleState<DshLlmProvidersValue> = DshSurfaceModuleState(),
    val models: DshSurfaceModuleState<DshLlmModelsValue> = DshSurfaceModuleState(),
    val systemPrompt: DshSurfaceModuleState<DshSystemPromptListValue> = DshSurfaceModuleState(),
    val subagents: DshSurfaceModuleState<DshSubagentCatalog> = DshSurfaceModuleState(),
    val sessions: DshSurfaceModuleState<List<DshSessionOption>> = DshSurfaceModuleState(),
    val selectedSessionId: String? = null,
    val lastGoal: DshGoalRefValue? = null,
    val lastPreset: String? = null,
    val lastSettings: DshSettingsNamespaceView? = null,
    val actionError: String? = null,
    val formError: DshSettingsFormError? = null,
) {
    /** The currently selected session target, when the roster knows it. */
    val selectedSession: DshSessionOption?
        get() = selectedSessionId?.let { id -> sessions.data?.firstOrNull { it.sessionId == id } }
}

@HiltViewModel
class DshHostSurfacesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val dshConnectionManager: dev.wuxie233.codecarry.data.dsh.DshConnectionManager,
    private val api: dev.wuxie233.codecarry.data.dsh.DshApiClient,
) : ViewModel() {
    private val serverId: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverId") ?: "",
        "UTF-8",
    )

    private val _uiState = MutableStateFlow(DshHostSurfacesUiState())
    val uiState: StateFlow<DshHostSurfacesUiState> = _uiState.asStateFlow()

    private var controller: DshHostSurfaceController? = null
    private val moduleJobs = mutableMapOf<DshHostModule, Job>()
    private var directoryPath: String? = null

    init {
        viewModelScope.launch { bind() }
    }

    fun refresh() {
        val host = controller ?: return
        val catalog = host.catalog()
        _uiState.update { it.copy(actionError = null, formError = null) }
        if (catalog.can("session/list")) loadSessionOptions()
        if (catalog.canBrowseHost) loadDirectory(null)
        if (catalog.canDescribeGit) loadGit()
        if (catalog.canListPresets) loadPresets()
        if (catalog.canManageAutomation) loadAutomation()
        if (catalog.can("settings/describe")) loadSettingsDescribe()
        if (catalog.can("llm/listProviders")) loadProviders()
        if (catalog.can("session/modelCatalog")) loadModels()
        if (catalog.canListSystemPrompt) loadSystemPrompt()
        if (catalog.canListSkills) {
            _uiState.value.selectedSessionId?.let(::loadSkills)
        }
    }

    /** Retry exactly one module; other modules keep their state. */
    fun retry(module: DshHostModule) {
        when (module) {
            DshHostModule.SESSIONS -> loadSessionOptions()
            DshHostModule.DIRECTORY -> loadDirectory(directoryPath)
            DshHostModule.GIT -> loadGit()
            DshHostModule.PRESETS -> loadPresets()
            DshHostModule.AUTOMATION -> loadAutomation()
            DshHostModule.SETTINGS -> loadSettingsDescribe()
            DshHostModule.PROVIDERS -> loadProviders()
            DshHostModule.MODELS -> loadModels()
            DshHostModule.SYSTEM_PROMPT -> loadSystemPrompt()
            DshHostModule.SKILLS -> retrySkills()
            DshHostModule.SUBAGENTS -> loadSubagents()
        }
    }

    // ---- Session context (#45, #47) ----

    /**
     * Set the session context every session-scoped surface targets. Skills
     * reload for the new session; subagent and git views re-resolve too.
     */
    fun selectSession(sessionId: String) {
        if (sessionId.isBlank()) return
        if (_uiState.value.selectedSessionId == sessionId) return
        val host = controller ?: return
        _uiState.update {
            it.copy(
                selectedSessionId = sessionId,
                skills = DshSurfaceModuleState(),
                subagents = DshSurfaceModuleState(),
                formError = null,
            )
        }
        if (host.catalog().canListSkills) loadSkills(sessionId)
        if (host.catalog().canDescribeGit) loadGit()
    }

    /** Skills are session-scoped on current DSH; load them for one session. */
    fun loadSkills(sessionId: String) {
        val host = controller ?: return
        if (!host.catalog().canListSkills) return
        launchModule(DshHostModule.SKILLS) {
            loadModuleState(
                DshHostModule.SKILLS,
                { state -> state.skills },
                { state, module -> state.copy(skills = module) },
            ) {
                skillCatalogOf(host.skillList(sessionId))
            }
        }
    }

    fun retrySkills() {
        _uiState.value.selectedSessionId?.let(::loadSkills)
    }

    private fun loadSessionOptions() {
        val host = controller ?: return
        launchModule(DshHostModule.SESSIONS) {
            val options = loadModuleState(
                DshHostModule.SESSIONS,
                { state -> state.sessions },
                { state, module -> state.copy(sessions = module) },
            ) {
                val listed = host.sessionList()
                val reducer = dshConnectionManager.reducer(serverId)
                reducer.applySessionList(listed.items)
                sessionOptionsFrom(reducer.state.value)
            } ?: return@launchModule
            applySessionOptions(options)
        }
    }

    /**
     * Publish the merged roster, inherit the session context when exactly one
     * session exists, and drop a selection that no longer exists.
     */
    private fun applySessionOptions(options: List<DshSessionOption>) {
        val previous = _uiState.value.selectedSessionId
        _uiState.update { state ->
            val stale = state.selectedSessionId != null &&
                options.none { it.sessionId == state.selectedSessionId }
            val inherited = state.selectedSessionId ?: options.singleOrNull()?.sessionId
            state.copy(
                sessions = state.sessions.copy(loading = false, error = null, data = options),
                selectedSessionId = if (stale) null else inherited,
                skills = if (stale) DshSurfaceModuleState() else state.skills,
                subagents = if (stale) DshSurfaceModuleState() else state.subagents,
            )
        }
        val selected = _uiState.value.selectedSessionId
        val host = controller
        if (selected != null && selected != previous && host?.catalog()?.canListSkills == true) {
            loadSkills(selected)
        }
    }

    // ---- Independent module loads (#46) ----

    private fun loadDirectory(path: String?) {
        val host = controller ?: return
        if (!host.catalog().canBrowseHost) return
        directoryPath = path
        launchModule(DshHostModule.DIRECTORY) {
            loadModuleState(
                DshHostModule.DIRECTORY,
                { state -> state.directory },
                { state, module -> state.copy(directory = module) },
            ) {
                host.listDirectory(path)
            }
        }
    }

    private fun loadGit() {
        val host = controller ?: return
        if (!host.catalog().canDescribeGit) return
        launchModule(DshHostModule.GIT) {
            loadModuleState(
                DshHostModule.GIT,
                { state -> state.git },
                { state, module -> state.copy(git = module) },
            ) {
                val selected = _uiState.value.selectedSessionId
                val workspaces = workspaceCatalogFromReducer()
                val targetSession = selected
                    ?: workspaces.items.firstNotNullOfOrNull { it.sessionIds.firstOrNull() }
                val targetWorkspace = workspaces.items.firstOrNull()
                    ?.takeIf { selected == null }
                    ?.workspaceId
                if (targetSession == null && targetWorkspace == null) {
                    null
                } else {
                    host.gitDescribe(sessionId = targetSession, workspaceId = targetWorkspace)
                }
            }
        }
    }

    private fun loadPresets() {
        val host = controller ?: return
        if (!host.catalog().canListPresets) return
        launchModule(DshHostModule.PRESETS) {
            loadModuleState(
                DshHostModule.PRESETS,
                { state -> state.presets },
                { state, module -> state.copy(presets = module) },
            ) {
                host.agentPresetList()
            }
        }
    }

    private fun loadAutomation() {
        val host = controller ?: return
        if (!host.catalog().canManageAutomation) return
        launchModule(DshHostModule.AUTOMATION) {
            loadModuleState(
                DshHostModule.AUTOMATION,
                { state -> state.automation },
                { state, module -> state.copy(automation = module) },
            ) {
                host.automationList()
            }
        }
    }

    private fun loadSettingsDescribe() {
        val host = controller ?: return
        if (!host.catalog().can("settings/describe")) return
        launchModule(DshHostModule.SETTINGS) {
            loadModuleState(
                DshHostModule.SETTINGS,
                { state -> state.settings },
                { state, module -> state.copy(settings = module) },
            ) {
                host.settingsDescribe()
            }
        }
    }

    private fun loadProviders() {
        val host = controller ?: return
        if (!host.catalog().can("llm/listProviders")) return
        launchModule(DshHostModule.PROVIDERS) {
            loadModuleState(
                DshHostModule.PROVIDERS,
                { state -> state.providers },
                { state, module -> state.copy(providers = module) },
            ) {
                host.llmProviders()
            }
        }
    }

    private fun loadModels() {
        val host = controller ?: return
        if (!host.catalog().can("session/modelCatalog")) return
        launchModule(DshHostModule.MODELS) {
            loadModuleState(
                DshHostModule.MODELS,
                { state -> state.models },
                { state, module -> state.copy(models = module) },
            ) {
                host.llmModels()
            }
        }
    }

    private fun loadSystemPrompt() {
        val host = controller ?: return
        if (!host.catalog().canListSystemPrompt) return
        launchModule(DshHostModule.SYSTEM_PROMPT) {
            loadModuleState(
                DshHostModule.SYSTEM_PROMPT,
                { state -> state.systemPrompt },
                { state, module -> state.copy(systemPrompt = module) },
            ) {
                host.systemPromptList()
            }
        }
    }

    // ---- Actions (#47) ----

    fun createWorkspace(path: String) {
        val host = controller ?: return
        launchAction {
            val created = host.createWorkspace(path)
            applyCreatedWorkspace(created)
        }
    }

    fun createDirectory(path: String, name: String) {
        val host = controller ?: return
        launchAction {
            val created = host.createDirectory(path, name)
            loadDirectory(created.path)
        }
    }

    fun browse(path: String) {
        loadDirectory(path)
    }

    fun selectPreset(presetId: String) {
        val host = controller ?: return
        val sessionId = _uiState.value.selectedSessionId ?: return
        launchAction {
            host.agentPresetSelect(sessionId, presetId)
            _uiState.update { it.copy(lastPreset = presetId, actionError = null) }
        }
    }

    fun createGoal(objective: String) {
        val host = controller ?: return
        val sessionId = _uiState.value.selectedSessionId ?: return
        launchAction {
            val goal = host.goalCreate(sessionId, objective)
            _uiState.update { it.copy(lastGoal = goal, actionError = null) }
        }
    }

    fun loadSubagents() {
        val host = controller ?: return
        if (!host.catalog().canListSubagents) return
        val parent = _uiState.value.selectedSessionId ?: return
        launchModule(DshHostModule.SUBAGENTS) {
            loadModuleState(
                DshHostModule.SUBAGENTS,
                { state -> state.subagents },
                { state, module -> state.copy(subagents = module) },
            ) {
                host.subagentList(parent)
            }
        }
    }

    fun promptSubagent(childSessionId: String, text: String) {
        val host = controller ?: return
        val parent = _uiState.value.selectedSessionId ?: return
        launchAction {
            host.subagentPrompt(parent, childSessionId, text)
            loadSubagents()
        }
    }

    fun interruptSubagent(childSessionId: String) {
        val host = controller ?: return
        val parent = _uiState.value.selectedSessionId ?: return
        launchAction {
            host.subagentInterrupt(parent, childSessionId)
            loadSubagents()
        }
    }

    // ---- Settings forms (#47) ----

    /** Simple typed fields of one namespace; empty when none are derivable. */
    fun settingsFieldsFor(ns: String): List<DshSettingsFormField> =
        _uiState.value.settings.data?.namespaces
            ?.firstOrNull { it.ns == ns }
            ?.let(::dshSettingsFormFields)
            .orEmpty()

    fun clearFormError() {
        _uiState.update { it.copy(formError = null) }
    }

    /** Submit the typed form: one `set` op per changed simple field. */
    fun mutateSettingsForm(
        ns: String,
        textValues: Map<String, String>,
        booleanValues: Map<String, Boolean>,
    ) {
        val host = controller ?: return
        val namespace = settingsNamespace(ns)
        if (namespace == null) {
            _uiState.update { it.copy(formError = DshSettingsFormError.NO_NAMESPACE) }
            return
        }
        val ops = dshSettingsFormOps(dshSettingsFormFields(namespace), textValues, booleanValues)
        if (ops == null) {
            _uiState.update { it.copy(formError = DshSettingsFormError.INVALID_NUMBER) }
            return
        }
        if (ops.isEmpty()) {
            _uiState.update { it.copy(formError = DshSettingsFormError.EMPTY_PATCH) }
            return
        }
        launchAction {
            val view = host.settingsMutate(ns, ops, expectedRevision = namespace.revision)
            applySettingsMutation(view)
        }
    }

    /** Explicit advanced path: raw JSON patch, validated before sending. */
    fun mutateSettingsJson(ns: String, patchJson: String) {
        val host = controller ?: return
        val namespace = settingsNamespace(ns)
        if (namespace == null) {
            _uiState.update { it.copy(formError = DshSettingsFormError.NO_NAMESPACE) }
            return
        }
        val ops = runCatching { Json.parseToJsonElement(patchJson) }.getOrNull() as? JsonArray
        val valid = ops != null && ops.all { element ->
            val op = (element as? JsonObject)?.get("op")
            op is JsonPrimitive && op.isString && op.content.isNotBlank()
        }
        if (ops == null || !valid) {
            _uiState.update { it.copy(formError = DshSettingsFormError.INVALID_JSON_ARRAY) }
            return
        }
        launchAction {
            val view = host.settingsMutate(ns, ops, expectedRevision = namespace.revision)
            applySettingsMutation(view)
        }
    }

    private fun settingsNamespace(ns: String): DshSettingsNamespaceView? =
        _uiState.value.settings.data?.namespaces?.firstOrNull { it.ns == ns }

    /** Fold a mutation receipt back into the described namespaces. */
    private fun applySettingsMutation(view: DshSettingsNamespaceView) {
        _uiState.update { state ->
            val describe = state.settings.data
            val nextDescribe = describe?.copy(
                namespaces = describe.namespaces.map { if (it.ns == view.ns) view else it },
            )
            state.copy(
                settings = state.settings.copy(data = nextDescribe),
                lastSettings = view,
                actionError = null,
                formError = null,
            )
        }
    }

    // ---- Plumbing ----

    private suspend fun bind() {
        val server = serverRepository.getServer(serverId)
        if (server == null || server.type != ServerType.DSH) {
            _uiState.value = DshHostSurfacesUiState(isDsh = false)
            return
        }
        val connection = DshConnection.from(server.url, server.password, server.token)
        val catalog = dshHostSurfaceCatalog(connection)
        controller = DshHostSurfaceController(api, connection, catalog)
        _uiState.value = DshHostSurfacesUiState(
            isDsh = true,
            serverName = server.displayName,
            catalog = catalog,
        )
        refresh()
    }

    /** The workspace catalog is the live `workspace/follow` baseline in the reducer. */
    private fun workspaceCatalogFromReducer(): DshWorkspaceListValue {
        val state = dshConnectionManager.reducer(serverId).state.value
        val order = state.workspaceOrder.ifEmpty { state.workspaces.keys.toList() }
        return DshWorkspaceListValue(
            items = order.mapNotNull { id -> state.workspaces[id]?.let(::workspaceViewOf) },
            archivedSessionIds = state.archivedSessionIds.toList(),
            hiddenWorkspaceIds = state.hiddenWorkspaceIds.toList(),
        )
    }

    private fun workspaceViewOf(obj: kotlinx.serialization.json.JsonObject): dev.wuxie233.codecarry.data.dsh.DshWorkspaceView? =
        runCatching {
            Json.decodeFromJsonElement(
                dev.wuxie233.codecarry.data.dsh.DshWorkspaceView.serializer(),
                obj,
            )
        }.getOrNull()

    private fun sessionOptionsFrom(state: dev.wuxie233.codecarry.data.dsh.DshEventState): List<DshSessionOption> {
        val mapped = mapDshEventStateToSessions(state)
        return mapped.sessions
            .map { session ->
                DshSessionOption(
                    sessionId = session.id,
                    title = session.title
                        ?: session.directory.substringAfterLast('/').ifBlank { session.id },
                    running = mapped.statuses[session.id] == SessionStatus.Busy,
                    updatedAt = session.time.updated,
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    private fun skillCatalogOf(value: dev.wuxie233.codecarry.data.dsh.DshSkillListValue): DshSkillCatalogValue =
        DshSkillCatalogValue(
            skills = value.skills.map { entry ->
                dev.wuxie233.codecarry.data.dsh.DshSkillCatalogEntry(
                    name = entry.name,
                    description = entry.description,
                    whenToUse = entry.whenToUse,
                    modelInvocable = entry.modelInvocable,
                    userInvocable = true,
                    source = "",
                    provider = "",
                )
            },
        )

    private fun applyCreatedWorkspace(created: DshWorkspaceCreateValue) {
        _uiState.update { current ->
            val items = current.workspaces?.items.orEmpty()
            val nextItems = if (items.any { it.workspaceId == created.workspace.workspaceId }) {
                items.map { if (it.workspaceId == created.workspace.workspaceId) created.workspace else it }
            } else {
                items + created.workspace
            }
            current.copy(
                workspaces = (current.workspaces ?: DshWorkspaceListValue()).copy(items = nextItems),
                actionError = null,
            )
        }
    }

    /**
     * Run one module load to completion. Success and failure publish only this
     * module's slice; caller cancellation propagates; results from an obsolete
     * connection generation or a superseded job are dropped.
     */
    private suspend fun <T> loadModuleState(
        module: DshHostModule,
        get: (DshHostSurfacesUiState) -> DshSurfaceModuleState<T>,
        set: (DshHostSurfacesUiState, DshSurfaceModuleState<T>) -> DshHostSurfacesUiState,
        load: suspend () -> T,
    ): T? {
        val generation = connectionGeneration()
        _uiState.update { state -> set(state, get(state).copy(loading = true, error = null)) }
        return try {
            val value = load()
            if (isCurrentModuleJob(module) && isGenerationCurrent(generation)) {
                _uiState.update { state -> set(state, get(state).copy(loading = false, data = value)) }
                value
            } else {
                markSupersededModuleIdle(module, get, set)
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isCurrentModuleJob(module) && isGenerationCurrent(generation)) {
                _uiState.update { state ->
                    set(
                        state,
                        get(state).copy(
                            loading = false,
                            error = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            } else {
                markSupersededModuleIdle(module, get, set)
            }
            null
        }
    }

    /** A dropped result must not leave the module stuck in loading. */
    private suspend fun <T> markSupersededModuleIdle(
        module: DshHostModule,
        get: (DshHostSurfacesUiState) -> DshSurfaceModuleState<T>,
        set: (DshHostSurfacesUiState, DshSurfaceModuleState<T>) -> DshHostSurfacesUiState,
    ) {
        if (!isCurrentModuleJob(module)) return
        _uiState.update { state -> set(state, get(state).copy(loading = false)) }
    }

    private fun launchModule(module: DshHostModule, block: suspend () -> Unit) {
        moduleJobs.remove(module)?.cancel()
        val job = viewModelScope.launch { block() }
        moduleJobs[module] = job
        job.invokeOnCompletion { if (moduleJobs[module] === job) moduleJobs.remove(module) }
    }

    private suspend fun isCurrentModuleJob(module: DshHostModule): Boolean =
        moduleJobs[module] === coroutineContext[Job]

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(actionError = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    private fun connectionGeneration(): Long? =
        dshConnectionManager.states.value[serverId]?.generation

    private fun isGenerationCurrent(generation: Long?): Boolean =
        dshConnectionManager.states.value[serverId]?.generation == generation
}

/** Derive editable simple fields from a namespace schema; unknown shapes yield none. */
internal fun dshSettingsFormFields(ns: DshSettingsNamespaceView): List<DshSettingsFormField> {
    val schema = ns.schema as? JsonObject ?: return emptyList()
    val properties = (schema["properties"] as? JsonObject) ?: schema
    val value = ns.value as? JsonObject
    return properties.entries.mapNotNull { (key, definition) ->
        val type = (definition as? JsonObject)
            ?.get("type")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?: return@mapNotNull null
        val current = value?.get(key)
        when (type) {
            "string" -> DshSettingsFormField(
                key = key,
                kind = DshSettingsFieldKind.TEXT,
                textValue = current?.let { (it as? JsonPrimitive)?.contentOrNull },
            )
            "number", "integer" -> DshSettingsFormField(
                key = key,
                kind = DshSettingsFieldKind.NUMBER,
                textValue = current?.let { (it as? JsonPrimitive)?.contentOrNull },
            )
            "boolean" -> DshSettingsFormField(
                key = key,
                kind = DshSettingsFieldKind.BOOLEAN,
                booleanValue = current?.let { (it as? JsonPrimitive)?.booleanOrNull },
            )
            else -> null
        }
    }
}

/** Build one `set` op per changed field; null when a numeric edit is invalid. */
internal fun dshSettingsFormOps(
    fields: List<DshSettingsFormField>,
    textValues: Map<String, String>,
    booleanValues: Map<String, Boolean>,
): JsonArray? {
    val ops = mutableListOf<JsonObject>()
    for (field in fields) {
        when (field.kind) {
            DshSettingsFieldKind.TEXT -> {
                val raw = textValues[field.key] ?: continue
                if (raw != (field.textValue ?: "")) {
                    ops += dshSettingsSetOp(field.key, JsonPrimitive(raw))
                }
            }
            DshSettingsFieldKind.NUMBER -> {
                val raw = textValues[field.key] ?: continue
                if (raw == (field.textValue ?: "")) continue
                val primitive = raw.toLongOrNull()?.let { JsonPrimitive(it) }
                    ?: raw.toDoubleOrNull()?.let { JsonPrimitive(it) }
                    ?: return null
                ops += dshSettingsSetOp(field.key, primitive)
            }
            DshSettingsFieldKind.BOOLEAN -> {
                val value = booleanValues[field.key] ?: continue
                if (value != field.booleanValue) {
                    ops += dshSettingsSetOp(field.key, JsonPrimitive(value))
                }
            }
        }
    }
    return JsonArray(ops)
}

private fun dshSettingsSetOp(key: String, value: JsonPrimitive): JsonObject = buildJsonObject {
    put("op", "set")
    put("path", JsonArray(listOf(JsonPrimitive(key))))
    put("value", value)
}
