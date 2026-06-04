package dev.minios.ocremote.ui.screens.roundtable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.data.api.PiLineupProposalItemDto
import dev.minios.ocremote.ui.screens.chat.PiSenderIdentity
import dev.minios.ocremote.ui.screens.chat.piSenderAccentColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundtableCastingScreen(
    onNavigateBack: () -> Unit,
    onConfirmedRoundtable: (RoundtableChatTarget) -> Unit,
    onManualSetup: () -> Unit,
    viewModel: RoundtableCastingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black

    BackHandler(onBack = onNavigateBack)

    LaunchedEffect(Unit) {
        viewModel.confirmedTarget.collect { target -> onConfirmedRoundtable(target) }
    }

    LaunchedEffect(uiState.messages.size, uiState.proposal?.items?.size) {
        if (uiState.messages.isNotEmpty() || uiState.proposal != null) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.roundtable_casting_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    TextButton(onClick = onManualSetup) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.roundtable_manual_setup))
                    }
                },
            )
        },
        bottomBar = {
            CastingComposer(
                uiState = uiState,
                onInputChange = viewModel::updateInput,
                onSend = viewModel::sendInput,
                onConfirm = viewModel::confirmCasting,
                modifier = Modifier.imePadding(),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (uiState.error != null) {
                    item(key = "error") {
                        CastingErrorCard(
                            message = uiState.error.orEmpty(),
                            canRetry = uiState.canRetry && !uiState.isTurnInFlight && !uiState.isConfirming,
                            onRetry = viewModel::retry,
                            isAmoled = isAmoled,
                        )
                    }
                }
                if (uiState.isTurnInFlight || uiState.isConfirming) {
                    item(key = "thinking") {
                        ModeratorThinkingCard(isConfirming = uiState.isConfirming)
                    }
                }
                uiState.proposal?.let { proposal ->
                    item(key = "proposal") {
                        CastingLineupCard(
                            items = proposal.items,
                            isAmoled = isAmoled,
                        )
                    }
                }
                items(uiState.messages.asReversed()) { message ->
                    CastingChatBubble(message = message, isAmoled = isAmoled)
                }
                if (uiState.messages.isEmpty()) {
                    item(key = "opener") {
                        CastingOpenerCard(isAmoled = isAmoled)
                    }
                }
            }
        }
    }
}

@Composable
private fun CastingOpenerCard(isAmoled: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.72f else 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(R.string.roundtable_casting_opener_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.roundtable_casting_opener_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CastingChatBubble(message: CastingChatMessage, isAmoled: Boolean) {
    val isUser = message.role == CastingMessageRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.92f),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp,
            ),
            color = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                isAmoled -> Color.Black
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            border = if (!isUser) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.72f else 0.28f)) else null,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(if (isUser) R.string.roundtable_casting_you else R.string.roundtable_casting_moderator),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CastingLineupCard(items: List<PiLineupProposalItemDto>, isAmoled: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.72f else 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.roundtable_casting_lineup_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                RoundtableBadge(stringResource(R.string.roundtable_casting_lineup_count, items.size))
            }
            Text(
                text = stringResource(R.string.roundtable_casting_lineup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { item -> item.persona.id ?: item.persona.name }) { item ->
                    CastingRoleCard(item = item, isAmoled = isAmoled)
                }
            }
        }
    }
}

@Composable
private fun CastingRoleCard(item: PiLineupProposalItemDto, isAmoled: Boolean) {
    val persona = item.persona
    val colorSeed = persona.id ?: persona.name
    val accent = piSenderAccentColor(
        PiSenderIdentity(
            id = colorSeed,
            name = persona.name,
            mbti = persona.mbti,
            role = persona.name,
            colorSeed = colorSeed,
        ),
    )
    Surface(
        modifier = Modifier.width(188.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.72f else 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(accent, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                )
                Text(
                    text = persona.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            RoundtableBadge(persona.mbti)
            Text(
                text = item.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModeratorThinkingCard(isConfirming: Boolean) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(if (isConfirming) R.string.roundtable_casting_launching else R.string.roundtable_casting_thinking),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CastingErrorCard(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    isAmoled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.48f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            if (canRetry) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun CastingComposer(
    uiState: RoundtableCastingUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = uiState.input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                enabled = !uiState.isTurnInFlight && !uiState.isConfirming && !uiState.isLoadingServer,
                label = { Text(stringResource(R.string.roundtable_casting_input_label)) },
                placeholder = { Text(stringResource(R.string.roundtable_casting_input_placeholder)) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onSend,
                    enabled = uiState.canSend,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.chat_send))
                }
                Button(
                    onClick = onConfirm,
                    enabled = uiState.canStart,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.roundtable_casting_confirm_start))
                }
            }
        }
    }
}
