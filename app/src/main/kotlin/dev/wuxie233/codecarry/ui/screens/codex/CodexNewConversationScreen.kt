@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wuxie233.codecarry.R

@Composable
fun CodexNewConversationScreen(
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    viewModel: CodexNewConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val projectName by viewModel.projectName.collectAsStateWithLifecycle()
    val openThread by viewModel.openThread.collectAsStateWithLifecycle()
    LaunchedEffect(openThread) { openThread?.let(onOpenThread) }
    var abandonDialog by remember { mutableStateOf(false) }
    if (abandonDialog) AlertDialog(
        onDismissRequest = { abandonDialog = false },
        title = { Text(stringResource(R.string.codex_new_abandon)) },
        text = { Text(stringResource(if (viewModel.submissionMayHaveSent) R.string.codex_new_abandon_submission_message else R.string.codex_new_abandon_message)) },
        confirmButton = { TextButton(onClick = {
            viewModel.abandonWait()
            abandonDialog = false
        }) { Text(stringResource(R.string.codex_new_edit_again)) } },
        dismissButton = { TextButton(onClick = { abandonDialog = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    val enabled = !state.isSending && !state.isSendConfirmationPending
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(projectName ?: stringResource(R.string.codex_new_title)) },
                navigationIcon = { IconButton(onClick = onNavigateBack, enabled = !state.isSending) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                } })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.codex_new_prompt), style = MaterialTheme.typography.headlineSmall)
                    projectName?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (state.isSendConfirmationPending && viewModel.canRecheck) {
                    TextButton(onClick = { viewModel.recheck() }, enabled = !state.isSending) {
                        Text(stringResource(R.string.codex_check_message_status))
                    }
                }
                if (state.isSendConfirmationPending && viewModel.canAbandonWait) {
                    TextButton(onClick = { abandonDialog = true }, enabled = !state.isSending) {
                        Text(stringResource(R.string.codex_new_abandon))
                    }
                } else if (!state.isConnected) {
                    TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.codex_new_retry)) }
                }
            }
            if (state.attachmentLimitReached) Text(stringResource(R.string.codex_new_attachment_limit), color = MaterialTheme.colorScheme.error)
            if (state.isHistoryAttachmentRecoveryRequired) CodexAttachmentRecoveryNotice(enabled, viewModel::reviewMissingAttachments)
            CodexAttachmentChips(state.composerAttachments, enabled, viewModel::removeAttachment)
            CodexComposerSurface(
                value = state.composerValue,
                onValueChange = viewModel::updateDraft,
                placeholder = stringResource(R.string.codex_message_hint),
                enabled = enabled,
                canSend = !state.isLoading && state.isConnected && !state.isHistoryAttachmentRecoveryRequired &&
                    (state.draft.isNotBlank() || state.composerAttachments.isNotEmpty()),
                isSending = state.isSending,
                sendLabel = stringResource(R.string.codex_new_send),
                onSend = viewModel::send,
                controls = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CodexAttachmentPicker(enabled = enabled, skills = state.skills,
                            skillsLoading = state.skillsLoading, skillsError = state.skillsError,
                            files = state.files, loading = state.attachmentsLoading, error = state.attachmentsError,
                            onLoadSkills = { viewModel.loadSkills() }, onSearchFiles = { viewModel.searchFiles(it) },
                            onAdd = viewModel::addAttachment)
                        CodexModelControls(state.copy(isRewritingHistory = !enabled), viewModel::selectModel,
                            viewModel::selectEffort, viewModel::loadModels)
                        if (state.selectedModel.codexFastTier() != null) {
                            FilterChip(selected = state.fastEnabled, onClick = viewModel::toggleFast,
                                enabled = enabled, label = { Text(stringResource(R.string.codex_fast)) })
                        }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
