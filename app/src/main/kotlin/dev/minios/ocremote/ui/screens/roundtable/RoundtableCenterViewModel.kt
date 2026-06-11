package dev.minios.ocremote.ui.screens.roundtable

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.R
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PiCastingDto
import dev.minios.ocremote.data.api.PiCatalogEntryDto
import dev.minios.ocremote.data.api.PiCommandRequest
import dev.minios.ocremote.data.api.PiConnection
import dev.minios.ocremote.data.api.PiCreateRoundtableRequest
import dev.minios.ocremote.data.api.PiLineupProposalRequest
import dev.minios.ocremote.data.api.PiModelRefDto
import dev.minios.ocremote.data.api.PiPersonaDto
import dev.minios.ocremote.data.api.PiRoundLimitsDto
import dev.minios.ocremote.data.api.PiRoundtableDto
import dev.minios.ocremote.data.api.PiSpeakerPolicyDto
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Roundtable
import dev.minios.ocremote.domain.model.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

enum class RoundtableSort(val wireName: String) {
    LastActivity("last_activity"),
    Created("created"),
    Topic("topic"),
}

enum class RoundtableFilter(val wireName: String) {
    Active("active"),
    Running("running"),
    Archived("archived"),
    All("all"),
}

enum class NewRoundtableStep {
    Topic,
    Review,
}

enum class RoundtableCadence(val wireName: String) {
    ModeratorRouted("moderator_routed"),
    RoundRobin("round_robin"),
    FreeRoundtable("free_roundtable"),
    MentionReactive("mention_reactive"),
}

data class RoundtableCenterUiState(
    val serverName: String = "",
    val serverUrl: String = "",
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val error: String? = null,
    val items: List<Roundtable> = emptyList(),
    val sort: RoundtableSort = RoundtableSort.LastActivity,
    val filter: RoundtableFilter = RoundtableFilter.Active,
    val configEditor: RoundtableConfigEditorState? = null,
) {
    val runningCount: Int = items.count { it.status == Roundtable.Status.Running }
}

data class RoundtableChatTarget(
    val serverUrl: String,
    val token: String,
    val serverName: String,
    val serverId: String,
    val roundtableId: String,
)

data class RoundtableConfigEditorState(
    val topic: String = "",
    val step: NewRoundtableStep = NewRoundtableStep.Topic,
    val roles: List<RoleConfigEditorState> = emptyList(),
    val personas: List<PiPersonaDto> = emptyList(),
    val catalog: List<PiCatalogEntryDto> = emptyList(),
    val templates: List<LineupTemplateState> = emptyList(),
    val selectedTemplateId: String? = null,
    val cadence: RoundtableCadence = RoundtableCadence.ModeratorRouted,
    val maxTurnsPerRound: Int = 6,
    val error: String? = null,
    val isLoadingCatalog: Boolean = false,
    val isProposing: Boolean = false,
) {
    fun validationErrors(context: Context): List<String> = roles.flatMap { role -> role.validationErrors(context, catalog) }
}

data class RoleConfigEditorState(
    val roleId: String,
    val name: String,
    val mbti: String,
    val stancePrompt: String,
    val style: String,
    val actionTagPrefs: List<String>,
    val provider: String,
    val model: String,
    val fallback: List<PiModelRefDto> = emptyList(),
    val enabled: Boolean = true,
    val reason: String? = null,
) {
    fun toPersonaDto(): PiPersonaDto = PiPersonaDto(
        id = roleId,
        name = name,
        mbti = mbti,
        stancePrompt = stancePrompt,
        style = style,
        actionTagPrefs = actionTagPrefs,
        provider = provider,
        model = model,
        fallback = fallback,
        enabled = enabled,
    )
}

data class LineupTemplateState(
    val id: String,
    val name: String,
    val roles: List<RoleConfigEditorState>,
    val cadence: RoundtableCadence,
    val maxTurnsPerRound: Int,
)

@Serializable
private data class StoredLineupTemplate(
    val id: String,
    val name: String,
    val roles: List<StoredRoleConfig>,
    val cadence: String,
    val maxTurnsPerRound: Int,
)

@Serializable
private data class StoredRoleConfig(
    val roleId: String,
    val name: String,
    val mbti: String,
    val stancePrompt: String,
    val style: String,
    val actionTagPrefs: List<String>,
    val provider: String,
    val model: String,
    val fallback: List<PiModelRefDto> = emptyList(),
    val enabled: Boolean = true,
    val reason: String? = null,
)

@HiltViewModel
class RoundtableCenterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val api: PiApi,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val serverId: String = decodeRouteArg(savedStateHandle.get<String>("serverId"))
    private val _serverName = MutableStateFlow(context.getString(R.string.roundtable_default_name))
    private val _serverUrl = MutableStateFlow("")
    private var resolvedServer: ServerConfig? = null
    private val templateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val _roundtables = MutableStateFlow<List<Roundtable>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    private val _isMutating = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _configEditor = MutableStateFlow<RoundtableConfigEditorState?>(null)
    private val _lineupTemplates = MutableStateFlow<List<LineupTemplateState>>(emptyList())

    private val sort = settingsRepository.roundtableSort(serverId).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RoundtableSort.LastActivity.wireName,
    )
    private val filter = settingsRepository.roundtableFilter(serverId).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RoundtableFilter.Active.wireName,
    )
    private val storedTemplates = settingsRepository.roundtableLineupTemplates(serverId).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        "[]",
    )

    private val loadingState = combine(_serverName, _serverUrl, _isLoading, _isMutating, _error) { serverName, serverUrl, loading, mutating, error ->
        RoundtableCenterLoadingState(serverName, serverUrl, loading, mutating, error)
    }
    private val secondaryState = combine(loadingState, sort, filter) { loading, sortWire, filterWire ->
        RoundtableCenterSecondaryState(loading.serverName, loading.serverUrl, loading.loading, loading.mutating, loading.error, sortWire, filterWire)
    }

    val uiState: StateFlow<RoundtableCenterUiState> = combine(_roundtables, secondaryState, _configEditor, _lineupTemplates) { roundtables, secondary, configEditor, templates ->
        val selectedSort = secondary.sortWire.toRoundtableSort()
        val selectedFilter = secondary.filterWire.toRoundtableFilter()
        RoundtableCenterUiState(
            serverName = secondary.serverName,
            serverUrl = secondary.serverUrl,
            isLoading = secondary.loading,
            isMutating = secondary.mutating,
            error = secondary.error,
            sort = selectedSort,
            filter = selectedFilter,
            items = roundtables
                .filter { it.matches(selectedFilter) }
                .sortedWith(selectedSort.comparator()),
            configEditor = configEditor?.copy(templates = templates),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RoundtableCenterUiState(serverName = context.getString(R.string.roundtable_default_name)),
    )

    init {
        viewModelScope.launch {
            storedTemplates.collect { templatesWire -> _lineupTemplates.value = decodeTemplates(templatesWire) }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val conn = resolveConnection()
                _roundtables.value = loadRoundtableRegistry(conn)
            } catch (error: Exception) {
                _error.value = error.message ?: context.getString(R.string.roundtable_error_load_roundtables)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createRoundtable() {
        _configEditor.value = RoundtableConfigEditorState(
            topic = "",
            step = NewRoundtableStep.Topic,
            templates = _lineupTemplates.value,
            isLoadingCatalog = true,
        )
        viewModelScope.launch {
            try {
                val conn = resolveConnection()
                val personas = api.listPersonas(conn).filter { it.enabled }
                val catalog = api.listCatalog(conn)
                _configEditor.value = _configEditor.value?.copy(
                    personas = personas,
                    catalog = catalog,
                    isLoadingCatalog = false,
                )
            } catch (error: Exception) {
                _configEditor.value = _configEditor.value?.copy(
                    isLoadingCatalog = false,
                    error = error.message ?: context.getString(R.string.roundtable_error_load_setup),
                )
            }
        }
    }

    fun dismissConfigEditor() {
        _configEditor.value = null
    }

    fun updateConfigTopic(value: String) {
        _configEditor.update { editor -> editor?.copy(topic = value, error = null) }
    }

    fun proposeLineup() {
        val editor = _configEditor.value ?: return
        if (editor.topic.isBlank()) {
            _configEditor.value = editor.copy(error = context.getString(R.string.roundtable_error_topic_required))
            return
        }
        _configEditor.value = editor.copy(isProposing = true, error = null)
        viewModelScope.launch {
            try {
                val conn = resolveConnection()
                val proposal = api.proposeLineup(conn, PiLineupProposalRequest(topic = editor.topic.trim()))
                val roles = proposal.items.map { item -> item.persona.toRoleConfigEditor(item.reason) }
                _configEditor.value = _configEditor.value?.copy(
                    topic = proposal.topic,
                    step = NewRoundtableStep.Review,
                    roles = roles,
                    cadence = proposal.speakerPolicy.mode.toRoundtableCadence(),
                    isProposing = false,
                    error = null,
                )
            } catch (error: Exception) {
                _configEditor.value = _configEditor.value?.copy(
                    isProposing = false,
                    error = error.message ?: context.getString(R.string.roundtable_error_propose_lineup),
                )
            }
        }
    }

    fun useSuggestionDirectly() {
        val editor = _configEditor.value ?: return
        if (editor.roles.isEmpty() || editor.step != NewRoundtableStep.Review) {
            _configEditor.value = editor.copy(error = context.getString(R.string.roundtable_error_review_lineup_first))
            return
        }
        saveConfigEditor()
    }

    fun swapRole(roleId: String, personaId: String) {
        _configEditor.update { editor ->
            val persona = editor?.personas?.firstOrNull { it.id == personaId } ?: return@update editor
            editor.copy(
                error = null,
                roles = editor.roles.map { role -> if (role.roleId == roleId) persona.toRoleConfigEditor(context.getString(R.string.roundtable_swapped_from_library)) else role },
            )
        }
    }

    fun updateRoleProvider(roleId: String, providerId: String) {
        _configEditor.update { editor ->
            editor?.copy(
                error = null,
                roles = editor.roles.map { role ->
                    if (role.roleId != roleId) return@map role
                    val provider = editor.catalog.firstOrNull { it.providerId == providerId }
                    val model = provider?.models?.firstOrNull { it.enabled }?.id ?: role.model
                    role.copy(provider = providerId, model = model, fallback = provider?.fallback ?: emptyList())
                },
            )
        }
    }

    fun updateRoleModel(roleId: String, model: String) {
        _configEditor.update { editor ->
            editor?.copy(error = null, roles = editor.roles.map { role -> if (role.roleId == roleId) role.copy(model = model) else role })
        }
    }

    fun updateCadence(cadence: RoundtableCadence) {
        _configEditor.update { editor -> editor?.copy(cadence = cadence, error = null) }
    }

    fun updateMaxTurnsPerRound(value: Int) {
        _configEditor.update { editor -> editor?.copy(maxTurnsPerRound = value.coerceIn(3, 12), error = null) }
    }

    fun addRoleFallback(roleId: String, providerId: String, model: String) {
        _configEditor.update { editor ->
            editor?.copy(
                error = null,
                roles = editor.roles.map { role ->
                    if (role.roleId == roleId) role.copy(fallback = role.fallback + PiModelRefDto(providerId, model)) else role
                },
            )
        }
    }

    fun removeRoleFallback(roleId: String, index: Int) {
        _configEditor.update { editor ->
            editor?.copy(
                error = null,
                roles = editor.roles.map { role ->
                    if (role.roleId == roleId) role.copy(fallback = role.fallback.filterIndexed { itemIndex, _ -> itemIndex != index }) else role
                },
            )
        }
    }

    fun moveRoleFallback(roleId: String, fromIndex: Int, toIndex: Int) {
        _configEditor.update { editor ->
            editor?.copy(
                error = null,
                roles = editor.roles.map { role ->
                    if (role.roleId != roleId) return@map role
                    val mutable = role.fallback.toMutableList()
                    if (fromIndex !in mutable.indices || toIndex !in mutable.indices) return@map role
                    val item = mutable.removeAt(fromIndex)
                    mutable.add(toIndex, item)
                    role.copy(fallback = mutable)
                },
            )
        }
    }

    fun saveLineupTemplate() {
        val editor = _configEditor.value ?: return
        if (editor.roles.size !in 3..5 || editor.validationErrors(context).isNotEmpty()) {
            _configEditor.value = editor.copy(error = context.getString(R.string.roundtable_error_template_needs_lineup))
            return
        }
        viewModelScope.launch {
            val current = _lineupTemplates.value
            val template = LineupTemplateState(
                id = UUID.randomUUID().toString(),
                name = editor.topic.trim().ifBlank { context.getString(R.string.roundtable_template_default_name, current.size + 1) },
                roles = editor.roles,
                cadence = editor.cadence,
                maxTurnsPerRound = editor.maxTurnsPerRound,
            )
            val updated = (current + template).takeLast(20)
            _lineupTemplates.value = updated
            settingsRepository.setRoundtableLineupTemplates(serverId, encodeTemplates(updated))
            _configEditor.update { it?.copy(selectedTemplateId = template.id, error = null) }
        }
    }

    fun applyTemplate(templateId: String) {
        val template = _lineupTemplates.value.firstOrNull { it.id == templateId } ?: return
        _configEditor.update { editor ->
            val reconciledRoles = editor?.let { activeEditor ->
                template.roles.map { savedRole ->
                    val activePersona = activeEditor.personas.firstOrNull { it.id == savedRole.roleId }
                    activePersona?.toRoleConfigEditor(savedRole.reason)?.copy(
                        model = savedRole.model,
                        enabled = savedRole.enabled,
                    ) ?: savedRole
                }
            } ?: template.roles
            editor?.copy(
                selectedTemplateId = template.id,
                step = NewRoundtableStep.Review,
                roles = reconciledRoles,
                cadence = template.cadence,
                maxTurnsPerRound = template.maxTurnsPerRound,
                error = null,
            )
        }
    }

    fun saveConfigEditor() {
        val editor = _configEditor.value ?: return
        val errors = editor.validationErrors(context)
        val sizeError = context.getString(R.string.roundtable_error_choose_personas).takeIf { editor.roles.size !in 3..5 }
        if (editor.topic.isBlank() || sizeError != null || errors.isNotEmpty()) {
            _configEditor.value = editor.copy(error = (listOfNotNull(context.getString(R.string.roundtable_error_topic_required).takeIf { editor.topic.isBlank() }, sizeError) + errors).first())
            return
        }
        mutate { conn ->
            api.createRoundtable(
                conn,
                PiCreateRoundtableRequest(
                    topic = editor.topic.trim(),
                    roster = editor.roles.map { it.toPersonaDto() },
                    limits = PiRoundLimitsDto(maxTurnsPerRound = editor.maxTurnsPerRound),
                    speakerPolicy = PiSpeakerPolicyDto(mode = editor.cadence.wireName),
                ),
            )
        }
    }

    fun resumeRoundtable(roundtableId: String) {
        val roundtable = _roundtables.value.firstOrNull { it.id == roundtableId }
        if (roundtable?.status != Roundtable.Status.AwaitingCommand) {
            _error.value = context.getString(R.string.roundtable_error_resume_unavailable)
            return
        }
        mutate { conn ->
            api.sendCommand(
                conn = conn,
                roundId = roundtableId,
                command = PiCommandRequest(roundId = roundtableId, command = "可", note = context.getString(R.string.roundtable_resume_note)),
            )
        }
    }

    fun liveChatTarget(roundtableId: String): RoundtableChatTarget? {
        val server = resolvedServer ?: return null
        return RoundtableChatTarget(
            serverUrl = server.url,
            token = server.token.orEmpty(),
            serverName = server.displayName.ifBlank { context.getString(R.string.roundtable_default_name) },
            serverId = server.id,
            roundtableId = roundtableId,
        )
    }

    fun castingId(roundtableId: String): String? = _roundtables.value.firstOrNull { roundtable ->
        roundtable.id == roundtableId && roundtable.kind == Roundtable.Kind.Casting
    }?.sourceId

    fun archiveRoundtable(roundtableId: String) {
        mutate { conn -> api.archiveRoundtable(conn, roundtableId) }
    }

    fun deleteRoundtable(roundtableId: String) {
        mutate { conn -> api.deleteRoundtable(conn, roundtableId) }
    }

    fun duplicateAsTemplate(roundtable: Roundtable) {
        mutate { conn ->
            val baseTopic = roundtable.topic?.takeIf { it.isNotBlank() } ?: context.getString(R.string.roundtable_default_topic)
            api.createRoundtable(
                conn,
                PiCreateRoundtableRequest(
                    topic = context.getString(R.string.roundtable_copy_suffix, baseTopic),
                    templateOf = roundtable.id,
                ),
            )
        }
    }

    fun setSort(sort: RoundtableSort) {
        viewModelScope.launch { settingsRepository.setRoundtableSort(serverId, sort.wireName) }
    }

    fun setFilter(filter: RoundtableFilter) {
        viewModelScope.launch { settingsRepository.setRoundtableFilter(serverId, filter.wireName) }
    }

    private fun mutate(block: suspend (PiConnection) -> Unit) {
        viewModelScope.launch {
            _isMutating.value = true
            _error.value = null
            try {
                val conn = resolveConnection()
                block(conn)
                _roundtables.value = loadRoundtableRegistry(conn)
                _configEditor.value = null
            } catch (error: Exception) {
                _error.value = error.message ?: context.getString(R.string.roundtable_error_action_failed)
            } finally {
                _isMutating.value = false
                _isLoading.value = false
            }
        }
    }

    private suspend fun resolveConnection(): PiConnection {
        val server = serverRepository.getServer(serverId) ?: error(context.getString(R.string.roundtable_error_saved_server_missing))
        resolvedServer = server
        _serverName.value = server.displayName.ifBlank { context.getString(R.string.roundtable_default_name) }
        _serverUrl.value = server.url
        return PiConnection.from(server.url, server.token)
    }

    private suspend fun loadRoundtableRegistry(conn: PiConnection): List<Roundtable> {
        val castings = runCatching { api.listCastings(conn).map { dto -> dto.toDomain() } }.getOrDefault(emptyList())
        val roundtables = api.listRoundtables(conn).mapNotNull { dto -> dto.toDomain() }
        return castings + roundtables
    }

    private fun decodeTemplates(raw: String): List<LineupTemplateState> = runCatching {
        templateJson.decodeFromString(ListSerializer(StoredLineupTemplate.serializer()), raw).map { stored ->
            LineupTemplateState(
                id = stored.id,
                name = stored.name,
                roles = stored.roles.map { it.toState() },
                cadence = stored.cadence.toRoundtableCadence(),
                maxTurnsPerRound = stored.maxTurnsPerRound.coerceIn(3, 12),
            )
        }
    }.getOrDefault(emptyList())

    private fun encodeTemplates(templates: List<LineupTemplateState>): String = templateJson.encodeToString(
        ListSerializer(StoredLineupTemplate.serializer()),
        templates.map { it.toStored() },
    )
}

private data class RoundtableCenterLoadingState(
    val serverName: String,
    val serverUrl: String,
    val loading: Boolean,
    val mutating: Boolean,
    val error: String?,
)

private data class RoundtableCenterSecondaryState(
    val serverName: String,
    val serverUrl: String,
    val loading: Boolean,
    val mutating: Boolean,
    val error: String?,
    val sortWire: String,
    val filterWire: String,
)

internal fun RoleConfigEditorState.validationErrors(context: Context, catalog: List<PiCatalogEntryDto>): List<String> {
    val providerEntry = catalog.firstOrNull { it.providerId == provider }
    val errors = mutableListOf<String>()
    if (providerEntry == null) {
        errors += context.getString(R.string.roundtable_error_unknown_provider, name, provider)
    } else {
        if (!providerEntry.baseUrl.isHttpUrl()) errors += context.getString(R.string.roundtable_error_bad_base_url, name, providerEntry.displayName)
        if (!providerEntry.enabled || providerEntry.validation.status == "disabled") errors += context.getString(R.string.roundtable_error_provider_disabled, name, providerEntry.displayName)
        if (providerEntry.validation.status == "invalid") errors += context.getString(R.string.roundtable_error_provider_invalid, name, providerEntry.displayName, providerEntry.validation.message ?: context.getString(R.string.roundtable_error_validation_failed))
        val modelEntry = providerEntry.models.firstOrNull { it.id == model }
        if (modelEntry == null) errors += context.getString(R.string.roundtable_error_unknown_model, name, model)
        else if (!modelEntry.enabled) errors += context.getString(R.string.roundtable_error_model_disabled, name, modelEntry.displayName)
    }
    fallback.forEachIndexed { index, ref ->
        val fallbackProvider = catalog.firstOrNull { it.providerId == ref.providerId }
        if (fallbackProvider == null) {
            errors += context.getString(R.string.roundtable_error_fallback_unknown_provider, name, index + 1, ref.providerId)
        } else if (!fallbackProvider.enabled || fallbackProvider.validation.status == "disabled") {
            errors += context.getString(R.string.roundtable_error_fallback_provider_disabled, name, index + 1, fallbackProvider.displayName)
        } else if (fallbackProvider.models.none { it.id == ref.model && it.enabled }) {
            errors += context.getString(R.string.roundtable_error_fallback_unknown_model, name, index + 1, ref.model)
        }
    }
    return errors
}

private fun String.isHttpUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme == "http" || uri.scheme == "https"
}.getOrDefault(false)

private fun PiPersonaDto.toRoleConfigEditor(reason: String? = null): RoleConfigEditorState = RoleConfigEditorState(
    roleId = id.orEmpty().ifBlank { name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-') },
    name = name,
    mbti = mbti,
    stancePrompt = stancePrompt,
    style = style,
    actionTagPrefs = actionTagPrefs,
    provider = provider,
    model = model,
    fallback = fallback,
    enabled = enabled,
    reason = reason,
)

private fun StoredRoleConfig.toState(): RoleConfigEditorState = RoleConfigEditorState(
    roleId = roleId,
    name = name,
    mbti = mbti,
    stancePrompt = stancePrompt,
    style = style,
    actionTagPrefs = actionTagPrefs,
    provider = provider,
    model = model,
    fallback = fallback,
    enabled = enabled,
    reason = reason,
)

private fun LineupTemplateState.toStored(): StoredLineupTemplate = StoredLineupTemplate(
    id = id,
    name = name,
    roles = roles.map { role ->
        StoredRoleConfig(
            roleId = role.roleId,
            name = role.name,
            mbti = role.mbti,
            stancePrompt = role.stancePrompt,
            style = role.style,
            actionTagPrefs = role.actionTagPrefs,
            provider = role.provider,
            model = role.model,
            fallback = role.fallback,
            enabled = role.enabled,
            reason = role.reason,
        )
    },
    cadence = cadence.wireName,
    maxTurnsPerRound = maxTurnsPerRound,
)

private fun PiRoundtableDto.toDomain(): Roundtable? {
    val roundtableId = id ?: roundId ?: return null
    return Roundtable(
        id = roundtableId,
        sourceId = roundtableId,
        kind = Roundtable.Kind.Roundtable,
        topic = topic ?: title ?: roundTitle,
        status = status.toDomainStatus(),
        roundCount = roundCount,
        rosterSummary = roster.joinToString(", ") { role -> role.name },
        roster = roster.map { role ->
            Roundtable.RoleSummary(
                id = role.id,
                name = role.name,
                role = role.role,
                colorSeed = role.colorSeed,
            )
        },
        time = Roundtable.Time(
            created = createdAt,
            updated = updatedAt,
            completed = archivedAt,
        ),
    )
}

private fun PiCastingDto.toDomain(): Roundtable = Roundtable(
    id = "casting:$id",
    sourceId = id,
    kind = Roundtable.Kind.Casting,
    topic = topic,
    status = Roundtable.Status.Casting,
    roundCount = 0,
    rosterSummary = proposal.items.joinToString(", ") { item -> item.persona.name },
    roster = proposal.items.map { item ->
        Roundtable.RoleSummary(
            id = item.persona.id.orEmpty().ifBlank { item.persona.name },
            name = item.persona.name,
            role = "persona",
            colorSeed = item.persona.provider,
        )
    },
    time = Roundtable.Time(
        created = createdAt,
        updated = updatedAt,
    ),
)

private fun String?.toDomainStatus(): Roundtable.Status = when (this?.lowercase()) {
    "running" -> Roundtable.Status.Running
    "paused", "awaiting_command" -> Roundtable.Status.AwaitingCommand
    "awaiting", "awaiting_skip" -> Roundtable.Status.AwaitingSkip
    "ended", "completed", "cancelled" -> Roundtable.Status.Completed
    "archived" -> Roundtable.Status.Archived
    "error" -> Roundtable.Status.Error
    else -> Roundtable.Status.Unknown
}

private fun Roundtable.matches(filter: RoundtableFilter): Boolean = when (filter) {
    RoundtableFilter.Active -> status != Roundtable.Status.Archived
    RoundtableFilter.Running -> status == Roundtable.Status.Running || status == Roundtable.Status.AwaitingCommand || status == Roundtable.Status.AwaitingSkip || status == Roundtable.Status.Casting
    RoundtableFilter.Archived -> status == Roundtable.Status.Archived
    RoundtableFilter.All -> true
}

internal fun Roundtable.Status.isActiveRoundtableStatus(): Boolean = when (this) {
    Roundtable.Status.Archived -> false
    else -> true
}

private fun RoundtableSort.comparator(): Comparator<Roundtable> = when (this) {
    RoundtableSort.LastActivity -> compareByDescending<Roundtable> { it.time.updated ?: "" }.thenBy { it.topic.orEmpty().lowercase() }
    RoundtableSort.Created -> compareByDescending<Roundtable> { it.time.created ?: "" }.thenBy { it.topic.orEmpty().lowercase() }
    RoundtableSort.Topic -> compareBy<Roundtable> { it.topic.orEmpty().lowercase() }.thenByDescending { it.time.updated ?: "" }
}

private fun String.toRoundtableSort(): RoundtableSort = RoundtableSort.entries.firstOrNull { it.wireName == this } ?: RoundtableSort.LastActivity

private fun String.toRoundtableFilter(): RoundtableFilter = RoundtableFilter.entries.firstOrNull { it.wireName == this } ?: RoundtableFilter.Active

private fun String.toRoundtableCadence(): RoundtableCadence = RoundtableCadence.entries.firstOrNull { it.wireName == this } ?: RoundtableCadence.ModeratorRouted

private fun decodeRouteArg(value: String?): String {
    val raw = value.orEmpty()
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}

internal fun formatRoundtableActivity(context: Context, value: String?): String {
    if (value.isNullOrBlank()) return context.getString(R.string.roundtable_activity_none)
    return runCatching {
        OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern(context.getString(R.string.roundtable_activity_time_pattern)))
    }.getOrDefault(value)
}
