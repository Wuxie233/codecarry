@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wuxie233.codecarry.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.wuxie233.codecarry.data.codex.CodexApprovalKind
import dev.wuxie233.codecarry.data.codex.CodexMemoryMode
import dev.wuxie233.codecarry.data.codex.CodexServerRequest
import dev.wuxie233.codecarry.ui.screens.chat.chatTextOverflow
import dev.wuxie233.codecarry.data.codex.requestKey
import dev.wuxie233.codecarry.ui.components.ErrorStateCard
import dev.wuxie233.codecarry.ui.components.LoadingStateCard
import dev.wuxie233.codecarry.ui.screens.chat.MessageMarkdownContent
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
    val attachments = state.composerAttachments
    var statusOpen by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var goalOpen by remember { mutableStateOf(false) }
    var memoryOpen by remember { mutableStateOf(false) }
    val timeline = remember(state.thread) {
        state.thread?.turns.orEmpty().flatMap { turn -> turn.items.map { turn.id to it } }
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.thread?.name?.takeIf(String::isNotBlank)
                                ?: state.thread?.preview?.lineSequence()?.firstOrNull()?.take(48)
                                ?: stringResource(R.string.codex_title),
                            maxLines = 1,
                        )
                        state.thread?.cwd?.let { cwd ->
                            Text(
                                text = cwd,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.activeTurnId != null) {
                        IconButton(onClick = viewModel::interruptTurn) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.codex_stop_turn))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.codex_thread_actions))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                state.pendingRequests.firstOrNull()?.let { request ->
                    val requestKey = request.id.requestKey()
                    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.codex_chat_pending_count, state.pendingRequests.size), style = MaterialTheme.typography.labelMedium)
                        state.requestErrors[requestKey]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        key(requestKey) {
                            Box {
                                androidx.compose.runtime.CompositionLocalProvider(LocalCodexRequestEnabled provides (requestKey !in state.replyingRequestIds)) {
                                    CodexRequestCard(
                                        request = request,
                                        thread = state.thread,
                                        onDecision = { viewModel.answerApproval(request, it) },
                                        onAnswer = { viewModel.answerUserInput(request, it) },
                                        onElicitation = { action, content -> viewModel.answerElicitation(request, action, content) },
                                        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                                        onCancel = { viewModel.cancelRequest(request) },
                                    )
                                }
                                if (requestKey in state.replyingRequestIds) {
                                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)).clickable { }, contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(when {
                        state.isLoading -> R.string.codex_opening_thread
                        !state.isConnected -> R.string.codex_chat_disconnected
                        state.isSendConfirmationPending -> R.string.codex_send_confirmation_pending
                        state.isAwaitingAuthoritativeTurn -> R.string.codex_chat_waiting_turn
                        state.activeTurnId != null -> R.string.codex_chat_steering
                        else -> R.string.codex_chat_ready
                    }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                if (state.attachmentLimitReached) Text(stringResource(R.string.codex_chat_payload_limit), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                CodexAttachmentChips(attachments, enabled = !state.isSending && !state.isSendConfirmationPending, onRemove = viewModel::removeAttachment)
                ModelControls(
                    state = state,
                    onModel = viewModel::selectModel,
                    onEffort = viewModel::selectEffort,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CodexAttachmentPicker(
                        enabled = !state.isSending && !state.isSendConfirmationPending && attachments.size < 8,
                        skills = state.skills, files = state.files,
                        loading = state.attachmentsLoading, error = state.attachmentsError,
                        onLoadSkills = viewModel::loadSkills, onSearchFiles = viewModel::searchFiles,
                        onAdd = viewModel::addAttachment,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = viewModel::updateDraft,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(if (state.activeTurnId != null) R.string.codex_chat_steer_hint else R.string.codex_message_hint)) },
                        minLines = 1,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            submitDraft()
                        }),
                    )
                    FilledIconButton(
                        onClick = ::submitDraft,
                        enabled = (draft.isNotBlank() || attachments.isNotEmpty()) &&
                            !state.isLoading &&
                            state.isConnected &&
                            state.thread != null &&
                            !state.isSending &&
                            !state.isAwaitingAuthoritativeTurn &&
                            !state.isSendConfirmationPending,
                    ) {
                        if (state.isSending || state.isAwaitingAuthoritativeTurn) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(if (state.activeTurnId != null) R.string.codex_chat_steer else R.string.chat_send))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> LoadingStateCard(Modifier.padding(16.dp), stringResource(R.string.codex_opening_thread))
                state.thread == null && state.error != null -> ErrorStateCard(
                    title = stringResource(R.string.codex_open_thread_failed),
                    message = state.error.orEmpty(),
                    onRetry = viewModel::connectAndLoad,
                    modifier = Modifier.padding(16.dp),
                )
                else -> CodexTimelineViewport(
                    contentKey = listOf(timeline, state.plans, state.diffs, state.activeTurnId, state.error),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    state.error?.let { error ->
                        item("error") {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(error, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    items(timeline, key = { (turnId, item) -> "$turnId:${item.id ?: item.type}" }) { (_, item) ->
                        CodexTimelineItem(item, onOpenThread)
                    }
                    state.thread?.turns?.lastOrNull()?.id?.let { turnId ->
                        state.plans[turnId]?.let { plan -> item("plan:$turnId") { CodexTurnPlanCard(plan) } }
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
                    if (state.activeTurnId != null && timeline.none { it.second.type == "agentMessage" && it.second.text.isNullOrEmpty() }) {
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
                state.goal?.let { goal ->
                    Text(goal.objective)
                    Text(stringResource(when (goal.status) {
                        "active" -> R.string.codex_chat_goal_active
                        "complete", "completed" -> R.string.codex_chat_goal_complete
                        "paused" -> R.string.codex_chat_goal_paused
                        "blocked" -> R.string.codex_chat_goal_blocked
                        else -> R.string.codex_chat_goal_unknown
                    }))
                } ?: Text(stringResource(R.string.codex_chat_no_goal))
                state.goal?.let { goal ->
                    Text(stringResource(R.string.codex_chat_goal_usage, goal.tokensUsed, goal.tokenBudget?.toString() ?: "—", goal.timeUsedSeconds))
                }
                Text(stringResource(R.string.codex_chat_memory_value, when (state.memoryMode) {
                    CodexMemoryMode.ENABLED -> stringResource(R.string.codex_enable)
                    CodexMemoryMode.DISABLED -> stringResource(R.string.codex_disable)
                    null -> "—"
                }))
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
        selected = state.memoryMode,
        onDismiss = { memoryOpen = false },
        onSelect = { viewModel.setMemoryMode(it); memoryOpen = false },
    )
}

internal fun codexChatVisibilityForEvent(event: Lifecycle.Event): Boolean? = when (event) {
    Lifecycle.Event.ON_START -> true
    Lifecycle.Event.ON_STOP -> false
    else -> null
}

@Composable
private fun ModelControls(
    state: CodexChatUiState,
    onModel: (dev.wuxie233.codecarry.data.codex.CodexModel) -> Unit,
    onEffort: (String) -> Unit,
) {
    var modelsOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }
    if (state.models.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            TextButton(onClick = { modelsOpen = true }) {
                Text(state.selectedModel?.displayName?.ifBlank { state.selectedModel.model } ?: stringResource(R.string.codex_model), maxLines = 1)
            }
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
                TextButton(onClick = { effortOpen = true }) {
                    Icon(Icons.Default.Psychology, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(state.selectedEffort ?: stringResource(R.string.codex_reasoning))
                }
                DropdownMenu(expanded = effortOpen, onDismissRequest = { effortOpen = false }) {
                    efforts.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.reasoningEffort)
                                    if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall)
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
}

private val LocalCodexRequestEnabled = androidx.compose.runtime.compositionLocalOf { true }

@Composable
private fun CodexRequestCard(
    request: CodexServerRequest,
    thread: dev.wuxie233.codecarry.data.codex.CodexThread?,
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
            Text(
                when {
                    approval?.kind == CodexApprovalKind.COMMAND_EXECUTION -> stringResource(R.string.codex_request_run_command)
                    approval?.kind == CodexApprovalKind.FILE_CHANGE -> stringResource(R.string.codex_request_apply_files)
                    approval?.kind == CodexApprovalKind.PERMISSIONS -> stringResource(R.string.codex_request_grant_permissions)
                    userInput != null -> userInput.questions.firstOrNull()?.header ?: stringResource(R.string.codex_request_needs_input)
                    elicitation != null -> stringResource(
                        R.string.codex_request_named_needs_input,
                        request.params.string("serverName") ?: "MCP",
                    )
                    else -> stringResource(R.string.codex_request)
                },
                style = MaterialTheme.typography.titleSmall,
            )
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
                UserInputQuestions(userInput.questions, onAnswer, onCancel)
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
            properties.forEach { (name, raw) ->
                (raw as? JsonObject)?.get("default")?.let { defaultValue -> put(name, defaultValue) }
            }
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
                val checked = (values[name] as? JsonPrimitive)?.booleanOrNull
                    ?: (field["default"] as? JsonPrimitive)?.booleanOrNull ?: false
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
            onClick = {
                onSubmit(buildJsonObject {
                    properties.forEach { (name, raw) ->
                        val defaultValue = (raw as? JsonObject)?.get("default")
                        (values[name] ?: defaultValue)?.let { put(name, it) }
                    }
                })
            },
            enabled = LocalCodexRequestEnabled.current && (valid),
        ) { Text(stringResource(R.string.codex_submit)) }
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
    questions: List<dev.wuxie233.codecarry.data.codex.CodexToolUserInputQuestion>,
    onAnswer: (Map<String, List<String>>) -> Unit,
    onCancel: () -> Unit,
) {
    val values = remember { mutableStateMapOf<String, String>() }
    questions.forEach { question ->
        Text(question.question, style = MaterialTheme.typography.bodyMedium)
        if (question.options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                question.options.forEach { option ->
                    OutlinedButton(enabled = LocalCodexRequestEnabled.current,
                        onClick = { values[question.id] = option.label },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(option.label)
                            if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (question.options.isEmpty() || question.isOther) {
            OutlinedTextField(
                value = values[question.id].orEmpty(),
                onValueChange = { values[question.id] = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (question.isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (question.isSecret) KeyboardType.Password else KeyboardType.Text,
                    autoCorrectEnabled = !question.isSecret,
                ),
            )
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(enabled = LocalCodexRequestEnabled.current, onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        Button(
            onClick = { onAnswer(values.mapValues { listOf(it.value) }) },
            enabled = LocalCodexRequestEnabled.current && (questions.all { values[it.id].orEmpty().isNotBlank() }),
        ) { Text(stringResource(R.string.codex_submit)) }
    }
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
