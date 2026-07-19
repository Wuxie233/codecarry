package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.screens.sessions.SessionRecentWorkItem
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionRecentWork(
    items: List<SessionRecentWorkItem>,
    onSessionClick: (sessionId: String, directory: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.sessions_recent_work),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            modifier = Modifier.testTag("session_recent_work"),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = SessionRecentWorkItem::sessionId) { item ->
                SessionRecentWorkCard(item, onSessionClick)
            }
        }
    }
}

@Composable
private fun SessionRecentWorkCard(
    item: SessionRecentWorkItem,
    onSessionClick: (sessionId: String, directory: String) -> Unit,
) {
    val statusText = when (val status = item.status) {
        SessionStatus.Idle -> stringResource(R.string.session_status_idle)
        SessionStatus.Busy -> stringResource(R.string.session_status_busy)
        is SessionStatus.Retry -> stringResource(R.string.sessions_recent_status_retry, status.attempt)
    }
    val statusColor = when (item.status) {
        SessionStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        SessionStatus.Busy -> MaterialTheme.colorScheme.primary
        is SessionStatus.Retry -> MaterialTheme.colorScheme.error
    }
    val updated = remember(item.updatedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt))
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(240.dp)
            .clickable { onSessionClick(item.sessionId, item.directory) },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = shortSessionDirectory(item.directory),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                Text(
                    text = updated,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun shortSessionDirectory(directory: String): String {
    val normalized = directory.trimEnd('/')
    if (normalized.isBlank()) return directory
    val parts = normalized.split('/').filter(String::isNotBlank)
    return when {
        parts.isEmpty() -> normalized
        parts.size == 1 -> parts.single()
        else -> ".../${parts.takeLast(2).joinToString("/")}"
    }
}
