@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.minios.ocremote.ui.screens.codex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.minios.ocremote.data.codex.CodexApprovalKind
import dev.minios.ocremote.data.codex.CodexMemoryMode
import dev.minios.ocremote.data.codex.CodexServerRequest
import dev.minios.ocremote.data.codex.CodexToolUserInputQuestion
import dev.minios.ocremote.ui.screens.chat.chatTextOverflow
import dev.minios.ocremote.data.codex.CodexThreadItem
import dev.minios.ocremote.data.codex.requestKey
import dev.minios.ocremote.ui.components.ErrorStateCard
import dev.minios.ocremote.ui.components.LoadingStateCard
import dev.minios.ocremote.ui.screens.chat.MessageMarkdownContent
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
    viewModel: CodexChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
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
    var draft by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var goalOpen by remember { mutableStateOf(false) }
    var memoryOpen by remember { mutableStateOf(false) }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var autoFollowEnabled by rememberSaveable { mutableStateOf(true) }
    val timeline = remember(state.thread) {
        state.thread?.turns.orEmpty().flatMap { turn -> turn.items.map { turn.id to it } }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= layout.totalItemsCount - 1 && last.offset + last.size <= layout.viewportEndOffset + 50
        }
    }
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress) autoFollowEnabled = false
        else if (isAtBottom) autoFollowEnabled = true
    }
    val timelineItemCount = timeline.size + state.pendingRequests.size +
        (if (state.error != null) 1 else 0) +
        (if (state.activeTurnId != null && timeline.none { it.second.type == "agentMessage" && it.second.text.isNullOrEmpty() }) 1 else 0)
    LaunchedEffect(
        timeline.size,
        timeline.lastOrNull()?.second?.text,
        timeline.lastOrNull()?.second?.output,
        state.pendingRequests.size,
        state.activeTurnId,
        state.error,
    ) {
        if (timelineItemCount > 0 && autoFollowEnabled) listState.scrollToItem(timelineItemCount - 1)
    }
    LaunchedEffect(Unit) {
        viewModel.sendResults.collect { result ->
            if (shouldClearCodexDraft(draft, result)) {
                draft = ""
                focusManager.clearFocus()
            }
        }
    }

    fun submitDraft() {
        if (
            draft.isNotBlank() &&
            !state.isLoading &&
            state.thread != null &&
            !state.isSending &&
            !state.isAwaitingAuthoritativeTurn &&
            !state.isSendConfirmationPending
        ) {
            viewModel.sendMessage(draft)
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
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.codex_thread_actions))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { controlsExpanded = !controlsExpanded },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        val selectedModel = state.selectedModel
                        Text(
                            selectedModel?.displayName?.ifBlank { selectedModel.model }
                                ?: stringResource(R.string.codex_model),
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            if (controlsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (controlsExpanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                            Modifier.size(18.dp),
                        )
                    }
                }
                if (controlsExpanded) {
                    ModelControls(
                        state = state,
                        onModel = viewModel::selectModel,
                        onEffort = viewModel::selectEffort,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.codex_message_hint)) },
                        minLines = 1,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            submitDraft()
                        }),
                    )
                    FilledIconButton(
                        onClick = if (state.activeTurnId != null) viewModel::interruptTurn else ::submitDraft,
                        enabled = state.activeTurnId != null || (draft.isNotBlank() &&
                            !state.isLoading && state.thread != null && !state.isSending &&
                            !state.isAwaitingAuthoritativeTurn && !state.isSendConfirmationPending),
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (state.activeTurnId != null) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.codex_stop_turn))
                        } else if (state.isSending || state.isAwaitingAuthoritativeTurn) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
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
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        CodexTimelineItem(item)
                    }
                    items(state.pendingRequests, key = { it.id.requestKey() }) { request ->
                        CodexRequestCard(
                            request = request,
                            thread = state.thread,
                            submitting = request.id.requestKey() in state.submittingRequestKeys,
                            onDecision = { viewModel.answerApproval(request, it) },
                            onAnswer = { viewModel.answerUserInput(request, it) },
                            onElicitation = { action, content ->
                                viewModel.answerElicitation(request, action, content)
                            },
                            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                            onCancel = { viewModel.cancelRequest(request) },
                        )
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
    onModel: (dev.minios.ocremote.data.codex.CodexModel) -> Unit,
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

@Composable
private fun CodexTimelineItem(item: CodexThreadItem) {
    when (codexTimelinePresentation(item)) {
        CodexTimelinePresentation.USER_PROMPT -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    MessageMarkdownContent(
                        markdown = item.text.orEmpty(),
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        isUser = true,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        CodexTimelinePresentation.ASSISTANT_PROSE -> MessageMarkdownContent(
            markdown = item.text.orEmpty().ifBlank { "..." },
            textColor = MaterialTheme.colorScheme.onSurface,
            isUser = false,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        )
        CodexTimelinePresentation.REASONING -> {
            val text = item.text.orEmpty().ifBlank {
                if (item.type == "plan") stringResource(R.string.codex_planning) else stringResource(R.string.codex_thinking)
            }
            CodexReasoningUnit(key = item.id ?: item.type, text = text, isPlaceholder = item.text.isNullOrBlank())
        }
        CodexTimelinePresentation.CONTEXT_NOTE -> Text(
            stringResource(R.string.codex_context_compacted),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        CodexTimelinePresentation.WORK_UNIT -> CodexToolRow(item)
    }
}

internal enum class CodexTimelinePresentation { USER_PROMPT, ASSISTANT_PROSE, REASONING, CONTEXT_NOTE, WORK_UNIT }

internal fun codexTimelinePresentation(item: CodexThreadItem): CodexTimelinePresentation = when (item.type) {
    "userMessage" -> CodexTimelinePresentation.USER_PROMPT
    "agentMessage" -> CodexTimelinePresentation.ASSISTANT_PROSE
    "reasoning", "plan" -> CodexTimelinePresentation.REASONING
    "contextCompaction" -> CodexTimelinePresentation.CONTEXT_NOTE
    else -> CodexTimelinePresentation.WORK_UNIT
}

@Composable
private fun CodexReasoningUnit(key: String, text: String, isPlaceholder: Boolean) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val state = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isPlaceholder) {
                            Modifier.semantics(mergeDescendants = true) { stateDescription = text }
                        } else {
                            Modifier
                                .clickable { expanded = !expanded }
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    stateDescription = state
                                }
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null, Modifier.size(16.dp))
                Text(
                    if (isPlaceholder) text else stringResource(R.string.codex_reasoning),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isPlaceholder) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Text(
                    text = text,
                    modifier = Modifier.fillMaxWidth().chatTextOverflow(text).padding(start = 36.dp, end = 12.dp, bottom = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

@Composable
private fun CodexToolRow(item: CodexThreadItem) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val title = when (item.type) {
        "commandExecution" -> item.command ?: stringResource(R.string.codex_tool_command)
        "fileChange" -> stringResource(R.string.codex_tool_file_changes)
        "mcpToolCall" -> stringResource(R.string.codex_tool_mcp)
        "webSearch" -> item.text ?: stringResource(R.string.codex_tool_web_search)
        "collabAgentToolCall" -> stringResource(R.string.codex_tool_collaboration)
        else -> item.type.replaceFirstChar { it.uppercase() }
    }
    val disclosureState = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .semantics(mergeDescendants = true) { role = Role.Button; stateDescription = disclosureState }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                title,
                Modifier.weight(1f).chatTextOverflow(title),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            item.status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            val details = item.output ?: item.text ?: item.raw.toString()
            Text(
                details,
                modifier = Modifier.fillMaxWidth().chatTextOverflow(details).padding(start = 36.dp, end = 12.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (item.type == "commandExecution") FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
    }
}

@Composable
private fun CodexRequestCard(
    request: CodexServerRequest,
    thread: dev.minios.ocremote.data.codex.CodexThread?,
    submitting: Boolean,
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
    val requestTitle = when {
        approval?.kind == CodexApprovalKind.COMMAND_EXECUTION -> stringResource(R.string.codex_request_run_command)
        approval?.kind == CodexApprovalKind.FILE_CHANGE -> stringResource(R.string.codex_request_apply_files)
        approval?.kind == CodexApprovalKind.PERMISSIONS -> stringResource(R.string.codex_request_grant_permissions)
        userInput != null -> userInput.questions.firstOrNull()?.header ?: stringResource(R.string.codex_request_needs_input)
        elicitation != null -> stringResource(R.string.codex_request_named_needs_input, request.params.string("serverName") ?: "MCP")
        else -> stringResource(R.string.codex_request)
    }
    val requestScope = buildList {
        approval?.command?.let(::add)
        approvalPresentation.details.forEach { add(it.value) }
    }.joinToString(". ")
    val requestState = stringResource(
        if (submitting) R.string.codex_request_submitting else R.string.codex_request_pending,
    )
    Surface(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = listOf(
                requestTitle,
                requestScope,
                requestState,
            ).filter(String::isNotBlank).joinToString(". ")
            liveRegion = LiveRegionMode.Assertive
        },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                requestTitle,
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
                        val enabled = !submitting && (!isCodexApprovalDecision(decision) || approvalPresentation.canApprove)
                        when (decision) {
                            "decline" -> OutlinedButton(onClick = { onDecision(decision) }, enabled = !submitting) {
                                Text(stringResource(R.string.codex_deny))
                            }
                            "cancel" -> OutlinedButton(onClick = { onDecision(decision) }, enabled = !submitting) {
                                Text(stringResource(R.string.codex_cancel_turn))
                            }
                            "accept" -> Button(
                                onClick = { onDecision(decision) },
                                enabled = enabled,
                            ) {
                                Text(stringResource(R.string.notification_permission_action_allow_once))
                            }
                            "acceptForSession" -> TextButton(
                                onClick = { onDecision(decision) },
                                enabled = enabled,
                            ) {
                                Text(stringResource(R.string.codex_for_session))
                            }
                        }
                    }
                }
            } else if (userInput != null) {
                UserInputQuestions(userInput.questions, submitting, onAnswer, onCancel)
            } else if (elicitation != null) {
                McpElicitationContent(request, submitting, onElicitation, onOpenUrl)
            } else {
                OutlinedButton(onClick = onCancel, enabled = !submitting) { Text(stringResource(R.string.cancel)) }
            }
            if (submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun McpElicitationContent(
    request: CodexServerRequest,
    submitting: Boolean,
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
            enabled = !submitting,
        )
        "url" -> {
            val url = request.params.string("url").orEmpty()
            Button(onClick = { onOpenUrl(url) }, enabled = !submitting && url.startsWith("https://")) {
                Text(stringResource(R.string.codex_open_link))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { onReply("cancel", null) }, enabled = !submitting) { Text(stringResource(R.string.cancel)) }
                OutlinedButton(onClick = { onReply("decline", null) }, enabled = !submitting) { Text(stringResource(R.string.codex_decline)) }
                Button(onClick = { onReply("accept", null) }, enabled = !submitting) { Text(stringResource(R.string.codex_continue)) }
            }
        }
        else -> OutlinedButton(onClick = { onReply("cancel", null) }, enabled = !submitting) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun McpForm(
    schema: JsonObject,
    onSubmit: (JsonObject) -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean = true,
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
                            enabled = enabled,
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
                    Checkbox(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = { values[name] = JsonPrimitive(it) },
                    )
                    Text(label)
                }
            }
            enumValues.isNotEmpty() -> {
                Text(label, style = MaterialTheme.typography.labelLarge)
                enumValues.forEach { (value, title) ->
                    OutlinedButton(
                        onClick = { values[name] = JsonPrimitive(value) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(title) }
                }
            }
            else -> {
                val current = (values[name] as? JsonPrimitive)?.contentOrNull
                    ?: (field["default"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                OutlinedTextField(
                    value = current,
                    enabled = enabled,
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
        TextButton(onClick = onCancel, enabled = enabled) { Text(stringResource(R.string.cancel)) }
        OutlinedButton(onClick = onDecline, enabled = enabled) { Text(stringResource(R.string.codex_decline)) }
        Button(
            onClick = {
                onSubmit(buildJsonObject {
                    properties.forEach { (name, raw) ->
                        val defaultValue = (raw as? JsonObject)?.get("default")
                        (values[name] ?: defaultValue)?.let { put(name, it) }
                    }
                })
            },
            enabled = enabled && valid,
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
    questions: List<CodexToolUserInputQuestion>,
    submitting: Boolean,
    onAnswer: (Map<String, List<String>>) -> Unit,
    onCancel: () -> Unit,
) {
    val values = remember { mutableStateMapOf<String, List<String>>() }
    val customValues = remember { mutableStateMapOf<String, String>() }
    questions.forEach { question ->
        Text(question.question, style = MaterialTheme.typography.bodyMedium)
        if (question.options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                question.options.forEach { option ->
                    val selected = option.label in values[question.id].orEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .then(
                                if (question.multiSelect) Modifier.toggleable(
                                    value = selected,
                                    enabled = !submitting,
                                    role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        val current = values[question.id].orEmpty()
                                        values[question.id] = if (checked) {
                                            question.options.map { it.label }.filter { it in current || it == option.label }
                                        } else {
                                            current - option.label
                                        }
                                    },
                                ) else Modifier.selectable(
                                    selected = selected,
                                    enabled = !submitting,
                                    role = Role.RadioButton,
                                    onClick = { values[question.id] = listOf(option.label) },
                                ),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (question.multiSelect) Checkbox(selected, null, enabled = !submitting)
                        else RadioButton(selected, null, enabled = !submitting)
                        Column(Modifier.weight(1f)) {
                            Text(option.label)
                            if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (question.options.isEmpty() || question.isOther) {
            OutlinedTextField(
                value = customValues[question.id].orEmpty(),
                onValueChange = { customValues[question.id] = it },
                enabled = !submitting,
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
        TextButton(onClick = onCancel, enabled = !submitting) { Text(stringResource(R.string.cancel)) }
        val answers = buildCodexUserInputAnswers(questions, values, customValues)
        Button(
            onClick = { onAnswer(answers) },
            enabled = !submitting && questions.all { answers[it.id].orEmpty().any(String::isNotBlank) },
        ) { Text(stringResource(R.string.codex_submit)) }
    }
}

internal fun buildCodexUserInputAnswers(
    questions: List<CodexToolUserInputQuestion>,
    selections: Map<String, List<String>>,
    customValues: Map<String, String>,
): Map<String, List<String>> = questions.associate { question ->
    val selected = question.options.map { it.label }.filter { it in selections[question.id].orEmpty() }
    val custom = customValues[question.id].orEmpty().trim().takeIf(String::isNotEmpty)
    question.id to (selected + listOfNotNull(custom))
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
