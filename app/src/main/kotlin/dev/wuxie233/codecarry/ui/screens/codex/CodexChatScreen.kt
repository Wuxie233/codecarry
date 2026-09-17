@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wuxie233.codecarry.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.wuxie233.codecarry.data.codex.CodexApprovalKind
import dev.wuxie233.codecarry.data.codex.CodexMemoryMode
import dev.wuxie233.codecarry.data.codex.CodexServerRequest
import dev.wuxie233.codecarry.data.codex.CodexToolUserInputQuestion
import dev.wuxie233.codecarry.ui.screens.chat.ChatHeader
import dev.wuxie233.codecarry.ui.screens.chat.ChatResponseDock
import dev.wuxie233.codecarry.ui.screens.chat.chatComposerPrimaryWidth
import dev.wuxie233.codecarry.ui.screens.chat.isAmoledTheme
import dev.wuxie233.codecarry.data.codex.requestKey
import dev.wuxie233.codecarry.ui.components.ErrorStateCard
import dev.wuxie233.codecarry.ui.components.LoadingStateCard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexChatScreen(
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit = {},
    viewModel: CodexChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val accountUsage by viewModel.accountUsage.collectAsState()
    val usage = accountUsage[viewModel.serverId]
        ?: dev.wuxie233.codecarry.data.codex.CodexAccountUsageState()
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            codexChatVisibilityForEvent(event)?.let(viewModel::setChatVisible)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.setChatVisible(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setChatVisible(false)
        }
    }
    val draft = state.draft
    val composerValue = state.composerValue
    val slashSkillQuery = codexSkillCompletionTarget(composerValue)?.query
    LaunchedEffect(slashSkillQuery != null, state.isConnected, state.thread?.cwd) {
        if (slashSkillQuery != null && state.isConnected) viewModel.loadSkills()
    }
    val attachments = state.composerAttachments
    var relatedOpen by remember { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var goalOpen by remember { mutableStateOf(false) }
    var memoryOpen by remember { mutableStateOf(false) }
    // Turns already carry stable immutable identities; flattening duplicates every historical item per delta.
    val timeline = state.thread?.turns.orEmpty()

    fun submitDraft() {
        if (
            (draft.isNotBlank() || attachments.isNotEmpty()) &&
            !state.isLoading &&
            state.isConnected &&
            state.thread != null &&
            !state.isSending &&
            !state.isAwaitingAuthoritativeTurn &&
            !state.isSendConfirmationPending
        ) {
            viewModel.sendMessage(draft, attachments)
        }
    }

    if (usageOpen) {
        CodexUsageSheet(usage, viewModel::refreshUsage, onDismiss = { usageOpen = false })
    }

    if (relatedOpen) {
        AlertDialog(
            onDismissRequest = { relatedOpen = false; viewModel.hideRelatedThreads() },
            title = { Text(stringResource(R.string.chat_related_tasks)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(state.relatedThreads, key = { it.id }) { thread ->
                        TextButton(
                            enabled = thread.id != state.thread?.id,
                            onClick = { relatedOpen = false; viewModel.hideRelatedThreads(); onOpenThread(thread.id) },
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(thread.displayTitle?.take(72) ?: thread.id)
                                Text(
                                    stringResource(when {
                                        thread.id == state.thread?.parentThreadId -> R.string.chat_related_parent
                                        thread.id == state.thread?.id -> R.string.chat_related_current
                                        else -> R.string.chat_related_child
                                    }) + " · " + stringResource(when (thread.status.type) {
                                        "active" -> R.string.codex_working
                                        "systemError" -> R.string.chat_failure_turn
                                        else -> R.string.codex_chat_ready
                                    }),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { relatedOpen = false; viewModel.hideRelatedThreads() }) { Text(stringResource(R.string.chat_subagents_close)) } },
        )
    }

    Scaffold(
        topBar = {
            ChatHeader(
                title = state.thread?.displayTitle?.take(72)
                    ?: stringResource(R.string.codex_title),
                context = state.thread?.cwd.orEmpty(),
                backendLabel = stringResource(R.string.codex_title),
                statusLabel = stringResource(when {
                    state.isLoading -> R.string.codex_opening_thread
                    !state.isConnected -> R.string.codex_chat_disconnected
                    state.isSendConfirmationPending -> R.string.codex_send_confirmation_pending
                    state.isAwaitingAuthoritativeTurn -> R.string.codex_chat_waiting_turn
                    state.activeTurnId != null -> R.string.codex_working
                    else -> R.string.codex_chat_ready
                }),
                usageSummary = null,
                additionalActions = {
                    CodexUsageAction(usage, onClick = {
                        usageOpen = true
                        viewModel.refreshUsage()
                    })
                },
                canStop = state.activeTurnId != null,
                showSubagents = true,
                runningSubagentCount = state.relatedThreads.count { it.id != state.thread?.id && it.status.type == "active" },
                showTerminal = false,
                showOverflow = true,
                onNavigateBack = onNavigateBack,
                onStop = viewModel::interruptTurn,
                onToggleSubagents = { relatedOpen = true; viewModel.refreshRelatedThreads() },
                onOpenTerminal = {},
                onOpenOverflow = { menuExpanded = true },
                overflowMenu = {
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_subagents_title)) },
                                onClick = { menuExpanded = false; relatedOpen = true; viewModel.refreshRelatedThreads() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_copy_loaded_messages)) },
                                enabled = state.thread?.turns?.isNotEmpty() == true,
                                onClick = {
                                    menuExpanded = false
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(
                                        dev.wuxie233.codecarry.ui.screens.chat.codexConversationDocument(state.thread?.turns.orEmpty()).toMarkdown(),
                                    ))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.codex_chat_status)) },
                                onClick = { menuExpanded = false; statusOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.codex_goal)) },
                                leadingIcon = { Icon(Icons.Default.TrackChanges, contentDescription = null) },
                                onClick = { menuExpanded = false; goalOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.codex_memory)) },
                                leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null) },
                                onClick = { menuExpanded = false; memoryOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.codex_thread_rename_action)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = { menuExpanded = false; renameOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.codex_compact_context)) },
                                leadingIcon = { Icon(Icons.Default.Compress, contentDescription = null) },
                                onClick = { menuExpanded = false; viewModel.compactThread() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.codex_reconnect)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = { menuExpanded = false; viewModel.connectAndLoad() },
                            )
                        }
                },
            )
        },
        bottomBar = {
            val dockItems = remember(state.pendingRequests) {
                buildCodexResponseDockItems(state.pendingRequests)
            }
            ChatResponseDock(
                items = dockItems,
                modifier = Modifier
                    .chatComposerPrimaryWidth()
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                responseContent = { item ->
                    val request = item.codexRequest(state.pendingRequests)
                    if (request != null) {
                        val requestKey = request.id.requestKey()
                        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            if (item == dockItems.first()) {
                                Text(
                                    stringResource(R.string.codex_chat_pending_count, state.pendingRequests.size),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            state.requestErrors[requestKey]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Box {
                                androidx.compose.runtime.CompositionLocalProvider(
                                    LocalCodexRequestEnabled provides (requestKey !in state.replyingRequestIds),
                                ) {
                                    CodexRequestCard(
                                        request = request,
                                        thread = state.thread,
                                        unlockToken = codexRequestUnlockToken(requestKey, state.requestErrors),
                                        onDecision = { viewModel.answerApproval(request, it) },
                                        onAnswer = { viewModel.answerUserInput(request, it) },
                                        onElicitation = { action, content -> viewModel.answerElicitation(request, action, content) },
                                        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                                        onCancel = { viewModel.cancelRequest(request) },
                                    )
                                }
                                if (requestKey in state.replyingRequestIds) {
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                                            .clickable { },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                },
                composerContent = {
                    if (state.isSendConfirmationPending) {
                        Column(
                            modifier = Modifier.padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                stringResource(R.string.codex_send_confirmation_pending),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = viewModel::recheckPendingSend, enabled = !state.isSending) {
                                    Text(stringResource(R.string.codex_check_message_status))
                                }
                                TextButton(onClick = viewModel::allowPendingResend, enabled = !state.isSending) {
                                    Text(stringResource(R.string.codex_allow_resend))
                                }
                            }
                        }
                    }
                    if (state.attachmentLimitReached) {
                        Text(
                            stringResource(R.string.codex_chat_payload_limit),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    CodexAttachmentChips(
                        attachments,
                        enabled = !state.isSending && !state.isSendConfirmationPending,
                        onRemove = viewModel::removeAttachment,
                    )
                    if (slashSkillQuery != null) {
                        CodexSlashSkillList(
                            query = slashSkillQuery,
                            skills = state.skills,
                            loading = state.skillsLoading,
                            error = state.skillsError,
                            enabled = state.isConnected && !state.isSending && !state.isSendConfirmationPending,
                            onRetry = viewModel::loadSkills,
                            onSelect = { viewModel.selectSlashSkill(it, composerValue) },
                        )
                    }
                    CodexComposerSurface(
                        value = composerValue,
                        onValueChange = viewModel::updateComposerValue,
                        placeholder = stringResource(if (state.activeTurnId != null) R.string.codex_chat_steer_hint else R.string.codex_message_hint),
                        canSend = (draft.isNotBlank() || attachments.isNotEmpty()) &&
                            !state.isLoading && state.isConnected && state.thread != null &&
                            !state.isSending && !state.isAwaitingAuthoritativeTurn && !state.isSendConfirmationPending,
                        isSending = state.isSending || state.isAwaitingAuthoritativeTurn,
                        sendLabel = stringResource(if (state.activeTurnId != null) R.string.codex_chat_steer else R.string.chat_send),
                        onSend = ::submitDraft,
                        controls = {
                            CodexComposerControlRow(
                                state = state,
                                attachmentsEnabled = !state.isSending &&
                                    !state.isSendConfirmationPending &&
                                    attachments.size < 8,
                                onAddAttachment = viewModel::addAttachment,
                                onLoadSkills = viewModel::loadSkills,
                                onSearchFiles = viewModel::searchFiles,
                                onModel = viewModel::selectModel,
                                onEffort = viewModel::selectEffort,
                                onFast = viewModel::toggleFast,
                                onRetryModels = viewModel::retryModelsLoad,
                            )
                            CodexFastNotice(state, onDismiss = viewModel::dismissFastHint)
                        },
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading && state.thread?.turns.isNullOrEmpty() -> LoadingStateCard(Modifier.padding(16.dp), stringResource(R.string.codex_opening_thread))
                state.thread == null && state.error != null -> ErrorStateCard(
                    title = stringResource(R.string.codex_open_thread_failed),
                    message = if (state.ephemeralHistoryUnavailable) stringResource(R.string.codex_ephemeral_history_unavailable) else state.error.orEmpty(),
                    onRetry = viewModel::connectAndLoad,
                    modifier = Modifier.padding(16.dp),
                )
                else -> CodexTimelineViewport(
                    contentKey = listOf(timeline, state.plans, state.diffs, state.activeTurnId, state.error, state.threadFailure, state.turnFailures),
                    modifier = Modifier.fillMaxSize(),
                    onFollowTailChanged = viewModel::onFollowTailChanged,
                ) {
                    state.error?.let { error ->
                        item("operation-error") {
                            dev.wuxie233.codecarry.ui.components.ChatErrorNotice(
                                message = if (state.ephemeralHistoryUnavailable) stringResource(R.string.codex_ephemeral_history_unavailable) else error, detail = null,
                                title = stringResource(R.string.chat_failure_operation),
                                detailsLabel = stringResource(R.string.chat_failure_details),
                            )
                        }
                    }
                    if (state.canRetryConnection) {
                        item("retry-connection") {
                            TextButton(onClick = viewModel::connectAndLoad) { Text(stringResource(R.string.retry)) }
                        }
                    }
                    state.threadFailure?.let { failure ->
                        item("thread-error") { CodexFailureNotice(failure) }
                    }
                    state.thread?.turns.orEmpty().forEach { turn ->
                        items(turn.items, key = { item -> "${turn.id}:${item.id ?: item.type}" }) { item ->
                            CodexTimelineItem(
                                item = item,
                                onOpenThread = onOpenThread,
                                loadRemoteImage = viewModel::loadRemoteImage,
                                workspaceCwd = state.thread?.cwd,
                                onOpenWorkspaceFile = viewModel::openWorkspaceFile,
                            )
                        }
                        state.turnFailures[turn.id]?.let { failure ->
                            item("turn-error:${turn.id}") { CodexFailureNotice(failure) }
                        }
                    }
                    val knownTurnIds = state.thread?.turns.orEmpty().map { it.id }.toSet()
                    state.turnFailures.filterKeys { it !in knownTurnIds }.forEach { (turnId, failure) ->
                        item("turn-error:$turnId") { CodexFailureNotice(failure) }
                    }
                    state.thread?.turns?.lastOrNull()?.id?.let { turnId ->
                        state.plans[turnId]?.let { plan ->
                            item("plan:$turnId") {
                                CodexTurnPlanCard(
                                    plan = plan,
                                    workspaceCwd = state.thread?.cwd,
                                    onOpenWorkspaceFile = viewModel::openWorkspaceFile,
                                )
                            }
                        }
                        state.diffs[turnId]?.takeIf { it.isNotBlank() }?.let { diff ->
                            item("diff:$turnId") {
                                var expanded by rememberSaveable(turnId) { mutableStateOf(false) }
                                Column {
                                    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.codex_chat_turn_diff)) }
                                    if (expanded) CodexDiffContent(diff)
                                }
                            }
                        }
                    }
                    if (state.activeTurnId != null && state.thread?.turns?.lastOrNull { it.id == state.activeTurnId }?.items.orEmpty().none { it.type == "agentMessage" && it.text.isNullOrEmpty() }) {
                        item("working") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.codex_working), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (statusOpen) AlertDialog(
        onDismissRequest = { statusOpen = false },
        title = { Text(stringResource(R.string.codex_chat_status)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CodexGoalStatus(state.goalState, onRetry = viewModel::retryGoalLoad)
                when (val receipt = state.memoryModeReceipt) {
                    null -> Text(stringResource(R.string.codex_memory_unknown_line))
                    else -> Text(
                        stringResource(
                            R.string.codex_memory_receipt_line,
                            stringResource(codexMemoryModeLabelRes(receipt.mode)),
                        ),
                    )
                }
                CodexThreadPolicyStatus(state = state)
                state.tokenUsage?.let { usage ->
                    Text(stringResource(R.string.codex_chat_context_value, usage.last.totalTokens, usage.modelContextWindow?.toString() ?: "—"))
                    Text(stringResource(R.string.codex_chat_total_tokens, usage.total.totalTokens))
                } ?: Text(stringResource(R.string.codex_chat_context_unavailable))
                FlowRow {
                    TextButton(onClick = { statusOpen = false; goalOpen = true }) { Text(stringResource(R.string.codex_goal)) }
                    TextButton(onClick = { statusOpen = false; memoryOpen = true }) { Text(stringResource(R.string.codex_memory)) }
                    TextButton(onClick = { viewModel.compactThread(); statusOpen = false }) { Text(stringResource(R.string.codex_compact_context)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { statusOpen = false }) { Text(stringResource(R.string.close)) } },
    )
    if (renameOpen) RenameDialog(
        initial = state.thread?.name.orEmpty(),
        onDismiss = { renameOpen = false },
        onSave = { viewModel.renameThread(it); renameOpen = false },
    )
    if (goalOpen) GoalDialog(
        initial = state.goal?.objective.orEmpty(),
        status = state.goal?.status ?: "active",
        tokenBudget = state.goal?.tokenBudget,
        onDismiss = { goalOpen = false },
        onSave = { objective, status, budget -> viewModel.setGoal(objective, status, budget); goalOpen = false },
        onClear = { viewModel.clearGoal(); goalOpen = false },
    )
    if (memoryOpen) MemoryDialog(
        selected = state.memoryModeReceipt?.mode,
        onDismiss = { memoryOpen = false },
        onSelect = { viewModel.setMemoryMode(it); memoryOpen = false },
    )
    state.filePreview?.let { preview ->
        CodexFilePreviewSheet(
            preview = preview,
            onDismiss = viewModel::dismissFilePreview,
            onRetry = viewModel::retryFilePreview,
        )
    }
}

internal fun codexChatVisibilityForEvent(event: Lifecycle.Event): Boolean? = when (event) {
    Lifecycle.Event.ON_START -> true
    Lifecycle.Event.ON_STOP -> false
    else -> null
}

internal fun codexMemoryModeLabelRes(mode: CodexMemoryMode): Int = when (mode) {
    CodexMemoryMode.ENABLED -> R.string.codex_enable
    CodexMemoryMode.DISABLED -> R.string.codex_disable
}

/** Goal metadata renders its own loading/empty/error lifecycle with retry; it never gates chat readiness. */
@Composable
private fun CodexGoalStatus(
    goalState: CodexMetadataLoadState<dev.wuxie233.codecarry.data.codex.CodexGoal?>,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (goalState) {
            CodexMetadataLoadState.Loading -> Text(stringResource(R.string.loading))
            is CodexMetadataLoadState.Loaded -> {
                val goal = goalState.value
                if (goal == null) {
                    Text(stringResource(R.string.codex_chat_no_goal))
                } else {
                    Text(goal.objective)
                    Text(stringResource(when (goal.status) {
                        "active" -> R.string.codex_chat_goal_active
                        "complete", "completed" -> R.string.codex_chat_goal_complete
                        "paused" -> R.string.codex_chat_goal_paused
                        "blocked" -> R.string.codex_chat_goal_blocked
                        else -> R.string.codex_chat_goal_unknown
                    }))
                    Text(stringResource(R.string.codex_chat_goal_usage, goal.tokensUsed, goal.tokenBudget?.toString() ?: "—", goal.timeUsedSeconds))
                }
                if (goalState.stale && goal != null) {
                    Text(
                        stringResource(R.string.codex_metadata_stale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is CodexMetadataLoadState.Failed -> {
                goalState.staleValue?.let { goal ->
                    Text(goal.objective)
                    Text(stringResource(R.string.codex_chat_goal_usage, goal.tokensUsed, goal.tokenBudget?.toString() ?: "—", goal.timeUsedSeconds))
                    Text(
                        stringResource(R.string.codex_metadata_stale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.codex_goal_load_failed, goalState.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

/** Read-only display of the authoritative approval policy and sandbox scope from thread/resume. */
@Composable
private fun CodexThreadPolicyStatus(state: CodexChatUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.codex_policy_title), style = MaterialTheme.typography.labelLarge)
        if (!state.isConnected) {
            Text(stringResource(R.string.codex_policy_unavailable), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val policy = state.threadPolicy
        Text(
            stringResource(
                R.string.codex_policy_approvals,
                policy?.approvalPolicy?.let { token ->
                    codexApprovalPolicyLabelRes(token)?.let { res -> stringResource(res) } ?: token
                } ?: stringResource(R.string.codex_policy_unknown),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.codex_policy_sandbox,
                policy?.sandboxMode?.let { token ->
                    codexSandboxModeLabelRes(token)?.let { res -> stringResource(res) } ?: token
                } ?: stringResource(R.string.codex_policy_unknown),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        policy?.sandboxNetworkEnabled?.let { enabled ->
            Text(
                stringResource(
                    if (enabled) R.string.codex_policy_network_on else R.string.codex_policy_network_off,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!policy?.sandboxWritableRoots.isNullOrEmpty()) {
            Text(
                stringResource(R.string.codex_policy_roots, policy?.sandboxWritableRoots?.joinToString(", ").orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// Tokens arrive verbatim from the app-server; unknown tokens render as-is instead of a wrong label.
internal fun codexApprovalPolicyLabelRes(token: String): Int? = when (codexPolicyTokenKey(token)) {
    "untrusted" -> R.string.codex_policy_approval_untrusted
    "unlessTrusted" -> R.string.codex_policy_approval_unless_trusted
    "onFailure" -> R.string.codex_policy_approval_on_failure
    "onRequest" -> R.string.codex_policy_approval_on_request
    "never" -> R.string.codex_policy_approval_never
    else -> null
}

internal fun codexSandboxModeLabelRes(token: String): Int? = when (codexPolicyTokenKey(token)) {
    "readOnly" -> R.string.codex_policy_sandbox_read_only
    "workspaceWrite" -> R.string.codex_policy_sandbox_workspace_write
    "dangerFullAccess" -> R.string.codex_policy_sandbox_full_access
    "externalSandbox" -> R.string.codex_policy_sandbox_external
    else -> null
}

/** camelCase, kebab-case, and snake_case tokens resolve to the same key. */
internal fun codexPolicyTokenKey(token: String): String =
    token.split('-', '_').mapIndexed { index, part ->
        if (index == 0) part else part.replaceFirstChar { it.uppercase() }
    }.joinToString("")

@Composable
internal fun CodexComposerControlRow(
    state: CodexChatUiState,
    attachmentsEnabled: Boolean,
    onAddAttachment: (CodexComposerAttachment) -> Unit,
    onLoadSkills: () -> Unit,
    onSearchFiles: (String) -> Unit,
    onModel: (dev.wuxie233.codecarry.data.codex.CodexModel) -> Unit,
    onEffort: (String) -> Unit,
    onFast: () -> Unit = {},
    onRetryModels: () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CodexModelControls(state, onModel, onEffort, onRetryModels)
            CodexFastChip(
                enabled = state.fastEnabled,
                available = (state.fastAvailable || state.fastEnabled) && state.isConnected && !state.isLoading && !state.isSending,
                pending = state.fastPending,
                onClick = onFast,
            )
        }
        CodexAttachmentPicker(
            enabled = attachmentsEnabled,
            skills = state.skills,
            files = state.files,
            skillsLoading = state.skillsLoading,
            skillsError = state.skillsError,
            loading = state.attachmentsLoading,
            error = state.attachmentsError,
            onLoadSkills = onLoadSkills,
            onSearchFiles = onSearchFiles,
            onAdd = onAddAttachment,
        )
    }
}

@Composable
internal fun CodexModelControls(
    state: CodexChatUiState,
    onModel: (dev.wuxie233.codecarry.data.codex.CodexModel) -> Unit,
    onEffort: (String) -> Unit,
    onRetryModels: () -> Unit = {},
) {
    var modelsOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }
    // The model catalog owns an independent lifecycle: loading and failure states stay visible
    // as compact chips and never block the rest of the composer.
    when (state.modelsState) {
        CodexMetadataLoadState.Loading -> if (state.models.isEmpty()) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .padding(horizontal = 3.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.codex_model),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        } else Unit
        is CodexMetadataLoadState.Failed -> if (state.models.isEmpty()) {
            CodexComposerChip(
                label = stringResource(R.string.codex_models_unavailable),
                onClick = onRetryModels,
            )
        } else Unit
        is CodexMetadataLoadState.Loaded -> Unit
    }
    if (state.models.isEmpty()) return
    if (state.modelsState is CodexMetadataLoadState.Failed) {
        // Stale-but-valid catalog stays usable with a visible retry marker.
        CodexComposerChip(
            label = stringResource(R.string.codex_models_stale_retry),
            onClick = onRetryModels,
        )
    }
    val modelLabel = state.selectedModel?.displayName?.ifBlank { state.selectedModel.model }
        ?: stringResource(R.string.codex_model)
    Box {
        CodexComposerChip(
            label = modelLabel,
            onClick = { modelsOpen = true },
        )
        DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
            state.models.filterNot { it.hidden }.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.displayName.ifBlank { model.model })
                            if (model.description.isNotBlank()) {
                                Text(model.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                    },
                    leadingIcon = if (model == state.selectedModel) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = { onModel(model); modelsOpen = false },
                )
            }
        }
    }
    val efforts = state.selectedModel?.supportedReasoningEfforts.orEmpty()
    if (efforts.isNotEmpty()) {
        Box {
            CodexComposerChip(
                label = state.selectedEffort?.replaceFirstChar { it.uppercase() }
                    ?: stringResource(R.string.codex_reasoning),
                onClick = { effortOpen = true },
                emphasized = state.selectedEffort != null,
            )
            DropdownMenu(expanded = effortOpen, onDismissRequest = { effortOpen = false }) {
                efforts.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.reasoningEffort)
                                if (option.description.isNotBlank()) {
                                    Text(option.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        leadingIcon = if (option.reasoningEffort == state.selectedEffort) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = { onEffort(option.reasoningEffort); effortOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun CodexComposerChip(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val color = if (emphasized) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
        Icon(
            Icons.Default.UnfoldMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

private val LocalCodexRequestEnabled = androidx.compose.runtime.compositionLocalOf { true }

@Composable
private fun CodexRequestCard(
    request: CodexServerRequest,
    thread: dev.wuxie233.codecarry.data.codex.CodexThread?,
    unlockToken: Int = 0,
    onDecision: (String) -> Unit,
    onAnswer: (Map<String, List<String>>) -> Unit,
    onElicitation: (String, JsonElement?) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val approval = request.approval
    val approvalPresentation = remember(request, thread) {
        codexApprovalPresentation(request, thread)
    }
    val userInput = request.userInput
    val elicitation = request.takeIf { it.method == "mcpServer/elicitation/request" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (userInput != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        @Suppress("DEPRECATION")
                        Icons.Default.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.chat_question_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            } else {
                Text(
                    when {
                        approval?.kind == CodexApprovalKind.COMMAND_EXECUTION -> stringResource(R.string.codex_request_run_command)
                        approval?.kind == CodexApprovalKind.FILE_CHANGE -> stringResource(R.string.codex_request_apply_files)
                        approval?.kind == CodexApprovalKind.PERMISSIONS -> stringResource(R.string.codex_request_grant_permissions)
                        elicitation != null -> stringResource(
                            R.string.codex_request_named_needs_input,
                            request.params.string("serverName") ?: "MCP",
                        )
                        else -> stringResource(R.string.codex_request)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            approval?.command?.let {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            approval?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            approvalPresentation.details.forEach { detail ->
                Text(
                    text = when (detail.kind) {
                        CodexApprovalDetailKind.WORKING_DIRECTORY -> stringResource(R.string.codex_approval_working_directory, detail.value)
                        CodexApprovalDetailKind.ENVIRONMENT -> stringResource(R.string.codex_approval_environment, detail.value)
                        CodexApprovalDetailKind.NETWORK_TARGET -> stringResource(R.string.codex_approval_network_target, detail.value)
                        CodexApprovalDetailKind.ADDITIONAL_PERMISSIONS -> stringResource(R.string.codex_approval_additional_permissions, detail.value)
                        CodexApprovalDetailKind.FILE_CHANGE -> detail.value
                        CodexApprovalDetailKind.GRANT_ROOT -> stringResource(R.string.codex_approval_grant_root, detail.value)
                        CodexApprovalDetailKind.PERMISSIONS -> stringResource(R.string.codex_approval_permissions, detail.value)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (approval != null) {
                if (!approvalPresentation.canApprove) {
                    Text(
                        stringResource(R.string.codex_approval_details_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    approvalPresentation.decisions.forEach { decision ->
                        val enabled = !isCodexApprovalDecision(decision) || approvalPresentation.canApprove
                        when (decision) {
                            "decline" -> OutlinedButton(enabled = LocalCodexRequestEnabled.current, onClick = { onDecision(decision) }) {
                                Text(stringResource(R.string.codex_deny))
                            }
                            "cancel" -> OutlinedButton(enabled = LocalCodexRequestEnabled.current, onClick = { onDecision(decision) }) {
                                Text(stringResource(R.string.codex_cancel_turn))
                            }
                            "accept" -> Button(
                                onClick = { onDecision(decision) },
                                enabled = LocalCodexRequestEnabled.current && (enabled),
                            ) {
                                Text(stringResource(R.string.notification_permission_action_allow_once))
                            }
                            "acceptForSession" -> TextButton(
                                onClick = { onDecision(decision) },
                                enabled = LocalCodexRequestEnabled.current && (enabled),
                            ) {
                                Text(stringResource(R.string.codex_for_session))
                            }
                        }
                    }
                }
            } else if (userInput != null) {
                UserInputQuestions(
                    questions = userInput.questions,
                    formKey = request.id.requestKey(),
                    unlockToken = unlockToken,
                    onAnswer = onAnswer,
                    onCancel = onCancel,
                )
            } else if (elicitation != null) {
                McpElicitationContent(request, onElicitation, onOpenUrl)
            } else {
                OutlinedButton(enabled = LocalCodexRequestEnabled.current, onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun McpElicitationContent(
    request: CodexServerRequest,
    onReply: (String, JsonElement?) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val mode = request.params.string("mode")
    val message = request.params.string("message").orEmpty()
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
    when (mode) {
        "form" -> McpForm(
            schema = request.params["requestedSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            onSubmit = { onReply("accept", it) },
            onDecline = { onReply("decline", null) },
            onCancel = { onReply("cancel", null) },
        )
        "url" -> {
            val url = request.params.string("url").orEmpty()
            Button(onClick = { onOpenUrl(url) }, enabled = LocalCodexRequestEnabled.current && (url.startsWith("https://"))) {
                Text(stringResource(R.string.codex_open_link))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(enabled = LocalCodexRequestEnabled.current, onClick = { onReply("cancel", null) }) { Text(stringResource(R.string.cancel)) }
                OutlinedButton(enabled = LocalCodexRequestEnabled.current, onClick = { onReply("decline", null) }) { Text(stringResource(R.string.codex_decline)) }
                Button(enabled = LocalCodexRequestEnabled.current, onClick = { onReply("accept", null) }) { Text(stringResource(R.string.codex_continue)) }
            }
        }
        else -> OutlinedButton(enabled = LocalCodexRequestEnabled.current, onClick = { onReply("cancel", null) }) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun McpForm(
    schema: JsonObject,
    onSubmit: (JsonObject) -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (schema["required"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    val values = remember(schema) {
        mutableStateMapOf<String, JsonElement>().apply {
            initialMcpFormValues(properties).forEach { (name, value) -> put(name, value) }
        }
    }
    properties.forEach { (name, raw) ->
        val field = raw as? JsonObject ?: return@forEach
        val type = field.string("type").orEmpty()
        val label = field.string("title") ?: name
        val description = field.string("description")
        val enumValues = field.enumValues()
        when {
            type == "array" && enumValues.isNotEmpty() -> {
                Text(label, style = MaterialTheme.typography.labelLarge)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val selected = (values[name] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                enumValues.forEach { (value, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = value in selected,
                            onCheckedChange = { checked ->
                                val next = if (checked) selected + value else selected - value
                                values[name] = JsonArray(next.map(::JsonPrimitive))
                            },
                        )
                        Text(title)
                    }
                }
            }
            type == "boolean" -> {
                val checked = displayedMcpBoolean(values[name], field)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { values[name] = JsonPrimitive(it) })
                    Text(label)
                }
            }
            enumValues.isNotEmpty() -> {
                Text(label, style = MaterialTheme.typography.labelLarge)
                enumValues.forEach { (value, title) ->
                    OutlinedButton(enabled = LocalCodexRequestEnabled.current,
                        onClick = { values[name] = JsonPrimitive(value) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(title) }
                }
            }
            else -> {
                val current = (values[name] as? JsonPrimitive)?.contentOrNull
                    ?: (field["default"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                OutlinedTextField(
                    value = current,
                    onValueChange = { input ->
                        values[name] = when (type) {
                            "integer" -> input.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(input)
                            "number" -> input.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(input)
                            else -> JsonPrimitive(input)
                        }
                    },
                    label = { Text(label) },
                    supportingText = description?.let { text -> ({ Text(text) }) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (type == "integer" || type == "number") KeyboardType.Number else KeyboardType.Text,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    val valid = properties.all { (name, raw) ->
        val field = raw as? JsonObject ?: return@all false
        validateMcpFormValue(field, values[name], name in required)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(enabled = LocalCodexRequestEnabled.current, onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        OutlinedButton(enabled = LocalCodexRequestEnabled.current, onClick = onDecline) { Text(stringResource(R.string.codex_decline)) }
        Button(
            onClick = { onSubmit(buildMcpFormSubmission(properties, values.toMap())) },
            enabled = LocalCodexRequestEnabled.current && (valid),
        ) { Text(stringResource(R.string.codex_submit)) }
    }
}

/**
 * Displayed and submitted values start identical. A boolean without a schema default
 * initializes to an explicit false so a required unchecked boolean submits directly.
 */
internal fun initialMcpFormValues(properties: JsonObject): Map<String, JsonElement> = buildMap {
    properties.forEach { (name, raw) ->
        val field = raw as? JsonObject ?: return@forEach
        when (field.string("type").orEmpty()) {
            "boolean" -> put(
                name,
                (field["default"] as? JsonPrimitive)?.takeIf { it.booleanOrNull != null } ?: JsonPrimitive(false),
            )
            else -> field["default"]?.let { defaultValue -> put(name, defaultValue) }
        }
    }
}

/** The value the checkbox renders for a field; it must always equal what submission sends. */
internal fun displayedMcpBoolean(value: JsonElement?, field: JsonObject): Boolean =
    (value as? JsonPrimitive)?.booleanOrNull
        ?: (field["default"] as? JsonPrimitive)?.booleanOrNull
        ?: false

internal fun buildMcpFormSubmission(properties: JsonObject, values: Map<String, JsonElement>): JsonObject = buildJsonObject {
    properties.forEach { (name, raw) ->
        val defaultValue = (raw as? JsonObject)?.get("default")
        (values[name] ?: defaultValue)?.let { put(name, it) }
    }
}

private fun JsonObject.enumValues(): List<Pair<String, String>> {
    if (string("type") == "array") {
        return (this["items"] as? JsonObject)?.enumValues().orEmpty()
    }
    val direct = (this["enum"] as? JsonArray).orEmpty().mapNotNull { it as? JsonPrimitive }
    val names = (this["enumNames"] as? JsonArray).orEmpty().mapNotNull { it as? JsonPrimitive }
    if (direct.isNotEmpty()) return direct.mapIndexed { index, value ->
        value.content to (names.getOrNull(index)?.contentOrNull ?: value.content)
    }
    val variants = (this["oneOf"] as? JsonArray) ?: (this["anyOf"] as? JsonArray)
    return variants.orEmpty().mapNotNull { option ->
        val objectValue = option as? JsonObject ?: return@mapNotNull null
        val value = (objectValue["const"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        value to (objectValue.string("title") ?: value)
    }
}

internal fun shouldClearCodexDraft(currentDraft: String, result: CodexSendResult): Boolean =
    result.accepted && currentDraft.trim() == result.content

internal fun validateMcpFormValue(
    field: JsonObject,
    value: JsonElement?,
    required: Boolean,
): Boolean {
    if (value == null) return !required
    val type = field.string("type").orEmpty()
    return when (type) {
        "boolean" -> (value as? JsonPrimitive)?.booleanOrNull != null
        "integer" -> {
            val number = (value as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return false
            val minimum = (field["minimum"] as? JsonPrimitive)?.doubleOrNull
            val maximum = (field["maximum"] as? JsonPrimitive)?.doubleOrNull
            (minimum == null || number >= minimum) && (maximum == null || number <= maximum)
        }
        "number" -> {
            val number = (value as? JsonPrimitive)?.doubleOrNull ?: return false
            val minimum = (field["minimum"] as? JsonPrimitive)?.doubleOrNull
            val maximum = (field["maximum"] as? JsonPrimitive)?.doubleOrNull
            (minimum == null || number >= minimum) && (maximum == null || number <= maximum)
        }
        "array" -> {
            val size = (value as? JsonArray)?.size ?: return false
            val minimum = (field["minItems"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val maximum = (field["maxItems"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            (!required || size > 0) && (minimum == null || size >= minimum) && (maximum == null || size <= maximum)
        }
        else -> {
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return false
            val minimum = (field["minLength"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val maximum = (field["maxLength"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            (!required || text.isNotBlank()) && (minimum == null || text.length >= minimum) &&
                (maximum == null || text.length <= maximum)
        }
    }
}

@Composable
private fun UserInputQuestions(
    questions: List<CodexToolUserInputQuestion>,
    formKey: String,
    unlockToken: Int,
    onAnswer: (Map<String, List<String>>) -> Unit,
    onCancel: () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val requestEnabled = LocalCodexRequestEnabled.current
    val needsExplicitSubmit = codexUserInputNeedsExplicitSubmit(questions)
    var submitted by rememberSaveable(formKey) { mutableStateOf(false) }
    LaunchedEffect(formKey, unlockToken) {
        if (unlockToken != 0) submitted = false
    }
    val answers = remember(formKey) { mutableStateMapOf<String, List<String>>() }
    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary
    val enabled = requestEnabled && !submitted

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            questions.forEach { question ->
                if (question.header.isNotBlank()) {
                    Text(
                        text = question.header,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                    )
                }
                if (question.question.isNotBlank()) {
                    Text(
                        text = question.question,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(2.dp))
                if (question.multiple) {
                    question.options.forEach { option ->
                        val checked = option.label in answers[question.id].orEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (checked) accentColor.copy(alpha = 0.12f) else Color.Transparent)
                                .toggleable(
                                    value = checked,
                                    enabled = enabled,
                                    role = Role.Checkbox,
                                    onValueChange = { isChecked ->
                                        val current = answers[question.id].orEmpty().toMutableList()
                                        if (isChecked) {
                                            if (option.label !in current) current.add(option.label)
                                        } else {
                                            current.remove(option.label)
                                        }
                                        answers[question.id] = current.toList()
                                    },
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = accentColor,
                                    uncheckedColor = contentColor.copy(alpha = 0.5f),
                                ),
                            )
                            CodexQuestionOptionCopy(option.label, option.description, contentColor)
                        }
                    }
                } else {
                    question.options.forEach { option ->
                        val isSelected = option.label in answers[question.id].orEmpty()
                        Surface(
                            onClick = {
                                if (!enabled) return@Surface
                                if (!needsExplicitSubmit) {
                                    codexInstantOptionAnswer(questions, question.id, option.label)?.let { payload ->
                                        submitted = true
                                        onAnswer(payload)
                                    }
                                } else {
                                    answers[question.id] = listOf(option.label)
                                }
                            },
                            enabled = enabled,
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                isSelected -> accentColor.copy(alpha = 0.12f)
                                isAmoled -> Color.Black
                                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            },
                            border = if (!isSelected && isAmoled) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSelected) accentColor else accentColor.copy(alpha = 0.7f),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) accentColor else contentColor,
                                    )
                                    if (option.description.isNotBlank()) {
                                        Text(
                                            text = option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (question.allowsCustomAnswer()) {
                    CodexCustomAnswerField(
                        question = question,
                        answers = answers,
                        enabled = enabled,
                        needsExplicitSubmit = needsExplicitSubmit,
                        accentColor = accentColor,
                        onInstantSubmit = { payload ->
                            submitted = true
                            onAnswer(payload)
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = {
                        submitted = true
                        onCancel()
                    },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.chat_dismiss), style = MaterialTheme.typography.labelMedium)
                }
                if (needsExplicitSubmit) {
                    Button(
                        onClick = {
                            submitted = true
                            onAnswer(codexUserInputAnswerPayload(questions, answers.toMap()))
                        },
                        enabled = enabled && codexUserInputDraftComplete(questions, answers),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(stringResource(R.string.question_submit), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
    }
}

@Composable
private fun CodexQuestionOptionCopy(
    label: String,
    description: String,
    contentColor: Color,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = contentColor)
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun CodexCustomAnswerField(
    question: CodexToolUserInputQuestion,
    answers: SnapshotStateMap<String, List<String>>,
    enabled: Boolean,
    needsExplicitSubmit: Boolean,
    accentColor: Color,
    onInstantSubmit: (Map<String, List<String>>) -> Unit,
) {
    val currentAnswers = answers[question.id].orEmpty()
    val customAnswer = currentAnswers.firstOrNull { answer -> question.options.none { it.label == answer } }
    if (customAnswer != null) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = accentColor.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.RadioButtonChecked,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accentColor,
                )
                Text(
                    text = if (question.isSecret) "•".repeat(customAnswer.length.coerceAtLeast(1)) else customAnswer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accentColor,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { if (enabled) answers[question.id] = emptyList() },
                    enabled = enabled,
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.chat_clear),
                        modifier = Modifier.size(16.dp),
                        tint = accentColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
        return
    }

    var isEditingCustom by rememberSaveable(key = "qc_editing_${question.id}") { mutableStateOf(question.options.isEmpty()) }
    var customText by rememberSaveable(key = "qc_customtext_${question.id}") { mutableStateOf("") }
    if (!isEditingCustom) {
        Surface(
            onClick = { isEditingCustom = true },
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accentColor.copy(alpha = 0.7f),
                )
                Text(
                    text = stringResource(R.string.question_custom_answer),
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor.copy(alpha = 0.7f),
                )
            }
        }
        return
    }

    OutlinedTextField(
        value = customText,
        onValueChange = { customText = it },
        enabled = enabled,
        placeholder = {
            Text(stringResource(R.string.chat_type_answer), style = MaterialTheme.typography.bodySmall)
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        shape = RoundedCornerShape(8.dp),
        visualTransformation = if (question.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (question.isSecret) KeyboardType.Password else KeyboardType.Text,
            autoCorrectEnabled = !question.isSecret,
            imeAction = ImeAction.Send,
        ),
        trailingIcon = {
            Row {
                IconButton(
                    onClick = {
                        val trimmed = customText.trim()
                        if (trimmed.isBlank()) return@IconButton
                        if (!needsExplicitSubmit) {
                            codexInstantCustomAnswer(questions = listOf(question), questionId = question.id, customText = trimmed)
                                ?.let(onInstantSubmit)
                        } else {
                            answers[question.id] = listOf(trimmed)
                            isEditingCustom = false
                            customText = ""
                        }
                    },
                    enabled = customText.isNotBlank() && enabled,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.question_submit),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (question.options.isNotEmpty()) {
                    IconButton(onClick = { isEditingCustom = false; customText = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.question_cancel),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.codex_thread_rename)) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.codex_thread_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun GoalDialog(
    initial: String,
    status: String,
    tokenBudget: Long?,
    onDismiss: () -> Unit,
    onSave: (String, String, Long?) -> Unit,
    onClear: () -> Unit,
) {
    var objective by rememberSaveable(initial) { mutableStateOf(initial) }
    var selectedStatus by rememberSaveable(status) { mutableStateOf(status) }
    var budget by rememberSaveable(tokenBudget) { mutableStateOf(tokenBudget?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.codex_goal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(objective, { objective = it }, label = { Text(stringResource(R.string.codex_goal_objective)) }, minLines = 2)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "active" to stringResource(R.string.codex_goal_active),
                        "paused" to stringResource(R.string.codex_goal_paused),
                        "complete" to stringResource(R.string.codex_goal_complete),
                    ).forEach { (option, label) ->
                        OutlinedButton(onClick = { selectedStatus = option }) {
                            if (selectedStatus == option) Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                            Text(label)
                        }
                    }
                }
                OutlinedTextField(
                    budget,
                    { budget = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.codex_goal_token_budget)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(objective, selectedStatus, budget.toLongOrNull()) },
                enabled = objective.isNotBlank(),
            ) { Text(stringResource(R.string.codex_thread_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Icon(Icons.Default.DeleteOutline, contentDescription = null); Text(stringResource(R.string.codex_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun MemoryDialog(
    selected: CodexMemoryMode?,
    onDismiss: () -> Unit,
    onSelect: (CodexMemoryMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.codex_memory_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.codex_memory_message))
                listOf(
                    CodexMemoryMode.ENABLED to stringResource(R.string.codex_enable),
                    CodexMemoryMode.DISABLED to stringResource(R.string.codex_disable),
                ).forEach { (mode, label) ->
                    OutlinedButton(onClick = { onSelect(mode) }, modifier = Modifier.fillMaxWidth()) {
                        if (selected == mode) Icon(Icons.Default.Check, contentDescription = null)
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CodexFailureNotice(failure: dev.wuxie233.codecarry.data.codex.CodexFailure) {
    dev.wuxie233.codecarry.ui.components.ChatErrorNotice(
        message = failure.message,
        detail = listOfNotNull(failure.code, failure.detail).joinToString("\n").takeIf { it.isNotBlank() },
        title = stringResource(if (failure.willRetry) R.string.chat_failure_retrying else R.string.chat_failure_turn),
        detailsLabel = stringResource(R.string.chat_failure_details),
    )
}
