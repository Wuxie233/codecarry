package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.screens.sessions.SessionActivityFilter
import dev.minios.ocremote.ui.screens.sessions.SessionActivityGroup
import dev.minios.ocremote.ui.screens.sessions.SessionActivityGroupKind
import dev.minios.ocremote.ui.screens.sessions.SessionActivityItem
import dev.minios.ocremote.ui.screens.sessions.SessionActivityKind
import dev.minios.ocremote.ui.screens.sessions.SessionActivityQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionActivityQueueView(
    queue: SessionActivityQueue,
    filter: SessionActivityFilter,
    onFilterChange: (SessionActivityFilter) -> Unit,
    onSessionClick: (sessionId: String, directory: String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SessionActivityFilter.entries.forEach { option ->
                val count = option.countIn(queue)
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = { Text(if (count > 0) "${option.label()} $count" else option.label()) },
                )
            }
        }

        if (queue.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.sessions_activity_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("session_activity_queue"),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                queue.groups.forEach { group ->
                    stickyHeader(key = "activity_header_${group.kind}") {
                        ActivityGroupHeader(group)
                    }
                    items(group.items, key = { it.sessionId }) { item ->
                        ActivityQueueRow(item = item, onClick = onSessionClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityGroupHeader(group: SessionActivityGroup) {
    val visual = group.kind.visual()
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(visual.icon, contentDescription = null, tint = visual.color, modifier = Modifier.size(18.dp))
            Text(visual.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = group.items.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }
}

@Composable
private fun ActivityQueueRow(
    item: SessionActivityItem,
    onClick: (sessionId: String, directory: String) -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val visual = item.primaryKind.visual()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(item.sessionId, item.directory) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(visual.color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(visual.icon, contentDescription = visual.label, tint = visual.color, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.title?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.projectName?.takeIf(String::isNotBlank) ?: item.directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = dateFormat.format(Date(item.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val secondaryLabels = item.secondarySignalLabels()
        if (secondaryLabels.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.semantics {
                    stateDescription = secondaryLabels.joinToString()
                },
            ) {
                secondaryLabels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

@Composable
private fun SessionActivityItem.secondarySignalLabels(): List<String> = buildList {
    if (primaryKind != SessionActivityKind.QUESTION && signals.questionCount > 0) {
        add("${stringResource(R.string.sessions_activity_status_question)} ${signals.questionCount}")
    }
    if (primaryKind != SessionActivityKind.PERMISSION && signals.permissionCount > 0) {
        add("${stringResource(R.string.sessions_activity_status_permission)} ${signals.permissionCount}")
    }
    if (primaryKind != SessionActivityKind.RETRY && signals.hasRetry) {
        add(stringResource(R.string.sessions_activity_status_retry))
    }
    if (primaryKind != SessionActivityKind.BUSY && signals.isBusy) {
        add(stringResource(R.string.sessions_activity_status_running))
    }
    if (primaryKind != SessionActivityKind.UNREAD && signals.isUnread) {
        add(stringResource(R.string.sessions_activity_status_unread))
    }
}

private data class ActivityVisual(val label: String, val icon: ImageVector, val color: Color)

@Composable
private fun SessionActivityGroupKind.visual(): ActivityVisual = when (this) {
    SessionActivityGroupKind.PENDING_ACTION -> ActivityVisual(stringResource(R.string.sessions_activity_group_pending), Icons.AutoMirrored.Filled.HelpOutline, MaterialTheme.colorScheme.error)
    SessionActivityGroupKind.RUNNING -> ActivityVisual(stringResource(R.string.sessions_activity_group_running), Icons.Default.Sync, MaterialTheme.colorScheme.primary)
    SessionActivityGroupKind.UNREAD_COMPLETED -> ActivityVisual(stringResource(R.string.sessions_activity_group_unread), Icons.Default.MarkChatUnread, Color(0xFF1976D2))
}

@Composable
private fun SessionActivityKind.visual(): ActivityVisual = when (this) {
    SessionActivityKind.QUESTION -> ActivityVisual(stringResource(R.string.sessions_activity_status_question), Icons.AutoMirrored.Filled.HelpOutline, MaterialTheme.colorScheme.tertiary)
    SessionActivityKind.PERMISSION -> ActivityVisual(stringResource(R.string.sessions_activity_status_permission), Icons.Default.Lock, MaterialTheme.colorScheme.secondary)
    SessionActivityKind.RETRY -> ActivityVisual(stringResource(R.string.sessions_activity_status_retry), Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
    SessionActivityKind.BUSY -> ActivityVisual(stringResource(R.string.sessions_activity_status_running), Icons.Default.Sync, MaterialTheme.colorScheme.primary)
    SessionActivityKind.UNREAD -> ActivityVisual(stringResource(R.string.sessions_activity_status_unread), Icons.Default.MarkChatUnread, Color(0xFF1976D2))
}

@Composable
private fun SessionActivityFilter.label(): String = when (this) {
    SessionActivityFilter.ALL -> stringResource(R.string.sessions_activity_filter_all)
    SessionActivityFilter.PENDING -> stringResource(R.string.sessions_activity_filter_pending)
    SessionActivityFilter.BUSY -> stringResource(R.string.sessions_activity_filter_running)
    SessionActivityFilter.UNREAD -> stringResource(R.string.sessions_activity_filter_unread)
    SessionActivityFilter.RETRY -> stringResource(R.string.sessions_activity_filter_retry)
}

private fun SessionActivityFilter.countIn(queue: SessionActivityQueue): Int = when (this) {
    SessionActivityFilter.ALL -> queue.totalSessionCount
    SessionActivityFilter.PENDING -> queue.pendingSessionCount
    SessionActivityFilter.BUSY -> queue.sessionCountsByKind[SessionActivityKind.BUSY].orZero()
    SessionActivityFilter.UNREAD -> queue.sessionCountsByKind[SessionActivityKind.UNREAD].orZero()
    SessionActivityFilter.RETRY -> queue.sessionCountsByKind[SessionActivityKind.RETRY].orZero()
}

private fun Int?.orZero(): Int = this ?: 0
