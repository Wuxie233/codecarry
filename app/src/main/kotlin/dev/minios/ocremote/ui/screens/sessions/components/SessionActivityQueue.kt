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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                    text = "No activity needs attention",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
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
                text = item.title?.takeIf(String::isNotBlank) ?: "Untitled session",
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
        if (item.signals.totalCount > 1) {
            Text(
                text = item.signals.totalCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = visual.color,
                modifier = Modifier
                    .background(visual.color.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

private val dev.minios.ocremote.ui.screens.sessions.SessionActivitySignals.totalCount: Int
    get() = questionCount + permissionCount + if (hasRetry) 1 else 0 + if (isBusy) 1 else 0 + if (isUnread) 1 else 0

private data class ActivityVisual(val label: String, val icon: ImageVector, val color: Color)

@Composable
private fun SessionActivityGroupKind.visual(): ActivityVisual = when (this) {
    SessionActivityGroupKind.PENDING_ACTION -> ActivityVisual("Pending Action", Icons.AutoMirrored.Filled.HelpOutline, MaterialTheme.colorScheme.error)
    SessionActivityGroupKind.RUNNING -> ActivityVisual("Running", Icons.Default.Sync, MaterialTheme.colorScheme.primary)
    SessionActivityGroupKind.UNREAD_COMPLETED -> ActivityVisual("Unread Completed", Icons.Default.MarkChatUnread, Color(0xFF1976D2))
}

@Composable
private fun SessionActivityKind.visual(): ActivityVisual = when (this) {
    SessionActivityKind.QUESTION -> ActivityVisual("Questions", Icons.AutoMirrored.Filled.HelpOutline, MaterialTheme.colorScheme.tertiary)
    SessionActivityKind.PERMISSION -> ActivityVisual("Permissions", Icons.Default.Lock, MaterialTheme.colorScheme.secondary)
    SessionActivityKind.RETRY -> ActivityVisual("Errors", Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error)
    SessionActivityKind.BUSY -> ActivityVisual("Running", Icons.Default.Sync, MaterialTheme.colorScheme.primary)
    SessionActivityKind.UNREAD -> ActivityVisual("Unread", Icons.Default.MarkChatUnread, Color(0xFF1976D2))
}

private fun SessionActivityFilter.label(): String = when (this) {
    SessionActivityFilter.ALL -> "All"
    SessionActivityFilter.PENDING -> "Pending"
    SessionActivityFilter.BUSY -> "Running"
    SessionActivityFilter.UNREAD -> "Unread"
    SessionActivityFilter.RETRY -> "Retry"
}

private fun SessionActivityFilter.countIn(queue: SessionActivityQueue): Int = when (this) {
    SessionActivityFilter.ALL -> queue.items.size
    SessionActivityFilter.PENDING -> queue.items.count {
        it.signals.questionCount > 0 || it.signals.permissionCount > 0
    }
    SessionActivityFilter.BUSY -> queue.sessionCountsByKind[SessionActivityKind.BUSY].orZero()
    SessionActivityFilter.UNREAD -> queue.sessionCountsByKind[SessionActivityKind.UNREAD].orZero()
    SessionActivityFilter.RETRY -> queue.sessionCountsByKind[SessionActivityKind.RETRY].orZero()
}

private fun Int?.orZero(): Int = this ?: 0
