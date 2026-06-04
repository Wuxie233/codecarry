package dev.minios.ocremote.ui.screens.roundtable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Roundtable
import dev.minios.ocremote.ui.screens.chat.PiSenderIdentity
import dev.minios.ocremote.ui.screens.chat.piSenderAccentColor
import dev.minios.ocremote.data.api.PiCatalogEntryDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundtableCenterScreen(
    onNavigateBack: () -> Unit,
    onOpenRoundtable: (RoundtableChatTarget) -> Unit,
    onOpenSummary: (String) -> Unit,
    onOpenPersonaLibrary: () -> Unit,
    onCreateRoundtable: () -> Unit,
    openManualSetup: Boolean = false,
    onManualSetupConsumed: () -> Unit = {},
    viewModel: RoundtableCenterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black

    LaunchedEffect(openManualSetup) {
        if (openManualSetup) {
            viewModel.createRoundtable()
            onManualSetupConsumed()
        }
    }

    Scaffold(
        topBar = {
            var showTopMenu by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.roundtable_center_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = uiState.serverName,
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
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.roundtable_refresh_roundtables))
                    }
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.roundtable_open_persona_library)) },
                                onClick = { showTopMenu = false; onOpenPersonaLibrary() },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.roundtable_manual_setup)) },
                                onClick = { showTopMenu = false; viewModel.createRoundtable() },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateRoundtable) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.roundtable_new_roundtable))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            RoundtableCenterControls(
                filter = uiState.filter,
                sort = uiState.sort,
                runningCount = uiState.runningCount,
                onFilter = viewModel::setFilter,
                onSort = viewModel::setSort,
            )

            if (uiState.error != null) {
                Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    uiState.items.isEmpty() -> RoundtableEmptyState(
                        modifier = Modifier.align(Alignment.Center),
                        onCreate = onCreateRoundtable,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.items, key = { item -> item.id }) { roundtable ->
                            RoundtableCard(
                                roundtable = roundtable,
                                isAmoled = isAmoled,
                                onOpen = {
                                    viewModel.liveChatTarget(roundtable.id)?.let(onOpenRoundtable)
                                },
                                onResume = {
                                    viewModel.resumeRoundtable(roundtable.id)
                                    viewModel.liveChatTarget(roundtable.id)?.let(onOpenRoundtable)
                                },
                                onOpenSummary = { onOpenSummary(roundtable.id) },
                                onArchive = { viewModel.archiveRoundtable(roundtable.id) },
                                onDelete = { viewModel.deleteRoundtable(roundtable.id) },
                                onDuplicate = { viewModel.duplicateAsTemplate(roundtable) },
                            )
                        }
                    }
                }

                if (uiState.isMutating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                    )
                }
            }
        }
    }

    uiState.configEditor?.let { editor ->
        RoundtableConfigDialog(
            editor = editor,
            onTopicChange = viewModel::updateConfigTopic,
            onPropose = viewModel::proposeLineup,
            onUseDirectly = viewModel::useSuggestionDirectly,
            onApplyTemplate = viewModel::applyTemplate,
            onSaveTemplate = viewModel::saveLineupTemplate,
            onSwapRole = viewModel::swapRole,
            onProviderChange = viewModel::updateRoleProvider,
            onModelChange = viewModel::updateRoleModel,
            onCadenceChange = viewModel::updateCadence,
            onMaxTurnsChange = viewModel::updateMaxTurnsPerRound,
            onAddFallback = viewModel::addRoleFallback,
            onRemoveFallback = viewModel::removeRoleFallback,
            onMoveFallback = viewModel::moveRoleFallback,
            onSave = viewModel::saveConfigEditor,
            onDismiss = viewModel::dismissConfigEditor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoundtableConfigDialog(
    editor: RoundtableConfigEditorState,
    onTopicChange: (String) -> Unit,
    onPropose: () -> Unit,
    onUseDirectly: () -> Unit,
    onApplyTemplate: (String) -> Unit,
    onSaveTemplate: () -> Unit,
    onSwapRole: (String, String) -> Unit,
    onProviderChange: (String, String) -> Unit,
    onModelChange: (String, String) -> Unit,
    onCadenceChange: (RoundtableCadence) -> Unit,
    onMaxTurnsChange: (Int) -> Unit,
    onAddFallback: (String, String, String) -> Unit,
    onRemoveFallback: (String, Int) -> Unit,
    onMoveFallback: (String, Int, Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val errors = editor.validationErrors(context)
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
                Text(stringResource(R.string.roundtable_new_dialog_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(
                        if (editor.step == NewRoundtableStep.Topic) {
                            R.string.roundtable_topic_step_desc
                        } else {
                            R.string.roundtable_review_step_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = editor.topic,
                    onValueChange = onTopicChange,
                    label = { Text(stringResource(R.string.roundtable_topic_label)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    isError = editor.topic.isBlank(),
                )
                if (editor.templates.isNotEmpty()) {
                    TemplateDropdown(
                        templates = editor.templates,
                        selectedTemplateId = editor.selectedTemplateId,
                        onSelect = onApplyTemplate,
                    )
                }
                if (editor.step == NewRoundtableStep.Topic) {
                    if (editor.isLoadingCatalog || editor.isProposing) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatalogDropdown(
                            label = stringResource(R.string.roundtable_default_cadence),
                            value = stringResource(roundtableCadenceLabelRes(editor.cadence)),
                            options = RoundtableCadence.entries.map { it.wireName to stringResource(roundtableCadenceLabelRes(it)) },
                            onSelect = { mode -> onCadenceChange(RoundtableCadence.entries.firstOrNull { it.wireName == mode } ?: RoundtableCadence.ModeratorRouted) },
                            modifier = Modifier.weight(1f),
                        )
                        CatalogDropdown(
                            label = stringResource(R.string.roundtable_turns),
                            value = editor.maxTurnsPerRound.toString(),
                            options = (3..12).map { it.toString() to it.toString() },
                            onSelect = { value -> onMaxTurnsChange(value.toIntOrNull() ?: editor.maxTurnsPerRound) },
                            modifier = Modifier.weight(0.45f),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(editor.roles, key = { it.roleId }) { role ->
                            RoleConfigCard(
                                role = role,
                                personas = editor.personas,
                                catalog = editor.catalog,
                                onSwapRole = { personaId -> onSwapRole(role.roleId, personaId) },
                                onProviderChange = { provider -> onProviderChange(role.roleId, provider) },
                                onModelChange = { model -> onModelChange(role.roleId, model) },
                                onAddFallback = { provider, model -> onAddFallback(role.roleId, provider, model) },
                                onRemoveFallback = { index -> onRemoveFallback(role.roleId, index) },
                                onMoveFallback = { from, to -> onMoveFallback(role.roleId, from, to) },
                            )
                        }
                    }
                }
                (editor.error ?: errors.firstOrNull())?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    if (editor.step == NewRoundtableStep.Review) {
                        TextButton(onClick = onSaveTemplate, enabled = !editor.isLoadingCatalog && errors.isEmpty() && editor.roles.size in 3..5) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.roundtable_save_as_template))
                        }
                        TextButton(onClick = onUseDirectly, enabled = !editor.isLoadingCatalog && errors.isEmpty() && editor.roles.size in 3..5) {
                            Text(stringResource(R.string.roundtable_use_suggestion_directly))
                        }
                    }
                    Button(
                        onClick = if (editor.step == NewRoundtableStep.Topic) onPropose else onSave,
                        enabled = !editor.isLoadingCatalog && !editor.isProposing && editor.topic.isNotBlank() && (editor.step == NewRoundtableStep.Topic || (errors.isEmpty() && editor.roles.size in 3..5)),
                    ) {
                        Text(stringResource(if (editor.step == NewRoundtableStep.Topic) R.string.roundtable_propose_lineup else R.string.roundtable_start))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleConfigCard(
    role: RoleConfigEditorState,
    personas: List<dev.minios.ocremote.data.api.PiPersonaDto>,
    catalog: List<PiCatalogEntryDto>,
    onSwapRole: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onAddFallback: (String, String) -> Unit,
    onRemoveFallback: (Int) -> Unit,
    onMoveFallback: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val provider = catalog.firstOrNull { it.providerId == role.provider }
    val roleErrors = role.validationErrors(context, catalog)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(role.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(role.reason ?: stringResource(R.string.roundtable_persona_default_override), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RoundtableBadge(provider?.displayName ?: role.provider)
            }
            CatalogDropdown(
                label = stringResource(R.string.roundtable_swap_persona),
                value = role.name,
                options = personas.mapNotNull { persona -> persona.id?.let { id -> id to persona.name } },
                onSelect = onSwapRole,
            )
            CatalogDropdown(
                label = stringResource(R.string.roundtable_gateway),
                value = provider?.displayName ?: role.provider,
                options = catalog.map { it.providerId to it.displayName },
                onSelect = onProviderChange,
            )
            CatalogDropdown(
                label = stringResource(R.string.roundtable_model),
                value = provider?.models?.firstOrNull { it.id == role.model }?.displayName ?: role.model,
                options = (provider?.models ?: emptyList()).map { it.id to it.displayName },
                onSelect = onModelChange,
            )
            Text(stringResource(R.string.roundtable_ordered_fallback_list), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            role.fallback.forEachIndexed { index, fallback ->
                FallbackRow(
                    index = index,
                    ref = fallback,
                    canMoveUp = index > 0,
                    canMoveDown = index < role.fallback.lastIndex,
                    onMoveUp = { onMoveFallback(index, index - 1) },
                    onMoveDown = { onMoveFallback(index, index + 1) },
                    onRemove = { onRemoveFallback(index) },
                )
            }
            val addProvider = catalog.firstOrNull { it.enabled && it.models.any { model -> model.enabled } }
            val addModel = addProvider?.models?.firstOrNull { it.enabled }
            TextButton(onClick = { if (addProvider != null && addModel != null) onAddFallback(addProvider.providerId, addModel.id) }, enabled = addProvider != null && addModel != null) {
                Text(stringResource(R.string.roundtable_add_fallback))
            }
            roleErrors.firstOrNull()?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CatalogDropdown(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
            modifier = modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, displayName) ->
                DropdownMenuItem(text = { Text(displayName) }, onClick = { expanded = false; onSelect(id) })
            }
        }
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
    }
}

@Composable
private fun TemplateDropdown(
    templates: List<LineupTemplateState>,
    selectedTemplateId: String?,
    onSelect: (String) -> Unit,
) {
    val selected = templates.firstOrNull { it.id == selectedTemplateId }
    CatalogDropdown(
        label = stringResource(R.string.roundtable_reuse_saved_template),
        value = selected?.name ?: stringResource(R.string.roundtable_choose_template),
        options = templates.map { template ->
            template.id to stringResource(
                R.string.roundtable_template_option,
                template.name,
                pluralStringResource(R.plurals.persona_template_personas_count, template.roles.size, template.roles.size),
                stringResource(roundtableCadenceLabelRes(template.cadence)),
            )
        },
        onSelect = onSelect,
    )
}

@Composable
private fun FallbackRow(
    index: Int,
    ref: dev.minios.ocremote.data.api.PiModelRefDto,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundtableBadge(text = stringResource(R.string.roundtable_fallback_label, index + 1, ref.providerId, ref.model), modifier = Modifier.weight(1f))
        IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.roundtable_move_fallback_up)) }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.roundtable_move_fallback_down)) }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.roundtable_remove_fallback)) }
    }
}

@Composable
private fun RoundtableCenterControls(
    filter: RoundtableFilter,
    sort: RoundtableSort,
    runningCount: Int,
    onFilter: (RoundtableFilter) -> Unit,
    onSort: (RoundtableSort) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundtableBadge(
            text = pluralStringResource(R.plurals.roundtable_running_count, runningCount, runningCount),
        )
        Text(
            text = stringResource(R.string.roundtable_filter_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundtableFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilter(option) },
                    label = { Text(stringResource(roundtableFilterLabelRes(option))) },
                )
            }
        }
        Text(
            text = stringResource(R.string.roundtable_sort_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundtableSort.entries.forEach { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { onSort(option) },
                    label = { Text(stringResource(roundtableSortLabelRes(option))) },
                )
            }
        }
    }
}

@Composable
private fun RoundtableCard(
    roundtable: Roundtable,
    isAmoled: Boolean,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onOpenSummary: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.72f else 0.35f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
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
                    Text(
                        text = roundtable.topic?.takeIf { it.isNotBlank() } ?: stringResource(R.string.roundtable_untitled_topic),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundtableRosterDots(roundtable.roster)
                        StatusChip(roundtable.status)
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.roundtable_actions))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.roundtable_view_summary)) },
                            onClick = { showMenu = false; onOpenSummary() },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.roundtable_duplicate_as_template)) },
                            onClick = { showMenu = false; onDuplicate() },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.roundtable_archive)) },
                            onClick = { showMenu = false; onArchive() },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.roundtable_card_meta,
                        pluralStringResource(R.plurals.roundtable_rounds_count, roundtable.roundCount, roundtable.roundCount),
                        formatRoundtableActivity(LocalContext.current, roundtable.time.updated),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onResume, enabled = roundtable.status != Roundtable.Status.Archived) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.roundtable_resume))
                }
            }
        }
    }
}

@Composable
private fun RoundtableRosterDots(roster: List<Roundtable.RoleSummary>) {
    Row(horizontalArrangement = Arrangement.spacedBy((-2).dp), verticalAlignment = Alignment.CenterVertically) {
        roster.take(6).forEach { role ->
            val color = piSenderAccentColor(
                PiSenderIdentity(
                    id = role.id,
                    name = role.name,
                    mbti = null,
                    role = role.role,
                    colorSeed = role.colorSeed,
                ),
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(color, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun StatusChip(status: Roundtable.Status) {
    RoundtableBadge(text = stringResource(roundtableStatusLabelRes(status)))
}

@Composable
private fun RoundtableEmptyState(
    modifier: Modifier,
    onCreate: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.roundtable_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.roundtable_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.roundtable_new_roundtable))
        }
    }
}

@StringRes
private fun roundtableFilterLabelRes(filter: RoundtableFilter): Int = when (filter) {
    RoundtableFilter.Active -> R.string.roundtable_filter_active
    RoundtableFilter.Running -> R.string.roundtable_filter_running
    RoundtableFilter.Archived -> R.string.roundtable_filter_archived
    RoundtableFilter.All -> R.string.roundtable_filter_all
}

@StringRes
private fun roundtableSortLabelRes(sort: RoundtableSort): Int = when (sort) {
    RoundtableSort.LastActivity -> R.string.roundtable_sort_activity
    RoundtableSort.Created -> R.string.roundtable_sort_created
    RoundtableSort.Topic -> R.string.roundtable_sort_topic
}

@StringRes
private fun roundtableStatusLabelRes(status: Roundtable.Status): Int = when (status) {
    Roundtable.Status.Running -> R.string.roundtable_status_running
    Roundtable.Status.Paused,
    Roundtable.Status.AwaitingCommand,
    Roundtable.Status.AwaitingSkip -> R.string.roundtable_status_paused
    Roundtable.Status.Archived -> R.string.roundtable_status_archived
    Roundtable.Status.Completed -> R.string.roundtable_status_ended
    Roundtable.Status.Error -> R.string.roundtable_status_error
    Roundtable.Status.Unknown -> R.string.roundtable_status_unknown
}

@StringRes
private fun roundtableCadenceLabelRes(cadence: RoundtableCadence): Int = when (cadence) {
    RoundtableCadence.ModeratorRouted -> R.string.roundtable_cadence_moderator_routed
    RoundtableCadence.RoundRobin -> R.string.roundtable_cadence_round_robin
    RoundtableCadence.FreeRoundtable -> R.string.roundtable_cadence_free_roundtable
    RoundtableCadence.MentionReactive -> R.string.roundtable_cadence_mention_reactive
}
