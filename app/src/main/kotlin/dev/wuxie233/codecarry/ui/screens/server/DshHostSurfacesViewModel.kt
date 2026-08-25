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
import dev.wuxie233.codecarry.data.dsh.DshWorkspaceCreateValue
import dev.wuxie233.codecarry.data.dsh.DshWorkspaceListValue
import dev.wuxie233.codecarry.data.dsh.dshHostSurfaceCatalog
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.domain.model.ServerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

data class DshHostSurfacesUiState(
    val isDsh: Boolean = false,
    val catalog: DshHostSurfaceCatalog? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val workspaces: DshWorkspaceListValue? = null,
    val directory: DshDirectoryListing? = null,
    val skills: DshSkillCatalogValue? = null,
    val git: DshSessionGitView? = null,
    val presets: DshAgentPresetListValue? = null,
    val automation: DshAutomationListValue? = null,
    val settings: DshSettingsDescribeValue? = null,
    val providers: DshLlmProvidersValue? = null,
    val models: DshLlmModelsValue? = null,
    val subagents: DshSubagentCatalog? = null,
    val systemPrompt: DshSystemPromptListValue? = null,
)

@HiltViewModel
class DshHostSurfacesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val api: dev.wuxie233.codecarry.data.dsh.DshApiClient,
) : ViewModel() {
    private val serverId: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverId") ?: "",
        "UTF-8",
    )

    private val _uiState = MutableStateFlow(DshHostSurfacesUiState())
    val uiState: StateFlow<DshHostSurfacesUiState> = _uiState.asStateFlow()

    private var controller: DshHostSurfaceController? = null

    init {
        viewModelScope.launch { bind() }
    }

    fun refresh() {
        viewModelScope.launch { loadAll() }
    }

    fun createWorkspace(path: String) {
        viewModelScope.launch {
            runCatching { requireController().createWorkspace(path) }
                .onSuccess { created -> applyCreatedWorkspace(created) }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun createDirectory(path: String, name: String) {
        viewModelScope.launch {
            runCatching { requireController().createDirectory(path, name) }
                .onSuccess { created -> browse(created.path) }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun browse(path: String? = null) {
        viewModelScope.launch {
            runCatching { requireController().listDirectory(path) }
                .onSuccess { listing -> _uiState.update { it.copy(directory = listing, error = null) } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    fun loadSubagents(parentSessionId: String) {
        viewModelScope.launch {
            runCatching { requireController().subagentList(parentSessionId) }
                .onSuccess { catalog -> _uiState.update { it.copy(subagents = catalog, error = null) } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message) } }
        }
    }

    private suspend fun bind() {
        val server = serverRepository.getServer(serverId)
        if (server == null || server.type != ServerType.DSH) {
            _uiState.value = DshHostSurfacesUiState(isDsh = false)
            return
        }
        val connection = DshConnection.from(server.url)
        val catalog = dshHostSurfaceCatalog(connection)
        controller = DshHostSurfaceController(api, connection, catalog)
        _uiState.value = DshHostSurfacesUiState(isDsh = true, catalog = catalog, isLoading = true)
        loadAll()
    }

    private suspend fun loadAll() {
        val host = controller ?: return
        val catalog = host.catalog()
        _uiState.update { it.copy(isLoading = true, error = null) }
        runCatching {
            val workspaces = if (catalog.canManageWorkspaces) host.listWorkspaces() else null
            val directory = if (catalog.canBrowseHost) host.listDirectory() else null
            val skills = if (catalog.can("skill.catalog")) host.skillCatalog() else null
            val git = if (catalog.canDescribeGit) runCatching { host.gitDescribe() }.getOrNull() else null
            val presets = if (catalog.canListPresets) host.agentPresetList() else null
            val automation = if (catalog.canManageAutomation) host.automationList() else null
            val settings = if (catalog.can("settings.describe")) host.settingsDescribe() else null
            val providers = if (catalog.can("llm.providers")) host.llmProviders() else null
            val models = if (catalog.can("llm.models")) host.llmModels() else null
            val systemPrompt = if (catalog.canListSystemPrompt) host.systemPromptList() else null
            DshHostSurfacesUiState(
                isDsh = true,
                catalog = catalog,
                isLoading = false,
                workspaces = workspaces,
                directory = directory,
                skills = skills,
                git = git,
                presets = presets,
                automation = automation,
                settings = settings,
                providers = providers,
                models = models,
                systemPrompt = systemPrompt,
                subagents = _uiState.value.subagents,
            )
        }.onSuccess { next ->
            _uiState.value = next
        }.onFailure { error ->
            _uiState.update { it.copy(isLoading = false, error = error.message) }
        }
    }

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
                error = null,
            )
        }
    }

    private fun requireController(): DshHostSurfaceController =
        controller ?: error("DSH host surfaces are not bound")
}
