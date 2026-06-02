package dev.minios.ocremote.ui.screens.roundtable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.PI_PROTOCOL_VERSION
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.PiConnection
import dev.minios.ocremote.data.api.PiGeneratePersonaRequest
import dev.minios.ocremote.data.api.PiModelRefDto
import dev.minios.ocremote.data.api.PiPersonaDto
import dev.minios.ocremote.data.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URLDecoder
import javax.inject.Inject

private val MBTI_OPTIONS = listOf("INTJ", "INTP", "ENTJ", "ENTP", "INFJ", "INFP", "ENFJ", "ENFP", "ISTJ", "ISFJ", "ESTJ", "ESFJ", "ISTP", "ISFP", "ESTP", "ESFP", "N/A")
private val ACTION_TAG_OPTIONS = listOf("陈述", "质疑", "补充", "反驳", "修正", "综合")

val personaMbtiOptions: List<String> = MBTI_OPTIONS
val personaActionTagOptions: List<String> = ACTION_TAG_OPTIONS

data class PersonaLibraryUiState(
    val serverName: String = "Roundtable",
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val personas: List<PiPersonaDto> = emptyList(),
    val editor: PersonaEditorState? = null,
    val showGenerateDialog: Boolean = false,
    val generateRequirement: String = "",
    val importText: String = "",
    val exportText: String? = null,
) {
    val enabledCount: Int = personas.count { it.enabled }
}

data class PersonaEditorState(
    val originalId: String? = null,
    val id: String = "",
    val name: String = "",
    val mbti: String = "INTJ",
    val stancePrompt: String = "",
    val style: String = "",
    val actionTagPrefs: Set<String> = setOf("陈述"),
    val provider: String = "fake-provider",
    val model: String = "fake-model",
    val fallbackText: String = "",
    val enabled: Boolean = true,
    val advancedExpanded: Boolean = false,
) {
    val rawSystemPrompt: String
        get() = listOf(
            "$name ($mbti)",
            "Role: persona",
            "Stance: $stancePrompt",
            "Style: $style",
            "Preferred action tags: ${actionTagPrefs.joinToString(", ")}",
        ).joinToString("\n")
}

@HiltViewModel
class PersonaLibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: PiApi,
    private val serverRepository: ServerRepository,
) : ViewModel() {
    private val serverId: String = decodeRouteArg(savedStateHandle.get<String>("serverId"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; isLenient = true; coerceInputValues = true; prettyPrint = true }

    private val _uiState = MutableStateFlow(PersonaLibraryUiState(serverName = "Roundtable"))
    val uiState: StateFlow<PersonaLibraryUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val conn = resolveConnection()
                val personas = api.listPersonas(conn).sortedWith(compareBy<PiPersonaDto> { !it.enabled }.thenBy { it.name.lowercase() })
                _uiState.update { it.copy(isLoading = false, personas = personas) }
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Failed to load personas") }
            }
        }
    }

    fun newPersona() {
        _uiState.update { it.copy(editor = PersonaEditorState(id = "custom-persona", name = "Custom Persona", stancePrompt = "Contribute a distinct, bounded point of view.", style = "Clear, concise, and concrete.")) }
    }

    fun openGenerateDialog() {
        _uiState.update { it.copy(showGenerateDialog = true, error = null) }
    }

    fun dismissGenerateDialog() {
        _uiState.update { it.copy(showGenerateDialog = false, generateRequirement = "") }
    }

    fun setGenerateRequirement(value: String) {
        _uiState.update { it.copy(generateRequirement = value) }
    }

    fun generateDraft() {
        val requirement = _uiState.value.generateRequirement.trim()
        if (requirement.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null) }
            try {
                val conn = resolveConnection()
                val draft = api.generatePersona(conn, PiGeneratePersonaRequest(requirement = requirement))
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        showGenerateDialog = false,
                        generateRequirement = "",
                        editor = draft.toEditorState(originalId = null),
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(isGenerating = false, error = error.message ?: "Persona generation failed") }
            }
        }
    }

    fun editPersona(persona: PiPersonaDto) {
        _uiState.update { it.copy(editor = persona.toEditorState(originalId = persona.id)) }
    }

    fun clonePersona(persona: PiPersonaDto) {
        val sourceId = persona.id?.takeIf { it.isNotBlank() } ?: persona.name.lowercase().replace(" ", "-")
        _uiState.update { it.copy(editor = persona.copy(id = "$sourceId-copy", name = "${persona.name} Copy").toEditorState(originalId = null)) }
    }

    fun dismissEditor() {
        _uiState.update { it.copy(editor = null) }
    }

    fun updateEditor(transform: (PersonaEditorState) -> PersonaEditorState) {
        _uiState.update { state -> state.copy(editor = state.editor?.let(transform)) }
    }

    fun saveEditor() {
        val editor = _uiState.value.editor ?: return
        mutate { conn ->
            val persona = editor.toPersona()
            val originalId = editor.originalId
            if (originalId == null) api.createPersona(conn, persona) else api.updatePersona(conn, originalId, persona)
        }
    }

    fun togglePersona(persona: PiPersonaDto) {
        val id = persona.id ?: return
        mutate { conn -> api.updatePersona(conn, id, persona.copy(enabled = !persona.enabled)) }
    }

    fun deletePersona(persona: PiPersonaDto) {
        val id = persona.id ?: return
        mutate { conn -> api.deletePersona(conn, id) }
    }

    fun exportPersona(persona: PiPersonaDto) {
        _uiState.update { it.copy(exportText = json.encodeToString(PiPersonaDto.serializer(), persona)) }
    }

    fun dismissExport() {
        _uiState.update { it.copy(exportText = null) }
    }

    fun setImportText(value: String) {
        _uiState.update { it.copy(importText = value) }
    }

    fun importJson() {
        val raw = _uiState.value.importText
        mutate { conn ->
            val personas = parsePersonas(raw)
            for (persona in personas) {
                val id = persona.id
                if (id.isNullOrBlank()) {
                    api.createPersona(conn, persona)
                } else {
                    runCatching { api.updatePersona(conn, id, persona) }.getOrElse { api.createPersona(conn, persona) }
                }
            }
        }
    }

    private fun mutate(block: suspend (PiConnection) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, error = null) }
            try {
                val conn = resolveConnection()
                block(conn)
                val personas = api.listPersonas(conn).sortedWith(compareBy<PiPersonaDto> { !it.enabled }.thenBy { it.name.lowercase() })
                _uiState.update { it.copy(isMutating = false, isLoading = false, personas = personas, editor = null, importText = "") }
            } catch (error: Exception) {
                _uiState.update { it.copy(isMutating = false, isLoading = false, error = error.message ?: "Persona action failed") }
            }
        }
    }

    private suspend fun resolveConnection(): PiConnection {
        val server = serverRepository.getServer(serverId) ?: error("Saved Pi server not found")
        _uiState.update { it.copy(serverName = server.displayName.ifBlank { "Roundtable" }) }
        return PiConnection.from(server.url, server.token)
    }

    private fun parsePersonas(raw: String): List<PiPersonaDto> {
        val root = json.parseToJsonElement(raw)
        return when (root) {
            is JsonArray -> json.decodeFromJsonElement(ListSerializer(PiPersonaDto.serializer()), root)
            is JsonObject -> {
                val items = root["items"] ?: root["personas"] ?: root["data"]
                when (items) {
                    is JsonArray -> json.decodeFromJsonElement(ListSerializer(PiPersonaDto.serializer()), items)
                    else -> listOf(json.decodeFromJsonElement(PiPersonaDto.serializer(), root["item"] ?: root))
                }
            }
            else -> emptyList()
        }
    }
}

private fun PiPersonaDto.toEditorState(originalId: String?): PersonaEditorState = PersonaEditorState(
    originalId = originalId,
    id = id.orEmpty(),
    name = name,
    mbti = mbti,
    stancePrompt = stancePrompt,
    style = style,
    actionTagPrefs = actionTagPrefs.toSet().ifEmpty { setOf("陈述") },
    provider = provider,
    model = model,
    fallbackText = fallback.joinToString("\n") { "${it.providerId}:${it.model}" },
    enabled = enabled,
)

private fun PersonaEditorState.toPersona(): PiPersonaDto = PiPersonaDto(
    id = id.trim().takeIf { it.isNotBlank() },
    name = name.trim().ifBlank { "Custom Persona" },
    mbti = mbti.takeIf { it in MBTI_OPTIONS } ?: "INTJ",
    stancePrompt = stancePrompt.trim().ifBlank { "Contribute a distinct, bounded point of view." },
    style = style.trim().ifBlank { "Clear, concise, and concrete." },
    actionTagPrefs = actionTagPrefs.filter { it in ACTION_TAG_OPTIONS }.ifEmpty { listOf("陈述") },
    provider = provider.trim().ifBlank { "fake-provider" },
    model = model.trim().ifBlank { "fake-model" },
    fallback = fallbackText.lines().mapNotNull { line ->
        val providerId = line.substringBefore(':').trim()
        val model = line.substringAfter(':', "").trim()
        if (providerId.isNotBlank() && model.isNotBlank()) PiModelRefDto(providerId = providerId, model = model) else null
    },
    enabled = enabled,
)

private fun decodeRouteArg(value: String?): String {
    val raw = value.orEmpty()
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}
