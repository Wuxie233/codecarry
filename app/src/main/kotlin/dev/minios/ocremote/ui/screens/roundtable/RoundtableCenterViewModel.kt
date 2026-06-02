package dev.minios.ocremote.ui.screens.roundtable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PiCatalogEntryDto
import dev.minios.ocremote.data.api.PiCommandRequest
import dev.minios.ocremote.data.api.PiConnection
import dev.minios.ocremote.data.api.PiCreateRoundtableRequest
import dev.minios.ocremote.data.api.PiModelRefDto
import dev.minios.ocremote.data.api.PiPersonaDto
import dev.minios.ocremote.data.api.PiRoundtableDto
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Roundtable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
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

data class RoundtableCenterUiState(
    val serverName: String = "Roundtable",
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

data class RoundtableConfigEditorState(
    val topic: String = "Roundtable topic 1",
    val roles: List<RoleConfigEditorState> = emptyList(),
    val catalog: List<PiCatalogEntryDto> = emptyList(),
    val error: String? = null,
    val isLoadingCatalog: Boolean = false,
) {
    val validationErrors: List<String>
        get() = roles.flatMap { role -> role.validationErrors(catalog) }
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

@HiltViewModel
class RoundtableCenterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: PiApi,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val serverUrl: String = decodeRouteArg(savedStateHandle.get<String>("serverUrl"))
    val serverName: String = decodeRouteArg(savedStateHandle.get<String>("serverName")).ifBlank { "Roundtable" }
    private val serverId: String = decodeRouteArg(savedStateHandle.get<String>("serverId"))
    private val token: String = decodeRouteArg(savedStateHandle.get<String>("token"))
    private val conn = PiConnection.from(serverUrl, token.ifBlank { null })

    private val _roundtables = MutableStateFlow<List<Roundtable>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    private val _isMutating = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _configEditor = MutableStateFlow<RoundtableConfigEditorState?>(null)

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

    private val secondaryState = combine(_isLoading, _isMutating, _error, sort, filter) { loading, mutating, error, sortWire, filterWire ->
        RoundtableCenterSecondaryState(loading, mutating, error, sortWire, filterWire)
    }

    val uiState: StateFlow<RoundtableCenterUiState> = combine(_roundtables, secondaryState, _configEditor) { roundtables, secondary, configEditor ->
        val selectedSort = secondary.sortWire.toRoundtableSort()
        val selectedFilter = secondary.filterWire.toRoundtableFilter()
        RoundtableCenterUiState(
            serverName = serverName,
            isLoading = secondary.loading,
            isMutating = secondary.mutating,
            error = secondary.error,
            sort = selectedSort,
            filter = selectedFilter,
            items = roundtables
                .filter { it.matches(selectedFilter) }
                .sortedWith(selectedSort.comparator()),
            configEditor = configEditor,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RoundtableCenterUiState(serverName = serverName),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _roundtables.value = api.listRoundtables(conn).mapNotNull { dto -> dto.toDomain() }
            } catch (error: Exception) {
                _error.value = error.message ?: "Failed to load roundtables"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createRoundtable() {
        val nextIndex = _roundtables.value.size + 1
        _configEditor.value = RoundtableConfigEditorState(
            topic = "Roundtable topic $nextIndex",
            isLoadingCatalog = true,
        )
        viewModelScope.launch {
            try {
                val personas = api.listPersonas(conn).filter { it.enabled }.take(3)
                val catalog = api.listCatalog(conn)
                _configEditor.value = RoundtableConfigEditorState(
                    topic = "Roundtable topic $nextIndex",
                    roles = personas.map { it.toRoleConfigEditor() },
                    catalog = catalog,
                )
            } catch (error: Exception) {
                _configEditor.value = _configEditor.value?.copy(
                    isLoadingCatalog = false,
                    error = error.message ?: "Failed to load provider catalog",
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

    fun saveConfigEditor() {
        val editor = _configEditor.value ?: return
        val errors = editor.validationErrors
        if (editor.topic.isBlank() || errors.isNotEmpty()) {
            _configEditor.value = editor.copy(error = (listOfNotNull("Topic is required".takeIf { editor.topic.isBlank() }) + errors).first())
            return
        }
        mutate {
            api.createRoundtable(
                conn,
                PiCreateRoundtableRequest(
                    topic = editor.topic.trim(),
                    roster = editor.roles.map { it.toPersonaDto() },
                ),
            )
        }
    }

    fun resumeRoundtable(roundtableId: String) {
        mutate {
            api.sendCommand(
                conn = conn,
                roundId = roundtableId,
                command = PiCommandRequest(roundId = roundtableId, command = "可", note = "resume from Roundtable Center"),
            )
        }
    }

    fun archiveRoundtable(roundtableId: String) {
        mutate { api.archiveRoundtable(conn, roundtableId) }
    }

    fun deleteRoundtable(roundtableId: String) {
        mutate { api.deleteRoundtable(conn, roundtableId) }
    }

    fun duplicateAsTemplate(roundtable: Roundtable) {
        mutate {
            val baseTopic = roundtable.topic?.takeIf { it.isNotBlank() } ?: "Roundtable topic"
            api.createRoundtable(
                conn,
                PiCreateRoundtableRequest(
                    topic = "$baseTopic (copy)",
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

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            _isMutating.value = true
            _error.value = null
            try {
                block()
                _roundtables.value = api.listRoundtables(conn).mapNotNull { dto -> dto.toDomain() }
                _configEditor.value = null
            } catch (error: Exception) {
                _error.value = error.message ?: "Roundtable action failed"
            } finally {
                _isMutating.value = false
                _isLoading.value = false
            }
        }
    }
}

private data class RoundtableCenterSecondaryState(
    val loading: Boolean,
    val mutating: Boolean,
    val error: String?,
    val sortWire: String,
    val filterWire: String,
)

internal fun RoleConfigEditorState.validationErrors(catalog: List<PiCatalogEntryDto>): List<String> {
    val providerEntry = catalog.firstOrNull { it.providerId == provider }
    val errors = mutableListOf<String>()
    if (providerEntry == null) {
        errors += "$name uses unknown provider $provider"
    } else {
        if (!providerEntry.baseUrl.isHttpUrl()) errors += "$name provider ${providerEntry.displayName} has a bad baseUrl"
        if (!providerEntry.enabled || providerEntry.validation.status == "disabled") errors += "$name provider ${providerEntry.displayName} is disabled"
        if (providerEntry.validation.status == "invalid") errors += "$name provider ${providerEntry.displayName}: ${providerEntry.validation.message ?: "validation failed"}"
        val modelEntry = providerEntry.models.firstOrNull { it.id == model }
        if (modelEntry == null) errors += "$name uses unknown model $model"
        else if (!modelEntry.enabled) errors += "$name model ${modelEntry.displayName} is disabled"
    }
    fallback.forEachIndexed { index, ref ->
        val fallbackProvider = catalog.firstOrNull { it.providerId == ref.providerId }
        if (fallbackProvider == null) {
            errors += "$name fallback ${index + 1} uses unknown provider ${ref.providerId}"
        } else if (!fallbackProvider.enabled || fallbackProvider.validation.status == "disabled") {
            errors += "$name fallback ${index + 1} provider ${fallbackProvider.displayName} is disabled"
        } else if (fallbackProvider.models.none { it.id == ref.model && it.enabled }) {
            errors += "$name fallback ${index + 1} uses unknown model ${ref.model}"
        }
    }
    return errors
}

private fun String.isHttpUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme == "http" || uri.scheme == "https"
}.getOrDefault(false)

private fun PiPersonaDto.toRoleConfigEditor(): RoleConfigEditorState = RoleConfigEditorState(
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
)

private fun PiRoundtableDto.toDomain(): Roundtable? {
    val roundtableId = id ?: roundId ?: return null
    return Roundtable(
        id = roundtableId,
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

private fun String?.toDomainStatus(): Roundtable.Status = when (this?.lowercase()) {
    "running" -> Roundtable.Status.Running
    "paused", "awaiting", "awaiting_command" -> Roundtable.Status.Paused
    "awaiting_skip" -> Roundtable.Status.AwaitingSkip
    "ended", "completed", "cancelled" -> Roundtable.Status.Completed
    "archived" -> Roundtable.Status.Archived
    "error" -> Roundtable.Status.Error
    else -> Roundtable.Status.Unknown
}

private fun Roundtable.matches(filter: RoundtableFilter): Boolean = when (filter) {
    RoundtableFilter.Active -> status != Roundtable.Status.Archived
    RoundtableFilter.Running -> status == Roundtable.Status.Running
    RoundtableFilter.Archived -> status == Roundtable.Status.Archived
    RoundtableFilter.All -> true
}

private fun RoundtableSort.comparator(): Comparator<Roundtable> = when (this) {
    RoundtableSort.LastActivity -> compareByDescending<Roundtable> { it.time.updated ?: "" }.thenBy { it.topic.orEmpty().lowercase() }
    RoundtableSort.Created -> compareByDescending<Roundtable> { it.time.created ?: "" }.thenBy { it.topic.orEmpty().lowercase() }
    RoundtableSort.Topic -> compareBy<Roundtable> { it.topic.orEmpty().lowercase() }.thenByDescending { it.time.updated ?: "" }
}

private fun String.toRoundtableSort(): RoundtableSort = RoundtableSort.entries.firstOrNull { it.wireName == this } ?: RoundtableSort.LastActivity

private fun String.toRoundtableFilter(): RoundtableFilter = RoundtableFilter.entries.firstOrNull { it.wireName == this } ?: RoundtableFilter.Active

private fun decodeRouteArg(value: String?): String {
    val raw = value.orEmpty()
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}

internal fun formatRoundtableActivity(value: String?): String {
    if (value.isNullOrBlank()) return "No activity yet"
    return runCatching {
        OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    }.getOrDefault(value)
}
