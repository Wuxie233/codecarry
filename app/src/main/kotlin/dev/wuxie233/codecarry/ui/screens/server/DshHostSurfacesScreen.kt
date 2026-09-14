package dev.wuxie233.codecarry.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wuxie233.codecarry.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshHostSurfacesScreen(
    onNavigateBack: () -> Unit,
    viewModel: DshHostSurfacesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var workspacePath by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
    var goalObjective by remember { mutableStateOf("") }
    var settingsNs by remember { mutableStateOf("") }
    var settingsPatch by remember { mutableStateOf("[]") }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var formTextEdits by remember { mutableStateOf(mapOf<String, String>()) }
    var formSwitchEdits by remember { mutableStateOf(mapOf<String, Boolean>()) }

    fun pickSettingsNamespace(ns: String) {
        settingsNs = ns
        formTextEdits = emptyMap()
        formSwitchEdits = emptyMap()
        showAdvancedSettings = false
        viewModel.clearFormError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_host_surfaces_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.actionError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            val catalog = uiState.catalog
            if (catalog != null) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_available)) {
                    Text(
                        catalog.availableMethods.sorted().joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (catalog.loopbackOnlyHidden.isNotEmpty()) {
                    DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_hidden_loopback)) {
                        Text(
                            catalog.loopbackOnlyHidden.sorted().joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (catalog?.can("session/list") == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_session_context, uiState.serverName)) {
                    if (DshModuleReady(uiState.sessions, { viewModel.retry(DshHostModule.SESSIONS) })) {
                        val options = uiState.sessions.data.orEmpty()
                        if (options.isEmpty()) {
                            Text(
                                stringResource(R.string.dsh_host_surfaces_no_sessions),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            options.forEach { option ->
                                val running = stringResource(R.string.dsh_host_surfaces_session_running)
                                val selected = option.sessionId == uiState.selectedSessionId
                                TextButton(onClick = { viewModel.selectSession(option.sessionId) }) {
                                    Text(
                                        buildString {
                                            append(option.title)
                                            if (selected) append(" ✓")
                                            if (option.running) append(" · ").append(running)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (catalog?.canManageWorkspaces == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_workspaces)) {
                    uiState.workspaces?.items.orEmpty().forEach { workspace ->
                        Text("${workspace.title} — ${workspace.path}", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(
                        value = workspacePath,
                        onValueChange = { workspacePath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_workspace_path)) },
                    )
                    TextButton(onClick = { viewModel.createWorkspace(workspacePath.trim()) }, enabled = workspacePath.isNotBlank()) {
                        Text(stringResource(R.string.dsh_host_surfaces_create_workspace))
                    }
                }
            }

            if (catalog?.canBrowseHost == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_browse)) {
                    if (DshModuleReady(uiState.directory, { viewModel.retry(DshHostModule.DIRECTORY) })) {
                        val listing = uiState.directory.data
                        if (listing != null) {
                            Text(listing.path, style = MaterialTheme.typography.bodyMedium)
                            listing.entries.forEach { entry ->
                                TextButton(onClick = { viewModel.browse(entry.path) }) {
                                    Text(entry.name)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_folder_name)) },
                    )
                    TextButton(
                        onClick = {
                            val parent = uiState.directory.data?.path ?: return@TextButton
                            viewModel.createDirectory(parent, folderName.trim())
                        },
                        enabled = folderName.isNotBlank() && uiState.directory.data != null,
                    ) {
                        Text(stringResource(R.string.dsh_host_surfaces_create_folder))
                    }
                }
            }

            if (catalog?.canListSkills == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_skills)) {
                    DshSessionTargetLine(uiState)
                    val selectedSessionId = uiState.selectedSessionId
                    if (selectedSessionId == null) {
                        Text(
                            stringResource(R.string.dsh_host_surfaces_no_session_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (DshModuleReady(uiState.skills, { viewModel.retry(DshHostModule.SKILLS) })) {
                        val skills = uiState.skills.data?.skills.orEmpty()
                        if (skills.isEmpty()) {
                            Text(
                                stringResource(R.string.dsh_host_surfaces_skills_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            skills.forEach { skill ->
                                Text("/${skill.name} — ${skill.description}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            if (catalog?.canDescribeGit == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_git)) {
                    if (DshModuleReady(uiState.git, { viewModel.retry(DshHostModule.GIT) })) {
                        val git = uiState.git.data
                        if (git != null) {
                            Text("${git.currentBranch} · ${git.worktreePath}", style = MaterialTheme.typography.bodyMedium)
                            git.branches.take(8).forEach { branch ->
                                Text(branch.name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (catalog?.canListPresets == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_presets)) {
                    DshSessionTargetLine(uiState)
                    val sessionSelected = uiState.selectedSessionId != null
                    if (DshModuleReady(uiState.presets, { viewModel.retry(DshHostModule.PRESETS) })) {
                        uiState.presets.data?.presets.orEmpty().forEach { preset ->
                            val label = preset.name ?: preset.id
                            TextButton(
                                onClick = { viewModel.selectPreset(preset.id) },
                                enabled = sessionSelected && catalog.canSelectPreset,
                            ) {
                                Text("${stringResource(R.string.dsh_host_surfaces_select_preset)}: $label")
                            }
                        }
                    }
                    uiState.lastPreset?.let { preset ->
                        Text(
                            stringResource(R.string.dsh_host_surfaces_presets_applied, preset),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (catalog?.canManageGoals == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_goals)) {
                    DshSessionTargetLine(uiState)
                    OutlinedTextField(
                        value = goalObjective,
                        onValueChange = { goalObjective = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_goal_objective)) },
                    )
                    TextButton(
                        onClick = { viewModel.createGoal(goalObjective.trim()) },
                        enabled = uiState.selectedSessionId != null && goalObjective.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.dsh_host_surfaces_create_goal))
                    }
                    uiState.lastGoal?.let { goal ->
                        Text("${goal.ref.id} r${goal.ref.revision}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (catalog?.canManageAutomation == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_automation)) {
                    if (DshModuleReady(uiState.automation, { viewModel.retry(DshHostModule.AUTOMATION) })) {
                        uiState.automation.data?.items.orEmpty().forEach { rule ->
                            Text("${rule.name} · ${rule.state}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (catalog?.canMutateSettings == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_settings)) {
                    if (DshModuleReady(uiState.settings, { viewModel.retry(DshHostModule.SETTINGS) })) {
                        uiState.settings.data?.namespaces.orEmpty().forEach { ns ->
                            val selected = ns.ns == settingsNs
                            TextButton(onClick = { pickSettingsNamespace(ns.ns) }) {
                                Text(
                                    buildString {
                                        append(ns.ns)
                                        append(" r")
                                        append(ns.revision)
                                        if (selected) append(" ✓")
                                    },
                                )
                            }
                        }
                        if (settingsNs.isNotBlank()) {
                            val fields = remember(settingsNs, uiState.settings.data) {
                                viewModel.settingsFieldsFor(settingsNs)
                            }
                            if (fields.isEmpty()) {
                                Text(
                                    stringResource(R.string.dsh_host_surfaces_settings_form_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                fields.forEach { field ->
                                    when (field.kind) {
                                        DshSettingsFieldKind.TEXT, DshSettingsFieldKind.NUMBER -> OutlinedTextField(
                                            value = formTextEdits[field.key] ?: field.textValue.orEmpty(),
                                            onValueChange = { formTextEdits = formTextEdits + (field.key to it) },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text(field.key) },
                                        )
                                        DshSettingsFieldKind.BOOLEAN -> Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Switch(
                                                checked = formSwitchEdits[field.key] ?: field.booleanValue ?: false,
                                                onCheckedChange = { formSwitchEdits = formSwitchEdits + (field.key to it) },
                                            )
                                            Text(
                                                field.key,
                                                modifier = Modifier.padding(start = 8.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.mutateSettingsForm(settingsNs, formTextEdits, formSwitchEdits)
                                    },
                                    enabled = fields.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.dsh_host_surfaces_settings_apply))
                                }
                            }
                            if (showAdvancedSettings) {
                                TextButton(onClick = { showAdvancedSettings = false }) {
                                    Text(stringResource(R.string.dsh_host_surfaces_settings_advanced_hide))
                                }
                                OutlinedTextField(
                                    value = settingsPatch,
                                    onValueChange = { settingsPatch = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.dsh_host_surfaces_settings_patch)) },
                                )
                                TextButton(
                                    onClick = { viewModel.mutateSettingsJson(settingsNs, settingsPatch) },
                                    enabled = settingsPatch.isNotBlank(),
                                ) {
                                    Text(stringResource(R.string.dsh_host_surfaces_settings_apply_json))
                                }
                            } else {
                                TextButton(onClick = { showAdvancedSettings = true }) {
                                    Text(stringResource(R.string.dsh_host_surfaces_settings_advanced_show))
                                }
                            }
                        }
                        uiState.formError?.let { error ->
                            Text(
                                stringResource(
                                    when (error) {
                                        DshSettingsFormError.NO_NAMESPACE -> R.string.dsh_host_surfaces_error_no_namespace
                                        DshSettingsFormError.INVALID_NUMBER -> R.string.dsh_host_surfaces_error_invalid_number
                                        DshSettingsFormError.EMPTY_PATCH -> R.string.dsh_host_surfaces_error_empty_patch
                                        DshSettingsFormError.INVALID_JSON_ARRAY -> R.string.dsh_host_surfaces_error_invalid_json
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        uiState.lastSettings?.let { view ->
                            Text("${view.ns} r${view.revision}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (catalog?.canListLlm == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_models)) {
                    if (DshModuleReady(uiState.providers, { viewModel.retry(DshHostModule.PROVIDERS) })) {
                        uiState.providers.data?.providers.orEmpty().forEach { provider ->
                            Text(provider.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (DshModuleReady(uiState.models, { viewModel.retry(DshHostModule.MODELS) })) {
                        uiState.models.data?.groups.orEmpty().forEach { group ->
                            Text("${group.name}: ${group.models.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (catalog?.canListSystemPrompt == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_system_prompt)) {
                    if (DshModuleReady(uiState.systemPrompt, { viewModel.retry(DshHostModule.SYSTEM_PROMPT) })) {
                        uiState.systemPrompt.data?.sections.orEmpty().forEach { section ->
                            Text(section.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (catalog?.canListSubagents == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_subagents)) {
                    DshSessionTargetLine(uiState)
                    var subagentPrompt by remember { mutableStateOf("") }
                    TextButton(
                        onClick = { viewModel.loadSubagents() },
                        enabled = uiState.selectedSessionId != null,
                    ) {
                        Text(stringResource(R.string.dsh_host_surfaces_load_subagents))
                    }
                    OutlinedTextField(
                        value = subagentPrompt,
                        onValueChange = { subagentPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_subagent_prompt)) },
                    )
                    if (DshModuleReady(uiState.subagents, { viewModel.retry(DshHostModule.SUBAGENTS) })) {
                        uiState.subagents.data?.entries.orEmpty().forEach { entry ->
                            Text("${entry.id} · ${entry.mode ?: entry.reason.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                            TextButton(
                                onClick = { viewModel.promptSubagent(entry.id, subagentPrompt.trim()) },
                                enabled = uiState.selectedSessionId != null && subagentPrompt.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.dsh_host_surfaces_send_prompt))
                            }
                            TextButton(
                                onClick = { viewModel.interruptSubagent(entry.id) },
                                enabled = uiState.selectedSessionId != null,
                            ) {
                                Text(stringResource(R.string.dsh_host_surfaces_interrupt))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Target line for session-scoped surfaces: the selected session and its server. */
@Composable
private fun DshSessionTargetLine(uiState: DshHostSurfacesUiState) {
    val target = uiState.selectedSession
    if (target != null) {
        Text(
            stringResource(R.string.dsh_host_surfaces_current_target, target.title, uiState.serverName),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            stringResource(R.string.dsh_host_surfaces_no_session_selected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Render one module's loading/error/unresolved chrome. Returns true when the
 * caller should render module data (which may still be legitimately empty).
 */
@Composable
private fun <T> DshModuleReady(
    state: DshSurfaceModuleState<T>,
    onRetry: () -> Unit,
): Boolean {
    return when {
        state.loading -> {
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
            false
        }
        state.error != null -> {
            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.dsh_host_surfaces_retry))
            }
            false
        }
        state.data != null -> true
        else -> {
            // Load finished without a publishable value (e.g. a dropped
            // obsolete-generation result); offer an explicit retry.
            Text(
                stringResource(R.string.dsh_host_surfaces_not_loaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.dsh_host_surfaces_retry))
            }
            false
        }
    }
}

@Composable
private fun DshSurfaceSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
        HorizontalDivider()
    }
}
