package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexAccountUsageState
import dev.wuxie233.codecarry.data.codex.CodexUsageBucket
import dev.wuxie233.codecarry.data.codex.CodexUsageWindow
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun CodexFastChip(enabled: Boolean, available: Boolean, pending: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = enabled,
        enabled = available,
        onClick = onClick,
        modifier = Modifier.testTag("codex_fast_toggle"),
        label = { Text(stringResource(if (pending) R.string.codex_fast_pending else R.string.codex_fast)) },
        leadingIcon = { Icon(Icons.Default.Bolt, null, Modifier.size(16.dp)) },
    )
}

@Composable
internal fun CodexFastNotice(state: CodexChatUiState, onDismiss: () -> Unit) {
    if (state.showFastHint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.codex_fast_hint),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.close), Modifier.size(16.dp))
            }
        }
    } else if (!state.isLoading && state.selectedModel != null && !state.fastAvailable) {
        Text(
            stringResource(R.string.codex_fast_unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

internal fun CodexAccountUsageState.mainBucket(): CodexUsageBucket? =
    buckets.firstOrNull { it.id == "codex" } ?: buckets.firstOrNull()

@Composable
internal fun CodexUsageAction(state: CodexAccountUsageState, onClick: () -> Unit) {
    val remaining = state.mainBucket()?.primary?.remainingPercent
    TextButton(onClick = onClick, modifier = Modifier.testTag("codex_usage_open")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 92.dp)) {
            Text(
                if (remaining != null) stringResource(R.string.codex_usage_compact, percent(remaining.toDouble()))
                else stringResource(R.string.codex_usage),
                color = if (state.isCurrent) usageColor(remaining?.toDouble()) else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!state.isCurrent && state.updatedAtMillis != null) {
                Text(stringResource(R.string.codex_usage_stale), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexUsageSheet(state: CodexAccountUsageState, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        CodexUsageContent(state, onRefresh, Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
internal fun CodexUsageContent(state: CodexAccountUsageState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.codex_usage_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !state.isRefreshing, modifier = Modifier.testTag("codex_usage_refresh")) {
                Text(stringResource(if (state.isRefreshing) R.string.codex_usage_loading else R.string.codex_usage_refresh))
            }
        }
        Text(
            stringResource(R.string.codex_usage_scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1f, fill = false)) {
            items(state.buckets, key = { it.id }) { bucket ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(bucket.name?.takeIf { it.isNotBlank() } ?: bucket.id, style = MaterialTheme.typography.titleMedium)
                    bucket.planType?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    bucket.primary?.let { UsageWindow(it, R.string.codex_usage_primary) }
                    bucket.secondary?.let { UsageWindow(it, R.string.codex_usage_secondary) }
                    if (bucket.primary == null && bucket.secondary == null) {
                        Text(stringResource(R.string.codex_usage_no_windows), style = MaterialTheme.typography.bodySmall)
                    }
                    bucket.credits?.let { credits ->
                        val value = when {
                            credits.unlimited == true -> stringResource(R.string.codex_usage_unlimited)
                            credits.balance != null -> credits.balance
                            credits.hasCredits == false -> stringResource(R.string.codex_usage_no_credits)
                            else -> stringResource(R.string.codex_usage_unavailable)
                        }
                        Text(stringResource(R.string.codex_usage_credits, value), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                val message = when {
                    state.unsupported -> stringResource(R.string.codex_usage_unsupported)
                    state.errorMessage != null -> stringResource(R.string.codex_usage_failed)
                    state.isRefreshing && state.buckets.isEmpty() -> stringResource(R.string.codex_usage_loading)
                    state.buckets.isEmpty() -> stringResource(R.string.codex_usage_unavailable)
                    !state.isCurrent -> stringResource(R.string.codex_usage_stale)
                    else -> null
                }
                if (message != null) Text(message, style = MaterialTheme.typography.bodySmall)
                state.updatedAtMillis?.let { updated ->
                    Text(
                        stringResource(R.string.codex_usage_updated, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated))),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageWindow(window: CodexUsageWindow, fallbackLabel: Int) {
    val duration = window.windowDurationMins
    val label = when {
        duration == null || duration <= 0 -> stringResource(fallbackLabel)
        duration % 10_080L == 0L -> stringResource(R.string.codex_usage_weeks, duration / 10_080L)
        duration % 1_440L == 0L -> stringResource(R.string.codex_usage_days, duration / 1_440L)
        duration % 60L == 0L -> stringResource(R.string.codex_usage_hours, duration / 60L)
        else -> stringResource(R.string.codex_usage_minutes, duration)
    }
    val remaining = window.remainingPercent?.toDouble()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (remaining != null) stringResource(R.string.codex_usage_remaining, percent(remaining))
                else stringResource(R.string.codex_usage_unavailable),
                color = usageColor(remaining), style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (remaining != null) {
            LinearProgressIndicator(progress = { (remaining / 100).toFloat() }, modifier = Modifier.fillMaxWidth(), color = usageColor(remaining))
        }
        window.resetsAt?.let { reset ->
            Text(
                stringResource(R.string.codex_usage_resets, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(reset * 1000))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun percent(value: Double): String = NumberFormat.getPercentInstance().format(value / 100)

@Composable
private fun usageColor(remaining: Double?): Color = when {
    remaining == null -> MaterialTheme.colorScheme.onSurfaceVariant
    remaining <= 0 -> MaterialTheme.colorScheme.error
    remaining < 20 -> Color(0xFFC77C00)
    else -> MaterialTheme.colorScheme.primary
}
