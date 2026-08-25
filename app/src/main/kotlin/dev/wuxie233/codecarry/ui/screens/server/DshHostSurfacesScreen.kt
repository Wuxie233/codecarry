package dev.wuxie233.codecarry.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var parentSessionId by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf("") }
    var goalObjective by remember { mutableStateOf("") }
    var settingsNs by remember { mutableStateOf("") }
    var settingsPatch by remember { mutableStateOf("[]") }
    var subagentPrompt by remember { mutableStateOf("") }

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
            uiState.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (uiState.isLoading) {
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
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
                    val listing = uiState.directory
                    if (listing != null) {
                        Text(listing.path, style = MaterialTheme.typography.bodyMedium)
                        listing.entries.forEach { entry ->
                            TextButton(onClick = { viewModel.browse(entry.path) }) {
                                Text(entry.name)
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
                            val parent = uiState.directory?.path ?: return@TextButton
                            viewModel.createDirectory(parent, folderName.trim())
                        },
                        enabled = folderName.isNotBlank() && uiState.directory != null,
                    ) {
                        Text(stringResource(R.string.dsh_host_surfaces_create_folder))
                    }
                }
            }

            if (catalog?.canListSkills == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_skills)) {
                    uiState.skills?.skills.orEmpty().forEach { skill ->
                        Text("/${skill.name} — ${skill.description}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (catalog?.canDescribeGit == true && uiState.git != null) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_git)) {
                    val git = uiState.git!!
                    Text("${git.currentBranch} · ${git.worktreePath}", style = MaterialTheme.typography.bodyMedium)
                    git.branches.take(8).forEach { branch ->
                        Text(branch.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (catalog?.canListPresets == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_presets)) {
                    OutlinedTextField(
                        value = sessionId,
                        onValueChange = { sessionId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_session_id)) },
                    )
                    uiState.presets?.presets.orEmpty().forEach { preset ->
                        val label = preset.name ?: preset.id
                        TextButton(
                            onClick = { viewModel.selectPreset(sessionId.trim(), preset.id) },
                            enabled = sessionId.isNotBlank() && catalog.canSelectPreset,
                        ) {
                            Text("${stringResource(R.string.dsh_host_surfaces_select_preset)}: $label")
                        }
                    }
                }
            }

            if (catalog?.canManageGoals == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_goals)) {
                    OutlinedTextField(
                        value = sessionId,
                        onValueChange = { sessionId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_session_id)) },
                    )
                    OutlinedTextField(
                        value = goalObjective,
                        onValueChange = { goalObjective = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_goal_objective)) },
                    )
                    TextButton(
                        onClick = { viewModel.createGoal(sessionId.trim(), goalObjective.trim()) },
                        enabled = sessionId.isNotBlank() && goalObjective.isNotBlank(),
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
                    uiState.automation?.items.orEmpty().forEach { rule ->
                        Text("${rule.name} · ${rule.state}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (catalog?.canMutateSettings == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_settings)) {
                    uiState.settings?.namespaces.orEmpty().forEach { ns ->
                        TextButton(onClick = { settingsNs = ns.ns }) {
                            Text("${ns.ns} r${ns.revision}")
                        }
                    }
                    OutlinedTextField(
                        value = settingsNs,
                        onValueChange = { settingsNs = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_settings_ns)) },
                    )
                    OutlinedTextField(
                        value = settingsPatch,
                        onValueChange = { settingsPatch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_settings_patch)) },
                    )
                    TextButton(
                        onClick = { viewModel.mutateSettings(settingsNs.trim(), settingsPatch) },
                        enabled = settingsNs.isNotBlank() && settingsPatch.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.dsh_host_surfaces_settings_mutate))
                    }
                    uiState.lastSettings?.let { view ->
                        Text("${view.ns} r${view.revision}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (catalog?.canListLlm == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_models)) {
                    uiState.providers?.providers.orEmpty().forEach { provider ->
                        Text(provider.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                    uiState.models?.groups.orEmpty().forEach { group ->
                        Text("${group.name}: ${group.models.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (catalog?.canListSystemPrompt == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_system_prompt)) {
                    uiState.systemPrompt?.sections.orEmpty().forEach { section ->
                        Text(section.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (catalog?.canListSubagents == true) {
                DshSurfaceSection(stringResource(R.string.dsh_host_surfaces_subagents)) {
                    OutlinedTextField(
                        value = parentSessionId,
                        onValueChange = { parentSessionId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_parent_session)) },
                    )
                    TextButton(
                        onClick = { viewModel.loadSubagents(parentSessionId.trim()) },
                        enabled = parentSessionId.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.dsh_host_surfaces_load_subagents))
                    }
                    OutlinedTextField(
                        value = subagentPrompt,
                        onValueChange = { subagentPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dsh_host_surfaces_subagent_prompt)) },
                    )
                    uiState.subagents?.entries.orEmpty().forEach { entry ->
                        Text("${entry.id} · ${entry.mode ?: entry.reason.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            onClick = { viewModel.promptSubagent(parentSessionId.trim(), entry.id, subagentPrompt.trim()) },
                            enabled = parentSessionId.isNotBlank() && subagentPrompt.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.dsh_host_surfaces_send_prompt))
                        }
                        TextButton(
                            onClick = { viewModel.interruptSubagent(parentSessionId.trim(), entry.id) },
                            enabled = parentSessionId.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.dsh_host_surfaces_interrupt))
                        }
                    }
                }
            }
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
