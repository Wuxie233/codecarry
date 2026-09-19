package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexTurn
import kotlinx.coroutines.delay

@Composable
internal fun CodexTurnActivityHeader(
    turn: CodexTurn,
    hasActivity: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    var nowMs by remember(turn.id) { mutableLongStateOf(System.currentTimeMillis()) }
    val running = codexTurnIsRunning(turn.status)
    LaunchedEffect(turn.id, running, turn.startedAt, turn.completedAt) {
        if (running && turn.startedAt != null && turn.completedAt == null) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }
    val elapsedMs = codexTurnElapsedMs(turn, nowMs)
    val status = stringResource(when {
        running -> R.string.codex_turn_working
        turn.status == "completed" -> R.string.codex_turn_completed
        turn.status in setOf("interrupted", "cancelled", "canceled") -> R.string.codex_turn_stopped
        turn.status == "failed" -> R.string.codex_turn_failed
        else -> R.string.codex_turn_activity
    })
    val label = if (elapsedMs == null) status else {
        val seconds = elapsedMs / 1_000
        val duration = when {
            seconds >= 3_600 -> stringResource(R.string.codex_turn_duration_hours, seconds / 3_600, seconds / 60 % 60, seconds % 60)
            seconds >= 60 -> stringResource(R.string.codex_turn_duration_minutes, seconds / 60, seconds % 60)
            else -> stringResource(R.string.codex_turn_duration_seconds, seconds)
        }
        stringResource(R.string.codex_turn_status_duration, status, duration)
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (hasActivity) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        if (hasActivity) Icon(
            imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = stringResource(if (expanded) R.string.codex_timeline_collapse else R.string.codex_timeline_expand),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
