package dev.minios.ocremote.ui.screens.roundtable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.data.api.PiPersonaDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaLibraryScreen(
    onNavigateBack: () -> Unit,
    viewModel: PersonaLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black

    Scaffold(
        topBar = {
            var showTopMenu by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.persona_library_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = stringResource(
                                R.string.persona_enabled_subtitle,
                                pluralStringResource(R.plurals.persona_enabled_count, uiState.enabledCount, uiState.enabledCount),
                                uiState.serverName,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.persona_refresh))
                    }
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.persona_ai_generate)) },
                                onClick = { showTopMenu = false; viewModel.openGenerateDialog() },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.persona_import_json)) },
                                onClick = { showTopMenu = false; viewModel.setImportText("{}") },
                                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::newPersona) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.persona_new))
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.personas.isEmpty() -> PersonaEmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    onCreate = viewModel::newPersona,
                    onGenerate = viewModel::openGenerateDialog,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.personas, key = { it.id ?: it.name }) { persona ->
                        PersonaCard(
                            persona = persona,
                            isAmoled = isAmoled,
                            onEdit = { viewModel.editPersona(persona) },
                            onClone = { viewModel.clonePersona(persona) },
                            onToggle = { viewModel.togglePersona(persona) },
                            onExport = { viewModel.exportPersona(persona) },
                            onDelete = { viewModel.deletePersona(persona) },
                        )
                    }
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                )
            }

            if (uiState.isMutating || uiState.isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                )
            }
        }
    }

    if (uiState.showGenerateDialog) {
        GeneratePersonaDialog(
            value = uiState.generateRequirement,
            isGenerating = uiState.isGenerating,
            onChange = viewModel::setGenerateRequirement,
            onGenerate = viewModel::generateDraft,
            onDismiss = viewModel::dismissGenerateDialog,
        )
    }

    uiState.editor?.let { editor ->
        PersonaEditorDialog(
            editor = editor,
            onChange = viewModel::updateEditor,
            onSave = viewModel::saveEditor,
            onDismiss = viewModel::dismissEditor,
        )
    }

    if (uiState.importText.isNotBlank()) {
        ImportPersonaDialog(
            value = uiState.importText,
            onChange = viewModel::setImportText,
            onImport = viewModel::importJson,
            onDismiss = { viewModel.setImportText("") },
        )
    }

    uiState.exportText?.let { exportText ->
        ExportPersonaDialog(
            value = exportText,
            onDismiss = viewModel::dismissExport,
        )
    }
}

@Composable
private fun PersonaCard(
    persona: PiPersonaDto,
    isAmoled: Boolean,
    onEdit: () -> Unit,
    onClone: () -> Unit,
    onToggle: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.72f else 0.35f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = persona.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RoundtableBadge(persona.mbti)
                    }
                    Text(
                        text = persona.stancePrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = persona.enabled, onCheckedChange = { onToggle() })
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.persona_actions))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.persona_edit)) }, onClick = { showMenu = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.persona_clone)) }, onClick = { showMenu = false; onClone() }, leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.persona_export_json)) }, onClick = { showMenu = false; onExport() }, leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { showMenu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                persona.actionTagPrefs.take(3).forEach { tag -> RoundtableBadge(tag) }
                RoundtableBadge(text = "${persona.provider} · ${persona.model}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaEditorDialog(
    editor: PersonaEditorState,
    onChange: ((PersonaEditorState) -> PersonaEditorState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (editor.originalId == null) R.string.persona_dialog_new_title else R.string.persona_dialog_edit_title), style = MaterialTheme.typography.titleLarge)
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PersonaField(stringResource(R.string.persona_id_label), editor.id) { value -> onChange { it.copy(id = value) } }
                    PersonaField(stringResource(R.string.persona_name_label), editor.name) { value -> onChange { it.copy(name = value) } }
                    Text(stringResource(R.string.persona_mbti_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChunkedChipRows(personaMbtiOptions, selected = setOf(editor.mbti)) { value -> onChange { it.copy(mbti = value) } }
                    PersonaField(stringResource(R.string.persona_stance_prompt_label), editor.stancePrompt, minLines = 3) { value -> onChange { it.copy(stancePrompt = value) } }
                    PersonaField(stringResource(R.string.persona_style_label), editor.style, minLines = 2) { value -> onChange { it.copy(style = value) } }
                    Text(stringResource(R.string.persona_action_tags_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChunkedChipRows(personaActionTagOptions, selected = editor.actionTagPrefs) { value ->
                        onChange { current ->
                            val next = if (value in current.actionTagPrefs) current.actionTagPrefs - value else current.actionTagPrefs + value
                            current.copy(actionTagPrefs = next.ifEmpty { setOf("陈述") })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Switch(checked = editor.enabled, onCheckedChange = { enabled -> onChange { it.copy(enabled = enabled) } })
                        Text(stringResource(R.string.persona_enabled_label), style = MaterialTheme.typography.bodyMedium)
                    }
                    PersonaField(stringResource(R.string.persona_provider_label), editor.provider) { value -> onChange { it.copy(provider = value) } }
                    PersonaField(stringResource(R.string.persona_model_label), editor.model) { value -> onChange { it.copy(model = value) } }
                    PersonaField(stringResource(R.string.persona_fallback_list_label), editor.fallbackText, minLines = 3) { value -> onChange { it.copy(fallbackText = value) } }
                    TextButton(onClick = { onChange { it.copy(advancedExpanded = !it.advancedExpanded) } }) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (editor.advancedExpanded) R.string.persona_hide_advanced_prompt else R.string.persona_show_advanced_prompt))
                    }
                    if (editor.advancedExpanded) {
                        Text(
                            text = editor.rawSystemPrompt(LocalContext.current),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = onSave, enabled = editor.name.isNotBlank() && editor.stancePrompt.isNotBlank() && editor.provider.isNotBlank() && editor.model.isNotBlank()) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.persona_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaField(label: String, value: String, minLines: Int = 1, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChunkedChipRows(options: List<String>, selected: Set<String>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    FilterChip(
                        selected = option in selected,
                        onClick = { onSelect(option) },
                        label = { Text(option) },
                        leadingIcon = if (option in selected) ({ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratePersonaDialog(
    value: String,
    isGenerating: Boolean,
    onChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = { if (!isGenerating) onDismiss() }) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.persona_generate_dialog_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(R.string.persona_generate_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(stringResource(R.string.persona_requirement_label)) },
                    placeholder = { Text(stringResource(R.string.persona_requirement_placeholder)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !isGenerating) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = onGenerate, enabled = value.isNotBlank() && !isGenerating) {
                        if (isGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.persona_generate_draft))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportPersonaDialog(value: String, onChange: (String) -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.persona_import_json), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.persona_import_dialog_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = value, onValueChange = onChange, minLines = 8, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = onImport, enabled = value.isNotBlank()) { Text(stringResource(R.string.persona_import)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportPersonaDialog(value: String, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.persona_export_dialog_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = value, onValueChange = {}, readOnly = true, minLines = 8, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.persona_done)) }
                }
            }
        }
    }
}

@Composable
private fun PersonaEmptyState(modifier: Modifier, onCreate: () -> Unit, onGenerate: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.persona_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.persona_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onGenerate, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.persona_ai_generate_short))
            }
            TextButton(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.persona_new))
            }
        }
    }
}
